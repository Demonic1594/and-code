package com.yugahashimoto.andcode.runtime.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import com.yugahashimoto.andcode.core.api.PromptAttachment
import java.io.ByteArrayOutputStream
import java.io.File

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
        val original =
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Cannot open attachment input stream" }
                input.readBytes()
            }
        val (bytes, sentMime) = runCatching { downscaleImage(original, mime) }.getOrNull() ?: (original to mime)
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return listOf(
            PromptAttachment(
                filename = filename,
                mime = sentMime,
                url = "data:$sentMime;base64,$encoded",
            ),
        )
    }

    /**
     * Writes [attachment]'s decoded bytes into `[workspaceDir]/.andcode-attachments` and returns
     * the destination file.
     *
     * Used for MIME types a provider would reject as a file part (see [AttachmentSupport]): the file
     * lands in the workspace so the agent can read it with its own file tools instead.
     */
    fun writeToWorkspace(
        attachment: PromptAttachment,
        workspaceDir: File,
    ): File {
        val encoded = attachment.url.substringAfter(";base64,", missingDelimiterValue = "")
        check(attachment.url.startsWith("data:") && encoded.isNotEmpty()) {
            "Attachment has no inline content to copy"
        }
        val directory =
            File(workspaceDir, ATTACHMENT_DIRECTORY).apply {
                check(mkdirs() || isDirectory) { "Cannot create attachment directory" }
            }
        val destination = uniqueDestination(directory, attachment.filename)
        destination.writeBytes(Base64.decode(encoded, Base64.DEFAULT))
        return destination
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

    /**
     * Shrinks a photo to [MAX_IMAGE_EDGE] on its long side, or returns null to send it as it is.
     *
     * A phone camera produces 12+ megapixel images. Claude rescales anything larger than this on its
     * side, so the extra pixels only cost upload time there; Gemini and many OpenCode providers tile a
     * large image and bill every tile, and the image is re-sent with the history on every later turn.
     * EXIF orientation is applied while re-encoding, since the re-encoded file no longer carries it.
     * PNG (and anything transparent) stays PNG so screenshots keep crisp text; the rest becomes JPEG.
     */
    private fun downscaleImage(
        bytes: ByteArray,
        mime: String,
    ): Pair<ByteArray, String>? {
        if (mime !in DOWNSCALABLE_IMAGE_MIMES) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val rotation = exifRotationDegrees(bytes)
        val (width, height) = fitWithin(bounds.outWidth, bounds.outHeight, MAX_IMAGE_EDGE)
        if (width == bounds.outWidth && height == bounds.outHeight && rotation == 0) return null
        val decoded =
            BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, MAX_IMAGE_EDGE) },
            ) ?: return null
        val matrix =
            Matrix().apply {
                postScale(width.toFloat() / decoded.width, height.toFloat() / decoded.height)
                if (rotation != 0) postRotate(rotation.toFloat())
            }
        val scaled = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        // A transparent WebP would turn black-backed as JPEG, so anything with alpha stays lossless.
        val png = mime == "image/png" || decoded.hasAlpha()
        val output = ByteArrayOutputStream()
        check(scaled.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, DOWNSCALE_JPEG_QUALITY, output))
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()
        val result = output.toByteArray()
        // Rotation alone must still win even if re-encoding grew the file: sideways is worse than big.
        if (rotation == 0 && result.size >= bytes.size) return null
        return result to if (png) "image/png" else "image/jpeg"
    }

    private fun exifRotationDegrees(bytes: ByteArray): Int =
        runCatching {
            when (
                ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            ) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        }.getOrDefault(0)

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

    private fun uniqueDestination(
        directory: File,
        rawName: String,
    ): File {
        val name = sanitize(rawName)
        if (!File(directory, name).exists()) return File(directory, name)
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        var index = 1
        while (true) {
            val candidate = File(directory, "$stem-$index$extension")
            if (!candidate.exists()) return candidate
            index++
        }
    }
}

/** Workspace subdirectory unsendable attachments are copied into. */
internal const val ATTACHMENT_DIRECTORY = ".andcode-attachments"

/** Long-edge cap for image attachments; Claude's own resize target, and a 2x2 tile grid for Gemini. */
internal const val MAX_IMAGE_EDGE = 1568
private const val DOWNSCALE_JPEG_QUALITY = 85
private val DOWNSCALABLE_IMAGE_MIMES = setOf("image/jpeg", "image/png", "image/webp")

/** [width] x [height] scaled to fit [maxEdge] on the long side, keeping the aspect ratio; never enlarged. */
internal fun fitWithin(
    width: Int,
    height: Int,
    maxEdge: Int,
): Pair<Int, Int> {
    val longEdge = maxOf(width, height)
    if (longEdge <= maxEdge) return width to height
    val scale = maxEdge.toDouble() / longEdge
    return maxOf(1, Math.round(width * scale).toInt()) to maxOf(1, Math.round(height * scale).toInt())
}

/** The largest power-of-two subsample that still leaves the long edge at least [maxEdge]. */
internal fun sampleSizeFor(
    width: Int,
    height: Int,
    maxEdge: Int,
): Int {
    var sample = 1
    while (maxOf(width, height) / (sample * 2) >= maxEdge) sample *= 2
    return sample
}
