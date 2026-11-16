package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.runtime.SessionApproval

/**
 * How the official `agy` CLI handles tool permissions for a session.
 *
 * Mirrors [ClaudePermissionMode]: agy's own `default` mode expects an interactive TUI to answer
 * per-call prompts, which the one-shot `--print` bridge this app drives has no channel for, so it is
 * deliberately not offered here.
 */
enum class AntigravityPermissionMode(
    val cliArgs: List<String>,
    val labelRes: Int,
    val descriptionRes: Int,
) {
    PLAN(listOf("--mode", "plan"), R.string.claude_permission_plan, R.string.antigravity_permission_plan_desc),
    ACCEPT_EDITS(
        listOf("--mode", "accept-edits"),
        R.string.claude_permission_accept_edits,
        R.string.antigravity_permission_accept_edits_desc,
    ),
    FULL_ACCESS(
        listOf("--dangerously-skip-permissions"),
        R.string.claude_permission_full_access,
        R.string.antigravity_permission_full_access_desc,
    ),
    ;

    /** What a session in this mode asks before using tools; agy's accept-edits also runs commands unasked. */
    val sessionApproval: SessionApproval
        get() =
            when (this) {
                FULL_ACCESS -> SessionApproval.NEVER_ASKS
                ACCEPT_EDITS -> SessionApproval.EDITS_AND_COMMANDS_PREAPPROVED
                PLAN -> SessionApproval.ASKS
            }

    /** The value persisted per session and round-tripped through [fromCliValue]. */
    val cliValue: String get() = name

    companion object {
        val DEFAULT = ACCEPT_EDITS

        fun fromCliValue(value: String?): AntigravityPermissionMode = entries.firstOrNull { it.cliValue == value } ?: DEFAULT
    }
}
