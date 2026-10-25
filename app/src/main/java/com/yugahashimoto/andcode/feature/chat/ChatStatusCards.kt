package com.yugahashimoto.andcode.feature.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.core.api.RunRetry
import com.yugahashimoto.andcode.core.diagnostics.StallDiagnosis
import com.yugahashimoto.andcode.core.diagnostics.StallReason
import com.yugahashimoto.andcode.core.diagnostics.explain
import com.yugahashimoto.andcode.core.diagnostics.supportingDetail
import com.yugahashimoto.andcode.ui.theme.AppShapes
import com.yugahashimoto.andcode.ui.theme.Spacing

@Composable
internal fun LoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
    }
}

@Composable
internal fun RuntimeSetupRequiredState(
    onOpenLocalSetup: () -> Unit,
    onOpenRemoteSetup: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        OpenCodeMark()
        Spacer(Modifier.height(22.dp))
        Text(
            text = stringResource(R.string.runtime_setup_required_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.runtime_setup_required_body_compact),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onOpenLocalSetup,
            modifier = Modifier.widthIn(max = 300.dp).fillMaxWidth(),
        ) {
            Text(stringResource(R.string.setup_this_android_action))
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = onOpenRemoteSetup,
            modifier = Modifier.widthIn(max = 300.dp).fillMaxWidth(),
        ) {
            Text(stringResource(R.string.connect_pc_mac_action))
        }
    }
}

@Composable
private fun OpenCodeMark() {
    Surface(
        modifier = Modifier.size(52.dp),
        shape = AppShapes.lg,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Default.Terminal,
                contentDescription = stringResource(R.string.cd_app_logo),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(27.dp),
            )
        }
    }
}

@Composable
internal fun ChatErrorCard(
    error: String,
    kind: ChatErrorKind,
    onOpenLocalSetup: () -> Unit,
    onOpenRemoteSetup: () -> Unit,
    onOpenProviderSettings: (() -> Unit)?,
) {
    Surface(
        shape = AppShapes.lg,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            if (kind == ChatErrorKind.ZEN_FREE_TIER) {
                Text(
                    text = stringResource(R.string.chat_error_zen_free_tier_title),
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.chat_error_zen_free_tier_body),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (onOpenProviderSettings != null) {
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onOpenProviderSettings) {
                        Text(stringResource(R.string.chat_error_zen_free_tier_action))
                    }
                }
            } else if (kind == ChatErrorKind.RUNTIME_NOT_READY) {
                Text(
                    text = stringResource(R.string.runtime_setup_required_title),
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.runtime_setup_required_body_compact),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Button(onClick = onOpenLocalSetup) {
                        Text(stringResource(R.string.setup_short_action))
                    }
                    OutlinedButton(onClick = onOpenRemoteSetup) {
                        Text(stringResource(R.string.connect_short_action))
                    }
                }
            } else if (kind == ChatErrorKind.TRANSIENT_CONNECTION) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        text = stringResource(R.string.chat_reconnecting),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(text = error, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/**
 * Says out loud that a run has stopped producing anything, and why the app thinks so.
 *
 * Without it a turn that died in the background is indistinguishable from one that is working: the
 * transcript just stops, under a spinner that never ends.
 */
@Composable
internal fun ChatStallCard(
    stall: StallDiagnosis,
    onRecheck: () -> Unit,
    onStop: () -> Unit,
) {
    val context = LocalContext.current
    // Rounded rather than truncated: a run 2 minutes 50 seconds quiet is nearer 3 than 2.
    val silentMinutes = ((stall.silentForMillis + 30_000L) / 60_000L).toInt().coerceAtLeast(1)
    // A turn with something visible left to wait for (a long tool call, an unanswered approval, a
    // dropped event stream) is reported in the neutral outline; a run that is over, unreachable or
    // producing nothing at all earns the error red.
    val accent =
        if (stall.isStopped) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier = Modifier.testTag("chat-stall-card"),
        shape = AppShapes.lg,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (stall.isStopped) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Icon(
                    imageVector = Icons.Default.WarningAmber,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(R.string.chat_stall_title),
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = pluralStringResource(R.plurals.chat_stall_silent_for, silentMinutes, silentMinutes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(text = stall.explain(context))
            stall.supportingDetail()?.let { detail ->
                Spacer(Modifier.height(4.dp))
                // Capped: a provider failure can arrive as a whole JSON error body.
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(12.dp))
            // Shared evenly rather than sized to their text: "Остановить выполнение" and its
            // Arabic counterpart do not fit beside another button on a narrow screen.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Button(onClick = onRecheck, modifier = Modifier.weight(1f).testTag("chat-stall-recheck")) {
                    Text(stringResource(R.string.chat_stall_recheck))
                }
                // Stopping goes through the runtime, so it is not offered when the runtime is the
                // thing that cannot be reached: the attempt would only fail and replace this card,
                // and its diagnosis, with a generic error.
                if (stall.reason != StallReason.RUNTIME_UNREACHABLE) {
                    OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f).testTag("chat-stall-stop")) {
                        Text(stringResource(R.string.chat_stall_stop))
                    }
                }
            }
        }
    }
}

/**
 * Tells the user why a run that is still going is not producing anything: the provider failed and
 * the runtime is trying again by itself. A rate-limited or overloaded model can keep this up for a
 * long time, and without the card it is indistinguishable from one that is thinking slowly.
 */
@Composable
internal fun ChatRetryCard(retry: RunRetry) {
    Surface(
        modifier = Modifier.testTag("chat-retry-card"),
        shape = AppShapes.lg,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Icon(
                    imageVector = Icons.Default.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(R.string.chat_retry_title),
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.chat_retry_attempt, retry.attempt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            retry.message?.let { message ->
                Spacer(Modifier.height(6.dp))
                // The provider's own words, capped: some return a whole JSON error body.
                Text(text = message, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.chat_retry_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
