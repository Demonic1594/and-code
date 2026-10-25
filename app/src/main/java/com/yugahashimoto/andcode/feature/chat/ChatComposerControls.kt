package com.yugahashimoto.andcode.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.core.api.OpenCodeAgent
import com.yugahashimoto.andcode.core.api.PromptAttachment
import com.yugahashimoto.andcode.core.api.defaultAgentName
import com.yugahashimoto.andcode.core.api.selectablePrimaryAgents
import com.yugahashimoto.andcode.runtime.local.SystemPromptPreset
import com.yugahashimoto.andcode.ui.components.systemPromptPresetLabel
import com.yugahashimoto.andcode.ui.theme.AppShapes
import com.yugahashimoto.andcode.ui.theme.Spacing

@Composable
internal fun ThinkingChip(
    options: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            modifier =
                Modifier
                    .clip(AppShapes.pill)
                    .clickable(onClick = { expanded = true }),
            shape = AppShapes.pill,
            color =
                if (selected != null) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            contentColor =
                if (selected != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Icon(Icons.Default.Psychology, contentDescription = stringResource(R.string.cd_thinking), modifier = Modifier.size(14.dp))
                Text(
                    selected?.let { thinkingLevelLabel(it) } ?: stringResource(R.string.chat_thinking_default),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_thinking_default)) },
                onClick = {
                    onSelect(null)
                    expanded = false
                },
            )
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(thinkingLevelLabel(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

/**
 * Turns Codex's fast service tier on or off for the turns that follow.
 *
 * A plain on/off pill in the same tokens as the thinking and auto-accept chips, so it reads as one
 * more setting on the row rather than a new kind of control. It carries switch semantics rather
 * than a click, so a screen reader also says whether it is on. The server's own wording for the
 * tier ("1.5x speed, increased usage") goes to the screen reader: a phone-width row has no room
 * for it.
 */
@Composable
internal fun FastModeChip(
    enabled: Boolean,
    description: String?,
    onToggle: (Boolean) -> Unit,
) {
    val containerColor =
        if (enabled) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
    val contentColor =
        if (enabled) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    Surface(
        modifier =
            Modifier
                .clip(AppShapes.pill)
                .toggleable(value = enabled, role = Role.Switch, onValueChange = onToggle)
                .testTag("chat-fast-mode"),
        shape = AppShapes.pill,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Icon(
                Icons.Default.Bolt,
                contentDescription =
                    description
                        ?.takeIf { it.isNotBlank() }
                        ?.let { stringResource(R.string.cd_fast_mode_with_description, it) }
                        ?: stringResource(R.string.cd_fast_mode),
                modifier = Modifier.size(14.dp),
            )
            Text(
                stringResource(R.string.chat_fast_mode),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
        }
    }
}

/**
 * Switches the system-prompt preset this chat sends with, without leaving it.
 *
 * Per chat, not per turn: [com.yugahashimoto.andcode.runtime.local.ClaudeCodeTarget] keeps one
 * preset id on the session and reads it for every turn, so a switch holds for the rest of the
 * conversation until it is switched again - the same scope as the thinking and permission chips it
 * sits beside. Settings still manages the presets themselves (adding, editing, deleting); picking
 * one for the chat in front of you is a one-tap move from here.
 */
@Composable
internal fun SystemPromptChip(
    presets: List<SystemPromptPreset>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = presets.firstOrNull { it.id == selectedId }
    Box {
        Surface(
            modifier =
                Modifier
                    .clip(AppShapes.pill)
                    .clickable(onClick = { expanded = true }),
            shape = AppShapes.pill,
            color =
                if (selected != null) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            contentColor =
                if (selected != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Icon(Icons.Default.Tune, contentDescription = stringResource(R.string.cd_system_prompt), modifier = Modifier.size(14.dp))
                Text(
                    // A preset name is free text, so it is capped rather than allowed to push the
                    // context meter off the row.
                    text = selected?.let { systemPromptPresetLabel(it) } ?: stringResource(R.string.chat_system_prompt_default),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 96.dp),
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.system_prompt_none)) },
                onClick = {
                    onSelect(null)
                    expanded = false
                },
            )
            presets.forEach { preset ->
                DropdownMenuItem(
                    text = { Text(systemPromptPresetLabel(preset)) },
                    onClick = {
                        onSelect(preset.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

/**
 * A model variant's display label for the thinking chip.
 *
 * Claude Code, Antigravity and Codex all offer reasoning-effort slugs ("low", "medium", "high",
 * plus Claude Code's and Codex's "xhigh" and "max"); those get their proper names ("Extra High",
 * "Max") instead of the raw slug, which used to make the CLI's highest level ("max") look like just
 * another word next to "xhigh" rather than the new top tier it is. Codex adds "ultra" above "max"
 * and "minimal" and "none" below "low"; "none" reads "Off", since the chip then names thinking
 * that is switched off, and a bare "None" would look like no choice was made. Any other provider's
 * variant id - OpenCode's own models offer different ones - falls back to capitalizing the raw
 * value, as before.
 */
@Composable
private fun thinkingLevelLabel(level: String): String =
    when (level.lowercase()) {
        "none" -> stringResource(R.string.chat_thinking_level_none)
        "minimal" -> stringResource(R.string.chat_thinking_level_minimal)
        "low" -> stringResource(R.string.chat_thinking_level_low)
        "medium" -> stringResource(R.string.chat_thinking_level_medium)
        "high" -> stringResource(R.string.chat_thinking_level_high)
        "xhigh" -> stringResource(R.string.chat_thinking_level_xhigh)
        "max" -> stringResource(R.string.chat_thinking_level_max)
        "ultra" -> stringResource(R.string.chat_thinking_level_ultra)
        else -> level.replaceFirstChar { it.uppercase() }
    }

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
internal fun ModeChip(
    agents: List<OpenCodeAgent>,
    selectedAgentId: String?,
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit = {},
    loading: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    // Every primary agent the server reports is offered, not just build/plan: plugins such as
    // oh-my-opencode-slim register their own primary (`orchestrator`), and hiding it left users
    // unable to reach it. `agents` is normally narrowed upstream already; filtering again keeps
    // subagents and OpenCode's internal compaction/title/summary agents out even if a caller
    // passes the raw list.
    val selectableAgents = agents.selectablePrimaryAgents()
    // The selection is remembered across runtimes, so an id this runtime does not offer would
    // otherwise label the chip with another agent's name. An empty list is not a licence to trust
    // it either: that is exactly the state a stopped runtime is in.
    val label =
        selectedAgentId?.takeIf { id -> selectableAgents.any { it.name == id } }
            ?: agents.defaultAgentName()
            ?: "build"
    Box {
        Surface(
            modifier =
                Modifier
                    .widthIn(max = 92.dp)
                    .clickable(
                        onClick = {
                            expanded = true
                            if (selectableAgents.isEmpty() && !loading) onRefresh()
                        },
                    )
                    .testTag("chat-mode-chip"),
            shape = AppShapes.pill,
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                if (loading && selectableAgents.isEmpty()) {
                    val loadingDescription = stringResource(R.string.chat_agents_loading)
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp).semantics { contentDescription = loadingDescription },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        strokeWidth = 1.5.dp,
                    )
                } else {
                    Icon(
                        Icons.Outlined.Shield,
                        contentDescription = stringResource(R.string.cd_agent_mode),
                        modifier = Modifier.size(14.dp),
                    )
                }
                Text(
                    label,
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
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (selectableAgents.isEmpty()) {
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (loading) R.string.chat_agents_loading else R.string.chat_agents_load_failed,
                            ),
                        )
                    },
                    onClick = onRefresh,
                    enabled = !loading,
                    modifier = if (loading) Modifier.testTag("chat-mode-loading") else Modifier,
                )
            }
            selectableAgents.forEach { agent ->
                DropdownMenuItem(
                    text = { Text(agent.name) },
                    onClick = {
                        onSelect(agent.name)
                        expanded = false
                    },
                    modifier = Modifier.testTag("chat-mode-${agent.name}"),
                )
            }
        }
    }
}

@Composable
internal fun PermissionModeChip(
    selectedLabel: Int,
    options: List<PermissionModeOption>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            modifier =
                Modifier
                    .clip(AppShapes.pill)
                    .clickable(onClick = { expanded = true })
                    .testTag("chat-permission-mode"),
            shape = AppShapes.pill,
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Icon(
                    Icons.Outlined.VerifiedUser,
                    contentDescription = stringResource(R.string.claude_permission_mode_label),
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    stringResource(selectedLabel),
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
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(option.labelRes)) },
                    onClick = {
                        onSelect(option.id)
                        expanded = false
                    },
                    modifier = Modifier.testTag("chat-permission-mode-${option.id}"),
                )
            }
        }
    }
}

/** One entry of the permission-mode chip, so the chip serves Claude Code and Antigravity alike. */
internal data class PermissionModeOption(val id: String, val labelRes: Int)

@Composable
internal fun AutoAcceptChip(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val containerColor =
        if (enabled) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
    val contentColor =
        if (enabled) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    Surface(
        modifier =
            Modifier
                .clip(AppShapes.pill)
                .clickable(onClick = { onToggle(!enabled) })
                .testTag("chat-auto-accept"),
        shape = AppShapes.pill,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Icon(
                if (enabled) Icons.Filled.VerifiedUser else Icons.Outlined.VerifiedUser,
                contentDescription = stringResource(R.string.chat_mode_auto_accept),
                modifier = Modifier.size(14.dp),
            )
            Text(
                stringResource(R.string.chat_auto_accept_short),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
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
