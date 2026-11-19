package com.yugahashimoto.andcode.feature.browser

import android.net.Uri
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.yugahashimoto.andcode.ui.components.PageStoppedResponding
import com.yugahashimoto.andcode.ui.components.WebPageRecovery
import com.yugahashimoto.andcode.ui.components.destroyWebView
import com.yugahashimoto.andcode.ui.components.discardAfterRendererGone

private const val TAG = "GuestWebContent"

/**
 * A WebView for a page the conversation's inline preview shows: a dev server on this device, or a
 * static site from the workspace served through [interceptRequest].
 *
 * It gets the Guest Browser's settings ([applyGuestBrowserSettings]: no file or content access), and
 * nothing else of it: it never turns the DevTools socket on, so the agent's browser tools do not
 * drive it, and the handoff stays the Guest Browser's. The view is destroyed when it leaves the
 * composition, so a dismissed preview keeps no rendering surface alive.
 *
 * Like every WebView of the app (see [WebPageRecovery]), it survives Android ending its renderer:
 * the dead view is discarded and "This page stopped responding" offers Reload, which builds a fresh
 * one. Nothing reloads on its own.
 */
@Composable
fun GuestWebContent(
    url: String,
    modifier: Modifier = Modifier,
    onWebViewCreated: (WebView?) -> Unit = {},
    onWebViewDisposed: ((WebView) -> Unit)? = null,
    interceptRequest: ((Uri) -> WebResourceResponse?)? = null,
    onProgress: (Int) -> Unit = {},
    onNavigationStateChange: (url: String, canGoBack: Boolean, canGoForward: Boolean) -> Unit = { _, _, _ -> },
    onUnavailable: (Throwable) -> Unit = {},
) {
    var recovery by remember {
        mutableStateOf(
            WebPageRecovery(firstLoad = url.takeIf { it.isNotBlank() }?.let(::normalizeGuestUrl).orEmpty()),
        )
    }
    val currentOnCreated by rememberUpdatedState(onWebViewCreated)
    val currentOnDisposed by rememberUpdatedState(onWebViewDisposed)
    val currentOnProgress by rememberUpdatedState(onProgress)
    val currentOnStateChange by rememberUpdatedState(onNavigationStateChange)
    val currentOnUnavailable by rememberUpdatedState(onUnavailable)
    val currentIntercept by rememberUpdatedState(interceptRequest)

    if (!recovery.showsView) {
        PageStoppedResponding(onReload = { recovery = recovery.rebuild() }, modifier = modifier)
        return
    }
    // Each fresh view after a renderer died is a new generation, so Compose builds it anew.
    key(recovery.generation) {
        AndroidView(
            modifier = modifier,
            factory = { context ->
                // Only starting the system WebView provider can fail for reasons the app cannot fix;
                // everything after it runs on a WebView that exists, so a failure there is a real bug
                // and must not leave a half-built view behind.
                val created =
                    viewOrNull(
                        create = { WebView(context) },
                        onFailure = { error ->
                            Log.w(TAG, "The system WebView could not start", error)
                            currentOnUnavailable(error)
                        },
                    )
                created?.apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    applyGuestBrowserSettings()
                    webChromeClient =
                        object : WebChromeClient() {
                            override fun onProgressChanged(
                                view: WebView?,
                                newProgress: Int,
                            ) = currentOnProgress(newProgress)
                        }
                    webViewClient =
                        object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView,
                                request: WebResourceRequest,
                            ): WebResourceResponse? = currentIntercept?.invoke(request.url) ?: super.shouldInterceptRequest(view, request)

                            override fun onPageFinished(
                                view: WebView?,
                                url: String?,
                            ) {
                                currentOnProgress(100)
                                currentOnStateChange(url.orEmpty(), view?.canGoBack() == true, view?.canGoForward() == true)
                            }

                            // Unhandled, Android would end the whole app with the renderer.
                            override fun onRenderProcessGone(
                                view: WebView?,
                                detail: RenderProcessGoneDetail?,
                            ): Boolean {
                                val dead = this@apply
                                recovery = recovery.rendererGone(lastUrl = dead.url, fallbackUrl = recovery.firstLoad)
                                currentOnCreated(null)
                                dead.discardAfterRendererGone(detail)
                                return true
                            }
                        }
                    currentOnCreated(this)
                    recovery.firstLoad.takeIf { it.isNotBlank() }?.let(::loadUrl)
                } ?: View(context)
            },
            onRelease = { view ->
                destroyWebView(view)
                val disposed = view as? WebView
                val customDispose = currentOnDisposed
                if (disposed != null && customDispose != null) customDispose(disposed) else currentOnCreated(null)
            },
        )
    }
}

/** Prefixes a bare host:port with a scheme and falls back to the guest's loopback address. */
internal fun normalizeGuestUrl(raw: String): String {
    val trimmed = raw.trim()
    val candidate = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "http://$trimmed"
    return candidate.takeIf { android.net.Uri.parse(it).scheme in setOf("http", "https") } ?: "http://127.0.0.1"
}
