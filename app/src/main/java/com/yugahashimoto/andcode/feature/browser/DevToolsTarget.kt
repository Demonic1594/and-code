package com.yugahashimoto.andcode.feature.browser

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** One page (or worker) of this app's WebViews, as WebView's DevTools lists it at `/json`. */
internal data class DevToolsTarget(
    val id: String,
    val type: String,
    val url: String,
)

/** The targets in a `/json` listing; anything that is not one reads as none. */
internal fun parseDevToolsTargets(body: String): List<DevToolsTarget> {
    val array = runCatching { Json.parseToJsonElement(body) as? JsonArray }.getOrNull() ?: return emptyList()
    return array.mapNotNull { element ->
        val target = element as? JsonObject ?: return@mapNotNull null

        fun field(name: String): String? = runCatching { target[name]?.jsonPrimitive?.contentOrNull }.getOrNull()
        val id = field("id") ?: return@mapNotNull null
        DevToolsTarget(id = id, type = field("type").orEmpty(), url = field("url").orEmpty())
    }
}

/** The id of the one page showing [marker] ([HiddenPageMarker]), or null when none does or two do. */
internal fun findMarkedTarget(
    targets: List<DevToolsTarget>,
    marker: String,
): String? = targets.filter { it.type in PAGE_TYPES && it.url == marker }.singleOrNull()?.id

/**
 * The body of the HTTP response on [input], read to its Content-Length (or its chunks, or the end of
 * the stream when it has neither): WebView's DevTools server keeps the connection open after a
 * response, so reading to the end would wait for a timeout. Null for anything but 200 OK.
 */
internal fun readHttpBody(input: InputStream): String? {
    val head = StringBuilder()
    while (!head.endsWith("\r\n\r\n")) {
        val byte = input.read()
        if (byte < 0 || head.length > MAX_HEAD_BYTES) return null
        head.append(byte.toChar())
    }
    val lines = head.lines()
    if (lines.firstOrNull()?.split(" ")?.getOrNull(1) != "200") return null

    fun header(name: String): String? =
        lines.drop(1).firstOrNull { it.startsWith("$name:", ignoreCase = true) }?.substringAfter(':')?.trim()
    val length = header("Content-Length")?.toIntOrNull()?.takeIf { it >= 0 }
    val bytes =
        when {
            length != null -> readExactly(input, length)
            header("Transfer-Encoding").equals("chunked", ignoreCase = true) -> readChunks(input)
            else -> input.readBytes()
        }
    return bytes.decodeToString()
}

private fun readExactly(
    input: InputStream,
    length: Int,
): ByteArray {
    val buffer = ByteArray(length)
    var read = 0
    while (read < length) {
        val count = input.read(buffer, read, length - read)
        if (count < 0) break
        read += count
    }
    return buffer.copyOf(read)
}

private fun readChunks(input: InputStream): ByteArray {
    val out = ByteArrayOutputStream()
    // Each chunk is its size in hex on a line, then that many bytes; a size of 0 (or none) ends them.
    var size = nextChunkSize(input)
    while (size > 0) {
        out.write(readExactly(input, size))
        readLine(input)
        size = nextChunkSize(input)
    }
    return out.toByteArray()
}

private fun nextChunkSize(input: InputStream): Int = readLine(input)?.substringBefore(';')?.trim()?.toIntOrNull(radix = 16) ?: 0

private fun readLine(input: InputStream): String? {
    val line = StringBuilder()
    while (true) {
        val byte = input.read()
        if (byte < 0) return line.takeIf { it.isNotEmpty() }?.toString()
        if (byte == '\n'.code) return line.toString().removeSuffix("\r")
        line.append(byte.toChar())
    }
}

/**
 * Lists this app's WebView pages through its own DevTools socket, `webview_devtools_remote_<pid>`:
 * the one the guest's browser tool connects to, open while WebView debugging is on. Blocking; call it
 * off the main thread. Null when the socket is closed or does not answer.
 */
internal fun listOwnDevToolsTargets(): List<DevToolsTarget>? =
    try {
        LocalSocket().use { socket ->
            socket.connect(LocalSocketAddress("webview_devtools_remote_${Process.myPid()}", LocalSocketAddress.Namespace.ABSTRACT))
            socket.soTimeout = SOCKET_TIMEOUT_MILLIS
            socket.outputStream.write("GET /json HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
            readHttpBody(socket.inputStream)?.let(::parseDevToolsTargets)
        }
    } catch (error: IOException) {
        null
    }

private val PAGE_TYPES = setOf("page", "document")
private const val MAX_HEAD_BYTES = 16 * 1024
private const val SOCKET_TIMEOUT_MILLIS = 5_000
