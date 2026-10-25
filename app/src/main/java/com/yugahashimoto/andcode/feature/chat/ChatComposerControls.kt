package com.yugahashimoto.andcode.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.core.api.PromptAttachment
import com.yugahashimoto.andcode.ui.theme.AppShapes
import com.yugahashimoto.andcode.ui.theme.Spacing

@Composable
internal fun AttachmentTray(
    attachments: List<PromptAttachment>,
    onRemove: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        attachments.forEachIndexed { index, attachment ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.InsertDriveFile,
                        contentDescription = stringResource(R.string.cd_attachment),
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        attachment.filename,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 120.dp),
                    )
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.chat_remove_attachment),
                        modifier =
                            Modifier
                                .size(14.dp)
                                .clickable { onRemove(index) },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
internal fun ListeningStatus(modifier: Modifier = Modifier) {
    Row(
        modifier =
            modifier
                .background(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                    shape = AppShapes.lg,
                )
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text(
            text = stringResource(R.string.voice_state_listening),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
internal fun ProcessingStatus(modifier: Modifier = Modifier) {
    Row(
        modifier =
            modifier
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    shape = AppShapes.lg,
                )
                .padding(horizontal = Spacing.md, vertical = Spacing.sm)
                .testTag("chat-voice-processing"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
        )
        Text(
            text = stringResource(R.string.processing),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun CompactContextButton(
    label: String,
    maxWidth: androidx.compose.ui.unit.Dp,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        modifier =
            Modifier
                .widthIn(max = maxWidth)
                .clickable(enabled = enabled, onClick = onClick),
        shape = AppShapes.pill,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 1f else 0.55f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.hairline),
        ) {
            Text(
                text = label,
                modifier = Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = stringResource(R.string.cd_expand_dropdown),
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
internal fun CompactContextMeter(
    tokensUsed: Long,
    contextLimit: Long,
) {
    // Session token totals can exceed the model window; the meter represents window usage.
    val displayedTokens = tokensUsed.coerceIn(0L, contextLimit)
    val fraction = displayedTokens.toFloat() / contextLimit.toFloat()
    val barColor =
        when {
            fraction >= 0.9f -> MaterialTheme.colorScheme.error
            fraction >= 0.7f -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.primary
        }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.hairline),
        modifier = Modifier.padding(horizontal = Spacing.hairline),
    ) {
        LinearProgressIndicator(
            progress = { fraction },
            modifier =
                Modifier
                    .width(34.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp)),
            color = barColor,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            // Material3 defaults round the bar's cap, gap it from the track and draw a stop dot at
            // the far end. At this size that reads as two dots instead of one bar filling L to R.
            strokeCap = StrokeCap.Butt,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
        Text(
            text = "${formatTokenCount(displayedTokens)}/${formatTokenCount(contextLimit)}",
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatTokenCount(tokens: Long): String =
    when {
        tokens >= 1_000_000 -> "%.1fM".format(tokens / 1_000_000.0)
        tokens >= 1_000 -> "%.0fk".format(tokens / 1_000.0)
        else -> tokens.toString()
    }

@Composable
internal fun CompactTokenSpeed(tokensPerSecond: Double) {
    Text(
        text = stringResource(R.string.chat_token_speed, formatTokenRate(tokensPerSecond)),
        style = MaterialTheme.typography.labelSmall,
        fontSize = 9.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.hairline),
    )
}

private fun formatTokenRate(tokensPerSecond: Double): String =
    if (tokensPerSecond >= 10.0) "%.0f".format(tokensPerSecond) else "%.1f".format(tokensPerSecond)
