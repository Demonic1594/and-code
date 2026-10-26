package com.yugahashimoto.andcode.feature.browser.devtools

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.ui.theme.CodeFontFamily
import com.yugahashimoto.andcode.ui.theme.Spacing
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Which lines the console shows. */
internal enum class ConsoleFilter(
    val label: Int,
) {
    ALL(R.string.devtools_level_all),
    ERRORS(R.string.devtools_level_errors),
    WARNINGS(R.string.devtools_level_warnings),
    INFO(R.string.devtools_level_info),
    LOGS(R.string.devtools_level_logs),
    DEBUG(R.string.devtools_level_debug),
    ;

    fun accepts(level: ConsoleLevel): Boolean =
        when (this) {
            ALL -> true
            ERRORS -> level == ConsoleLevel.ERROR
            WARNINGS -> level == ConsoleLevel.WARN
            INFO -> level == ConsoleLevel.INFO
            LOGS -> level == ConsoleLevel.LOG || level == ConsoleLevel.INPUT || level == ConsoleLevel.RESULT
            DEBUG -> level == ConsoleLevel.DEBUG
        }
}

/** The lines [filter] and [query] let through, oldest first. Pulled out so it can be tested. */
internal fun filterConsole(
    entries: List<ConsoleEntry>,
    filter: ConsoleFilter,
    query: String,
): List<ConsoleEntry> =
    entries.filter { entry ->
        filter.accepts(entry.level) &&
            (query.isBlank() || entry.text.contains(query, ignoreCase = true) || entry.source.contains(query, ignoreCase = true))
    }

@Composable
internal fun ConsoleTab(
    bridge: DevToolsBridge,
    modifier: Modifier = Modifier,
) {
    val entries by bridge.store.console.collectAsState()
    var filter by rememberSaveable { mutableStateOf(ConsoleFilter.ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(entries, filter, query) { filterConsole(entries, filter, query) }
    val listState = rememberLazyListState()
    // Follow new output while the user is at the bottom; once they scroll up to read, stay put.
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= info.totalItemsCount - 2
        }
    }
    LaunchedEffect(shown.size) {
        if (shown.isNotEmpty() && atBottom) listState.scrollToItem(shown.lastIndex)
    }
    Column(modifier = modifier) {
        DevToolsToolbar(
            query = query,
            onQueryChange = { query = it },
            chips = ConsoleFilter.entries.map { it.label },
            selectedChip = filter.ordinal,
            onChipSelected = { filter = ConsoleFilter.entries[it] },
            onClear = bridge.store::clearConsole,
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (shown.isEmpty()) {
                DevToolsEmpty(
                    stringResource(if (bridge.receivesEvents) R.string.devtools_empty_console else R.string.devtools_no_events),
                )
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(shown, key = { it.id }) { entry ->
                        ConsoleLine(entry)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                }
            }
        }
        ConsolePrompt(onRun = bridge::evaluate)
    }
}

private data class LevelStyle(
    val background: Color,
    val foreground: Color,
)

@Composable
private fun levelStyle(level: ConsoleLevel): LevelStyle {
    val dark = MaterialTheme.colorScheme.surface.run { 0.2126f * red + 0.7152f * green + 0.0722f * blue } < 0.5f
    return when (level) {
        ConsoleLevel.ERROR ->
            if (dark) LevelStyle(Color(0x33F28B82), Color(0xFFF28B82)) else LevelStyle(Color(0xFFFCEBEB), Color(0xFFC5221F))
        ConsoleLevel.WARN ->
            if (dark) LevelStyle(Color(0x33FDD663), Color(0xFFFDD663)) else LevelStyle(Color(0xFFFEF7E0), Color(0xFF8A5A00))
        else -> LevelStyle(Color.Transparent, MaterialTheme.colorScheme.onSurface)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConsoleLine(entry: ConsoleEntry) {
    val style = levelStyle(entry.level)
    val colors = valueColors()
    val clipboard = LocalClipboardManager.current
    var expanded by remember(entry.id) { mutableStateOf(false) }
    val expandable = entry.stack.isNotBlank() || entry.args.any { it is JsonObject || it is kotlinx.serialization.json.JsonArray }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(style.background)
                .combinedClickable(
                    onClick = { if (expandable) expanded = !expanded },
                    onLongClick = {
                        clipboard.setText(
                            AnnotatedString(entry.text + if (entry.stack.isNotBlank()) "\n" + entry.stack else ""),
                        )
                    },
                ).padding(start = (Spacing.sm + (entry.depth * 12).dp), end = Spacing.sm, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        val icon =
            when (entry.level) {
                ConsoleLevel.ERROR -> Icons.Default.Error
                ConsoleLevel.WARN -> Icons.Default.Warning
                ConsoleLevel.INFO -> Icons.Default.Info
                ConsoleLevel.INPUT -> Icons.AutoMirrored.Filled.KeyboardArrowRight
                else -> null
            }
        Box(modifier = Modifier.width(18.dp).padding(top = 1.dp)) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (entry.level == ConsoleLevel.INPUT) colors.keyword else style.foreground,
                    modifier = Modifier.size(14.dp),
                )
            } else if (entry.level == ConsoleLevel.RESULT) {
                Text("←", color = colors.muted, fontSize = 12.sp, fontFamily = CodeFontFamily)
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            if (expanded) {
                entry.args.forEach { arg ->
                    if (arg is JsonPrimitive && arg.isString) {
                        Text(arg.content, color = style.foreground, fontFamily = CodeFontFamily, fontSize = 12.sp, lineHeight = 17.sp)
                    } else {
                        JsonTree(arg, initiallyExpanded = true)
                    }
                }
                if (entry.stack.isNotBlank()) {
                    Text(
                        entry.stack,
                        color = colors.muted,
                        fontFamily = CodeFontFamily,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(top = Spacing.xs),
                    )
                }
            } else {
                Text(
                    text =
                        androidx.compose.ui.text.buildAnnotatedString {
                            entry.args.forEachIndexed { index, arg ->
                                if (index > 0) append(" ")
                                append(styledPreview(arg, colors, topLevel = true))
                            }
                        },
                    color = style.foreground,
                    fontFamily = CodeFontFamily,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    maxLines = if (entry.level == ConsoleLevel.ERROR) 6 else 12,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (entry.source.isNotBlank() || entry.frame.isNotBlank()) {
                Text(
                    text =
                        listOf(
                            entry.source.substringAfterLast('/'),
                            entry.frame.takeIf {
                                it.isNotBlank()
                            }?.let { "iframe" },
                        ).filterNotNull().joinToString(" · "),
                    color = colors.muted,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (entry.repeat > 1) {
            Surface(
                shape = RoundedCornerShape(50),
                color =
                    style.foreground.takeIf {
                        entry.level == ConsoleLevel.ERROR || entry.level == ConsoleLevel.WARN
                    } ?: MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.padding(start = Spacing.xs),
            ) {
                Text(
                    entry.repeat.toString(),
                    fontSize = 10.sp,
                    color = if (entry.level == ConsoleLevel.ERROR || entry.level == ConsoleLevel.WARN) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }
    }
}

/** The `›` line at the bottom: runs JavaScript in the page, with the session's earlier commands a tap away. */
@Composable
private fun ConsolePrompt(onRun: (String) -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    val history = remember { mutableStateListOf<String>() }
    var historyIndex by remember { mutableIntStateOf(-1) }
    val colors = valueColors()

    fun run() {
        val text = code.trim()
        if (text.isEmpty()) return
        onRun(text)
        if (history.lastOrNull() != text) history.add(text)
        historyIndex = -1
        code = ""
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = Spacing.sm, end = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.keyword,
                modifier = Modifier.size(16.dp),
            )
            Box(modifier = Modifier.weight(1f).padding(vertical = Spacing.sm, horizontal = Spacing.xs)) {
                if (code.isEmpty()) {
                    Text(
                        stringResource(R.string.devtools_console_prompt_hint),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        fontFamily = CodeFontFamily,
                    )
                }
                BasicTextField(
                    value = code,
                    onValueChange = { code = it },
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontFamily = CodeFontFamily),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    maxLines = 6,
                    keyboardOptions =
                        KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Go,
                        ),
                    keyboardActions = KeyboardActions(onGo = { run() }),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 20.dp),
                )
            }
            IconButton(
                enabled = history.isNotEmpty(),
                onClick = {
                    // Steps back through earlier commands, newest first, wrapping round.
                    historyIndex = if (historyIndex <= 0) history.lastIndex else historyIndex - 1
                    code = history.getOrNull(historyIndex).orEmpty()
                },
            ) { Icon(Icons.Default.History, contentDescription = stringResource(R.string.devtools_history_previous)) }
            IconButton(enabled = code.isNotBlank(), onClick = ::run) {
                Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.devtools_run))
            }
        }
    }
}
