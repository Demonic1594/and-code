package com.yugahashimoto.andcode.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A file part can carry a multi-megabyte `data:` URL, and its MIME type used to be found by copying
 * that payload, up to twice per call: in [parseDataImageUri], and, when that returned null, in the
 * extension fallback of [imageMime], whose `lowercase()` asked for a 76 MB allocation in a
 * Crashlytics OOM. These tests pin that the type comes from the header alone.
 */
class DataImageMimeTest {
    /** Records how far into the text a caller reads and whether it ever copies a large span. */
    private class WatchedChars(private val value: String) : CharSequence {
        var highestIndexRead = -1
        var largeCopies = 0

        override val length: Int get() = value.length

        override fun get(index: Int): Char {
            highestIndexRead = maxOf(highestIndexRead, index)
            return value[index]
        }

        override fun subSequence(
            startIndex: Int,
            endIndex: Int,
        ): CharSequence {
            highestIndexRead = maxOf(highestIndexRead, endIndex - 1)
            if (endIndex - startIndex > HEADER_BUDGET) largeCopies++
            return value.subSequence(startIndex, endIndex)
        }

        override fun toString(): String {
            largeCopies++
            return value
        }
    }

    private val payload = "A".repeat(2_000_000)

    @Test
    fun `reads an image mime from the header without touching the payload`() {
        val url = WatchedChars("data:image/png;base64,$payload")

        assertEquals("image/png", dataImageMime(url))
        assertTrue("read ${url.highestIndexRead} chars", url.highestIndexRead < HEADER_BUDGET)
        assertEquals(0, url.largeCopies)
    }

    @Test
    fun `rejects a non image data url without reading its payload`() {
        val url = WatchedChars("data:text/plain;base64,$payload")

        assertNull(dataImageMime(url))
        assertTrue("read ${url.highestIndexRead} chars", url.highestIndexRead < HEADER_BUDGET)
        assertEquals(0, url.largeCopies)
    }

    @Test
    fun `a header that never ends is rejected after reading only the header length`() {
        val url = WatchedChars("data:image/png;base64$payload")

        assertNull(dataImageMime(url))
        assertTrue("read ${url.highestIndexRead} chars", url.highestIndexRead < HEADER_BUDGET)
        assertEquals(0, url.largeCopies)
    }

    @Test
    fun `a header longer than the limit is not read as an image type`() {
        val url = "data:image/png;name=${"x".repeat(1100)};base64,AAAA"

        assertNull(dataImageMime(url))
        assertNull(imageMime(null, url))
        assertNull(parseDataImageUri(url))
    }

    @Test
    fun `the saved file extension follows the data url header`() {
        assertTrue(safeImageFilename(ChatImageSource("data:image/webp;base64,AAAA", "image/*")).endsWith(".webp"))
        assertTrue(safeImageFilename(ChatImageSource("data:text/plain,notes.png", "image/*")).endsWith(".png"))
    }

    @Test
    fun `rejects a data url that is not base64 or has no header end`() {
        assertNull(dataImageMime("data:image/png,abc"))
        assertNull(dataImageMime("data:image/png;base64"))
        assertNull(dataImageMime("https://example.com/shot.png"))
    }

    @Test
    fun `imageMime takes the mime of an undeclared data url from its header`() {
        assertEquals("image/webp", imageMime(null, "data:image/webp;base64,AAAA"))
    }

    @Test
    fun `imageMime does not look for a file extension inside a data url`() {
        // The payload ends in ".png", which the extension fallback used to read as a PNG.
        assertNull(imageMime(null, "data:text/plain,notes.png"))
        assertNull(imageMime(null, "data:application/octet-stream;base64,$payload.png"))
    }

    @Test
    fun `imageMime still reads the extension of an ordinary url`() {
        assertEquals("image/png", imageMime(null, "https://example.com/shot.PNG?size=2#top"))
        assertEquals("image/jpeg", imageMime(null, "/workspace/photo.jpg"))
        assertNull(imageMime(null, "https://example.com/readme.md"))
    }

    @Test
    fun `a declared image mime wins over the url`() {
        assertEquals("image/gif", imageMime("image/gif", "data:image/png;base64,AAAA"))
    }

    @Test
    fun `parseDataImageUri still returns the mime and the whole payload`() {
        val parsed = parseDataImageUri("data:image/png;base64,$payload")

        assertEquals("image/png", parsed?.mime)
        assertEquals(payload, parsed?.base64)
    }

    private companion object {
        const val HEADER_BUDGET = 1024
    }
}
