package com.yugahashimoto.andcode.feature.chat

import com.yugahashimoto.andcode.core.api.PullRequestRef
import com.yugahashimoto.andcode.data.repository.PullRequestStatusRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Floor between transcript scans for pull request links, so streaming does not drive them. */
private const val PULL_REQUEST_SCAN_THROTTLE_MS = 300L

/**
 * The pull request badges above the composer, for [ChatViewModel]: the links in the open chat's
 * transcript, less the ones the user hid, with their state from GitHub. Launches on the view model's
 * scope and writes into its state; [statuses] is absent in tests and previews.
 */
internal class PullRequestBadges(
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<ChatUiState>,
    private val statuses: PullRequestStatusRepository?,
) {
    private val pullRequestRefs = MutableStateFlow<List<PullRequestRef>>(emptyList())

    /**
     * Pull requests the user hid with [dismiss]. Scanning re-derives the refs from the
     * transcript on every message change, so without this set every refetch would put the dismissed
     * badge back. Like the dismissed questions it is cleared when the composer moves to another chat:
     * the dismissal belongs to the chat it was made in, and opening a chat is an explicit act of
     * attention that offers its badges again.
     */
    private val dismissedPullRequestKeys = mutableSetOf<String>()

    /**
     * Keeps the pull request badges above the composer in step with the transcript.
     *
     * Scanning is conflated rather than run per update: a streaming answer rewrites the message
     * list many times a second, and the links it contains do not change that fast. State and links
     * are combined separately so a badge appears the moment its link is printed, then fills in with
     * the diff size and state once GitHub answers.
     */
    fun track(statuses: PullRequestStatusRepository) {
        scope.launch {
            uiState
                .map { it.messages }
                .distinctUntilChanged()
                .conflate()
                .collect { messages ->
                    val refs = pullRequestRefsIn(messages).filterNot { it.key in dismissedPullRequestKeys }
                    pullRequestRefs.value = refs
                    statuses.track(refs)
                    delay(PULL_REQUEST_SCAN_THROTTLE_MS)
                }
        }
        scope.launch {
            combine(pullRequestRefs, statuses.statuses) { refs, byKey ->
                refs.map { ref -> ChatPullRequest(ref, byKey[ref.key]) }
            }
                .distinctUntilChanged()
                .collect { pullRequests -> uiState.update { it.copy(pullRequests = pullRequests) } }
        }
    }

    /** Refetches any pull request state that has gone stale; see [ChatViewModel.refreshPullRequests]. */
    fun refresh() {
        statuses?.track(pullRequestRefs.value)
    }

    /** Hides one pull request's badges for as long as this chat is open; see [ChatViewModel.dismissPullRequest]. */
    fun dismiss(key: String) {
        dismissedPullRequestKeys += key
        pullRequestRefs.value = pullRequestRefs.value.filterNot { it.key == key }
    }

    /** The composer moved to another chat, whose badges are all offered again. */
    fun forgetDismissals() {
        dismissedPullRequestKeys.clear()
    }
}
