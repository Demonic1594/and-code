package com.yugahashimoto.andcode.runtime.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs the guest's PermissionRequest hook under the host's sh and jq and checks that its answers
 * have the shape Claude Code reads: `hookSpecificOutput.decision.behavior`, not PreToolUse's
 * `permissionDecision`, which Claude Code drops (and then denies the call). Skipped without jq.
 */
class PermissionHookScriptTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val script =
        listOf(
            "src/main/assets/scripts/and-code-claude-permission-hook.sh",
            "app/src/main/assets/scripts/and-code-claude-permission-hook.sh",
        ).map(::File).first { it.isFile }

    private val bridge by lazy { folder.newFolder("bridge") }

    @Before
    fun needsShellAndJq() {
        assumeTrue("needs sh and jq", runCatching { exec("sh", "-c", "command -v jq").waitFor() == 0 }.getOrDefault(false))
    }

    @Test
    fun `an always-allow rule answers allow in the PermissionRequest shape`() {
        File(bridge, "always-rules.json").writeText("""{"rules":[{"toolName":"Bash","commandPrefix":"git status"}]}""")

        val answer = runHook(bashCall("git status --short"))

        assertEquals("allow", answer.behavior())
        assertNull(answer.hookOutput()["permissionDecision"])
    }

    @Test
    fun `a rule kept for a project allows only there, and the request carries the directory`() {
        File(bridge, "always-rules.json").writeText(
            """{"rules":[{"toolName":"Bash","commandPrefix":"npm test","project":"/root/shop"}]}""",
        )

        assertEquals("allow", runHook(bashCall("npm test", cwd = "/root/shop/web")).behavior())
        val climbing = runHook(bashCall("npm test", cwd = "/root/shop/../blog"), response = """{"decision":"deny"}""")
        assertEquals("deny", climbing.behavior())

        var asked: JsonObject? = null
        val elsewhere =
            runHook(
                bashCall("npm test", cwd = "/root/shopfront"),
                response = """{"decision":"deny","message":"no"}""",
                onRequest = { asked = it },
            )
        assertEquals("deny", elsewhere.behavior())
        assertEquals("/root/shopfront", asked?.get("cwd")?.jsonPrimitive?.content)
    }

    @Test
    fun `a blocked command is refused before any always-allow rule, without asking`() {
        File(bridge, "always-rules.json").writeText("""{"rules":[{"toolName":"Bash","commandPrefix":"git push"}]}""")
        File(
            bridge,
            ClaudePermissionBridge.BLOCK_RULES_FILE,
        ).writeText("""{"rules":[{"toolName":"Bash","commandPrefix":"git push --force"}]}""")

        val answer = runHook(bashCall("git push origin --force"))

        assertEquals("deny", answer.behavior())
        assertEquals(
            ClaudePermissionBridge.blockedMessage("git push --force"),
            answer.decision()["message"]?.jsonPrimitive?.content,
        )
        assertFalse(File(bridge, "pending").listFiles().orEmpty().any())
    }

    @Test
    fun `the hook blocks the same commands as the app`() {
        File(bridge, ClaudePermissionBridge.BLOCK_RULES_FILE).writeText("""{"rules":[{"toolName":"Bash","commandPrefix":"rm -rf"}]}""")
        listOf("cd x && sudo /bin/rm -rf /", "rm -fr x", "rm -r -f x", "rm \\\n  -rf x", "rm -r x", "echo rm -rf", "ls; rm -v x").forEach {
                command ->
            val blocked =
                runHook(bashCall(command), env = mapOf("ANDCODE_PERMISSION_TIMEOUT_SEC" to "0"))
                    .decision()["message"]?.jsonPrimitive?.content.orEmpty().startsWith("The user blocked")
            assertEquals(command, ShellCommandRules.blocks("rm -rf", command), blocked)
        }
    }

    @Test
    fun `before a tool call only blocked commands are refused, in PreToolUse's shape`() {
        File(bridge, ClaudePermissionBridge.BLOCK_RULES_FILE).writeText("""{"rules":[{"toolName":"Bash","commandPrefix":"rm -rf"}]}""")

        val refused = Json.parseToJsonElement(runHookOutput(bashCall("rm -rf build"), args = arrayOf("--pre-tool-use"))).jsonObject
        val allowed = runHookOutput(bashCall("ls -la"), args = arrayOf("--pre-tool-use"))

        val output = refused.getValue("hookSpecificOutput").jsonObject
        assertEquals("PreToolUse", output.getValue("hookEventName").jsonPrimitive.content)
        assertEquals("deny", output.getValue("permissionDecision").jsonPrimitive.content)
        assertEquals(ClaudePermissionBridge.blockedMessage("rm -rf"), output.getValue("permissionDecisionReason").jsonPrimitive.content)
        // No answer leaves the call to Claude Code's own permission step, and nothing is asked here.
        assertEquals("", allowed)
        assertFalse(File(bridge, "pending").listFiles().orEmpty().any())
    }

    @Test
    fun `an unreadable rules file refuses rather than letting a blocked command through`() {
        File(bridge, ClaudePermissionBridge.BLOCK_RULES_FILE).writeText("{not json")
        File(bridge, "always-rules.json").writeText("""{"rules":[{"toolName":"Bash","commandPrefix":"rm -rf"}]}""")

        val answer = runHook(bashCall("rm -rf build"))

        assertEquals("deny", answer.behavior())
        assertTrue(answer.decision()["message"]?.jsonPrimitive?.content.orEmpty().contains("could not read"))
    }

    @Test
    fun `an empty rules file refuses like an unreadable one`() {
        File(bridge, ClaudePermissionBridge.BLOCK_RULES_FILE).writeText("")

        val answer = runHook(bashCall("ls"))

        assertEquals("deny", answer.behavior())
        assertTrue(answer.decision()["message"]?.jsonPrimitive?.content.orEmpty().contains("could not read"))
    }

    @Test
    fun `a user's rejection answers deny with the message for the agent`() {
        val answer = runHook(bashCall("rm -rf build"), response = """{"decision":"deny"}""")

        assertEquals("deny", answer.behavior())
        assertEquals(ClaudePermissionBridge.DECLINED_MESSAGE, answer.decision()["message"]?.jsonPrimitive?.content)
    }

    @Test
    fun `an approval with edited input passes the input on`() {
        val answer =
            runHook(
                bashCall("rm -rf build"),
                response = """{"decision":"allow","updatedInput":{"command":"rm -rf build/tmp"}}""",
            )

        assertEquals("allow", answer.behavior())
        assertEquals(
            "rm -rf build/tmp",
            answer.decision()["updatedInput"]?.jsonObject?.get("command")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `an unanswered prompt is not granted and leaves no request behind`() {
        val answer = runHook(bashCall("rm -rf build"), env = mapOf("ANDCODE_PERMISSION_TIMEOUT_SEC" to "0"))

        assertEquals("deny", answer.behavior())
        assertTrue(answer.decision()["message"]?.jsonPrimitive?.content.orEmpty().contains("did not answer in time"))
        assertFalse(File(bridge, "pending").listFiles().orEmpty().any())
    }

    @Test
    fun `the approval shows the command, not only the agent's description of it`() {
        var label = ""
        runHook(
            """{"tool_name":"Bash","tool_input":{"command":"rm -rf build","description":"Clean the build"}}""",
            response = """{"decision":"deny"}""",
            onRequest = { request -> label = request.getValue("permissionLabel").jsonPrimitive.content },
        )

        assertEquals("Bash: Clean the build — rm -rf build", label)
    }

    @Test
    fun `without jq the hook refuses rather than ask to approve what it cannot show`() {
        val bin = folder.newFolder("bin")
        listOf("cat", "mkdir").forEach { tool ->
            val path = exec("sh", "-c", "command -v $tool").inputStream.bufferedReader().readText().trim()
            java.nio.file.Files.createSymbolicLink(File(bin, tool).toPath(), File(path).toPath())
        }

        val answer = runHook(bashCall("rm -rf build"), env = mapOf("PATH" to bin.absolutePath), shell = "/bin/sh")

        assertEquals("deny", answer.behavior())
        assertTrue(answer.decision()["message"]?.jsonPrimitive?.content.orEmpty().contains("jq is missing"))
        assertFalse(File(bridge, "pending").listFiles().orEmpty().any())
    }

    @Test
    fun `the hook falls back to the same declined text as the app`() {
        assertTrue(script.readText().contains(ClaudePermissionBridge.DECLINED_MESSAGE))
    }

    private fun bashCall(
        command: String,
        cwd: String? = null,
    ): String =
        """{"hook_event_name":"PermissionRequest","session_id":"s1","tool_name":"Bash",""" +
            (cwd?.let { """"cwd":${JsonPrimitive(it)},""" } ?: "") +
            """"tool_input":{"command":${JsonPrimitive(command)}}}"""

    /**
     * Feeds [input] to the hook. With a [response], waits for the hook's request to appear in the
     * bridge and answers it the way the app does, by writing the response file.
     */
    private fun runHook(
        input: String,
        response: String? = null,
        env: Map<String, String> = emptyMap(),
        onRequest: (JsonObject) -> Unit = {},
        shell: String = "sh",
    ): JsonObject = Json.parseToJsonElement(runHookOutput(input, response, env, onRequest, shell)).jsonObject

    /** Runs the hook like [runHook] and returns what it printed, which may be nothing. */
    private fun runHookOutput(
        input: String,
        response: String? = null,
        env: Map<String, String> = emptyMap(),
        onRequest: (JsonObject) -> Unit = {},
        shell: String = "sh",
        vararg args: String,
    ): String {
        val out = File(folder.root, "out-${System.nanoTime()}.txt")
        val process =
            ProcessBuilder(shell, script.absolutePath, *args)
                .redirectOutput(out)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .apply {
                    environment()["ANDCODE_CLAUDE_BRIDGE"] = bridge.absolutePath
                    environment()["ANDCODE_PERMISSION_TIMEOUT_SEC"] = "20"
                    environment().putAll(env)
                }.start()
        process.outputStream.use { it.write(input.toByteArray()) }
        if (response != null) {
            val pending = File(bridge, "pending")
            val deadline = System.currentTimeMillis() + WAIT_MILLIS
            var request: Pair<File, JsonObject>? = null
            while (request == null && System.currentTimeMillis() < deadline) {
                // Read only once whole: the hook writes the file in place, so it can be seen half written.
                request =
                    pending.listFiles()?.firstNotNullOfOrNull { file ->
                        file.takeIf { it.name.endsWith(".json") }
                            ?.let { runCatching { it to Json.parseToJsonElement(it.readText()).jsonObject }.getOrNull() }
                    }
                if (request == null) Thread.sleep(POLL_MILLIS)
            }
            checkNotNull(request) { "the hook never wrote its request" }
            onRequest(request.second)
            // Written aside and renamed in, as the app does, so the hook never reads half a file.
            val responses = File(bridge, "responses")
            val name = request.first.name
            File(responses, "$name.tmp").apply { writeText(response) }.renameTo(File(responses, name))
        }
        if (!process.waitFor(WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            error("the hook did not finish")
        }
        return out.readText().trim()
    }

    private fun exec(vararg command: String): Process = ProcessBuilder(*command).redirectErrorStream(true).start()

    private fun JsonObject.hookOutput(): JsonObject = getValue("hookSpecificOutput").jsonObject

    private fun JsonObject.decision(): JsonObject = hookOutput().getValue("decision").jsonObject

    private fun JsonObject.behavior(): String = decision().getValue("behavior").jsonPrimitive.content

    private companion object {
        const val WAIT_MILLIS = 20_000L
        const val POLL_MILLIS = 50L
    }
}
