package com.yugahashimoto.andcode.feature.inbox

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.ui.components.SessionStatus
import com.yugahashimoto.andcode.ui.components.StatusDot
import com.yugahashimoto.andcode.ui.theme.AppShapes
import com.yugahashimoto.andcode.ui.theme.CodeFontFamily
import com.yugahashimoto.andcode.ui.theme.Spacing

@Composable
internal fun InboxSectionHeader(
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
                modifier = Modifier.padding(horizontal = Spacing.sm),
            )
        }
    }
}

/** The chat a card belongs to: its agent's mark and title, tappable to open it. */
@Composable
internal fun ChatLine(
    chat: InboxChat,
    onOpen: () -> Unit,
) {
    Surface(onClick = onOpen, shape = AppShapes.sm, color = Color.Transparent) {
        Row(
            modifier = Modifier.padding(vertical = Spacing.hairline),
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
internal fun AgentMark(chat: InboxChat) {
    val agent = chat.agent ?: return
    Image(
        painter = painterResource(agent.iconRes),
        contentDescription = stringResource(agent.displayNameRes),
        modifier = Modifier.size(16.dp),
    )
}

@Composable
internal fun chatTitle(chat: InboxChat): String = chat.title.ifBlank { stringResource(R.string.new_chat) }

@Composable
internal fun InboxCard(
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
internal fun RunningRow(
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
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.hairline)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
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
internal fun FinishedRow(
    chat: InboxChat,
    relativeTime: (Long) -> String,
    sending: Boolean,
    replyFailed: Boolean,
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
    // A reply that went out leaves nothing to keep: the same chat finishing again later must not
    // reopen with it. One that failed stays, so it can be sent again.
    var wasSending by remember { mutableStateOf(false) }
    LaunchedEffect(sending) {
        if (wasSending && !sending && !replyFailed) {
            draft = ""
            replying = false
        }
        wasSending = sending
    }
    InboxCard(onClick = onOpen, modifier = modifier.animateContentSize()) {
        Column {
            Row(
                modifier = Modifier.padding(start = Spacing.lg, end = Spacing.xs, top = Spacing.sm, bottom = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                StatusDot(SessionStatus.COMPLETED_UNREAD)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.hairline)) {
                    Text(
                        text = chatTitle(chat),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        AgentMark(chat)
                        Text(
                            text = relativeTime(chat.updatedAt),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // Another agent's chat opens instead: its events are not followed here, so a reply
                // sent from the inbox could never be seen to finish.
                IconButton(onClick = { if (chat.canReply) replying = !replying else onOpen() }) {
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
internal fun BoxScope.BusyOverlay() {
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
internal fun InboxEmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = Spacing.xxl).testTag("inbox-empty"),
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
