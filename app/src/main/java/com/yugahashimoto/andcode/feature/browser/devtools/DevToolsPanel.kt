package com.yugahashimoto.andcode.feature.browser.devtools

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.ui.theme.Spacing

internal enum class DevToolsTab(
    val label: Int,
) {
    CONSOLE(R.string.devtools_tab_console),
    ELEMENTS(R.string.devtools_tab_elements),
    NETWORK(R.string.devtools_tab_network),
    STORAGE(R.string.devtools_tab_storage),
    SOURCES(R.string.devtools_tab_sources),
    PERFORMANCE(R.string.devtools_tab_performance),
}

/**
 * The DevTools panel docked under the page: a drag handle to resize it, a tab per tool, and the
 * tool. [height] is owned by the caller so the page above can give way as it grows.
 */
@Composable
fun DevToolsPanel(
    bridge: DevToolsBridge,
    height: Dp,
    onHeightChange: (Dp) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable { mutableStateOf(DevToolsTab.CONSOLE) }
    val problems by bridge.store.problems.collectAsState()
    val network by bridge.store.network.collectAsState()
    val inspected by bridge.store.inspected.collectAsState()
    // A pick on the page jumps straight to the element.
    if (inspected != null && tab != DevToolsTab.ELEMENTS) tab = DevToolsTab.ELEMENTS
    val density = LocalDensity.current
    Surface(
        modifier = modifier.fillMaxWidth().height(height),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 8.dp,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Drag up or down to give the page or the tools more room.
            val resizeLabel = stringResource(R.string.devtools_resize)
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(16.dp)
                        .semantics { contentDescription = resizeLabel }
                        .draggable(
                            orientation = Orientation.Vertical,
                            state = rememberDraggableState { delta -> onHeightChange(height - with(density) { delta.toDp() }) },
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(width = 36.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                PrimaryScrollableTabRow(
                    selectedTabIndex = tab.ordinal,
                    edgePadding = Spacing.sm,
                    containerColor = MaterialTheme.colorScheme.surface,
                    divider = {},
                    modifier = Modifier.weight(1f),
                ) {
                    DevToolsTab.entries.forEach { entry ->
                        Tab(
                            selected = tab == entry,
                            onClick = { tab = entry },
                            text = {
                                when {
                                    entry == DevToolsTab.CONSOLE && problems.errors + problems.warnings > 0 ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(stringResource(entry.label))
                                            Badge(
                                                containerColor =
                                                    if (problems.errors > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                                                modifier = Modifier.padding(start = 6.dp),
                                            ) { Text((problems.errors + problems.warnings).coerceAtMost(999).toString()) }
                                        }
                                    entry == DevToolsTab.NETWORK && network.isNotEmpty() ->
                                        Text("${stringResource(entry.label)} ${network.size}")
                                    else -> Text(stringResource(entry.label))
                                }
                            },
                        )
                    }
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.devtools_close))
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            when (tab) {
                DevToolsTab.CONSOLE -> ConsoleTab(bridge, Modifier.weight(1f))
                DevToolsTab.ELEMENTS -> ElementsTab(bridge, Modifier.weight(1f))
                DevToolsTab.NETWORK -> NetworkTab(bridge, Modifier.weight(1f))
                DevToolsTab.STORAGE -> StorageTab(bridge, Modifier.weight(1f))
                DevToolsTab.SOURCES -> SourcesTab(bridge, Modifier.weight(1f))
                DevToolsTab.PERFORMANCE -> PerformanceTab(bridge, Modifier.weight(1f))
            }
        }
    }
}

/** The search field, filter chips and clear button most tools share. */
@Composable
internal fun DevToolsToolbar(
    query: String,
    onQueryChange: (String) -> Unit,
    chips: List<Int>,
    selectedChip: Int,
    onChipSelected: (Int) -> Unit,
    onClear: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.sm, vertical = Spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.weight(1f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = Spacing.md, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Box(modifier = Modifier.weight(1f).padding(start = Spacing.sm)) {
                        if (query.isEmpty()) {
                            Text(
                                stringResource(R.string.devtools_filter_hint),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = onQueryChange,
                            singleLine = true,
                            textStyle = TextStyle(fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (query.isNotEmpty()) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.devtools_clear_filter),
                            modifier = Modifier.size(18.dp).clip(RoundedCornerShape(50)).clickable { onQueryChange("") }.padding(1.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            trailing()
            if (onClear != null) {
                IconButton(onClick = onClear) { Icon(Icons.Default.Block, contentDescription = stringResource(R.string.devtools_clear)) }
            }
        }
        if (chips.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = Spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                chips.forEachIndexed { index, label ->
                    FilterChip(
                        selected = index == selectedChip,
                        onClick = { onChipSelected(index) },
                        label = { Text(stringResource(label), fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(),
                        modifier = Modifier.height(28.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun DevToolsEmpty(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(Spacing.xxl), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** A section title inside a tool's detail pane. */
@Composable
internal fun DevToolsSectionTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = Spacing.md, bottom = Spacing.xs),
    )
}

/** A name and value pair as headers, attributes and storage entries show them. */
@Composable
internal fun DevToolsKeyValue(
    key: String,
    value: String,
    modifier: Modifier = Modifier,
    keyWidth: Dp = 120.dp,
) {
    val colors = valueColors()
    Row(modifier = modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            key,
            color = colors.key,
            fontFamily = com.yugahashimoto.andcode.ui.theme.CodeFontFamily,
            fontSize = 12.sp,
            modifier = Modifier.width(keyWidth),
        )
        Text(
            value,
            fontFamily = com.yugahashimoto.andcode.ui.theme.CodeFontFamily,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
        )
    }
}

internal fun formatBytes(bytes: Long?): String {
    if (bytes == null || bytes < 0) return "–"
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f kB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }
}

internal fun formatMillis(ms: Double?): String {
    if (ms == null || ms < 0) return "–"
    return if (ms < 1000) "${ms.toInt()} ms" else "%.2f s".format(ms / 1000)
}
