package com.yugahashimoto.andcode.feature.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.core.ProvideSafeUriHandler
import com.yugahashimoto.andcode.core.api.PermissionRequest
import com.yugahashimoto.andcode.runtime.PermissionResponse
import com.yugahashimoto.andcode.ui.theme.AppShapes
import com.yugahashimoto.andcode.ui.theme.CodeFontFamily
import com.yugahashimoto.andcode.ui.theme.LocalThemeColors
import com.yugahashimoto.andcode.ui.theme.Spacing
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.material.icons.filled.Image as ImageIcon

private val TOOL_CALL_ECHO_REGEX =
    Regex("""Called the [A-Za-z][A-Za-z ]*? tool with the following input: \{(?:[^{}]|\{[^{}]*\})*\}""")

private fun String.hideToolCallEcho(): String = TOOL_CALL_ECHO_REGEX.replace(this, "").replace(Regex("[ \t]+\n"), "\n").trim()

@Composable
fun MessageBubble(
    message: ChatMessage,
    onImageClick: (ChatImageSource) -> Unit = {},
) {
    val displayText = remember(message.text) { message.text.hideToolCallEcho() }
    // Your own messages sit on a quiet tonal surface rather than the accent colour: in a coding
    // chat the agent's answer is what you read, so the prompt should frame it, not shout over it.
    // The time moves under the bubble, where it no longer pushes long prompts narrower.
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 48.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = Spacing.md)) {
                // Images persist in the transcript as base64 data-URL file parts, so once the
                // message is recorded they render straight from the attachment and survive the
                // reload that follows a completed send. The transient imagePreviews bitmaps only
                // stand in for the optimistic echo before the message reaches the transcript, or
                // for runtimes whose attachment URLs are not inlineable data URLs.
                val imageAttachments = message.attachments.filter { it.mime.startsWith("image/") }
                imageAttachments.forEachIndexed { index, attachment ->
                    ChatImageThumbnail(
                        source =
                            ChatImageSource(
                                attachment.url,
                                attachment.mime,
                                attachment.filename,
                                message.imagePreviews.getOrNull(index),
                            ),
                        modifier = Modifier.widthIn(max = 280.dp).heightIn(max = 220.dp).padding(bottom = 8.dp),
                        onImageClick = onImageClick,
                    )
                }
                if (imageAttachments.isEmpty()) {
                    message.imagePreviews.forEachIndexed { index, bitmap ->
                        val source = ChatImageSource("preview:${message.id}:$index", "image/jpeg", preview = bitmap)
                        ChatImageThumbnail(
                            source = source,
                            modifier = Modifier.widthIn(max = 280.dp).heightIn(max = 220.dp).padding(bottom = 8.dp),
                            onImageClick = onImageClick,
                        )
                    }
                }
                message.attachments.forEach { attachment ->
                    if (attachment.mime.startsWith("image/")) return@forEach
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(bottom = 8.dp),
                    ) {
                        Icon(Icons.Default.Description, contentDescription = stringResource(R.string.cd_attachment))
                        Text(
                            text = attachment.filename,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (displayText.isNotBlank()) {
                    LinkedText(
                        text = displayText,
                        linkColor = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        Text(
            text = formatClockTime(message.timestamp),
            style = MaterialTheme.typography.labelSmall,
            color = LocalThemeColors.current.foregroundExtraMuted,
            modifier = Modifier.padding(end = Spacing.xs),
        )
    }
}

@Composable
fun TimelineEntryRow(
    entry: TimelineEntry,
    onOpenActivity: (String) -> Unit = {},
    onImageClick: (ChatImageSource) -> Unit = {},
    onOpenProviderSettings: (() -> Unit)? = null,
    onOpenChanges: (ChatPart.Patch) -> Unit = {},
) {
    when (entry) {
        is TimelineEntry.UserMessage -> MessageBubble(entry.message, onImageClick)
        is TimelineEntry.Body -> MarkdownText(entry.part.text, onImageClick = onImageClick)
        is TimelineEntry.Image -> ImagePartView(entry.part, onImageClick)
        is TimelineEntry.Error -> ErrorPartCard(entry.part, onOpenProviderSettings)
        is TimelineEntry.Activity ->
            AssistantActivityRow(
                parts = entry.parts,
                onClick = { onOpenActivity(entry.id) },
            )
        is TimelineEntry.Todo -> TodoTimelineCard(entry.todos)
        is TimelineEntry.Changes -> ChangesCard(entry, onReview = { onOpenChanges(entry.patch) })
        is TimelineEntry.Footer -> MessageFooter(entry)
    }
}

/** An assistant turn that failed (provider error, retries exhausted, ...) surfaced in the chat. */
@Composable
private fun ErrorPartCard(
    part: ChatPart.Error,
    onOpenProviderSettings: (() -> Unit)?,
) {
    val zenFreeTier = classifyChatError(part.message) == ChatErrorKind.ZEN_FREE_TIER
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f)),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Text(
                    text =
                        stringResource(
                            if (zenFreeTier) {
                                R.string.chat_error_zen_free_tier_title
                            } else {
                                R.string.error_session_failed
                            },
                        ),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text =
                    if (zenFreeTier) {
                        stringResource(R.string.chat_error_zen_free_tier_body)
                    } else {
                        part.message
                    },
                color = MaterialTheme.colorScheme.onSurface,
                style = LocalTextStyle.current.copy(textDirection = TextDirection.Content),
            )
            if (zenFreeTier && onOpenProviderSettings != null) {
                Spacer(Modifier.height(12.dp))
                Button(onClick = onOpenProviderSettings) {
                    Text(stringResource(R.string.chat_error_zen_free_tier_action))
                }
            }
        }
    }
}

@Composable
fun PermissionCard(
    permission: PermissionRequest,
    onPermission: (String, PermissionResponse, Boolean) -> Unit,
) {
    // One glance, one thumb: what the agent wants to run, in a code block, then the two answers
    // you give most often side by side. "Always allow" is the rarer, broader grant, so it steps
    // down to a text button instead of being the biggest, brightest target on the card.
    val warningColor = LocalThemeColors.current.warning
    Surface(
        shape = AppShapes.lg,
        color = warningColor.copy(alpha = 0.07f),
        border = BorderStroke(1.dp, warningColor.copy(alpha = 0.35f)),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Icon(
                    Icons.Default.Security,
                    contentDescription = stringResource(R.string.cd_permission),
                    tint = warningColor,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    stringResource(R.string.permission_required),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Surface(shape = AppShapes.pill, color = warningColor.copy(alpha = 0.16f), contentColor = warningColor) {
                    Text(
                        text = permission.permission,
                        fontFamily = CodeFontFamily,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 3.dp),
                    )
                }
            }
            if (permission.patterns.isNotEmpty()) {
                Surface(
                    shape = AppShapes.sm,
                    color = MaterialTheme.colorScheme.background.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = Spacing.md, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        permission.patterns.forEach { pattern ->
                            Text(
                                text = pattern,
                                fontFamily = CodeFontFamily,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            } else {
                Text(
                    text = stringResource(R.string.permission_chat_confirmation),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Side by side, but free to wrap: "Allow once" runs long in Russian, French or Arabic,
            // and a clipped label on the button you press most is worse than a two-line one.
            Row(
                modifier = Modifier.height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                OutlinedButton(
                    onClick = { onPermission(permission.id, PermissionResponse.REJECT, false) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.sm),
                ) {
                    Text(stringResource(R.string.reject), textAlign = TextAlign.Center, maxLines = 2)
                }
                Button(
                    onClick = { onPermission(permission.id, PermissionResponse.ONCE, false) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.sm),
                ) {
                    Text(stringResource(R.string.allow_once), textAlign = TextAlign.Center, maxLines = 2)
                }
            }
            TextButton(
                onClick = { onPermission(permission.id, PermissionResponse.ALWAYS, true) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.always_allow))
            }
        }
    }
}

@Composable
private fun InlineText(
    inlines: List<MarkdownInline>,
    style: TextStyle,
    linkColor: Color,
    codeBackground: Color,
) {
    val annotated =
        remember(inlines, linkColor, codeBackground) {
            renderInline(inlines, codeBackground, linkColor)
        }
    // Agent-authored Markdown can contain file:// links: the default UriHandler would
    // forward those to another app and crash with FileUriExposedException (issue #300).
    ProvideSafeUriHandler {
        SelectionContainer {
            // Resolve paragraph direction from the content (first-strong heuristic) instead of
            // the app layout direction, so mixed Arabic/English text (issue #341) is not laid out
            // with a forced base direction that visually reverses line starts and ends.
            Text(text = annotated, style = style.copy(textDirection = TextDirection.Content))
        }
    }
}

@Composable
private fun RowScope.TableCellText(
    text: String,
    style: TextStyle,
    linkColor: Color,
    codeBackground: Color,
) {
    val inlines = remember(text) { MarkdownLite.parseInline(text) }
    Box(
        modifier =
            Modifier
                .weight(1f)
                .padding(end = 8.dp),
    ) {
        InlineText(
            inlines = inlines,
            style = style,
            linkColor = linkColor,
            codeBackground = codeBackground,
        )
    }
}

@Composable
private fun LinkedText(
    text: String,
    linkColor: Color,
) {
    val style = LocalTextStyle.current
    val inlines = remember(text) { MarkdownLite.parseInline(text) }
    val annotated =
        remember(inlines, linkColor) {
            buildAnnotatedString {
                // User text is shown plain (no emphasis styling), but a link nested inside emphasis
                // is still walked so `**http://localhost:4200**` remains tappable.
                fun appendPlain(nodes: List<MarkdownInline>) {
                    nodes.forEach { inline ->
                        when (inline) {
                            is MarkdownInline.Link ->
                                withLink(linkAnnotation(inline.url, linkColor)) { append(inline.text) }
                            is MarkdownInline.Bold -> appendPlain(inline.inlines)
                            is MarkdownInline.Italic -> appendPlain(inline.inlines)
                            is MarkdownInline.Strikethrough -> appendPlain(inline.inlines)
                            else -> append(inline.text)
                        }
                    }
                }
                appendPlain(inlines)
            }
        }
    // Same file:// guard as InlineText above (issue #300).
    ProvideSafeUriHandler {
        // Content-based paragraph direction for mixed RTL/LTR input (issue #341).
        Text(text = annotated, style = style.copy(textDirection = TextDirection.Content))
    }
}

@Composable
private fun MarkdownText(
    text: String,
    onImageClick: (ChatImageSource) -> Unit = {},
) {
    val blocks = remember(text) { MarkdownLite.parse(text) }
    val codeInlineBackground = MaterialTheme.colorScheme.surfaceVariant
    val linkColor = MaterialTheme.colorScheme.primary
    val bodyStyle =
        MaterialTheme.typography.bodyLarge.copy(
            color = MaterialTheme.colorScheme.onSurface,
        )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Heading ->
                    InlineText(
                        inlines = block.inlines,
                        style =
                            when (block.level) {
                                1 -> MaterialTheme.typography.titleLarge
                                2 -> MaterialTheme.typography.titleMedium
                                else -> MaterialTheme.typography.titleSmall
                            }.copy(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface),
                        linkColor = linkColor,
                        codeBackground = codeInlineBackground,
                    )
                is MarkdownBlock.Paragraph -> {
                    val currentInlines = mutableListOf<MarkdownInline>()

                    @Composable
                    fun flushInlines() {
                        if (currentInlines.isNotEmpty()) {
                            InlineText(
                                inlines = currentInlines.toList(),
                                style = bodyStyle,
                                linkColor = linkColor,
                                codeBackground = codeInlineBackground,
                            )
                            currentInlines.clear()
                        }
                    }
                    block.inlines.forEach { inline ->
                        if (inline is MarkdownInline.Image) {
                            flushInlines()
                            MarkdownImageView(inline, onImageClick)
                        } else {
                            currentInlines += inline
                        }
                    }
                    flushInlines()
                }
                is MarkdownBlock.CodeBlock ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ) {
                        Text(
                            text = block.code,
                            modifier =
                                Modifier
                                    .horizontalScroll(rememberScrollState())
                                    .padding(10.dp),
                            fontFamily = CodeFontFamily,
                            // Code is always laid out LTR, even in RTL app locales (issue #341).
                            style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr),
                        )
                    }
                is MarkdownBlock.BulletList ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        block.items.forEach { item ->
                            Row {
                                Text("•  ", color = MaterialTheme.colorScheme.onSurface)
                                InlineText(item, bodyStyle, linkColor, codeInlineBackground)
                            }
                        }
                    }
                is MarkdownBlock.OrderedList ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        block.items.forEachIndexed { index, item ->
                            Row {
                                Text("${index + 1}.  ", color = MaterialTheme.colorScheme.onSurface)
                                InlineText(item, bodyStyle, linkColor, codeInlineBackground)
                            }
                        }
                    }
                is MarkdownBlock.Blockquote ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ) {
                        Box(
                            modifier =
                                Modifier.padding(
                                    start = 10.dp,
                                    end = 8.dp,
                                    top = 6.dp,
                                    bottom = 6.dp,
                                ),
                        ) {
                            InlineText(
                                inlines = block.inlines,
                                style = bodyStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                                linkColor = linkColor,
                                codeBackground = codeInlineBackground,
                            )
                        }
                    }
                is MarkdownBlock.Table ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ) {
                        Column(
                            modifier = Modifier.padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(modifier = Modifier.fillMaxWidth()) {
                                block.headers.forEach { header ->
                                    TableCellText(
                                        text = header,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                        linkColor = linkColor,
                                        codeBackground = codeInlineBackground,
                                    )
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                            block.rows.forEach { row ->
                                val padded =
                                    row.take(block.headers.size) +
                                        List((block.headers.size - row.size).coerceAtLeast(0)) { "" }
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    padded.forEach { cell ->
                                        TableCellText(
                                            text = cell,
                                            style = MaterialTheme.typography.bodyMedium,
                                            linkColor = linkColor,
                                            codeBackground = codeInlineBackground,
                                        )
                                    }
                                }
                            }
                        }
                    }
                is MarkdownBlock.HorizontalRule ->
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                    )
            }
        }
    }
}

internal data class DataImage(val mime: String, val base64: String)

internal fun parseDataImageUri(url: String): DataImage? {
    if (!url.startsWith("data:")) return null
    val payload = url.removePrefix("data:")
    val comma = payload.indexOf(',')
    if (comma < 0) return null
    val meta = payload.substring(0, comma)
    if (!meta.endsWith(";base64")) return null
    val mime = meta.removeSuffix(";base64")
    if (!mime.startsWith("image/")) return null
    return DataImage(mime, payload.substring(comma + 1))
}

private fun decodeDataImage(url: String): android.graphics.Bitmap? {
    val data = parseDataImageUri(url) ?: return null
    return runCatching {
        val bytes = android.util.Base64.decode(data.base64, android.util.Base64.DEFAULT)
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()
}

internal fun imageFileCandidates(
    url: String,
    workspaceHostDir: File,
    rootfsDirs: List<File> = emptyList(),
): List<File> {
    if (url.startsWith("data:") || url.startsWith("http://") || url.startsWith("https://")) {
        return emptyList()
    }
    val raw = if (url.startsWith("file://")) url.removePrefix("file://") else url
    return buildList {
        when {
            raw == "/workspace" -> add(workspaceHostDir)
            raw.startsWith("/workspace/") -> add(File(workspaceHostDir, raw.removePrefix("/workspace/")))
        }
        if (raw.startsWith("/")) {
            rootfsDirs.forEach { rootfs -> add(File(rootfs, raw.removePrefix("/"))) }
        }
        add(File(raw))
        if (!raw.startsWith("/")) add(File(workspaceHostDir, raw))
    }
        .distinctBy { it.path }
}

internal fun resolveImageFile(
    url: String,
    workspaceHostDir: File,
    rootfsDirs: List<File> = emptyList(),
): File? = imageFileCandidates(url, workspaceHostDir, rootfsDirs).firstOrNull { it.exists() }

internal fun decodeImageFromUrlOrPath(
    url: String,
    workspaceHostDir: File,
    rootfsDirs: List<File> = emptyList(),
): Bitmap? {
    if (url.startsWith("data:")) {
        return decodeDataImage(url)
    }
    val path = resolveImageFile(url, workspaceHostDir, rootfsDirs)?.absolutePath ?: return null
    return runCatching {
        val options =
            BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
        BitmapFactory.decodeFile(path, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null

        val maxDim = 1024
        var sampleSize = 1
        while (options.outWidth / sampleSize > maxDim || options.outHeight / sampleSize > maxDim) {
            sampleSize *= 2
        }
        val decodeOptions =
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
            }
        BitmapFactory.decodeFile(path, decodeOptions)
    }.getOrNull()
}

@Composable
private fun MarkdownImageView(
    image: MarkdownInline.Image,
    onImageClick: (ChatImageSource) -> Unit,
) {
    ChatImageThumbnail(
        source = ChatImageSource(image.url, "image/*", image.text.ifBlank { null }),
        modifier = Modifier.widthIn(max = 320.dp).heightIn(max = 320.dp).padding(vertical = 4.dp),
        onImageClick = onImageClick,
    )
}

@Composable
private fun ImagePartView(
    part: ChatPart.Image,
    onImageClick: (ChatImageSource) -> Unit,
) {
    ChatImageThumbnail(
        source = ChatImageSource(part.url, part.mime, part.filename),
        modifier = Modifier.widthIn(max = 320.dp).heightIn(max = 320.dp),
        onImageClick = onImageClick,
    )
}

@Composable
private fun ChatImageThumbnail(
    source: ChatImageSource,
    modifier: Modifier,
    onImageClick: (ChatImageSource) -> Unit,
) {
    val context = LocalContext.current
    val bitmapState =
        produceState(initialValue = source.preview, source.url) {
            value = loadChatImageBitmap(context, source)
        }
    val bitmap = bitmapState.value
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = source.filename ?: stringResource(R.string.cd_image_preview),
            modifier = modifier.testTag("chat-image-thumbnail").clickable { onImageClick(source.copy(preview = bitmap)) },
            contentScale = ContentScale.Fit,
        )
    } else {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            modifier = modifier.testTag("chat-image-thumbnail").clickable { onImageClick(source) },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(10.dp),
            ) {
                Icon(Icons.Default.ImageIcon, contentDescription = null)
                Text(
                    text = source.filename ?: source.mime,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * A [LinkAnnotation.Url] Compose's own text-link handling can dispatch without a competing
 * `pointerInput`, so it does not swallow gestures a long-press ancestor (e.g. the message bubble's
 * copy/edit action sheet) needs to see - see [LinkedText] for the bug this avoids.
 */
private fun linkAnnotation(
    url: String,
    color: Color,
): LinkAnnotation.Url =
    LinkAnnotation.Url(
        url,
        TextLinkStyles(style = SpanStyle(color = color, textDecoration = TextDecoration.Underline)),
    )

private fun renderInline(
    inlines: List<MarkdownInline>,
    codeBackground: Color,
    linkColor: Color,
): AnnotatedString =
    buildAnnotatedString {
        // Emphasis is rendered by recursing into its parsed children, so a link nested inside bold
        // or italic (e.g. `**http://localhost:4200**`) keeps its link annotation - see
        // MarkdownInline.Bold.
        fun appendInlines(nodes: List<MarkdownInline>) {
            nodes.forEach { inline ->
                when (inline) {
                    is MarkdownInline.Plain -> append(inline.text)
                    is MarkdownInline.Bold ->
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInlines(inline.inlines) }
                    is MarkdownInline.Italic ->
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInlines(inline.inlines) }
                    is MarkdownInline.Strikethrough ->
                        withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { appendInlines(inline.inlines) }
                    is MarkdownInline.Code -> {
                        val start = length
                        withStyle(
                            SpanStyle(fontFamily = CodeFontFamily, background = codeBackground),
                        ) { append(inline.text) }
                        addStringAnnotation("code", inline.text, start, length)
                    }
                    is MarkdownInline.Link -> {
                        withLink(linkAnnotation(inline.url, linkColor)) { append(inline.text) }
                    }
                    is MarkdownInline.Image -> {
                        withLink(linkAnnotation(inline.url, linkColor)) {
                            append(inline.text.ifBlank { "[Image]" })
                        }
                    }
                }
            }
        }
        appendInlines(inlines)
    }

private val clockTimeFormat = SimpleDateFormat("HH:mm", Locale.US)

private fun formatClockTime(epochMs: Long): String = clockTimeFormat.format(Date(epochMs))

@Composable
private fun MessageFooter(entry: TimelineEntry.Footer) {
    val minutes = entry.durationMs / 60_000L
    val durationLabel =
        when {
            entry.durationMs <= 0L -> null
            minutes < 1L -> stringResource(R.string.chat_response_duration_under_minute)
            else -> stringResource(R.string.chat_response_duration, minutes)
        }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
    ) {
        if (durationLabel != null) {
            Text(
                text = durationLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (entry.completedAt > 0L) {
            Text(
                text = formatClockTime(entry.completedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
