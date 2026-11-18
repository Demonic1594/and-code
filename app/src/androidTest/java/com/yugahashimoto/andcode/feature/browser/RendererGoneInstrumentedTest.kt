package com.yugahashimoto.andcode.feature.browser

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.feature.chat.ArtifactWebView
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Kills the renderer under each of the app's WebViews, as Android does under memory pressure or a
 * page does by crashing it, and checks the app lives on with the page offered back. Unhandled, the
 * whole test process would die with the renderer.
 */
@RunWith(AndroidJUnit4::class)
class RendererGoneInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun guestBrowserSurvivesItsRendererCrashingAndReloadsInAFreshView() {
        composeRule.setContent {
            MaterialTheme { GuestBrowserScreen(initialUrl = "", agentControl = false, onAgentControlChange = {}, onBack = {}) }
        }
        killRendererAndReload(CRASH_URL)
    }

    @Test
    fun guestBrowserSurvivesAndroidKillingItsRendererAndReloadsInAFreshView() {
        composeRule.setContent {
            MaterialTheme { GuestBrowserScreen(initialUrl = "", agentControl = false, onAgentControlChange = {}, onBack = {}) }
        }
        killRendererAndReload(KILL_URL)
    }

    @Test
    fun artifactPreviewSurvivesItsRendererCrashingAndReloadsInAFreshView() {
        composeRule.setContent {
            MaterialTheme { ArtifactWebView(html = "<p>artifact</p>", path = "page.html") }
        }
        killRendererAndReload(CRASH_URL)
    }

    private fun killRendererAndReload(debugUrl: String) {
        lateinit var first: WebView
        // A page first, so a renderer is running for the debug address to end.
        composeRule.runOnIdle {
            first = requireNotNull(composeRule.activity.window.decorView.findWebView())
            first.loadDataWithBaseURL(null, "<p>page</p>", "text/html", "UTF-8", null)
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            var loaded = false
            composeRule.runOnIdle { loaded = first.progress == 100 }
            loaded
        }
        composeRule.runOnIdle { first.loadUrl(debugUrl) }
        val title = context.getString(R.string.webview_page_gone_title)
        composeRule.waitUntil(timeoutMillis = 15_000) { composeRule.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
        // The dead view is out of the tree, and no other has taken its place before the user asks.
        composeRule.runOnIdle { assertNull(composeRule.activity.window.decorView.findWebView()) }

        composeRule.onNodeWithText(context.getString(R.string.webview_page_gone_reload)).performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) { composeRule.onAllNodesWithText(title).fetchSemanticsNodes().isEmpty() }
        composeRule.runOnIdle {
            val second = composeRule.activity.window.decorView.findWebView()
            assertNotNull(second)
            assertNotSame(first, second)
        }
    }

    private fun View.findWebView(): WebView? {
        if (this is WebView) return this
        if (this is ViewGroup) {
            for (index in 0 until childCount) {
                getChildAt(index).findWebView()?.let { return it }
            }
        }
        return null
    }

    private companion object {
        // WebView's own debug addresses: one crashes the renderer, the other ends it as Android would.
        const val CRASH_URL = "chrome://crash"
        const val KILL_URL = "chrome://kill"
    }
}
