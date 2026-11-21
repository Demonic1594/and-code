package com.yugahashimoto.andcode.feature.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserCoverTest {
    private fun cover(
        webViewUnavailable: Boolean = false,
        pageGone: Boolean = false,
        loadError: Boolean = false,
        address: String = "https://example.com/",
        pageStarted: Boolean = true,
    ) = browserCover(webViewUnavailable, pageGone, loadError, address, pageStarted)

    @Test
    fun `a live page is shown with nothing over it`() {
        assertEquals(BrowserCover.NONE, cover())
    }

    @Test
    fun `a page whose renderer is gone says so, over a load error left from before`() {
        assertEquals(BrowserCover.PAGE_GONE, cover(pageGone = true))
        assertEquals(BrowserCover.PAGE_GONE, cover(pageGone = true, loadError = true))
    }

    @Test
    fun `a page gone before anything loaded still offers to reload rather than the start screen`() {
        assertEquals(BrowserCover.PAGE_GONE, cover(pageGone = true, address = "", pageStarted = false))
    }

    @Test
    fun `a WebView that cannot start at all outranks everything`() {
        assertEquals(BrowserCover.WEBVIEW_UNAVAILABLE, cover(webViewUnavailable = true, pageGone = true, loadError = true))
    }

    @Test
    fun `a load error and the start screen keep their places`() {
        assertEquals(BrowserCover.LOAD_ERROR, cover(loadError = true))
        assertEquals(BrowserCover.START, cover(address = "", pageStarted = false))
        assertEquals(BrowserCover.NONE, cover(address = "", pageStarted = true))
    }
}
