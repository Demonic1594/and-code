package com.yugahashimoto.andcode.feature.browser

import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.feature.browser.devtools.AndroidDevToolsMessages
import com.yugahashimoto.andcode.feature.browser.devtools.DevToolsBridge
import com.yugahashimoto.andcode.feature.browser.devtools.DevToolsStore
import com.yugahashimoto.andcode.runtime.LocalAgent
import com.yugahashimoto.andcode.ui.components.discardAfterRendererGone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "HiddenAgentBrowser"

/** Something the agent asked to show the user, waiting for the app's screen to do it. */
internal sealed interface VisibleBrowserRequest {
    /** Open the Guest Browser at [url]. */
    data class OpenUrl(
        val url: String,
    ) : VisibleBrowserRequest

    /** Show the hidden page in the Guest Browser, as the notice's View does. */
    data object ShowHiddenPage : VisibleBrowserRequest

    /** Pin [url], a page on this device, in the conversation (browser_preview). */
    data class Preview(
        val url: String,
    ) : VisibleBrowserRequest

    /**
     * Hand the page to the user until they hand it back (browser_handoff), recording the answer to
     * [requestId] in [rootfs]. A handoff applies to the page on the user's screen, so the hidden page
     * is brought there first ([showHiddenPage]), as View does.
     */
    data class Handoff(
        val rootfs: File,
        val showHiddenPage: Boolean,
        val requestId: String = "",
    ) : VisibleBrowserRequest
}

/**
 * The hidden page on its way to the Guest Browser after View: the same [view], with the same state and
 * no reload, and the [bridge] whose page agent its document already carries. [targetId] keeps naming
 * it to the agent's tools while the Guest Browser shows it.
 */
internal class AgentPageHandoff(
    val view: WebView,
    val bridge: DevToolsBridge,
    targetId: String?,
    private val context: MutableContextWrapper,
) {
    /** Set when the app finds the page after the user took it with View, so the screen names it from then on. */
    var targetId by mutableStateOf(targetId)

    /** Puts the view in [host]'s window: an activity's, so its pickers and popups have one to open in. */
    fun adoptInto(host: Context): WebView {
        (view.parent as? ViewGroup)?.removeView(view)
        context.baseContext = host
        return view
    }

    /** Takes the view out of a window that is going away for a moment (a rotation), keeping it whole. */
    fun park() {
        (view.parent as? ViewGroup)?.removeView(view)
        context.baseContext = context.applicationContext
    }
}

/**
 * The hidden browser: a WebView the app owns outside every screen, where an agent browses without
 * taking the user's screen (browser_show with mode "hidden"). There is at most one; it loads only
 * what an agent asks for, and nothing again on its own.
 *
 * It is never put in a window. WebView counts a view that has never been attached as visible, so the
 * page keeps running its timers and animation frames when the app is in the background, as long as
 * the view is not paused (it never is), and it is laid out at the phone's screen size so pages lay out
 * as they would on it. Its renderer keeps the app's importance even though nothing shows it. The
 * process itself stays up for as long as the agent works, under the agent's own foreground service.
 *
 * The page gets the Guest Browser's settings and page agent, and the DevTools socket stays on while it
 * is open, so the agent's tools drive it as they do the Guest Browser. To tell it from every other
 * WebView of the app, it first opens a blank page with a marker of its own ([HiddenPageMarker]); the
 * app finds that page among the DevTools targets and writes its target to the guest
 * ([AgentBrowserState.statusJson]), and the tools act on that target only.
 *
 * WebView is main-thread only: every method that touches [page] runs on the main thread.
 */
internal class HiddenAgentBrowser(
    private val context: Context,
    private val runtimeDirectory: File,
    private val selectedAgent: () -> LocalAgent?,
    private val agentControl: StateFlow<Boolean>,
    private val foreground: StateFlow<Boolean>,
    private val clock: () -> Long = System::currentTimeMillis,
) : GuestBrowserPresence {
    private val mutableState = MutableStateFlow(AgentBrowserState())
    val state: StateFlow<AgentBrowserState> = mutableState.asStateFlow()

    private val mutableVisibleRequests = MutableStateFlow<VisibleBrowserRequest?>(null)

    /** The Guest Browser an agent asked to show, for the app's screen to open and then [consumeVisibleRequest]. */
    val visibleRequests: StateFlow<VisibleBrowserRequest?> = mutableVisibleRequests.asStateFlow()

    private val statusFile = AgentBrowserStatusFile(runtimeDirectory)

    private var page: PageHolder? = null

    /** The page View handed over last, until the Guest Browser screen showing it releases it. */
    var handedOver: AgentPageHandoff? = null
        private set

    private val mutableHandovers = MutableStateFlow(0)

    /** Counts the pages View has handed over: the Guest Browser's agent page is built anew for each. */
    val handovers: StateFlow<Int> = mutableHandovers.asStateFlow()

    /**
     * The handoffs that still hold the DevTools socket for the page they carry. Each lets go once a
     * Guest Browser shows its page (holding the socket itself) or releases it, not before: letting go
     * first would close the socket for a moment in release builds and cut the agent's call short.
     */
    private val debuggingHolds = HashSet<AgentPageHandoff>()

    /** The Guest Browser screens open now, each with the handed-over page it shows, if one. */
    private val guestBrowsers = LinkedHashMap<Any, String?>()

    /**
     * Runs the bridge to the guest: reads its commands while [active] (the app on screen, or an agent
     * working), keeps the status it reads up to date, and closes the hidden browser when it idles, when
     * agent control is switched off, or when [runtime] changes.
     */
    suspend fun run(
        active: Flow<Boolean>,
        runtime: Flow<String?>,
    ) = coroutineScope {
        launch { state.collectLatest { status -> withContext(Dispatchers.IO) { statusFile.write(status.statusJson(clock())) } } }
        launch(Dispatchers.Main) {
            state.map { it.hidden != null }.distinctUntilChanged().collectLatest { open ->
                while (open) {
                    delay(IDLE_CHECK_MILLIS)
                    if (state.value.idle(clock())) close(HiddenBrowserEnd.IDLE)
                }
            }
        }
        launch(Dispatchers.Main) { agentControl.collect { allowed -> if (!allowed) close(HiddenBrowserEnd.AGENT_CONTROL_OFF) } }
        launch(Dispatchers.Main) { runtime.distinctUntilChanged().drop(1).collect { endChat() } }
        watchBrowserCommands(
            active = active,
            poll = { withContext(Dispatchers.IO) { consumeBrowserCommand(selectedAgent(), runtimeDirectory, clock, ::canActOnNow) } },
            onCommand = { command -> withContext(Dispatchers.Main) { handle(command) } },
        )
    }

    /**
     * What happens on the user's screen (a visible open, a preview, a handoff) is only for someone
     * looking at the app: it waits in its file until the app is on screen. The hidden browser does not.
     */
    private fun canActOnNow(command: BrowserCommand): Boolean =
        when (command) {
            is BrowserCommand.Close -> true
            is BrowserCommand.Open -> command.mode == BrowserMode.HIDDEN || foreground.value
            is BrowserCommand.Preview, is BrowserCommand.Handoff -> foreground.value
        }

    private suspend fun handle(command: BrowserCommand) {
        when (command) {
            is BrowserCommand.Close ->
                if (page != null) close(HiddenBrowserEnd.CLOSED_BY_AGENT, command.requestId) else answer(command.requestId)
            is BrowserCommand.Preview -> mutableVisibleRequests.value = VisibleBrowserRequest.Preview(command.url)
            is BrowserCommand.Handoff ->
                mutableVisibleRequests.value =
                    VisibleBrowserRequest.Handoff(command.rootfs, showHiddenPage = owns(command.ownHidden), requestId = command.requestId)
            is BrowserCommand.Open ->
                when {
                    command.mode == BrowserMode.HIDDEN -> openHidden(command.url.orEmpty(), command.requestId)
                    command.url == null -> {
                        // Handed over as it is: the Guest Browser takes the page when it opens (handOver).
                        mutableState.update { it.copy(lastRequestId = command.requestId) }
                        if (owns(command.ownHidden)) mutableVisibleRequests.value = VisibleBrowserRequest.ShowHiddenPage
                    }
                    else -> {
                        // Choosing the visible browser closes the agent's hidden one: it works in one at a time.
                        if (owns(command.ownHidden)) close(HiddenBrowserEnd.CLOSED_BY_AGENT)
                        mutableState.update { it.visibleOpened(command.requestId) }
                        mutableVisibleRequests.value = VisibleBrowserRequest.OpenUrl(command.url)
                    }
                }
        }
    }

    /** Whether the hidden page open now is the one [ownHidden] names, the asking agent's own. */
    private fun owns(ownHidden: String?): Boolean = page != null && ownHidden != null && state.value.hidden?.requestId == ownHidden

    private fun answer(requestId: String) = mutableState.update { it.hiddenEnded(HiddenBrowserEnd.CLOSED_BY_AGENT, requestId) }

    private suspend fun openHidden(
        url: String,
        requestId: String,
    ) {
        val current = page
        when {
            !agentControl.value -> mutableState.update { it.hiddenEnded(HiddenBrowserEnd.AGENT_CONTROL_OFF, requestId) }
            current != null -> {
                current.view.loadUrl(url)
                mutableState.update { it.navigateHidden(requestId, url, clock()) }
            }
            else -> openFreshPage(url, requestId)
        }
    }

    private suspend fun openFreshPage(
        url: String,
        requestId: String,
    ) {
        val created = createPage() ?: return mutableState.update { it.hiddenEnded(HiddenBrowserEnd.FAILED, requestId) }
        page = created
        AgentDebugging.acquire()
        mutableState.update { it.startHidden(requestId, url, clock()) }
        created.view.loadUrl(created.marker)
        val targetId =
            try {
                withContext(Dispatchers.IO) { awaitMarkedTarget(created.marker) }
            } catch (cancelled: CancellationException) {
                // The command loop stopped (the guest went quiet): a page left starting would never
                // answer, and a later open would reuse it with no target to drive.
                if (page === created) close(HiddenBrowserEnd.FAILED, requestId)
                throw cancelled
            }
        if (page !== created) {
            // Handed to the user while it was being found: it still opens what the agent asked for, also when
            // its target never showed (the tools then take the page on screen), unless the user has gone
            // somewhere from the blank page meanwhile. Closed instead (the user closed it, agent control
            // went off): there is nothing left to answer.
            val handoff = handedOver?.takeIf { it.view === created.view }
            if (handoff != null) {
                targetId?.let { handoff.targetId = it }
                if (HiddenPageMarker.isMarker(created.view.url)) created.view.loadUrl(url)
            }
            return
        }
        if (targetId == null) {
            Log.w(TAG, "The hidden page could not be found among the DevTools targets")
            return close(HiddenBrowserEnd.FAILED, requestId)
        }
        created.targetId = targetId
        created.view.loadUrl(url)
        mutableState.update { it.hiddenReady(targetId) }
    }

    /** The marker page's DevTools target, polled for a few seconds while it loads; null if it never shows. */
    private suspend fun awaitMarkedTarget(marker: String): String? {
        repeat(FIND_TARGET_ATTEMPTS) {
            listOwnDevToolsTargets()?.let { findMarkedTarget(it, marker) }?.let { return it }
            delay(FIND_TARGET_INTERVAL_MILLIS)
        }
        return null
    }

    /** Closes the hidden browser for [reason], answering [requestId] if one asked; nothing when none is open. */
    fun close(
        reason: HiddenBrowserEnd,
        requestId: String? = null,
    ) {
        val current = page ?: return
        page = null
        current.destroy()
        AgentDebugging.release()
        mutableState.update { it.hiddenEnded(reason, requestId) }
    }

    /** The close on the notice, in the chat or the notification. */
    fun closeByUser() = close(HiddenBrowserEnd.CLOSED_BY_USER)

    /** The chat the agent works in changed from [previous] to [current]: a hidden browser it opened is done with. */
    fun onChatChanged(
        previous: String?,
        current: String?,
    ) {
        if (chatEnded(previous, current)) endChat()
    }

    /** A hidden page open goes with its chat; with none, the next chat's agent still starts on the visible browser. */
    private fun endChat() {
        if (page != null) close(HiddenBrowserEnd.CHAT_ENDED) else mutableState.update { it.chatEnded() }
    }

    /**
     * View: hands the hidden page, if one is open, to the Guest Browser: the same WebView, state and
     * history, with no reload. From then on it is the Guest Browser's page, on the user's screen, and
     * the agent's tools treat it as the visible browser. It stays [handedOver] until the screen
     * showing it has [released] it, so a screen built again (after a rotation) takes the same page; a
     * later View hands over the newer hidden page in its place, and the earlier page goes with its screen.
     */
    fun handOver() {
        val current = page ?: return
        page = null
        mutableVisibleRequests.update { if (it == VisibleBrowserRequest.ShowHiddenPage) null else it }
        val handoff = AgentPageHandoff(current.view, current.bridge, current.targetId, current.context)
        // The socket stays held until the Guest Browser holds it for itself (shown) or lets the page go.
        debuggingHolds += handoff
        handedOver = handoff
        mutableState.update { it.viewed() }
        mutableHandovers.update { it + 1 }
    }

    override fun released(handoff: AgentPageHandoff) {
        if (handedOver === handoff) handedOver = null
        releaseDebugging(handoff)
    }

    private fun releaseDebugging(handoff: AgentPageHandoff) {
        if (debuggingHolds.remove(handoff)) AgentDebugging.release()
    }

    fun consumeVisibleRequest(request: VisibleBrowserRequest) = mutableVisibleRequests.compareAndSet(request, null)

    override fun shown(
        token: Any,
        agentPage: AgentPageHandoff?,
        viewedTargetId: String?,
    ) {
        guestBrowsers[token] = viewedTargetId
        publishGuestBrowsers()
        // Called from the screen's effects after its own hold on the socket is in place.
        agentPage?.let(::releaseDebugging)
    }

    override fun gone(token: Any) {
        guestBrowsers.remove(token)
        publishGuestBrowsers()
    }

    private fun publishGuestBrowsers() =
        mutableState.update { state ->
            if (guestBrowsers.isEmpty()) state.guestBrowserClosed() else state.guestBrowserShown(guestBrowsers.values.last())
        }

    private fun createPage(): PageHolder? {
        val wrapper = MutableContextWrapper(context.applicationContext)
        val view =
            viewOrNull(
                create = { WebView(wrapper) },
                onFailure = { error -> Log.w(TAG, "The system WebView could not start", error) },
            ) ?: return null
        val bridge = DevToolsBridge(DevToolsStore(AndroidDevToolsMessages(context)), DevToolsBridge.loadScript(context))
        val created = PageHolder(view, bridge, wrapper, HiddenPageMarker.new())
        view.applyGuestBrowserSettings()
        // Nothing is saved or handed to another app from a page nobody sees.
        view.setDownloadListener { _, _, _, _, _ -> }
        // Android ends a renderer it thinks nobody looks at first; this one is the agent's work.
        view.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        view.webChromeClient = HiddenChromeClient(created)
        view.webViewClient = HiddenViewClient(created)
        val metrics = context.resources.displayMetrics
        view.measure(
            View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
        // Before the first load, so the page agent is in place for its first line.
        bridge.attach(view)
        return created
    }

    private fun pageChanged(
        changed: PageHolder,
        url: String?,
        title: String?,
    ) {
        if (page !== changed) return
        mutableState.update { it.pageChanged(url.orEmpty(), title.orEmpty()) }
    }

    private inner class HiddenChromeClient(
        private val owner: PageHolder,
    ) : WebChromeClient() {
        override fun onReceivedTitle(
            view: WebView?,
            title: String?,
        ) = pageChanged(owner, view?.url, title)

        // The agent's browser tool logs this before each action it takes: it keeps the page from idling out.
        override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
            if (message?.message() != AgentActivity.MARKER) return super.onConsoleMessage(message)
            if (page === owner) mutableState.update { it.touched(clock()) }
            return true
        }
    }

    private inner class HiddenViewClient(
        private val owner: PageHolder,
    ) : WebViewClient() {
        override fun onPageStarted(
            view: WebView?,
            url: String?,
            favicon: Bitmap?,
        ) {
            owner.bridge.onPageStarted()
            pageChanged(owner, url, view?.title)
        }

        override fun onPageFinished(
            view: WebView?,
            url: String?,
        ) {
            // The marker page goes from the history once the first real page is in, so Back after View
            // never lands on it.
            val realPage = url != null && !HiddenPageMarker.isMarker(url)
            if (view != null && realPage && !owner.historyCleared) {
                owner.historyCleared = true
                view.clearHistory()
            }
            pageChanged(owner, url, view?.title)
        }

        // As in the Guest Browser (WebPageRecovery): the dead view is destroyed and nothing comes back on
        // its own. The agent learns the page is gone and may open another.
        override fun onRenderProcessGone(
            view: WebView?,
            detail: RenderProcessGoneDetail?,
        ): Boolean {
            if (page === owner) {
                page = null
                AgentDebugging.release()
                mutableState.update { it.hiddenEnded(HiddenBrowserEnd.RENDERER_GONE) }
            }
            runCatching { owner.bridge.detach() }
            (view ?: owner.view).discardAfterRendererGone(detail)
            return true
        }
    }

    /** The hidden page: its view, its page agent, and the marker it opened on. */
    private class PageHolder(
        val view: WebView,
        val bridge: DevToolsBridge,
        val context: MutableContextWrapper,
        val marker: String,
    ) {
        var targetId: String? = null
        var historyCleared = false

        fun destroy() {
            runCatching { bridge.detach() }
            if (view.getTag(R.id.webview_discarded) != true) {
                view.stopLoading()
                view.destroy()
            }
        }
    }

    private companion object {
        const val IDLE_CHECK_MILLIS = 15_000L
        const val FIND_TARGET_ATTEMPTS = 25
        const val FIND_TARGET_INTERVAL_MILLIS = 200L
    }
}

/** Whether moving from chat [previous] to [current] leaves a chat: a new chat starting from none does not. */
internal fun chatEnded(
    previous: String?,
    current: String?,
): Boolean = previous != null && previous != current
