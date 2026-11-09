package com.yugahashimoto.andcode.core.api

/**
 * How an agent's context window is split, as the agent itself counts it. Only Claude Code reports
 * this (its `get_context_usage` control request); the other agents give a total only.
 */
data class ContextBreakdown(
    /** Tokens in the window now; what the meter shows. */
    val usedTokens: Long,
    val maxTokens: Long,
    /** In the agent's own order: what is in the window, then what is deferred, reserved and free. */
    val categories: List<Category>,
    /** Servers whose tools are in the window, largest first. */
    val mcpServers: List<McpServerShare>,
    /** Instruction files (CLAUDE.md and the like) loaded into the window. */
    val memoryFiles: List<MemoryFile>,
    /** The size at which the agent compacts on its own; null when it does not. */
    val autoCompactAt: Long?,
) {
    data class Category(
        /** The agent's id for it (`system_prompt`, `mcp_tools`...), which picks a translated label. */
        val id: String?,
        /** The agent's own label, shown when [id] is one the app does not know. */
        val name: String,
        val tokens: Long,
        val kind: Kind,
    )

    enum class Kind {
        /** In the window now. */
        USED,

        /** Listed by name only; the full definition enters the window when it is first used. */
        DEFERRED,

        /** Kept free so a compaction has room to run. */
        BUFFER,
        FREE,
    }

    data class McpServerShare(
        val name: String,
        /** Tokens of this server's tools that are in the window now. */
        val tokens: Long,
        val tools: Int,
    )

    data class MemoryFile(
        val path: String,
        val tokens: Long,
    )
}
