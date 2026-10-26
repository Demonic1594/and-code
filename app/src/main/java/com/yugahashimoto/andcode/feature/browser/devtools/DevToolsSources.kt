package com.yugahashimoto.andcode.feature.browser.devtools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.ui.theme.CodeFontFamily
import com.yugahashimoto.andcode.ui.theme.Spacing

/** Every script and stylesheet on the page, each readable with line numbers. */
@Composable
internal fun SourcesTab(
    bridge: DevToolsBridge,
    modifier: Modifier = Modifier,
) {
    var sources by remember { mutableStateOf<List<PageSource>?>(null) }
    var open by remember { mutableStateOf<Pair<String, String?>?>(null) }
    var query by remember { mutableStateOf("") }
    var requests by remember { mutableIntStateOf(0) }
    val inlineScript = stringResource(R.string.devtools_inline_script)
    val inlineStyle = stringResource(R.string.devtools_inline_style)

    fun refresh() = bridge.call("sources") { sources = DevToolsProtocol.parseSources(it) }
    val page by bridge.store.page.collectAsState()
    LaunchedEffect(page) {
        open = null
        refresh()
    }

    open?.let { (title, text) ->
        SourceViewer(title = title, text = text, onBack = { open = null }, modifier = modifier)
        return
    }
    Column(modifier = modifier) {
        DevToolsToolbar(
            query = query,
            onQueryChange = { query = it },
            chips = emptyList(),
            selectedChip = 0,
            onChipSelected = {},
            trailing = {
                IconButton(
                    onClick = ::refresh,
                ) { Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.devtools_refresh)) }
            },
        )
        val list = sources
        when {
            list == null -> DevToolsEmpty(stringResource(R.string.devtools_loading))
            list.isEmpty() -> DevToolsEmpty(stringResource(R.string.devtools_sources_empty))
            else -> {
                var scripts = 0
                var styles = 0
                val labelled =
                    list.map { source ->
                        val label =
                            when {
                                source.url.isNotBlank() -> source.url
                                source.kind == "style" -> "$inlineStyle ${++styles}"
                                else -> "$inlineScript ${++scripts}"
                            }
                        label to source
                    }.filter { (label, _) -> query.isBlank() || label.contains(query, ignoreCase = true) }
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(labelled) { (label, source) ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val title = label.substringAfterLast('/').ifBlank { label }
                                        if (source.inline != null) {
                                            open = title to source.inline
                                        } else {
                                            open = title to null
                                            val request = ++requests
                                            bridge.fetchText(source.url) { ok, text ->
                                                // A late answer for a file the user already left is dropped.
                                                if (request == requests && open?.first == title) open = title to (if (ok) text else "")
                                            }
                                        }
                                    }.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (source.kind == "style") Icons.Outlined.Palette else Icons.Outlined.Code,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Column(modifier = Modifier.padding(start = Spacing.sm)) {
                                Text(
                                    label.substringAfterLast('/').ifBlank { label },
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (source.url.isNotBlank()) {
                                    Text(
                                        source.url,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceViewer(
    title: String,
    text: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onBack,
            ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back)) }
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (text != null) {
                IconButton(onClick = { clipboard.setText(AnnotatedString(text)) }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.devtools_copy))
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (text == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return
        }
        if (text.isEmpty()) {
            DevToolsEmpty(stringResource(R.string.devtools_source_unavailable))
            return
        }
        val lines = remember(text) { text.lines() }
        val gutter = remember(lines.size) { (lines.size.toString().length * 8 + 8).dp }
        SelectionContainer {
            LazyColumn(modifier = Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                items(lines.size) { index ->
                    Row {
                        Text(
                            (index + 1).toString(),
                            fontFamily = CodeFontFamily,
                            fontSize = 11.sp,
                            color = valueColors().muted,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(gutter).padding(end = 6.dp),
                        )
                        Text(
                            lines[index],
                            fontFamily = CodeFontFamily,
                            fontSize = 12.sp,
                            softWrap = false,
                            modifier = Modifier.padding(end = Spacing.md),
                        )
                    }
                }
            }
        }
    }
}
