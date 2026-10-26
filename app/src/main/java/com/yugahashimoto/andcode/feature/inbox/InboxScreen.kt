package com.yugahashimoto.andcode.feature.inbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.stateDescription
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.feature.chat.PermissionCard
import com.yugahashimoto.andcode.runtime.PermissionResponse
import com.yugahashimoto.andcode.ui.theme.LocalThemeColors
import com.yugahashimoto.andcode.ui.theme.Spacing

/**
 * One place for every chat that wants something, across all agents - in the manner of the task
 * inboxes of desktop agent apps. Approvals come first with their buttons right on the card, then
 * runs in progress with what each is doing and a Stop, then chats that finished unread with a
 * reply box, so most answers never need the chat itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    state: InboxUiState,
    relativeTime: (Long) -> String,
    onOpen: (InboxChat) -> Unit,
    onRespond: (InboxApproval, PermissionResponse, Boolean) -> Unit,
    onStop: (InboxChat) -> Unit,
    onReply: (InboxChat, String) -> Unit,
    onMarkRead: (InboxChat) -> Unit,
    onMarkAllRead: () -> Unit,
    onConsumeFailure: () -> Unit,
    onBack: () -> Unit,
) {
    val content = state.content
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(state.failure) {
        val failure = state.failure ?: return@LaunchedEffect
        val message =
            when (failure) {
                InboxFailure.APPROVAL -> R.string.inbox_failed_approval
                InboxFailure.STOP -> R.string.inbox_failed_stop
                InboxFailure.REPLY -> R.string.inbox_failed_reply
            }
        // Shown before it is consumed: consuming first changes this effect's key, which cancels the
        // snackbar while it is still on screen.
        snackbarHostState.showSnackbar(context.getString(message))
        onConsumeFailure()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.inbox_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back))
                    }
                },
                actions = {
                    if (content.finished.isNotEmpty()) {
                        IconButton(onClick = onMarkAllRead) {
                            Icon(Icons.Default.DoneAll, contentDescription = stringResource(R.string.inbox_mark_all_read))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (content.isEmpty) {
            InboxEmptyState(Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("inbox-list"),
            contentPadding = PaddingValues(start = Spacing.screenEdge, end = Spacing.screenEdge, bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            if (content.approvals.isNotEmpty()) {
                item(key = "h-approvals") {
                    InboxSectionHeader(stringResource(R.string.inbox_needs_you), content.approvals.size, LocalThemeColors.current.warning)
                }
                items(content.approvals, key = { "a-${it.request.id}" }) { approval ->
                    Column(
                        modifier = Modifier.animateItem(),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        ChatLine(approval.chat, onOpen = { onOpen(approval.chat) })
                        val busy = approval.request.id in state.busyIds
                        val sendingLabel = stringResource(R.string.inbox_sending)
                        Box(
                            modifier =
                                if (busy) {
                                    // The buttons under the veil are not there to press; say what is happening.
                                    Modifier.clearAndSetSemantics { stateDescription = sendingLabel }
                                } else {
                                    Modifier
                                },
                        ) {
                            PermissionCard(
                                permission = approval.request,
                                onPermission = { _, response, remember -> onRespond(approval, response, remember) },
                            )
                            if (busy) BusyOverlay()
                        }
                    }
                }
            }
            if (content.running.isNotEmpty()) {
                item(key = "h-running") {
                    InboxSectionHeader(stringResource(R.string.inbox_running), content.running.size, LocalThemeColors.current.statusRunning)
                }
                items(content.running, key = { "r-${it.chat.sessionId}" }) { run ->
                    RunningRow(
                        run = run,
                        stopping = run.chat.sessionId in state.busyIds,
                        onOpen = { onOpen(run.chat) },
                        onStop = { onStop(run.chat) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
            if (content.finished.isNotEmpty()) {
                item(key = "h-finished") {
                    InboxSectionHeader(
                        stringResource(R.string.inbox_finished),
                        content.finished.size,
                        LocalThemeColors.current.statusUnread,
                    )
                }
                items(content.finished, key = { "f-${it.sessionId}" }) { chat ->
                    FinishedRow(
                        chat = chat,
                        relativeTime = relativeTime,
                        sending = chat.sessionId in state.busyIds,
                        replyFailed = state.failure == InboxFailure.REPLY,
                        onOpen = { onOpen(chat) },
                        onReply = { text -> onReply(chat, text) },
                        onMarkRead = { onMarkRead(chat) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}
