package com.yugahashimoto.andcode.ui.components

/** A page whose renderer Android ended, and the address Reload loads again in a fresh WebView. */
internal data class GonePage(
    val url: String,
)

/**
 * One screen's WebView across the deaths of its renderer, in plain Kotlin so it is tested on the JVM.
 *
 * Android can end the process that draws a WebView's pages, to free memory or because a page crashed
 * it. A WebView whose renderer is gone can never draw again, and unless the app says it handled that,
 * Android ends the whole app with it. So the screen takes the view out and destroys it ([rendererGone]),
 * shows that the page stopped responding, and on Reload builds a fresh view of the next [generation]
 * that loads [firstLoad] ([rebuild]).
 *
 * Nothing comes back on its own: a page that kills its renderer as it loads would otherwise do it
 * again and again, and a renderer Android ended to free memory is the worst thing to start straight
 * away.
 */
internal data class WebPageRecovery(
    /** Set from the moment the renderer is gone until a fresh view replaces the dead one. */
    val gone: GonePage? = null,
    /** What the view of this [generation] loads once it is built; blank for nothing. */
    val firstLoad: String = "",
    /** Counts the views built after a renderer died, to key each fresh view apart from the dead one. */
    val generation: Int = 0,
) {
    /** Whether a WebView belongs on screen: none while the page is gone, until the user asks for it back. */
    val showsView: Boolean get() = gone == null

    /**
     * The renderer of the current view is gone. Reload brings back [lastUrl], the page the dead view
     * last showed, or else [fallbackUrl], the address the screen was on; with neither a web page, it
     * builds an empty view. A second report keeps the first.
     */
    fun rendererGone(
        lastUrl: String?,
        fallbackUrl: String,
    ): WebPageRecovery {
        if (gone != null) return this
        val url = listOfNotNull(lastUrl, fallbackUrl).firstOrNull(::isWebPage).orEmpty()
        return copy(gone = GonePage(url))
    }

    /** A fresh view that loads [url]: by default the page that went away, as Reload does. */
    fun rebuild(url: String = gone?.url.orEmpty()): WebPageRecovery = copy(gone = null, firstLoad = url, generation = generation + 1)

    private companion object {
        /**
         * Only an http(s) page is brought back: not an empty `about:blank`, and never WebView's own
         * `chrome://crash` or `chrome://kill`, which a view may still report and which would only end
         * the fresh view's renderer too.
         */
        fun isWebPage(url: String): Boolean = url.substringBefore("://", "").lowercase() in setOf("http", "https")
    }
}
