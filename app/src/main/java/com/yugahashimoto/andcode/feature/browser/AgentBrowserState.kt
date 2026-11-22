package com.yugahashimoto.andcode.feature.browser

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/** Why the hidden browser went away, told to the agent until it opens another. */
internal enum class HiddenBrowserEnd(
    val id: String,
) {
    /** The agent closed it, or switched to the visible browser. */
    CLOSED_BY_AGENT("closed_by_agent"),

    /** The user closed it from its notice. */
    CLOSED_BY_USER("closed_by_user"),

    /** The agent left it unused for [AgentBrowserState.IDLE_TIMEOUT_MILLIS]. */
    IDLE("idle"),

    /** Android ended the renderer under the page; it is not brought back on its own. */
    RENDERER_GONE("renderer_gone"),

    /** The chat it was opened in was left, or the runtime changed. */
    CHAT_ENDED("chat_ended"),

    /** "Let agents control this browser" is off, so no agent may drive a hidden page either. */
    AGENT_CONTROL_OFF("agent_control_off"),

    /** The system WebView could not start, or the page could not be found to drive. */
    FAILED("failed"),

    /** The user tapped View: the page is on their screen now, in the Guest Browser. */
    VIEWED("viewed"),
}

/** The hidden browser's page, while there is one. */
internal data class HiddenPage(
    /** The agent request the page last answered, or is about to. */
    val requestId: String,
    val url: String,
    val title: String = "",
    /** Its DevTools target, once the app has found it among its WebViews' pages; the tools act on this one only. */
    val targetId: String? = null,
    /** When the agent last acted on it, for the idle timeout. */
    val lastActivityMs: Long,
) {
    val ready: Boolean get() = targetId != null
}

/**
 * Which browser the agent works in and how the hidden one stands, in plain Kotlin so it is tested on
 * the JVM. The app keeps one for the whole app (like the Guest Browser), writes it to the guest as
 * [statusJson] and shows [browsingNotice] while the hidden browser is in use.
 */
internal data class AgentBrowserState(
    /**
     * The browser an agent chose last, as the status shows it. Each agent's tools follow their own
     * agent's choice instead (chosen_mode in andcode-browser-mcp.py), and its hidden page by request.
     */
    val mode: BrowserMode = BrowserMode.VISIBLE,
    val hidden: HiddenPage? = null,
    /** Why the last hidden browser went away; cleared when another opens. */
    val ended: HiddenBrowserEnd? = null,
    /** Whether the Guest Browser screen is open. */
    val guestBrowserOpen: Boolean = false,
    /** The target of the page the Guest Browser shows when it is one handed over from the hidden browser. */
    val viewedTargetId: String? = null,
    /** The request the hidden page last answered when the user took it over with View: its agent's page is the visible one now. */
    val viewedRequestId: String? = null,
    /** The last agent request this state answers, so the tool waiting on it knows its answer has come. */
    val lastRequestId: String? = null,
) {
    /** A fresh hidden page that loads [url] once the app has found it among the DevTools targets. */
    fun startHidden(
        requestId: String,
        url: String,
        now: Long,
    ): AgentBrowserState = copy(mode = BrowserMode.HIDDEN, hidden = HiddenPage(requestId, url, lastActivityMs = now), ended = null)

    /** The page found as [targetId]: the tools can drive it, and its request is answered. */
    fun hiddenReady(targetId: String): AgentBrowserState {
        val page = hidden ?: return this
        return copy(hidden = page.copy(targetId = targetId), lastRequestId = page.requestId)
    }

    /** The existing hidden page sent to [url]: answered at once, since the tools can already drive it. */
    fun navigateHidden(
        requestId: String,
        url: String,
        now: Long,
    ): AgentBrowserState {
        val page = hidden ?: return this
        return copy(
            mode = BrowserMode.HIDDEN,
            hidden = page.copy(requestId = requestId, url = url, lastActivityMs = now),
            ended = null,
            lastRequestId = requestId,
        )
    }

    /**
     * The hidden browser is gone, for [reason]; [requestId] is the request that could not be met, if
     * one. The agent stays on the hidden browser: its tools must not fall through to a page the user
     * has open, so they say why it ended until the agent opens another or picks the visible one. Only
     * when the chat ended is that choice gone with it: the next chat's agent has chosen nothing, so it
     * starts on the visible browser, as every agent does.
     */
    fun hiddenEnded(
        reason: HiddenBrowserEnd,
        requestId: String? = null,
    ): AgentBrowserState =
        copy(
            mode = if (reason == HiddenBrowserEnd.CHAT_ENDED) BrowserMode.VISIBLE else BrowserMode.HIDDEN,
            hidden = null,
            ended = reason,
            lastRequestId = requestId ?: hidden?.requestId ?: lastRequestId,
        )

    /**
     * The user tapped View: the page is the Guest Browser's now, on screen, still known by its target.
     * Taken before the app had found it, it answers the open it was starting for, which no longer
     * waits for the page to be found.
     */
    fun viewed(): AgentBrowserState {
        val page = hidden ?: return this
        return copy(
            mode = BrowserMode.VISIBLE,
            hidden = null,
            ended = HiddenBrowserEnd.VIEWED,
            viewedTargetId = page.targetId,
            viewedRequestId = page.requestId,
            lastRequestId = if (page.ready) lastRequestId else page.requestId,
        )
    }

    /**
     * The chat ended with no hidden page open (it closed before, or the user took it with View): the
     * status shows the visible browser again, as after a chat whose page was still open, and why the
     * last one ended stays told to the agent that opened it.
     */
    fun chatEnded(): AgentBrowserState = if (mode == BrowserMode.HIDDEN && hidden == null) copy(mode = BrowserMode.VISIBLE) else this

    /** The agent opened the Guest Browser on the user's screen. */
    fun visibleOpened(requestId: String): AgentBrowserState = copy(mode = BrowserMode.VISIBLE, lastRequestId = requestId)

    /** The Guest Browser screen is open, showing the handed-over page [viewedTargetId] or one of its own (null). */
    fun guestBrowserShown(viewedTargetId: String?): AgentBrowserState = copy(guestBrowserOpen = true, viewedTargetId = viewedTargetId)

    fun guestBrowserClosed(): AgentBrowserState = copy(guestBrowserOpen = false, viewedTargetId = null)

    /** The hidden page moved to [url] titled [title]; the blank marker it starts on is not a page of its own. */
    fun pageChanged(
        url: String,
        title: String,
    ): AgentBrowserState {
        val page = hidden ?: return this
        if (HiddenPageMarker.isMarker(url)) return this
        return copy(hidden = page.copy(url = url, title = title))
    }

    /** The agent acted on the hidden page at [now]. */
    fun touched(now: Long): AgentBrowserState {
        val page = hidden ?: return this
        return copy(hidden = page.copy(lastActivityMs = now))
    }

    /** Whether the hidden page has gone [IDLE_TIMEOUT_MILLIS] without the agent acting on it. */
    fun idle(now: Long): Boolean {
        val page = hidden ?: return false
        return now - page.lastActivityMs >= IDLE_TIMEOUT_MILLIS
    }

    /** What the user is told while the hidden browser is in use: in the chat, or as a notification in the background. */
    fun browsingNotice(foreground: Boolean): AgentBrowsingNotice? =
        hidden?.let { AgentBrowsingNotice(displayHost(it.url), inBackground = !foreground) }

    /** What andcode-browser-mcp.py reads (read_state there), written to [BROWSER_STATE_PATH]. */
    fun statusJson(now: Long): String =
        buildJsonObject {
            put("version", STATUS_VERSION)
            put("mode", mode.id)
            put(
                "hidden",
                hidden?.let { page ->
                    buildJsonObject {
                        put("state", if (page.ready) "ready" else "starting")
                        put("url", page.url)
                        put("title", page.title)
                        put("targetId", page.targetId?.let(::JsonPrimitive) ?: JsonNull)
                        put("requestId", page.requestId)
                    }
                } ?: JsonNull,
            )
            put("ended", ended?.id?.let(::JsonPrimitive) ?: JsonNull)
            put("guestBrowserOpen", guestBrowserOpen)
            put("visibleTargetId", viewedTargetId?.let(::JsonPrimitive) ?: JsonNull)
            put("viewedRequestId", viewedRequestId?.let(::JsonPrimitive) ?: JsonNull)
            put("lastRequestId", lastRequestId?.let(::JsonPrimitive) ?: JsonNull)
            put("updatedAtMs", now)
        }.toString()

    companion object {
        /** A few minutes: long enough for an agent to think between actions, short enough not to linger. */
        const val IDLE_TIMEOUT_MILLIS = 5 * 60_000L

        private const val STATUS_VERSION = 1
    }
}

/** "Agent is browsing [host]", with View and close; a notification instead while [inBackground]. */
internal data class AgentBrowsingNotice(
    val host: String,
    val inBackground: Boolean,
)

/**
 * The blank page a fresh hidden browser opens first, so the app can tell its DevTools target from
 * every other WebView's (an artifact preview, the Guest Browser): only this page shows this marker.
 */
internal object HiddenPageMarker {
    private const val PREFIX = "about:blank#andcode-hidden-browser-"

    fun url(token: String): String = PREFIX + token

    fun new(): String = url(UUID.randomUUID().toString())

    fun isMarker(url: String?): Boolean = url != null && url.startsWith(PREFIX)
}
