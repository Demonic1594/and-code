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
 * The browser MCP script's preview, handoff, upload and password rules, under the host's python3,
 * against the command the app reads ([consumeBrowserCommand]). Skipped where python3 is missing.
 */
class BrowserMcpHandoffScriptTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val runner by lazy { BrowserMcpScriptRunner(folder) }

    @Before
    fun needsPython() {
        assumeTrue("needs python3", BrowserMcpScriptRunner.pythonStarts())
    }

    @Test
    fun `browser_preview asks for a page on this device only, in the guest home`() {
        val runtime = folder.newFolder("runtime")
        val home = File(File(runtime, "environment/rootfs"), BROWSER_COMMAND_PATH)
        val answers =
            run(
                """
                from pathlib import Path
                m.COMMAND_FILE = Path(r'''${home.absolutePath}''')
                for url in ("https://example.com/", "http://10.0.0.2:8080/", "file:///etc/passwd", None):
                    print(m.tool_preview({"url": url}) == m.NOT_LOOPBACK)
                print(m.COMMAND_FILE.exists())
                print(m.tool_preview({"url": " http://127.0.0.1:5173/ "}))
                """.trimIndent(),
            )

        assertEquals(
            listOf("True", "True", "True", "True", "False", "requested the app to show http://127.0.0.1:5173/ as an inline preview"),
            answers.lines(),
        )
        assertEquals(
            BrowserCommand.Preview("http://127.0.0.1:5173/"),
            consumeBrowserCommand("/root/project", LocalAgent.CLAUDE_CODE, runtime),
        )
    }

    /** The app writes the status; the tool clears a stale one first, asks, and reads the answer. */
    @Test
    fun `browser_handoff waits for the user's answer in the status file`() {
        val runtime = folder.newFolder("runtime")
        val rootfs = File(runtime, "environment/rootfs")
        val answers =
            run(
                """
                import threading, time
                from pathlib import Path
                m.COMMAND_FILE = Path(r'''${File(rootfs, BROWSER_COMMAND_PATH).absolutePath}''')
                m.STATUS_FILE = Path(r'''${File(rootfs, BROWSER_STATUS_PATH).absolutePath}''')
                m._find_devtools_socket = lambda: "webview_devtools_remote_1"
                m.STATUS_FILE.parent.mkdir(parents=True, exist_ok=True)
                m.STATUS_FILE.write_text('{"state": "agent"}')
                def answer(state):
                    time.sleep(0.3)
                    print("asked", m.COMMAND_FILE.exists(), m.STATUS_FILE.exists())
                    m.STATUS_FILE.write_text('{"state": "%s"}' % state)
                for state, expected in (("agent", m.HANDOFF_BACK), ("dismissed", m.HANDOFF_CANCELLED)):
                    threading.Thread(target=answer, args=(state,)).start()
                    print(m.tool_handoff({"timeout_s": 5}) == expected)
                print(m.tool_handoff({"timeout_s": 0.1}) == m.HANDOFF_TIMED_OUT or "stale")
                """.trimIndent(),
            )

        // A stale answer from the handoff before never ends this one: it is cleared before asking.
        assertEquals(listOf("asked True False", "True", "asked True False", "True", "True"), answers.lines())
        assertEquals(BrowserCommand.Handoff(rootfs), consumeBrowserCommand("/root/project", LocalAgent.CLAUDE_CODE, runtime))
    }

    @Test
    fun `browser_handoff needs the browser open`() {
        val answer =
            run(
                """
                m._wait_for_socket = lambda timeout: None
                try:
                    m.tool_handoff({})
                except m.CdpError as error:
                    print(str(error) == m.NOT_REACHABLE)
                """.trimIndent(),
            )

        assertEquals("True", answer)
    }

    @Test
    fun `typing into a password field is refused, and nothing is typed`() {
        val answer =
            run(
                """
                class Page:
                    def __init__(self):
                        self.typed = None
                    def call(self, method, **params):
                        if method == "Input.insertText":
                            self.typed = params["text"]
                            return {}
                        expression = params.get("expression", "")
                        if "autocomplete" in expression:
                            return {"result": {"value": True}}
                        return {"result": {"value": "ok"}}
                    def close(self):
                        pass
                page = Page()
                class Session:
                    def open_page_session(self):
                        return page
                m._session = lambda: Session()
                print(m.tool_type({"text": "hunter2"}) == m.PASSWORD_REFUSED, page.typed)
                print(m.tool_type({"text": "hunter2", "ref": 3}) == m.PASSWORD_REFUSED, page.typed)
                """.trimIndent(),
            )

        assertEquals("True None\nTrue None", answer)
    }

    /** The WebView reads the file itself, so only device storage, which holds no links, may be attached. */
    @Test
    fun `only a file under device storage can be attached`() {
        val answers =
            run(
                """
                for path in ("/etc/passwd", "/root/.ssh/id_rsa", "/sdcard/../etc/passwd", "", None, "/sdcard/no-such-file"):
                    try:
                        m.upload_path(path)
                        print("allowed")
                    except m.CdpError as error:
                        print("refused")
                """.trimIndent(),
            )

        assertTrue(answers, answers.lines().all { it == "refused" })
    }

    private fun run(code: String): String = runner.run(code)
}
