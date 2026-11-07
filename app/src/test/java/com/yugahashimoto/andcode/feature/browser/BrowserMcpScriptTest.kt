package com.yugahashimoto.andcode.feature.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs the guest's browser MCP script under the host's python3: how it finds the WebView's DevTools
 * socket and which page it drives. Skipped where python3 is missing.
 */
class BrowserMcpScriptTest {
    private val staleRef = "that handle is gone from the page; call browser_read again for fresh ones"
    private val noTarget = "pass a handle from browser_read, or both x and y"
    private val notEditable = "that element does not take typed text; pass the handle of an enabled text field"
    private val badRef = "ref must be the whole-number handle shown in [brackets] by browser_read"

    @get:Rule
    val folder = TemporaryFolder()

    private val script =
        listOf("src/main/assets/scripts/andcode-browser-mcp.py", "app/src/main/assets/scripts/andcode-browser-mcp.py")
            .map(::File)
            .first { it.isFile }

    @Before
    fun needsPython() {
        // Only whether python3 starts: a broken script must fail the tests, not skip them.
        val starts =
            runCatching {
                ProcessBuilder("python3", "-c", "pass").start().waitFor(30, TimeUnit.SECONDS)
            }.getOrDefault(false)
        assumeTrue("needs python3", starts)
    }

    @Test
    fun `abstract socket names are read in the text form Linux prints`() {
        val listing =
            "Num       RefCount Protocol Flags    Type St Inode Path\\n" +
                "0000: 00000002 00000000 00010000 0001 01 1234 @webview_devtools_remote_4242\\n" +
                "0000: 00000002 00000000 00010000 0001 01 1235 @other_socket\\n" +
                "0000: 00000002 00000000 00010000 0001 01 1236 /dev/socket/zygote\\n"

        val names = run("print(m.socket_names_from_proc_net_unix('$listing'))")

        assertEquals("['webview_devtools_remote_4242']", names)
    }

    @Test
    fun `hex encoded names are accepted too`() {
        val hex = "webview_devtools_remote_7".toByteArray().joinToString("") { "%02x".format(it) }
        val listing = "header\\n0000: 00000002 00000000 00010000 0001 01 1 $hex\\n"

        assertEquals("['webview_devtools_remote_7']", run("print(m.socket_names_from_proc_net_unix('$listing'))"))
    }

    @Test
    fun `the socket of this process is found when it listens`() {
        assumeTrue("abstract sockets are Linux only", isLinux())
        val found =
            run(
                """
                import os, socket
                s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
                s.bind("\0" + m.DEVTOOLS_PREFIX + str(os.getpid()))
                s.listen(1)
                print(m._find_devtools_socket() == m.DEVTOOLS_PREFIX + str(os.getpid()))
                """.trimIndent(),
            )

        assertEquals("True", found)
    }

    @Test
    fun `a body is read to its content length even when the json ends with a newline`() {
        // Chrome 156 ends its /json bodies with a newline; the old sentinel loop waited for a
        // closing bracket that never arrived and blocked until the socket timed out.
        val body =
            run(
                """
                class Sock:
                    def __init__(self, chunks):
                        self.chunks = list(chunks)
                    def recv(self, n):
                        return self.chunks.pop(0) if self.chunks else b""
                head = b"HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\n"
                sock = Sock([head[:11], head[11:] + b'[1]\n', b'left over, dropped'])
                print(m._read_http_body(sock))
                """.trimIndent(),
            )

        assertEquals("b'[1]\\n'", body)
    }

    @Test
    fun `a body without a length is read until the socket closes, and chunked bodies decode`() {
        val outcome =
            run(
                """
                class Sock:
                    def __init__(self, chunks):
                        self.chunks = list(chunks)
                    def recv(self, n):
                        return self.chunks.pop(0) if self.chunks else b""
                head = b"HTTP/1.1 200 OK\r\n\r\n"
                print(m._read_http_body(Sock([head, b'[2]'])))
                print(m._dechunk(b'3\r\n[1]\r\n0\r\n\r\n'))
                print(m._dechunk(b'[{"a": 1}]'))
                """.trimIndent(),
            )

        assertEquals("b'[2]'\nb'[1]'\nb'[{\"a\": 1}]'", outcome)
    }

    @Test
    fun `the target list is read from a devtools socket that ends its json with a newline`() {
        assumeTrue("abstract sockets are Linux only", isLinux())
        val url =
            run(
                """
                import os, socket, threading
                name = m.DEVTOOLS_PREFIX + str(os.getpid())
                srv = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
                srv.bind("\0" + name)
                srv.listen(1)
                payload = b'[{"type": "page", "url": "kept-open"}]\n'
                def serve():
                    conn, _ = srv.accept()
                    conn.recv(4096)
                    conn.sendall(b"HTTP/1.1 200 OK\r\nContent-Length: %d\r\n\r\n%s" % (len(payload), payload))
                    conn.close()
                threading.Thread(target=serve, daemon=True).start()
                print(m.CdpSession(name).list_targets()[0]["url"])
                """.trimIndent(),
            )

        assertEquals("kept-open", url)
    }

    @Test
    fun `the socket is found by process id when the socket list cannot be read`() {
        assumeTrue("abstract sockets are Linux only", isLinux())
        val found =
            run(
                """
                import builtins, os, socket
                real_open = builtins.open
                def hidden(path, *args, **kwargs):
                    if path == "/proc/net/unix":
                        raise PermissionError(path)
                    return real_open(path, *args, **kwargs)
                m.open = hidden
                m._visible_pids = lambda: ["1", str(os.getpid())]
                s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
                s.bind("\0" + m.DEVTOOLS_PREFIX + str(os.getpid()))
                s.listen(1)
                print(m._find_devtools_socket() == m.DEVTOOLS_PREFIX + str(os.getpid()))
                """.trimIndent(),
            )

        assertEquals("True", found)
    }

    @Test
    fun `the visible page is driven rather than another WebView`() {
        val chosen =
            run(
                """
                import json
                targets = [
                    {"type": "page", "url": "artifact", "description": json.dumps({"attached": True, "visible": False})},
                    {"type": "service_worker", "url": "sw"},
                    {"type": "page", "url": "browser", "description": json.dumps({"attached": True, "visible": True})},
                ]
                print(m.choose_target(targets)["url"])
                """.trimIndent(),
            )

        assertEquals("browser", chosen)
    }

    @Test
    fun `a lone page is used even when not reported on screen, and without pages none`() {
        val chosen =
            run(
                """
                print(m.choose_target([{"type": "page", "url": "first", "description": "not json"}])["url"])
                print(m.choose_target([{"type": "page", "url": "odd", "description": 42}])["url"])
                print(m.choose_target([{"type": "worker", "url": "w"}]))
                """.trimIndent(),
            )

        assertEquals("first\nodd\nNone", chosen)
    }

    @Test
    fun `an ambiguous choice is refused rather than guessed`() {
        val refused =
            run(
                """
                import json
                shown = json.dumps({"attached": True, "visible": True})
                for targets in (
                    [{"type": "page", "url": "a", "description": shown}, {"type": "page", "url": "b", "description": shown}],
                    [{"type": "page", "url": "a"}, {"type": "page", "url": "b"}],
                ):
                    try:
                        m.choose_target(targets)
                        print("chosen")
                    except m.CdpError as error:
                        print(str(error) == m.AMBIGUOUS)
                """.trimIndent(),
            )

        assertEquals("True\nTrue", refused)
    }

    @Test
    fun `each page session tells the browser an agent is acting, with the marker it listens for`() {
        val sent =
            run(
                """
                class FakeWs:
                    def __init__(self):
                        self.sent = []
                    def send_text(self, text):
                        self.sent.append(text)
                    def recv_text(self):
                        return '{"id": 1, "result": {}}'
                ws = FakeWs()
                m._PageSession(ws).announce()
                import json
                print(json.loads(ws.sent[0])["params"]["expression"])
                """.trimIndent(),
            )

        assertEquals("console.debug(\"${AgentActivity.MARKER}\")", sent)
    }

    @Test
    fun `a refused announcement is ignored but a broken socket still fails the tool`() {
        val outcome =
            run(
                """
                class RefusingWs:
                    def send_text(self, text):
                        pass
                    def recv_text(self):
                        return '{"id": 1, "error": {"message": "Cannot find context"}}'
                class DeadWs:
                    def send_text(self, text):
                        raise TimeoutError("timed out")
                m._PageSession(RefusingWs()).announce()
                print("refused ignored")
                try:
                    m._PageSession(DeadWs()).announce()
                    print("swallowed")
                except OSError:
                    print("raised")
                """.trimIndent(),
            )

        assertEquals("refused ignored\nraised", outcome)
    }

    @Test
    fun `the page console hook keeps the marker out of the user's console`() {
        val devtools =
            listOf("src/main/assets/devtools/andcode-devtools.js", "app/src/main/assets/devtools/andcode-devtools.js")
                .map(::File)
                .first { it.isFile }
                .readText()

        assertTrue(devtools.contains("var AGENT_ACTIVITY_MARKER = '${AgentActivity.MARKER}';"))
    }

    @Test
    fun `the outline lists one line per element with its handle`() {
        val outline =
            run(
                """
                print(m.format_outline({
                    "title": "Shop", "url": "http://x/", "headings": ["Cart"], "total": 4,
                    "elements": [
                        {"ref": 1, "tag": "button", "role": "", "type": "", "name": "Buy", "value": "", "href": "",
                         "checked": False, "disabled": True},
                        {"ref": 2, "tag": "input", "role": "", "type": "email", "name": "Email", "value": "a@b.c", "href": "",
                         "checked": False, "disabled": False},
                        {"ref": 3, "tag": "a", "role": "", "type": "", "name": "Help", "value": "", "href": "/help",
                         "checked": False, "disabled": False},
                    ],
                }))
                """.trimIndent(),
            )

        assertEquals(
            listOf(
                "Shop - http://x/",
                "# Cart",
                "[1] button \"Buy\" (disabled)",
                "[2] input(email) \"Email\" value=\"a@b.c\"",
                "[3] a \"Help\" -> /help",
                "... 1 more not shown; scroll or navigate to narrow it down",
            ),
            outline.lines(),
        )
    }

    @Test
    fun `a handle becomes a lookup of that element and nothing else`() {
        val expression = run("""print(m.ref_expression("7", "el.click();"))""")

        assertTrue(expression, expression.contains("""[data-andcode-ref="7"]"""))
        val injected =
            run(
                """
                try:
                    m.ref_expression("7\"]) || alert(1) || ([", "el.click();")
                except ValueError:
                    print("refused")
                """.trimIndent(),
            )
        assertEquals("refused", injected)
    }

    @Test
    fun `clicking a handle that left the page says to read again`() {
        val answer =
            run(
                """
                class Page:
                    def call(self, method, **params):
                        return {"result": {"value": "stale"}}
                    def close(self):
                        pass
                class Session:
                    def open_page_session(self):
                        return Page()
                m._session = lambda: Session()
                print(m.tool_click({"ref": 4}))
                print(m.tool_type({"ref": 4, "text": "hi"}))
                """.trimIndent(),
            )

        assertEquals("${staleRef}\n$staleRef", answer)
    }

    @Test
    fun `a click with neither a handle nor both coordinates says what to pass`() {
        val answer =
            run(
                """
                def fail():
                    raise AssertionError("no page should be opened")
                m._session = fail
                print(m.tool_click({}))
                print(m.tool_click({"x": 10}))
                """.trimIndent(),
            )

        assertEquals("$noTarget\n$noTarget", answer)
    }

    @Test
    fun `a handle that is not a whole number says what a handle is`() {
        val answer =
            run(
                """
                for bad in ["abc", "1.5", 1.5, True, None]:
                    try:
                        m.ref_expression(bad, "el.click();")
                        print("accepted", bad)
                    except ValueError as exc:
                        print(exc)
                print(m.ref_expression(3.0, "x").count('data-andcode-ref="3"'))
                """.trimIndent(),
            )

        assertEquals(List(5) { badRef }.joinToString("\n") + "\n1", answer)
    }

    @Test
    fun `typing by handle into something that is not a text field types nothing`() {
        val answer =
            run(
                """
                calls = []
                class Page:
                    def call(self, method, **params):
                        calls.append(method)
                        return {"result": {"value": "not editable"}}
                    def close(self):
                        pass
                class Session:
                    def open_page_session(self):
                        return Page()
                m._session = lambda: Session()
                print(m.tool_type({"ref": 2, "text": "hi"}))
                print("Input.insertText" in calls)
                """.trimIndent(),
            )

        assertEquals("$notEditable\nFalse", answer)
    }

    @Test
    fun `a page script that throws is reported rather than read as an empty page`() {
        val answer =
            run(
                """
                class Page:
                    def call(self, method, **params):
                        return {"result": {}, "exceptionDetails": {"exception": {"description": "TypeError: boom"}}}
                    def close(self):
                        pass
                class Session:
                    def open_page_session(self):
                        return Page()
                m._session = lambda: Session()
                for call in (lambda: m.tool_read({}), lambda: m.tool_click({"ref": 1})):
                    try:
                        print(call())
                    except m.CdpError as exc:
                        print(exc)
                """.trimIndent(),
            )

        assertEquals("the page script failed: TypeError: boom\nthe page script failed: TypeError: boom", answer)
    }

    @Test
    fun `a password field is never named by what was typed, and a cloned handle is renumbered`() {
        val nodeStarts = runCatching { ProcessBuilder("node", "--version").start().waitFor(30, TimeUnit.SECONDS) }.getOrDefault(false)
        assumeTrue("needs node", nodeStarts)
        val body = Regex("READ_JS = r\"\"\"([\\s\\S]*?)\"\"\"").find(script.readText())!!.groupValues[1].replace("%d", "10")
        val page =
            folder.newFile("page.js").apply {
                writeText(
                    """
                    function node(tag, props) {
                      const attrs = Object.assign({}, props.attrs || {});
                      return Object.assign({
                        tagName: tag.toUpperCase(), innerText: '', labels: [], value: '',
                        getAttribute: (k) => (k in attrs ? attrs[k] : null),
                        setAttribute: (k, v) => { attrs[k] = String(v); },
                        getBoundingClientRect: () => ({ left: 0, top: 0, width: 10, height: 10 }),
                      }, props);
                    }
                    const els = [
                      node('input', { type: 'password', value: 'hunter2' }),
                      node('input', { type: 'submit', value: 'Sign in' }),
                      node('button', { innerText: 'A', attrs: { 'data-andcode-ref': '1' } }),
                      node('button', { innerText: 'B', attrs: { 'data-andcode-ref': '1' } }),
                    ];
                    global.window = { __andcodeRefs: { next: 2 } };
                    global.document = { title: 't', querySelectorAll: (q) => (q === 'h1,h2,h3' ? [] : els) };
                    global.location = { href: 'https://example.test/' };
                    global.getComputedStyle = () => ({ visibility: 'visible', display: 'block' });
                    console.log(eval(require('fs').readFileSync(process.argv[2], 'utf8')));
                    """.trimIndent(),
                )
            }
        val snippet = folder.newFile("read-run.js").apply { writeText(body) }

        val process = ProcessBuilder("node", page.absolutePath, snippet.absolutePath).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(output, process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0)

        assertFalse(output, output.contains("hunter2"))
        assertTrue(output, output.contains("\"name\":\"Sign in\""))
        val refs = Regex("\"ref\":(\\d+)").findAll(output).map { it.groupValues[1] }.toList()
        assertEquals(output, refs.size, refs.toSet().size)
    }

    @Test
    fun `the console log lists entries oldest first and says what to pass next`() {
        val log =
            run(
                """
                print(m.format_console({
                    "url": "http://127.0.0.1:5173/", "next": 9, "dropped": 3,
                    "entries": [
                        {"kind": "warn", "text": "slow render", "source": "http://127.0.0.1:5173/app.js:4"},
                        {"kind": "exception", "text": "TypeError: x is undefined", "source": "app.js:3:9",
                         "stack": "TypeError: x is undefined\n    at load (app.js:3:9)\n    at main (app.js:9:1)"},
                        {"kind": "network", "text": "GET http://127.0.0.1:5173/api/users -> 404 Not Found"},
                    ],
                }, 0))
                print(m.format_console({"url": "http://x/", "next": 9, "entries": []}, 9))
                print(m.format_console({"url": "http://x/", "next": 3, "entries": [{"kind": "log", "text": str(i)} for i in range(3)]}, 0, 2))
                """.trimIndent(),
            )

        assertEquals(
            listOf(
                "http://127.0.0.1:5173/",
                "... 3 older entries were dropped to keep the log short",
                "[warn] slow render (http://127.0.0.1:5173/app.js:4)",
                "[exception] TypeError: x is undefined (app.js:3:9)",
                "    at load (app.js:3:9)",
                "    at main (app.js:9:1)",
                "[network] GET http://127.0.0.1:5173/api/users -> 404 Not Found",
                "next: 9 (pass since=9 to read only what comes after this)",
                "http://x/",
                "no console messages, errors or failed requests since the last read",
                "next: 9 (pass since=9 to read only what comes after this)",
                "http://x/",
                "... 1 more entries before these are not shown",
                "[log] 1",
                "[log] 2",
                "next: 3 (pass since=3 to read only what comes after this)",
            ),
            log.lines(),
        )
    }

    @Test
    fun `since must be a whole number, checked before the browser is reached`() {
        val answers = run("""for since in ("3", -1, 2.5, True): print(m.tool_console({"since": since}))""")

        assertEquals(List(4) { "since must be a whole number from an earlier browser_console reply" }, answers.lines())
    }

    @Test
    fun `keys become the key events a person pressing them sends`() {
        val events =
            run(
                """
                import json
                for spec in ("Enter", "shift+Tab", "ctrl+a", "A", "shift+b", "7", "ArrowDown", "space"):
                    print(json.dumps(m.key_event(spec), sort_keys=True))
                for spec in ("F13", "hyper+a", "", None, "ab", "é"):
                    print(m.key_event(spec))
                """.trimIndent(),
            )

        assertEquals(
            listOf(
                """{"code": "Enter", "key": "Enter", "modifiers": 0, "nativeVirtualKeyCode": 13, "text": "\r", "windowsVirtualKeyCode": 13}""",
                """{"code": "Tab", "key": "Tab", "modifiers": 8, "nativeVirtualKeyCode": 9, "windowsVirtualKeyCode": 9}""",
                """{"code": "KeyA", "key": "a", "modifiers": 2, "nativeVirtualKeyCode": 65, "windowsVirtualKeyCode": 65}""",
                """{"code": "KeyA", "key": "a", "modifiers": 0, "nativeVirtualKeyCode": 65, "text": "a", "windowsVirtualKeyCode": 65}""",
                """{"code": "KeyB", "key": "B", "modifiers": 8, "nativeVirtualKeyCode": 66, "text": "B", "windowsVirtualKeyCode": 66}""",
                """{"code": "Digit7", "key": "7", "modifiers": 0, "nativeVirtualKeyCode": 55, "text": "7", "windowsVirtualKeyCode": 55}""",
                """{"code": "ArrowDown", "key": "ArrowDown", "modifiers": 0, "nativeVirtualKeyCode": 40, "windowsVirtualKeyCode": 40}""",
                """{"code": "Space", "key": " ", "modifiers": 0, "nativeVirtualKeyCode": 32, "text": " ", "windowsVirtualKeyCode": 32}""",
                "None",
                "None",
                "None",
                "None",
                "None",
                "None",
            ),
            events.lines(),
        )
    }

    @Test
    fun `a key that is not one says which keys there are, before the browser is reached`() {
        val answer = run("""print(m.tool_press_key({"key": "F13"}))""")

        assertTrue(answer, answer.startsWith("key must be a key name (Enter, Tab"))
    }

    @Test
    fun `a scroll says where it ended up, or that nothing moved`() {
        val answers =
            run(
                """
                print(m.describe_scroll({"moved": True, "inner": False, "x": 0, "y": 1200, "width": 390, "height": 4000,
                                         "viewWidth": 390, "viewHeight": 800}))
                print(m.describe_scroll({"moved": True, "inner": True, "x": 0, "y": 300, "width": 390, "height": 900,
                                         "viewWidth": 390, "viewHeight": 500}))
                print(m.describe_scroll({"moved": False}))
                print(m.tool_scroll.__name__ and m.SCROLL_DIRECTIONS.get("sideways"))
                """.trimIndent(),
            )

        assertEquals(
            listOf(
                "scrolled the page to x=0, y=1200 of 390x4000 (view 390x800); call browser_read for what is now in view",
                "scrolled a scrolling panel to x=0, y=300 of 390x900 (view 390x500); call browser_read for what is now in view",
                "the page did not move: it is already at that end, or nothing here scrolls that way",
                "None",
            ),
            answers.lines(),
        )
    }

    @Test
    fun `the page agent keeps a log of console lines, exceptions and failed requests for the tools`() {
        val nodeStarts = runCatching { ProcessBuilder("node", "--version").start().waitFor(30, TimeUnit.SECONDS) }.getOrDefault(false)
        assumeTrue("needs node", nodeStarts)
        val agent =
            listOf("src/main/assets/devtools/andcode-devtools.js", "app/src/main/assets/devtools/andcode-devtools.js")
                .map(::File)
                .first { it.isFile }
        // Just enough of a browser for the agent to install its hooks.
        val probe =
            folder.newFile("probe.js").apply {
                writeText(
                    """
                    const vm = require('vm');
                    const listeners = {};
                    const quiet = {};
                    ['log', 'info', 'warn', 'error', 'debug', 'trace', 'table', 'dir', 'assert', 'count', 'time',
                     'timeEnd', 'group', 'groupCollapsed', 'groupEnd', 'clear'].forEach((name) => { quiet[name] = () => {}; });
                    const page = {
                      console: quiet, location: { href: 'http://127.0.0.1:5173/' },
                      document: { title: 'App', addEventListener() {} }, performance: { now: () => 1 },
                      setTimeout: () => 0, Date, JSON, Math, String, Number, Array, Object, Error, Promise, Map, Set, RegExp, URL,
                      addEventListener: (type, fn) => { (listeners[type] = listeners[type] || []).push(fn); },
                      __andcodeDevtoolsBridge: { postMessage() {} },
                      Request: function () {},
                      fetch: (url) => Promise.resolve({ status: 404, statusText: 'Not Found', headers: { get: () => null, forEach() {} },
                                                        type: 'basic', redirected: false, url: url }),
                    };
                    page.window = page;
                    page.top = page;
                    vm.createContext(page);
                    vm.runInContext(require('fs').readFileSync(process.argv[2], 'utf8'), page);
                    page.console.debug('${AgentActivity.MARKER}');
                    page.console.error('boom', { a: 1 });
                    page.console.log('hello');
                    listeners.error.forEach((fn) => fn({ target: page, message: 'TypeError: x is undefined',
                      filename: 'http://127.0.0.1:5173/app.js', lineno: 3, colno: 9 }));
                    page.fetch('/api/users').then(() => {
                      const all = JSON.parse(page.__andcodeDT.agentLog());
                      console.log(all.entries.map((e) => e.seq + ' ' + e.kind + ' ' + e.text).join('\n'));
                      console.log('next ' + all.next);
                      console.log(JSON.parse(page.__andcodeDT.agentLog(3)).entries.map((e) => e.seq).join(','));
                    });
                    """.trimIndent(),
                )
            }

        val process = ProcessBuilder("node", probe.absolutePath, agent.absolutePath).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(output, process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0)

        assertEquals(
            listOf(
                "1 error boom {\"a\":1}",
                "2 log hello",
                "3 exception TypeError: x is undefined",
                "4 network GET http://127.0.0.1:5173/api/users -> 404 Not Found",
                "next 4",
                "4",
            ),
            output.trim().lines(),
        )
    }

    @Test
    fun `the page script that lists elements is valid javascript`() {
        val nodeStarts = runCatching { ProcessBuilder("node", "--version").start().waitFor(30, TimeUnit.SECONDS) }.getOrDefault(false)
        assumeTrue("needs node", nodeStarts)
        val body = Regex("READ_JS = r\"\"\"([\\s\\S]*?)\"\"\"").find(script.readText())!!.groupValues[1].replace("%d", "5")
        val snippet = folder.newFile("read.js").apply { writeText(body) }
        val check =
            folder.newFile(
                "check.js",
            ).apply { writeText("new Function('return ' + require('fs').readFileSync(process.argv[2], 'utf8'));") }

        val process = ProcessBuilder("node", check.absolutePath, snippet.absolutePath).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()

        assertTrue(output, process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0)
    }

    /** Loads the script as module `m` and runs [code], returning what it printed. */
    private fun run(code: String): String {
        val driver =
            folder.newFile("driver.py").apply {
                writeText(
                    "import importlib.util\n" +
                        "spec = importlib.util.spec_from_file_location('m', r'''${script.absolutePath}''')\n" +
                        "m = importlib.util.module_from_spec(spec)\n" +
                        "spec.loader.exec_module(m)\n" +
                        code + "\n",
                )
            }
        val out = File(folder.root, "out.txt")
        // -B: loading the script must not leave a __pycache__ beside it in the assets.
        val process =
            ProcessBuilder("python3", "-I", "-B", driver.absolutePath)
                .redirectErrorStream(true)
                .redirectOutput(out)
                .start()
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("python3 timed out")
        }
        val output = out.readText()
        check(process.exitValue() == 0) { output }
        driver.delete()
        out.delete()
        return output.trim()
    }

    private fun isLinux(): Boolean = System.getProperty("os.name").orEmpty().contains("Linux", ignoreCase = true)
}
