package com.yugahashimoto.andcode.feature.browser

import android.content.MutableContextWrapper
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yugahashimoto.andcode.feature.browser.devtools.AndroidDevToolsMessages
import com.yugahashimoto.andcode.feature.browser.devtools.DevToolsBridge
import com.yugahashimoto.andcode.feature.browser.devtools.DevToolsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The parts of the hidden browser only a device can check: a WebView never put in a window still
 * counts as visible and lays out at the size it is given, and View shows that same WebView in the
 * Guest Browser without loading its page again.
 */
@RunWith(AndroidJUnit4::class)
class HiddenAgentBrowserInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun aPageNeverPutInAWindowCountsAsVisibleAndLaysOutAtItsSize() {
        lateinit var view: WebView
        instrumentation.runOnMainSync {
            view = hiddenView()
            view.measure(
                View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, WIDTH, HEIGHT)
        }
        load(view, "<p>hidden</p>")

        val answer = evaluate(view, "document.visibilityState + ':' + (innerWidth > 0)")

        assertEquals("\"visible:true\"", answer)
        instrumentation.runOnMainSync { view.destroy() }
    }

    @Test
    fun viewShowsTheSamePageWithoutLoadingItAgain() {
        lateinit var view: WebView
        lateinit var handoff: AgentPageHandoff
        val context = instrumentation.targetContext
        instrumentation.runOnMainSync {
            val wrapper = MutableContextWrapper(context.applicationContext)
            view = WebView(wrapper)
            view.applyGuestBrowserSettings()
            val bridge = DevToolsBridge(DevToolsStore(AndroidDevToolsMessages(context)), DevToolsBridge.loadScript(context))
            bridge.attach(view)
            handoff = AgentPageHandoff(view, bridge, targetId = "T", context = wrapper)
        }
        load(view, "<script>window.loads = (window.loads || 0) + 1</script>")
        instrumentation.runOnMainSync { view.evaluateJavascript("window.typedByAgent = 'kept'", null) }

        composeRule.setContent {
            MaterialTheme {
                GuestBrowserScreen(initialUrl = "", agentControl = true, onAgentControlChange = {}, onBack = {}, agentPage = handoff)
            }
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle { assertNotNull("the same view is on screen", view.parent) }
        assertEquals("\"kept:1\"", evaluate(view, "window.typedByAgent + ':' + window.loads"))
    }

    private fun hiddenView(): WebView =
        WebView(MutableContextWrapper(instrumentation.targetContext.applicationContext)).apply { applyGuestBrowserSettings() }

    private fun load(
        view: WebView,
        html: String,
    ) {
        val loaded = CountDownLatch(1)
        instrumentation.runOnMainSync {
            view.webViewClient =
                object : WebViewClient() {
                    override fun onPageFinished(
                        view: WebView?,
                        url: String?,
                    ) {
                        loaded.countDown()
                    }
                }
            view.loadDataWithBaseURL("https://hidden.example/", html, "text/html", "utf-8", null)
        }
        assertTrue("the page loads", loaded.await(15, TimeUnit.SECONDS))
    }

    private fun evaluate(
        view: WebView,
        script: String,
    ): String? {
        var answer: String? = null
        val done = CountDownLatch(1)
        instrumentation.runOnMainSync {
            view.evaluateJavascript(script) {
                answer = it
                done.countDown()
            }
        }
        assertTrue("the page answers", done.await(15, TimeUnit.SECONDS))
        return answer
    }

    private companion object {
        const val WIDTH = 412
        const val HEIGHT = 915
    }
}
