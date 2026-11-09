package com.yugahashimoto.andcode.core.api

import kotlinx.serialization.Serializable

/**
 * How full a chat's context window is, as the agent itself last reported it, for an agent that
 * reports the window's size along with what is in it (Codex). Agents that do not report it fill the
 * meter from the last reply's tokens and the model's listed limit instead.
 */
@Serializable
data class ContextUsage(
    val usedTokens: Long,
    val windowTokens: Long,
)
