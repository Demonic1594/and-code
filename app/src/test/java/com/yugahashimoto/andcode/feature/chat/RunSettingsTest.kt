package com.yugahashimoto.andcode.feature.chat

import com.yugahashimoto.andcode.runtime.local.SystemPromptPreset
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunSettingsTest {
    private val preset = SystemPromptPreset(id = "debug", name = "Debugging", prompt = "Find the bug.")

    private fun settings(
        permissionMode: PermissionModeSelection? = null,
        supportsAutoAccept: Boolean = true,
        autoAccept: Boolean = false,
        thinkingOptions: List<String> = listOf("low", "high"),
        selectedThinking: String? = null,
        fastModeAvailable: Boolean = false,
        fastModeEnabled: Boolean = false,
        presets: List<SystemPromptPreset> = listOf(preset),
        selectedSystemPromptId: String? = null,
    ) = RunSettings(
        agents = emptyList(),
        selectedAgentId = null,
        agentsLoading = false,
        permissionMode = permissionMode,
        supportsAutoAccept = supportsAutoAccept,
        autoAccept = autoAccept,
        thinkingOptions = thinkingOptions,
        selectedThinking = selectedThinking,
        fastModeAvailable = fastModeAvailable,
        fastModeEnabled = fastModeEnabled,
        fastModeDescription = null,
        systemPromptPresets = presets,
        selectedSystemPromptId = selectedSystemPromptId,
    )

    private val claudeMode =
        PermissionModeSelection(selectedId = "default", selectedLabelRes = 0, options = emptyList(), onSelect = {})

    @Test
    fun `a fresh chat has no overrides`() {
        assertFalse(settings().hasOverrides)
    }

    @Test
    fun `thinking level, fast mode, auto-accept and a preset each count as an override`() {
        assertTrue(settings(selectedThinking = "high").hasOverrides)
        assertTrue(settings(fastModeAvailable = true, fastModeEnabled = true).hasOverrides)
        assertTrue(settings(autoAccept = true).hasOverrides)
        assertTrue(settings(selectedSystemPromptId = "debug").hasOverrides)
    }

    @Test
    fun `settings the runtime does not offer are not overrides`() {
        // A remembered fast-mode choice on a model without a fast tier changes nothing.
        assertFalse(settings(fastModeAvailable = false, fastModeEnabled = true).hasOverrides)
        // Auto-accept is remembered globally but means nothing where permissions are never asked.
        assertFalse(settings(supportsAutoAccept = false, autoAccept = true).hasOverrides)
        // Claude Code decides permissions by mode, so the OpenCode auto-accept switch is not shown.
        assertFalse(settings(permissionMode = claudeMode, autoAccept = true).hasOverrides)
        // A thinking level left over from a model that offers none has no section to undo it.
        assertFalse(settings(thinkingOptions = emptyList(), selectedThinking = "high").hasOverrides)
        // A preset id that was deleted in Settings falls back to no preset.
        assertFalse(settings(selectedSystemPromptId = "gone").hasOverrides)
    }
}
