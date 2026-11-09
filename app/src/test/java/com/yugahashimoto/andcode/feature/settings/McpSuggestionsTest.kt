package com.yugahashimoto.andcode.feature.settings

import com.yugahashimoto.andcode.core.api.McpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpSuggestionsTest {
    @Test
    fun `each suggestion is either a remote address or a local command`() {
        mcpSuggestions.forEach { suggestion ->
            assertTrue(suggestion.name, (suggestion.url == null) != (suggestion.command == null))
            suggestion.url?.let { assertTrue(it, it.startsWith("https://")) }
        }
        assertEquals(mcpSuggestions.size, mcpSuggestions.map { it.name }.toSet().size)
    }

    @Test
    fun `a server already added is not suggested again, by name or by address`() {
        val servers =
            listOf(
                McpServer(name = "Context7", url = "https://example.com/other"),
                McpServer(name = "wiki", url = "https://mcp.deepwiki.com/mcp/"),
                McpServer(name = "local-thing", command = "node server.js"),
            )

        assertEquals(
            listOf("microsoft-learn", "sequential-thinking"),
            pendingSuggestions(servers).map { it.name },
        )
        assertEquals(mcpSuggestions, pendingSuggestions(emptyList()))
    }
}
