package com.yugahashimoto.andcode.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.core.api.OpenCodeAgent
import com.yugahashimoto.andcode.core.api.defaultAgentName
import com.yugahashimoto.andcode.core.api.selectablePrimaryAgents
import com.yugahashimoto.andcode.runtime.local.SystemPromptPreset
import com.yugahashimoto.andcode.ui.components.systemPromptPresetLabel
import com.yugahashimoto.andcode.ui.theme.AppShapes
import com.yugahashimoto.andcode.ui.theme.Spacing

/** One entry of the permission-mode section, so the sheet serves Claude Code and Antigravity alike. */
internal data class PermissionModeOption(val id: String, val labelRes: Int)

/**
 * Everything that decides how the next turn runs, in one place.
 *
 * These used to be a row of up to six chips under the composer (mode or permission mode,
 * auto-accept, thinking, fast mode, system prompt), which no longer fit a phone and had to scroll
 * sideways. They now sit in one sheet behind [RunSettingsChip]. Each section only appears when the
 * runtime offers it, and every change applies at once, the same as the chips did, so the sheet
 * stays open to change several things in a row.
 *
 * Exactly one of the mode sections shows: Claude Code and Antigravity have a single agent and
 * decide permissions per session, so they get a permission mode, while OpenCode picks a primary
 * agent and can auto-accept permission prompts.
 */
internal class RunSettings(
    val agents: List<OpenCodeAgent>,
    val selectedAgentId: String?,
    val agentsLoading: Boolean,
    val permissionMode: PermissionModeSelection?,
    val supportsAutoAccept: Boolean,
    val autoAccept: Boolean,
    val thinkingOptions: List<String>,
    val selectedThinking: String?,
    val fastModeAvailable: Boolean,
    val fastModeEnabled: Boolean,
    val fastModeDescription: String?,
    val systemPromptPresets: List<SystemPromptPreset>,
    val selectedSystemPromptId: String?,
) {
    /** True when anything differs from what a fresh chat would run with, so the chip can say so. */
    val hasOverrides: Boolean
        get() =
            selectedThinking != null ||
                (fastModeAvailable && fastModeEnabled) ||
                (supportsAutoAccept && permissionMode == null && autoAccept) ||
                systemPromptPresets.any { it.id == selectedSystemPromptId }
}

/** The permission mode a Claude Code or Antigravity chat runs in, and the modes it may switch to. */
internal class PermissionModeSelection(
    val selectedId: String,
    val selectedLabelRes: Int,
    val options: List<PermissionModeOption>,
    val onSelect: (String) -> Unit,
)

internal class RunSettingsActions(
    val onSelectAgent: (String) -> Unit,
    val onRefreshAgents: () -> Unit,
    val onToggleAutoAccept: (Boolean) -> Unit,
    val onSelectThinking: (String?) -> Unit,
    val onToggleFastMode: (Boolean) -> Unit,
    val onSelectSystemPrompt: (String?) -> Unit,
)

/**
 * The agent name the mode section and chip show. The selection is remembered across runtimes, so
 * an id this runtime does not offer would otherwise label the chip with another agent's name. An
 * empty list is not a licence to trust it either: that is exactly the state a stopped runtime is in.
 */
private fun RunSettings.agentLabel(): String =
    selectedAgentId?.takeIf { id -> agents.selectablePrimaryAgents().any { it.name == id } }
        ?: agents.defaultAgentName()
        ?: "build"

/** The pill under the composer that names the current mode and thinking level and opens the sheet. */
@Composable
internal fun RunSettingsChip(
    settings: RunSettings,
    onClick: () -> Unit,
) {
    val modeLabel = settings.permissionMode?.let { stringResource(it.selectedLabelRes) } ?: settings.agentLabel()
    val summary =
        buildList {
            add(modeLabel)
            settings.selectedThinking?.let { add(thinkingLevelLabel(it)) }
            if (settings.fastModeAvailable && settings.fastModeEnabled) add(stringResource(R.string.chat_fast_mode))
        }.joinToString(" · ")
    val highlighted = settings.hasOverrides
    Surface(
        modifier =
            Modifier
                .clip(AppShapes.pill)
                .clickable(onClick = onClick)
                .testTag("chat-run-settings"),
        shape = AppShapes.pill,
        color = if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            if (settings.agentsLoading && settings.permissionMode == null && settings.agents.selectablePrimaryAgents().isEmpty()) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    strokeWidth = 1.5.dp,
                )
            } else {
                Icon(
                    Icons.Default.Tune,
                    contentDescription = stringResource(R.string.cd_run_settings),
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                summary,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 200.dp),
            )
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RunSettingsSheet(
    settings: RunSettings,
    actions: RunSettingsActions,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = Spacing.screenEdge)
                    .padding(bottom = Spacing.xxl)
                    .testTag("chat-run-settings-sheet"),
        ) {
            Text(
                stringResource(R.string.run_settings_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(Spacing.sm))

            val permissionMode = settings.permissionMode
            if (permissionMode != null) {
                SectionHeader(stringResource(R.string.claude_permission_mode_label))
                Column(Modifier.selectableGroup()) {
                    permissionMode.options.forEach { option ->
                        ChoiceRow(
                            label = stringResource(option.labelRes),
                            selected = option.id == permissionMode.selectedId,
                            onClick = { permissionMode.onSelect(option.id) },
                            testTag = "chat-permission-mode-${option.id}",
                        )
                    }
                }
            } else {
                AgentSection(settings, actions)
                // Auto-accept answers permission prompts, so it only means something on a runtime
                // that asks for permission. Antigravity decides permissions per invocation from its
                // mode flag and never raises a prompt, which made this a switch that did nothing.
                if (settings.supportsAutoAccept) {
                    ToggleRow(
                        label = stringResource(R.string.chat_mode_auto_accept),
                        description = stringResource(R.string.run_settings_auto_accept_caption),
                        checked = settings.autoAccept,
                        onCheckedChange = actions.onToggleAutoAccept,
                        testTag = "chat-auto-accept",
                    )
                }
            }

            if (settings.thinkingOptions.isNotEmpty()) {
                SectionDivider()
                SectionHeader(stringResource(R.string.cd_thinking))
                Column(Modifier.selectableGroup()) {
                    ChoiceRow(
                        label = stringResource(R.string.run_settings_model_default),
                        selected = settings.selectedThinking == null,
                        onClick = { actions.onSelectThinking(null) },
                    )
                    settings.thinkingOptions.forEach { option ->
                        ChoiceRow(
                            label = thinkingLevelLabel(option),
                            selected = settings.selectedThinking == option,
                            onClick = { actions.onSelectThinking(option) },
                        )
                    }
                }
            }

            // Right after thinking: both decide how the model runs the next turn. Only a Codex model
            // with a fast tier offers it, and the sheet finally has room for the server's own
            // description of the tier ("1.5x speed, increased usage"), which the chip had to hide.
            if (settings.fastModeAvailable) {
                SectionDivider()
                ToggleRow(
                    label = stringResource(R.string.cd_fast_mode),
                    description = settings.fastModeDescription?.takeIf { it.isNotBlank() },
                    checked = settings.fastModeEnabled,
                    onCheckedChange = actions.onToggleFastMode,
                    testTag = "chat-fast-mode",
                )
            }

            // Per chat, not per turn: ClaudeCodeTarget keeps one preset id on the session and reads
            // it for every turn, so a switch holds until it is switched again. Settings still
            // manages the presets themselves; picking one for this chat is a tap from here.
            if (settings.systemPromptPresets.isNotEmpty()) {
                SectionDivider()
                SectionHeader(stringResource(R.string.cd_system_prompt))
                Column(Modifier.selectableGroup()) {
                    ChoiceRow(
                        label = stringResource(R.string.system_prompt_none),
                        selected = settings.systemPromptPresets.none { it.id == settings.selectedSystemPromptId },
                        onClick = { actions.onSelectSystemPrompt(null) },
                    )
                    settings.systemPromptPresets.forEach { preset ->
                        ChoiceRow(
                            label = systemPromptPresetLabel(preset),
                            selected = preset.id == settings.selectedSystemPromptId,
                            onClick = { actions.onSelectSystemPrompt(preset.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentSection(
    settings: RunSettings,
    actions: RunSettingsActions,
) {
    // Every primary agent the server reports is offered, not just build/plan: plugins such as
    // oh-my-opencode-slim register their own primary (`orchestrator`), and hiding it left users
    // unable to reach it. `agents` is normally narrowed upstream already; filtering again keeps
    // subagents and OpenCode's internal compaction/title/summary agents out even if a caller
    // passes the raw list.
    val selectableAgents = settings.agents.selectablePrimaryAgents()
    SectionHeader(stringResource(R.string.cd_agent_mode))
    if (selectableAgents.isEmpty()) {
        if (settings.agentsLoading) {
            Row(
                modifier = Modifier.padding(vertical = Spacing.md).testTag("chat-mode-loading"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.chat_agents_loading), style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            TextButton(onClick = actions.onRefreshAgents) {
                Text(stringResource(R.string.chat_agents_load_failed))
            }
        }
        return
    }
    val selected = settings.agentLabel()
    Column(Modifier.selectableGroup()) {
        selectableAgents.forEach { agent ->
            ChoiceRow(
                label = agent.name,
                selected = agent.name == selected,
                onClick = { actions.onSelectAgent(agent.name) },
                testTag = "chat-mode-${agent.name}",
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.xs),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.sm))
}

@Composable
private fun ChoiceRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String? = null,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(AppShapes.sm)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
                .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ToggleRow(
    label: String,
    description: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(AppShapes.sm)
                .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
                .testTag(testTag)
                .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * A model variant's display label for the thinking section and the chip summary.
 *
 * Claude Code, Antigravity and Codex all offer reasoning-effort slugs ("low", "medium", "high",
 * plus Claude Code's and Codex's "xhigh" and "max"); those get their proper names ("Extra High",
 * "Max") instead of the raw slug, which used to make the CLI's highest level ("max") look like just
 * another word next to "xhigh" rather than the new top tier it is. Codex adds "ultra" above "max"
 * and "minimal" and "none" below "low"; "none" reads "Off", since the label then names thinking
 * that is switched off, and a bare "None" would look like no choice was made. Any other provider's
 * variant id - OpenCode's own models offer different ones - falls back to capitalizing the raw
 * value, as before.
 */
@Composable
internal fun thinkingLevelLabel(level: String): String =
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
