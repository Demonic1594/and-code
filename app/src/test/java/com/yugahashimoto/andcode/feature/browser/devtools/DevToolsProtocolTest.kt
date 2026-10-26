package com.yugahashimoto.andcode.feature.browser.devtools

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DevToolsProtocolTest {
    @Test
    fun `evaluateJavascript results are unwrapped once`() {
        assertEquals("""{"ok":true}""", DevToolsProtocol.unwrapJavascriptResult("\"{\\\"ok\\\":true}\""))
        assertNull(DevToolsProtocol.unwrapJavascriptResult("null"))
        assertNull(DevToolsProtocol.unwrapJavascriptResult(null))
        // A non-string answer is not ours to read.
        assertNull(DevToolsProtocol.unwrapJavascriptResult("42"))
    }

    @Test
    fun `an evaluation reads ok, value and a pending promise`() {
        val done = DevToolsProtocol.parseEvaluation("""{"ok":true,"value":3}""")!!
        assertTrue(done.ok)
        assertEquals(JsonPrimitive(3), done.value)
        assertNull(done.pendingToken)

        val pending = DevToolsProtocol.parseEvaluation("""{"ok":true,"pending":true,"token":4,"value":{"__t":"promise","v":"Promise"}}""")!!
        assertEquals(4, pending.pendingToken)

        val thrown = DevToolsProtocol.parseEvaluation("""{"ok":false,"value":{"__t":"error","v":"ReferenceError: x is not defined"}}""")!!
        assertFalse(thrown.ok)
        assertEquals("ReferenceError: x is not defined", previewOf(thrown.value))
    }

    @Test
    fun `values preview the way a collapsed console line does`() {
        fun preview(json: String) = previewOf(devToolsJson.parseToJsonElement(json))

        assertEquals("hello", preview("\"hello\""))
        assertEquals("[1, \"a\", null, Array(2)]", preview("""[1,"a",null,[1,2]]"""))
        assertEquals("{a: 1, b: \"x\", c: {…}}", preview("""{"a":1,"b":"x","c":{"d":1}}"""))
        assertEquals("Point {x: 1}", preview("""{"x":1,"__class":"Point"}"""))
        assertEquals("undefined", preview("""{"__t":"undefined"}"""))
        assertEquals("ƒ onClick()", preview("""{"__t":"function","v":"ƒ onClick()"}"""))
        assertEquals("Map(2)", preview("""{"__t":"map","size":2,"entries":{}}"""))
    }

    @Test
    fun `the elements tree, details and storage are read from the agent's answers`() {
        val root =
            DevToolsProtocol.parseRoot(
                """{"doctype":"<!DOCTYPE html>","root":{"id":1,"type":1,"tag":"html","attrs":[["lang","en"]],"childCount":2,"children":[{"id":2,"type":1,"tag":"head","childCount":3},{"id":3,"type":1,"tag":"body","childCount":1}]}}""",
            )!!
        assertEquals("<!DOCTYPE html>", root.first)
        assertEquals("html", root.second.tag)
        assertEquals(listOf(Header("lang", "en")), root.second.attributes)

        val children =
            DevToolsProtocol.parseRootChildren(
                """{"root":{"id":1,"type":1,"tag":"html","children":[{"id":2,"type":1,"tag":"head"},{"id":5,"type":3,"text":"hi"}]}}""",
            )
        assertEquals(listOf(2, 5), children.map { it.id })
        assertEquals("hi", children[1].text)

        val details =
            DevToolsProtocol.parseDetails(
                """{"id":3,"tag":"button","attrs":[["class","primary"]],"inline":"color: red","rules":[{"selector":".primary","css":"padding: 4px;","source":"app.css"}],""" +
                    """"computed":[["display","block"]],"box":{"x":1,"y":2,"width":30,"height":10,"margin":[1,2,3,4],"padding":[0,0,0,0],"border":[1,1,1,1]},"outerHTML":"<button/>","text":"Go"}""",
            )!!
        assertEquals(".primary", details.rules.single().selector)
        assertEquals(listOf(1.0, 2.0, 3.0, 4.0), details.box!!.margin)
        assertEquals("Go", details.text)

        val storage =
            DevToolsProtocol.parseStorage(
                """{"origin":"http://localhost:5173","local":[["theme","dark"]],"session":[],"cookies":[["sid","abc"]]}""",
            )!!
        assertEquals(listOf(Header("theme", "dark")), storage.local)
        assertEquals(listOf(Header("sid", "abc")), storage.cookies)
    }

    @Test
    fun `performance reads timings, paints, memory and totals`() {
        val perf =
            DevToolsProtocol.parsePerformance(
                """{"url":"u","title":"t","nav":{"type":"navigate","dns":1,"connect":2,"ttfb":30,"download":4,"domInteractive":100,"domContentLoaded":120,"load":300,"transfer":1000,"decoded":3000},""" +
                    """"paints":{"first-paint":80,"first-contentful-paint":90},"memory":{"used":1000,"total":2000,"limit":4000},"nodes":120,""" +
                    """"resources":[{"url":"a","transfer":100},{"url":"b","transfer":0,"decoded":50}],"viewport":{"width":412,"height":800,"dpr":2.625},"userAgent":"UA"}""",
            )!!
        assertEquals(300.0, perf.navigation!!.load, 0.0)
        assertEquals(90.0, perf.firstContentfulPaint!!, 0.0)
        assertEquals(1000L, perf.heapUsed)
        assertEquals(2, perf.resourceCount)
        assertEquals(150L, perf.resourceBytes)
        assertEquals(412, perf.viewportWidth)
    }

    @Test
    fun `unknown and malformed messages are ignored`() {
        assertNull(DevToolsProtocol.parseEvent("not json"))
        assertNull(DevToolsProtocol.parseEvent("""{"t":"mystery"}"""))
        assertNull(DevToolsProtocol.parseEvent("""{"t":"net","phase":"start"}"""))
        assertTrue(DevToolsProtocol.parseChildren("oops").isEmpty())
    }
}
