package com.yugahashimoto.andcode.feature.chat

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.core.api.OpenCodeAgent
import com.yugahashimoto.andcode.core.api.OpenCodeCommand
import com.yugahashimoto.andcode.core.api.OpenCodeSkill
import com.yugahashimoto.andcode.core.api.PromptAttachment
import com.yugahashimoto.andcode.core.api.selectablePrimaryAgents
import com.yugahashimoto.andcode.data.settings.SavedPrompt
import com.yugahashimoto.andcode.feature.workspace.GitHubAutoAttachChips
import com.yugahashimoto.andcode.feature.workspace.GitHubReference
import com.yugahashimoto.andcode.runtime.local.AntigravityPermissionMode
import com.yugahashimoto.andcode.runtime.local.ClaudePermissionMode
import com.yugahashimoto.andcode.runtime.local.SystemPromptPreset
import com.yugahashimoto.andcode.ui.components.VolumeMeter
import com.yugahashimoto.andcode.ui.theme.AppShapes
import com.yugahashimoto.andcode.ui.theme.Spacing

@Composable
internal fun ChatComposer(
    inputValue: TextFieldValue,
    onInputValueChange: (TextFieldValue) -> Unit,
    isRunning: Boolean,
    onSend: () -> Unit,
    onAbort: () -> Unit,
    onMic: () -> Unit,
    isListening: Boolean,
    isSpeechProcessing: Boolean,
    modelLabel: String,
    onModelChipClick: () -> Unit,
    agents: List<OpenCodeAgent>,
    selectedAgentId: String?,
    onSelectAgent: (String) -> Unit,
    onRefreshAgents: () -> Unit,
    agentsLoading: Boolean = false,
    thinkingOptions: List<String>,
    selectedVariant: String?,
    onSelectVariant: (String?) -> Unit,
    fastModeAvailable: Boolean,
    fastModeEnabled: Boolean,
    fastModeDescription: String?,
    onToggleFastMode: (Boolean) -> Unit,
    attachments: List<PromptAttachment>,
    onAttach: () -> Unit,
    onRemoveAttachment: (Int) -> Unit,
    autoAcceptPermissions: Boolean,
    supportsPermissions: Boolean,
    onToggleAutoAccept: (Boolean) -> Unit,
    claudePermissionMode: ClaudePermissionMode?,
    onSelectClaudePermissionMode: (ClaudePermissionMode) -> Unit,
    antigravityPermissionMode: AntigravityPermissionMode?,
    onSelectAntigravityPermissionMode: (AntigravityPermissionMode) -> Unit,
    systemPromptPresets: List<SystemPromptPreset>,
    selectedSystemPromptId: String?,
    onSelectSystemPrompt: (String?) -> Unit,
    sendBehavior: String,
    enterToSend: Boolean,
    contextTokensUsed: Long,
    contextLimit: Long,
    tokensPerSecond: Double,
    showSlashCommands: Boolean,
    slashCommands: List<OpenCodeCommand>,
    slashSkills: List<OpenCodeSkill>,
    savedPrompts: List<SavedPrompt>,
    onSlashCommandSelect: (SlashSuggestion) -> Unit,
    githubRefs: List<GitHubReference>,
    pullRequests: List<ChatPullRequest>,
    onOpenUrl: (String) -> Unit,
    onDismissPullRequest: (String) -> Unit,
    attachedImages: List<Bitmap>,
    onRemoveImage: (Int) -> Unit,
    onCameraLaunch: () -> Unit,
    onGalleryLaunch: () -> Unit,
) {
    val input = inputValue.text
    val voiceActive = isListening || isSpeechProcessing
    var showAttachMenu by remember { mutableStateOf(false) }
    var showRunSettings by remember { mutableStateOf(false) }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 10.dp, vertical = Spacing.sm),
    ) {
        if (showSlashCommands) {
            val filtered = SlashCommandRegistry.suggestions(input, slashCommands, slashSkills, savedPrompts)
            if (filtered.isNotEmpty()) {
                Card(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = Spacing.xs),
                    shape = AppShapes.md,
                ) {
                    Column(modifier = Modifier.padding(vertical = Spacing.xs)) {
                        filtered.forEach { suggestion ->
                            val description =
                                suggestion.descriptionRes?.let { stringResource(it) }
                                    ?: suggestion.description
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = suggestion.name,
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Text(
                                            text = description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                onClick = { onSlashCommandSelect(suggestion) },
                            )
                        }
                    }
                }
            }
        }

        PullRequestLinkBar(
            pullRequests = pullRequests,
            onOpenUrl = onOpenUrl,
            onDismiss = onDismissPullRequest,
            modifier = Modifier.padding(bottom = 6.dp),
        )

        GitHubAutoAttachChips(
            references = githubRefs,
            onAttach = {},
            modifier = Modifier.padding(bottom = Spacing.xs),
        )

        if (attachedImages.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.padding(bottom = Spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(attachedImages) { index, bitmap ->
                    Box {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = stringResource(R.string.cd_attached_image),
                            modifier =
                                Modifier
                                    .size(56.dp)
                                    .clip(AppShapes.sm),
                            contentScale = ContentScale.Crop,
                        )
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.cd_remove_image),
                            modifier =
                                Modifier
                                    .size(18.dp)
                                    .align(Alignment.TopEnd)
                                    .clickable { onRemoveImage(index) },
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = Spacing.sm)) {
                if (attachments.isNotEmpty()) {
                    AttachmentTray(attachments = attachments, onRemove = onRemoveAttachment)
                    Spacer(Modifier.height(6.dp))
                }
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.xs, vertical = Spacing.xs),
                ) {
                    if (input.isEmpty()) {
                        Text(
                            text = stringResource(R.string.chat_message_placeholder),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    BasicTextField(
                        value = inputValue,
                        onValueChange = onInputValueChange,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .testTag("chat-message-input"),
                        minLines = 1,
                        maxLines = 4,
                        textStyle =
                            TextStyle(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                                lineHeight = MaterialTheme.typography.bodyLarge.lineHeight,
                                // Follow the typed content's direction so Arabic (RTL) input lays
                                // out correctly even when the app language is LTR (issue #341).
                                textDirection = TextDirection.Content,
                            ),
                        keyboardOptions =
                            KeyboardOptions(
                                imeAction =
                                    if (enterToSend) ImeAction.Send else ImeAction.Default,
                            ),
                        keyboardActions =
                            KeyboardActions(
                                onSend = {
                                    if (enterToSend) {
                                        onSend()
                                    }
                                },
                            ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    )
                }
                if (isListening || isSpeechProcessing) {
                    Spacer(Modifier.height(8.dp))
                    if (isListening) {
                        ListeningStatus(modifier = Modifier.fillMaxWidth())
                    } else {
                        ProcessingStatus(modifier = Modifier.fillMaxWidth())
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    Box {
                        AttachButton(onClick = { showAttachMenu = true })
                        DropdownMenu(
                            expanded = showAttachMenu,
                            onDismissRequest = { showAttachMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.attach_file)) },
                                leadingIcon = {
                                    Icon(
                                        Icons.AutoMirrored.Filled.InsertDriveFile,
                                        contentDescription = stringResource(R.string.cd_attach_file),
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                                onClick = {
                                    showAttachMenu = false
                                    onAttach()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.attach_camera)) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.CameraAlt,
                                        contentDescription = stringResource(R.string.cd_camera),
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                                onClick = {
                                    showAttachMenu = false
                                    onCameraLaunch()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.attach_gallery)) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.PhotoLibrary,
                                        contentDescription = stringResource(R.string.cd_gallery),
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                                onClick = {
                                    showAttachMenu = false
                                    onGalleryLaunch()
                                },
                            )
                        }
                    }
                    CompactContextButton(
                        label = modelLabel,
                        maxWidth = 168.dp,
                        onClick = onModelChipClick,
                    )
                    Spacer(Modifier.weight(1f))
                    if (isListening) {
                        VolumeMeter(amplitude = 0.5f, idle = true)
                    }
                    val micContainerColor =
                        if (voiceActive) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    val micContentColor =
                        if (voiceActive) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    Surface(
                        modifier =
                            Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(19.dp))
                                .testTag("chat-mic-button"),
                        shape = RoundedCornerShape(19.dp),
                        color = micContainerColor,
                    ) {
                        IconButton(onClick = onMic, modifier = Modifier.fillMaxSize()) {
                            Icon(
                                if (voiceActive) Icons.Default.Stop else Icons.Default.Mic,
                                contentDescription =
                                    stringResource(if (voiceActive) R.string.stop_run else R.string.voice),
                                modifier = Modifier.size(21.dp),
                                tint = micContentColor,
                            )
                        }
                    }
                    if (isRunning) {
                        if (sendBehavior == "queue") {
                            Surface(
                                shape = AppShapes.pill,
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            ) {
                                Text(
                                    text = stringResource(R.string.status_queued),
                                    modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                        FilledIconButton(onClick = onAbort, modifier = Modifier.size(38.dp)) {
                            Icon(Icons.Default.Stop, contentDescription = stringResource(R.string.stop_run))
                        }
                    } else {
                        FilledIconButton(
                            onClick = onSend,
                            enabled = input.isNotBlank() || attachments.isNotEmpty(),
                            modifier = Modifier.size(38.dp),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = stringResource(R.string.send_description),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        val runSettings =
            RunSettings(
                agents = agents,
                selectedAgentId = selectedAgentId,
                agentsLoading = agentsLoading,
                permissionMode =
                    when {
                        claudePermissionMode != null ->
                            PermissionModeSelection(
                                selectedId = claudePermissionMode.cliValue,
                                selectedLabelRes = claudePermissionMode.labelRes,
                                options = ClaudePermissionMode.entries.map { PermissionModeOption(it.cliValue, it.labelRes) },
                                onSelect = { id -> onSelectClaudePermissionMode(ClaudePermissionMode.fromCliValue(id)) },
                            )
                        // Antigravity's modes were once offered through the agent chip, where the
                        // globally remembered agent id overrode the mode settings chose; the choice
                        // now comes from - and goes back to - that same setting.
                        antigravityPermissionMode != null ->
                            PermissionModeSelection(
                                selectedId = antigravityPermissionMode.cliValue,
                                selectedLabelRes = antigravityPermissionMode.labelRes,
                                options = AntigravityPermissionMode.entries.map { PermissionModeOption(it.cliValue, it.labelRes) },
                                onSelect = { id -> onSelectAntigravityPermissionMode(AntigravityPermissionMode.fromCliValue(id)) },
                            )
                        else -> null
                    },
                supportsAutoAccept = supportsPermissions,
                autoAccept = autoAcceptPermissions,
                thinkingOptions = thinkingOptions,
                selectedThinking = selectedVariant,
                fastModeAvailable = fastModeAvailable,
                fastModeEnabled = fastModeEnabled,
                fastModeDescription = fastModeDescription,
                systemPromptPresets = systemPromptPresets,
                selectedSystemPromptId = selectedSystemPromptId,
            )
        if (showRunSettings) {
            RunSettingsSheet(
                settings = runSettings,
                actions =
                    RunSettingsActions(
                        onSelectAgent = onSelectAgent,
                        onRefreshAgents = onRefreshAgents,
                        onToggleAutoAccept = onToggleAutoAccept,
                        onSelectThinking = onSelectVariant,
                        onToggleFastMode = onToggleFastMode,
                        onSelectSystemPrompt = onSelectSystemPrompt,
                    ),
                onDismiss = { showRunSettings = false },
            )
        }
        Row(
            // Still scrollable: the run-settings chip names the mode and thinking level, and with
            // the context meter and token speed beside it a narrow phone can run out of width.
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            RunSettingsChip(
                settings = runSettings,
                onClick = {
                    showRunSettings = true
                    // Opening the sheet on an OpenCode runtime whose agents never loaded retries
                    // the load, as tapping the old mode chip did.
                    if (runSettings.permissionMode == null && agents.selectablePrimaryAgents().isEmpty() && !agentsLoading) onRefreshAgents()
                },
            )
            if (contextLimit > 0L) {
                CompactContextMeter(
                    tokensUsed = contextTokensUsed,
                    contextLimit = contextLimit,
                )
            }
            if (isRunning && tokensPerSecond >= 0.05) {
                CompactTokenSpeed(tokensPerSecond)
            }
        }
    }
}

@Composable
private fun AttachButton(onClick: () -> Unit) {
    Surface(
        modifier =
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(17.dp))
                .testTag("chat-attach-button"),
        shape = RoundedCornerShape(17.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        IconButton(onClick = onClick, modifier = Modifier.fillMaxSize()) {
            Icon(
                Icons.Default.Add,
                contentDescription = stringResource(R.string.chat_attach),
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
