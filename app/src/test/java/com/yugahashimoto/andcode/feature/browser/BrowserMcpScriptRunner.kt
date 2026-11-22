package com.yugahashimoto.andcode.feature.browser

import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/** Runs the guest's browser MCP script under the host's python3, loaded as module `m`. */
internal class BrowserMcpScriptRunner(
    private val folder: TemporaryFolder,
) {
    /** Loads the script as module `m` and runs [code], returning what it printed. */
    fun run(code: String): String {
        val driver =
            folder.newFile().apply {
                writeText(
                    "import importlib.util\n" +
                        "spec = importlib.util.spec_from_file_location('m', r'''${script.absolutePath}''')\n" +
                        "m = importlib.util.module_from_spec(spec)\n" +
                        "spec.loader.exec_module(m)\n" +
                        code + "\n",
                )
            }
        val out = folder.newFile()
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

    companion object {
        val script: File =
            listOf("src/main/assets/scripts/andcode-browser-mcp.py", "app/src/main/assets/scripts/andcode-browser-mcp.py")
                .map(::File)
                .first { it.isFile }

        /** Only whether python3 starts: a broken script must fail the tests, not skip them. */
        fun pythonStarts(): Boolean =
            runCatching { ProcessBuilder("python3", "-c", "pass").start().waitFor(30, TimeUnit.SECONDS) }.getOrDefault(false)
    }
}
