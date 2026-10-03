package com.yugahashimoto.andcode.core.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Decodes [bytes] downsampled so neither dimension exceeds [maxDimension].
 *
 * A camera or gallery photo can be 30-50+ MP; decoding it at full resolution just to show a chat
 * bubble thumbnail can allocate 100-200MB for a single bitmap - enough to OOM outright, or to blow
 * past the GPU's max texture size once Compose tries to draw it (#320).
 */
fun decodeSampledBitmap(
    bytes: ByteArray,
    maxDimension: Int,
): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) {
        sample *= 2
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}
