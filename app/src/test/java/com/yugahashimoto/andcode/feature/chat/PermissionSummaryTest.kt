package com.yugahashimoto.andcode.feature.chat

import com.yugahashimoto.andcode.core.api.PermissionRequest
import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionSummaryTest {
    private fun request(
        permission: String,
        patterns: List<String>,
    ) = PermissionRequest(id = "p", sessionId = "s", permission = permission, patterns = patterns)

    @Test
    fun `claude code's long label goes in the box and its tool in the tag`() {
        val (tag, details) = permissionSummary(request("Bash: list files — ls -la /very/long/path", listOf("Bash")))

        assertEquals("Bash", tag)
        assertEquals(listOf("Bash: list files — ls -la /very/long/path"), details)
    }

    @Test
    fun `opencode's permission type is the tag and its commands the details`() {
        val (tag, details) = permissionSummary(request("bash", listOf("npm test", "npm run lint")))

        assertEquals("bash", tag)
        assertEquals(listOf("npm test", "npm run lint"), details)
    }

    @Test
    fun `a request with no patterns has nothing for the box`() {
        val (tag, details) = permissionSummary(request("edit", emptyList()))

        assertEquals("edit", tag)
        assertEquals(emptyList<String>(), details)
    }
}
