package com.yugahashimoto.andcode.feature.chat

import com.yugahashimoto.andcode.core.api.PermissionRequest
import kotlinx.serialization.json.JsonArray
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
            // Wrapped, prefixed or with the flags after the path.
            "bash -c \"rm -rf x\"",
            "sh -c 'rm -rf x'",
            "ssh host 'rm -rf x'",
            "echo `rm -rf x`",
            "/bin/rm -rf x",
            "\\rm -rf x",
            "sudo rm -rf /",
            "rm build -rf",
            "rm -v -r x",
            "rm -rf",
            "git push -fu origin x",
            "git -C repo push -f",
            "git push origin :old",
            "git push --delete origin old",
            "git push --mirror",
            "git push --force-with-lease",
            "git checkout .",
            "git restore .",
            "git branch --delete --force x",
            "git stash drop",
            "chmod 755 -R x",
            "chmod --recursive 755 x",
            "find . -delete",
            "find . -exec rm {} \\;",
            "psql -c 'TRUNCATE users'",
            "sqlite3 db 'DELETE FROM users'",
            "psql -c \"TRUNCATE TABLE users\"",
            "mysql -e \"truncate table users\"",
            // Behind a launcher's options, a shell keyword, a group or a background job.
            "find . -print0 | xargs -0 rm -rf",
            "sudo -u root rm -rf x",
            "timeout 5 rm -rf x",
            "nice -n 10 rm -rf x",
            "env -i rm -rf x",
            "for f in *; do rm -f \"x\"; done",
            "if test -d x; then rm -rf x; fi",
            "(rm -rf x)",
            "{ rm -rf x; }",
            "! rm -rf x",
            "sleep 1 & rm -rf x",
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
            "rm -i x",
            "rm --verbose x",
            "git rm -rf --cached x",
            "docker rm -f container",
            "git push -u origin feature",
            "git restore --staged .",
            "npm run rm -- -r",
            "psql -c \"SELECT * FROM users\"",
            "sudo apt install ripgrep",
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
        // Each OpenCode pattern is one command of a chain: joined, these two would read as a forced push.
        val chained = PermissionRequest(id = "5", sessionId = "s", permission = "bash", patterns = listOf("git push origin", "ls -f"))
        val argv =
            PermissionRequest(
                id = "6",
                sessionId = "s",
                permission = "bash",
                metadata = mapOf("command" to JsonArray(listOf(JsonPrimitive("rm"), JsonPrimitive("-rf"), JsonPrimitive("x")))),
            )
        val edit = PermissionRequest(id = "4", sessionId = "s", permission = "edit")

        assertEquals(ToolRisk.DESTRUCTIVE, ToolRisks.of(claude))
        assertEquals(ToolRisk.DESTRUCTIVE, ToolRisks.of(codex))
        assertEquals(ToolRisk.DESTRUCTIVE, ToolRisks.of(openCode))
        assertEquals(ToolRisk.WRITING, ToolRisks.of(edit))
        assertEquals(ToolRisk.EXECUTING, ToolRisks.of(chained))
        assertEquals(ToolRisk.DESTRUCTIVE, ToolRisks.of(argv))
        // A read-only tool stays read-only whatever its input says.
        assertEquals(ToolRisk.READ_ONLY, ToolRisks.of("Grep", "rm -rf /"))
    }
}
