package com.yugahashimoto.andcode.live

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.yugahashimoto.andcode.AndCodeApplication
import com.yugahashimoto.andcode.MainActivity
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.feature.chat.ChatPart
import com.yugahashimoto.andcode.feature.chat.ChatUiState
import com.yugahashimoto.andcode.feature.chat.ChatViewModel
import com.yugahashimoto.andcode.runtime.LocalAgent
import com.yugahashimoto.andcode.runtime.LocalRuntimeStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import java.io.File

/**
 * Shared plumbing for the live agent tests: real runtimes, real providers, real network.
 *
 * These run only in the hand-started "Live agent tests" workflow, which hands the provider keys in
 * as instrumentation arguments. Without its argument each test is skipped (an assumption failure),
 * so the ordinary device-test run passes over them without installing anything.
 *
 * Keys are read here and passed straight to the app's own stores. Nothing in this package may log,
 * print or put a key in an assertion message: the workflow scrubs its artifacts, but the first
 * line of defence is never writing the value anywhere.
 */
internal object LiveAgents {
    /** Instrumentation arguments the workflow sets. Each is absent unless its secret is. */
    const val ARG_LIVE = "liveAgentTests"
    const val ARG_ZAI_KEY = "zaiKey"
    const val ARG_ZAI_MODEL = "zaiModel"
    const val ARG_ANTHROPIC_KEY = "anthropicKey"
    const val ARG_OPENAI_KEY = "openaiKey"

    /** OpenCode's own provider id for the z.ai GLM Coding Plan (models.dev `zai-coding-plan`). */
    const val ZAI_PROVIDER_ID = "zai-coding-plan"

    /** A model every Coding Plan tier includes; the workflow can override it. */
    const val DEFAULT_ZAI_MODEL = "glm-4.7"

    /** Downloads of the sandbox and agents can take a long time on a hosted runner. */
    const val INSTALL_TIMEOUT_MS = 30 * 60_000L

    /** One short prompt per agent; a model that has not finished in this time has failed. */
    const val TURN_TIMEOUT_MS = 6 * 60_000L

    const val UI_TIMEOUT_MS = 30_000L

    const val CREATE_FILE_PROMPT = "Create a file named hello.txt containing exactly: hi"

    private val arguments get() = InstrumentationRegistry.getArguments()

    val app: AndCodeApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AndCodeApplication

    fun argument(name: String): String? = arguments.getString(name)?.trim()?.takeIf { it.isNotEmpty() }

    /** Skips the calling test unless the live workflow started it. */
    fun assumeLiveRun() {
        assumeTrue("Live agent tests run only from the live-agent-tests workflow", argument(ARG_LIVE) == "true")
    }

    /** Skips the calling test unless [name] was passed; returns its value. Never put it in a message. */
    fun requireKey(name: String): String {
        assumeLiveRun()
        val value = argument(name)
        assumeTrue("No $name argument: its secret is not set", value != null)
        return checkNotNull(value)
    }

    /** Every agent some key was given for, so the sandbox is provisioned in a single install. */
    private fun wantedAgents(): Set<LocalAgent> =
        buildSet {
            add(LocalAgent.OPEN_CODE)
            if (argument(ARG_ANTHROPIC_KEY) != null) add(LocalAgent.CLAUDE_CODE)
            if (argument(ARG_OPENAI_KEY) != null) add(LocalAgent.CODEX)
        }

    private val installLock = Any()

    /**
     * Installs the local runtime through [com.yugahashimoto.andcode.runtime.local.LocalRuntimeManager.installAndStart],
     * the call the setup guide's install button reaches through the runtime service, and waits for
     * the OpenCode server to be up. Later callers find it installed and only wait for it to run.
     */
    fun ensureRuntimeInstalled(): LocalRuntimeStatus.Ready =
        synchronized(installLock) {
            runBlocking {
                val manager = app.localRuntimeManager
                val wanted = wantedAgents()
                val installed = app.localRuntimeInstaller.installedRuntime()?.metadata
                val result =
                    withTimeout(INSTALL_TIMEOUT_MS) {
                        if (installed == null || wanted.any { !installed.has(it) }) {
                            manager.installAndStart(agents = wanted)
                        } else {
                            manager.ensureRunning()
                        }
                    }
                val ready = result.getOrElse { error -> fail("Local runtime install failed: ${error.message}") as Nothing }
                // The setup guide marks onboarding done once a runtime is usable; the chat screen
                // is the start destination from then on.
                app.settings.onboardingCompleted = true
                skipStarPrompts()
                ready
            }
        }

    /**
     * Answers the "Support AndCode?" prompts as a user who chose Maybe later and dismissed the
     * follow-up would have, so neither covers the chat: the first shows on launch once onboarding is
     * done, the second after a chat's first finished turn.
     */
    private fun skipStarPrompts() {
        app.settings.githubStarPromptShown = true
        app.settings.githubStarPromptDeferred = true
        app.settings.githubStarSecondPromptShown = true
        app.settings.githubStarThankYouShown = true
    }

    /** The guest's `/workspace`, as this app's storage holds it. */
    fun hostWorkspace(): File = File(app.filesDir, "runtime/workspace")

    /** A project folder no earlier run has touched, as both the host and the guest name it. */
    fun freshProject(prefix: String): Pair<File, String> {
        val name = "$prefix-${System.currentTimeMillis()}"
        val host = File(hostWorkspace(), name)
        assertTrue("could not create the project folder", host.mkdirs())
        return host to "/workspace/$name"
    }

    fun selectRuntime(agent: LocalAgent) {
        assertTrue("the ${agent.id} runtime is not registered", app.runtimeRegistry.select(agent.targetId))
    }

    fun launchApp(): ActivityScenario<MainActivity> {
        // UiAutomator waits up to 10 s for the UI to settle before every lookup; a chat with a run
        // in progress keeps changing, so look straight away instead.
        Configurator.getInstance().waitForIdleTimeout = UI_IDLE_WAIT_MS
        return ActivityScenario.launch(MainActivity::class.java)
    }

    /**
     * The chat view model the app's screen uses: AndCodeApp keys it by the selected runtime in the
     * activity's store, so this is the very instance behind the visible chat.
     */
    fun chatViewModel(
        scenario: ActivityScenario<MainActivity>,
        agent: LocalAgent,
    ): ChatViewModel {
        var viewModel: ChatViewModel? = null
        val deadline = System.currentTimeMillis() + UI_TIMEOUT_MS
        while (viewModel == null && System.currentTimeMillis() < deadline) {
            scenario.onActivity { activity -> viewModel = existingChatViewModel(activity, agent) }
            if (viewModel == null) Thread.sleep(POLL_MS)
        }
        return viewModel ?: fail("The chat screen never created its view model for ${agent.id}") as Nothing
    }

    private fun existingChatViewModel(
        activity: Activity,
        agent: LocalAgent,
    ): ChatViewModel? =
        runCatching {
            val owner = activity as androidx.lifecycle.ViewModelStoreOwner
            ViewModelProvider(owner, NoNewViewModels)["chat-${agent.targetId}", ChatViewModel::class.java]
        }.getOrNull()

    /** Refuses to build one: a view model this test made would not be the one the screen shows. */
    private object NoNewViewModels : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T = error("not created yet")
    }

    /** What the drawer's project entry does: a new chat in [guestPath]. */
    fun startChatIn(
        scenario: ActivityScenario<MainActivity>,
        viewModel: ChatViewModel,
        guestPath: String,
    ) {
        scenario.onActivity {
            viewModel.newSession()
            viewModel.selectWorkspace(guestPath)
        }
        waitForState(viewModel, UI_TIMEOUT_MS, "the chat never moved to the fresh project") {
            it.sessionId == null && it.selectedWorkspacePath == guestPath
        }
    }

    /**
     * Types [prompt] into the composer and presses Send, as a user does. UiAutomator rather than
     * Compose's test finders: those wait for the UI to go idle, which it never does while the
     * agent's progress indicator spins.
     */
    fun sendFromComposer(prompt: String) {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val input =
            device.wait(Until.findObject(By.clazz("android.widget.EditText")), UI_TIMEOUT_MS)
                ?: fail("The chat composer is not on screen") as Nothing
        input.click()
        input.text = prompt
        val sendLabel = app.getString(R.string.send_description)
        val send =
            device.wait(Until.findObject(By.desc(sendLabel)), UI_TIMEOUT_MS)
                ?: fail("The Send button is not on screen") as Nothing
        send.click()
    }

    fun waitForState(
        viewModel: ChatViewModel,
        timeoutMs: Long,
        failure: String,
        condition: (ChatUiState) -> Boolean,
    ): ChatUiState =
        runBlocking {
            runCatching { withTimeout(timeoutMs) { viewModel.uiState.first(condition) } }
                .getOrElse { fail("$failure (last error: ${viewModel.uiState.value.error?.take(ERROR_EXCERPT)})") as Nothing }
        }

    /** Waits for the turn to start and then finish, and returns the assistant's last reply. */
    fun awaitAssistantReply(viewModel: ChatViewModel): String {
        waitForState(viewModel, UI_TIMEOUT_MS, "the prompt was never sent") { state ->
            state.isRunning || state.messages.any { it.isUser }
        }
        val done =
            waitForState(viewModel, TURN_TIMEOUT_MS, "the agent did not finish its turn") { state ->
                turnSucceeded(state)
            }
        return done.messages.last { !it.isUser && it.text.isNotBlank() }.text
    }

    /**
     * Whether the latest turn has ended with a reply and no error, neither the chat's own nor one
     * the runtime left in the transcript. A turn that recovers before the timeout still counts.
     */
    fun turnSucceeded(state: ChatUiState): Boolean {
        val turn = state.messages.takeLastWhile { !it.isUser }
        return !state.isRunning &&
            state.error == null &&
            turn.none { message -> message.parts.any { it is ChatPart.Error } } &&
            turn.any { it.text.isNotBlank() && !it.isStreaming }
    }

    /** The create-a-file check every agent gets: the file, with exactly that content, and a reply. */
    fun assertCreatesHelloFile(
        agent: LocalAgent,
        prepare: (ChatViewModel) -> Unit = {},
        afterReply: (ActivityScenario<MainActivity>, ChatViewModel) -> Unit = { _, _ -> },
    ) {
        selectRuntime(agent)
        val (project, guestPath) = freshProject("live-${agent.id}")
        launchApp().use { scenario ->
            val viewModel = chatViewModel(scenario, agent)
            startChatIn(scenario, viewModel, guestPath)
            prepare(viewModel)
            sendFromComposer(CREATE_FILE_PROMPT)
            val reply = awaitAssistantReply(viewModel)
            assertTrue("the assistant reply is empty", reply.isNotBlank())
            val hello = File(project, "hello.txt")
            assertTrue("hello.txt was not created in the project", hello.isFile)
            assertEquals("hi", hello.readText().trim())
            afterReply(scenario, viewModel)
        }
    }

    const val POLL_MS = 250L

    private const val UI_IDLE_WAIT_MS = 500L

    private const val ERROR_EXCERPT = 300
}
