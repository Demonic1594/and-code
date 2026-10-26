package com.yugahashimoto.andcode.feature.browser.devtools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.ui.theme.CodeFontFamily
import com.yugahashimoto.andcode.ui.theme.Spacing

private val TimelineColors =
    listOf(
        Color(0xFF8AB4F8),
        Color(0xFF81C995),
        Color(0xFFFDD663),
        Color(0xFFF28B82),
        Color(0xFFC58AF9),
    )

/** How fast the page loaded and what it weighs, at a glance. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PerformanceTab(
    bridge: DevToolsBridge,
    modifier: Modifier = Modifier,
) {
    var snapshot by remember { mutableStateOf<PerformanceSnapshot?>(null) }
    var loaded by remember { mutableStateOf(false) }

    fun refresh() =
        bridge.call("performance") {
            snapshot = DevToolsProtocol.parsePerformance(it)
            loaded = true
        }
    // Read again for every new page.
    val page by bridge.store.page.collectAsState()
    LaunchedEffect(page) { refresh() }

    val perf = snapshot
    Column(modifier = modifier) {
        Row(modifier = Modifier.fillMaxWidth().padding(start = Spacing.md), verticalAlignment = Alignment.CenterVertically) {
            Text(
                perf?.title?.ifBlank { perf.url } ?: "",
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = ::refresh) { Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.devtools_refresh)) }
        }
        if (perf == null) {
            DevToolsEmpty(stringResource(if (loaded) R.string.devtools_elements_empty else R.string.devtools_loading))
            return@Column
        }
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.md, vertical = Spacing.xs),
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                MetricCard(stringResource(R.string.devtools_first_contentful_paint), formatMillis(perf.firstContentfulPaint))
                MetricCard(stringResource(R.string.devtools_dom_content_loaded), formatMillis(perf.navigation?.domContentLoaded))
                MetricCard(stringResource(R.string.devtools_load_event), formatMillis(perf.navigation?.load))
                MetricCard(stringResource(R.string.devtools_ttfb), formatMillis(perf.navigation?.ttfb))
                MetricCard(stringResource(R.string.devtools_resources), "${perf.resourceCount} · ${formatBytes(perf.resourceBytes)}")
                MetricCard(stringResource(R.string.devtools_dom_nodes), perf.nodeCount.toString())
                MetricCard(
                    stringResource(R.string.devtools_js_heap),
                    perf.heapUsed?.let { stringResource(R.string.devtools_heap_value, formatBytes(it), formatBytes(perf.heapLimit)) }
                        ?: stringResource(R.string.devtools_not_available),
                )
            }
            perf.navigation?.let { nav ->
                DevToolsSectionTitle(stringResource(R.string.devtools_load_timeline))
                val phases =
                    listOf(
                        stringResource(R.string.devtools_dns) to nav.dns,
                        stringResource(R.string.devtools_connect) to nav.connect,
                        stringResource(R.string.devtools_ttfb) to nav.ttfb,
                        stringResource(R.string.devtools_download) to nav.download,
                        stringResource(
                            R.string.devtools_dom_interactive,
                        ) to (nav.domInteractive - (nav.ttfb + nav.download + nav.dns + nav.connect)).coerceAtLeast(0.0),
                    )
                val total = phases.sumOf { it.second }.coerceAtLeast(1.0)
                Row(
                    modifier =
                        Modifier.fillMaxWidth().height(
                            14.dp,
                        ).clip(RoundedCornerShape(7.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                ) {
                    phases.forEachIndexed { index, (_, ms) ->
                        if (ms > 0) {
                            Box(
                                modifier =
                                    Modifier.weight(
                                        (ms / total).toFloat(),
                                    ).fillMaxHeight().background(TimelineColors[index % TimelineColors.size]),
                            )
                        }
                    }
                }
                Column(modifier = Modifier.padding(top = Spacing.sm)) {
                    phases.forEachIndexed { index, (label, ms) ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                            Box(
                                modifier =
                                    Modifier.size(
                                        10.dp,
                                    ).clip(RoundedCornerShape(2.dp)).background(TimelineColors[index % TimelineColors.size]),
                            )
                            Text(label, fontSize = 12.sp, modifier = Modifier.weight(1f).padding(start = Spacing.sm))
                            Text(formatMillis(ms), fontSize = 12.sp, fontFamily = CodeFontFamily)
                        }
                    }
                }
                DevToolsKeyValue(stringResource(R.string.devtools_first_paint), formatMillis(perf.firstPaint), keyWidth = 170.dp)
                DevToolsKeyValue(
                    stringResource(R.string.devtools_document_size),
                    "${formatBytes(nav.transfer)} / ${formatBytes(nav.decoded)}",
                    keyWidth = 170.dp,
                )
            }
            DevToolsSectionTitle(stringResource(R.string.devtools_environment))
            DevToolsKeyValue(
                stringResource(R.string.devtools_viewport),
                "${perf.viewportWidth} × ${perf.viewportHeight} @${"%.2f".format(perf.devicePixelRatio).trimEnd('0').trimEnd('.')}x",
                keyWidth = 120.dp,
            )
            DevToolsKeyValue(stringResource(R.string.devtools_user_agent), perf.userAgent, keyWidth = 120.dp)
            DevToolsKeyValue(stringResource(R.string.devtools_url), perf.url, keyWidth = 120.dp)
        }
    }
}

@Composable
private fun MetricCard(
    label: String,
    value: String,
) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.width(150.dp)) {
        Column(modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            Text(value, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, fontFamily = CodeFontFamily, maxLines = 1)
        }
    }
}
