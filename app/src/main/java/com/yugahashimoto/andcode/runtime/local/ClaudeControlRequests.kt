package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.ContextBreakdown
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Control requests the app sends a running Claude Code process and waits on. The answer comes back
 * on the process's output among the turn's own lines, as a `control_response` carrying the
 * request's id, and is handed to the request waiting on that id.
 */
internal class ClaudeControlRequests(
    private val json: Json,
) {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject?>>()

    /**
     * Sends a request of [subtype] through [write] and waits up to [timeoutMillis] for its answer.
     * Null when it could not be sent, the CLI refused it, or no answer came in time.
     */
    suspend fun ask(
        subtype: String,
        timeoutMillis: Long,
        write: (String) -> Boolean,
    ): JsonObject? {
        val id = "$subtype-${UUID.randomUUID()}"
        val answer = CompletableDeferred<JsonObject?>()
        pending[id] = answer
        return try {
            val line = json.encodeToString(JsonObject.serializer(), request(id, subtype))
            if (write(line)) withTimeoutOrNull(timeoutMillis) { answer.await() } else null
        } finally {
            pending.remove(id)
        }
    }

    /**
     * Hands a `control_response` line to the request waiting on it. True when [line] was one (the
     * answer to an interrupt included), so the turn's reader can skip it.
     */
    fun answer(line: String): Boolean {
        val root = if (line.contains(CONTROL_RESPONSE)) runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() else null
        if (root?.string("type") != CONTROL_RESPONSE) return false
        val response = root["response"] as? JsonObject
        val id = response?.string("request_id")
        if (id != null) {
            pending[id]?.complete(response.takeIf { it.string("subtype") == "success" }?.get("response") as? JsonObject)
        }
        return true
    }

    private fun request(
        id: String,
        subtype: String,
    ): JsonObject =
        JsonObject(
            mapOf(
                "type" to JsonPrimitive("control_request"),
                "request_id" to JsonPrimitive(id),
                "request" to JsonObject(mapOf("subtype" to JsonPrimitive(subtype))),
            ),
        )

    companion object {
        const val CONTEXT_USAGE = "get_context_usage"
        private const val CONTROL_RESPONSE = "control_response"

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

        private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toLong()

        private fun JsonObject.objects(key: String): List<JsonObject> =
            (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

        private fun JsonObject.isLoaded(): Boolean = (this["isLoaded"] as? JsonPrimitive)?.contentOrNull != "false"

        /**
         * Reads the answer to [CONTEXT_USAGE]: the window's categories, the MCP tools grouped by
         * server, and the memory files. Null when it carries no window size, so there is nothing to
         * measure against.
         */
        fun contextBreakdown(answer: JsonObject): ContextBreakdown? {
            val max = answer.long("maxTokens")?.takeIf { it > 0 } ?: return null
            val categories =
                answer.objects("categories").mapNotNull { category ->
                    val tokens = category.long("tokens") ?: return@mapNotNull null
                    ContextBreakdown.Category(
                        id = category.string("id"),
                        name = category.string("name").orEmpty(),
                        tokens = tokens,
                        kind =
                            when (category.string("kind")) {
                                "deferred" -> ContextBreakdown.Kind.DEFERRED
                                "buffer" -> ContextBreakdown.Kind.BUFFER
                                "free" -> ContextBreakdown.Kind.FREE
                                else -> ContextBreakdown.Kind.USED
                            },
                    )
                }
            // A deferred tool is in the window by name only, so only loaded ones count toward a
            // server's share; a server with none loaded still shows, at zero, with its tool count.
            val servers =
                answer
                    .objects("mcpTools")
                    .groupBy { it.string("serverName").orEmpty() }
                    .map { (server, tools) ->
                        ContextBreakdown.McpServerShare(
                            name = server,
                            tokens = tools.filter { it.isLoaded() }.sumOf { it.long("tokens") ?: 0L },
                            tools = tools.size,
                        )
                    }.sortedByDescending { it.tokens }
            val enabled = (answer["isAutoCompactEnabled"] as? JsonPrimitive)?.contentOrNull == "true"
            return ContextBreakdown(
                usedTokens = answer.long("totalTokens") ?: categories.filter { it.kind == ContextBreakdown.Kind.USED }.sumOf { it.tokens },
                maxTokens = max,
                categories = categories,
                mcpServers = servers,
                memoryFiles =
                    answer.objects("memoryFiles").mapNotNull { file ->
                        file.string("path")?.let { ContextBreakdown.MemoryFile(it, file.long("tokens") ?: 0L) }
                    },
                autoCompactAt = answer.long("autoCompactThreshold")?.takeIf { enabled },
            )
        }
    }
}
