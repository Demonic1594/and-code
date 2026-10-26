package com.yugahashimoto.andcode.feature.browser.devtools

import com.yugahashimoto.andcode.feature.browser.AddressKind
import com.yugahashimoto.andcode.feature.browser.addressKindOf
import com.yugahashimoto.andcode.feature.browser.displayHost
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class DevToolsHelpersTest {
    private fun line(
        id: Long,
        level: ConsoleLevel,
        text: String,
    ) = ConsoleEntry(id, level, listOf(JsonPrimitive(text)))

    @Test
    fun `console filters by level and by text or source`() {
        val lines =
            listOf(
                line(1, ConsoleLevel.LOG, "booting"),
                line(2, ConsoleLevel.WARN, "slow render"),
                line(3, ConsoleLevel.ERROR, "TypeError: nope"),
                line(4, ConsoleLevel.INPUT, "1+1"),
                ConsoleEntry(5, ConsoleLevel.INFO, listOf(JsonPrimitive("ready")), source = "http://h/app.js:3"),
            )

        assertEquals(listOf(3L), filterConsole(lines, ConsoleFilter.ERRORS, "").map { it.id })
        assertEquals(listOf(1L, 4L), filterConsole(lines, ConsoleFilter.LOGS, "").map { it.id })
        assertEquals(listOf(2L), filterConsole(lines, ConsoleFilter.ALL, "SLOW").map { it.id })
        assertEquals(listOf(5L), filterConsole(lines, ConsoleFilter.ALL, "app.js").map { it.id })
    }

    @Test
    fun `network filters by category, text and failure`() {
        val requests =
            listOf(
                NetworkEntry("a", "fetch", "GET", "http://h/api/users", status = 200),
                NetworkEntry("b", "script", "GET", "http://h/main.js", status = 200),
                NetworkEntry("c", "fetch", "POST", "http://h/api/login", status = 401),
                NetworkEntry("d", "img", "GET", "http://h/logo.png", error = "net::ERR_FAILED"),
            )

        assertEquals(listOf("a", "c"), filterNetwork(requests, NetworkFilter.FETCH, "", false).map { it.id })
        assertEquals(listOf("c", "d"), filterNetwork(requests, NetworkFilter.ALL, "", true).map { it.id })
        assertEquals(listOf("c"), filterNetwork(requests, NetworkFilter.ALL, "post", false).map { it.id })
        assertEquals(listOf("b"), filterNetwork(requests, NetworkFilter.JS, "", false).map { it.id })
    }

    @Test
    fun `a request copies as a curl command a shell can run`() {
        val request =
            NetworkEntry(
                id = "x",
                kind = "fetch",
                method = "POST",
                url = "http://localhost:3000/api/todos",
                requestHeaders = listOf(Header("Content-Type", "application/json")),
                requestBody = """{"title":"it's done"}""",
            )

        assertEquals(
            "curl 'http://localhost:3000/api/todos' -X POST \\\n" +
                "  -H 'Content-Type: application/json' \\\n" +
                "  --data-raw '{\"title\":\"it'\\''s done\"}'",
            curlCommand(request),
        )
        assertEquals("curl 'http://h/'", curlCommand(NetworkEntry("y", "fetch", "GET", "http://h/")))
    }

    @Test
    fun `json bodies are indented and anything else is left alone`() {
        assertEquals("{\n    \"a\": 1\n}", prettyBody("""{"a":1}""", "application/json"))
        assertEquals("<p>hi</p>", prettyBody("<p>hi</p>", "text/html"))
        assertEquals("{broken", prettyBody("{broken", "application/json"))
    }

    @Test
    fun `the tree shows children only under open nodes`() {
        val html = DomNode(1, 1, "html", childCount = 2)
        val head = DomNode(2, 1, "head", childCount = 1)
        val body = DomNode(3, 1, "body", childCount = 1)
        val div = DomNode(4, 1, "div")
        val children = mapOf(1 to listOf(head, body), 3 to listOf(div), 2 to listOf(DomNode(5, 1, "title")))

        val rows = flattenTree(html, children, expanded = setOf(1, 3))

        assertEquals(listOf("html", "head", "body", "div"), rows.map { it.node.tag })
        assertEquals(listOf(0, 1, 1, 2), rows.map { it.depth })
    }

    @Test
    fun `selectors read tag, id and the first classes`() {
        val node =
            DomNode(
                1,
                1,
                "button",
                attributes = listOf(Header("id", "save"), Header("class", "btn  primary large")),
            )
        assertEquals("button#save.btn.primary", selectorOf(node))
        assertEquals("#text", selectorOf(DomNode(2, 3, text = "hi")))
    }

    @Test
    fun `the address bar tells local pages from secure and insecure ones`() {
        assertEquals(AddressKind.LOCAL, addressKindOf("http://localhost:5173/"))
        assertEquals(AddressKind.LOCAL, addressKindOf("http://127.0.0.1:8000/docs"))
        assertEquals(AddressKind.SECURE, addressKindOf("https://example.com"))
        assertEquals(AddressKind.INSECURE, addressKindOf("http://example.com"))
        assertEquals(AddressKind.NONE, addressKindOf(""))
        assertEquals("localhost:5173", displayHost("http://localhost:5173/app?x=1"))
        assertEquals("example.com", displayHost("https://www.example.com:443/a"))
        // Credentials in front of the host do not get to impersonate it.
        assertEquals(AddressKind.INSECURE, addressKindOf("http://localhost:1@evil.example/"))
        assertEquals("evil.example", displayHost("https://accounts.google.com:x@evil.example/login"))
        assertEquals("localhost:5173", displayHost("http://user:pw@localhost:5173/"))
    }

    @Test
    fun `values are passed to the page as javascript literals`() {
        assertEquals("null", DevToolsBridge.javascriptLiteral(null))
        assertEquals("7", DevToolsBridge.javascriptLiteral(7))
        assertEquals("true", DevToolsBridge.javascriptLiteral(true))
        assertEquals("\"a\\\"b\\nc\"", DevToolsBridge.javascriptLiteral("a\"b\nc"))
    }

    @Test
    fun `sizes and durations read the way DevTools prints them`() {
        assertEquals("512 B", formatBytes(512))
        assertEquals("1.5 kB", formatBytes(1536))
        assertEquals("–", formatBytes(null))
        assertEquals("42 ms", formatMillis(42.7))
        assertEquals("1.50 s", formatMillis(1500.0))
    }
}
