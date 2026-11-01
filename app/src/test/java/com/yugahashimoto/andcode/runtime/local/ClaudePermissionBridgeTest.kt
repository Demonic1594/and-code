package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.runtime.PermissionResponse
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class ClaudePermissionBridgeTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `pollPending surfaces a new permission request once`() {
        val bridge = ClaudePermissionBridge(folder.root)
        val requestId = UUID.randomUUID().toString()
        bridge.writeGuestRequest(
            ClaudePermissionBridge.Request(
                requestId = requestId,
                androidSessionId = "session-1",
                kind = ClaudePermissionBridge.Kind.PERMISSION,
                toolName = "Bash",
                toolInputJson = """{"command":"ls"}""",
                permissionLabel = "Bash",
            ),
        )

        val first = bridge.pollPending()
        assertEquals(1, first.size)
        assertEquals(requestId, first.single().requestId)
        assertEquals("Bash", first.single().toolName)
        assertTrue(first.single().permissionLabel.contains("Bash"))

        assertTrue(bridge.pollPending().isEmpty())
    }

    @Test
    fun `pendingRequests recovers a request the event stream already carried`() {
        val bridge = ClaudePermissionBridge(folder.root)
        val requestId = UUID.randomUUID().toString()
        bridge.writeGuestRequest(
            ClaudePermissionBridge.Request(
                requestId = requestId,
                androidSessionId = "session-1",
                kind = ClaudePermissionBridge.Kind.QUESTION,
                toolName = "AskUserQuestion",
                toolInputJson = """{"questions":[{"question":"Pick?","options":[{"label":"A"}]}]}""",
                permissionLabel = "AskUserQuestion",
            ),
        )

        // The watcher already emitted it; a chat opened later must still find it on disk.
        bridge.pollPending()
        assertEquals(listOf(requestId), bridge.pendingRequests().map { it.requestId })
    }

    @Test
    fun `pendingRequests does not consume the watcher's one-shot emission`() {
        val bridge = ClaudePermissionBridge(folder.root)
        val requestId = UUID.randomUUID().toString()
        bridge.writeGuestRequest(
            ClaudePermissionBridge.Request(
                requestId = requestId,
                androidSessionId = "session-1",
                kind = ClaudePermissionBridge.Kind.PERMISSION,
                toolName = "Bash",
                toolInputJson = "{}",
                permissionLabel = "Bash",
            ),
        )

        assertEquals(listOf(requestId), bridge.pendingRequests().map { it.requestId })
        assertEquals(listOf(requestId), bridge.pollPending().map { it.requestId })
    }

    @Test
    fun `respond writes a response the hook can read`() {
        val bridge = ClaudePermissionBridge(folder.root)
        val requestId = UUID.randomUUID().toString()
        bridge.writeGuestRequest(
            ClaudePermissionBridge.Request(
                requestId = requestId,
                androidSessionId = "session-1",
                kind = ClaudePermissionBridge.Kind.PERMISSION,
                toolName = "Bash",
                toolInputJson = "{}",
                permissionLabel = "Bash",
            ),
        )
        bridge.pollPending()

        assertTrue(bridge.respond(requestId, PermissionResponse.ONCE, remember = false))
        val response = bridge.readResponse(requestId)
        assertNotNull(response)
        assertEquals("allow", response!!.decision)
        assertFalse(response.remember)
    }

    @Test
    fun `reject writes deny`() {
        val bridge = ClaudePermissionBridge(folder.root)
        val requestId = UUID.randomUUID().toString()
        bridge.writeGuestRequest(
            ClaudePermissionBridge.Request(
                requestId = requestId,
                androidSessionId = "session-1",
                kind = ClaudePermissionBridge.Kind.PERMISSION,
                toolName = "Bash",
                toolInputJson = "{}",
                permissionLabel = "Bash",
            ),
        )
        bridge.pollPending()
        assertTrue(bridge.respond(requestId, PermissionResponse.REJECT, remember = false, message = "nope"))
        assertEquals("deny", bridge.readResponse(requestId)!!.decision)
        assertEquals("nope", bridge.readResponse(requestId)!!.message)
    }

    @Test
    fun `a reject without a message tells the agent not to retry`() {
        val bridge = ClaudePermissionBridge(folder.root)
        val requestId = UUID.randomUUID().toString()
        bridge.writeGuestRequest(
            ClaudePermissionBridge.Request(
                requestId = requestId,
                androidSessionId = "session-1",
                kind = ClaudePermissionBridge.Kind.PERMISSION,
                toolName = "Bash",
                toolInputJson = "{}",
                permissionLabel = "Bash",
            ),
        )
        bridge.pollPending()

        assertTrue(bridge.respond(requestId, PermissionResponse.REJECT, remember = false))

        assertEquals(ClaudePermissionBridge.DECLINED_MESSAGE, bridge.readResponse(requestId)!!.message)
    }

    @Test
    fun `always allow records a remembered rule`() {
        val bridge = ClaudePermissionBridge(folder.root)
        val requestId = UUID.randomUUID().toString()
        bridge.writeGuestRequest(
            ClaudePermissionBridge.Request(
                requestId = requestId,
                androidSessionId = "session-1",
                kind = ClaudePermissionBridge.Kind.PERMISSION,
                toolName = "Bash",
                toolInputJson = """{"command":"git status"}""",
                permissionLabel = "Bash",
            ),
        )
        bridge.pollPending()
        assertTrue(bridge.respond(requestId, PermissionResponse.ALWAYS, remember = true))
        assertTrue(bridge.isAlwaysAllowed("Bash", """{"command":"git status"}"""))
        assertFalse(bridge.isAlwaysAllowed("Write", """{"file_path":"a"}"""))
    }

    @Test
    fun `an always-allowed command does not cover a chained one`() {
        val bridge = ClaudePermissionBridge(folder.root)
        approveAlways(bridge, "git status")

        assertTrue(bridge.isAlwaysAllowed("Bash", """{"command":"git status --short"}"""))
        assertFalse(bridge.isAlwaysAllowed("Bash", """{"command":"git status; rm -rf ~"}"""))
        assertFalse(bridge.isAlwaysAllowed("Bash", """{"command":"git statusfoo"}"""))
    }

    @Test
    fun `a chained command approved with always is not remembered`() {
        val bridge = ClaudePermissionBridge(folder.root)
        approveAlways(bridge, "git status && rm -rf ~")

        assertFalse(bridge.isAlwaysAllowed("Bash", """{"command":"git status"}"""))
        assertFalse(bridge.isAlwaysAllowed("Bash", """{"command":"anything"}"""))
    }

    @Test
    fun `a tool-wide rule never covers a command`() {
        val bridge = ClaudePermissionBridge(folder.root)
        java.io.File(folder.root, "always-rules.json").writeText("""{"rules":[{"toolName":"Bash"}]}""")

        assertFalse(bridge.isAlwaysAllowed("Bash", """{"command":"rm -rf ~"}"""))
    }

    private fun approveAlways(
        bridge: ClaudePermissionBridge,
        command: String,
    ) {
        val requestId = UUID.randomUUID().toString()
        bridge.writeGuestRequest(
            ClaudePermissionBridge.Request(
                requestId = requestId,
                androidSessionId = "session-1",
                kind = ClaudePermissionBridge.Kind.PERMISSION,
                toolName = "Bash",
                toolInputJson = buildJsonObject { put("command", command) }.toString(),
                permissionLabel = "Bash",
            ),
        )
        bridge.pollPending()
        assertTrue(bridge.respond(requestId, PermissionResponse.ALWAYS, remember = true))
    }

    @Test
    fun `answer question writes answers payload`() {
        val bridge = ClaudePermissionBridge(folder.root)
        val requestId = UUID.randomUUID().toString()
        bridge.writeGuestRequest(
            ClaudePermissionBridge.Request(
                requestId = requestId,
                androidSessionId = "session-1",
                kind = ClaudePermissionBridge.Kind.QUESTION,
                toolName = "AskUserQuestion",
                toolInputJson = """{"questions":[{"question":"Pick?","options":[{"label":"A"}]}]}""",
                permissionLabel = "AskUserQuestion",
            ),
        )
        bridge.pollPending()
        assertTrue(
            bridge.answerQuestion(
                requestId,
                questionsJson = """[{"question":"Pick?","options":[{"label":"A"}]}]""",
                answers = mapOf("Pick?" to "A"),
            ),
        )
        val response = bridge.readResponse(requestId)!!
        assertEquals("allow", response.decision)
        assertNotNull(response.answersJson)
        assertTrue(response.answersJson!!.contains("Pick?"))
    }

    @Test
    fun `unknown request id returns false`() {
        val bridge = ClaudePermissionBridge(folder.root)
        assertFalse(bridge.respond("missing", PermissionResponse.ONCE, remember = false))
        assertNull(bridge.readResponse("missing"))
    }

    @Test
    fun `hook settings fragment marks and-code permission hook`() {
        val fragment = ClaudePermissionHooks.settingsFragment()
        assertTrue(fragment.contains("PermissionRequest"))
        assertTrue(fragment.contains(ClaudePermissionHooks.HOOK_GUEST_PATH))
        assertTrue(fragment.contains("and-code-claude-permission"))
    }

    @Test
    fun `mergeSettings injects hook without dropping existing hooks`() {
        val existing =
            """
            {
              "hooks": {
                "Stop": [{ "matcher": "*", "hooks": [{ "type": "command", "command": "echo hi" }] }]
              }
            }
            """.trimIndent()
        val merged = ClaudePermissionHooks.mergeSettingsJson(existing)
        assertTrue(merged.contains("echo hi"))
        assertTrue(merged.contains("PermissionRequest"))
        assertTrue(merged.contains(ClaudePermissionHooks.HOOK_GUEST_PATH))
    }

    @Test
    fun `mergeSettings checks blocked commands before every Bash call and keeps the user's own`() {
        val existing =
            """
            {
              "hooks": {
                "PreToolUse": [
                  { "matcher": "Edit", "hooks": [{ "type": "command", "command": "my-lint" }] },
                  { "matcher": "Bash", "hooks": [{ "type": "command", "command": "/old/and-code-claude-permission-hook.sh --pre-tool-use" }] }
                ]
              }
            }
            """.trimIndent()

        val preToolUse =
            kotlinx.serialization.json.Json.parseToJsonElement(ClaudePermissionHooks.mergeSettingsJson(existing))
                .jsonObject.getValue("hooks").jsonObject.getValue("PreToolUse").jsonArray
        val commands =
            preToolUse.map {
                it.jsonObject.getValue(
                    "hooks",
                ).jsonArray.single().jsonObject.getValue("command").jsonPrimitive.content
            }

        assertEquals(listOf("my-lint", ClaudePermissionHooks.BLOCK_CHECK_COMMAND), commands)
        assertEquals("Bash", preToolUse.last().jsonObject.getValue("matcher").jsonPrimitive.content)
    }

    @Test
    fun `a blocked command is refused and listed until it is forgotten`() {
        val bridge = ClaudePermissionBridge(folder.root)

        assertTrue(bridge.block(" rm  -rf "))
        assertFalse(bridge.block("  "))
        val rule = ClaudePermissionBridge.CommandRule("Bash", "rm -rf", blocks = true)

        assertEquals(listOf(rule), bridge.rules.value)
        assertEquals(rule, bridge.blockedBy("Bash", """{"command":"cd x && rm -rf /"}"""))
        assertNull(bridge.blockedBy("Bash", """{"command":"rm -r x"}"""))
        assertNull(bridge.blockedBy("Read", """{"file_path":"/x"}"""))
        // A second bridge on the same folder reads the same rules, as the guest hook does.
        assertEquals(listOf(rule), ClaudePermissionBridge(folder.root).rules.value)

        bridge.forget(rule)

        assertTrue(bridge.rules.value.isEmpty())
        assertNull(bridge.blockedBy("Bash", """{"command":"rm -rf /"}"""))
    }

    @Test
    fun `a blocked command is never remembered as always allowed`() {
        val bridge = ClaudePermissionBridge(folder.root)
        bridge.block("rm -rf")
        val requestId = UUID.randomUUID().toString()
        bridge.writeGuestRequest(
            ClaudePermissionBridge.Request(
                requestId = requestId,
                androidSessionId = "session-1",
                kind = ClaudePermissionBridge.Kind.PERMISSION,
                toolName = "Bash",
                toolInputJson = """{"command":"rm -rf build"}""",
                permissionLabel = "Bash",
            ),
        )
        bridge.pollPending()

        assertTrue(bridge.respond(requestId, PermissionResponse.ALWAYS, remember = true))

        assertEquals(listOf(ClaudePermissionBridge.CommandRule("Bash", "rm -rf", blocks = true)), bridge.rules.value)
        File(folder.root, "always-rules.json").writeText("""{"rules":[{"toolName":"Bash","commandPrefix":"rm -rf"}]}""")
        assertFalse(bridge.isAlwaysAllowed("Bash", """{"command":"rm -rf build"}"""))
    }

    @Test
    fun `an always-allow rule shows in the list and can be forgotten`() {
        val bridge = ClaudePermissionBridge(folder.root)
        val requestId = UUID.randomUUID().toString()
        bridge.writeGuestRequest(
            ClaudePermissionBridge.Request(
                requestId = requestId,
                androidSessionId = "session-1",
                kind = ClaudePermissionBridge.Kind.PERMISSION,
                toolName = "Bash",
                toolInputJson = """{"command":"git status --short"}""",
                permissionLabel = "Bash",
            ),
        )
        bridge.pollPending()
        bridge.respond(requestId, PermissionResponse.ALWAYS, remember = true)
        val rule = ClaudePermissionBridge.CommandRule("Bash", "git status", blocks = false)

        assertEquals(listOf(rule), bridge.rules.value)

        bridge.forget(rule)

        assertFalse(bridge.isAlwaysAllowed("Bash", """{"command":"git status"}"""))
        assertTrue(bridge.rules.value.isEmpty())
    }

    @Test
    fun `an approval of a command blocked while it waited still refuses it`() {
        val bridge = ClaudePermissionBridge(folder.root)
        val requestId = UUID.randomUUID().toString()
        bridge.writeGuestRequest(
            ClaudePermissionBridge.Request(
                requestId = requestId,
                androidSessionId = "session-1",
                kind = ClaudePermissionBridge.Kind.PERMISSION,
                toolName = "Bash",
                toolInputJson = """{"command":"rm -rf build"}""",
                permissionLabel = "Bash",
            ),
        )
        bridge.pollPending()
        bridge.block("rm -rf")

        assertTrue(bridge.respond(requestId, PermissionResponse.ALWAYS, remember = true))

        assertEquals("deny", bridge.readResponse(requestId)!!.decision)
        assertEquals(ClaudePermissionBridge.blockedMessage("rm -rf"), bridge.readResponse(requestId)!!.message)
        assertFalse(bridge.isAlwaysAllowed("Bash", """{"command":"rm -rf build"}"""))
    }

    @Test
    fun `forgetting the last blocking rule removes the file the hook checks`() {
        val bridge = ClaudePermissionBridge(folder.root)
        bridge.block("rm -rf")

        bridge.forget(ClaudePermissionBridge.CommandRule("Bash", "rm -rf", blocks = true))

        assertFalse(File(folder.root, ClaudePermissionBridge.BLOCK_RULES_FILE).exists())
    }

    @Test
    fun `an unreadable blocking rules file is kept rather than replaced`() {
        val file = File(folder.root, ClaudePermissionBridge.BLOCK_RULES_FILE)
        file.writeText("{not json")
        val bridge = ClaudePermissionBridge(folder.root)

        assertFalse(bridge.block("sudo"))
        bridge.forget(ClaudePermissionBridge.CommandRule("Bash", "sudo", blocks = true))

        assertEquals("{not json", file.readText())
    }

    @Test
    fun `mergeSettings keeps the user's handlers that share a group with AndCode's`() {
        val existing =
            """
            {
              "hooks": {
                "PreToolUse": [
                  { "matcher": "Bash", "hooks": [
                    { "type": "command", "command": "my-audit" },
                    { "type": "command", "command": "/old/and-code-claude-permission-hook.sh --pre-tool-use" }
                  ] }
                ]
              }
            }
            """.trimIndent()

        val groups =
            kotlinx.serialization.json.Json.parseToJsonElement(ClaudePermissionHooks.mergeSettingsJson(existing))
                .jsonObject.getValue("hooks").jsonObject.getValue("PreToolUse").jsonArray
        val commands =
            groups.map {
                    group ->
                group.jsonObject.getValue("hooks").jsonArray.map { it.jsonObject.getValue("command").jsonPrimitive.content }
            }

        assertEquals(listOf(listOf("my-audit"), listOf(ClaudePermissionHooks.BLOCK_CHECK_COMMAND)), commands)
    }
}
