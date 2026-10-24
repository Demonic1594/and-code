package com.yugahashimoto.andcode.data.repository

import com.yugahashimoto.andcode.core.api.OpenCodeSession
import com.yugahashimoto.andcode.runtime.LocalAgent
import com.yugahashimoto.andcode.runtime.RuntimeTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

/** An archived chat and the runtime that owns it. */
data class ArchivedChat(
    val runtimeId: String,
    val agent: LocalAgent?,
    val session: OpenCodeSession,
)

/**
 * What happened to each chat an action was asked for. [failed] maps a chat id to the reason the
 * runtime gave, so the UI can say why a chat is still there instead of letting it quietly reappear.
 */
data class ChatActionResult(
    val succeeded: Set<String> = emptySet(),
    val failed: Map<String, String> = emptyMap(),
)

/** Which action a [ChatActionResult] reports, for the message shown afterwards. */
enum class ChatAction { DELETE, ARCHIVE, RESTORE }

data class ChatActionReport(
    val action: ChatAction,
    val result: ChatActionResult,
)

/**
 * The one place chats are deleted, archived and restored.
 *
 * Each call goes to the runtime that owns the chat (from [ownerOf], or every runtime when that is
 * unknown), reports per chat whether it worked, and on a delete also clears what the app itself
 * kept about the chat ([forgetLocally]: pin, draft, unread mark), so nothing of a deleted chat is
 * left behind.
 *
 * A delete from the drawer is not sent at once: [scheduleDelete] hides the chats ([pendingDeletion])
 * for [undoWindowMillis] first, so the snackbar's Undo can take it back. Only after that does the
 * delete reach the runtime, and its outcome arrives on [reports].
 */
class ChatLifecycleRepository(
    private val targets: () -> List<RuntimeTarget>,
    private val ownerOf: (String) -> String?,
    private val scope: CoroutineScope,
    private val forgetLocally: (String) -> Unit,
    private val onRestored: (String) -> Unit = {},
    private val onChatsChanged: () -> Unit = {},
    private val undoWindowMillis: Long = UNDO_WINDOW_MILLIS,
) {
    private val mutablePendingDeletion = MutableStateFlow<Set<String>>(emptySet())

    /** Chats waiting out the undo window. Lists hide them as if they were already gone. */
    val pendingDeletion: StateFlow<Set<String>> = mutablePendingDeletion.asStateFlow()

    private val mutableReports = MutableSharedFlow<ChatActionReport>(extraBufferCapacity = 16)

    /** The outcome of every delete, archive and restore, for the snackbar. */
    val reports: SharedFlow<ChatActionReport> = mutableReports.asSharedFlow()

    private val pendingJobs = mutableMapOf<Set<String>, Job>()

    /**
     * Hides [requested] now and deletes them once the undo window passes without [undoDelete].
     * Returns the chats actually scheduled, which is what Undo has to pass back: any already waiting
     * from an earlier delete keep their own window.
     */
    fun scheduleDelete(requested: Set<String>): Set<String> {
        val sessionIds = requested - mutablePendingDeletion.value
        if (sessionIds.isEmpty()) return emptySet()
        mutablePendingDeletion.update { it + sessionIds }
        val job =
            scope.launch {
                delay(undoWindowMillis)
                synchronized(pendingJobs) { pendingJobs.remove(sessionIds) }
                try {
                    deleteNow(sessionIds.associateWith { null })
                } finally {
                    mutablePendingDeletion.update { it - sessionIds }
                }
            }
        synchronized(pendingJobs) { pendingJobs[sessionIds] = job }
        return sessionIds
    }

    /** Cancels a [scheduleDelete] that has not run yet; the chats show again. */
    fun undoDelete(sessionIds: Set<String>) {
        val job = synchronized(pendingJobs) { pendingJobs.remove(sessionIds) } ?: return
        job.cancel()
        mutablePendingDeletion.update { it - sessionIds }
    }

    /**
     * Deletes each chat on its runtime right away. The map's values name the owning runtime when the
     * caller knows it (the Archive screen does); null looks it up via [ownerOf].
     */
    suspend fun deleteNow(chats: Map<String, String?>): ChatActionResult {
        val result = runOnOwners(chats) { target, id -> target.deleteSession(id) }
        result.succeeded.forEach(forgetLocally)
        return report(ChatAction.DELETE, result)
    }

    suspend fun archive(chats: Map<String, String?>): ChatActionResult =
        report(ChatAction.ARCHIVE, runOnOwners(chats) { target, id -> target.archiveSession(id) })

    suspend fun restore(chats: Map<String, String?>): ChatActionResult {
        val result = runOnOwners(chats) { target, id -> target.unarchiveSession(id) }
        result.succeeded.forEach(onRestored)
        return report(ChatAction.RESTORE, result)
    }

    /** Every runtime's archived chats, newest first. A runtime that cannot be reached adds none. */
    suspend fun listArchived(): List<ArchivedChat> =
        supervisorScope {
            targets()
                .map { target ->
                    async {
                        runCatching { target.listArchivedSessions() }.getOrDefault(emptyList())
                            .map { ArchivedChat(target.id, target.agent, it) }
                    }
                }.awaitAll()
                .flatten()
                .distinctBy { it.runtimeId to it.session.id }
                .sortedByDescending { it.session.time.archived ?: it.session.time.updated ?: it.session.time.created }
        }

    private suspend fun report(
        action: ChatAction,
        result: ChatActionResult,
    ): ChatActionResult {
        onChatsChanged()
        mutableReports.emit(ChatActionReport(action, result))
        return result
    }

    /**
     * Runs [action] for every chat on its owning runtime, or on every runtime when the owner is not
     * known, in parallel. A chat counts as done when any runtime it was sent to succeeded; otherwise
     * the owner's error (or the first one, when the owner is unknown) is its reason.
     */
    private suspend fun runOnOwners(
        chats: Map<String, String?>,
        action: suspend (RuntimeTarget, String) -> Any,
    ): ChatActionResult {
        if (chats.isEmpty()) return ChatActionResult()
        val available = targets()
        val outcomes =
            supervisorScope {
                chats.map { (id, runtimeId) ->
                    async {
                        val owner = (runtimeId ?: ownerOf(id))?.let { owner -> available.filter { it.id == owner } }
                        val candidates = owner?.takeIf { it.isNotEmpty() } ?: available
                        val attempts = candidates.map { target -> runCatching { action(target, id) } }
                        val ok = attempts.any { attempt -> attempt.getOrNull().let { it != null && it != false } }
                        id to if (ok) null else failureReason(attempts)
                    }
                }.awaitAll()
            }
        return ChatActionResult(
            succeeded = outcomes.filter { it.second == null }.map { it.first }.toSet(),
            failed = outcomes.mapNotNull { (id, reason) -> reason?.let { id to it } }.toMap(),
        )
    }

    private fun failureReason(attempts: List<Result<Any>>): String =
        attempts.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { error ->
            when (error) {
                is UnsupportedOperationException -> UNSUPPORTED
                else -> error.message?.takeIf { it.isNotBlank() } ?: error::class.java.simpleName
            }
        } ?: NOT_FOUND

    companion object {
        const val UNDO_WINDOW_MILLIS = 5_000L

        /** Reasons the UI replaces with its own localized text. */
        const val UNSUPPORTED = "unsupported"
        const val NOT_FOUND = "not-found"
    }
}
