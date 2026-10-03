package com.yugahashimoto.andcode.runtime.local

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import com.yugahashimoto.andcode.core.api.PromptAttachment
import com.yugahashimoto.andcode.core.util.decodeSampledBitmap
import java.io.ByteArrayOutputStream

class AttachmentImporter(
    private val context: Context,
) {
    /**
     * Imports [uri] as a single attachment. For video this keeps only the first
     * extracted frame; use [importAll] to receive every frame.
     */
    fun import(uri: Uri): PromptAttachment = importAll(uri).first()

    /**
     * Imports [uri] as one or more model-sendable attachments.
     *
     * Video files are not sent raw: most providers reject video file parts, so
     * representative JPEG frames are extracted instead. Everything else keeps the
     * previous single-attachment behaviour.
     */
    fun importAll(uri: Uri): List<PromptAttachment> {
        val filename = sanitize(queryDisplayName(uri) ?: "attachment-${System.currentTimeMillis()}")
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        if (VideoAttachmentHelper.isVideoMime(mime)) {
            return importVideoFrames(uri, filename)
        }
        val bytes =
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Cannot open attachment input stream" }
                input.readBytes()
            }
        // An image straight off the camera can be tens of megabytes, and it goes to the model as
        // base64 - an even bigger string that every later transcript read carries around forever.
        // Vision providers downscale server-side anyway, so a photo larger than the threshold is
        // re-encoded within the dimension the models keep. Small images (icons, screenshots,
        // diagrams - where every pixel and the original format can matter) and non-images pass
        // through untouched.
        val attachment =
            if (mime.startsWith("image/") && mime != "image/gif" && bytes.size > IMAGE_REENCODE_THRESHOLD_BYTES) {
                reencodeSampled(bytes, filename)
            } else {
                null
            }
                ?: PromptAttachment(
                    filename = filename,
                    mime = mime,
                    url = "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP),
                )
        return listOf(attachment)
    }

    /**
     * Decodes [bytes] at most [ATTACHMENT_IMAGE_MAX_DIMENSION] per side and re-encodes as JPEG, or
     * null when the bytes are not a decodable image - the caller keeps the original then.
     */
    private fun reencodeSampled(
        bytes: ByteArray,
        filename: String,
    ): PromptAttachment? {
        val bitmap = decodeSampledBitmap(bytes, ATTACHMENT_IMAGE_MAX_DIMENSION) ?: return null
        try {
            val output = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)) return null
            return PromptAttachment(
                filename = "${filename.substringBeforeLast('.')}.jpg",
                mime = "image/jpeg",
                url = "data:image/jpeg;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP),
            )
        } finally {
            bitmap.recycle()
        }
    }

    fun import(
        bitmap: Bitmap,
        filename: String = "image-${System.currentTimeMillis()}.jpg",
    ): PromptAttachment {
        val baos = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, baos)) { "Cannot encode attachment" }
        val encoded = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        return PromptAttachment(sanitize(filename), "image/jpeg", "data:image/jpeg;base64,$encoded")
    }

    private fun importVideoFrames(
        uri: Uri,
        filename: String,
    ): List<PromptAttachment> {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val durationMs =
                runCatching {
                    retriever
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: 0L
                }.getOrDefault(0L)
            val timestampsUs = VideoAttachmentHelper.frameTimestampsUs(durationMs * 1_000L)
            val frames =
                timestampsUs.mapIndexedNotNull { index, timestampUs ->
                    val frame =
                        runCatching {
                            retriever.getFrameAtTime(
                                timestampUs,
                                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            )
                        }.getOrNull() ?: return@mapIndexedNotNull null
                    try {
                        val (scaledWidth, scaledHeight) =
                            VideoAttachmentHelper.scaledDimensions(frame.width, frame.height)
                        if (scaledWidth <= 0 || scaledHeight <= 0) return@mapIndexedNotNull null
                        val scaled =
                            if (scaledWidth == frame.width && scaledHeight == frame.height) {
                                frame
                            } else {
                                Bitmap.createScaledBitmap(frame, scaledWidth, scaledHeight, true)
                            }
                        try {
                            val baos = ByteArrayOutputStream()
                            check(
                                scaled.compress(Bitmap.CompressFormat.JPEG, 80, baos),
                            ) { "Cannot encode video frame" }
                            val encoded = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                            PromptAttachment(
                                filename = VideoAttachmentHelper.frameFilename(filename, index),
                                mime = "image/jpeg",
                                url = "data:image/jpeg;base64,$encoded",
                            )
                        } finally {
                            if (scaled !== frame) scaled.recycle()
                        }
                    } finally {
                        frame.recycle()
                    }
                }
            require(frames.isNotEmpty()) { "Could not extract images from video" }
            return frames
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            Log.w("AttachmentImporter", "Failed to extract video frames", e)
            throw IllegalArgumentException("Could not extract images from video", e)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun queryDisplayName(uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()

    private fun sanitize(name: String): String = name.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "attachment" }

    private companion object {
        /**
         * Images larger than this are re-encoded down to [ATTACHMENT_IMAGE_MAX_DIMENSION] before
         * they are base64-encoded into a prompt: 2048px is above what the vision models keep, so
         * nothing the model can see is lost, while the payload — and every transcript that carries
         * it afterwards — shrinks by an order of magnitude for a camera photo.
         */
        private const val ATTACHMENT_IMAGE_MAX_DIMENSION = 2048

        /** Small images pass through untouched; only oversized ones pay the re-encode. */
        private const val IMAGE_REENCODE_THRESHOLD_BYTES = 1_500_000
    }
}
