package com.yugahashimoto.andcode.feature.browser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentBrowserStateTest {
    private val start = AgentBrowserState()

    @Test
    fun `the agent starts on the visible browser, with nothing hidden`() {
        assertEquals(BrowserMode.VISIBLE, start.mode)
        assertNull(start.hidden)
        assertNull(start.browsingNotice(foreground = true))
    }

    /** The script waits for its request to be answered: a page that is still starting has not answered. */
    @Test
    fun `a hidden page answers its request once its DevTools target is known`() {
        val starting = start.startHidden("r1", "https://example.com/", now = 10)

        assertEquals(BrowserMode.HIDDEN, starting.mode)
        assertFalse(starting.hidden!!.ready)
        assertNull(starting.lastRequestId)

        val ready = starting.hiddenReady("T1")

        assertTrue(ready.hidden!!.ready)
        assertEquals("T1", ready.hidden!!.targetId)
        assertEquals("r1", ready.lastRequestId)
    }

    @Test
    fun `a second hidden open reuses the page and answers at once`() {
        val ready = start.startHidden("r1", "https://example.com/", now = 10).hiddenReady("T1")

        val again = ready.navigateHidden("r2", "https://example.com/next", now = 20)

        assertEquals("T1", again.hidden!!.targetId)
        assertEquals("https://example.com/next", again.hidden!!.url)
        assertEquals("r2", again.lastRequestId)
        assertEquals(20L, again.hidden!!.lastActivityMs)
    }

    /**
     * The agent chose the hidden browser: when it goes away, the tools must not fall through to the
     * page the user has open, so the mode stays hidden and says why it ended.
     */
    @Test
    fun `an ended hidden browser keeps the agent on hidden and says why`() {
        val ready = start.startHidden("r1", "https://example.com/", now = 10).hiddenReady("T1")

        listOf(
            HiddenBrowserEnd.RENDERER_GONE,
            HiddenBrowserEnd.IDLE,
            HiddenBrowserEnd.CLOSED_BY_USER,
        ).forEach { reason ->
            val ended = ready.hiddenEnded(reason)
            assertEquals(BrowserMode.HIDDEN, ended.mode)
            assertNull(ended.hidden)
            assertEquals(reason, ended.ended)
            assertEquals("r1", ended.lastRequestId)
        }
    }

    /** The next chat's agent chose nothing: it starts on the visible browser, as every agent does. */
    @Test
    fun `a chat ending returns the agent to the visible browser, saying why the hidden one closed`() {
        val ended = start.startHidden("r1", "https://example.com/", now = 10).hiddenReady("T1").hiddenEnded(HiddenBrowserEnd.CHAT_ENDED)

        assertEquals(BrowserMode.VISIBLE, ended.mode)
        assertEquals(HiddenBrowserEnd.CHAT_ENDED, ended.ended)
        assertNull(ended.hidden)
    }

    /** The hidden page closed before the chat ended: the next chat's agent still starts on the visible browser. */
    @Test
    fun `a chat ending after its hidden page closed returns to the visible browser, keeping why it closed`() {
        val idle = start.startHidden("r1", "https://example.com/", now = 10).hiddenReady("T1").hiddenEnded(HiddenBrowserEnd.IDLE)

        val next = idle.chatEnded()

        assertEquals(BrowserMode.VISIBLE, next.mode)
        assertEquals(HiddenBrowserEnd.IDLE, next.ended)
        val open = start.startHidden("r2", "https://example.com/", now = 10)
        assertEquals(open, open.chatEnded())
    }

    @Test
    fun `a request that could not be met is answered with the reason`() {
        val refused = start.hiddenEnded(HiddenBrowserEnd.AGENT_CONTROL_OFF, requestId = "r9")

        assertEquals("r9", refused.lastRequestId)
        assertEquals(HiddenBrowserEnd.AGENT_CONTROL_OFF, refused.ended)
        assertEquals(BrowserMode.HIDDEN, refused.mode)
    }

    @Test
    fun `a new hidden page clears the reason the last one ended`() {
        val ended = start.hiddenEnded(HiddenBrowserEnd.IDLE)

        assertNull(ended.startHidden("r2", "https://example.com/", now = 30).ended)
    }

    /** After View the page is the user's, on screen: the agent is told it is visible now, and which page it is. */
    @Test
    fun `viewing hands the page to the visible browser by its target`() {
        val ready = start.startHidden("r1", "https://example.com/", now = 10).hiddenReady("T1")

        val viewed = ready.viewed()

        assertEquals(BrowserMode.VISIBLE, viewed.mode)
        assertNull(viewed.hidden)
        assertEquals(HiddenBrowserEnd.VIEWED, viewed.ended)
        assertEquals("T1", viewed.viewedTargetId)
        assertEquals("r1", viewed.viewedRequestId)
        assertNull(viewed.browsingNotice(foreground = true))
    }

    /** View before the app found the page: the open it was starting for is answered, not left to time out. */
    @Test
    fun `viewing a page that is still starting answers its open`() {
        val earlier = start.visibleOpened("r0")

        val viewed = earlier.startHidden("r1", "https://example.com/", now = 10).viewed()

        assertEquals("r1", viewed.lastRequestId)
        assertEquals("r1", viewed.viewedRequestId)
        assertNull(viewed.viewedTargetId)
        val answeredSince = earlier.startHidden("r1", "https://example.com/", now = 10).hiddenReady("T1").copy(lastRequestId = "r2")
        assertEquals("r2", answeredSince.viewed().lastRequestId)
    }

    @Test
    fun `the Guest Browser closing forgets the page it was showing`() {
        val viewed = start.startHidden("r1", "https://example.com/", now = 10).hiddenReady("T1").viewed().guestBrowserShown("T1")

        assertTrue(viewed.guestBrowserOpen)
        val closed = viewed.guestBrowserClosed()

        assertFalse(closed.guestBrowserOpen)
        assertNull(closed.viewedTargetId)
    }

    @Test
    fun `a visible open switches the agent back to the browser on screen`() {
        val hidden = start.hiddenEnded(HiddenBrowserEnd.CLOSED_BY_AGENT, requestId = "r1")

        val visible = hidden.visibleOpened("r2")

        assertEquals(BrowserMode.VISIBLE, visible.mode)
        assertEquals("r2", visible.lastRequestId)
    }

    @Test
    fun `the page's own address and title follow its navigations, the marker never`() {
        val ready = start.startHidden("r1", "https://example.com/", now = 10).hiddenReady("T1")

        val moved = ready.pageChanged(url = "https://example.com/b", title = "B")
        assertEquals("https://example.com/b", moved.hidden!!.url)
        assertEquals("B", moved.hidden!!.title)

        assertEquals(moved, moved.pageChanged(url = HiddenPageMarker.url("abc"), title = ""))
    }

    @Test
    fun `the hidden browser is idle after minutes without the agent acting on it`() {
        val ready = start.startHidden("r1", "https://example.com/", now = 0).hiddenReady("T1")

        assertFalse(ready.idle(now = AgentBrowserState.IDLE_TIMEOUT_MILLIS - 1))
        assertTrue(ready.idle(now = AgentBrowserState.IDLE_TIMEOUT_MILLIS))
        assertFalse(ready.touched(now = 60_000).idle(now = AgentBrowserState.IDLE_TIMEOUT_MILLIS))
        assertFalse(start.idle(now = Long.MAX_VALUE))
    }

    @Test
    fun `the notice names the host the agent is on, and goes to a notification in the background`() {
        val ready = start.startHidden("r1", "https://www.example.com/path?q=1", now = 0).hiddenReady("T1")

        assertEquals(AgentBrowsingNotice("example.com", inBackground = false), ready.browsingNotice(foreground = true))
        assertEquals(AgentBrowsingNotice("example.com", inBackground = true), ready.browsingNotice(foreground = false))
        val local = start.startHidden("r1", "http://127.0.0.1:5173/", now = 0)
        assertEquals("127.0.0.1:5173", local.browsingNotice(foreground = true)!!.host)
    }

    /** A new chat's first message gives it its id: that is the same chat going on, not one ending. */
    @Test
    fun `leaving a chat ends it, starting one does not`() {
        assertTrue(chatEnded(previous = "a", current = "b"))
        assertTrue(chatEnded(previous = "a", current = null))
        assertFalse(chatEnded(previous = null, current = "a"))
        assertFalse(chatEnded(previous = "a", current = "a"))
    }

    /** What andcode-browser-mcp.py reads: see read_state there. */
    @Test
    fun `the status file tells the script which browser is active and which page is the hidden one`() {
        val ready = start.startHidden("r1", "https://example.com/", now = 0).hiddenReady("T1").pageChanged("https://example.com/", "Ex")

        val json = status(ready)

        assertEquals("1", json.getValue("version").jsonPrimitive.content)
        assertEquals("hidden", json.getValue("mode").jsonPrimitive.content)
        val hidden = json.getValue("hidden").jsonObject
        assertEquals("T1", hidden.getValue("targetId").jsonPrimitive.content)
        assertEquals("https://example.com/", hidden.getValue("url").jsonPrimitive.content)
        assertEquals("Ex", hidden.getValue("title").jsonPrimitive.content)
        assertEquals("ready", hidden.getValue("state").jsonPrimitive.content)
        assertEquals("r1", json.getValue("lastRequestId").jsonPrimitive.content)
        assertEquals(JsonNull, json.getValue("ended"))
        assertEquals("false", json.getValue("guestBrowserOpen").jsonPrimitive.content)
        assertEquals(JsonNull, json.getValue("visibleTargetId"))
        assertEquals(JsonNull, json.getValue("viewedRequestId"))
        assertEquals("1234", json.getValue("updatedAtMs").jsonPrimitive.content)
    }

    @Test
    fun `the status file says why a hidden browser ended and which page the user took over`() {
        val gone = start.startHidden("r1", "https://example.com/", now = 0).hiddenReady("T1").hiddenEnded(HiddenBrowserEnd.RENDERER_GONE)
        assertEquals("renderer_gone", status(gone).getValue("ended").jsonPrimitive.content)
        assertEquals(JsonNull, status(gone).getValue("hidden"))

        val viewed = start.startHidden("r1", "https://example.com/", now = 0).hiddenReady("T1").viewed().guestBrowserShown("T1")
        assertEquals("visible", status(viewed).getValue("mode").jsonPrimitive.content)
        assertEquals("T1", status(viewed).getValue("visibleTargetId").jsonPrimitive.content)
        assertEquals("r1", status(viewed).getValue("viewedRequestId").jsonPrimitive.content)
        assertEquals("true", status(viewed).getValue("guestBrowserOpen").jsonPrimitive.content)

        val starting = start.startHidden("r2", "https://example.com/", now = 0)
        assertEquals("starting", status(starting).getValue("hidden").jsonObject.getValue("state").jsonPrimitive.content)
    }

    private fun status(state: AgentBrowserState): JsonObject = Json.parseToJsonElement(state.statusJson(now = 1234)).jsonObject
}
