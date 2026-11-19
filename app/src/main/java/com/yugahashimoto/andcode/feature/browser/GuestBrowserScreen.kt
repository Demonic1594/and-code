package com.yugahashimoto.andcode.feature.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yugahashimoto.andcode.BuildConfig
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.core.UrlLauncher
import com.yugahashimoto.andcode.feature.browser.devtools.AndroidDevToolsMessages
import com.yugahashimoto.andcode.feature.browser.devtools.DevToolsBridge
import com.yugahashimoto.andcode.feature.browser.devtools.DevToolsPanel
import com.yugahashimoto.andcode.feature.browser.devtools.DevToolsStore
import com.yugahashimoto.andcode.ui.components.PageStoppedResponding
import com.yugahashimoto.andcode.ui.components.WebPageRecovery
import com.yugahashimoto.andcode.ui.components.destroyWebView
import com.yugahashimoto.andcode.ui.components.discardAfterRendererGone
import kotlinx.coroutines.delay
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
 *
 * With [agentPage] it shows the hidden browser's page (View) instead of a page of its own: the same
 * WebView, as the agent left it, with no reload. It tells [presence] while it is open, and which
 * handed-over page it shows, so the agent's tools know what is on the user's screen.
 */
@Composable
internal fun GuestBrowserScreen(
    initialUrl: String,
    agentControl: Boolean,
    onAgentControlChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    agentPage: AgentPageHandoff? = null,
    presence: GuestBrowserPresence? = null,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val linkCopied = stringResource(R.string.guest_browser_link_copied)
    // A handed-over page keeps its page agent's bridge: its document already reports to that one.
    val bridge =
        remember {
            agentPage?.bridge ?: DevToolsBridge(DevToolsStore(AndroidDevToolsMessages(context)), DevToolsBridge.loadScript(context))
        }
    val problems by bridge.store.problems.collectAsState()

    var currentUrl by rememberSaveable { mutableStateOf(agentPage?.view?.url ?: initialUrl) }
    var title by remember { mutableStateOf(agentPage?.view?.title.orEmpty()) }
    var progress by remember { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(agentPage?.view?.canGoBack() == true) }
    var canGoForward by remember { mutableStateOf(agentPage?.view?.canGoForward() == true) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var webViewUnavailable by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<PageLoadError?>(null) }
    // The page's WebView across the deaths of its renderer: gone until Reload builds a fresh one.
    // A handed-over page is loaded already: it is shown as it is, never loaded again.
    var recovery by remember {
        val first = if (agentPage != null) "" else initialUrl.takeIf { it.isNotBlank() }?.let(::normalizeBrowserUrl).orEmpty()
        mutableStateOf(WebPageRecovery(firstLoad = first))
    }
    // The start screen gives way to the first page that loads, whoever loads it (the agent may).
    var pageStarted by remember { mutableStateOf(agentPage != null) }
    var desktopMode by rememberSaveable { mutableStateOf(false) }

    AgentDebuggingHold(agentControl)
    // Counts the agent's actions on the page; the banner shows for a while after the latest one.
    var agentActions by remember { mutableIntStateOf(0) }
    var agentActive by remember { mutableStateOf(false) }
    LaunchedEffect(agentActions) {
        if (agentActions == 0) return@LaunchedEffect
        agentActive = true
        delay(AgentActivity.BANNER_MILLIS)
        agentActive = false
    }
    var findOpen by rememberSaveable { mutableStateOf(false) }
    var findQuery by rememberSaveable { mutableStateOf("") }
    var findMatch by remember { mutableStateOf(FindMatch()) }
    var editingAddress by rememberSaveable { mutableStateOf(initialUrl.isBlank() && agentPage == null) }
    var devToolsOpen by rememberSaveable { mutableStateOf(false) }
    var devToolsHeight by remember { mutableStateOf<Dp?>(null) }
    val loading = progress in 1..99

    fun closeFind() {
        findOpen = false
        findQuery = ""
        findMatch = FindMatch()
        webView?.clearMatches()
    }

    // The address the page actions act on. With no WebView (the system one could not start) the
    // address bar's URL is still worth copying, sharing or opening in another browser.
    fun pageUrl(): String? = (webView?.url ?: currentUrl).takeIf { it.isNotBlank() }

    fun open(raw: String) {
        val url = normalizeBrowserUrl(raw)
        editingAddress = false
        loadError = null
        currentUrl = url
        // With the page gone there is no view to load it in: a fresh one is built that loads it.
        if (recovery.showsView) webView?.loadUrl(url) else recovery = recovery.rebuild(url)
    }

    // The page again, in a fresh view when its renderer is gone: the dead view can never draw.
    fun reload() {
        loadError = null
        if (recovery.showsView) webView?.reload() else recovery = recovery.rebuild()
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
    // A handed-over page parked for a rotation keeps its bridge; the screen built again takes both back.
    DisposableEffect(Unit) { onDispose { if (!(agentPage != null && context.isChangingConfigurations())) bridge.detach() } }
    // The handed-over page is the agent's to name only while this screen shows that very view.
    val viewedTargetId = agentPage?.targetId?.takeIf { recovery.generation == 0 && recovery.showsView }
    val presenceToken = remember { Any() }
    LaunchedEffect(presence, viewedTargetId) { presence?.shown(presenceToken, viewedTargetId) }
    DisposableEffect(presence) { onDispose { presence?.gone(presenceToken) } }

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
                    onReload = ::reload,
                    onStop = { webView?.stopLoading() },
                )
                Box(modifier = Modifier.fillMaxWidth().height(2.dp)) {
                    if (loading) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxSize())
                }
                // The agent handed the page to the user (browser_handoff): it waits until they hand it back.
                if (BrowserHandoff.active != null) {
                    GuestBrowserHandoffBanner(onHandBack = BrowserHandoff::handBack, onDismiss = BrowserHandoff::dismiss)
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
                            // No view while the page is gone. Each fresh one is a new generation, so Compose
                            // builds it anew rather than keeping the holder of the dead one.
                            if (recovery.showsView) {
                                key(recovery.generation) {
                                    AndroidView(
                                        // WebView can draw its background outside its measured bounds, covering the
                                        // address bar even while that bar still accepts focus and keyboard input.
                                        modifier = Modifier.fillMaxSize().clipToBounds(),
                                        factory = { viewContext ->
                                            // Only starting the system WebView provider can fail for reasons the app
                                            // cannot fix; everything after it runs on a WebView that exists, so a failure
                                            // there is a real bug and must not leave a half-built view behind.
                                            // The handed-over page, the first time; a view its renderer took down is not one.
                                            val adopted =
                                                agentPage
                                                    ?.takeIf { recovery.generation == 0 && it.view.getTag(R.id.webview_discarded) != true }
                                                    ?.adoptInto(viewContext)
                                            val created =
                                                adopted ?: viewOrNull(
                                                    create = {
                                                        // The in-guest agent drives this WebView over CDP (the devtools abstract
                                                        // socket is reachable from the guest, which shares the app's UID), so a
                                                        // page can be shown to the user and operated by human and agent at the
                                                        // same time. AgentDebugging, above, decides when that socket exists.
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
                                                    onAgentActivity = { agentActions++ },
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
                                                    onPageLoaded = { view ->
                                                        if (findOpen && findQuery.isNotEmpty()) view.findAllAsync(findQuery)
                                                    },
                                                    onMainFrameError = { url, description -> loadError = PageLoadError(url, description) },
                                                    onRendererGone = { detail ->
                                                        val dead = this@apply
                                                        recovery = recovery.rendererGone(lastUrl = dead.url, fallbackUrl = currentUrl)
                                                        loadError = null
                                                        progress = 0
                                                        canGoBack = false
                                                        canGoForward = false
                                                        if (webView === dead) {
                                                            // Off the dead page first: it cannot answer the bridge again.
                                                            runCatching { bridge.detach() }.onFailure { error ->
                                                                Log.w(TAG, "DevTools could not let go of the dead page", error)
                                                            }
                                                            webView = null
                                                        }
                                                        dead.discardAfterRendererGone(detail)
                                                    },
                                                )
                                                setFindListener { active, count, _ -> findMatch = FindMatch(active, count) }
                                                applyViewMode(desktopMode)
                                                // Before the first load, so the page agent is in place for its first line. A
                                                // handed-over page has it already, through the same bridge.
                                                if (!bridge.isAttachedTo(this)) bridge.attach(this)
                                                webView = this
                                                // Asked for while the page was gone: the cache is every view's, so this one clears it.
                                                if (recovery.clearCacheFirst) clearCache(true)
                                                // The address it opened with or, rebuilt after its renderer died, the page to bring back.
                                                recovery.firstLoad.takeIf { it.isNotBlank() }?.let { first -> loadUrl(first) }
                                            }
                                        },
                                        onRelease = { view ->
                                            val handedOver = agentPage != null && view === agentPage.view
                                            if (handedOver && context.isChangingConfigurations()) {
                                                // Only the window is going (a rotation): the screen built again shows it.
                                                agentPage?.park()
                                            } else {
                                                // A fresh view may hold the bridge already; only this one's is let go.
                                                if (webView == null || webView === view) bridge.detach()
                                                destroyWebView(view)
                                                if (handedOver) agentPage?.let { presence?.released(it) }
                                            }
                                        },
                                    )
                                }
                            }
                            when (browserCover(webViewUnavailable, !recovery.showsView, loadError != null, currentUrl, pageStarted)) {
                                BrowserCover.WEBVIEW_UNAVAILABLE -> WebViewUnavailableNotice(modifier = Modifier.fillMaxSize())
                                BrowserCover.PAGE_GONE ->
                                    PageStoppedResponding(onReload = ::reload, detail = recovery.gone?.url?.let(::displayHost))
                                BrowserCover.LOAD_ERROR -> loadError?.let { error -> PageErrorState(error = error, onRetry = ::reload) }
                                BrowserCover.START -> BrowserStartState(onOpen = ::open)
                                BrowserCover.NONE -> Unit
                            }
                            // Over the page, not above it: pushing the page down while the agent measures
                            // and taps it could move what it is about to tap.
                            // The plain one: inside the page column the scoped overload would be picked.
                            androidx.compose.animation.AnimatedVisibility(
                                visible = agentActive && agentControl,
                                modifier = Modifier.align(Alignment.TopCenter),
                            ) {
                                AgentControllingBanner()
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
                                if (recovery.showsView) {
                                    webView?.apply {
                                        clearCache(true)
                                        reload()
                                    }
                                } else {
                                    loadError = null
                                    recovery = recovery.rebuild(clearCache = true)
                                }
                            },
                            onCopyLink = {
                                pageUrl()?.let { url ->
                                    clipboard.setText(AnnotatedString(url))
                                    scope.launch { snackbar.showSnackbar(linkCopied) }
                                }
                            },
                            onShare = {
                                pageUrl()?.let { url ->
                                    val send =
                                        Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, url)
                                        }
                                    runCatching { context.startActivity(Intent.createChooser(send, null)) }
                                }
                            },
                            onOpenExternal = { pageUrl()?.let { UrlLauncher.openUrl(context, it) } },
                            agentControl = agentControl,
                            onToggleAgentControl = { onAgentControlChange(!agentControl) },
                        ),
                )
            }
            SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        }
    }
}

/**
 * Shown while the agent has handed the page to the user. The page stays live underneath, so the
 * user can sign in (or do any other step only they can do) and then give control back in one tap.
 */
@Composable
internal fun GuestBrowserHandoffBanner(
    onHandBack: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.guest_browser_handoff_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.guest_browser_handoff_body),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.guest_browser_handoff_cancel))
            }
            Button(onClick = onHandBack, modifier = Modifier.padding(start = 8.dp)) {
                Text(stringResource(R.string.guest_browser_handoff_done))
            }
        }
    }
}

/** What the Guest Browser tells the hidden browser's owner ([HiddenAgentBrowser]) about itself. */
internal interface GuestBrowserPresence {
    /** The screen known by [token] is open, showing the handed-over page [viewedTargetId], or a page of its own (null). */
    fun shown(
        token: Any,
        viewedTargetId: String?,
    )

    fun gone(token: Any)

    /** The screen destroyed the handed-over page [handoff]. */
    fun released(handoff: AgentPageHandoff)
}

/** Whether the activity behind [this] is being rebuilt for a configuration change (a rotation), rather than closed. */
private fun Context.isChangingConfigurations(): Boolean {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current.isChangingConfigurations
        current = current.baseContext
    }
    return false
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

private fun WebView.configure(
    onProgress: (Int) -> Unit,
    onTitle: (String) -> Unit,
    onPageStarted: (String) -> Unit,
    onNavigationStateChange: (url: String, canGoBack: Boolean, canGoForward: Boolean) -> Unit,
    onPageLoaded: (WebView) -> Unit,
    onMainFrameError: (url: String, description: String) -> Unit,
    onAgentActivity: () -> Unit,
    onRendererGone: (RenderProcessGoneDetail?) -> Unit,
) {
    applyGuestBrowserSettings()
    // A download (a release asset, an archive) goes to the phone's browser: this one cannot save it.
    setDownloadListener { url, _, _, _, _ -> UrlLauncher.openUrl(context, url) }
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

            // The agent's browser tool logs this before each action it takes on the page.
            override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                if (message?.message() != AgentActivity.MARKER) return super.onConsoleMessage(message)
                onAgentActivity()
                return true
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

            // Android ended the renderer under the page (to free memory, or the page crashed it).
            // Unhandled, the whole app goes with it; handled, the screen discards this view, which
            // also ends the agent's DevTools sessions on it, and offers the page back.
            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?,
            ): Boolean {
                onRendererGone(detail)
                return true
            }
        }
}

/**
 * What every page the user or the agent browses in the app gets: the Guest Browser's, the hidden
 * browser's ([HiddenAgentBrowser]) and the inline preview's.
 */
@SuppressLint("SetJavaScriptEnabled")
internal fun WebView.applyGuestBrowserSettings() {
    // Guest pages (dev servers, dashboards, tool UIs) are interactive web apps that need JS.
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    // Pages are web pages: a file: or content: address (typed, linked from a page, or asked for by an
    // agent) must not show the app's own files or the phone's media. Off by default only from API 30.
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    // Media may play, with sound, without a tap first, also in a cross-origin embed (a YouTube player
    // the page unmutes); the agent drives pages nobody taps. With the default, a muted embed asked to
    // unmute pauses instead.
    settings.mediaPlaybackRequiresUserGesture = false
    // Pinch to zoom, without the legacy on-screen zoom buttons; the menu zooms in steps too.
    settings.setSupportZoom(true)
    settings.builtInZoomControls = true
    settings.displayZoomControls = false
}

/**
 * Keeps the DevTools socket the agent drives this browser through on while the browser is on screen
 * with [agentControl] on and the app is in the foreground; a browser left behind the home screen
 * holds nothing. Otherwise the socket is the build's default (on only in debug builds, for Chrome's
 * inspector). It is process-wide, so it covers the app's other WebViews too.
 */
@Composable
private fun AgentDebuggingHold(agentControl: Boolean) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var started by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> started = event.targetState.isAtLeast(Lifecycle.State.STARTED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val hold = agentControl && started
    DisposableEffect(hold) {
        if (hold) AgentDebugging.acquire()
        onDispose { if (hold) AgentDebugging.release() }
    }
}

/**
 * How many browsers want the DevTools socket on for the agent - Guest Browsers on screen, and the
 * hidden browser while it is open - counted so one closing while another opens cannot switch it off
 * under the new one. Main thread only (composition effects and [HiddenAgentBrowser] drive it), so a
 * plain counter is enough.
 */
internal object AgentDebugging {
    private var holders = 0

    fun acquire() {
        holders++
        apply()
    }

    fun release() {
        holders = (holders - 1).coerceAtLeast(0)
        apply()
    }

    /** Turns the socket on or off; a device without a working WebView has none. */
    private fun apply() {
        runCatching { WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG || holders > 0) }
            .onFailure { error -> Log.w(TAG, "WebView debugging could not be changed", error) }
    }
}

/** Shown while an agent is acting on the page, so the user knows the taps and typing are not theirs. */
@Composable
private fun AgentControllingBanner() {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Default.SmartToy,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                stringResource(R.string.guest_browser_agent_controlling),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

internal object AgentActivity {
    /** What the agent's browser tool logs to the page console before each action (andcode-browser-mcp.py). */
    const val MARKER = "andcode:agent-control"

    /** How long the banner stays after the agent's last action. */
    const val BANNER_MILLIS = 8_000L
}
