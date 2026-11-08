package com.yugahashimoto.andcode.feature.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The browser tools that read the page's log, press keys and scroll (`andcode-browser-mcp.py`), and
 * the page agent's log they read (`andcode-devtools.js`), run under the host's python3 and node.
 * Skipped where either is missing.
 */
class BrowserAgentToolsTest {
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
    fun `the console log lists entries oldest first and says what to pass next`() {
        val log =
            run(
                """
                print(m.format_console({
                    "url": "http://127.0.0.1:5173/", "doc": "k3x9", "next": 9, "dropped": 3,
                    "entries": [
                        {"kind": "warn", "text": "slow render", "source": "http://127.0.0.1:5173/app.js:4"},
                        {"kind": "exception", "text": "TypeError: x is undefined", "source": "app.js:3:9",
                         "stack": "TypeError: x is undefined\n    at load (app.js:3:9)\n    at main (app.js:9:1)"},
                        {"kind": "network", "text": "GET http://127.0.0.1:5173/api/users?key=… -> 404 Not Found"},
                    ],
                }, 0))
                print(m.format_console({"url": "http://x/", "doc": "k3x9", "next": 9, "entries": []}, 9))
                print(m.format_console({"url": "http://x/", "doc": "a1", "next": 3, "entries": [{"kind": "log", "text": str(i)} for i in range(3)]}, 0, 2))
                print(m.format_console({"url": "http://x/next", "doc": "b2", "reset": True, "next": 0, "entries": []}, 0))
                print(m.format_console({"url": "http://x/", "doc": "c3", "next": 1, "entries": [{"kind": "log", "text": "y" * 500}]}, 0))
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
                "[network] GET http://127.0.0.1:5173/api/users?key=… -> 404 Not Found",
                "next: k3x9:9 (pass since=k3x9:9 to read only what comes after this)",
                "http://x/",
                "no console messages, errors or failed requests since the last read",
                "next: k3x9:9 (pass since=k3x9:9 to read only what comes after this)",
                "http://x/",
                "... 1 earlier entry is not shown",
                "[log] 1",
                "[log] 2",
                "next: a1:3 (pass since=a1:3 to read only what comes after this)",
                "http://x/next",
                "the page changed since that cursor (a navigation or reload); this is the new page's log",
                "no console messages, errors or failed requests",
                "next: b2:0 (pass since=b2:0 to read only what comes after this)",
                "http://x/",
                "[log] " + "y".repeat(394) + "... (506 chars)",
                "next: c3:1 (pass since=c3:1 to read only what comes after this)",
            ),
            log.lines(),
        )
    }

    @Test
    fun `a cursor names its page and position, and a bad one is refused before the browser is reached`() {
        val parsed = run("""for since in (None, "", 7, "7", "k3x9:40", " k3x9:0 "): print(m.parse_since(since))""")
        val refused =
            run("""for since in ("abc", "k3x9:", "k3x9:²", -1, 2.5, True, float("nan"), [1]): print(m.tool_console({"since": since}))""")

        assertEquals(
            listOf("(None, 0)", "(None, 0)", "(None, 7)", "(None, 7)", "('k3x9', 40)", "('k3x9', 0)"),
            parsed.lines(),
        )
        assertEquals(List(8) { "since must be the next value from an earlier browser_console reply" }, refused.lines())
    }

    @Test
    fun `keys become the key events a person pressing them sends`() {
        // One line per key: key, code, virtual key code (also the native one), modifier bits, typed text.
        val events =
            run(
                """
                for spec in ("Enter", "shift+Tab", "ctrl+a", "A", "shift+b", "7", "ArrowDown", "space"):
                    e = m.key_event(spec)
                    assert e["nativeVirtualKeyCode"] == e["windowsVirtualKeyCode"]
                    print(repr((e["key"], e["code"], e["windowsVirtualKeyCode"], e["modifiers"], e.get("text"))))
                for spec in ("F13", "hyper+a", "", None, "ab", "é"):
                    print(m.key_event(spec))
                """.trimIndent(),
            )

        assertEquals(
            listOf(
                "('Enter', 'Enter', 13, 0, '\\r')",
                "('Tab', 'Tab', 9, 8, None)",
                "('a', 'KeyA', 65, 2, None)",
                "('a', 'KeyA', 65, 0, 'a')",
                "('B', 'KeyB', 66, 8, 'B')",
                "('7', 'Digit7', 55, 0, '7')",
                "('ArrowDown', 'ArrowDown', 40, 0, None)",
                "(' ', 'Space', 32, 0, ' ')",
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
    fun `a scroll says where it ended up, or that nothing moved, and a bad request never reaches the browser`() {
        val answers =
            run(
                """
                print(m.describe_scroll({"moved": True, "inner": False, "x": 0, "y": 1200, "width": 390, "height": 4000,
                                         "viewWidth": 390, "viewHeight": 800}))
                print(m.describe_scroll({"moved": True, "inner": True, "x": 0, "y": 300, "width": 390, "height": 900,
                                         "viewWidth": 390, "viewHeight": 500}))
                print(m.describe_scroll({"moved": False}))
                print(m.tool_scroll({"direction": "sideways"}))
                for amount in (0, -5, "abc", float("inf"), float("nan"), True):
                    print(m.tool_scroll({"amount": amount}))
                print(m.scroll_request({"direction": "Up", "amount": 300}))
                print(m.scroll_request({}))
                """.trimIndent(),
            )

        assertEquals(
            listOf(
                "scrolled the page to x=0, y=1200 of 390x4000 (view 390x800); call browser_read for what is now in view",
                "scrolled a scrolling panel to x=0, y=300 of 390x900 (view 390x500); call browser_read for what is now in view",
                "the page did not move: it is already at that end, or nothing here scrolls that way",
                "direction must be down, up, left or right",
            ) + List(6) { "amount must be a positive number of CSS pixels" } +
                listOf("((0, -1), 300.0)", "((0, 1), None)"),
            answers.lines(),
        )
    }

    @Test
    fun `the scroll script is valid javascript and scrolls instantly`() {
        val nodeStarts = runCatching { ProcessBuilder("node", "--version").start().waitFor(30, TimeUnit.SECONDS) }.getOrDefault(false)
        assumeTrue("needs node", nodeStarts)
        val body = run("print(m.SCROLL_JS % (0.0, 640.0))")
        assertEquals(2, Regex("behavior: 'instant'").findAll(body).count())
        val snippet = folder.newFile("scroll.js").apply { writeText(body) }
        val check =
            folder.newFile(
                "check-scroll.js",
            ).apply { writeText("new Function('return ' + require('fs').readFileSync(process.argv[2], 'utf8'));") }

        val process = ProcessBuilder("node", check.absolutePath, snippet.absolutePath).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()

        assertTrue(output, process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0)
    }

    @Test
    fun `the page agent keeps a log of console lines, exceptions and failed requests for the tools`() {
        val output =
            runPageAgent(
                """
                const page = newPage(true);
                page.console.debug('${AgentActivity.MARKER}');
                page.console.error('boom', { a: 1 });
                page.console.log('hello');
                page.listeners.error.forEach((fn) => fn({ target: page, message: 'TypeError: x is undefined',
                  filename: 'http://127.0.0.1:5173/app.js', lineno: 3, colno: 9 }));
                page.listeners.error.forEach((fn) => fn({ target: { tagName: 'IMG', src: 'http://me:pw@cdn/x.png?sig=abc&w=2#t=9' } }));
                page.abortNext = true;
                page.fetch('/api/cancelled').catch(() => {}).then(() => page.fetch('/api/users?token=secret')).then(() => {
                  const all = JSON.parse(page.__andcodeDT.agentLog());
                  console.log(all.entries.map((e) => e.seq + ' ' + e.kind + ' ' + e.text).join('\n'));
                  console.log('next ' + all.next + ' reset ' + all.reset);
                  console.log(JSON.parse(page.__andcodeDT.agentLog(3, all.doc)).entries.map((e) => e.seq).join(','));
                  // A cursor from another document reads this page's whole log, saying so.
                  const other = JSON.parse(page.__andcodeDT.agentLog(40, 'olddoc'));
                  console.log('other ' + other.reset + ' ' + other.entries.length);
                  // Without the bridge (an old WebView), console lines still reach the agent's log.
                  const bare = newPage(false);
                  bare.console.warn('no bridge');
                  console.log(JSON.parse(bare.__andcodeDT.agentLog()).entries.map((e) => e.kind + ' ' + e.text).join(','));
                });
                """.trimIndent(),
            )

        assertEquals(
            listOf(
                "1 error boom {\"a\":1}",
                "2 log hello",
                "3 exception TypeError: x is undefined",
                "4 failed load Failed to load img: http://cdn/x.png?sig=…&w=…#…",
                "5 network GET http://127.0.0.1:5173/api/users?token=… -> 404 Not Found",
                "next 5 reset false",
                "4,5",
                "other true 5",
                "warn no bridge",
            ),
            output.lines(),
        )
    }

    /** Runs [body] under node with the page agent loaded into stubbed pages made by `newPage(withBridge)`. */
    private fun runPageAgent(body: String): String {
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
                    const source = require('fs').readFileSync(process.argv[2], 'utf8');
                    function newPage(withBridge) {
                      const listeners = {};
                      const quiet = {};
                      ['log', 'info', 'warn', 'error', 'debug', 'trace', 'table', 'dir', 'assert', 'count', 'time',
                       'timeEnd', 'group', 'groupCollapsed', 'groupEnd', 'clear'].forEach((name) => { quiet[name] = () => {}; });
                      const page = {
                        console: quiet, location: { href: 'http://127.0.0.1:5173/' }, listeners: listeners,
                        document: { title: 'App', addEventListener() {} }, performance: { now: () => 1 },
                        setTimeout: () => 0, Date, JSON, Math, String, Number, Array, Object, Error, Promise, Map, Set, RegExp, URL,
                        addEventListener: (type, fn) => { (listeners[type] = listeners[type] || []).push(fn); },
                        Request: function () {},
                        abortNext: false,
                      };
                      page.fetch = (url) => {
                        if (page.abortNext) {
                          page.abortNext = false;
                          const error = new Error('The user aborted a request.');
                          error.name = 'AbortError';
                          return Promise.reject(error);
                        }
                        return Promise.resolve({ status: 404, statusText: 'Not Found', headers: { get: () => null, forEach() {} },
                                                 type: 'basic', redirected: false, url: url });
                      };
                      if (withBridge) page.__andcodeDevtoolsBridge = { postMessage() {} };
                      page.window = page;
                      page.top = page;
                      vm.createContext(page);
                      vm.runInContext(source, page);
                      return page;
                    }
                    """.trimIndent()
                        +
                        "\n" + body,
                )
            }

        val process = ProcessBuilder("node", probe.absolutePath, agent.absolutePath).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(output, process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0)
        return output.trim()
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
}
