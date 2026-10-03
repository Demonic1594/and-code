package com.yugahashimoto.andcode.runtime.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import com.yugahashimoto.andcode.core.api.PromptAttachment
import com.yugahashimoto.andcode.core.util.decodeSampledBitmap
import java.io.ByteArrayInputStream
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
        val details = queryDetails(uri)
        val filename = sanitize(details.name ?: "attachment-${System.currentTimeMillis()}")
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        if (VideoAttachmentHelper.isVideoMime(mime)) {
            return importVideoFrames(uri, filename)
        }
        // Re-encodable images are bounded by [reencodeSampled] whatever their size; anything
        // else goes into the prompt as raw base64, and a few hundred megabytes of it - a big
        // PDF, an export zip - was read fully into RAM (twice: bytes, then the 1.33x string)
        // before anything could object. Providers reject the oversized part anyway; refusing
        // here keeps the app alive and tells the user why.
        if (!mime.startsWith("image/") && (details.sizeBytes ?: 0L) > MAX_RAW_ATTACHMENT_BYTES) {
            throw IllegalArgumentException(
                "Attachment is too large to send (${(details.sizeBytes ?: 0L) / 1_000_000} MB); limit is ${MAX_RAW_ATTACHMENT_BYTES / 1_000_000} MB",
            )
        }
        val bytes =
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Cannot open attachment input stream" }
                input.readBytes()
            }
        // The pre-read size comes from the provider and can be missing or wrong; the bytes on
        // hand cannot lie. This also catches an oversized "image" that failed to re-encode and
        // would otherwise fall through to the raw path.
        if (bytes.size > MAX_RAW_ATTACHMENT_BYTES) {
            throw IllegalArgumentException(
                "Attachment is too large to send (${bytes.size / 1_000_000} MB); limit is ${MAX_RAW_ATTACHMENT_BYTES / 1_000_000} MB",
            )
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
     * Decodes [bytes] at most [ATTACHMENT_IMAGE_MAX_DIMENSION] per side and re-encodes, or null
     * when the bytes are not a decodable image - the caller keeps the original then.
     *
     * The EXIF orientation is applied before encoding: BitmapFactory ignores it, so without this
     * every re-encoded camera photo (which is to say every photo - they are all over the size
     * threshold) went to the model, and into the transcript, rotated 90 or 180 degrees.
     */
    private fun reencodeSampled(
        bytes: ByteArray,
        filename: String,
    ): PromptAttachment? {
        val bitmap = decodeSampledBitmap(bytes, ATTACHMENT_IMAGE_MAX_DIMENSION) ?: return null
        try {
            val oriented = applyExifRotation(bytes, bitmap)
            try {
                // Transparency composites to black under JPEG; vision models have no use for it,
                // so a white matte keeps line art and screenshots legible.
                val flattened = flattenOnWhite(oriented)
                try {
                    val output = ByteArrayOutputStream()
                    if (!flattened.compress(Bitmap.CompressFormat.JPEG, 90, output)) return null
                    return PromptAttachment(
                        filename = "${filename.substringBeforeLast('.')}.jpg",
                        mime = "image/jpeg",
                        url = "data:image/jpeg;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP),
                    )
                } finally {
                    if (flattened !== oriented) flattened.recycle()
                }
            } finally {
                if (oriented !== bitmap) oriented.recycle()
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** Returns [bitmap] drawn over an opaque white background when it carries transparency. */
    private fun flattenOnWhite(bitmap: Bitmap): Bitmap {
        if (!bitmap.hasAlpha()) return bitmap
        return runCatching {
            val flattened = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(flattened)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(bitmap, 0f, 0f, null)
            flattened
        }.getOrDefault(bitmap)
    }

    /** Returns [bitmap] rotated per the JPEG's EXIF orientation, or [bitmap] when upright or unknown. */
    private fun applyExifRotation(
        bytes: ByteArray,
        bitmap: Bitmap,
    ): Bitmap {
        val orientation =
            runCatching {
                ExifInterface(ByteArrayInputStream(bytes))
                    .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val degrees =
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        if (degrees == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(degrees) }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrDefault(bitmap)
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

    private data class AttachmentDetails(
        val name: String? = null,
        /** The provider-reported size, when it reports one; providers may lie or omit it. */
        val sizeBytes: Long? = null,
    )

    private fun queryDetails(uri: Uri): AttachmentDetails =
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use AttachmentDetails()
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                AttachmentDetails(
                    name = if (nameIndex >= 0 && !cursor.isNull(nameIndex)) cursor.getString(nameIndex) else null,
                    sizeBytes = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null,
                )
            } ?: AttachmentDetails()
        }.getOrDefault(AttachmentDetails())

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

        /**
         * Ceiling for anything embedded into the prompt without re-encoding (documents, archives,
         * undecodable "images"). Sized to stay survivable as ~2x + 1.33x heap while a model could
         * still plausibly make use of the content; beyond it the provider would reject the part
         * anyway.
         */
        private const val MAX_RAW_ATTACHMENT_BYTES = 30L * 1024L * 1024L
    }
}
