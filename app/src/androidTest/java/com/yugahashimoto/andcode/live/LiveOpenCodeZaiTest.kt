package com.yugahashimoto.andcode.live

import android.content.ClipboardManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.yugahashimoto.andcode.MainActivity
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.feature.chat.ChatPart
import com.yugahashimoto.andcode.feature.chat.ChatViewModel
import com.yugahashimoto.andcode.feature.chat.replyMarkdown
import com.yugahashimoto.andcode.feature.chat.replyPlainText
import com.yugahashimoto.andcode.runtime.LocalAgent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OpenCode on the device with a real z.ai GLM Coding Plan key (secret `ZAI_API_KEY`).
 *
 * The key goes where Settings' provider dialog puts it, the app's provider credential store, which
 * the runtime writes into OpenCode's `auth.json` under the `zai-coding-plan` provider on start.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class LiveOpenCodeZaiTest {
    private lateinit var model: String

    @Before
    fun setUp() {
        val key = LiveAgents.requireKey(LiveAgents.ARG_ZAI_KEY)
        model = LiveAgents.argument(LiveAgents.ARG_ZAI_MODEL) ?: LiveAgents.DEFAULT_ZAI_MODEL
        val app = LiveAgents.app
        app.providerCredentials.setCredential(LiveAgents.ZAI_PROVIDER_ID, key)
        LiveAgents.ensureRuntimeInstalled()
        // The credential is synced into the guest on every start, so restart to pick it up when the
        // runtime was already running before this test stored it.
        runBlocking {
            app.localRuntimeManager.stop()
            app.localRuntimeManager.start().getOrElse { fail("OpenCode did not restart: ${it.message}") }
        }
        assertModelIsOffered()
        app.preferences.selectModel(LiveAgents.ZAI_PROVIDER_ID, model)
        app.preferences.setAutoAcceptPermissions(true)
    }

    @After
    fun tearDown() {
        // The emulator is thrown away after the run; this only keeps a rerun on a kept one honest.
        if (LiveAgents.argument(LiveAgents.ARG_ZAI_KEY) != null) {
            LiveAgents.app.providerCredentials.clearCredential(LiveAgents.ZAI_PROVIDER_ID)
        }
    }

    /** Fails with the plan's model list when [model] is not one OpenCode offers for it. */
    private fun assertModelIsOffered() {
        val backend = LiveAgents.app.runtimeRegistry.target(LocalAgent.OPEN_CODE.targetId) ?: fail("OpenCode is not registered") as Nothing
        val catalog = runBlocking { backend.listProviders() }
        assertTrue(
            "OpenCode does not count ${LiveAgents.ZAI_PROVIDER_ID} as connected; the key was not picked up",
            LiveAgents.ZAI_PROVIDER_ID in catalog.connected,
        )
        val provider =
            catalog.all.firstOrNull { it.id == LiveAgents.ZAI_PROVIDER_ID }
                ?: fail("OpenCode does not know the ${LiveAgents.ZAI_PROVIDER_ID} provider") as Nothing
        assertTrue(
            "$model is not a ${LiveAgents.ZAI_PROVIDER_ID} model; offered: ${provider.models.keys.sorted()}",
            provider.models.containsKey(model),
        )
    }

    /** The chat uses the plan's model only once the app has checked it against OpenCode's catalogue. */
    private val usesPlanModel: (ChatViewModel) -> Unit = { viewModel ->
        LiveAgents.waitForState(viewModel, LiveAgents.UI_TIMEOUT_MS, "the chat did not select $model") {
            it.selectedProviderId == LiveAgents.ZAI_PROVIDER_ID && it.selectedModelId == model
        }
    }

    /** Also covers the reply's action row (#143): Copy puts the reply on the clipboard, Retry sends the prompt again. */
    @Test
    fun createsTheFileItIsAskedFor() {
        LiveAgents.assertCreatesHelloFile(LocalAgent.OPEN_CODE, prepare = usesPlanModel, afterReply = ::copyAndRetry)
    }

    private fun copyAndRetry(
        scenario: ActivityScenario<MainActivity>,
        viewModel: ChatViewModel,
    ) {
        val app = LiveAgents.app
        // What Copy copies: every non-blank text part of the turn, as the reply footer collects them
        // (OpenCode splits a turn into one message per model step).
        val turnTexts =
            viewModel.uiState.value.messages
                .takeLastWhile { !it.isUser }
                .flatMap { it.parts }
                .filterIsInstance<ChatPart.Text>()
                .map { it.text }
                .filter { it.isNotBlank() }
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

        val copy =
            device.wait(Until.findObject(By.desc(app.getString(R.string.reply_action_copy))), LiveAgents.UI_TIMEOUT_MS)
                ?: fail("The reply has no Copy action") as Nothing
        copy.click()
        val deadline = System.currentTimeMillis() + LiveAgents.UI_TIMEOUT_MS
        var copied: String? = null
        while (System.currentTimeMillis() < deadline && copied.isNullOrBlank()) {
            scenario.onActivity { activity ->
                val clipboard = activity.getSystemService(ClipboardManager::class.java)
                copied = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(activity)?.toString()
            }
            if (copied.isNullOrBlank()) Thread.sleep(LiveAgents.POLL_MS)
        }

        // Copy gives the reply as plain text (Markdown rendered) or as its Markdown; either counts.
        fun normalized(text: String) = text.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        val expected = setOf(replyPlainText(turnTexts), replyMarkdown(turnTexts)).map(::normalized)
        assertTrue("Copy did not put the reply on the clipboard", copied?.let(::normalized) in expected)

        val retry =
            device.wait(Until.findObject(By.desc(app.getString(R.string.reply_action_retry))), LiveAgents.UI_TIMEOUT_MS)
                ?: fail("The reply has no Retry action") as Nothing
        retry.click()
        // A runtime that cannot rewind asks first whether to restore the files the reply changed;
        // keep them if it asks.
        device.wait(Until.findObject(By.text(app.getString(R.string.reply_retry_as_is))), RETRY_DIALOG_WAIT_MS)?.click()
        LiveAgents.waitForState(viewModel, LiveAgents.UI_TIMEOUT_MS, "Retry did not send the prompt again") { state ->
            state.messages.count { it.isUser && it.text.trim() == LiveAgents.CREATE_FILE_PROMPT } >= 2
        }
        // The retried turn must end with a reply of its own, after the second prompt.
        LiveAgents.waitForState(viewModel, LiveAgents.TURN_TIMEOUT_MS, "the retried turn did not end with a reply") { state ->
            LiveAgents.turnSucceeded(state)
        }
    }

    /**
     * browser_show in hidden mode (#142): the agent reads the page while the user's screen stays on
     * the chat, with the "Agent is browsing" notice above it.
     */
    @Test
    fun browsesHiddenWithoutTakingTheScreen() {
        val app = LiveAgents.app
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val noticePrefix = app.getString(R.string.agent_browsing_notice, "").trim()
        val sawHiddenPage = AtomicBoolean(false)
        val sawGuestBrowser = AtomicBoolean(false)
        val sawNotice = AtomicBoolean(false)
        val watcher = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        watcher.launch {
            app.agentBrowser.state.collect { state ->
                if (state.hidden != null) sawHiddenPage.set(true)
                if (state.guestBrowserOpen) sawGuestBrowser.set(true)
            }
        }
        watcher.launch {
            while (true) {
                if (device.hasObject(By.textContains(noticePrefix))) sawNotice.set(true)
                kotlinx.coroutines.delay(LiveAgents.POLL_MS)
            }
        }

        LiveAgents.selectRuntime(LocalAgent.OPEN_CODE)
        val (_, guestPath) = LiveAgents.freshProject("live-browser")
        val reply =
            try {
                LiveAgents.launchApp().use { scenario ->
                    val viewModel = LiveAgents.chatViewModel(scenario, LocalAgent.OPEN_CODE)
                    LiveAgents.startChatIn(scenario, viewModel, guestPath)
                    usesPlanModel(viewModel)
                    LiveAgents.sendFromComposer(BROWSER_PROMPT)
                    LiveAgents.awaitAssistantReply(viewModel)
                }
            } finally {
                watcher.cancel()
            }

        assertTrue(
            "the reply does not name the page title: ${reply.take(REPLY_EXCERPT)}",
            reply.contains("Example Domain", ignoreCase = true),
        )
        assertTrue("the agent never opened the hidden browser", sawHiddenPage.get())
        assertTrue("the \"Agent is browsing\" notice never appeared", sawNotice.get())
        assertFalse("the Guest Browser screen came to the foreground", sawGuestBrowser.get())
    }

    private companion object {
        const val REPLY_EXCERPT = 200
        const val RETRY_DIALOG_WAIT_MS = 5_000L
        const val BROWSER_PROMPT =
            "Use the browser_show tool with mode \"hidden\" to open https://example.com, " +
                "read the page, and reply with the page's title only."
    }
}
