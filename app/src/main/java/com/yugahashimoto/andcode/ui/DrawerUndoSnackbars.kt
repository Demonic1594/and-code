package com.yugahashimoto.andcode.ui

import android.content.Context
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.data.repository.ChatLifecycleRepository
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Says [count] chats were deleted, with Undo while the delete is still waiting.
 *
 * A snackbar with an action never times out on its own, and Undo is only real while the delete is
 * still waiting: the snackbar is cut to that window, and one already showing is dismissed so this
 * one does not queue behind it.
 */
internal suspend fun offerUndoDelete(
    host: SnackbarHostState,
    context: Context,
    count: Int,
    undo: suspend () -> Unit,
) {
    val message =
        if (count == 1) {
            context.getString(R.string.chat_deleted_snackbar)
        } else {
            context.getString(R.string.chats_deleted_snackbar, count)
        }
    host.currentSnackbarData?.dismiss()
    val choice =
        withTimeoutOrNull(ChatLifecycleRepository.UNDO_WINDOW_MILLIS) {
            host.showSnackbar(
                message,
                actionLabel = context.getString(R.string.code_undo),
                duration = SnackbarDuration.Indefinite,
            )
        }
    if (choice == SnackbarResult.ActionPerformed) undo()
}

/** Says [count] chats were archived, with Undo. */
internal suspend fun offerUndoArchive(
    host: SnackbarHostState,
    context: Context,
    count: Int,
    undo: suspend () -> Unit,
) {
    val message =
        if (count == 1) {
            context.getString(R.string.chat_archived_snackbar)
        } else {
            context.getString(R.string.chats_archived_snackbar, count)
        }
    host.currentSnackbarData?.dismiss()
    val choice =
        host.showSnackbar(
            message,
            actionLabel = context.getString(R.string.code_undo),
            duration = SnackbarDuration.Short,
        )
    if (choice == SnackbarResult.ActionPerformed) undo()
}
