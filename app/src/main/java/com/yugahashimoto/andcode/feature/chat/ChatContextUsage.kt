package com.yugahashimoto.andcode.feature.chat

import com.yugahashimoto.andcode.core.pricing.PriceCatalog
import com.yugahashimoto.andcode.core.util.safeMessage
import com.yugahashimoto.andcode.runtime.OpenCodeBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One refresh of the meter: tokens in the window, the window's size when the agent reported it (else 0), and the totals. */
private data class ContextReading(
    val used: Long,
    val window: Long,
    val usage: SessionUsage?,
)

/**
 * The open chat's context window, for [ChatViewModel]: the meter's reading, the sheet that says how
 * the window is split, and compacting it on request. Launches on the view model's scope and writes
 * into its state.
 */
internal class ContextUsage(
    private val backend: OpenCodeBackend?,
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<ChatUiState>,
    /** The current price catalog, for the usage row's estimate on turns the runtime priced at nothing. */
    private val priceCatalog: () -> PriceCatalog?,
    /** Shown when compacting failed and the runtime gave no reason; read when it is shown. */
    private val compactFailedMessage: () -> String,
) {
    fun refresh(sessionId: String) {
        val currentBackend = backend ?: return
        // Captured for staleness: a fetch for chat A landing after the switch to chat B wrote
        // A's usage into B, and B's >=90% reading then fired summarizeSession against B.
        val generation = uiState.value.chatGeneration
        scope.launch {
            runCatching {
                // The indicator (and the >=90% summarize gate below) must read the context
                // window's occupancy, which only the last assistant message's usage reports:
                // `input + cache.read` of that single LLM call. The session endpoint aggregates
                // the same fields over every turn of the session - with prompt caching each turn
                // re-reads the whole prefix, so that sum grows by roughly a window per turn and
                // reached "800k/1.0M" within minutes of real usage. Summed lifetime tokens made
                // shouldSummarizeBeforePrompt fire before every prompt, and each summarize turn
                // then added another window's worth to the sum: a self-amplifying loop. The
                // session number is therefore only a fallback for sessions whose messages carry
                // no tokens at all (there it is ~0 anyway), never the primary source.
                val messages = currentBackend.listMessages(sessionId)
                val usage = summarizeSessionUsage(messages, priceCatalog())
                // An agent that reports its window's fill itself (Codex) is taken at its word.
                val reported = runCatching { currentBackend.contextUsage(sessionId) }.getOrNull()
                val used =
                    reported?.usedTokens
                        ?: messages
                            .asReversed()
                            .firstNotNullOfOrNull { message ->
                                message.info.tokens?.contextUsed
                                    ?.takeIf { !message.info.role.equals("user", ignoreCase = true) }
                            }
                        ?: currentBackend.session(sessionId).tokens?.contextUsed
                        ?: 0L
                ContextReading(used, reported?.windowTokens ?: 0L, usage)
            }.onSuccess { reading ->
                if (uiState.value.chatGeneration != generation) return@onSuccess
                uiState.update {
                    it.copy(contextTokensUsed = reading.used, contextWindow = reading.window, sessionUsage = reading.usage)
                }
            }
        }
    }

    /** Bumped by every [showBreakdown], so only the latest request's answer fills the sheet. */
    private var breakdownRequest = 0L

    fun showBreakdown() {
        val currentBackend = backend ?: return
        val sessionId = uiState.value.sessionId
        val request = ++breakdownRequest
        uiState.update { it.copy(contextBreakdown = ContextBreakdownState.Loading) }
        scope.launch {
            val breakdown = sessionId?.let { runCatching { currentBackend.contextBreakdown(it) }.getOrNull() }
            uiState.update { state ->
                // Closed meanwhile, or asked for again since (perhaps on another chat, after the
                // switch closed this one): the sheet is not this answer's to fill or to close.
                if (request != breakdownRequest || state.contextBreakdown != ContextBreakdownState.Loading) {
                    state
                } else if (state.sessionId != sessionId) {
                    // The composer moved to another chat under the sheet still waiting on this
                    // answer: closed rather than left spinning.
                    state.copy(contextBreakdown = ContextBreakdownState.Hidden)
                } else {
                    state.copy(contextBreakdown = breakdown?.let(ContextBreakdownState::Shown) ?: ContextBreakdownState.Unavailable)
                }
            }
        }
    }

    fun hideBreakdown() {
        uiState.update { it.copy(contextBreakdown = ContextBreakdownState.Hidden) }
    }

    fun compact() {
        val currentBackend = backend ?: return
        val state = uiState.value
        val sessionId = state.sessionId ?: return
        if (!state.compactAvailable || state.isRunning) return
        uiState.update { it.copy(contextBreakdown = ContextBreakdownState.Hidden) }
        scope.launch {
            runCatching { currentBackend.compactSession(sessionId) }
                .onFailure { error ->
                    if (uiState.value.sessionId == sessionId) {
                        val message = error.safeMessage(compactFailedMessage())
                        uiState.update { it.copy(error = message) }
                    }
                }
        }
    }
}
