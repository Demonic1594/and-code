package com.yugahashimoto.andcode.feature.chat

import com.yugahashimoto.andcode.core.api.OpenCodeFileChange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A snapshot call that fails gives [fallback]; leaving the screen still cancels it. */
internal suspend fun <T> snapshotCall(
    fallback: T,
    block: suspend () -> T,
): T =
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        fallback
    }

/**
 * The open chat's file restore points, for [ChatViewModel], on runtimes without checkpoints of their
 * own: a snapshot of the workspace before each user message, putting the files back to one and
 * undoing that, and a turn's changes for the diff. Turn numbers count the messages that went out.
 * Launches on the view model's scope and writes into its state; [onSettled] releases the prompts held
 * while a restore was rewriting files.
 */
internal class RestorePoints(
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<ChatUiState>,
    private val turnSnapshots: TurnSnapshots?,
    /** Shown when putting the files back failed. */
    private val restoreFailedMessage: String,
    /** Shown when a restore was refused because an ignored file is in the way, named when known. */
    private val restoreRefusedMessage: (String?) -> String,
    private val onSettled: () -> Unit,
) {
    /**
     * The restore or undo still rewriting files. Kept apart from [ChatUiState.isRestoringFiles],
     * which a chat switch clears for the screen: the files are still being rewritten, so nothing may
     * be sent until it ends, from whichever chat.
     */
    private var restoreJob: Job? = null

    val inFlight: Boolean
        get() = uiState.value.isRestoringFiles || restoreJob?.isActive == true

    /**
     * Called last by the restore or undo coroutine: it is still active at that point, so it lets go
     * of [restoreJob] first, or the prompts held for it would see a restore in flight and stay held.
     */
    private suspend fun finishRestoreJob() {
        val self = currentCoroutineContext()[Job]
        if (restoreJob === self) restoreJob = null
        onSettled()
    }

    /**
     * Messages shown while offline, before they were sent. When they do go out they are sent again as
     * new messages, so counting the placeholders would number every later turn one too high.
     */
    private val offlinePlaceholders = mutableSetOf<String>()

    /** [messages] without the offline placeholders, as the turn numbers count them. */
    private fun sentMessages(messages: List<ChatMessage>): List<ChatMessage> = messages.filterNot { it.id in offlinePlaceholders }

    /** Notes [messageId] as a message shown while offline, which the turn numbers do not count. */
    fun markOffline(messageId: String) {
        offlinePlaceholders += messageId
    }

    /** How many of the user's messages in [messages] went out: the turn number of the next one. */
    fun sentTurns(messages: List<ChatMessage>): Int = messages.count { it.isUser && it.id !in offlinePlaceholders }

    /** The folder the chat's snapshots are taken of: the session's, else the one picked for it. */
    fun snapshotDirectory(sessionDirectory: String? = null): String? =
        uiState.value.sessionDirectory ?: sessionDirectory ?: uiState.value.selectedWorkspacePath

    fun canRestoreFiles(messageId: String): Boolean {
        val state = uiState.value
        val sessionId = state.sessionId
        val isUserMessage = state.messages.any { it.id == messageId && it.isUser }
        val turn = turnOf(sentMessages(state.messages), messageId)
        return turnSnapshots != null && sessionId != null && isUserMessage && turn != null &&
            turnSnapshots.has(sessionId, turn)
    }

    /** [onRestored] runs once the files are back, in the same chat, and nothing is in flight any more. */
    fun restoreFilesTo(
        messageId: String,
        onRestored: () -> Unit = {},
    ) {
        val state = uiState.value
        val snapshots = turnSnapshots
        val sessionId = state.sessionId
        val directory = snapshotDirectory()
        val turn = turnOf(sentMessages(state.messages), messageId)
        if (snapshots == null || sessionId == null) return
        if (directory == null || turn == null) return
        if (state.isRunning || inFlight) return
        uiState.update { it.copy(isRestoringFiles = true) }
        restoreJob =
            scope.launch {
                val result = snapshotCall<RestoreResult>(RestoreResult.Failed) { snapshots.restore(sessionId, turn, directory) }
                val stillHere = uiState.value.sessionId == sessionId
                uiState.update {
                    when {
                        // The chat changed meanwhile: its Undo must not appear in another one.
                        it.sessionId != sessionId -> it.copy(isRestoringFiles = false)
                        result is RestoreResult.Restored ->
                            it.copy(isRestoringFiles = false, fileRestore = FileRestore(sessionId, directory, result.token))
                        // Nothing changed, and saying which file is in the way tells the user what to move.
                        result is RestoreResult.Refused -> it.copy(isRestoringFiles = false, error = restoreRefusedMessage(result.path))
                        else -> it.copy(isRestoringFiles = false, error = restoreFailedMessage)
                    }
                }
                finishRestoreJob()
                if (stillHere && result is RestoreResult.Restored) onRestored()
            }
    }

    fun undoFileRestore() {
        val snapshots = turnSnapshots ?: return
        val restore = uiState.value.fileRestore ?: return
        val busy = inFlight || uiState.value.isRunning
        if (busy || restore.sessionId != uiState.value.sessionId) return
        uiState.update { it.copy(isRestoringFiles = true) }
        restoreJob =
            scope.launch {
                val undone = snapshotCall(false) { snapshots.undoRestore(restore.directory, restore.token) }
                uiState.update {
                    when {
                        undone -> it.copy(isRestoringFiles = false, fileRestore = null)
                        it.sessionId != restore.sessionId -> it.copy(isRestoringFiles = false)
                        else -> it.copy(isRestoringFiles = false, error = restoreFailedMessage)
                    }
                }
                finishRestoreJob()
            }
    }

    fun dismissFileRestore() {
        uiState.update { it.copy(fileRestore = null) }
    }

    /** The changes of the turn [patch] belongs to, from its snapshots; empty when there are none. */
    suspend fun snapshotChanges(
        sessionId: String,
        patch: ChatPart.Patch,
    ): List<OpenCodeFileChange> {
        val snapshots = turnSnapshots ?: return emptyList()
        val directory = snapshotDirectory() ?: return emptyList()
        val turn = turnOf(sentMessages(uiState.value.messages), patch.messageId) ?: return emptyList()
        val changes = snapshotCall(null) { snapshots.turnChanges(sessionId, turn, directory) }.orEmpty()
        return changesForPatch(changes, patch.files)
    }

    /** Best effort: a turn without a snapshot only loses its restore point, never the message. */
    suspend fun recordTurnSnapshot(
        sessionId: String,
        turn: Int,
        sessionDirectory: String?,
    ) {
        val directory = snapshotDirectory(sessionDirectory) ?: return
        snapshotCall(Unit) { turnSnapshots?.record(sessionId, turn, directory) }
    }
}
