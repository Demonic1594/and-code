package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageDownscaleTest {
    @Test
    fun `fits the long edge and keeps the aspect ratio`() {
        assertEquals(1568 to 1176, fitWithin(4000, 3000, 1568))
        assertEquals(882 to 1568, fitWithin(2250, 4000, 1568))
    }

    @Test
    fun `never enlarges a small image`() {
        assertEquals(800 to 600, fitWithin(800, 600, 1568))
    }

    @Test
    fun `subsamples by powers of two without going under the target`() {
        assertEquals(2, sampleSizeFor(4000, 3000, 1568))
        assertEquals(4, sampleSizeFor(8000, 6000, 1568))
        assertEquals(1, sampleSizeFor(2000, 1500, 1568))
    }
}
