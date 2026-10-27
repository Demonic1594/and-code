package com.yugahashimoto.andcode.feature.chat

import androidx.lifecycle.viewModelScope
import com.yugahashimoto.andcode.core.api.OpenCodeAgent
import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.OpenCodeHealth
import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodeMessageError
import com.yugahashimoto.andcode.core.api.OpenCodeMessageInfo
import com.yugahashimoto.andcode.core.api.OpenCodePart
import com.yugahashimoto.andcode.core.api.OpenCodeSession
import com.yugahashimoto.andcode.core.api.OpenCodeTime
import com.yugahashimoto.andcode.core.api.PermissionRequest
import com.yugahashimoto.andcode.core.api.PromptRequest
import com.yugahashimoto.andcode.core.api.ProviderCatalog
import com.yugahashimoto.andcode.core.api.RunRetry
import com.yugahashimoto.andcode.core.diagnostics.StallReason
import com.yugahashimoto.andcode.runtime.BackendKind
import com.yugahashimoto.andcode.runtime.OpenCodeBackend
import com.yugahashimoto.andcode.runtime.PermissionResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Covers the answer to "is it still working, or did it die?" for a turn that produces nothing.
 *
 * Every test sends a prompt and then lets the clock run past the post-send poll, which is what
 * leaves a real run with nothing watching it: no events, no polling, and a composer stuck on the
 * stop button.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelStallTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a run that produces nothing is reported as stalled`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = viewModel(backend)

            viewModel.sendMessage("Hello")
            runSilentlyPastThePoll()
            viewModel.checkForStall()
            advanceUntilIdle()

            val stall = viewModel.uiState.value.stall
            assertEquals(StallReason.NO_OUTPUT, stall?.reason)
            assertTrue((stall?.silentForMillis ?: 0L) >= STALL_THRESHOLD_MS)
            assertTrue(viewModel.uiState.value.isRunning)
        }

    @Test
    fun `coming back to the app re-reads the open chat`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val onScreen = MutableStateFlow(true)
            val viewModel = viewModel(backend, onScreen)
            viewModel.sendMessage("Hello")
            // Bounded steps only: a running chat keeps its catch-up loop going, so the clock never idles.
            advanceTimeBy(1_000L)
            runCurrent()

            // Away long enough to count, landing between two ticks of the post-send poll so only
            // the return itself can bring in the reply written meanwhile.
            onScreen.value = false
            runCurrent()
            advanceTimeBy(RESYNC_AFTER_AWAY_MS + 500L)
            backend.historyMessages = listOf(assistantMessage(text = "Done while you were away", completed = 3L))
            onScreen.value = true
            runCurrent()

            assertTrue(viewModel.uiState.value.messages.any { message -> message.text.contains("Done while you were away") })
            viewModel.stopCatchingUp()
        }

    @Test
    fun `a short glance away does not re-read the chat`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val onScreen = MutableStateFlow(true)
            viewModel(backend, onScreen)
            val calls = backend.listMessagesCalls

            onScreen.value = false
            runCurrent()
            advanceTimeBy(2_000L)
            onScreen.value = true
            runCurrent()

            assertEquals(calls, backend.listMessagesCalls)
        }

    @Test
    fun `a running chat that hears nothing live catches up from the transcript`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = viewModel(backend, MutableStateFlow(true))
            viewModel.sendMessage("Hello")
            // Past the post-send poll, which would otherwise be what brings the reply in.
            runSilentlyPastThePoll()
            val calls = backend.listMessagesCalls
            backend.historyMessages = listOf(assistantMessage(text = "Partial answer"))

            advanceTimeBy(CATCH_UP_MAX_INTERVAL_MS + 2 * CATCH_UP_CHECK_MS)
            runCurrent()

            assertTrue(backend.listMessagesCalls > calls)
            assertTrue(viewModel.uiState.value.messages.any { message -> message.text.contains("Partial answer") })
            assertTrue(viewModel.uiState.value.isRunning)
            viewModel.stopCatchingUp()
        }

    @Test
    fun `a re-read while a reply streams keeps what streamed and lets the next delta extend it`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val onScreen = MutableStateFlow(true)
            val viewModel = viewModel(backend, onScreen)
            viewModel.sendMessage("Hello")
            advanceTimeBy(1_000L)
            runCurrent()
            backend.events.emit(OpenCodeEvent.MessagePartDelta("s1", "assistant-1", "text-1", "text", "Hello"))
            backend.events.emit(OpenCodeEvent.MessagePartDelta("s1", "assistant-1", "text-1", "text", " world"))
            runCurrent()

            // The transcript lags behind the stream: it only has the first delta.
            onScreen.value = false
            runCurrent()
            advanceTimeBy(RESYNC_AFTER_AWAY_MS + 500L)
            backend.historyMessages = listOf(assistantMessage(text = "Hello"))
            onScreen.value = true
            runCurrent()
            backend.events.emit(OpenCodeEvent.MessagePartDelta("s1", "assistant-1", "text-1", "text", "!"))
            runCurrent()

            val reply = viewModel.uiState.value.messages.single { it.id == "assistant-1" }
            assertEquals("Hello world!", reply.text)
            viewModel.stopCatchingUp()
        }

    @Test
    fun `a re-read takes the transcript's text when the stream fell behind it`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val onScreen = MutableStateFlow(true)
            val viewModel = viewModel(backend, onScreen)
            viewModel.sendMessage("Say hi")
            advanceTimeBy(1_000L)
            runCurrent()
            backend.events.emit(OpenCodeEvent.MessagePartDelta("s1", "assistant-1", "text-1", "text", "Hello"))
            runCurrent()

            // The stream died quietly while the app was away; the reply kept going without it.
            onScreen.value = false
            runCurrent()
            advanceTimeBy(RESYNC_AFTER_AWAY_MS + 500L)
            backend.historyMessages = listOf(userMessage("Say hi"), assistantMessage(text = "Hello world"))
            onScreen.value = true
            runCurrent()
            assertEquals("Hello world", viewModel.uiState.value.messages.single { it.id == "assistant-1" }.text)

            backend.events.emit(OpenCodeEvent.MessagePartDelta("s1", "assistant-1", "text-1", "text", "!"))
            runCurrent()
            assertEquals("Hello world!", viewModel.uiState.value.messages.single { it.id == "assistant-1" }.text)
            viewModel.stopCatchingUp()
        }

    @Test
    fun `a turn that ended while the stream was silent is wrapped up on return`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val onScreen = MutableStateFlow(true)
            val viewModel = viewModel(backend, onScreen)
            viewModel.sendMessage("Say hi")
            advanceTimeBy(1_000L)
            runCurrent()
            backend.events.emit(OpenCodeEvent.MessagePartDelta("s1", "assistant-1", "text-1", "text", "Hel"))
            runCurrent()

            onScreen.value = false
            runCurrent()
            advanceTimeBy(CATCH_UP_QUIET_MS + 500L)
            backend.historyMessages = listOf(userMessage("Say hi"), assistantMessage(text = "Hello there", completed = 3L))
            onScreen.value = true
            runCurrent()

            assertFalse(viewModel.uiState.value.isRunning)
            assertEquals("Hello there", viewModel.uiState.value.messages.single { it.id == "assistant-1" }.text)
            viewModel.stopCatchingUp()
        }

    @Test
    fun `a re-read that comes back after the turn ended does not roll the reply back`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val onScreen = MutableStateFlow(true)
            val viewModel = viewModel(backend, onScreen)
            viewModel.sendMessage("Hello")
            advanceTimeBy(1_000L)
            runCurrent()

            // A re-read on return that is slow to answer, with a snapshot from mid-turn...
            onScreen.value = false
            runCurrent()
            advanceTimeBy(RESYNC_AFTER_AWAY_MS + 500L)
            backend.historyMessages = listOf(assistantMessage(text = "Half"))
            backend.listMessagesDelayMs = 2_000L
            onScreen.value = true
            runCurrent()

            // ...overtaken by the turn ending and its own final reload.
            backend.listMessagesDelayMs = 0L
            backend.historyMessages = listOf(assistantMessage(text = "Half and the rest", completed = 3L))
            backend.events.emit(OpenCodeEvent.SessionIdle("s1"))
            runCurrent()
            advanceTimeBy(3_000L)
            runCurrent()

            val reply = viewModel.uiState.value.messages.single { it.id == "assistant-1" }
            assertEquals("Half and the rest", reply.text)
            viewModel.stopCatchingUp()
        }

    @Test
    fun `a quiet run is left alone until the threshold passes`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = viewModel(backend)

            viewModel.sendMessage("Hello")
            advanceTimeBy(STALL_THRESHOLD_MS - 1000L)
            runCurrent()
            viewModel.checkForStall()
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.stall)
        }

    @Test
    fun `an unreachable runtime is named as the reason`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = viewModel(backend)

            viewModel.sendMessage("Hello")
            runSilentlyPastThePoll()
            backend.healthError = IOException("connection refused")
            viewModel.checkForStall()
            advanceUntilIdle()

            val stall = viewModel.uiState.value.stall
            assertEquals(StallReason.RUNTIME_UNREACHABLE, stall?.reason)
            assertEquals("connection refused", stall?.detail)
        }

    @Test
    fun `a tool left running is named as what the turn is waiting on`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = viewModel(backend)

            viewModel.sendMessage("Hello")
            runSilentlyPastThePoll()
            backend.historyMessages = listOf(assistantMessage(runningTool = "./gradlew test"))
            viewModel.checkForStall()
            advanceUntilIdle()

            assertEquals(StallReason.TOOL_RUNNING, viewModel.uiState.value.stall?.reason)
            assertEquals("./gradlew test", viewModel.uiState.value.stall?.detail)
        }

    @Test
    fun `an unanswered approval is named, and not painted as a failure`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = viewModel(backend)

            viewModel.sendMessage("Hello")
            backend.events.emit(
                OpenCodeEvent.PermissionAsked(
                    PermissionRequest(id = "perm-1", sessionId = "s1", permission = "bash"),
                ),
            )
            runSilentlyPastThePoll()
            viewModel.checkForStall()
            advanceUntilIdle()

            val stall = viewModel.uiState.value.stall
            assertEquals(StallReason.AWAITING_PERMISSION, stall?.reason)
            // The turn is blocked, not dead, so the card stays out of the failure colour.
            assertFalse(stall?.isStopped ?: true)
        }

    @Test
    fun `a turn that finished unheard is settled instead of flagged`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = viewModel(backend)

            viewModel.sendMessage("Hello")
            runSilentlyPastThePoll()
            // The idle never arrived, so only the transcript knows the turn is over.
            backend.historyMessages = listOf(assistantMessage(completed = 5L, text = "done"))
            viewModel.checkForStall()
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.stall)
            assertFalse(viewModel.uiState.value.isRunning)
            assertEquals("done", viewModel.uiState.value.messages.last().text)
        }

    @Test
    fun `a provider failure ends the run and surfaces the provider's message`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = viewModel(backend)

            viewModel.sendMessage("Hello")
            runSilentlyPastThePoll()
            backend.historyMessages =
                listOf(
                    assistantMessage(
                        text = "partial",
                        error =
                            OpenCodeMessageError(
                                name = "ApiError",
                                data = mapOf("message" to JsonPrimitive("rate limited")),
                            ),
                    ),
                )
            viewModel.checkForStall()
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.stall)
            assertFalse(viewModel.uiState.value.isRunning)
            assertEquals("rate limited", viewModel.uiState.value.error)
        }

    @Test
    fun `progress on the session takes the warning back down`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = viewModel(backend)

            viewModel.sendMessage("Hello")
            runSilentlyPastThePoll()
            viewModel.checkForStall()
            advanceUntilIdle()
            assertEquals(StallReason.NO_OUTPUT, viewModel.uiState.value.stall?.reason)

            backend.events.emit(
                OpenCodeEvent.MessagePartDelta(
                    sessionId = "s1",
                    messageId = "m1",
                    partId = "p1",
                    field = "text",
                    delta = "working on it",
                ),
            )
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.stall)
        }

    @Test
    fun `a provider retry is kept so the chat can say why nothing is coming`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = viewModel(backend)

            viewModel.sendMessage("Hello")
            backend.events.emit(retryStatus(attempt = 3, message = "Rate limit exceeded"))
            advanceUntilIdle()

            assertEquals(RunRetry(attempt = 3, message = "Rate limit exceeded"), viewModel.uiState.value.retry)
            assertTrue(viewModel.uiState.value.isRunning)
        }

    @Test
    fun `the retry is dropped once the reply starts streaming`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = viewModel(backend)

            viewModel.sendMessage("Hello")
            backend.events.emit(retryStatus(attempt = 1, message = "Overloaded"))
            advanceUntilIdle()
            assertEquals(1, viewModel.uiState.value.retry?.attempt)

            backend.events.emit(
                OpenCodeEvent.MessagePartDelta(
                    sessionId = "s1",
                    messageId = "m1",
                    partId = "p1",
                    field = "text",
                    delta = "Here you go",
                ),
            )
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.retry)
        }

    @Test
    fun `the retry is dropped when the run ends`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = viewModel(backend)

            viewModel.sendMessage("Hello")
            backend.events.emit(retryStatus(attempt = 2, message = "Overloaded"))
            advanceUntilIdle()

            backend.events.emit(OpenCodeEvent.SessionIdle("s1"))
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isRunning)
            assertNull(viewModel.uiState.value.retry)
        }

    @Test
    fun `a replacement prompt does not inherit the previous run's retry`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = viewModel(backend)

            viewModel.sendMessage("Hello")
            backend.events.emit(retryStatus(attempt = 2, message = "Overloaded"))
            advanceUntilIdle()

            viewModel.sendMessage("Try again")

            assertNull(viewModel.uiState.value.retry)
        }

    @Test
    fun `stopping a retrying run takes the retry down with it`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = viewModel(backend)

            viewModel.sendMessage("Hello")
            backend.events.emit(retryStatus(attempt = 2, message = "Overloaded"))
            advanceUntilIdle()
            assertEquals(2, viewModel.uiState.value.retry?.attempt)

            viewModel.abort()
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isRunning)
            assertNull(viewModel.uiState.value.retry)
        }

    @Test
    fun `a run the provider keeps failing is explained as a retry, not as producing nothing`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = viewModel(backend)

            viewModel.sendMessage("Hello")
            backend.events.emit(retryStatus(attempt = 5, message = "Rate limit exceeded"))
            runSilentlyPastThePoll()
            viewModel.checkForStall()
            advanceUntilIdle()

            val stall = viewModel.uiState.value.stall
            assertEquals(StallReason.PROVIDER_RETRYING, stall?.reason)
            assertEquals("Rate limit exceeded", stall?.detail)
            assertFalse(stall?.isStopped ?: true)
        }

    @Test
    fun `the watchdog reports a quiet run on its own, without being asked`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel =
                ChatViewModel(
                    backend = backend,
                    eventFlow = backend.events,
                    monitorStalls = true,
                    now = { testScheduler.currentTime },
                ).also { advanceUntilIdle() }

            viewModel.sendMessage("Hello")
            // Only bounded steps: the watchdog polls for as long as the turn is marked running.
            advanceTimeBy(STALL_THRESHOLD_MS + STALL_CHECK_INTERVAL_MS)
            runCurrent()

            assertEquals(StallReason.NO_OUTPUT, viewModel.uiState.value.stall?.reason)

            // Having said its piece, it must stop asking every 30 seconds: a wedged run left open
            // would otherwise spend the battery on health checks nobody is waiting for.
            val probesWhenReported = backend.listMessagesCalls
            advanceTimeBy(STALL_CHECK_INTERVAL_MS * 4)
            runCurrent()

            assertEquals(probesWhenReported, backend.listMessagesCalls)

            // The idle ends the turn, which also ends the polling this test must not leave running.
            backend.events.emit(OpenCodeEvent.SessionIdle("s1"))
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.stall)
            assertFalse(viewModel.uiState.value.isRunning)
        }

    @Test
    fun `a session title being rewritten is not mistaken for progress`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = viewModel(backend)

            viewModel.sendMessage("Hello")
            runSilentlyPastThePoll()
            // Metadata, not work: a runtime that kept sending these would otherwise hold the
            // watchdog off a genuinely dead turn for good.
            backend.events.emit(
                OpenCodeEvent.SessionUpdated(OpenCodeSession(id = "s1", title = "Renamed")),
            )
            advanceUntilIdle()
            viewModel.checkForStall()
            advanceUntilIdle()

            assertEquals(StallReason.NO_OUTPUT, viewModel.uiState.value.stall?.reason)
        }

    @Test
    fun `a run that comes back to life mid-probe is not flagged by the verdict`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = viewModel(backend)

            viewModel.sendMessage("Hello")
            runSilentlyPastThePoll()
            // Hold the transcript read open, so the answer arrives after the run has resumed.
            backend.listMessagesDelayMs = 5_000L
            viewModel.checkForStall()
            runCurrent()

            backend.events.emit(
                OpenCodeEvent.MessagePartDelta(
                    sessionId = "s1",
                    messageId = "m1",
                    partId = "p1",
                    field = "text",
                    delta = "back to work",
                ),
            )
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.stall)
        }

    @Test
    fun `stopping the run clears the warning`() =
        runTest(dispatcher) {
            val backend = FakeBackend()
            val viewModel = viewModel(backend)

            viewModel.sendMessage("Hello")
            runSilentlyPastThePoll()
            viewModel.checkForStall()
            advanceUntilIdle()
            assertEquals(StallReason.NO_OUTPUT, viewModel.uiState.value.stall?.reason)

            viewModel.abort()
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.stall)
            assertFalse(viewModel.uiState.value.isRunning)
        }

    /**
     * The chat under test, reading the virtual clock so the watchdog measures the same time the
     * test advances.
     */
    private fun TestScope.viewModel(
        backend: FakeBackend,
        foreground: Flow<Boolean>? = null,
    ): ChatViewModel =
        ChatViewModel(
            backend = backend,
            eventFlow = backend.events,
            now = { testScheduler.currentTime },
            foreground = foreground,
        ).also {
            // Let the opening health check land: a chat that has not connected queues prompts
            // offline instead of running them.
            advanceUntilIdle()
        }

    /**
     * Ends the view model's work, as leaving the screen does. A chat left running keeps a catch-up
     * loop ticking, so the test's closing wait for an idle clock would otherwise never return.
     */
    private fun ChatViewModel.stopCatchingUp() = viewModelScope.cancel()

    /**
     * Runs the clock past both the stall threshold and the bounded post-send poll, leaving the turn
     * exactly where a real one is abandoned: still marked running, with nothing watching it.
     */
    private fun TestScope.runSilentlyPastThePoll() {
        advanceTimeBy(STALL_THRESHOLD_MS + 1000L)
        runCurrent()
    }

    /** What OpenCode announces while it waits to try a failing provider call again. */
    private fun retryStatus(
        attempt: Int,
        message: String?,
    ): OpenCodeEvent = OpenCodeEvent.SessionStatusChanged("s1", "retry", RunRetry(attempt = attempt, message = message))

    private class FakeBackend : OpenCodeBackend {
        override val id: String = "fake"
        override val displayName: String = "Fake"
        override val kind: BackendKind = BackendKind.REMOTE
        val events = MutableSharedFlow<OpenCodeEvent>(extraBufferCapacity = 20)
        var historyMessages: List<OpenCodeMessage> = emptyList()
        var healthError: Throwable? = null

        /** Holds a transcript read open, so a test can act while a probe is in flight. */
        var listMessagesDelayMs: Long = 0L
        val abortedSessions = mutableListOf<String>()

        override suspend fun health(): OpenCodeHealth {
            healthError?.let { throw it }
            return OpenCodeHealth(true, "test")
        }

        override suspend fun listSessions(directory: String?): List<OpenCodeSession> = emptyList()

        override suspend fun createSession(
            title: String?,
            directory: String?,
        ): OpenCodeSession = OpenCodeSession(id = "s1", title = title ?: "", directory = directory, time = OpenCodeTime(created = 1))

        var listMessagesCalls = 0

        override suspend fun listMessages(sessionId: String): List<OpenCodeMessage> {
            listMessagesCalls++
            if (listMessagesDelayMs > 0L) delay(listMessagesDelayMs)
            return historyMessages
        }

        override suspend fun listProviders(): ProviderCatalog = ProviderCatalog()

        override suspend fun listAgents(): List<OpenCodeAgent> = emptyList()

        override suspend fun sendMessage(
            sessionId: String,
            request: PromptRequest,
        ) = Unit

        override suspend fun abortSession(sessionId: String): Boolean {
            abortedSessions += sessionId
            return true
        }

        override suspend fun respondToPermission(
            sessionId: String,
            permissionId: String,
            response: PermissionResponse,
            remember: Boolean,
        ): Boolean = true

        override fun events(): Flow<OpenCodeEvent> = events
    }

    private fun userMessage(text: String): OpenCodeMessage =
        OpenCodeMessage(
            info = OpenCodeMessageInfo(id = "user-1", sessionId = "s1", role = "user", time = OpenCodeTime(created = 1L)),
            parts = listOf(OpenCodePart(id = "user-text-1", type = "text", text = text)),
        )

    private fun assistantMessage(
        text: String? = null,
        completed: Long? = null,
        runningTool: String? = null,
        error: OpenCodeMessageError? = null,
    ): OpenCodeMessage =
        OpenCodeMessage(
            info =
                OpenCodeMessageInfo(
                    id = "assistant-1",
                    sessionId = "s1",
                    role = "assistant",
                    time = OpenCodeTime(created = 2L, completed = completed),
                    error = error,
                ),
            parts =
                listOfNotNull(
                    text?.let { OpenCodePart(id = "text-1", type = "text", text = it) },
                    runningTool?.let {
                        OpenCodePart(
                            id = "tool-1",
                            type = "tool",
                            tool = "bash",
                            state = mapOf("status" to JsonPrimitive("running"), "title" to JsonPrimitive(it)),
                        )
                    },
                ),
        )
}
