package com.yugahashimoto.andcode.feature.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.yugahashimoto.andcode.BuildConfig
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.core.UrlLauncher
import kotlinx.coroutines.launch

/** In-app browser for pages served inside the guest runtime (e.g. http://127.0.0.1:PORT/). */
@OptIn(ExperimentalMaterial3Api::class)
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
    var urlInput by rememberSaveable { mutableStateOf(initialUrl) }
    var progress by remember { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var desktopMode by rememberSaveable { mutableStateOf(false) }
    var findOpen by rememberSaveable { mutableStateOf(false) }
    var findQuery by rememberSaveable { mutableStateOf("") }
    var findMatch by remember { mutableStateOf(FindMatch()) }
    // While the user is typing an address, a navigation (an agent's, a dev server's reload, a
    // redirect) must not replace what they have typed so far.
    var urlFocused by remember { mutableStateOf(false) }

    fun closeFind() {
        findOpen = false
        findQuery = ""
        findMatch = FindMatch()
        webView?.clearMatches()
    }

    BackHandler {
        val view = webView
        when {
            findOpen -> closeFind()
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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            GuestBrowserTopBar(
                canGoBack = canGoBack,
                canGoForward = canGoForward,
                desktopMode = desktopMode,
                onBack = onBack,
                onHistoryBack = { webView?.goBack() },
                onForward = { webView?.goForward() },
                onReload = { webView?.reload() },
                actions =
                    BrowserMenuActions(
                        onToggleDesktop = { desktopMode = !desktopMode },
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
                        onOpenExternal = { webView?.url?.let { UrlLauncher.openUrl(context, it) } },
                    ),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            GuestBrowserUrlBar(
                urlInput = urlInput,
                onUrlChange = { urlInput = it },
                onFocusChange = { urlFocused = it },
                onGo = { webView?.loadUrl(normalizeBrowserUrl(urlInput)) },
            )
            if (findOpen) {
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
            if (progress in 1..99) {
                LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            }
            AndroidView(
                // WebView can draw its background outside its measured bounds, covering the URL
                // field even while that field still accepts focus and keyboard input.
                modifier = Modifier.weight(1f).clipToBounds(),
                factory = { context ->
                    // Lets the in-guest agent drive this WebView over CDP (the devtools abstract
                    // socket is reachable from the guest, which shares the app's UID), so a page
                    // can be shown to the user and operated by human and agent at the same time.
                    WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
                    WebView(context).apply {
                        layoutParams =
                            ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                        configure(
                            onProgress = { progress = it },
                            onNavigationStateChange = { url, back, forward ->
                                if (!urlFocused) urlInput = url
                                canGoBack = back
                                canGoForward = forward
                            },
                            // A new page has new matches: search it again for what is still asked.
                            onPageLoaded = { view -> if (findOpen && findQuery.isNotEmpty()) view.findAllAsync(findQuery) },
                        )
                        setFindListener { active, count, _ -> findMatch = FindMatch(active, count) }
                        applyViewMode(desktopMode)
                        webView = this
                        if (initialUrl.isNotBlank()) {
                            loadUrl(normalizeBrowserUrl(initialUrl))
                        }
                    }
                },
            )
        }
    }
}

/** Where find in page stands: the 0-based [active] match of [count]. */
private data class FindMatch(
    val active: Int = 0,
    val count: Int = 0,
)

private class BrowserMenuActions(
    val onToggleDesktop: () -> Unit,
    val onFind: () -> Unit,
    val onZoomIn: () -> Unit,
    val onZoomOut: () -> Unit,
    val onHardReload: () -> Unit,
    val onCopyLink: () -> Unit,
    val onOpenExternal: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GuestBrowserTopBar(
    canGoBack: Boolean,
    canGoForward: Boolean,
    desktopMode: Boolean,
    onBack: () -> Unit,
    onHistoryBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    actions: BrowserMenuActions,
) {
    var menuOpen by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text(stringResource(R.string.guest_browser_title)) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.nav_back),
                )
            }
        },
        actions = {
            IconButton(enabled = canGoBack, onClick = onHistoryBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.guest_browser_back),
                )
            }
            IconButton(enabled = canGoForward, onClick = onForward) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = stringResource(R.string.guest_browser_forward),
                )
            }
            IconButton(onClick = onReload) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.guest_browser_reload),
                )
            }
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.guest_browser_more))
            }
            BrowserMenu(expanded = menuOpen, desktopMode = desktopMode, actions = actions, onDismiss = { menuOpen = false })
        },
    )
}

@Composable
private fun BrowserMenu(
    expanded: Boolean,
    desktopMode: Boolean,
    actions: BrowserMenuActions,
    onDismiss: () -> Unit,
) {
    @Composable
    fun item(
        @StringRes label: Int,
        icon: androidx.compose.ui.graphics.vector.ImageVector,
        onClick: () -> Unit,
        trailing: (@Composable () -> Unit)? = null,
        modifier: Modifier = Modifier,
    ) = DropdownMenuItem(
        modifier = modifier,
        text = { Text(stringResource(label)) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        trailingIcon = trailing,
        onClick = {
            onDismiss()
            onClick()
        },
    )

    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        item(
            label = R.string.guest_browser_desktop_site,
            icon = Icons.Default.DesktopWindows,
            onClick = actions.onToggleDesktop,
            trailing = { Checkbox(checked = desktopMode, onCheckedChange = null) },
            modifier =
                Modifier.semantics {
                    role = Role.Checkbox
                    toggleableState = ToggleableState(desktopMode)
                },
        )
        item(R.string.guest_browser_find, Icons.Default.Search, actions.onFind)
        item(R.string.guest_browser_zoom_in, Icons.Default.ZoomIn, actions.onZoomIn)
        item(R.string.guest_browser_zoom_out, Icons.Default.ZoomOut, actions.onZoomOut)
        HorizontalDivider()
        item(R.string.guest_browser_hard_reload, Icons.Default.Refresh, actions.onHardReload)
        item(R.string.guest_browser_copy_link, Icons.Default.ContentCopy, actions.onCopyLink)
        item(R.string.guest_browser_open_external, Icons.Default.OpenInBrowser, actions.onOpenExternal)
    }
}

@Composable
private fun GuestBrowserUrlBar(
    urlInput: String,
    onUrlChange: (String) -> Unit,
    onFocusChange: (Boolean) -> Unit,
    onGo: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        OutlinedTextField(
            value = urlInput,
            onValueChange = onUrlChange,
            modifier = Modifier.weight(1f).onFocusChanged { onFocusChange(it.isFocused) },
            singleLine = true,
            placeholder = { Text(stringResource(R.string.guest_browser_url_hint)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onGo() }),
        )
        Button(onClick = onGo, modifier = Modifier.padding(start = 8.dp)) {
            Text(stringResource(R.string.guest_browser_go))
        }
    }
}

@Composable
private fun FindInPageBar(
    query: String,
    match: FindMatch,
    onQueryChange: (String) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text(stringResource(R.string.guest_browser_find_hint)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onNext() }),
        )
        if (query.isNotEmpty()) {
            Text(
                text =
                    stringResource(
                        R.string.guest_browser_find_count,
                        if (match.count == 0) 0 else match.active + 1,
                        match.count,
                    ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        IconButton(enabled = match.count > 0, onClick = onPrevious) {
            Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.guest_browser_find_previous))
        }
        IconButton(enabled = match.count > 0, onClick = onNext) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.guest_browser_find_next))
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.guest_browser_find_close))
        }
    }
}

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
    onNavigationStateChange: (url: String, canGoBack: Boolean, canGoForward: Boolean) -> Unit,
    onPageLoaded: (WebView) -> Unit,
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
        }
    webViewClient =
        object : WebViewClient() {
            override fun onPageStarted(
                view: WebView?,
                url: String?,
                favicon: Bitmap?,
            ) {
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
        }
}
