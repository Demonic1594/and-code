package com.yugahashimoto.andcode.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.core.api.ContextBreakdown
import com.yugahashimoto.andcode.ui.theme.Spacing

/** The context window sheet the context meter opens. */
sealed interface ContextBreakdownState {
    data object Hidden : ContextBreakdownState

    data object Loading : ContextBreakdownState

    data class Shown(
        val breakdown: ContextBreakdown,
    ) : ContextBreakdownState

    /** The agent could not be asked: no process runs for the chat, or it did not answer. */
    data object Unavailable : ContextBreakdownState
}

/**
 * What the chat screen does for the context window sheet; the meter opens it only when [onOpen] is set,
 * and the sheet offers to compact the chat only when [onCompact] is.
 */
data class ContextBreakdownActions(
    val onOpen: (() -> Unit)? = null,
    val onDismiss: () -> Unit = {},
    val onManageMcp: (() -> Unit)? = null,
    val onCompact: (() -> Unit)? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContextBreakdownSheet(
    state: ContextBreakdownState,
    agentName: String,
    actions: ContextBreakdownActions,
) {
    if (state == ContextBreakdownState.Hidden) return
    ModalBottomSheet(onDismissRequest = actions.onDismiss) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.xxl)
                    .padding(bottom = Spacing.xxl)
                    .testTag("chat-context-breakdown"),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(
                stringResource(R.string.context_breakdown_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            when (state) {
                ContextBreakdownState.Loading ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.context_breakdown_loading, agentName), style = MaterialTheme.typography.bodyMedium)
                    }
                ContextBreakdownState.Unavailable ->
                    Text(
                        stringResource(R.string.context_breakdown_unavailable, agentName),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                is ContextBreakdownState.Shown -> {
                    BreakdownContent(state.breakdown, actions.onManageMcp)
                    actions.onCompact?.let { compact -> CompactAction(compact) }
                }
                ContextBreakdownState.Hidden -> Unit
            }
        }
    }
}

@Composable
private fun BreakdownContent(
    breakdown: ContextBreakdown,
    onManageMcp: (() -> Unit)?,
) {
    val colors = categoryColors(breakdown.categories)
    val percent = (breakdown.usedTokens * 100 / breakdown.maxTokens).toInt()
    Text(
        stringResource(
            R.string.context_breakdown_total,
            formatTokenCount(breakdown.usedTokens),
            formatTokenCount(breakdown.maxTokens),
            percent,
        ),
        style = MaterialTheme.typography.bodyLarge,
    )
    breakdown.autoCompactAt?.let {
        Text(
            stringResource(R.string.context_breakdown_auto_compact, formatTokenCount(it)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    WindowBar(breakdown.categories, colors)
    breakdown.categories.forEach { category ->
        TokenRow(
            label = categoryLabel(category),
            tokens = category.tokens,
            color = colors[category],
        )
    }
    if (breakdown.mcpServers.isNotEmpty()) {
        SectionTitle(stringResource(R.string.context_breakdown_mcp_title))
        breakdown.mcpServers.forEach { server ->
            TokenRow(
                label = server.name,
                detail = stringResource(R.string.context_breakdown_mcp_tools, server.tools),
                tokens = server.tokens,
            )
        }
        onManageMcp?.let { manage ->
            TextButton(onClick = manage) { Text(stringResource(R.string.context_breakdown_manage_mcp)) }
        }
    }
    if (breakdown.memoryFiles.isNotEmpty()) {
        SectionTitle(stringResource(R.string.context_breakdown_memory_title))
        val largeAt = largeInstructionFileTokens(breakdown.maxTokens)
        breakdown.memoryFiles.forEach { file ->
            TokenRow(
                label = file.path,
                tokens = file.tokens,
                detail = stringResource(R.string.context_breakdown_memory_large).takeIf { file.tokens > largeAt },
            )
        }
    }
}

/**
 * The size, in tokens, past which Claude Code says one instruction file "will impact performance".
 * It warns only in its own terminal, which AndCode does not show, so the sheet says it instead.
 * Claude Code 2.1.319 allows the larger of 40,000 characters and 5% of the window, in characters,
 * per file; at its 3 to 4 characters a token, that is about the larger of 10,000 tokens and a
 * twentieth of the window.
 */
internal fun largeInstructionFileTokens(windowTokens: Long): Long = maxOf(10_000L, windowTokens / 20)

/** The window as one bar: what fills it, then what is kept for compaction, then what is free. */
@Composable
private fun WindowBar(
    categories: List<ContextBreakdown.Category>,
    colors: Map<ContextBreakdown.Category, Color>,
) {
    val segments = categories.filter { it.kind != ContextBreakdown.Kind.DEFERRED && it.tokens > 0 }
    if (segments.isEmpty()) return
    // Drawn only: every segment is also a labelled row below, which is what a screen reader reads.
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .clearAndSetSemantics {},
    ) {
        segments.forEach { category ->
            Box(
                Modifier
                    .weight(category.tokens.toFloat())
                    .height(10.dp)
                    .background(colors[category] ?: MaterialTheme.colorScheme.surfaceVariant),
            )
        }
    }
}

/** Compacting on request: what it does, then the button. */
@Composable
private fun CompactAction(onCompact: () -> Unit) {
    Text(
        stringResource(R.string.context_breakdown_compact_caption),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.sm),
    )
    OutlinedButton(onClick = onCompact, modifier = Modifier.testTag("chat-context-compact")) {
        Text(stringResource(R.string.context_breakdown_compact))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = Spacing.sm).semantics { heading() },
    )
}

@Composable
private fun TokenRow(
    label: String,
    tokens: Long,
    color: Color? = null,
    detail: String? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        if (color != null) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            detail?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            formatTokenCount(tokens),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A colour per category: the used ones from the theme's accents in turn; the rest muted. */
@Composable
private fun categoryColors(categories: List<ContextBreakdown.Category>): Map<ContextBreakdown.Category, Color> {
    val scheme = MaterialTheme.colorScheme
    // No error colour: an ordinary category must not read as a warning.
    val accents =
        listOf(
            scheme.primary,
            scheme.tertiary,
            scheme.secondary,
            scheme.outline,
            scheme.primary.copy(alpha = 0.55f),
            scheme.tertiary.copy(alpha = 0.55f),
        )
    var next = 0
    return categories.associateWith { category ->
        when (category.kind) {
            ContextBreakdown.Kind.USED -> accents[next++ % accents.size]
            ContextBreakdown.Kind.DEFERRED -> scheme.outlineVariant
            ContextBreakdown.Kind.BUFFER -> scheme.outlineVariant
            ContextBreakdown.Kind.FREE -> scheme.surfaceVariant
        }
    }
}

@Composable
private fun categoryLabel(category: ContextBreakdown.Category): String =
    when (category.id) {
        "system_prompt" -> stringResource(R.string.context_category_system_prompt)
        "system_tools" -> stringResource(R.string.context_category_system_tools)
        "system_tools_deferred" -> stringResource(R.string.context_category_system_tools_deferred)
        "mcp_tools" -> stringResource(R.string.context_category_mcp_tools)
        "mcp_tools_deferred" -> stringResource(R.string.context_category_mcp_tools_deferred)
        "mcp_instructions" -> stringResource(R.string.context_category_mcp_instructions)
        "custom_agents" -> stringResource(R.string.context_category_custom_agents)
        "memory_files" -> stringResource(R.string.context_category_memory_files)
        "skills" -> stringResource(R.string.context_category_skills)
        "messages" -> stringResource(R.string.context_category_messages)
        "in_use" -> stringResource(R.string.context_category_in_use)
        "autocompact_buffer", "compact_buffer" -> stringResource(R.string.context_category_compact_buffer)
        "free_space" -> stringResource(R.string.context_category_free_space)
        else -> category.name.ifBlank { category.id.orEmpty() }
    }
