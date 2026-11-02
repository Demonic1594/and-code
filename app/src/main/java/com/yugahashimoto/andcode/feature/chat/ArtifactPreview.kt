package com.yugahashimoto.andcode.feature.chat

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.BitmapFactory
import android.util.Base64
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.PhoneAndroid
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
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.core.ProvideSafeUriHandler
import com.yugahashimoto.andcode.core.UrlLauncher
import com.yugahashimoto.andcode.feature.browser.DESKTOP_VIEWPORT_SCRIPT
import com.yugahashimoto.andcode.ui.theme.CodeFontFamily
import com.yugahashimoto.andcode.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
            modifier = Modifier.padding(horizontal = Spacing.xxl, vertical = Spacing.sm).semantics { heading() },
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth().testTag("chat-artifacts"),
            contentPadding = PaddingValues(bottom = Spacing.xxl),
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
                    is Artifact.Server -> {
                        val address = artifact.url.removePrefix("http://").removePrefix("https://")
                        ArtifactRow(
                            icon = Icons.Outlined.Language,
                            title = artifact.name ?: address,
                            subtitle =
                                if (artifact.name != null) {
                                    stringResource(R.string.artifacts_kept_server, address)
                                } else {
                                    stringResource(R.string.artifacts_local_server)
                                },
                            onClick = { onOpenServer(artifact.url) },
                        )
                    }
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
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
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
    var desktopLayout by rememberSaveable(artifact.path) { mutableStateOf(false) }
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
                // A page can be seen the way a laptop would lay it out, zoomed out to fit.
                if (artifact.kind == ArtifactKind.HTML && !showSource) {
                    IconToggleButton(checked = desktopLayout, onCheckedChange = { desktopLayout = it }) {
                        Icon(
                            if (desktopLayout) Icons.Default.DesktopWindows else Icons.Default.PhoneAndroid,
                            contentDescription = stringResource(R.string.guest_browser_desktop_site),
                        )
                    }
                }
                if (loaded != null && !loaded.base64) {
                    IconButton(onClick = {
                        val send =
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, artifact.name)
                                putExtra(Intent.EXTRA_TEXT, loaded.content)
                            }
                        runCatching { context.startActivity(Intent.createChooser(send, artifact.name)) }
                    }) { Icon(Icons.Default.Share, contentDescription = stringResource(R.string.artifact_share)) }
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
                            RenderedArtifact(state, desktopLayout)
                        }
                }
            }
        }
    }
}

/**
 * The file's text, one lazy row per line with its number in a gutter. One `Text` for the whole file
 * stops at the tallest layout Compose can measure, so a long file was cut off part way down.
 */
@Composable
private fun SourceView(content: String) {
    val lines = remember(content) { content.lines() }
    val numberStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = CodeFontFamily)
    // Measured, not guessed, so the widest number fits at any font size without wrapping.
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val gutterWidth =
        remember(lines.size, numberStyle, density) {
            with(density) { measurer.measure("9".repeat(lines.size.toString().length), numberStyle).size.width.toDp() }
        }
    // Code reads left to right, gutter first, whatever the app's language.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        SelectionContainer {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = Spacing.sm),
            ) {
                items(lines.size) { index ->
                    Row(modifier = Modifier.fillMaxWidth().padding(end = Spacing.md)) {
                        // Line numbers are not part of the file: a copied selection leaves them out.
                        DisableSelection {
                            Text(
                                text = (index + 1).toString(),
                                style = numberStyle,
                                color = MaterialTheme.colorScheme.outline,
                                textAlign = TextAlign.End,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(horizontal = Spacing.sm).width(gutterWidth),
                            )
                        }
                        Text(
                            text = lines[index],
                            fontFamily = CodeFontFamily,
                            style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RenderedArtifact(
    state: ArtifactPreviewState.Loaded,
    desktopLayout: Boolean,
) {
    when (state.artifact.kind) {
        ArtifactKind.HTML -> ArtifactWebView(html = state.content, path = state.artifact.path, desktopLayout = desktopLayout)
        // An SVG on its own page, scaled to fit, on white: most drawings assume a light canvas.
        ArtifactKind.SVG ->
            ArtifactWebView(
                path = state.artifact.path,
                html =
                    "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
                        "<style>html,body{margin:0;height:100%;background:#fff;display:flex;align-items:center;justify-content:center}" +
                        "svg{max-width:100%;max-height:100%;height:auto}</style></head><body>${state.content}</body></html>",
            )
        ArtifactKind.MARKDOWN ->
            ProvideSafeUriHandler {
                LazyMarkdownDocument(
                    text = state.content,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(Spacing.lg),
                )
            }
        ArtifactKind.IMAGE -> {
            // Decoded off the main thread and scaled to the screen: an agent can write a picture
            // far larger than a canvas will draw, and drawing it full size would crash the app.
            val decoded by produceState<Pair<Boolean, ImageBitmap?>>(false to null, state.content) {
                value = true to withContext(Dispatchers.Default) { decodeArtifactImage(state.content, state.base64) }
            }
            val (done, bitmap) = decoded
            if (!done) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (bitmap != null) {
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
private fun ArtifactWebView(
    html: String,
    path: String,
    desktopLayout: Boolean = false,
) {
    val context = LocalContext.current
    // An origin of its own per file: storage a page keeps is not another artifact's to read.
    val baseUrl = remember(path) { "https://a${path.hashCode().toUInt().toString(16)}.artifact.andcode.invalid/" }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { viewContext ->
            WebView(viewContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                // Pinch to zoom, without the legacy on-screen zoom buttons.
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                webViewClient =
                    object : WebViewClient() {
                        // The preview shows the agent's page and nothing else: a link or script
                        // that would take it elsewhere opens that site in the browser instead, so a
                        // page cannot dress itself up as the app on a screen with no address bar.
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ): Boolean {
                            val url = request.url.toString()
                            if (url.startsWith(baseUrl)) return false
                            // Only a tap opens the browser: a page redirecting itself on load or on a
                            // timer must not launch apps over and over.
                            if (request.isForMainFrame && request.hasGesture()) UrlLauncher.openUrl(context, url)
                            return true
                        }

                        override fun onPageFinished(
                            view: WebView,
                            url: String?,
                        ) {
                            if (view.settings.useWideViewPort) view.evaluateJavascript(DESKTOP_VIEWPORT_SCRIPT, null)
                        }
                    }
            }
        },
        update = { view ->
            // Desktop lays the page out at a laptop's width and zooms out to fit; the flag is read
            // back from the settings when the page finishes, to widen its viewport tag too.
            view.settings.useWideViewPort = desktopLayout
            view.settings.loadWithOverviewMode = desktopLayout
            view.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null)
        },
        onRelease = { view -> view.destroy() },
    )
}

/** Longest side, in pixels, a previewed picture is decoded at. */
private const val MAX_IMAGE_SIDE = 2048

/** [content] as a picture no larger than the screen needs, or null when it is not one. */
internal fun decodeArtifactImage(
    content: String,
    base64: Boolean,
): ImageBitmap? =
    runCatching {
        val bytes = if (base64) Base64.decode(content, Base64.DEFAULT) else content.toByteArray()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_IMAGE_SIDE) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }.getOrNull()
