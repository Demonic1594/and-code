package com.yugahashimoto.andcode.feature.chat

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.BitmapFactory
import android.util.Base64
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.core.ProvideSafeUriHandler
import com.yugahashimoto.andcode.ui.theme.CodeFontFamily
import com.yugahashimoto.andcode.ui.theme.Spacing

internal fun ArtifactKind.icon(): ImageVector =
    when (this) {
        ArtifactKind.HTML -> Icons.Outlined.Language
        ArtifactKind.SVG -> Icons.Outlined.Palette
        ArtifactKind.MARKDOWN -> Icons.Outlined.Article
        ArtifactKind.IMAGE -> Icons.Outlined.Image
    }

/**
 * Every artifact of the chat in one list: the pages, drawings, documents and pictures the agent
 * wrote, and the local servers it started. A file opens in the preview; a server in the in-app
 * browser.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ArtifactsSheet(
    artifacts: List<Artifact>,
    onOpenFile: (Artifact.File) -> Unit,
    onOpenServer: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.artifacts_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = Spacing.xxl, vertical = Spacing.sm),
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth().testTag("chat-artifacts"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = Spacing.xxl),
        ) {
            items(artifacts, key = { it.key }) { artifact ->
                when (artifact) {
                    is Artifact.File ->
                        ArtifactRow(
                            icon = artifact.kind.icon(),
                            title = artifact.name,
                            subtitle = artifact.path.substringBeforeLast('/', ""),
                            onClick = { onOpenFile(artifact) },
                        )
                    is Artifact.Server ->
                        ArtifactRow(
                            icon = Icons.Outlined.Language,
                            title = artifact.url.removePrefix("http://").removePrefix("https://"),
                            subtitle = stringResource(R.string.artifacts_local_server),
                            onClick = { onOpenServer(artifact.url) },
                        )
                }
            }
        }
    }
}

@Composable
private fun ArtifactRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.fillMaxWidth().clickableRow(onClick),
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily = CodeFontFamily,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
    )
}

private fun Modifier.clickableRow(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)

/**
 * An artifact shown as what it is, with its source one tap away: a page or a drawing renders in a
 * sandboxed web view, a document as formatted text, a picture as a picture. Share hands the source
 * to another app.
 */
@Composable
internal fun ArtifactPreviewDialog(
    state: ArtifactPreviewState,
    onDismiss: () -> Unit,
) {
    val artifact = state.artifact
    var showSource by rememberSaveable(artifact.path) { mutableStateOf(false) }
    val loaded = state as? ArtifactPreviewState.Loaded
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("artifact-preview")) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = Spacing.xs, end = Spacing.xs, top = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onDismiss,
                ) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.activity_details_close)) }
                Icon(
                    artifact.kind.icon(),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    artifact.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = Spacing.sm),
                )
                if (loaded != null && !loaded.base64) {
                    IconButton(onClick = {
                        val send =
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, artifact.name)
                                putExtra(Intent.EXTRA_TEXT, loaded.content)
                            }
                        runCatching { context.startActivity(Intent.createChooser(send, artifact.name)) }
                    }) { Icon(Icons.Default.Share, contentDescription = stringResource(R.string.chat_menu_share)) }
                }
            }
            // Pictures have no source worth reading; everything else can flip to its text.
            if (artifact.kind != ArtifactKind.IMAGE) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm)) {
                    SegmentedButton(
                        selected = !showSource,
                        onClick = { showSource = false },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                        icon = {},
                    ) { Text(stringResource(R.string.artifact_preview)) }
                    SegmentedButton(
                        selected = showSource,
                        onClick = { showSource = true },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                        icon = {},
                    ) { Text(stringResource(R.string.artifact_source)) }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (state) {
                    is ArtifactPreviewState.Loading ->
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    is ArtifactPreviewState.Failed ->
                        Text(
                            text = state.message ?: stringResource(R.string.artifact_unavailable),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center).padding(Spacing.xxl),
                        )
                    is ArtifactPreviewState.Loaded ->
                        if (showSource && !state.base64) {
                            SourceView(state.content)
                        } else {
                            RenderedArtifact(state)
                        }
                }
            }
        }
    }
}

@Composable
private fun SourceView(content: String) {
    SelectionContainer {
        Text(
            text = content,
            fontFamily = CodeFontFamily,
            style = MaterialTheme.typography.bodySmall,
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .padding(Spacing.lg),
        )
    }
}

@Composable
private fun RenderedArtifact(state: ArtifactPreviewState.Loaded) {
    when (state.artifact.kind) {
        ArtifactKind.HTML -> ArtifactWebView(html = state.content)
        // An SVG on its own page, scaled to fit, on white: most drawings assume a light canvas.
        ArtifactKind.SVG ->
            ArtifactWebView(
                html =
                    "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
                        "<style>html,body{margin:0;height:100%;background:#fff;display:flex;align-items:center;justify-content:center}" +
                        "svg{max-width:100%;max-height:100%;height:auto}</style></head><body>${state.content}</body></html>",
            )
        ArtifactKind.MARKDOWN ->
            ProvideSafeUriHandler {
                Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.lg)) {
                    MarkdownText(state.content)
                }
            }
        ArtifactKind.IMAGE -> {
            val bitmap =
                remember(state.content) {
                    runCatching {
                        val bytes = if (state.base64) Base64.decode(state.content, Base64.DEFAULT) else state.content.toByteArray()
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                    }.getOrNull()
                }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = state.artifact.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(Spacing.lg),
                )
            } else {
                Text(
                    stringResource(R.string.artifact_unavailable),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(Spacing.xxl),
                )
            }
        }
    }
}

/**
 * A web view for one agent-written page. Scripts run, since interactive pages are the point, but
 * the page is cut off from the device: no file or content access, no JavaScript bridge, and links
 * stay inside the view rather than launching other apps.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ArtifactWebView(html: String) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                webViewClient = WebViewClient()
            }
        },
        update = { view -> view.loadDataWithBaseURL(ARTIFACT_BASE_URL, html, "text/html", "utf-8", null) },
    )
}

private const val ARTIFACT_BASE_URL = "https://artifact.andcode.invalid/"
