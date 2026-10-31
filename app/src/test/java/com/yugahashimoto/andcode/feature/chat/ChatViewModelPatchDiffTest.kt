package com.yugahashimoto.andcode.feature.chat

import com.yugahashimoto.andcode.core.api.OpenCodeAgent
import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.OpenCodeFileChange
import com.yugahashimoto.andcode.core.api.OpenCodeHealth
import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodeSession
import com.yugahashimoto.andcode.core.api.OpenCodeTime
import com.yugahashimoto.andcode.core.api.PromptRequest
import com.yugahashimoto.andcode.core.api.ProviderCatalog
import com.yugahashimoto.andcode.runtime.BackendKind
import com.yugahashimoto.andcode.runtime.PermissionResponse
import com.yugahashimoto.andcode.runtime.RuntimeCapabilities
import com.yugahashimoto.andcode.runtime.RuntimeState
import com.yugahashimoto.andcode.runtime.RuntimeTarget
import com.yugahashimoto.andcode.runtime.RuntimeType
import com.yugahashimoto.andcode.runtime.WorkspaceRef
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Covers [ChatViewModel.openPatchDiff] and [ChatViewModel.dismissPatchDiff]. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelPatchDiffTest {
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
    fun `a diff-capable backend loads the diff`() =
        runTest(dispatcher) {
            val backend = FakeTarget(diffCapable = true)
            backend.diffResult = listOf(OpenCodeFileChange(file = "A.kt", patch = "@@ -1 +1 @@\n-old\n+new"))
            val viewModel = ChatViewModel(backend = backend, eventFlow = backend.events).also { advanceUntilIdle() }
            viewModel.sendMessage("Hello")
            advanceUntilIdle()

            viewModel.openPatchDiff(ChatPart.Patch("p1", listOf("A.kt"), "m1"))
            advanceUntilIdle()

            val diff = viewModel.uiState.value.patchDiff
            assertTrue(diff is PatchDiffState.Loaded)
            assertEquals(listOf("A.kt"), diff?.files)
            assertEquals(1, backend.sessionDiffCalls)
        }

    @Test
    fun `a backend without diff support never gets a call and shows unavailable immediately`() =
        runTest(dispatcher) {
            val backend = FakeTarget(diffCapable = false)
            val viewModel = ChatViewModel(backend = backend, eventFlow = backend.events).also { advanceUntilIdle() }
            viewModel.sendMessage("Hello")
            advanceUntilIdle()

            viewModel.openPatchDiff(ChatPart.Patch("p1", listOf("A.kt"), "m1"))
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.patchDiff is PatchDiffState.Unavailable)
            assertEquals(0, backend.sessionDiffCalls)
        }

    @Test
    fun `a diff-capable backend that throws is treated as unavailable`() =
        runTest(dispatcher) {
            val backend = FakeTarget(diffCapable = true)
            backend.diffError = IllegalStateException("boom")
            val viewModel = ChatViewModel(backend = backend, eventFlow = backend.events).also { advanceUntilIdle() }
            viewModel.sendMessage("Hello")
            advanceUntilIdle()

            viewModel.openPatchDiff(ChatPart.Patch("p1", listOf("A.kt"), "m1"))
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.patchDiff is PatchDiffState.Unavailable)
        }

    @Test
    fun `an empty diff response is treated as unavailable rather than as no changes`() =
        runTest(dispatcher) {
            val backend = FakeTarget(diffCapable = true)
            backend.diffResult = emptyList()
            val viewModel = ChatViewModel(backend = backend, eventFlow = backend.events).also { advanceUntilIdle() }
            viewModel.sendMessage("Hello")
            advanceUntilIdle()

            viewModel.openPatchDiff(ChatPart.Patch("p1", listOf("A.kt"), "m1"))
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.patchDiff is PatchDiffState.Unavailable)
        }

    @Test
    fun `dismissing clears the dialog`() =
        runTest(dispatcher) {
            val backend = FakeTarget(diffCapable = false)
            val viewModel = ChatViewModel(backend = backend, eventFlow = backend.events).also { advanceUntilIdle() }
            viewModel.sendMessage("Hello")
            advanceUntilIdle()
            viewModel.openPatchDiff(ChatPart.Patch("p1", listOf("A.kt"), "m1"))
            advanceUntilIdle()

            viewModel.dismissPatchDiff()

            assertEquals(null, viewModel.uiState.value.patchDiff)
        }

    @Test
    fun `sending records a snapshot for the turn, in the chat's folder`() =
        runTest(dispatcher) {
            val backend = FakeTarget(diffCapable = false)
            val snapshots = FakeSnapshots()
            val viewModel =
                ChatViewModel(
                    backend = backend,
                    eventFlow = backend.events,
                    turnSnapshots = snapshots,
                ).also { advanceUntilIdle() }
            viewModel.selectWorkspace("/workspace/app")

            viewModel.sendMessage("First")
            advanceUntilIdle()
            viewModel.sendMessage("Second")
            advanceUntilIdle()

            assertEquals(listOf(Triple("s1", 0, "/workspace/app"), Triple("s1", 1, "/workspace/app")), snapshots.recorded)
        }

    @Test
    fun `without a runtime diff, a turn's changes come from its snapshots`() =
        runTest(dispatcher) {
            val backend = FakeTarget(diffCapable = false)
            val snapshots = FakeSnapshots()
            snapshots.changes =
                listOf(
                    OpenCodeFileChange(file = "A.kt", patch = "@@ -1 +1 @@\n-old\n+new"),
                    OpenCodeFileChange(file = "Other.kt", patch = "@@ -1 +1 @@\n-a\n+b"),
                )
            val viewModel =
                ChatViewModel(
                    backend = backend,
                    eventFlow = backend.events,
                    turnSnapshots = snapshots,
                ).also { advanceUntilIdle() }
            viewModel.selectWorkspace("/workspace/app")
            viewModel.sendMessage("Hello")
            advanceUntilIdle()
            val userMessageId = viewModel.uiState.value.messages.first { it.isUser }.id

            viewModel.openPatchDiff(ChatPart.Patch("p1", listOf("/workspace/app/A.kt"), userMessageId))
            advanceUntilIdle()

            val diff = viewModel.uiState.value.patchDiff
            assertTrue(diff is PatchDiffState.Loaded)
            assertEquals(listOf("A.kt"), (diff as PatchDiffState.Loaded).changes.map { it.displayPath })
            assertEquals(0, backend.sessionDiffCalls)
        }

    @Test
    fun `files are restored to before a message and the restore can be undone`() =
        runTest(dispatcher) {
            val backend = FakeTarget(diffCapable = false)
            val snapshots = FakeSnapshots()
            val viewModel =
                ChatViewModel(
                    backend = backend,
                    eventFlow = backend.events,
                    turnSnapshots = snapshots,
                ).also { advanceUntilIdle() }
            viewModel.selectWorkspace("/workspace/app")
            viewModel.sendMessage("Hello")
            advanceUntilIdle()
            backend.events.tryEmit(OpenCodeEvent.SessionIdle("s1"))
            advanceUntilIdle()
            val userMessageId = viewModel.uiState.value.messages.first { it.isUser }.id
            assertTrue(viewModel.canRestoreFiles(userMessageId))

            viewModel.restoreFilesTo(userMessageId)
            advanceUntilIdle()

            assertEquals(listOf(0), snapshots.restored)
            assertEquals("undo-token", viewModel.uiState.value.fileRestore?.token)

            viewModel.undoFileRestore()
            advanceUntilIdle()

            assertEquals(listOf("undo-token"), snapshots.undone)
            assertEquals(null, viewModel.uiState.value.fileRestore)
        }

    private suspend fun kotlinx.coroutines.test.TestScope.restoredChat(snapshots: FakeSnapshots): Pair<ChatViewModel, FakeTarget> {
        val backend = FakeTarget(diffCapable = false)
        val viewModel =
            ChatViewModel(
                backend = backend,
                eventFlow = backend.events,
                turnSnapshots = snapshots,
            ).also { advanceUntilIdle() }
        viewModel.selectWorkspace("/workspace/app")
        viewModel.sendMessage("Hello")
        advanceUntilIdle()
        backend.events.tryEmit(OpenCodeEvent.SessionIdle("s1"))
        advanceUntilIdle()
        viewModel.restoreFilesTo(viewModel.uiState.value.messages.first { it.isUser }.id)
        return viewModel to backend
    }

    @Test
    fun `nothing is sent while files are being restored, and sending works again after`() =
        runTest(dispatcher) {
            val snapshots = FakeSnapshots().apply { restoreGate = CompletableDeferred() }
            val (viewModel, backend) = restoredChat(snapshots)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.isRestoringFiles)
            val sentBefore = backend.sent.size

            viewModel.sendMessage("After the restore")
            advanceUntilIdle()

            assertEquals("nothing is sent mid-restore", sentBefore, backend.sent.size)

            snapshots.restoreGate?.complete(Unit)
            advanceUntilIdle()
            viewModel.sendMessage("After the restore")
            advanceUntilIdle()

            assertEquals(sentBefore + 1, backend.sent.size)
        }

    @Test
    fun `a new chat drops the undo of a restore made in the last one`() =
        runTest(dispatcher) {
            val (viewModel, _) = restoredChat(FakeSnapshots())
            advanceUntilIdle()
            assertEquals("undo-token", viewModel.uiState.value.fileRestore?.token)

            viewModel.newSession()
            advanceUntilIdle()

            assertEquals(null, viewModel.uiState.value.fileRestore)
        }

    @Test
    fun `a restore that finishes after leaving the chat shows no undo`() =
        runTest(dispatcher) {
            val snapshots = FakeSnapshots().apply { restoreGate = CompletableDeferred() }
            val (viewModel, _) = restoredChat(snapshots)
            advanceUntilIdle()

            viewModel.newSession()
            snapshots.restoreGate?.complete(Unit)
            advanceUntilIdle()

            assertEquals(null, viewModel.uiState.value.fileRestore)
            assertEquals(false, viewModel.uiState.value.isRestoringFiles)
        }

    @Test
    fun `patch paths match on whole path segments only`() {
        val changes = listOf(OpenCodeFileChange(file = "xa/Foo.kt"), OpenCodeFileChange(file = "a/Foo.kt"))

        val kept = changesForPatch(changes, listOf("/workspace/app/a/Foo.kt"))

        assertEquals(listOf("a/Foo.kt"), kept.map { it.displayPath })
    }

    private class FakeSnapshots : TurnSnapshots {
        val recorded = mutableListOf<Triple<String, Int, String>>()
        val restored = mutableListOf<Int>()
        val undone = mutableListOf<String>()
        var changes: List<OpenCodeFileChange> = emptyList()

        /** Holds a restore until completed, to act while it runs. */
        var restoreGate: CompletableDeferred<Unit>? = null

        override suspend fun record(
            sessionId: String,
            turn: Int,
            directory: String,
        ) {
            recorded += Triple(sessionId, turn, directory)
        }

        override fun has(
            sessionId: String,
            turn: Int,
        ): Boolean = recorded.any { it.first == sessionId && it.second == turn }

        override suspend fun restore(
            sessionId: String,
            turn: Int,
            directory: String,
        ): String {
            restored += turn
            restoreGate?.await()
            return "undo-token"
        }

        override suspend fun undoRestore(
            directory: String,
            token: String,
        ): Boolean {
            undone += token
            return true
        }

        override suspend fun turnChanges(
            sessionId: String,
            turn: Int,
            directory: String,
        ): List<OpenCodeFileChange> = changes
    }

    private class FakeTarget(diffCapable: Boolean) : RuntimeTarget {
        override val id: String = "fake"
        override val displayName: String = "Fake"
        override val kind: BackendKind = BackendKind.LOCAL
        override val type: RuntimeType = RuntimeType.LOCAL
        override val capabilities: RuntimeCapabilities = RuntimeCapabilities(diffCapable = diffCapable)
        private val mutableState = MutableStateFlow<RuntimeState>(RuntimeState.Connected("test"))
        override val state: StateFlow<RuntimeState> = mutableState

        val events = MutableSharedFlow<OpenCodeEvent>(extraBufferCapacity = 20)
        var sessionDiffCalls = 0
        var diffResult: List<OpenCodeFileChange> = emptyList()
        var diffError: Throwable? = null

        override suspend fun connect(): Result<OpenCodeHealth> = Result.success(OpenCodeHealth(true, "test"))

        override fun disconnect() = Unit

        override suspend fun listWorkspaces(): List<WorkspaceRef> = emptyList()

        override suspend fun health(): OpenCodeHealth = OpenCodeHealth(true, "test")

        override suspend fun listSessions(directory: String?): List<OpenCodeSession> = emptyList()

        override suspend fun createSession(
            title: String?,
            directory: String?,
        ): OpenCodeSession = OpenCodeSession(id = "s1", title = title ?: "", directory = directory, time = OpenCodeTime(created = 1))

        override suspend fun listMessages(sessionId: String): List<OpenCodeMessage> = emptyList()

        override suspend fun listProviders(): ProviderCatalog = ProviderCatalog()

        override suspend fun listAgents(): List<OpenCodeAgent> = emptyList()

        override suspend fun sendMessage(
            sessionId: String,
            request: PromptRequest,
        ) {
            sent += request
        }

        val sent = mutableListOf<PromptRequest>()

        override suspend fun abortSession(sessionId: String): Boolean = true

        override suspend fun respondToPermission(
            sessionId: String,
            permissionId: String,
            response: PermissionResponse,
            remember: Boolean,
        ): Boolean = true

        override suspend fun sessionDiff(
            sessionId: String,
            directory: String?,
            messageId: String?,
        ): List<OpenCodeFileChange> {
            sessionDiffCalls++
            diffError?.let { throw it }
            return diffResult
        }

        override fun events(): Flow<OpenCodeEvent> = events
    }
}
