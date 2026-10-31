package com.yugahashimoto.andcode.core.api

/**
 * The thinking levels every agent is shown on, weakest first. Agents name their levels differently
 * and list them in their own order; sorting by this scale makes "High" mean the same row on every
 * agent. `none` is the "Off" entry (Codex's own name for it; Claude Code's off switch is mapped onto
 * it too).
 */
private val THINKING_SCALE = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra")

/**
 * [levels] in the shared scale's order. A level the scale does not know (a provider's new name) is
 * kept, after the known ones and in the order it came, so nothing a server offers is dropped.
 */
fun orderThinkingLevels(levels: Collection<String>): List<String> {
    val (known, unknown) = levels.distinct().partition { it.lowercase() in THINKING_SCALE }
    return known.sortedBy { THINKING_SCALE.indexOf(it.lowercase()) } + unknown
}
