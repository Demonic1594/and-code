package com.yugahashimoto.andcode.feature.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserDesktopModeTest {
    @Test
    fun `a WebView user agent becomes desktop Chrome's`() {
        val webView =
            "Mozilla/5.0 (Linux; Android 14; Pixel 7 Build/UQ1A.240205.004; wv) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Version/4.0 Chrome/124.0.6367.82 Mobile Safari/537.36"

        assertEquals(
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.6367.82 Safari/537.36",
            desktopUserAgent(webView),
        )
    }

    @Test
    fun `a user agent without the Android markers is left as it is`() {
        val desktop = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
        assertEquals(desktop, desktopUserAgent(desktop))
    }

    @Test
    fun `the viewport script sets the desktop width`() {
        assertTrue(DESKTOP_VIEWPORT_SCRIPT.contains("width=$DESKTOP_VIEWPORT_WIDTH"))
    }

    @Test
    fun `typed addresses become http urls`() {
        assertEquals("http://127.0.0.1:5173", normalizeBrowserUrl(" 127.0.0.1:5173 "))
        assertEquals("https://example.com/a", normalizeBrowserUrl("https://example.com/a"))
        assertEquals("http://localhost:3000/", normalizeBrowserUrl("http://localhost:3000/"))
        assertEquals("http://127.0.0.1", normalizeBrowserUrl(""))
    }
}
