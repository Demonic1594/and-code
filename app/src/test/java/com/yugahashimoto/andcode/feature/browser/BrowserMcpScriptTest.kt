package com.yugahashimoto.andcode.feature.browser

import org.junit.Assert.assertEquals
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
    @get:Rule
    val folder = TemporaryFolder()

    private val script =
        listOf("src/main/assets/scripts/andcode-browser-mcp.py", "app/src/main/assets/scripts/andcode-browser-mcp.py")
            .map(::File)
            .first { it.isFile }

    @Before
    fun needsPython() {
        assumeTrue("needs python3", runCatching { run("print('ok')") == "ok" }.getOrDefault(false))
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
    fun `without a visible page the first page is used and without pages none`() {
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
