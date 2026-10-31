package com.yugahashimoto.andcode.core

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import java.net.URI
import java.util.Locale

/**
 * Single choke point for opening URLs in an external app.
 *
 * Chat messages render agent-authored Markdown, and a link such as
 * `[workspace](file:///workspace/my-workspace)` used to reach Compose's default
 * [UriHandler], which fires `ACTION_VIEW` with the raw `file://` URI. Android 7+
 * rejects that with [android.os.FileUriExposedException] and the app crashed
 * (issue #300). Only `http`/`https` URLs are ever handed to another app now;
 * anything else is ignored.
 */
object UrlLauncher {
    private const val TAG = "UrlLauncher"
    private val SCHEME_REGEX = Regex("[a-zA-Z][a-zA-Z0-9+.-]*")

    // Pure-Kotlin scheme check (no android.net.Uri, so plain JVM tests can cover it).
    // Only http/https are ever handed to another app; file://, content://, intent://,
    // javascript:, data: and bare paths are all refused.
    fun isOpenableUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return false
        val colon = trimmed.indexOf(':')
        if (colon <= 0) return false
        val scheme = trimmed.substring(0, colon)
        if (!scheme.matches(SCHEME_REGEX)) return false
        val normalized = scheme.lowercase(Locale.ROOT)
        if (normalized != "http" && normalized != "https") return false
        // Require the authority form ("http://..."), not "http:foo".
        return trimmed.regionMatches(colon + 1, "//", 0, 2, ignoreCase = false)
    }

    /**
     * Opens [url] in an external app when it is an http(s) URL.
     *
     * @return true when an external activity was started, false otherwise.
     */
    fun openUrl(
        context: Context,
        url: String,
    ): Boolean {
        if (!isOpenableUrl(url)) {
            Log.w(TAG, "Refusing to open non-http(s) URL")
            return false
        }
        return runCatching {
            val intent =
                Intent(Intent.ACTION_VIEW, Uri.parse(url.trim())).apply {
                    // Callers pass activity and application contexts alike.
                    if (context !is Activity) {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }
            context.startActivity(intent)
            true
        }.onFailure { error ->
            Log.w(TAG, "Unable to open URL", error)
        }.getOrDefault(false)
    }
}

/**
 * A [UriHandler] that only opens http(s) URLs, guarding every
 * `LinkAnnotation.Url` click rendered from agent-authored Markdown.
 */
private class SafeUriHandler(
    private val context: Context,
    private val openLocal: ((String) -> Unit)?,
) : UriHandler {
    override fun openUri(uri: String) {
        // A server on this device (a dev server the agent started) opens in the app's own browser,
        // which can reach it; another app's browser usually cannot. GitHub links open there too, so
        // a PR or issue the agent links to can be read without leaving the chat.
        if (openLocal != null && (isLocalServerUrl(uri) || isGitHubUrl(uri))) {
            openLocal(uri)
        } else {
            UrlLauncher.openUrl(context, uri)
        }
    }
}

/** Where links to a server on this device, and GitHub links, open when something on screen can show them. */
val LocalServerUrlOpener = staticCompositionLocalOf<((String) -> Unit)?> { null }

// The host must end where the URL's host ends: "localhost.example.com" and "127.0.0.1.nip.io" are
// sites on the internet, and a six-digit "port" is no port. A full stop ending the sentence is not
// part of the host.
private val LOCAL_SERVER_URL =
    Regex(
        """https?://(localhost|127\.0\.0\.1|0\.0\.0\.0|\[::1])(?::\d{2,5})?(?![\w\-@\\]|[.:]\w)(?:[/?#][^\s)\]>"'`]*)?""",
        RegexOption.IGNORE_CASE,
    )

private object GitHubLinks {
    val hosts = setOf("github.com", "www.github.com", "gist.github.com")

    /** Downloads, which the in-app browser cannot save: a release asset, a source archive, a raw file. */
    val downloadPath = Regex("/(releases/download|archive|raw)/")
}

/** True for an https page on GitHub or its gists; downloads are left to the phone's browser. */
fun isGitHubUrl(url: String): Boolean =
    runCatching {
        val uri = URI(url.trim())
        uri.scheme == "https" &&
            uri.host?.lowercase() in GitHubLinks.hosts &&
            !GitHubLinks.downloadPath.containsMatchIn(uri.path.orEmpty())
    }.getOrDefault(false)

/** True for a URL that points at a server on this device rather than the internet. */
fun isLocalServerUrl(url: String): Boolean = LOCAL_SERVER_URL.matchEntire(url.trim()) != null

/** The local server URLs in [text], each once, with trailing sentence punctuation dropped. */
fun localServerUrls(text: String): List<String> =
    LOCAL_SERVER_URL.findAll(text)
        .map { match ->
            val url = match.value.trimEnd('.', ',', ';', ':', '!', '?')
            // 0.0.0.0 is where a server listens, not an address to open: it is on 127.0.0.1.
            val host = match.groups[1]!!.range
            if (match.groupValues[1] == "0.0.0.0") {
                url.replaceRange(host.first - match.range.first, host.last - match.range.first + 1, "127.0.0.1")
            } else {
                url
            }
        }
        .distinct()
        .toList()

/**
 * Wraps [content] so that Markdown link clicks anywhere below only ever open
 * http(s) URLs externally instead of crashing on `file://` URIs.
 */
@Composable
fun ProvideSafeUriHandler(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val openLocal = LocalServerUrlOpener.current
    val handler = remember(context, openLocal) { SafeUriHandler(context, openLocal) }
    CompositionLocalProvider(LocalUriHandler provides handler, content = content)
}
