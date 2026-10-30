package com.yugahashimoto.andcode.feature.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class PlatformViewTest {
    @Test
    fun `returns the view when the platform builds it`() {
        val view = Any()

        assertSame(view, viewOrNull(create = { view }))
    }

    @Test
    fun `returns null and reports the cause when the platform refuses to build the view`() {
        // The WebView provider's resource registration failed on a vivo device and surfaced as a
        // RuntimeException wrapping a Resources.NotFoundException.
        val refusal = RuntimeException("failed to redirect ResourcesImpl", IllegalStateException("not found"))
        val reported = mutableListOf<Throwable>()

        val view = viewOrNull<Any>(create = { throw refusal }, onFailure = { reported += it })

        assertNull(view)
        assertEquals(listOf<Throwable>(refusal), reported)
    }

    @Test
    fun `a provider whose native code fails to load is also a missing view`() {
        // The provider's classes and libraries can fail to load, which arrives as a LinkageError.
        val reported = mutableListOf<Throwable>()

        val view =
            viewOrNull<Any>(
                create = { throw UnsatisfiedLinkError("libwebviewchromium.so") },
                onFailure = { reported += it },
            )

        assertNull(view)
        assertEquals(1, reported.size)
    }

    @Test
    fun `does not hide an error that is not a missing view`() {
        assertThrows(OutOfMemoryError::class.java) {
            viewOrNull<Any>(create = { throw OutOfMemoryError() })
        }
    }
}
