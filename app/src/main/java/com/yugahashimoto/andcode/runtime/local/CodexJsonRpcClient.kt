package com.yugahashimoto.andcode.runtime.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Speaks Codex's `app-server` JSON-RPC protocol: one JSON object per line on stdin/stdout, request
 * ids correlate a response to its call, and everything without an `id` is a notification.
 *
 * Verified by hand against the real `codex app-server` binary (0.155.1): `initialize` returns
 * immediately with `{userAgent, codexHome, platformFamily, platformOs}`, an out-of-band
 * `configWarning`/`remoteControl/status/changed` notification follows unprompted, and a JSON-RPC
 * error comes back as `{"error": {"code", "message"}, "id"}` - no `Content-Length` framing, unlike
 * LSP. There is no protocol-version negotiation in `initialize`, so none is sent here either.
 */
class CodexJsonRpcClient(
    private val output: OutputStream,
    private val onNotification: (method: String, params: JsonElement?) -> Unit,
    /**
     * Serializes every write to [output]. A constructor parameter (not a class body property) so
     * [onServerRequest]'s own default value below can use it: a default parameter expression can only
     * reference earlier constructor parameters, not class body members - the same restriction that
     * already keeps that default from calling the [respond]/[respondError] instance methods directly.
     */
    private val writeLock: Mutex = Mutex(),
    /**
     * A request the server sent to us (an approval prompt, most often): unlike a notification it
     * carries an `id` and expects a matching [respond] or [respondError] call, but not necessarily
     * before this callback returns - the app answers these from user input, which can take a while.
     */
    private val onServerRequest: (id: JsonElement, method: String, params: JsonElement?) -> Unit = { id, method, _ ->
        // No approval UI wired up: refuse rather than hang the server waiting for a reply forever, in
        // the exact wire shape respond()/respondError() would produce. Routed through writeLock via
        // runBlocking (this callback runs synchronously on the reader thread, not inside a coroutine)
        // so it cannot interleave with a call()/notify()/respond() write and corrupt the
        // newline-delimited JSON stream. Unreached in production, where CodexRuntime always supplies
        // its own onServerRequest, but tests and future callers still go through it.
        runCatching {
            runBlocking {
                writeLock.withLock {
                    output.write(
                        """{"jsonrpc":"2.0","id":$id,"error":{"code":-32601,"message":"No server-request handler installed for $method"}}""".toByteArray(),
                    )
                    output.write("\n".toByteArray())
                    output.flush()
                }
            }
        }
    },
    private val onClientError: (Throwable) -> Unit = {},
    /**
     * Invoked when a write to [output] overruns [WRITE_TIMEOUT_MS] - the app-server is alive but
     * no longer reading stdin, and the only cure is tearing the process down so the reader loop
     * ends and a fresh server replaces it. The hook lets the owner of the process react; the
     * write still throws so the caller fails like any other error.
     */
    private val onWriteFailure: () -> Unit = {},
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        },
) {
    private val nextId = AtomicLong(1)

    /**
     * A plain concurrent map, not a coroutine [Mutex]: [receiveLine] runs synchronously on a
     * reader thread and must read this without suspending, while [call] writes to it from whatever
     * coroutine sent the request.
     */
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()

    class RpcError(val code: Long, message: String) : Exception(message)

    /**
     * Sends [method] with [params] and suspends for the matching response's `result`.
     *
     * The wait is bounded by [timeoutMillis]: an app-server that is alive but never answering (a
     * wedged PRoot guest, a hung boot) otherwise left the call suspended forever - and every
     * coroutine queued behind CodexRuntime's server mutex with it, each still holding its request
     * params - until the process happened to die. A timeout fails the call like any other error;
     * a reply that lands after the timeout finds the id already gone from [pending] and is
     * dropped by [receiveLine].
     */
    suspend fun call(
        method: String,
        params: JsonElement? = null,
        timeoutMillis: Long = DEFAULT_CALL_TIMEOUT_MS,
    ): JsonObject {
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred
        try {
            val message =
                buildJsonObject {
                    put("jsonrpc", JsonPrimitive("2.0"))
                    put("id", JsonPrimitive(id))
                    put("method", JsonPrimitive(method))
                    // Always present: the app-server rejects a request with no `params` at all
                    // ("missing field `params`"), even for methods that take no arguments.
                    put("params", params ?: JsonObject(emptyMap()))
                }
            // writeLine() itself is inside the try, not just deferred.await(): a write that throws
            // (a broken pipe from an already-dead process) must still remove this id from `pending`,
            // or it leaks there until something else happens to call failPending() on this client.
            writeLine(message)
            return withTimeout(timeoutMillis) { deferred.await() }
        } finally {
            pending.remove(id)
        }
    }

    /** Sends a notification (no reply expected). */
    suspend fun notify(
        method: String,
        params: JsonElement? = null,
    ) {
        val message =
            buildJsonObject {
                put("jsonrpc", JsonPrimitive("2.0"))
                put("method", JsonPrimitive(method))
                params?.let { put("params", it) }
            }
        writeLine(message)
    }

    private suspend fun writeLine(message: JsonObject) {
        val bytes = (json.encodeToString(JsonObject.serializer(), message) + "\n").toByteArray(Charsets.UTF_8)
        // The write is bounded, lock and stream alike. A wedged app-server that stopped reading
        // stdin filled the pipe and blocked this write forever - while it held the write lock, so
        // every later call, notification and permission response queued behind the mutex with NO
        // timeout of their own (they never reached the write, so their call timeout never even
        // started). The timeout converts the wedge into a failure callers can see; the process
        // teardown [onWriteFailure] triggers is what actually recovers the connection.
        try {
            withTimeout(WRITE_TIMEOUT_MS) {
                writeLock.withLock {
                    withContext(Dispatchers.IO) {
                        output.write(bytes)
                        output.flush()
                    }
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            onWriteFailure()
            throw IOException("Codex app-server stopped reading stdin (write timed out)", timeout)
        }
    }

    /**
     * Feeds one line of the server's stdout; call this from a dedicated reader loop.
     *
     * A line's shape decides what it is, not merely whether `id` is present: a server-initiated
     * request (an approval prompt) carries both `method` and `id`, so `method` is checked first.
     * Only a bare `id` - a response or error to a call this client made - falls through to the
     * pending-call table.
     */
    fun receiveLine(line: String) {
        if (line.isBlank()) return
        val root =
            runCatching { json.parseToJsonElement(line).jsonObject }.getOrElse {
                onClientError(it)
                return
            }
        // One bad line must cost exactly that line. A dispatch that throws - a handler tripping
        // over an unexpected notification shape - used to unwind into the reader loop's own
        // catch and end the whole app-server: every chat's in-flight turn failed and the process
        // was abandoned mid-stream. Contained here, the stream keeps reading.
        runCatching {
            dispatchLine(root)
        }.onFailure { error -> onClientError(error) }
    }

    private fun dispatchLine(root: JsonObject) {
        val method = (root["method"] as? JsonPrimitive)?.content
        val id = root["id"]
        if (method != null) {
            if (id != null) {
                onServerRequest(id, method, root["params"])
            } else {
                onNotification(method, root["params"])
            }
            return
        }
        val ourId = (id as? JsonPrimitive)?.longOrNullCompat() ?: return
        val deferred = pending[ourId] ?: return
        val error = root["error"]?.jsonObject
        if (error != null) {
            val code = (error["code"] as? JsonPrimitive)?.longOrNullCompat() ?: -1
            val message = (error["message"] as? JsonPrimitive)?.content ?: "Codex app-server error"
            deferred.completeExceptionally(RpcError(code, message))
        } else {
            deferred.complete(root["result"]?.jsonObject ?: JsonObject(emptyMap()))
        }
    }

    /** Replies to a request the server sent us (see [onServerRequest]) with a successful [result]. */
    suspend fun respond(
        id: JsonElement,
        result: JsonElement,
    ) {
        writeLine(
            buildJsonObject {
                put("jsonrpc", JsonPrimitive("2.0"))
                put("id", id)
                put("result", result)
            },
        )
    }

    /** Replies to a request the server sent us (see [onServerRequest]) with an error. */
    suspend fun respondError(
        id: JsonElement,
        code: Long,
        message: String,
    ) {
        writeLine(
            buildJsonObject {
                put("jsonrpc", JsonPrimitive("2.0"))
                put("id", id)
                put(
                    "error",
                    buildJsonObject {
                        put("code", JsonPrimitive(code))
                        put("message", JsonPrimitive(message))
                    },
                )
            },
        )
    }

    /** Fails every call still awaiting a reply, e.g. because the process died. */
    fun failPending(cause: Throwable) {
        pending.values.forEach { it.completeExceptionally(cause) }
        pending.clear()
    }

    private fun JsonPrimitive.longOrNullCompat(): Long? = runCatching { long }.getOrNull()

    companion object {
        /**
         * Generous for the metadata-sized calls that make up almost every request (thread and
         * config reads, MCP reloads): slow is fine, forever is not.
         */
        const val DEFAULT_CALL_TIMEOUT_MS: Long = 60_000L

        /**
         * `initialize` starts the conversation, so on a cold boot it pays for the app-server's own
         * startup inside PRoot before it can answer - easily the slowest call the client makes.
         */
        const val INITIALIZE_TIMEOUT_MS: Long = 120_000L

        /**
         * stdin of a healthy app-server never blocks for long - requests are KB-sized against a
         * 64 KB pipe - so half a minute of write stall means the guest has wedged. See
         * [writeLine].
         */
        const val WRITE_TIMEOUT_MS: Long = 30_000L
    }
}

/**
 * Runs a reader loop over [lines], feeding [client] until the source ends or [scope] is cancelled.
 *
 * [onEnded] is skipped when this coroutine was cancelled (a deliberate stop, e.g. the app tearing
 * the process down itself): the same distinction [ClaudeCodeRuntime]'s own reader job makes, so a
 * caller does not have to tell an unrequested crash apart from its own shutdown to decide whether an
 * in-flight turn needs to be reported as failed.
 */
fun launchCodexReaderLoop(
    scope: CoroutineScope,
    lines: Sequence<String>,
    client: CodexJsonRpcClient,
    onEnded: suspend (Throwable?) -> Unit,
): Job =
    scope.launch(Dispatchers.IO) {
        val failure = runCatching { lines.forEach(client::receiveLine) }.exceptionOrNull()
        if (failure is CancellationException || !isActive) return@launch
        onEnded(failure)
    }
