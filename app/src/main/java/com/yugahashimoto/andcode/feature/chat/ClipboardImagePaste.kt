package com.yugahashimoto.andcode.feature.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.TextToolbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val IMAGE_MIME_PATTERN = "image/*"
private const val IMAGE_MIME_PREFIX = "image/"

/**
 * Whether a paste should attach the clipboard's image rather than insert its text.
 *
 * Text wins when both are present: a copied web selection can carry an image alongside its text,
 * and replacing the paste the user expects with an attachment would be the surprise.
 */
internal fun shouldPasteImage(
    hasText: Boolean,
    hasImage: Boolean,
): Boolean = hasImage && !hasText

/** Ctrl+V, or Shift+Insert, as a hardware keyboard sends them. */
internal fun isPasteShortcut(
    key: Key,
    ctrl: Boolean,
    shift: Boolean,
): Boolean = (ctrl && key == Key.V) || (shift && key == Key.Insert)

/**
 * Adds "Paste" to the text field's selection toolbar when the clipboard holds an image.
 *
 * The field only offers Paste for text, so a copied screenshot gave the long-press menu nothing to
 * paste. When the field offers no paste of its own and [canPasteImage] holds, this supplies one
 * that calls [onPasteImage]; a paste the field does offer, and every other action, pass through.
 */
internal class ImagePasteTextToolbar(
    private val delegate: TextToolbar,
    private val canPasteImage: () -> Boolean,
    private val onPasteImage: () -> Unit,
) : TextToolbar by delegate {
    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        val paste =
            onPasteRequested
                ?: if (canPasteImage()) {
                    {
                        onPasteImage()
                        delegate.hide()
                    }
                } else {
                    null
                }
        delegate.showMenu(rect, onCopyRequested, paste, onCutRequested, onSelectAllRequested)
    }
}

private fun Context.clipboard(): ClipboardManager? = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

/**
 * Whether the primary clip holds an image. Reads only the clip's description, which unlike its
 * content does not make Android 12+ tell the user that the app has read their clipboard.
 */
internal fun Context.clipboardHasImage(): Boolean =
    runCatching { clipboard()?.primaryClipDescription?.hasMimeType(IMAGE_MIME_PATTERN) == true }
        .getOrDefault(false)

/** The primary clip, or null when the clipboard is empty or cannot be read. */
internal fun Context.primaryClipOrNull(): ClipData? = runCatching { clipboard()?.primaryClip }.getOrNull()

/**
 * The content URIs on this clip that hold an image; empty when there are none.
 *
 * Asking a URI for its type calls into the app that copied it, which may be slow to answer, so
 * this runs off the main thread. Only `content://` URIs count: the clip's owner chooses the URI,
 * and a `file://` one could point at this app's own files.
 */
internal suspend fun ClipData.imageUris(resolver: ContentResolver): List<Uri> =
    withContext(Dispatchers.IO) {
        (0 until itemCount)
            .mapNotNull { getItemAt(it).uri }
            .filter { uri -> uri.scheme == ContentResolver.SCHEME_CONTENT && resolver.isImage(uri) }
    }

private fun ContentResolver.isImage(uri: Uri): Boolean =
    runCatching { getType(uri)?.startsWith(IMAGE_MIME_PREFIX) == true }.getOrDefault(false)
