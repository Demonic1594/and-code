package com.yugahashimoto.andcode.feature.chat

import androidx.annotation.StringRes
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.core.api.OpenCodeCommand
import com.yugahashimoto.andcode.core.api.OpenCodeSkill
import com.yugahashimoto.andcode.data.settings.SavedPrompt

enum class SlashAction { NEW_CHAT, CLEAR, MODEL, AGENT, ATTACH, HELP }

data class SlashCommand(
    val name: String,
    @StringRes val descriptionRes: Int,
    val action: SlashAction,
)

/**
 * One entry in the composer's slash-command popup: either an app-level command or a command/skill
 * the connected backend advertises. All of them end up inserting `/<name> ` into the input; the
 * send path then routes backend commands and skills through the runtime's command handling.
 *
 * App commands carry a string resource so their description follows the app language; backend
 * entries carry the text the backend advertised.
 */
sealed interface SlashSuggestion {
    val name: String
    val description: String

    @get:StringRes
    val descriptionRes: Int?
        get() = null

    /**
     * [description] is empty by design: UI must resolve [descriptionRes] so the text follows the
     * app language. Only backend entries carry literal text.
     */
    data class App(val command: SlashCommand) : SlashSuggestion {
        override val name: String = command.name
        override val description: String = ""
        override val descriptionRes: Int = command.descriptionRes
    }

    data class Backend(
        override val name: String,
        override val description: String,
        val isSkill: Boolean = false,
    ) : SlashSuggestion

    /** One of the user's saved prompts: picking it puts [prompt]'s text in the composer. */
    data class Saved(val prompt: SavedPrompt) : SlashSuggestion {
        override val name: String = prompt.command
        override val description: String = prompt.text.lineSequence().first().take(SAVED_PREVIEW_LENGTH)
    }
}

private const val SAVED_PREVIEW_LENGTH = 60

object SlashCommandRegistry {
    val commands: List<SlashCommand> =
        listOf(
            SlashCommand("/new", R.string.slash_desc_new, SlashAction.NEW_CHAT),
            SlashCommand("/clear", R.string.slash_desc_clear, SlashAction.CLEAR),
            SlashCommand("/model", R.string.slash_desc_model, SlashAction.MODEL),
            SlashCommand("/agent", R.string.slash_desc_agent, SlashAction.AGENT),
            SlashCommand("/attach", R.string.slash_desc_attach, SlashAction.ATTACH),
            SlashCommand("/help", R.string.slash_desc_help, SlashAction.HELP),
        )

    val builtInCommandNames: Set<String> = commands.map { it.name }.toSet()

    /**
     * [text] with a saved prompt's command swapped for the prompt itself, when the message starts with
     * that command (`/review 123 urgent`). Whatever follows the command fills the prompt's
     * placeholders the way Claude Code's custom commands do: `$ARGUMENTS` takes all of it, `$1`..`$9`
     * one whitespace-separated word each. A prompt without placeholders gets the arguments appended
     * on a new paragraph, so nothing typed is lost. Built-in commands and other text pass unchanged.
     */
    fun expandSavedPrompt(
        text: String,
        savedPrompts: List<SavedPrompt>,
    ): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith("/")) return text
        val command = trimmed.substringBefore(' ').substringBefore('\n')
        if (command in builtInCommandNames) return text
        val prompt = savedPrompts.firstOrNull { it.command == command } ?: return text
        return fillArguments(prompt.text, trimmed.removePrefix(command).trim())
    }

    internal fun fillArguments(
        template: String,
        arguments: String,
    ): String {
        val words = arguments.split(Regex("\\s+")).filter(String::isNotEmpty)
        if (!hasPlaceholders(template)) return if (arguments.isEmpty()) template else "$template\n\n$arguments"
        // One pass, so an argument that itself contains `$1` is inserted as typed, not expanded again.
        return PLACEHOLDER.replace(template) { match ->
            val key = match.groupValues[1]
            if (key == "ARGUMENTS") arguments else words.getOrNull(key.toInt() - 1).orEmpty()
        }
    }

    fun hasPlaceholders(template: String): Boolean = PLACEHOLDER.containsMatchIn(template)

    private val PLACEHOLDER = Regex("\\$(ARGUMENTS|[1-9])")

    /**
     * Builds the popup list: app commands first, then the user's saved prompts, then the backend's
     * commands and skills, filtering everything by what the user has typed so far.
     */
    fun suggestions(
        query: String,
        backendCommands: List<OpenCodeCommand>,
        backendSkills: List<OpenCodeSkill>,
        savedPrompts: List<SavedPrompt> = emptyList(),
    ): List<SlashSuggestion> {
        val trimmed = query.trim()

        fun matches(name: String): Boolean = trimmed.isEmpty() || name.startsWith(trimmed, ignoreCase = true)

        val app = commands.filter { matches(it.name) }.map(SlashSuggestion::App)
        val saved =
            savedPrompts
                .filter { it.command != "/" && it.command !in builtInCommandNames && matches(it.command) }
                .sortedBy { it.command }
                .map(SlashSuggestion::Saved)
        val backend =
            backendCommands
                .filter { matches("/${it.name}") }
                .map { SlashSuggestion.Backend("/${it.name}", it.description.orEmpty()) } +
                backendSkills
                    .filter { matches("/${it.name}") }
                    .map { SlashSuggestion.Backend("/${it.name}", it.description.orEmpty(), isSkill = true) }
        return app + saved + backend.sortedBy { it.name }
    }
}
