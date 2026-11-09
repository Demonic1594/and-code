package com.yugahashimoto.andcode.feature.settings

import androidx.annotation.StringRes
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.core.api.McpServer

/**
 * A well-known MCP server the MCP screen offers to add, so a user on a phone does not have to find
 * and type its address. Tapping one fills the add-server form, which the user still confirms.
 */
data class McpSuggestion(
    /** The name it is added under, as its own documentation names it. */
    val name: String,
    /** A remote server's address; a remote one needs nothing installed in the Linux environment. */
    val url: String? = null,
    /** A local server's command line. */
    val command: String? = null,
    @StringRes val description: Int,
)

/**
 * Each answered an MCP `initialize` (remote) or is a published npm package (local) when this list was
 * written. Remote servers come first: they work for every agent with nothing to install.
 */
internal val mcpSuggestions =
    listOf(
        McpSuggestion(
            name = "context7",
            url = "https://mcp.context7.com/mcp",
            description = R.string.mcp_suggestion_context7,
        ),
        McpSuggestion(
            name = "deepwiki",
            url = "https://mcp.deepwiki.com/mcp",
            description = R.string.mcp_suggestion_deepwiki,
        ),
        McpSuggestion(
            name = "microsoft-learn",
            url = "https://learn.microsoft.com/api/mcp",
            description = R.string.mcp_suggestion_microsoft_learn,
        ),
        McpSuggestion(
            name = "sequential-thinking",
            command = "npx -y @modelcontextprotocol/server-sequential-thinking",
            description = R.string.mcp_suggestion_sequential_thinking,
        ),
    )

/** The suggestions not already among [servers], by name or by address. */
internal fun pendingSuggestions(
    servers: List<McpServer>,
    suggestions: List<McpSuggestion> = mcpSuggestions,
): List<McpSuggestion> {
    val names = servers.mapTo(HashSet()) { it.name.lowercase() }
    val urls = servers.mapNotNullTo(HashSet()) { it.url?.trimEnd('/') }
    return suggestions.filterNot { suggestion ->
        suggestion.name.lowercase() in names || suggestion.url?.trimEnd('/') in urls
    }
}
