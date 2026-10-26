package com.yugahashimoto.andcode.feature.browser.devtools

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.ui.theme.CodeFontFamily
import com.yugahashimoto.andcode.ui.theme.Spacing
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

internal enum class NetworkFilter(
    val label: Int,
    val category: NetworkCategory?,
) {
    ALL(R.string.devtools_level_all, null),
    FETCH(R.string.devtools_network_fetch, NetworkCategory.FETCH),
    DOC(R.string.devtools_network_doc, NetworkCategory.DOC),
    JS(R.string.devtools_network_js, NetworkCategory.JS),
    CSS(R.string.devtools_network_css, NetworkCategory.CSS),
    IMG(R.string.devtools_network_img, NetworkCategory.IMG),
    FONT(R.string.devtools_network_font, NetworkCategory.FONT),
    WS(R.string.devtools_network_ws, NetworkCategory.WS),
    OTHER(R.string.devtools_network_other, NetworkCategory.OTHER),
}

internal fun filterNetwork(
    entries: List<NetworkEntry>,
    filter: NetworkFilter,
    query: String,
    onlyFailed: Boolean,
): List<NetworkEntry> =
    entries.filter { entry ->
        (filter.category == null || entry.category == filter.category) &&
            (!onlyFailed || entry.failed) &&
            (query.isBlank() || entry.url.contains(query, ignoreCase = true) || entry.method.contains(query, ignoreCase = true))
    }

/**
 * The request as a `curl` command that repeats it, single-quoted for a POSIX shell. Headers the
 * browser adds by itself are not recorded, so only what the page set goes in.
 */
internal fun curlCommand(entry: NetworkEntry): String =
    buildString {
        append("curl ")
        append(shellQuote(entry.url))
        if (entry.method != "GET") append(" -X ").append(entry.method)
        entry.requestHeaders.forEach { header -> append(" \\\n  -H ").append(shellQuote("${header.name}: ${header.value}")) }
        entry.requestBody?.let { append(" \\\n  --data-raw ").append(shellQuote(it)) }
    }

private fun shellQuote(text: String): String = "'" + text.replace("'", "'\\''") + "'"

/** A response body laid out for reading: JSON indented, anything else as it came. */
internal fun prettyBody(
    body: String,
    mime: String,
): String {
    val trimmed = body.trim()
    val looksJson = mime.contains("json", ignoreCase = true) || trimmed.startsWith("{") || trimmed.startsWith("[")
    if (!looksJson) return body
    return runCatching {
        prettyJson.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), devToolsJson.parseToJsonElement(trimmed))
    }
        .getOrDefault(body)
}

private val prettyJson = kotlinx.serialization.json.Json { prettyPrint = true }

@Composable
internal fun NetworkTab(
    bridge: DevToolsBridge,
    modifier: Modifier = Modifier,
) {
    val entries by bridge.store.network.collectAsState()
    var filter by rememberSaveable { mutableStateOf(NetworkFilter.ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    var onlyFailed by rememberSaveable { mutableStateOf(false) }
    var preserve by remember { mutableStateOf(bridge.store.preserveLog) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val shown = remember(entries, filter, query, onlyFailed) { filterNetwork(entries, filter, query, onlyFailed) }
    val selected = entries.firstOrNull { it.id == selectedId }

    if (selected != null) {
        RequestDetail(selected, onBack = { selectedId = null }, modifier = modifier)
        return
    }
    Column(modifier = modifier) {
        DevToolsToolbar(
            query = query,
            onQueryChange = { query = it },
            chips = NetworkFilter.entries.map { it.label },
            selectedChip = filter.ordinal,
            onChipSelected = { filter = NetworkFilter.entries[it] },
            onClear = bridge.store::clearNetwork,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val failedCount = entries.count { it.failed }
            val bytes = entries.sumOf { it.size ?: 0L }
            Text(
                listOfNotNull(
                    pluralStringResource(R.plurals.devtools_requests, entries.size, entries.size),
                    formatBytes(bytes),
                    if (failedCount > 0) pluralStringResource(R.plurals.devtools_failed, failedCount, failedCount) else null,
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            FilterChip(
                selected = onlyFailed,
                onClick = { onlyFailed = !onlyFailed },
                label = { Text(stringResource(R.string.devtools_only_failed), fontSize = 11.sp) },
                modifier = Modifier.height(26.dp).padding(start = Spacing.xs),
            )
            FilterChip(
                selected = preserve,
                onClick = {
                    preserve = !preserve
                    bridge.store.preserveLog = preserve
                },
                label = { Text(stringResource(R.string.devtools_preserve_log), fontSize = 11.sp) },
                modifier = Modifier.height(26.dp).padding(start = Spacing.xs),
            )
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (shown.isEmpty()) {
                DevToolsEmpty(stringResource(if (bridge.receivesEvents) R.string.devtools_network_empty else R.string.devtools_no_events))
            } else {
                val start = shown.minOf { it.startedAt.takeIf { at -> at > 0 } ?: Long.MAX_VALUE }.takeIf { it != Long.MAX_VALUE } ?: 0L
                val end = shown.maxOf { (it.startedAt + (it.durationMs ?: 0.0).toLong()) }.coerceAtLeast(start + 1)
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(shown, key = { it.id }) { entry ->
                        RequestRow(entry, start, end, onClick = { selectedId = entry.id })
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                }
            }
        }
    }
}

@Composable
private fun statusColor(entry: NetworkEntry): Color =
    when {
        entry.error != null || (entry.status ?: 0) >= 500 -> MaterialTheme.colorScheme.error
        (entry.status ?: 0) >= 400 -> Color(0xFFE37400)
        (entry.status ?: 0) in 300..399 -> MaterialTheme.colorScheme.tertiary
        !entry.finished || entry.statusUnknown -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> Color(0xFF1E8E3E)
    }

/** The status as a line shows it: the code, the error, or why there is neither. */
@Composable
private fun statusText(entry: NetworkEntry): String =
    entry.status?.let { "$it ${entry.statusText}".trim() }
        ?: entry.error
        ?: stringResource(if (entry.statusUnknown) R.string.devtools_status_unknown else R.string.devtools_pending)

@Composable
private fun RequestRow(
    entry: NetworkEntry,
    start: Long,
    end: Long,
    onClick: () -> Unit,
) {
    val colors = valueColors()
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = Spacing.md, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = statusColor(entry).copy(alpha = 0.14f),
            modifier = Modifier.width(44.dp),
        ) {
            Text(
                text =
                    when {
                        entry.status != null -> entry.status.toString()
                        entry.error != null -> "✕"
                        entry.statusUnknown -> "?"
                        else -> "…"
                    },
                color = statusColor(entry),
                fontFamily = CodeFontFamily,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.padding(vertical = 2.dp).fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        Column(modifier = Modifier.weight(1f).padding(horizontal = Spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.method,
                    color = colors.keyword,
                    fontFamily = CodeFontFamily,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(end = 6.dp),
                )
                Text(
                    entry.name,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (entry.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                listOfNotNull(
                    entry.url.substringAfter("://").substringBefore('/'),
                    entry.kind,
                    entry.error,
                ).joinToString(" · "),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(72.dp)) {
            Text(formatBytes(entry.size), fontSize = 11.sp, fontFamily = CodeFontFamily)
            Text(
                formatMillis(entry.durationMs),
                fontSize = 11.sp,
                fontFamily = CodeFontFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // A small waterfall: where in the page's timeline this request ran.
            if (entry.startedAt > 0) {
                val span = (end - start).toFloat()
                // At least a sliver wide, and never past the end, even for the last request to start.
                val offset = ((entry.startedAt - start) / span).coerceIn(0f, 1f - MIN_BAR)
                val width = (((entry.durationMs ?: 0.0) / span).toFloat()).coerceAtLeast(MIN_BAR).coerceAtMost(1f - offset)
                Box(
                    modifier =
                        Modifier.padding(
                            top = 3.dp,
                        ).width(
                            64.dp,
                        ).height(4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxHeight()
                                .padding(start = (64 * offset).dp)
                                .width((64 * width).dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(statusColor(entry)),
                    )
                }
            }
        }
    }
}

private const val MIN_BAR = 0.03f

private enum class RequestSection(
    val label: Int,
) {
    HEADERS(R.string.devtools_headers),
    PAYLOAD(R.string.devtools_payload),
    PREVIEW(R.string.devtools_preview),
    RESPONSE(R.string.devtools_response),
    MESSAGES(R.string.devtools_messages),
}

@Composable
private fun RequestDetail(
    entry: NetworkEntry,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    val previewValue =
        remember(entry.responseBody) {
            entry.responseBody?.let { body ->
                runCatching { devToolsJson.parseToJsonElement(body.trim()) }.getOrNull()?.takeIf { it is JsonObject || it is JsonArray }
            }
        }
    val sections =
        buildList {
            add(RequestSection.HEADERS)
            if (entry.requestBody != null) add(RequestSection.PAYLOAD)
            if (previewValue != null) add(RequestSection.PREVIEW)
            if (entry.responseBody != null || entry.kind == "fetch" || entry.kind == "xhr") add(RequestSection.RESPONSE)
            if (entry.kind == "websocket") add(RequestSection.MESSAGES)
        }
    var section by rememberSaveable(entry.id) { mutableStateOf(RequestSection.HEADERS) }
    var menu by remember { mutableStateOf(false) }
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = Spacing.xs)) {
            IconButton(
                onClick = onBack,
            ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back)) }
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${entry.method} · ${statusText(entry)} · ${formatMillis(entry.durationMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor(entry),
                )
            }
            Box {
                IconButton(
                    onClick = { menu = true },
                ) { Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.devtools_copy)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.devtools_copy_url)) },
                        onClick = {
                            menu = false
                            clipboard.setText(AnnotatedString(entry.url))
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.devtools_copy_curl)) },
                        leadingIcon = { Icon(Icons.Default.Terminal, contentDescription = null) },
                        onClick = {
                            menu = false
                            clipboard.setText(AnnotatedString(curlCommand(entry)))
                        },
                    )
                    if (entry.responseBody != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.devtools_copy_response)) },
                            onClick = {
                                menu = false
                                clipboard.setText(AnnotatedString(entry.responseBody))
                            },
                        )
                    }
                }
            }
        }
        if (sections.size > 1) {
            PrimaryScrollableTabRow(selectedTabIndex = sections.indexOf(section).coerceAtLeast(0), edgePadding = Spacing.sm, divider = {}) {
                sections.forEach { item ->
                    Tab(
                        selected = item == section,
                        onClick = { section = item },
                        text = { Text(stringResource(item.label), fontSize = 12.sp) },
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SelectionContainer(modifier = Modifier.weight(1f)) {
            Column(
                modifier =
                    Modifier.fillMaxSize().verticalScroll(
                        rememberScrollState(),
                    ).padding(horizontal = Spacing.md, vertical = Spacing.sm),
            ) {
                when (section) {
                    RequestSection.HEADERS -> {
                        DevToolsSectionTitle(stringResource(R.string.devtools_general))
                        DevToolsKeyValue(stringResource(R.string.devtools_request_url), entry.url)
                        DevToolsKeyValue(stringResource(R.string.devtools_request_method), entry.method)
                        DevToolsKeyValue(
                            stringResource(R.string.devtools_status_code),
                            statusText(entry),
                        )
                        DevToolsKeyValue(
                            stringResource(R.string.devtools_type),
                            listOf(entry.kind, entry.mime).filter { it.isNotBlank() }.joinToString(" · "),
                        )
                        DevToolsKeyValue(stringResource(R.string.devtools_duration), formatMillis(entry.durationMs))
                        DevToolsKeyValue(stringResource(R.string.devtools_size), formatBytes(entry.size))
                        if (entry.responseHeaders.isNotEmpty()) {
                            DevToolsSectionTitle(stringResource(R.string.devtools_response_headers))
                            entry.responseHeaders.forEach { DevToolsKeyValue(it.name, it.value) }
                        }
                        DevToolsSectionTitle(stringResource(R.string.devtools_request_headers))
                        if (entry.requestHeaders.isEmpty()) {
                            Text(
                                stringResource(R.string.devtools_headers_not_recorded),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        entry.requestHeaders.forEach { DevToolsKeyValue(it.name, it.value) }
                    }
                    RequestSection.PAYLOAD -> CodeBlock(prettyBody(entry.requestBody.orEmpty(), ""))
                    RequestSection.PREVIEW -> previewValue?.let { JsonTree(it, initiallyExpanded = true) }
                    RequestSection.RESPONSE ->
                        if (entry.responseBody.isNullOrEmpty()) {
                            Text(
                                stringResource(R.string.devtools_no_body),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            CodeBlock(prettyBody(entry.responseBody, entry.mime))
                        }
                    RequestSection.MESSAGES ->
                        entry.frames.forEach { frame ->
                            val colors = valueColors()
                            Row(modifier = Modifier.padding(vertical = 2.dp)) {
                                Text(
                                    when (frame.direction) {
                                        "out" -> "↑"
                                        "in" -> "↓"
                                        else -> "•"
                                    },
                                    color = if (frame.direction == "out") Color(0xFF1E8E3E) else colors.keyword,
                                    fontFamily = CodeFontFamily,
                                    fontSize = 12.sp,
                                    modifier = Modifier.width(16.dp),
                                )
                                Text(frame.data, fontFamily = CodeFontFamily, fontSize = 12.sp)
                            }
                        }
                }
            }
        }
    }
}

@Composable
internal fun CodeBlock(text: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            fontFamily = CodeFontFamily,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(Spacing.sm),
        )
    }
}
