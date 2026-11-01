package com.yugahashimoto.andcode.feature.chat

import com.yugahashimoto.andcode.core.api.PermissionRequest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolRiskTest {
    @Test
    fun `tools are sorted by what they can do`() {
        assertEquals(ToolRisk.READ_ONLY, ToolRisks.of("Read", null))
        assertEquals(ToolRisk.READ_ONLY, ToolRisks.of("WebFetch", null))
        assertEquals(ToolRisk.WRITING, ToolRisks.of("Edit", null))
        assertEquals(ToolRisk.WRITING, ToolRisks.of("edit", null))
        assertEquals(ToolRisk.EXECUTING, ToolRisks.of("Bash", "npm test"))
        // An unknown tool, an MCP server's for one, could do anything.
        assertEquals(ToolRisk.EXECUTING, ToolRisks.of("mcp__github__create_issue", null))
    }

    @Test
    fun `commands that delete or rewrite for good are destructive, also inside a longer command`() {
        listOf(
            "rm -rf build",
            "rm -fr build",
            "rm -r node_modules",
            "rm --recursive dist",
            "cd app && rm -rf .",
            "git push --force origin main",
            "git push -f",
            "git push origin +main",
            "git reset --hard HEAD~3",
            "git clean -fdx",
            "git branch -D old",
            "git checkout -- .",
            "dd if=/dev/zero of=/dev/sda",
            "mkfs.ext4 /dev/sdb1",
            "chmod -R 777 /",
            "psql -c 'DROP TABLE users'",
        ).forEach { assertEquals(it, ToolRisk.DESTRUCTIVE, ToolRisks.of("Bash", it)) }
    }

    @Test
    fun `ordinary commands that only look alike are not`() {
        listOf(
            "rm notes.txt",
            "git push origin main",
            "git reset HEAD file.kt",
            "git clean -n",
            "git branch -d merged",
            "npm run format",
            "ls -R",
        ).forEach { assertEquals(it, ToolRisk.EXECUTING, ToolRisks.of("Bash", it)) }
    }

    @Test
    fun `an approval is classed from Claude Code's tool input, Codex's command or OpenCode's patterns`() {
        val claude =
            PermissionRequest(
                id = "1",
                sessionId = "s",
                permission = "Bash: delete — rm -rf build",
                metadata = mapOf("toolName" to JsonPrimitive("Bash"), "toolInput" to buildJsonObject { put("command", "rm -rf build") }),
            )
        val codex =
            PermissionRequest(
                id = "2",
                sessionId = "s",
                permission = "bash",
                metadata = mapOf("command" to JsonPrimitive("git reset --hard")),
            )
        val openCode = PermissionRequest(id = "3", sessionId = "s", permission = "bash", patterns = listOf("git push --force"))
        val edit = PermissionRequest(id = "4", sessionId = "s", permission = "edit")

        assertEquals(ToolRisk.DESTRUCTIVE, ToolRisks.of(claude))
        assertEquals(ToolRisk.DESTRUCTIVE, ToolRisks.of(codex))
        assertEquals(ToolRisk.DESTRUCTIVE, ToolRisks.of(openCode))
        assertEquals(ToolRisk.WRITING, ToolRisks.of(edit))
    }
}
