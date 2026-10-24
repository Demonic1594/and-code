package com.yugahashimoto.andcode.data.settings

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * A reusable prompt the user saved once and runs again as `/name` from the composer, the way
 * Windsurf workflows, Goose recipes or Devin playbooks work on the desktop.
 */
@Serializable
data class SavedPrompt(
    val id: String,
    val name: String,
    val text: String,
) {
    /** The slash command this prompt answers to, e.g. `/review-pr`. */
    val command: String get() = "/${normalizeName(name)}"

    companion object {
        const val MAX_NAME_LENGTH = 32

        /**
         * Lower-cases and turns anything that isn't a letter, digit, `-` or `_` into `-`, so a
         * name typed with spaces ("Review PR") still works as a single slash-command token.
         */
        fun normalizeName(raw: String): String =
            raw
                .trim()
                .lowercase()
                .replace(Regex("[^\\p{L}\\p{N}_-]+"), "-")
                .trim('-')
                .take(MAX_NAME_LENGTH)
    }
}

internal object SavedPromptCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(prompts: List<SavedPrompt>): String = json.encodeToString(prompts)

    /** Corrupt or missing storage decodes to an empty list rather than crashing app start. */
    fun decode(raw: String?): List<SavedPrompt> =
        raw
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { json.decodeFromString<List<SavedPrompt>>(it) }.getOrNull() }
            .orEmpty()
}
