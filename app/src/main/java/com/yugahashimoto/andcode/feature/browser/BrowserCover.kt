package com.yugahashimoto.andcode.feature.browser

/** What the Guest Browser shows over its page area, if anything. */
internal enum class BrowserCover {
    /** The page itself. */
    NONE,

    /** The system WebView cannot start, which the app cannot repair. */
    WEBVIEW_UNAVAILABLE,

    /** Android ended the page's renderer: it stopped responding, with Reload. */
    PAGE_GONE,

    /** The page could not be loaded, with Try again. */
    LOAD_ERROR,

    /** Nothing opened yet: the start screen. */
    START,
}

/**
 * Which cover wins. A page that is gone outranks a load error left from before it (Reload starts over
 * either way) and the start screen (there is a page to bring back); only a WebView that cannot start
 * at all outranks it.
 */
internal fun browserCover(
    webViewUnavailable: Boolean,
    pageGone: Boolean,
    loadError: Boolean,
    address: String,
    pageStarted: Boolean,
): BrowserCover =
    when {
        webViewUnavailable -> BrowserCover.WEBVIEW_UNAVAILABLE
        pageGone -> BrowserCover.PAGE_GONE
        loadError -> BrowserCover.LOAD_ERROR
        address.isBlank() && !pageStarted -> BrowserCover.START
        else -> BrowserCover.NONE
    }
