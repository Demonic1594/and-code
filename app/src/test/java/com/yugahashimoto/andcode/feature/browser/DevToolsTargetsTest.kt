package com.yugahashimoto.andcode.feature.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DevToolsTargetsTest {
    private val marker = HiddenPageMarker.url("abc123")

    private val listing =
        """
        [ {
           "description": "{\"attached\":true,\"visible\":true}",
           "id": "ARTIFACT",
           "title": "Preview",
           "type": "page",
           "url": "http://127.0.0.1:5173/",
           "webSocketDebuggerUrl": "ws:///devtools/page/ARTIFACT"
        }, {
           "description": "{\"attached\":false,\"never_attached\":true,\"visible\":true}",
           "id": "HIDDEN",
           "title": "",
           "type": "page",
           "url": "$marker",
           "webSocketDebuggerUrl": "ws:///devtools/page/HIDDEN"
        } ]
        """.trimIndent()

    @Test
    fun `the hidden page is the one target showing its marker`() {
        assertEquals("HIDDEN", findMarkedTarget(parseDevToolsTargets(listing), marker))
    }

    @Test
    fun `another page's marker, two pages showing it, or none, find nothing`() {
        val targets = parseDevToolsTargets(listing)

        assertNull(findMarkedTarget(targets, HiddenPageMarker.url("other")))
        assertNull(findMarkedTarget(targets + targets.last().copy(id = "TWIN"), marker))
        assertNull(findMarkedTarget(emptyList(), marker))
    }

    @Test
    fun `only pages count, and a listing that is not one reads as none`() {
        val worker = """[{"id":"W","type":"service_worker","url":"$marker"}]"""

        assertNull(findMarkedTarget(parseDevToolsTargets(worker), marker))
        assertEquals(emptyList<DevToolsTarget>(), parseDevToolsTargets("not json"))
        assertEquals(emptyList<DevToolsTarget>(), parseDevToolsTargets("{\"id\":\"x\"}"))
    }

    /** Each marker is its own: a page could not guess the next one and pass for the hidden browser. */
    @Test
    fun `markers are blank pages, unique to each hidden browser`() {
        val first = HiddenPageMarker.new()
        val second = HiddenPageMarker.new()

        assertTrue(first.startsWith("about:blank#andcode-hidden-browser-"))
        assertTrue(first != second)
        assertTrue(HiddenPageMarker.isMarker(first))
        assertTrue(!HiddenPageMarker.isMarker("https://example.com/#andcode-hidden-browser-x"))
    }

    @Test
    fun `a response body is read to its length, whether chunked or not`() {
        val plain = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 4\r\n\r\n[1]\nleft over"
        val chunked = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\n[1]\r\n0\r\n\r\n"
        val unframed = "HTTP/1.1 200 OK\r\n\r\n[2]"

        assertEquals("[1]\n", readHttpBody(plain.byteInputStream()))
        assertEquals("[1]", readHttpBody(chunked.byteInputStream()))
        assertEquals("[2]", readHttpBody(unframed.byteInputStream()))
        assertNull(readHttpBody("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".byteInputStream()))
    }
}
