package com.yugahashimoto.andcode.feature.browser

import com.yugahashimoto.andcode.runtime.LocalAgent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The browser MCP script's two browsers, under the host's python3: which page each one drives, what
 * the agent is told when there is none, and the commands and status it shares with the app
 * ([BrowserOpenCommand], [AgentBrowserState]). Skipped where python3 is missing.
 */
class BrowserMcpModeScriptTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val runner by lazy { BrowserMcpScriptRunner(folder) }

    @Before
    fun needsPython() {
        assumeTrue("needs python3", BrowserMcpScriptRunner.pythonStarts())
    }

    // A WebView whose renderer died: DevTools says so instead of answering.
    private val crashingWs =
        """
        class CrashingWs:
            def __init__(self):
                self.replies = ['{"method": "Inspector.targetCrashed", "params": {}}']
            def send_text(self, text):
                pass
            def recv_text(self):
                if not self.replies:
                    raise TimeoutError("waited out the socket timeout")
                return self.replies.pop(0)
        """.trimIndent()

    // The app's WebViews as DevTools lists them: an artifact preview on screen, the Guest Browser, the hidden page.
    private val targets =
        """
        targets = [
            {"id": "ART", "type": "page", "url": "http://127.0.0.1:5173/", "description": '{"attached": true, "visible": true}'},
            {"id": "GUEST", "type": "page", "url": "https://visible.example/", "description": '{"attached": true, "visible": false}'},
            {"id": "HID", "type": "page", "url": "https://hidden.example/", "description": '{"attached": false, "visible": true}'},
        ]
        """.trimIndent()

    @Test
    fun `the hidden browser drives the target the app named and nothing else`() {
        val chosen =
            run(
                targets + "\n" +
                    """
                    state = {"mode": "hidden", "hidden": {"state": "ready", "targetId": "HID"}, "guestBrowserOpen": True}
                    print(m.choose_page(targets, state)["id"])
                    for gone in ([t for t in targets if t["id"] != "HID"], []):
                        try:
                            m.choose_page(gone, state)
                        except m.PageGone as error:
                            print(str(error) == m.HIDDEN_GONE)
                    """.trimIndent(),
            )

        assertEquals("HID\nTrue\nTrue", chosen)
    }

    @Test
    fun `with no hidden page the agent learns why, and never falls through to a page on screen`() {
        val answers =
            run(
                targets + "\n" +
                    """
                    for state in (
                        {"mode": "hidden", "hidden": None, "ended": "closed_by_user"},
                        {"mode": "hidden", "hidden": None, "ended": "renderer_gone"},
                        {"mode": "hidden", "hidden": None, "ended": "agent_control_off"},
                        {"mode": "hidden", "hidden": None, "ended": None},
                        {"mode": "hidden", "hidden": {"state": "starting", "targetId": None}},
                    ):
                        try:
                            print(m.choose_page(targets, state))
                        except m.CdpError as error:
                            print(str(error))
                    """.trimIndent(),
            )

        val lines = answers.lines()
        assertEquals(5, lines.size)
        assertTrue(lines[0], lines[0].startsWith("The user closed the hidden browser"))
        assertTrue(lines[1], lines[1].startsWith("The hidden browser's page stopped responding") && "Nothing reloads" in lines[1])
        assertTrue(lines[2], lines[2].contains("'Let agents control this browser' is off"))
        assertTrue(lines[3], lines[3].startsWith("No hidden browser is open."))
        assertEquals("The hidden browser is still starting; retry in a moment.", lines[4])
    }

    /** With the hidden browser holding the DevTools socket open, an artifact or chat preview on screen is reachable too. */
    @Test
    fun `the visible browser is never the hidden page, the artifact preview or the chat's preview`() {
        val answers =
            run(
                targets + "\n" +
                    """
                    closed = {"mode": "visible", "hidden": {"state": "ready", "targetId": "HID"}, "guestBrowserOpen": False}
                    try:
                        m.choose_page(targets, closed)
                    except m.CdpError as error:
                        print(str(error) == m.VISIBLE_NOT_OPEN)
                    on_screen = '{"attached": true, "visible": true}'
                    guest = [dict(t, description=on_screen) if t["id"] == "GUEST" else t for t in targets if t["id"] != "ART"]
                    marker = {"id": "NEW", "type": "page", "url": m.HIDDEN_MARKER_PREFIX + "x", "description": "{}"}
                    static = {"id": "CARD", "type": "page", "url": "https://andcode.preview/index.html", "description": on_screen}
                    shown = {"mode": "visible", "hidden": {"state": "ready", "targetId": "HID"}, "guestBrowserOpen": True}
                    print(m.choose_page(guest + [marker, static], shown)["id"])
                    viewed = {"mode": "visible", "hidden": None, "ended": "viewed", "guestBrowserOpen": True, "visibleTargetId": "HID"}
                    print(m.choose_page(targets, viewed)["id"])
                    print(m.choose_page([t for t in targets if t["id"] == "ART"], None)["id"])
                    """.trimIndent(),
            )

        // The last: before the app writes any status, the page on screen, as before there were modes.
        assertEquals("True\nGUEST\nHID\nART", answers)
    }

    @Test
    fun `a hidden page whose renderer dies says it is gone and will not come back by itself`() {
        val outcome =
            run(
                crashingWs + "\n" +
                    """
                    try:
                        m._PageSession(CrashingWs(), m.HIDDEN_GONE).call("Runtime.evaluate", expression="1")
                    except m.PageGone as error:
                        print(str(error) == m.HIDDEN_GONE)
                    """.trimIndent(),
            )

        assertEquals("True", outcome)
    }

    /** The status the app writes is the one the script reads: the same fields, end to end. */
    @Test
    fun `the script reads the status the app writes`() {
        val state =
            AgentBrowserState()
                .startHidden("r1", "https://hidden.example/", now = 0)
                .hiddenReady("HID")
        val file = folder.newFile("browser-state.json").apply { writeText(state.statusJson(now = 0)) }
        val answers =
            run(
                targets + "\n" +
                    """
                    from pathlib import Path
                    m.STATE_FILE = Path(r'''${file.absolutePath}''')
                    state = m.read_state()
                    print(m.chosen_mode(state), state["lastRequestId"], m.choose_page(targets, state)["id"])
                    """.trimIndent(),
            )

        assertEquals("hidden r1 HID", answers)
    }

    @Test
    fun `browser_show asks for the hidden browser and waits for the app to open it`() {
        val runtime = folder.newFolder("runtime-hidden")
        val home = File(File(runtime, "environment/rootfs"), BROWSER_COMMAND_PATH)
        val answers =
            run(
                """
                from pathlib import Path
                m.COMMAND_FILE = Path(r'''${home.absolutePath}''')
                m._new_request_id = lambda: "r1"
                m._await_load = lambda target_id: None
                m.HIDDEN_OPEN_TIMEOUT = 0
                show = {"url": "https://example.com/a", "mode": "hidden"}
                m.read_state = lambda: {"mode": "hidden", "lastRequestId": "r1", "hidden": {"state": "ready", "targetId": "HID"}}
                print(m.tool_show(show).startswith("opened https://example.com/a in the hidden browser"))
                m.read_state = lambda: {"mode": "hidden", "lastRequestId": "r1", "hidden": None, "ended": "agent_control_off"}
                print(m.tool_show(show) == m.HIDDEN_ENDED["agent_control_off"])
                m.read_state = lambda: {"mode": "visible", "lastRequestId": "older"}
                print(m.tool_show(show).startswith("the app has not opened the hidden browser yet"))
                print(m.tool_show(dict(show, mode="popup")) == m.BAD_MODE)
                print(m.tool_show({"mode": "hidden"}) == m.HIDDEN_NEEDS_URL)
                print(m.tool_show({}) == m.NOTHING_TO_HAND_OVER)
                """.trimIndent(),
            )

        assertEquals(List(6) { "True" }.joinToString("\n"), answers)
        assertEquals(
            BrowserCommand.Open("https://example.com/a", BrowserMode.HIDDEN, "r1"),
            consumeBrowserCommand(LocalAgent.CLAUDE_CODE, runtime),
        )
    }

    @Test
    fun `browser_close leaves a close command, and handing the page over a visible open without an address`() {
        val runtime = folder.newFolder("runtime-close")
        val home = File(File(runtime, "environment/rootfs"), BROWSER_COMMAND_PATH)
        val prelude =
            """
            from pathlib import Path
            m.COMMAND_FILE = Path(r'''${home.absolutePath}''')
            m._new_request_id = lambda: "r2"
            m.CLOSE_TIMEOUT = 0
            open_state = {"mode": "hidden", "hidden": {"state": "ready", "targetId": "HID"}}
            m.read_state = lambda: open_state
            """.trimIndent()

        assertEquals("asked the app to close the hidden browser", run(prelude + "\nprint(m.tool_close({}))"))
        assertEquals(BrowserCommand.Close("r2"), consumeBrowserCommand(LocalAgent.CLAUDE_CODE, runtime))

        assertTrue(run(prelude + "\nprint(m.tool_show({'mode': 'visible'}))").startsWith("asked the app to show the hidden page"))
        assertEquals(BrowserCommand.Open(null, BrowserMode.VISIBLE, "r2"), consumeBrowserCommand(LocalAgent.CLAUDE_CODE, runtime))

        assertEquals("no hidden browser is open", run(prelude + "\nopen_state['hidden'] = None\nprint(m.tool_close({}))"))
    }

    @Test
    fun `browser_status says which browser is active and when the user took the page over`() {
        val answers =
            run(
                targets + "\n" +
                    """
                    m._find_devtools_socket = lambda: "webview_devtools_remote_1"
                    m.CdpSession.list_targets = lambda self: targets
                    m.read_state = lambda: {"mode": "hidden", "hidden": {"state": "ready", "targetId": "HID"}}
                    print(m.tool_status({}))
                    m.read_state = lambda: {"mode": "visible", "ended": "viewed", "guestBrowserOpen": True, "visibleTargetId": "HID"}
                    print(m.tool_status({}))
                    m.read_state = lambda: {"mode": "hidden", "hidden": None, "ended": "idle"}
                    print(m.tool_status({}))
                    """.trimIndent(),
            )

        assertEquals(
            listOf(
                "active: hidden browser (not on the user's screen)",
                "connected: webview_devtools_remote_1 / page: https://hidden.example/ ()",
                "active: visible browser (the Guest Browser on the user's screen)",
                "connected: webview_devtools_remote_1 / page: https://hidden.example/ ()",
                "The user tapped View: the page is on their screen now, in the Guest Browser, as you left it, and counts " +
                    "as the visible browser. The other browser_* tools still act on it while it stays there.",
                "active: hidden browser / The hidden browser was closed after a few minutes without use. " +
                    "Call browser_show with mode \"hidden\" and a URL to open a new one.",
            ),
            answers.lines(),
        )
    }

    /** The next chat's agent starts on the visible browser; the agent whose hidden browser closed is told why. */
    @Test
    fun `an agent that chose the hidden browser never lands on the user's page after its chat ended`() {
        val answers =
            run(
                targets + "\n" +
                    """
                    on_screen = '{"attached": true, "visible": true}'
                    guest = [dict(t, description=on_screen) for t in targets if t["id"] == "GUEST"]
                    after = {"mode": "visible", "hidden": None, "ended": "chat_ended", "guestBrowserOpen": True}
                    print(m.choose_page(guest, after)["id"])
                    m._last_chosen_mode = "hidden"
                    try:
                        m.choose_page(guest, after)
                    except m.CdpError as error:
                        print(str(error) == m.HIDDEN_ENDED["chat_ended"])
                    viewed = {"mode": "visible", "ended": "viewed", "guestBrowserOpen": True, "visibleTargetId": "GUEST"}
                    print(m.choose_page(guest, viewed)["id"])
                    """.trimIndent(),
            )

        assertEquals("GUEST\nTrue\nGUEST", answers)
    }

    /** A tool the agent cannot see in tools/list cannot be called, whatever handler it has. */
    @Test
    fun `every tool is listed once, and every listed tool has a handler`() {
        val answer =
            run(
                """
                names = [tool["name"] for tool in m.TOOLS]
                print(len(names) == len(set(names)), sorted(names) == sorted(m.HANDLERS))
                print(all({"name", "description", "inputSchema"} <= set(tool) for tool in m.TOOLS))
                """.trimIndent(),
            )

        assertEquals("True True\nTrue", answer)
    }

    private fun run(code: String): String = runner.run(code)
}
