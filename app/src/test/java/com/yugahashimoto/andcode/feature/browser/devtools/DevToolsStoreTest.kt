package com.yugahashimoto.andcode.feature.browser.devtools

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DevToolsStoreTest {
    private fun event(raw: String) = requireNotNull(DevToolsProtocol.parseEvent(raw)) { raw }

    @Test
    fun `console lines keep their level, source and group nesting`() {
        val store = DevToolsStore()
        store.handle(event("""{"t":"console","level":"group","args":["Setup"],"ts":1}"""))
        store.handle(
            event("""{"t":"console","level":"warn","args":["slow",{"ms":120}],"source":"http://localhost:5173/app.js:12","ts":2}"""),
        )
        store.handle(event("""{"t":"console","level":"groupEnd","args":[]}"""))
        store.handle(event("""{"t":"console","level":"log","args":["done"]}"""))

        val lines = store.console.value
        assertEquals(listOf(ConsoleLevel.LOG, ConsoleLevel.WARN, ConsoleLevel.LOG), lines.map { it.level })
        assertEquals(listOf(0, 1, 0), lines.map { it.depth })
        assertEquals("slow {ms: 120}", lines[1].text)
        assertEquals("http://localhost:5173/app.js:12", lines[1].source)
        assertEquals(DevToolsStore.ProblemCount(errors = 0, warnings = 1), store.problems.value)
    }

    @Test
    fun `identical lines in a row collapse into one with a count`() {
        val store = DevToolsStore()
        repeat(3) { store.handle(event("""{"t":"console","level":"log","args":["tick"],"source":"a.js:1"}""")) }
        store.handle(event("""{"t":"console","level":"log","args":["tock"],"source":"a.js:1"}"""))

        assertEquals(listOf(3, 1), store.console.value.map { it.repeat })
    }

    @Test
    fun `uncaught errors and rejections count as problems`() {
        val store = DevToolsStore()
        store.handle(
            event(
                """{"t":"error","kind":"uncaught","message":"TypeError: x is undefined","source":"main.js:4:2","stack":"at f (main.js:4:2)"}""",
            ),
        )

        val line = store.console.value.single()
        assertEquals(ConsoleLevel.ERROR, line.level)
        assertEquals("TypeError: x is undefined", line.text)
        assertEquals("at f (main.js:4:2)", line.stack)
        assertEquals(1, store.problems.value.errors)
    }

    @Test
    fun `a request is updated in place when it finishes`() {
        val store = DevToolsStore()
        store.handle(
            event(
                """{"t":"net","phase":"start","id":"t1","kind":"fetch","method":"post","url":"http://localhost:3000/api/todos?x=1",""" +
                    """"reqHeaders":[["content-type","application/json"]],"reqBody":"{\"a\":1}","ts":5}""",
            ),
        )
        assertEquals(false, store.network.value.single().finished)

        store.handle(
            event(
                """{"t":"net","phase":"end","id":"t1","status":201,"statusText":"Created","resHeaders":[["x-id","9"]],""" +
                    """"resBody":"{\"id\":9}","mime":"application/json","size":8,"duration":12.5}""",
            ),
        )

        val request = store.network.value.single()
        assertEquals("POST", request.method.uppercase())
        assertEquals("todos", request.name)
        assertEquals(201, request.status)
        assertEquals(listOf(Header("content-type", "application/json")), request.requestHeaders)
        assertEquals("{\"id\":9}", request.responseBody)
        assertEquals(12.5, request.durationMs!!, 0.0)
        assertEquals(NetworkCategory.FETCH, request.category)
    }

    @Test
    fun `a failed request records the error`() {
        val store = DevToolsStore()
        store.handle(event("""{"t":"net","phase":"start","id":"x1","kind":"xhr","method":"GET","url":"http://localhost:1/"}"""))
        store.handle(event("""{"t":"net","phase":"error","id":"x1","error":"Failed to fetch","duration":3}"""))

        val request = store.network.value.single()
        assertTrue(request.failed)
        assertEquals("Failed to fetch", request.error)
    }

    @Test
    fun `resource timings fill in what fetch hooks cannot see, without duplicating a hooked request`() {
        val store = DevToolsStore()
        store.handle(event("""{"t":"net","phase":"start","id":"t1","kind":"fetch","method":"GET","url":"http://h/api"}"""))
        store.handle(
            event(
                """{"t":"resources","entries":[{"url":"http://h/main.js","initiator":"script","duration":8,"start":1,"transfer":300,"decoded":900},""" +
                    """{"url":"http://h/logo.png","initiator":"img","duration":2,"start":2,"transfer":0,"decoded":50},""" +
                    """{"url":"http://h/api","initiator":"other","duration":1,"start":3}]}""",
            ),
        )

        val byName = store.network.value.associateBy { it.name }
        assertEquals(setOf("api", "main.js", "logo.png"), byName.keys)
        assertEquals(NetworkCategory.JS, byName.getValue("main.js").category)
        assertEquals(300L, byName.getValue("main.js").size)
        assertEquals(NetworkCategory.IMG, byName.getValue("logo.png").category)
    }

    @Test
    fun `a new page clears the logs unless they are preserved, but an iframe does not`() {
        val store = DevToolsStore()
        store.handle(event("""{"t":"console","level":"error","args":["boom"]}"""))
        store.handle(event("""{"t":"ready","url":"http://localhost/frame","frame":"http://localhost/frame"}"""))
        assertEquals(1, store.console.value.size)
        store.handle(event("""{"t":"ready","url":"http://localhost/next","frame":""}"""))
        assertTrue(store.console.value.isEmpty())
        assertEquals(0, store.problems.value.errors)

        store.preserveLog = true
        store.handle(event("""{"t":"console","level":"log","args":["kept"]}"""))
        store.handle(event("""{"t":"ready","url":"http://localhost/again","frame":""}"""))
        assertEquals(listOf("kept", "Navigated to http://localhost/again"), store.console.value.map { it.text })
    }

    @Test
    fun `the console keeps only its newest lines`() {
        val store = DevToolsStore(maxConsoleEntries = 3)
        (1..5).forEach { store.handle(event("""{"t":"console","level":"log","args":["$it"]}""")) }

        assertEquals(listOf("3", "4", "5"), store.console.value.map { it.text })
    }

    @Test
    fun `prompt input and its result are never merged`() {
        val store = DevToolsStore()
        store.appendInput("1+1")
        store.appendResult(true, JsonPrimitive(2))
        store.appendInput("1+1")
        store.appendResult(true, JsonPrimitive(2))

        assertEquals(4, store.console.value.size)
    }

    @Test
    fun `an element picked on the page is handed to the Elements tab once`() {
        val store = DevToolsStore()
        store.handle(event("""{"t":"inspect","id":7,"path":[1,3,7]}"""))
        assertEquals(listOf(1, 3, 7), store.inspected.value?.path)

        store.consumeInspected()
        assertNull(store.inspected.value)
    }

    @Test
    fun `console clear from the page empties the log and says so`() {
        val store = DevToolsStore()
        store.handle(event("""{"t":"console","level":"log","args":["a"]}"""))
        store.handle(event("""{"t":"console","level":"clear","args":[]}"""))

        assertEquals(listOf("Console was cleared"), store.console.value.map { it.text })
        assertNotNull(store.console.value.single())
    }

    @Test
    fun `a batch is applied at once, and the frame comes from the WebView, not the page`() {
        val store = DevToolsStore()
        store.handle(event("""{"t":"console","level":"log","args":["top"]}"""))
        val raw =
            """{"t":"batch","items":[{"t":"ready","url":"http://evil/","frame":""},""" +
                """{"t":"console","level":"error","args":["from the ad"],"frame":""}]}"""

        store.handleAll(DevToolsProtocol.parseEvents(raw, fromMainFrame = false, origin = "https://ads.example"))

        // An iframe claiming to be the top page cannot reset the top page's log.
        assertEquals(listOf("top", "from the ad"), store.console.value.map { it.text })
        assertEquals("https://ads.example", store.console.value.last().frame)
        assertEquals(0, store.page.value)
    }

    @Test
    fun `different objects that abbreviate the same stay separate lines`() {
        val store = DevToolsStore()
        store.handle(event("""{"t":"console","level":"log","args":[{"a":{"b":1}}]}"""))
        store.handle(event("""{"t":"console","level":"log","args":[{"a":{"b":2}}]}"""))

        assertEquals(listOf(1, 1), store.console.value.map { it.repeat })
    }

    @Test
    fun `a resource with no status is finished but not called a success`() {
        val store = DevToolsStore()
        store.handle(
            event(
                """{"t":"resources","entries":[{"url":"https://cdn.example/a.js","initiator":"script","start":1700000000000.5,"status":0}]}""",
            ),
        )

        val entry = store.network.value.single()
        assertNull(entry.status)
        assertTrue(entry.finished)
        assertEquals(1_700_000_000_000L, entry.startedAt)
    }

    @Test
    fun `each new top page bumps the page counter for the on-demand tools`() {
        val store = DevToolsStore()
        store.handle(event("""{"t":"ready","url":"http://localhost/a","frame":""}"""))
        store.handle(event("""{"t":"ready","url":"http://localhost/b","frame":""}"""))

        assertEquals(2, store.page.value)
    }
}
