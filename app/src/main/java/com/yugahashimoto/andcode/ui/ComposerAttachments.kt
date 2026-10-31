package com.yugahashimoto.andcode.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import com.yugahashimoto.andcode.core.api.PromptAttachment
import com.yugahashimoto.andcode.core.util.decodeSampledBitmap
import com.yugahashimoto.andcode.feature.chat.COMPOSER_PREVIEW_MAX_DIMENSION
import com.yugahashimoto.andcode.runtime.local.AttachmentImporter
import com.yugahashimoto.andcode.runtime.local.AttachmentSupport
import com.yugahashimoto.andcode.runtime.local.VideoAttachmentHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Reads what the user picked for the composer, each with an image preview when it is an image.
 *
 * [workspacePath] is the chat's workspace when the on-device runtime is selected, and null for a
 * remote runtime: only the on-device runtime mounts `/workspace` onto a local directory. A remote
 * runtime may also spell its workspace `/workspace`, but there is no local mount to copy into, so
 * its attachments are always sent as they are.
 */
internal suspend fun importComposerAttachments(
    context: Context,
    uri: Uri,
    workspacePath: String?,
    localRuntime: Boolean,
): List<Pair<PromptAttachment, Bitmap?>> =
    withContext(Dispatchers.IO) {
        val importer = AttachmentImporter(context)
        val runtimeWorkspaceRoot = File(context.filesDir, "runtime/workspace")
        val workspaceDir =
            if (localRuntime) AttachmentSupport.hostWorkspace(workspacePath, runtimeWorkspaceRoot) else null
        importer.importAll(uri).map { attachment ->
            // A MIME the provider rejects as a file part (zip, binaries, ...): drop a copy into the
            // workspace and let the agent read it from disk. If the copy fails, fall back to sending
            // the attachment as before.
            val guestPath =
                workspaceDir
                    ?.takeIf { !AttachmentSupport.isModelSendable(attachment.mime) }
                    ?.let { directory ->
                        runCatching { importer.writeToWorkspace(attachment, directory) }
                            .getOrNull()
                            ?.let { AttachmentSupport.guestWorkspacePath(it, runtimeWorkspaceRoot) }
                    }
            if (guestPath != null) {
                attachment.copy(url = "", workspacePath = guestPath) to null
            } else {
                attachment to imagePreview(attachment)
            }
        }
    }

/** A small bitmap of an image attachment for the composer, or null for anything else. */
private fun imagePreview(attachment: PromptAttachment): Bitmap? {
    if (!VideoAttachmentHelper.isImageMime(attachment.mime)) return null
    return runCatching {
        val base64 = attachment.url.substringAfter("base64,", missingDelimiterValue = "")
        if (base64.isEmpty()) return@runCatching null
        decodeSampledBitmap(Base64.decode(base64, Base64.DEFAULT), COMPOSER_PREVIEW_MAX_DIMENSION)
    }.getOrNull()
}
