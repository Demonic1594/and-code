package com.yugahashimoto.andcode.feature.browser.devtools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** How a console line is shown: its colour, icon and filter chip. */
enum class ConsoleLevel {
    LOG,
    INFO,
    WARN,
    ERROR,
    DEBUG,

    /** What the user typed at the console prompt. */
    INPUT,

    /** What the page answered to it. */
    RESULT,
}

data class ConsoleEntry(
    val id: Long,
    val level: ConsoleLevel,
    /** Each logged value, as the page agent serialized it (see `serialize` in andcode-devtools.js). */
    val args: List<JsonElement>,
    /** `file:line` the call came from, when the page agent could tell. */
    val source: String = "",
    val stack: String = "",
    val timestamp: Long = 0,
    /** Nesting from console.group, for indentation. */
    val depth: Int = 0,
    /** How many identical lines in a row this one stands for. */
    val repeat: Int = 1,
    /** The iframe it came from, or empty for the top page. */
    val frame: String = "",
) {
    /** The line as plain text, for search, copy and collapsing repeats. */
    val text: String by lazy { args.joinToString(" ") { previewOf(it) } }
}

data class Header(
    val name: String,
    val value: String,
)

data class WebSocketFrame(
    /** "in", "out" or "close". */
    val direction: String,
    val data: String,
    val timestamp: Long,
)

data class NetworkEntry(
    val id: String,
    /** fetch, xhr, websocket, or a resource initiator (script, img, css, link, navigation…). */
    val kind: String,
    val method: String,
    val url: String,
    val startedAt: Long = 0,
    val status: Int? = null,
    val statusText: String = "",
    val requestHeaders: List<Header> = emptyList(),
    val requestBody: String? = null,
    val responseHeaders: List<Header> = emptyList(),
    val responseBody: String? = null,
    val mime: String = "",
    val size: Long? = null,
    val durationMs: Double? = null,
    val error: String? = null,
    val frames: List<WebSocketFrame> = emptyList(),
    /** Set for what resource timing reports: it has finished even when its status is unknown. */
    val completed: Boolean = false,
) {
    val finished: Boolean get() = completed || status != null || error != null
    val failed: Boolean get() = error != null || (status != null && status >= 400)

    /** The last path segment, as the request list labels it. */
    val name: String
        get() {
            val path = url.substringBefore('?').substringBefore('#').trimEnd('/')
            val last = path.substringAfterLast('/')
            return last.ifEmpty { url.substringAfter("://").substringBefore('/') }.ifEmpty { url }
        }

    /** Which filter chip it falls under. */
    val category: NetworkCategory
        get() =
            when {
                kind == "fetch" || kind == "xhr" -> NetworkCategory.FETCH
                kind == "websocket" -> NetworkCategory.WS
                kind == "navigation" || kind == "iframe" -> NetworkCategory.DOC
                kind == "script" -> NetworkCategory.JS
                kind == "css" || kind == "link" && url.substringBefore('?').endsWith(".css") -> NetworkCategory.CSS
                kind == "img" || kind == "image" || IMAGE_EXTENSION.containsMatchIn(url.substringBefore('?')) -> NetworkCategory.IMG
                FONT_EXTENSION.containsMatchIn(url.substringBefore('?')) -> NetworkCategory.FONT
                else -> NetworkCategory.OTHER
            }
}

enum class NetworkCategory { FETCH, DOC, JS, CSS, IMG, FONT, WS, OTHER }

private val IMAGE_EXTENSION = Regex("""\.(png|jpe?g|gif|webp|svg|ico|avif|bmp)$""", RegexOption.IGNORE_CASE)
private val FONT_EXTENSION = Regex("""\.(woff2?|ttf|otf|eot)$""", RegexOption.IGNORE_CASE)

/** One node of the Elements tree, as the page agent summarizes it. */
data class DomNode(
    val id: Int,
    /** DOM nodeType: 1 element, 3 text, 8 comment. */
    val type: Int,
    val tag: String = "",
    val attributes: List<Header> = emptyList(),
    val text: String? = null,
    val childCount: Int = 0,
)

data class CssRule(
    val selector: String,
    val css: String,
    val source: String,
)

data class BoxModel(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    /** top, right, bottom, left. */
    val margin: List<Double>,
    val padding: List<Double>,
    val border: List<Double>,
)

data class ElementDetails(
    val id: Int,
    val tag: String,
    val attributes: List<Header>,
    val inlineStyle: String,
    val rules: List<CssRule>,
    val computed: List<Header>,
    val box: BoxModel?,
    val outerHtml: String,
    val text: String,
)

data class StorageSnapshot(
    val origin: String,
    val local: List<Header>,
    val session: List<Header>,
    val cookies: List<Header>,
)

data class NavigationTiming(
    val type: String,
    val redirect: Double,
    val dns: Double,
    val connect: Double,
    val ttfb: Double,
    val download: Double,
    val domInteractive: Double,
    val domContentLoaded: Double,
    val load: Double,
    val transfer: Long,
    val decoded: Long,
)

data class PerformanceSnapshot(
    val url: String,
    val title: String,
    val navigation: NavigationTiming?,
    val firstPaint: Double?,
    val firstContentfulPaint: Double?,
    val heapUsed: Long?,
    val heapLimit: Long?,
    val nodeCount: Int,
    val viewportWidth: Int,
    val viewportHeight: Int,
    val devicePixelRatio: Double,
    val userAgent: String,
    val resourceCount: Int,
    val resourceBytes: Long,
)

data class PageSource(
    /** "script" or "style". */
    val kind: String,
    val url: String,
    /** The text of an inline block; null for an external file, which is fetched when opened. */
    val inline: String?,
)

/** What the page agent posts on its own, as it happens. */
sealed interface DevToolsEvent {
    data class Console(
        val level: String,
        val args: List<JsonElement>,
        val source: String,
        val timestamp: Long,
        val frame: String,
    ) : DevToolsEvent

    data class PageError(
        val kind: String,
        val message: String,
        val source: String,
        val stack: String,
        val timestamp: Long,
        val frame: String,
    ) : DevToolsEvent

    data class NetworkStart(
        val entry: NetworkEntry,
    ) : DevToolsEvent

    data class NetworkEnd(
        val id: String,
        val status: Int?,
        val statusText: String,
        val responseHeaders: List<Header>,
        val responseBody: String?,
        val mime: String,
        val size: Long?,
        val durationMs: Double?,
        val error: String?,
    ) : DevToolsEvent

    data class WebSocketMessage(
        val id: String,
        val frame: WebSocketFrame,
    ) : DevToolsEvent

    data class Resources(
        val entries: List<NetworkEntry>,
    ) : DevToolsEvent

    data class Inspect(
        val id: Int,
        /** Element ids from <html> down to the picked one, so the tree can open along the way. */
        val path: List<Int>,
    ) : DevToolsEvent

    data class EvalSettled(
        val token: Int,
        val ok: Boolean,
        val value: JsonElement,
    ) : DevToolsEvent

    data class Ready(
        val url: String,
        val title: String,
        val frame: String,
    ) : DevToolsEvent

    /** The answer to a [DevToolsBridge.fetchText] request. */
    data class Text(
        val token: Int,
        val ok: Boolean,
        val text: String,
    ) : DevToolsEvent
}

internal val devToolsJson = Json { ignoreUnknownKeys = true }

/** Parses everything the page agent sends or returns. Each reader returns null for what it cannot read. */
object DevToolsProtocol {
    /**
     * Everything in one message from the page agent: a single event, or a batch of them. The frame
     * each came from is decided here, from what the WebView says sent it, never from the message: a
     * page could otherwise claim to be the top page and reset its parent's log.
     */
    fun parseEvents(
        raw: String,
        fromMainFrame: Boolean,
        origin: String,
    ): List<DevToolsEvent> {
        val message = parseObject(raw) ?: return emptyList()
        val items =
            if ((message["t"] as? JsonPrimitive)?.contentOrNull == "batch") {
                (message["items"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            } else {
                listOf(message)
            }
        val frame = if (fromMainFrame) "" else origin.ifEmpty { "iframe" }
        return items.mapNotNull { item -> parseEvent(item)?.inFrame(frame) }
    }

    private fun DevToolsEvent.inFrame(frame: String): DevToolsEvent =
        when (this) {
            is DevToolsEvent.Console -> copy(frame = frame)
            is DevToolsEvent.PageError -> copy(frame = frame)
            is DevToolsEvent.Ready -> copy(frame = frame)
            else -> this
        }

    fun parseEvent(raw: String): DevToolsEvent? = parseObject(raw)?.let(::parseEvent)

    private fun parseEvent(message: JsonObject): DevToolsEvent? {
        val frame = message.string("frame")
        val timestamp = message.long("ts") ?: 0L
        return when (message.string("t")) {
            "console" ->
                DevToolsEvent.Console(
                    level = message.string("level").ifEmpty { "log" },
                    args = (message["args"] as? JsonArray)?.toList().orEmpty(),
                    source = message.string("source"),
                    timestamp = timestamp,
                    frame = frame,
                )
            "error" ->
                DevToolsEvent.PageError(
                    kind = message.string("kind"),
                    message = message.string("message"),
                    source = message.string("source"),
                    stack = message.string("stack"),
                    timestamp = timestamp,
                    frame = frame,
                )
            "net" -> parseNetwork(message, timestamp)
            "ws" ->
                DevToolsEvent.WebSocketMessage(
                    id = message.string("id"),
                    frame = WebSocketFrame(message.string("dir"), message.string("data"), timestamp),
                )
            "resources" ->
                DevToolsEvent.Resources(
                    (message["entries"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::resourceEntry) },
                )
            "inspect" ->
                message.int("id")?.let { id ->
                    DevToolsEvent.Inspect(id, (message["path"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.intOrNull })
                }
            "evalAsync" ->
                message.int("token")?.let { token ->
                    DevToolsEvent.EvalSettled(token, message.boolean("ok") ?: false, message["value"] ?: JsonNull)
                }
            "ready" -> DevToolsEvent.Ready(message.string("url"), message.string("title"), frame)
            "text" -> message.int("token")?.let { DevToolsEvent.Text(it, message.boolean("ok") ?: false, message.string("text")) }
            else -> null
        }
    }

    private fun parseNetwork(
        message: JsonObject,
        timestamp: Long,
    ): DevToolsEvent? {
        val id = message.string("id").ifEmpty { return null }
        return when (message.string("phase")) {
            "start" ->
                DevToolsEvent.NetworkStart(
                    NetworkEntry(
                        id = id,
                        kind = message.string("kind").ifEmpty { "fetch" },
                        method = message.string("method").ifEmpty { "GET" },
                        url = message.string("url"),
                        startedAt = timestamp,
                        requestHeaders = headers(message["reqHeaders"]),
                        requestBody = message.stringOrNull("reqBody"),
                    ),
                )
            "end", "error" ->
                DevToolsEvent.NetworkEnd(
                    id = id,
                    status = message.int("status"),
                    statusText = message.string("statusText"),
                    responseHeaders = headers(message["resHeaders"]),
                    responseBody = message.stringOrNull("resBody"),
                    mime = message.string("mime"),
                    size = message.long("size"),
                    durationMs = message.double("duration"),
                    error = message.stringOrNull("error"),
                )
            else -> null
        }
    }

    private fun resourceEntry(entry: JsonObject): NetworkEntry? {
        val url = entry.string("url").ifEmpty { return null }
        val start = entry.double("start") ?: 0.0
        return NetworkEntry(
            id = "res:$url:$start",
            kind = entry.string("initiator").ifEmpty { "other" },
            method = "GET",
            url = url,
            startedAt = start.toLong(),
            // 0 means the browser does not know (cross-origin without Timing-Allow-Origin, or too old
            // to report it): shown as unknown, never as a success it may not have been.
            status = entry.int("status")?.takeIf { it > 0 },
            completed = true,
            size = (entry.long("transfer")?.takeIf { it > 0 } ?: entry.long("decoded"))?.takeIf { it > 0 },
            durationMs = entry.double("duration"),
        )
    }

    /** The answer to `__andcodeDT.evaluate`. */
    fun parseEvaluation(raw: String?): DevToolsEvaluation? {
        val result = parseObject(raw ?: return null) ?: return null
        return DevToolsEvaluation(
            ok = result.boolean("ok") ?: false,
            value = result["value"] ?: JsonNull,
            pendingToken = if (result.boolean("pending") == true) result.int("token") else null,
        )
    }

    fun parseRoot(raw: String?): Pair<String?, DomNode>? {
        val result = parseObject(raw ?: return null) ?: return null
        val root = (result["root"] as? JsonObject)?.let(::domNode) ?: return null
        return result.stringOrNull("doctype") to root
    }

    /** The root's children come with it, so the tree opens on <head> and <body>. */
    fun parseRootChildren(raw: String?): List<DomNode> {
        val result = parseObject(raw ?: return emptyList()) ?: return emptyList()
        val root = result["root"] as? JsonObject ?: return emptyList()
        return (root["children"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::domNode) }
    }

    fun parseChildren(raw: String?): List<DomNode> = parseArray(raw).mapNotNull { (it as? JsonObject)?.let(::domNode) }

    fun parseDetails(raw: String?): ElementDetails? {
        val details = parseObject(raw ?: return null) ?: return null
        val box = details["box"] as? JsonObject
        return ElementDetails(
            id = details.int("id") ?: return null,
            tag = details.string("tag"),
            attributes = headers(details["attrs"]),
            inlineStyle = details.string("inline"),
            rules =
                (details["rules"] as? JsonArray).orEmpty().mapNotNull { rule ->
                    (rule as? JsonObject)?.let { CssRule(it.string("selector"), it.string("css"), it.string("source")) }
                },
            computed = headers(details["computed"]),
            box =
                box?.let {
                    BoxModel(
                        x = it.double("x") ?: 0.0,
                        y = it.double("y") ?: 0.0,
                        width = it.double("width") ?: 0.0,
                        height = it.double("height") ?: 0.0,
                        margin = doubles(it["margin"]),
                        padding = doubles(it["padding"]),
                        border = doubles(it["border"]),
                    )
                },
            outerHtml = details.string("outerHTML"),
            text = details.string("text"),
        )
    }

    fun parseStorage(raw: String?): StorageSnapshot? {
        val storage = parseObject(raw ?: return null) ?: return null
        return StorageSnapshot(
            origin = storage.string("origin"),
            local = headers(storage["local"]),
            session = headers(storage["session"]),
            cookies = headers(storage["cookies"]),
        )
    }

    fun parsePerformance(raw: String?): PerformanceSnapshot? {
        val perf = parseObject(raw ?: return null) ?: return null
        val nav = perf["nav"] as? JsonObject
        val paints = perf["paints"] as? JsonObject
        val memory = perf["memory"] as? JsonObject
        val viewport = perf["viewport"] as? JsonObject
        val resources = (perf["resources"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        return PerformanceSnapshot(
            url = perf.string("url"),
            title = perf.string("title"),
            navigation =
                nav?.let {
                    NavigationTiming(
                        type = it.string("type"),
                        redirect = it.double("redirect") ?: 0.0,
                        dns = it.double("dns") ?: 0.0,
                        connect = it.double("connect") ?: 0.0,
                        ttfb = it.double("ttfb") ?: 0.0,
                        download = it.double("download") ?: 0.0,
                        domInteractive = it.double("domInteractive") ?: 0.0,
                        domContentLoaded = it.double("domContentLoaded") ?: 0.0,
                        load = it.double("load") ?: 0.0,
                        transfer = it.long("transfer") ?: 0,
                        decoded = it.long("decoded") ?: 0,
                    )
                },
            firstPaint = paints?.double("first-paint"),
            firstContentfulPaint = paints?.double("first-contentful-paint"),
            heapUsed = memory?.long("used"),
            heapLimit = memory?.long("limit"),
            nodeCount = perf.int("nodes") ?: 0,
            viewportWidth = viewport?.int("width") ?: 0,
            viewportHeight = viewport?.int("height") ?: 0,
            devicePixelRatio = viewport?.double("dpr") ?: 1.0,
            userAgent = perf.string("userAgent"),
            resourceCount = resources.size,
            resourceBytes = resources.sumOf { (it.long("transfer")?.takeIf { size -> size > 0 } ?: it.long("decoded") ?: 0L) },
        )
    }

    fun parseSources(raw: String?): List<PageSource> =
        parseArray(raw).mapNotNull { element ->
            (element as? JsonObject)?.let { PageSource(it.string("kind"), it.string("url"), it.stringOrNull("inline")) }
        }

    /**
     * What `WebView.evaluateJavascript` hands back for a function that returns a string: that string
     * as a JSON literal, or `null`. The agent's functions return JSON text, so this unwraps one level.
     */
    fun unwrapJavascriptResult(raw: String?): String? {
        if (raw == null || raw == "null" || raw == "undefined") return null
        return runCatching { (devToolsJson.parseToJsonElement(raw) as? JsonPrimitive)?.takeIf { it.isString }?.content }
            .getOrNull()
    }

    private fun domNode(node: JsonObject): DomNode? {
        val id = node.int("id") ?: return null
        return DomNode(
            id = id,
            type = node.int("type") ?: 1,
            tag = node.string("tag"),
            attributes = headers(node["attrs"]),
            text = node.stringOrNull("text"),
            childCount = node.int("childCount") ?: 0,
        )
    }

    private fun parseObject(raw: String): JsonObject? = runCatching { devToolsJson.parseToJsonElement(raw) as? JsonObject }.getOrNull()

    private fun parseArray(raw: String?): JsonArray =
        raw?.let { runCatching { devToolsJson.parseToJsonElement(it) as? JsonArray }.getOrNull() } ?: JsonArray(emptyList())

    private fun headers(element: JsonElement?): List<Header> =
        (element as? JsonArray).orEmpty().mapNotNull { pair ->
            val items = pair as? JsonArray ?: return@mapNotNull null
            val name = (items.getOrNull(0) as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            Header(name, (items.getOrNull(1) as? JsonPrimitive)?.contentOrNull.orEmpty())
        }

    private fun doubles(element: JsonElement?): List<Double> =
        (element as? JsonArray).orEmpty().map { (it as? JsonPrimitive)?.doubleOrNull ?: 0.0 }
}

data class DevToolsEvaluation(
    val ok: Boolean,
    val value: JsonElement,
    /** Set when the value was a promise: its outcome arrives later as [DevToolsEvent.EvalSettled]. */
    val pendingToken: Int?,
)

private fun JsonArray?.orEmpty(): List<JsonElement> = this?.toList() ?: emptyList()

private fun JsonObject.string(key: String): String = stringOrNull(key).orEmpty()

private fun JsonObject.stringOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }

private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

private fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

/**
 * A one-line rendering of a value the page agent serialized: strings bare, objects and arrays
 * abbreviated the way a console prints them collapsed.
 */
fun previewOf(
    value: JsonElement,
    nested: Boolean = false,
): String =
    when (value) {
        is JsonNull -> "null"
        is JsonPrimitive -> if (value.isString) (if (nested) "\"${value.content}\"" else value.content) else value.content
        is JsonArray ->
            if (nested) {
                "Array(${value.size})"
            } else {
                value.take(PREVIEW_ITEMS).joinToString(", ", "[", if (value.size > PREVIEW_ITEMS) ", …]" else "]") { previewOf(it, true) }
            }
        is JsonObject -> specialPreview(value) ?: objectPreview(value, nested)
    }

private const val PREVIEW_ITEMS = 6

/** The `{"__t": …}` markers the agent uses for values JSON cannot hold, shown as their label. */
internal fun specialPreview(value: JsonObject): String? {
    val marker = (value["__t"] as? JsonPrimitive)?.contentOrNull ?: return null
    val label = (value["v"] as? JsonPrimitive)?.contentOrNull
    return when (marker) {
        "undefined" -> "undefined"
        "map" -> "Map(${(value["size"] as? JsonPrimitive)?.content ?: "?"})"
        "set" -> "Set(${(value["size"] as? JsonPrimitive)?.content ?: "?"})"
        "stack" -> label?.lineSequence()?.drop(1)?.joinToString("\n") { it.trim() } ?: ""
        else -> label ?: marker
    }
}

private fun objectPreview(
    value: JsonObject,
    nested: Boolean,
): String {
    val className = (value["__class"] as? JsonPrimitive)?.contentOrNull
    val prefix = if (className != null) "$className " else ""
    if (nested) return prefix + "{…}"
    val fields = value.entries.filter { it.key != "__class" }
    val body = fields.take(PREVIEW_ITEMS).joinToString(", ") { (key, field) -> "$key: ${previewOf(field, true)}" }
    return prefix + "{" + body + (if (fields.size > PREVIEW_ITEMS) ", …" else "") + "}"
}
