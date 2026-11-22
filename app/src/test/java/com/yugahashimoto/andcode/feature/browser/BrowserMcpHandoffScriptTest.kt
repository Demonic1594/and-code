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

    /**
     * The app writes the status; the tool clears a stale one first, asks, and reads only the answer to
     * its own request. Each answer is written once the request is in the command file, and the next
     * only after the tool has read it, so no step depends on timing.
     */
    @Test
    fun `browser_handoff waits for the answer to its own request in the status file`() {
        val runtime = folder.newFolder("runtime")
        val rootfs = File(runtime, "environment/rootfs")
        val answers =
            run(
                """
                import json, threading
                from pathlib import Path
                m.COMMAND_FILE = Path(r'''${File(rootfs, BROWSER_COMMAND_PATH).absolutePath}''')
                m.STATUS_FILE = Path(r'''${File(rootfs, BROWSER_STATUS_PATH).absolutePath}''')
                m.HANDOFF_POLL_S = 0.01
                m._find_devtools_socket = lambda: "webview_devtools_remote_1"
                m.STATUS_FILE.parent.mkdir(parents=True, exist_ok=True)
                m.STATUS_FILE.write_text(json.dumps({"state": "agent", "requestId": "before"}))
                read = threading.Event()
                read_status = m._read_status
                def reading():
                    status = read_status()
                    read.set()
                    return status
                m._read_status = reading
                def answer(statuses):
                    for index, status in enumerate(statuses):
                        m.STATUS_FILE.write_text(json.dumps(status))
                        if index == len(statuses) - 1:
                            return
                        # Twice: the first read may have started before the write.
                        for _ in range(2):
                            read.clear()
                            read.wait(5)
                pending = []
                write_command = m._write_command
                def asked(command):
                    write_command(command)
                    print("asked", m.STATUS_FILE.exists())
                    mine, other = command["requestId"], "other"
                    statuses = [dict(s, requestId=mine if s.pop("mine") else other) for s in pending.pop(0)]
                    threading.Thread(target=answer, args=(statuses,), daemon=True).start()
                m._write_command = asked
                pending.append([{"state": "agent", "mine": False}, {"state": "awaiting_user", "mine": True}, {"state": "agent", "mine": True}])
                print(m.tool_handoff({"timeout_s": 5}) == m.HANDOFF_BACK)
                pending.append([{"state": "dismissed", "mine": True}])
                print(m.tool_handoff({"timeout_s": 5}) == m.HANDOFF_CANCELLED)
                pending.append([{"state": "dismissed", "mine": False}, {"state": "awaiting_user", "mine": True}, {"state": "awaiting_user", "mine": False}])
                print(m.tool_handoff({"timeout_s": 5}) == m.HANDOFF_SUPERSEDED)
                pending.append([])
                print(m.tool_handoff({"timeout_s": 0.1}) == m.HANDOFF_TIMED_OUT)
                print(json.loads(m.COMMAND_FILE.read_text())["requestId"])
                """.trimIndent(),
            ).lines()

        // Another request's answer, before this one was shown, never ends it: it is someone else's.
        assertEquals(List(4) { listOf("asked False", "True") }.flatten(), answers.dropLast(1))
        assertEquals(
            BrowserCommand.Handoff(rootfs, answers.last()),
            consumeBrowserCommand("/root/project", LocalAgent.CLAUDE_CODE, runtime),
        )
    }

    /**
     * A newer handoff can take the user's screen before this tool ever reads its own `awaiting_user`:
     * by replacing this command before the app takes it, or once the app has taken it. Either way the
     * tool says so at once instead of waiting out its timeout.
     */
    @Test
    fun `browser_handoff is superseded even when its own request was never seen shown`() {
        val answers =
            run(
                """
                import json, tempfile
                from pathlib import Path
                folder = Path(tempfile.mkdtemp())
                m.COMMAND_FILE = folder / "browser-command.json"
                m.STATUS_FILE = folder / "browser-status.json"
                m.HANDOFF_POLL_S = 0.01
                m._find_devtools_socket = lambda: "webview_devtools_remote_1"
                write_command = m._write_command
                def replaced(command):
                    write_command(command)
                    write_command({"action": "handoff", "requestId": "other"})
                    m.STATUS_FILE.write_text(json.dumps({"state": "awaiting_user", "requestId": "other"}))
                m._write_command = replaced
                print(m.tool_handoff({"timeout_s": 5}) == m.HANDOFF_SUPERSEDED)
                def taken(command):
                    write_command(command)
                    m.COMMAND_FILE.unlink()
                    m.STATUS_FILE.write_text(json.dumps({"state": "awaiting_user", "requestId": "newer"}))
                m._write_command = taken
                print(m.tool_handoff({"timeout_s": 5}) == m.HANDOFF_SUPERSEDED)
                """.trimIndent(),
            )

        assertEquals("True\nTrue", answers)
    }

    /** The MCP client gives up at 120 s: the wait for the browser must come out of the tool's own timeout. */
    @Test
    fun `browser_handoff counts the wait for the browser against its timeout`() {
        val answer =
            run(
                """
                import tempfile
                from pathlib import Path
                class Clock:
                    now = 0.0
                    def monotonic(self):
                        return self.now
                    def sleep(self, seconds):
                        self.now += seconds
                    def time(self):
                        return 1800000000.0
                clock = Clock()
                m.time = clock
                folder = Path(tempfile.mkdtemp())
                m.COMMAND_FILE = folder / "browser-command.json"
                m.STATUS_FILE = folder / "browser-status.json"
                def socket_after(timeout):
                    clock.now += 8.0
                    return "webview_devtools_remote_1"
                m._wait_for_socket = socket_after
                print(m.tool_handoff({"timeout_s": 10}) == m.HANDOFF_TIMED_OUT, clock.now <= 10.5)
                """.trimIndent(),
            )

        assertEquals("True True", answer)
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
