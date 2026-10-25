package com.yugahashimoto.andcode.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.InputStream
import java.util.Arrays

class ReadLimitedTest {
    @Test
    fun `a stream at the size limit is read in full`() {
        assertEquals(LIMIT, readLimited(zeros(LIMIT)).size)
    }

    @Test
    fun `a stream one byte over the size limit is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { readLimited(zeros(LIMIT + 1)) }
    }

    @Test
    fun `an empty stream reads as no bytes`() {
        assertEquals(0, readLimited(zeros(0)).size)
    }

    /** A stream of [count] zero bytes that never allocates them, so the test can cross the limit cheaply. */
    private fun zeros(count: Int): InputStream =
        object : InputStream() {
            private var left = count

            override fun read(): Int = if (left > 0) 0.also { left-- } else -1

            override fun read(
                buffer: ByteArray,
                offset: Int,
                length: Int,
            ): Int {
                if (left == 0) return -1
                val read = minOf(length, left)
                Arrays.fill(buffer, offset, offset + read, 0)
                left -= read
                return read
            }
        }

    private companion object {
        /** Mirrors the private `MAX_IMAGE_BYTES` in `ChatImageViewer.kt`. */
        const val LIMIT = 25 * 1024 * 1024
    }
}
