package com.yugahashimoto.andcode.feature.usage

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.feature.chat.formatUsageTokens
import com.yugahashimoto.andcode.feature.chat.formatUsd
import com.yugahashimoto.andcode.runtime.LocalAgent
import com.yugahashimoto.andcode.ui.components.ProviderIcon
import com.yugahashimoto.andcode.ui.components.SectionCard
import com.yugahashimoto.andcode.ui.theme.CodeFontFamily
import com.yugahashimoto.andcode.ui.theme.Spacing
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

/** The parts of a token count, in the order the charts stack them. */
private enum class TokenKind(
    val label: Int,
) {
    INPUT(R.string.usage_kind_input),
    OUTPUT(R.string.usage_kind_output),
    REASONING(R.string.usage_kind_reasoning),
    CACHE_READ(R.string.usage_kind_cache_read),
    CACHE_WRITE(R.string.usage_kind_cache_write),
    ;

    fun of(totals: UsageTotals): Long =
        when (this) {
            INPUT -> totals.input
            OUTPUT -> totals.output
            REASONING -> totals.reasoning
            CACHE_READ -> totals.cacheRead
            CACHE_WRITE -> totals.cacheWrite
        }
}

@Composable
private fun kindColor(kind: TokenKind): Color {
    val scheme = MaterialTheme.colorScheme
    return when (kind) {
        TokenKind.INPUT -> scheme.primary
        TokenKind.OUTPUT -> scheme.tertiary
        // Secondary and tertiary sit too close in this theme; reasoning needs its own hue.
        TokenKind.REASONING -> if (scheme.surface.luminance() < 0.5f) Color(0xFFE3B341) else Color(0xFFB7791F)
        TokenKind.CACHE_READ -> scheme.primary.copy(alpha = 0.35f)
        TokenKind.CACHE_WRITE -> scheme.tertiary.copy(alpha = 0.35f)
    }
}

private val RANGE_LABELS =
    mapOf(
        UsageRange.WEEK to R.string.usage_range_week,
        UsageRange.MONTH to R.string.usage_range_month,
        UsageRange.QUARTER to R.string.usage_range_quarter,
        UsageRange.ALL to R.string.usage_range_all,
    )

/**
 * Where the tokens and money went: totals for a range, a day-by-day chart, and the split by
 * model, by agent and by chat. Every number comes from what the runtimes recorded per turn.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageScreen(
    state: UsageUiState,
    onRangeChange: (UsageRange) -> Unit,
    onIncludeArchivedChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onOpenChat: (ChatUsageRow) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.usage_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh, enabled = !state.loading) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                },
            )
        },
    ) { padding ->
        val report = state.report
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Spacing.screenEdge, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item { RangeRow(state, onRangeChange, onIncludeArchivedChange) }
            item {
                AnimatedVisibility(visible = state.loading) {
                    Column {
                        LinearProgressIndicator(
                            progress = { if (state.total == 0) 0f else state.read.toFloat() / state.total },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            stringResource(R.string.usage_reading, state.read, state.total),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = Spacing.xs),
                        )
                    }
                }
            }
            if (report == null) return@LazyColumn
            if (report.totals.turns == 0 && !state.loading) {
                item { EmptyUsage() }
                item { UsageNotes(report) }
                return@LazyColumn
            }
            item { SummaryCard(report) }
            item { DailyChartCard(report) }
            item { TokenMixCard(report.totals) }
            if (report.models.isNotEmpty()) {
                item {
                    BreakdownCard(stringResource(R.string.usage_by_model), report.models, report.totals.tokens) { share ->
                        ModelLabel(share.key)
                    }
                }
            }
            if (report.agents.size > 1 || report.agents.any { it.key.isEmpty() }) {
                item {
                    BreakdownCard(stringResource(R.string.usage_by_agent), report.agents, report.totals.tokens) { share ->
                        Text(
                            agentName(share.key),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (report.topChats.isNotEmpty()) {
                item { TopChatsHeader() }
                items(report.topChats, key = { "${it.ref.runtimeId}/${it.ref.session.id}" }) { row ->
                    ChatRow(row, report.totals.tokens, onClick = { onOpenChat(row) })
                }
            }
            item { UsageNotes(report) }
        }
    }
}

@Composable
private fun RangeRow(
    state: UsageUiState,
    onRangeChange: (UsageRange) -> Unit,
    onIncludeArchivedChange: (Boolean) -> Unit,
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            UsageRange.entries.forEach { range ->
                FilterChip(
                    selected = state.range == range,
                    onClick = { onRangeChange(range) },
                    label = { Text(stringResource(RANGE_LABELS.getValue(range)), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.usage_include_archived),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = state.includeArchived, onCheckedChange = onIncludeArchivedChange)
        }
    }
}

/** What the totals leave out, and why. */
@Composable
private fun UsageNotes(report: UsageReport) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        if (report.untrackedChats > 0) {
            Text(
                pluralStringResource(R.plurals.usage_untracked, report.untrackedChats, report.untrackedChats),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (report.unreadableChats > 0) {
            Text(
                pluralStringResource(R.plurals.usage_unreadable, report.unreadableChats, report.unreadableChats),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyUsage() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xxl * 2),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Outlined.Insights,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.usage_empty_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = Spacing.md),
        )
        Text(
            stringResource(R.string.usage_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xs),
        )
    }
}

@Composable
private fun SummaryCard(report: UsageReport) {
    val totals = report.totals
    SectionCard {
        Text(
            stringResource(R.string.usage_total_tokens),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                formatUsageTokens(totals.tokens),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { contentDescription = "%,d".format(Locale.getDefault(), totals.tokens) },
            )
            Spacer(Modifier.weight(1f))
            totals.cost?.let { cost ->
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        stringResource(R.string.usage_cost),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(formatUsd(cost), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(vertical = Spacing.md),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Stat(stringResource(R.string.usage_stat_chats), report.activeChats.toString(), Modifier.weight(1f))
            Stat(stringResource(R.string.usage_stat_turns), totals.turns.toString(), Modifier.weight(1f))
            Stat(stringResource(R.string.usage_stat_daily), formatUsageTokens(report.dailyAverage), Modifier.weight(1f))
            Stat(
                stringResource(R.string.usage_stat_cache),
                totals.cacheHitRate?.let { "${(it * 100).roundToInt()}%" } ?: "–",
                Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun Stat(
    label: String,
    value: String,
    modifier: Modifier,
) {
    Column(modifier = modifier) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Tokens per day as stacked bars; tapping a bar shows that day's numbers above the chart. */
@Composable
private fun DailyChartCard(report: UsageReport) {
    val days = report.days
    var selected by remember(days.size, days.firstOrNull()?.date) { mutableStateOf<Int?>(null) }
    val dateFormat = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
    val shortFormat =
        remember {
            val locale = Locale.getDefault()
            DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMd"), locale)
        }
    val kinds = TokenKind.entries
    val colors = kinds.map { kindColor(it) }
    val track = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val highlight = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    SectionCard {
        Text(stringResource(R.string.usage_daily), style = MaterialTheme.typography.titleSmall)
        val focus = selected?.let { days.getOrNull(it) } ?: report.busiestDay
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Spacing.xs).height(36.dp)) {
            if (focus != null) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (selected == null) {
                            stringResource(
                                R.string.usage_busiest_day,
                                focus.date.format(dateFormat),
                            )
                        } else {
                            focus.date.format(dateFormat)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        listOfNotNull(
                            stringResource(R.string.usage_tokens_value, formatUsageTokens(focus.totals.tokens)),
                            focus.totals.cost?.let(::formatUsd),
                            pluralStringResource(R.plurals.usage_turns, focus.totals.turns, focus.totals.turns),
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
        val max = days.maxOfOrNull { it.totals.tokens }?.coerceAtLeast(1) ?: 1
        Canvas(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .padding(top = Spacing.sm)
                    .pointerInput(days, rtl) {
                        detectTapGestures { offset ->
                            if (days.isEmpty()) return@detectTapGestures
                            val slot = size.width / days.size
                            val x = if (rtl) size.width - offset.x else offset.x
                            val index = (x / slot).toInt().coerceIn(0, days.lastIndex)
                            selected = if (selected == index) null else index
                        }
                    },
        ) {
            if (days.isEmpty()) return@Canvas
            val slot = size.width / days.size
            val gap = (slot * 0.25f).coerceAtMost(6.dp.toPx())
            val barWidth = (slot - gap).coerceAtLeast(1f)
            val radius = CornerRadius((barWidth / 2).coerceAtMost(3.dp.toPx()))
            days.forEachIndexed { index, day ->
                val left = if (rtl) size.width - (index + 1) * slot + gap / 2 else index * slot + gap / 2
                if (index == selected) {
                    drawRoundRect(highlight, Offset(left - gap / 2, 0f), Size(slot, size.height), CornerRadius(4.dp.toPx()))
                }
                if (day.totals.tokens == 0L) {
                    drawRoundRect(track, Offset(left, size.height - 2.dp.toPx()), Size(barWidth, 2.dp.toPx()), radius)
                    return@forEachIndexed
                }
                var bottom = size.height
                kinds.forEachIndexed { k, kind ->
                    val value = kind.of(day.totals)
                    if (value == 0L) return@forEachIndexed
                    val height = (value.toFloat() / max * size.height).coerceAtLeast(1f)
                    drawRoundRect(colors[k], Offset(left, bottom - height), Size(barWidth, height), radius)
                    bottom -= height
                }
            }
        }
        if (days.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs)) {
                Text(
                    days.first().date.format(shortFormat),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.usage_daily_average, formatUsageTokens(report.dailyAverage)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    days.last().date.format(shortFormat),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Legend(kinds.filter { kind -> days.any { kind.of(it.totals) > 0 } })
    }
}

@Composable
private fun Legend(kinds: List<TokenKind>) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        kinds.forEach { kind ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(kindColor(kind)))
                Text(
                    stringResource(kind.label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }
}

/** What the tokens were: one bar split by kind, then each kind with its count and share. */
@Composable
private fun TokenMixCard(totals: UsageTotals) {
    val kinds = TokenKind.entries.filter { it.of(totals) > 0 }
    if (kinds.isEmpty()) return
    SectionCard {
        Text(stringResource(R.string.usage_token_mix), style = MaterialTheme.typography.titleSmall)
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.md).height(12.dp).clip(RoundedCornerShape(6.dp))) {
            kinds.forEach { kind ->
                Box(Modifier.weight(kind.of(totals).toFloat()).fillMaxHeight().background(kindColor(kind)))
            }
        }
        kinds.forEach { kind ->
            val value = kind.of(totals)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(kindColor(kind)))
                Text(
                    stringResource(kind.label),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f).padding(start = Spacing.sm),
                )
                Text(formatUsageTokens(value), style = MaterialTheme.typography.bodyMedium, fontFamily = CodeFontFamily)
                Text(
                    "${(value * 100.0 / totals.tokens).roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(44.dp).padding(start = Spacing.sm),
                )
            }
        }
    }
}

@Composable
private fun BreakdownCard(
    title: String,
    shares: List<UsageShare>,
    total: Long,
    label: @Composable (UsageShare) -> Unit,
) {
    SectionCard {
        Text(title, style = MaterialTheme.typography.titleSmall)
        shares.forEachIndexed { index, share ->
            Column(modifier = Modifier.padding(top = if (index == 0) Spacing.sm else Spacing.md)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.weight(1f)) { label(share) }
                    Text(formatUsageTokens(share.totals.tokens), style = MaterialTheme.typography.bodyMedium, fontFamily = CodeFontFamily)
                    share.totals.cost?.let {
                        Text(
                            formatUsd(it),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = Spacing.sm),
                        )
                    }
                }
                ShareBar(share.totals.tokens, total, modifier = Modifier.padding(top = 4.dp))
                Text(
                    listOf(
                        pluralStringResource(R.plurals.usage_chats, share.chats, share.chats),
                        pluralStringResource(R.plurals.usage_turns, share.totals.turns, share.totals.turns),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun ShareBar(
    value: Long,
    total: Long,
    modifier: Modifier = Modifier,
) {
    val fraction = if (total <= 0) 0f else (value.toFloat() / total).coerceIn(0f, 1f)
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.primary))
    }
}

@Composable
private fun ModelLabel(key: String) {
    if (key.isEmpty()) {
        Text(
            stringResource(R.string.usage_model_unknown),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val provider = key.substringBefore('/')
    val model = key.substringAfter('/')
    Row(verticalAlignment = Alignment.CenterVertically) {
        ProviderIcon(provider, size = 16)
        Column(modifier = Modifier.padding(start = Spacing.sm)) {
            Text(model, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(provider, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun agentName(key: String): String =
    LocalAgent.fromId(key)?.let { stringResource(it.displayNameRes) } ?: stringResource(R.string.usage_agent_server)

@Composable
private fun TopChatsHeader() {
    Text(
        stringResource(R.string.usage_top_chats),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = Spacing.sm),
    )
}

@Composable
private fun ChatRow(
    row: ChatUsageRow,
    total: Long,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.ref.session.title.ifBlank { stringResource(R.string.usage_untitled) },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(formatUsageTokens(row.totals.tokens), style = MaterialTheme.typography.bodyMedium, fontFamily = CodeFontFamily)
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                Text(
                    listOfNotNull(
                        agentName(row.ref.agent?.id.orEmpty()),
                        pluralStringResource(R.plurals.usage_turns, row.totals.turns, row.totals.turns),
                        row.totals.cost?.let(::formatUsd),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ShareBar(row.totals.tokens, total, modifier = Modifier.padding(top = Spacing.xs))
        }
    }
}
