package com.yugahashimoto.andcode.feature.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * The `serviceTier` a prompt carries for a model whose fast tier is [fastTier], given the user's
 * [fastMode] choice: null when the model has no fast tier or the choice still follows the model's
 * default (Codex decides), the tier when fast mode is on, and `"default"` when it was turned off on a
 * model that would otherwise run fast.
 */
internal fun codexServiceTier(
    fastTier: String?,
    fastMode: Boolean?,
): String? {
    fastTier ?: return null
    return when (fastMode) {
        null -> null
        true -> fastTier
        false -> "default"
    }
}

/**
 * The model the open chat sends with, for [ChatViewModel], and what follows from it: the context
 * limit the summarize gate measures against, Codex's fast tier and Claude Code's ultracode, each
 * combined with the user's saved choice. Writes the derived fields into the view model's state.
 */
internal class ModelSelection(
    private val uiState: MutableStateFlow<ChatUiState>,
    /** Persists the Codex fast-mode choice (null = follow the model default). */
    private val onCodexFastModeChanged: (Boolean?) -> Unit,
    initialCodexFastMode: Boolean?,
    /** Persists the Claude Code ultracode choice. */
    private val onClaudeUltracodeChanged: (Boolean) -> Unit,
    initialClaudeUltracode: Boolean,
) {
    private var contextLimit: Long = 0L

    /** Codex fast-mode user choice; null follows the selected model's own default. */
    private var codexFastMode: Boolean? = initialCodexFastMode

    /** The user's ultracode choice; it applies only while the selected model supports it. */
    private var claudeUltracode: Boolean = initialClaudeUltracode

    /** Whether the selected model can run in ultracode. */
    private var selectedUltracodeAvailable: Boolean = false

    /** The selected model's Codex fast tier id, or null when that model offers no fast mode. */
    private var selectedFastServiceTier: String? = null

    /** Whether the selected model's Codex fast tier is on by default. */
    private var selectedFastByDefault: Boolean = false

    fun selectConfiguration(
        providerId: String?,
        modelId: String?,
        agentId: String?,
        contextLimit: Long = 0L,
        fastServiceTier: String? = null,
        fastByDefault: Boolean = false,
        fastDescription: String? = null,
        ultracodeAvailable: Boolean = false,
    ) {
        this.contextLimit = contextLimit
        selectedFastServiceTier = fastServiceTier
        selectedFastByDefault = fastByDefault
        selectedUltracodeAvailable = ultracodeAvailable
        uiState.update {
            val modelChanged = providerId != it.selectedProviderId || modelId != it.selectedModelId
            it.copy(
                selectedProviderId = providerId,
                selectedModelId = modelId,
                selectedAgentId = agentId,
                // A reasoning-effort variant belongs to the model that offered it, not to the chat
                // session, so carrying it across a model switch can silently apply an effort the new
                // model never listed - AntigravityModels.cliArgs in particular reads a leftover
                // variant straight off whatever provider set it last, for any model whose id has no
                // effort baked in.
                selectedVariant = if (modelChanged) null else it.selectedVariant,
                fastModeDescription = fastDescription,
            )
        }
        refreshFastMode()
        refreshUltracode()
    }

    private fun refreshUltracode() {
        uiState.update {
            it.copy(
                ultracodeAvailable = selectedUltracodeAvailable,
                ultracodeEnabled = selectedUltracodeAvailable && claudeUltracode,
            )
        }
    }

    fun setUltracode(enabled: Boolean) {
        claudeUltracode = enabled
        onClaudeUltracodeChanged(enabled)
        refreshUltracode()
    }

    /**
     * Derives the three fast-mode fields from the model and the user's choice. Fast mode exists only
     * for a model that lists a fast tier, so every other model reads as unavailable.
     */
    private fun refreshFastMode() {
        val available = selectedFastServiceTier != null
        uiState.update {
            it.copy(
                fastModeAvailable = available,
                fastModeEnabled = available && (codexFastMode ?: selectedFastByDefault),
            )
        }
    }

    fun setFastMode(enabled: Boolean?) {
        codexFastMode = enabled
        onCodexFastModeChanged(enabled)
        refreshFastMode()
    }

    /**
     * The `serviceTier` for a prompt on the selected model: null when fast mode is unavailable or
     * still follows the default (Codex then chooses), the model's fast tier when the user turned it
     * on, and `"default"` when they turned it off on a model that would otherwise be fast.
     */
    fun serviceTierForPrompt(): String? = codexServiceTier(selectedFastServiceTier, codexFastMode)

    /** Whether the next prompt runs in ultracode: the user's choice, on a model that supports it. */
    val ultracodeForPrompt: Boolean
        get() = selectedUltracodeAvailable && claudeUltracode

    /**
     * Measured against the model's listed limit only, never [ChatUiState.contextWindow]: an agent that
     * reports its own window (Codex) compacts on its own, and is compacted on request from the sheet.
     */
    fun shouldSummarizeBeforePrompt(): Boolean =
        contextLimit > 0L &&
            uiState.value.contextTokensUsed.toDouble() / contextLimit.toDouble() >= 0.9
}
