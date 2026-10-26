package com.yugahashimoto.andcode.feature.inbox

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.feature.chat.PermissionCard
import com.yugahashimoto.andcode.runtime.PermissionResponse
import com.yugahashimoto.andcode.ui.components.SessionStatus
import com.yugahashimoto.andcode.ui.components.StatusDot
import com.yugahashimoto.andcode.ui.theme.AppShapes
import com.yugahashimoto.andcode.ui.theme.CodeFontFamily
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
        onConsumeFailure()
        snackbarHostState.showSnackbar(context.getString(message))
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
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ChatLine(approval.chat, onOpen = { onOpen(approval.chat) })
                        Box {
                            PermissionCard(
                                permission = approval.request,
                                onPermission = { _, response, remember -> onRespond(approval, response, remember) },
                            )
                            if (approval.request.id in state.busyIds) BusyOverlay()
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

@Composable
private fun InboxSectionHeader(
    title: String,
    count: Int,
    accent: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.lg, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Surface(shape = AppShapes.pill, color = accent.copy(alpha = 0.16f), contentColor = accent) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = CodeFontFamily,
                modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 1.dp),
            )
        }
    }
}

/** The chat a card belongs to: its agent's mark and title, tappable to open it. */
@Composable
private fun ChatLine(
    chat: InboxChat,
    onOpen: () -> Unit,
) {
    Surface(onClick = onOpen, shape = AppShapes.sm, color = Color.Transparent) {
        Row(
            modifier = Modifier.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            AgentMark(chat)
            Text(
                text = chatTitle(chat),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AgentMark(chat: InboxChat) {
    val agent = chat.agent ?: return
    Image(
        painter = painterResource(agent.iconRes),
        contentDescription = stringResource(agent.displayNameRes),
        modifier = Modifier.size(16.dp),
    )
}

@Composable
private fun chatTitle(chat: InboxChat): String = chat.title.ifBlank { stringResource(R.string.new_chat) }

@Composable
private fun InboxCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = AppShapes.lg,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        content()
    }
}

@Composable
private fun RunningRow(
    run: InboxRun,
    stopping: Boolean,
    onOpen: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    InboxCard(onClick = onOpen, modifier = modifier) {
        Row(
            modifier = Modifier.padding(start = Spacing.lg, end = Spacing.xs, top = Spacing.md, bottom = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            StatusDot(SessionStatus.RUNNING)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AgentMark(run.chat)
                    Text(
                        text = chatTitle(run.chat),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = run.step ?: stringResource(R.string.inbox_working),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = if (run.step != null) CodeFontFamily else null,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (stopping) {
                CircularProgressIndicator(modifier = Modifier.padding(Spacing.md).size(20.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onStop) {
                    Icon(Icons.Default.Stop, contentDescription = stringResource(R.string.cd_stop), tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun FinishedRow(
    chat: InboxChat,
    relativeTime: (Long) -> String,
    sending: Boolean,
    onOpen: () -> Unit,
    onReply: (String) -> Unit,
    onMarkRead: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var replying by rememberSaveable(chat.sessionId) { mutableStateOf(false) }
    var draft by rememberSaveable(chat.sessionId) { mutableStateOf("") }
    val send = {
        if (draft.isNotBlank() && !sending) onReply(draft)
    }
    InboxCard(onClick = onOpen, modifier = modifier.animateContentSize()) {
        Column {
            Row(
                modifier = Modifier.padding(start = Spacing.lg, end = Spacing.xs, top = Spacing.sm, bottom = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                StatusDot(SessionStatus.COMPLETED_UNREAD)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = chatTitle(chat),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AgentMark(chat)
                        Text(
                            text = relativeTime(chat.updatedAt),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = { replying = !replying }) {
                    Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = stringResource(R.string.inbox_reply))
                }
                IconButton(onClick = onMarkRead) {
                    Icon(Icons.Default.Check, contentDescription = stringResource(R.string.inbox_mark_read))
                }
            }
            AnimatedVisibility(visible = replying) {
                Row(
                    modifier = Modifier.padding(start = Spacing.md, end = Spacing.xs, bottom = Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        placeholder = { Text(stringResource(R.string.inbox_reply_hint)) },
                        enabled = !sending,
                        maxLines = 4,
                        shape = AppShapes.md,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { send() }),
                        modifier = Modifier.weight(1f).testTag("inbox-reply-${chat.sessionId}"),
                    )
                    if (sending) {
                        CircularProgressIndicator(modifier = Modifier.padding(Spacing.md).size(20.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = send, enabled = draft.isNotBlank()) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.cd_send))
                        }
                    }
                }
            }
        }
    }
}

/** Covers a card whose answer is on its way, so a second tap cannot send another. */
@Composable
private fun BoxScope.BusyOverlay() {
    Surface(
        modifier = Modifier.matchParentSize(),
        shape = AppShapes.lg,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun InboxEmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp).testTag("inbox-empty"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(shape = AppShapes.pill, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)) {
            Icon(
                Icons.Outlined.Inbox,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(Spacing.lg).size(32.dp),
            )
        }
        Spacer(Modifier.height(Spacing.lg))
        Text(stringResource(R.string.inbox_empty_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(Spacing.sm))
        Text(
            stringResource(R.string.inbox_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
