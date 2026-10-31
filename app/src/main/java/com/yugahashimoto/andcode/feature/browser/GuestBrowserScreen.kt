package com.yugahashimoto.andcode.feature.browser

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.yugahashimoto.andcode.BuildConfig
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.core.UrlLauncher
import com.yugahashimoto.andcode.feature.browser.devtools.AndroidDevToolsMessages
import com.yugahashimoto.andcode.feature.browser.devtools.DevToolsBridge
import com.yugahashimoto.andcode.feature.browser.devtools.DevToolsPanel
import com.yugahashimoto.andcode.feature.browser.devtools.DevToolsStore
import kotlinx.coroutines.launch

/** Why the page could not be shown, for the error screen in its place. */
internal data class PageLoadError(
    val url: String,
    val description: String,
)

private const val TAG = "GuestBrowser"

/**
 * In-app browser for pages served inside the guest runtime (e.g. http://127.0.0.1:PORT/) and the
 * web at large: an address bar on top, controls at the bottom within thumb reach, and DevTools
 * docked between them on demand.
 */
@Composable
fun GuestBrowserScreen(
    initialUrl: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val linkCopied = stringResource(R.string.guest_browser_link_copied)
    val bridge = remember { DevToolsBridge(DevToolsStore(AndroidDevToolsMessages(context)), DevToolsBridge.loadScript(context)) }
    val problems by bridge.store.problems.collectAsState()

    var currentUrl by rememberSaveable { mutableStateOf(initialUrl) }
    var title by remember { mutableStateOf("") }
    var progress by remember { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var webViewUnavailable by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<PageLoadError?>(null) }
    // The start screen gives way to the first page that loads, whoever loads it (the agent may).
    var pageStarted by remember { mutableStateOf(false) }
    var desktopMode by rememberSaveable { mutableStateOf(false) }
    var findOpen by rememberSaveable { mutableStateOf(false) }
    var findQuery by rememberSaveable { mutableStateOf("") }
    var findMatch by remember { mutableStateOf(FindMatch()) }
    var editingAddress by rememberSaveable { mutableStateOf(initialUrl.isBlank()) }
    var devToolsOpen by rememberSaveable { mutableStateOf(false) }
    var devToolsHeight by remember { mutableStateOf<Dp?>(null) }
    val loading = progress in 1..99

    fun closeFind() {
        findOpen = false
        findQuery = ""
        findMatch = FindMatch()
        webView?.clearMatches()
    }

    fun open(raw: String) {
        val url = normalizeBrowserUrl(raw)
        editingAddress = false
        loadError = null
        currentUrl = url
        webView?.loadUrl(url)
    }

    BackHandler {
        val view = webView
        when {
            editingAddress && currentUrl.isNotBlank() -> editingAddress = false
            findOpen -> closeFind()
            devToolsOpen -> devToolsOpen = false
            view != null && view.canGoBack() -> view.goBack()
            else -> onBack()
        }
    }

    // The mode is applied to the live view and the page loaded again in it, so a switch shows the
    // same page the other way rather than only taking effect on the next link.
    LaunchedEffect(webView, desktopMode) {
        val view = webView ?: return@LaunchedEffect
        if (view.isDesktopMode != desktopMode) {
            view.applyViewMode(desktopMode)
            if (!view.url.isNullOrBlank()) view.reload()
        }
    }
    DisposableEffect(Unit) { onDispose { bridge.detach() } }

    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                BrowserAddressBar(
                    url = currentUrl,
                    title = title,
                    editing = editingAddress,
                    loading = loading,
                    onEditingChange = { editingAddress = it },
                    onSubmit = ::open,
                    onClose = onBack,
                    onReload = {
                        loadError = null
                        webView?.reload()
                    },
                    onStop = { webView?.stopLoading() },
                )
                Box(modifier = Modifier.fillMaxWidth().height(2.dp)) {
                    if (loading) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxSize())
                }
                AnimatedVisibility(visible = findOpen) {
                    FindInPageBar(
                        query = findQuery,
                        match = findMatch,
                        onQueryChange = {
                            findQuery = it
                            if (it.isEmpty()) {
                                findMatch = FindMatch()
                                webView?.clearMatches()
                            } else {
                                webView?.findAllAsync(it)
                            }
                        },
                        onPrevious = { webView?.findNext(false) },
                        onNext = { webView?.findNext(true) },
                        onClose = ::closeFind,
                    )
                }
                BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    val pageArea = maxHeight
                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            AndroidView(
                                // WebView can draw its background outside its measured bounds, covering the
                                // address bar even while that bar still accepts focus and keyboard input.
                                modifier = Modifier.fillMaxSize().clipToBounds(),
                                factory = { viewContext ->
                                    // Only starting the system WebView provider can fail for reasons the app
                                    // cannot fix; everything after it runs on a WebView that exists, so a failure
                                    // there is a real bug and must not leave a half-built view behind.
                                    val created =
                                        viewOrNull(
                                            create = {
                                                // Lets the in-guest agent drive this WebView over CDP (the devtools
                                                // abstract socket is reachable from the guest, which shares the app's
                                                // UID), so a page can be shown to the user and operated by human and
                                                // agent at the same time.
                                                WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
                                                WebView(viewContext)
                                            },
                                            onFailure = { error -> Log.w(TAG, "The system WebView could not start", error) },
                                        )
                                    if (created == null) {
                                        webViewUnavailable = true
                                        return@AndroidView View(viewContext)
                                    }
                                    created.apply {
                                        layoutParams =
                                            ViewGroup.LayoutParams(
                                                ViewGroup.LayoutParams.MATCH_PARENT,
                                                ViewGroup.LayoutParams.MATCH_PARENT,
                                            )
                                        configure(
                                            onProgress = { progress = it },
                                            onTitle = { title = it },
                                            onPageStarted = { url ->
                                                loadError = null
                                                // The first page, if the agent opened it while the empty address
                                                // field was waiting for input, takes over from the field.
                                                if (!pageStarted && currentUrl.isBlank()) editingAddress = false
                                                pageStarted = true
                                                if (!editingAddress) currentUrl = url
                                                bridge.onPageStarted()
                                            },
                                            onNavigationStateChange = { url, back, forward ->
                                                if (!editingAddress) currentUrl = url
                                                canGoBack = back
                                                canGoForward = forward
                                            },
                                            onPageLoaded = { view -> if (findOpen && findQuery.isNotEmpty()) view.findAllAsync(findQuery) },
                                            onMainFrameError = { url, description -> loadError = PageLoadError(url, description) },
                                        )
                                        setFindListener { active, count, _ -> findMatch = FindMatch(active, count) }
                                        applyViewMode(desktopMode)
                                        // Before the first load, so the page agent is in place for its first line.
                                        bridge.attach(this)
                                        webView = this
                                        if (initialUrl.isNotBlank()) loadUrl(normalizeBrowserUrl(initialUrl))
                                    }
                                },
                                onRelease = { view ->
                                    bridge.detach()
                                    (view as? WebView)?.destroy()
                                },
                            )
                            when {
                                webViewUnavailable -> WebViewUnavailableNotice(modifier = Modifier.fillMaxSize())
                                loadError != null ->
                                    PageErrorState(
                                        error = loadError!!,
                                        onRetry = {
                                            loadError = null
                                            webView?.reload()
                                        },
                                    )
                                currentUrl.isBlank() && !pageStarted -> BrowserStartState(onOpen = ::open)
                            }
                        }
                        if (devToolsOpen) {
                            val height = devToolsHeight ?: (pageArea * 0.45f)
                            DevToolsPanel(
                                bridge = bridge,
                                height = height,
                                onHeightChange = { devToolsHeight = it.coerceIn(160.dp, pageArea - 80.dp) },
                                onClose = { devToolsOpen = false },
                            )
                        }
                    }
                }
                BrowserBottomBar(
                    canGoBack = canGoBack,
                    canGoForward = canGoForward,
                    desktopMode = desktopMode,
                    devToolsOpen = devToolsOpen,
                    errorCount = problems.errors,
                    warningCount = problems.warnings,
                    onHistoryBack = { webView?.goBack() },
                    onForward = { webView?.goForward() },
                    onToggleDevTools = { devToolsOpen = !devToolsOpen },
                    onToggleDesktop = { desktopMode = !desktopMode },
                    actions =
                        BrowserMenuActions(
                            onFind = { findOpen = true },
                            onZoomIn = { webView?.zoomIn() },
                            onZoomOut = { webView?.zoomOut() },
                            onHardReload = {
                                webView?.apply {
                                    clearCache(true)
                                    reload()
                                }
                            },
                            onCopyLink = {
                                webView?.url?.takeIf { it.isNotBlank() }?.let { url ->
                                    clipboard.setText(AnnotatedString(url))
                                    scope.launch { snackbar.showSnackbar(linkCopied) }
                                }
                            },
                            onShare = {
                                webView?.url?.takeIf { it.isNotBlank() }?.let { url ->
                                    val send =
                                        Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, url)
                                        }
                                    runCatching { context.startActivity(Intent.createChooser(send, null)) }
                                }
                            },
                            onOpenExternal = { webView?.url?.let { UrlLauncher.openUrl(context, it) } },
                        ),
                )
            }
            SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        }
    }
}

/** Shown instead of the page when the system WebView cannot start, which the app cannot repair. */
@Composable
private fun WebViewUnavailableNotice(modifier: Modifier = Modifier) {
    Box(modifier = modifier.padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.guest_browser_webview_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Where find in page stands: the 0-based [active] match of [count]. */
internal data class FindMatch(
    val active: Int = 0,
    val count: Int = 0,
)

// Desktop mode is the only thing that widens the viewport, so it doubles as the mode flag.
private val WebView.isDesktopMode: Boolean
    get() = settings.useWideViewPort

/**
 * Lays pages out as a phone or as a desktop browser would. Desktop sends a desktop user agent and
 * lays the page out at [DESKTOP_VIEWPORT_WIDTH], zoomed out to fit, with pinch to zoom back in.
 */
private fun WebView.applyViewMode(desktop: Boolean) {
    // Null puts back the platform's own user agent.
    settings.userAgentString = if (desktop) desktopUserAgent(WebSettings.getDefaultUserAgent(context)) else null
    settings.useWideViewPort = desktop
    settings.loadWithOverviewMode = desktop
}

@SuppressLint("SetJavaScriptEnabled")
private fun WebView.configure(
    onProgress: (Int) -> Unit,
    onTitle: (String) -> Unit,
    onPageStarted: (String) -> Unit,
    onNavigationStateChange: (url: String, canGoBack: Boolean, canGoForward: Boolean) -> Unit,
    onPageLoaded: (WebView) -> Unit,
    onMainFrameError: (url: String, description: String) -> Unit,
) {
    // Guest pages (dev servers, dashboards, tool UIs) are interactive web apps that need JS.
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    // Pinch to zoom, without the legacy on-screen zoom buttons; the menu zooms in steps too.
    settings.setSupportZoom(true)
    settings.builtInZoomControls = true
    settings.displayZoomControls = false
    webChromeClient =
        object : WebChromeClient() {
            override fun onProgressChanged(
                view: WebView?,
                newProgress: Int,
            ) {
                onProgress(newProgress)
            }

            override fun onReceivedTitle(
                view: WebView?,
                title: String?,
            ) {
                onTitle(title.orEmpty())
            }
        }
    webViewClient =
        object : WebViewClient() {
            override fun onPageStarted(
                view: WebView?,
                url: String?,
                favicon: Bitmap?,
            ) {
                onPageStarted(url.orEmpty())
                // The address bar follows a navigation as it starts, not only once it finishes, so
                // a slow or failing page still shows where it was going.
                onNavigationStateChange(
                    url.orEmpty(),
                    view?.canGoBack() == true,
                    view?.canGoForward() == true,
                )
            }

            override fun onPageFinished(
                view: WebView?,
                url: String?,
            ) {
                if (view?.isDesktopMode == true) view.evaluateJavascript(DESKTOP_VIEWPORT_SCRIPT, null)
                view?.let(onPageLoaded)
                onProgress(100)
                onNavigationStateChange(
                    url.orEmpty(),
                    view?.canGoBack() == true,
                    view?.canGoForward() == true,
                )
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                // Only the page itself failing replaces it; a broken image or script is DevTools' business.
                if (request?.isForMainFrame == true) {
                    onMainFrameError(request.url.toString(), error?.description?.toString().orEmpty())
                }
            }
        }
}
