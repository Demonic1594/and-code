package com.yugahashimoto.andcode.runtime.local

private const val SHORT_TITLE_MAX_WORDS = 6
private const val SHORT_TITLE_MAX_CHARS = 40

/**
 * Whether a chat opening with [prompt] is worth a model call to name it.
 *
 * Claude Code and Antigravity name nothing themselves, so the app asks a model for a title. When the
 * opening message is already one short line ("fix the login bug"), the prompt itself is used as the
 * title right away and the call would only restate it, so it is skipped.
 */
internal fun needsSummarizedTitle(prompt: String): Boolean {
    val lines = prompt.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    if (lines.size != 1) return lines.isNotEmpty()
    val line = lines.single()
    return line.length > SHORT_TITLE_MAX_CHARS || line.split(Regex("\\s+")).size > SHORT_TITLE_MAX_WORDS
}
