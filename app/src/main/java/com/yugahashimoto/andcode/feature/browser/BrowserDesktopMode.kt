package com.yugahashimoto.andcode.feature.browser

/** CSS width a page is laid out at in desktop mode: a common laptop width. */
internal const val DESKTOP_VIEWPORT_WIDTH = 1280

// The platform part of an Android WebView's user agent, e.g. `(Linux; Android 14; Pixel 7 Build/UQ1A; wv)`.
private val ANDROID_PLATFORM = Regex("""\(Linux; Android[^)]*\)""")

/**
 * [mobile], a WebView's own user agent, as a desktop Chrome would send it: Linux desktop for the
 * platform, without the WebView `Version/4.0` token and the `Mobile` marker sites test for.
 */
internal fun desktopUserAgent(mobile: String): String =
    mobile
        .replace(ANDROID_PLATFORM, "(X11; Linux x86_64)")
        .replace(Regex("""Version/[\d.]+ """), "")
        .replace(" Mobile Safari/", " Safari/")

/**
 * Script that lays the page out at [DESKTOP_VIEWPORT_WIDTH] whatever its viewport tag says. A
 * responsive page (most dev servers) asks for `width=device-width` and would otherwise keep its
 * phone layout, however desktop the user agent looks.
 */
internal val DESKTOP_VIEWPORT_SCRIPT =
    """
    (function() {
      var meta = document.querySelector('meta[name="viewport"]');
      if (!meta) {
        meta = document.createElement('meta');
        meta.name = 'viewport';
        (document.head || document.documentElement).appendChild(meta);
      }
      meta.setAttribute('content', 'width=$DESKTOP_VIEWPORT_WIDTH');
    })();
    """.trimIndent()

/**
 * What the user typed in the address bar, as a URL to load: `http://` added when no scheme is
 * given, and anything that is not http(s) replaced by the guest's loopback address.
 */
internal fun normalizeBrowserUrl(raw: String): String {
    val trimmed = raw.trim()
    val candidate = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "http://$trimmed"
    return candidate.takeIf { it.substringBefore("://") in setOf("http", "https") && it.length > it.indexOf("://") + 3 }
        ?: "http://127.0.0.1"
}
