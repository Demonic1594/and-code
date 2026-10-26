package com.yugahashimoto.andcode.feature.browser.devtools

import android.content.Context
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.serialization.json.JsonPrimitive

/**
 * Connects one [WebView] to a [DevToolsStore].
 *
 * The page agent (assets/devtools/andcode-devtools.js) is installed as a document-start script, so
 * it sees the page's first console line and request, and reports through a WebMessage listener
 * named [BRIDGE_NAME]. On a WebView too old for document-start scripts it is injected when each
 * page starts instead, which misses what runs before that. The app talks back through
 * [call], which evaluates one of the agent's functions and returns its JSON.
 *
 * Every frame of every page gets the agent, since the browser opens whatever the user or agent
 * types. A page can therefore post to the bridge itself; the worst it can do is put lines in the
 * DevTools log, which only ever displays what it is sent.
 */
class DevToolsBridge(
    val store: DevToolsStore,
    private val script: String,
) {
    private var webView: WebView? = null
    private var startScript: ScriptHandler? = null
    private val textRequests = mutableMapOf<Int, (Boolean, String) -> Unit>()
    private var nextTextToken = 0

    /** False on WebViews that cannot report events; the panel says so instead of staying empty. */
    var receivesEvents: Boolean = false
        private set

    fun attach(view: WebView) {
        webView = view
        receivesEvents = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        if (receivesEvents) {
            // Called on the UI thread, which is where the store expects every event.
            WebViewCompat.addWebMessageListener(view, BRIDGE_NAME, ALL_ORIGINS) { _, message, sourceOrigin, isMainFrame, _ ->
                val raw = message.data ?: return@addWebMessageListener
                val events = DevToolsProtocol.parseEvents(raw, isMainFrame, sourceOrigin.toString())
                // A file read answers only the request that asked, and only from the top page.
                events.filterIsInstance<DevToolsEvent.Text>().forEach { text ->
                    if (isMainFrame) textRequests.remove(text.token)?.invoke(text.ok, text.text)
                }
                store.handleAll(events.filterNot { it is DevToolsEvent.Text })
            }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            startScript = WebViewCompat.addDocumentStartJavaScript(view, script, ALL_ORIGINS)
        }
    }

    fun detach() {
        textRequests.clear()
        startScript?.remove()
        startScript = null
        webView?.let { view ->
            if (receivesEvents) runCatching { WebViewCompat.removeWebMessageListener(view, BRIDGE_NAME) }
        }
        webView = null
    }

    /**
     * From the view's WebViewClient, for the top page only. Where document-start scripts are missing
     * the agent goes in now, late; its first message then resets the log as usual.
     */
    fun onPageStarted() {
        if (startScript == null) webView?.evaluateJavascript(script, null)
    }

    /**
     * Calls `window.__andcodeDT.<function>(args…)` in the page and hands its JSON answer to [onResult],
     * or null when the page has no agent (an error page, a PDF) or the call threw.
     */
    fun call(
        function: String,
        vararg args: Any?,
        onResult: (String?) -> Unit = {},
    ) {
        val view = webView ?: return onResult(null)
        val arguments = args.joinToString(",") { javascriptLiteral(it) }
        val expression =
            "(function(){try{var d=window.__andcodeDT;return d?d.$function($arguments):null}catch(e){return null}})()"
        view.evaluateJavascript(expression) { raw -> onResult(DevToolsProtocol.unwrapJavascriptResult(raw)) }
    }

    /** Reads a file the page loaded (a script, a stylesheet) through the page, then calls [onResult]. */
    fun fetchText(
        url: String,
        onResult: (ok: Boolean, text: String) -> Unit,
    ) {
        val token = ++nextTextToken
        textRequests[token] = onResult
        call("fetchText", url, token) { started -> if (started == null) textRequests.remove(token)?.invoke(false, "") }
    }

    /** Runs what the user typed at the console prompt, and logs it and its answer. */
    fun evaluate(code: String) {
        store.appendInput(code)
        call("evaluate", code) { raw ->
            val result = DevToolsProtocol.parseEvaluation(raw)
            when {
                result == null -> store.appendResult(false, JsonPrimitive(store.messages.cannotRunCode))
                // A Content-Security-Policy without 'unsafe-eval' stops the agent's eval; the
                // WebView's own evaluation is not subject to it, so the code runs that way instead,
                // with its result as plain JSON rather than the agent's richer form.
                !result.ok && previewOf(result.value).startsWith("EvalError") -> evaluateDirectly(code)
                // A promise's outcome arrives as its own event once it settles.
                result.pendingToken != null -> store.appendResult(true, result.value)
                else -> store.appendResult(result.ok, result.value)
            }
        }
    }

    private fun evaluateDirectly(code: String) {
        val view = webView ?: return store.appendResult(false, JsonPrimitive(store.messages.cannotRunCode))
        view.evaluateJavascript(code) { raw ->
            val value = raw?.let { runCatching { devToolsJson.parseToJsonElement(it) }.getOrNull() } ?: JsonPrimitive(raw.orEmpty())
            store.appendResult(true, value)
        }
    }

    companion object {
        const val BRIDGE_NAME = "__andcodeDevtoolsBridge"
        private val ALL_ORIGINS = setOf("*")
        private const val SCRIPT_ASSET = "devtools/andcode-devtools.js"

        fun loadScript(context: Context): String = context.assets.open(SCRIPT_ASSET).bufferedReader().use { it.readText() }

        internal fun javascriptLiteral(value: Any?): String =
            when (value) {
                null -> "null"
                is Boolean, is Int, is Long -> value.toString()
                is Double -> if (value.isFinite()) value.toString() else "null"
                // A JSON string literal is a valid JavaScript one, quotes and newlines escaped.
                else -> JsonPrimitive(value.toString()).toString()
            }
    }
}
