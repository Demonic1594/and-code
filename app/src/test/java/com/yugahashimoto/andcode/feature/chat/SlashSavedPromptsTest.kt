package com.yugahashimoto.andcode.feature.chat

import com.yugahashimoto.andcode.data.settings.SavedPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SlashSavedPromptsTest {
    private val saved =
        listOf(
            SavedPrompt("1", "review", "Review the staged diff\nand list risks"),
            SavedPrompt("2", "new", "Shadowing a built-in"),
            SavedPrompt("3", "explain", "Explain this file"),
        )

    @Test
    fun `saved prompts follow app commands and filter by prefix`() {
        val names = SlashCommandRegistry.suggestions("/re", emptyList(), emptyList(), saved).map { it.name }
        assertEquals(listOf("/review"), names)
    }

    @Test
    fun `a saved prompt never shadows a built-in command`() {
        val suggestions = SlashCommandRegistry.suggestions("/new", emptyList(), emptyList(), saved)
        assertFalse(suggestions.any { it is SlashSuggestion.Saved })
    }

    @Test
    fun `description previews the first line`() {
        val review = SlashCommandRegistry.suggestions("/review", emptyList(), emptyList(), saved).single() as SlashSuggestion.Saved
        assertEquals("Review the staged diff", review.description)
    }
}

class SavedPromptExpansionTest {
    private val saved = listOf(SavedPrompt("1", "review", "Review the staged diff"), SavedPrompt("2", "new", "shadow"))

    @Test
    fun `a typed saved command expands to its text`() {
        assertEquals("Review the staged diff", SlashCommandRegistry.expandSavedPrompt(" /review ", saved))
    }

    @Test
    fun `built-in commands, other text and commands with arguments are left alone`() {
        assertEquals("/new", SlashCommandRegistry.expandSavedPrompt("/new", saved))
        assertEquals("/review now", SlashCommandRegistry.expandSavedPrompt("/review now", saved))
        assertEquals("hello", SlashCommandRegistry.expandSavedPrompt("hello", saved))
    }
}
