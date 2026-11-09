package com.yugahashimoto.andcode.runtime.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class ClaudeChecksTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val script =
        listOf(
            "src/main/assets/scripts/and-code-claude-permission-hook.sh",
            "app/src/main/assets/scripts/and-code-claude-permission-hook.sh",
        ).map(::File).first { it.isFile }

    @Test
    fun `a check is kept as a script the hook can run, and a blank one removes it`() {
        val checks = ClaudeChecks(folder.root)
        checks.set(ClaudeChecks.Kind.BEFORE_FINISH, "  npm test  ")

        assertEquals(mapOf(ClaudeChecks.Kind.BEFORE_FINISH to "npm test"), checks.commands.value)
        assertEquals("npm test\n", File(folder.root, "checks/before-finish.sh").readText())
        assertEquals(checks.commands.value, ClaudeChecks(folder.root).commands.value)

        checks.set(ClaudeChecks.Kind.BEFORE_FINISH, " ")
        assertTrue(checks.commands.value.isEmpty())
        assertFalse(File(folder.root, "checks/before-finish.sh").exists())
    }

    @Test
    fun `the hooks run the check after the editing tools and before the turn ends`() {
        assertNull(ClaudeChecks.hooks(emptySet()))

        val hooks = ClaudeChecks.hooks(ClaudeChecks.Kind.entries.toSet())!!
        val afterEdit = hooks.getValue("PostToolUse").jsonArray.single().jsonObject
        assertEquals("Write|Edit|MultiEdit|NotebookEdit", afterEdit.getValue("matcher").jsonPrimitive.content)
        assertEquals(
            "${ClaudePermissionHooks.HOOK_GUEST_PATH} --check after-edit",
            afterEdit.getValue("hooks").jsonArray.single().jsonObject.getValue("command").jsonPrimitive.content,
        )
        val stop = hooks.getValue("Stop").jsonArray.single().jsonObject
        assertNull(stop["matcher"])
        assertEquals(
            "${ClaudePermissionHooks.HOOK_GUEST_PATH} --check before-finish",
            stop.getValue("hooks").jsonArray.single().jsonObject.getValue("command").jsonPrimitive.content,
        )
        val settings = Json.parseToJsonElement(ClaudeModels.cliSettings(false, checks = setOf(ClaudeChecks.Kind.AFTER_EDIT))!!).jsonObject
        assertEquals(setOf("PostToolUse"), settings.getValue("hooks").jsonObject.keys)
    }

    @Test
    fun `the hook passes a check that succeeds and sends a failing one's output back`() {
        assumeTrue("needs sh", File("/bin/sh").canExecute())
        val checks = ClaudeChecks(folder.root)

        assertEquals(0 to "", runCheck("after-edit", "{}"))

        checks.set(ClaudeChecks.Kind.AFTER_EDIT, "true")
        assertEquals(0 to "", runCheck("after-edit", "{}"))

        checks.set(ClaudeChecks.Kind.AFTER_EDIT, "echo 'src/a.ts:3 missing semicolon'; exit 1")
        val (status, output) = runCheck("after-edit", "{}")
        assertEquals(2, status)
        assertTrue(output, output.contains("after a file edit"))
        assertTrue(output, output.contains("src/a.ts:3 missing semicolon"))
    }

    @Test
    fun `a turn the finishing check already sent back once is let end`() {
        assumeTrue("needs sh", File("/bin/sh").canExecute())
        ClaudeChecks(folder.root).set(ClaudeChecks.Kind.BEFORE_FINISH, "echo 2 tests failed; exit 1")

        assertEquals(2, runCheck("before-finish", """{"hook_event_name":"Stop","stop_hook_active":false}""").first)
        assertEquals(0 to "", runCheck("before-finish", """{"hook_event_name":"Stop","stop_hook_active": true}"""))
        // An unknown kind runs nothing.
        assertEquals(0 to "", runCheck("anything", "{}"))
    }

    /** Runs the hook's check mode with [input] on stdin; returns its exit status and what it wrote to stderr. */
    private fun runCheck(
        kind: String,
        input: String,
    ): Pair<Int, String> {
        val err = File(folder.root, "err-${System.nanoTime()}.txt")
        val process =
            ProcessBuilder("sh", script.absolutePath, "--check", kind)
                .redirectError(err)
                .redirectOutput(File(folder.root, "out-${System.nanoTime()}.txt"))
                .directory(folder.root)
                .apply { environment()["ANDCODE_CLAUDE_BRIDGE"] = folder.root.absolutePath }
                .start()
        process.outputStream.use { it.write(input.toByteArray()) }
        check(process.waitFor(WAIT_SECONDS, TimeUnit.SECONDS)) { "the hook did not finish" }
        return process.exitValue() to err.readText().trim()
    }

    private companion object {
        const val WAIT_SECONDS = 20L
    }
}
