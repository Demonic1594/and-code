package com.yugahashimoto.andcode.feature.inbox

import com.yugahashimoto.andcode.core.api.OpenCodeAgent
import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.OpenCodeHealth
import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodeSession
import com.yugahashimoto.andcode.core.api.PermissionRequest
import com.yugahashimoto.andcode.core.api.PromptRequest
import com.yugahashimoto.andcode.core.api.ProviderCatalog
import com.yugahashimoto.andcode.data.connection.ConnectionProfile
import com.yugahashimoto.andcode.data.repository.RuntimeActivityRepository
import com.yugahashimoto.andcode.data.repository.RuntimeCatalogRepository
import com.yugahashimoto.andcode.runtime.BackendKind
import com.yugahashimoto.andcode.runtime.PermissionResponse
import com.yugahashimoto.andcode.runtime.RuntimeConnectionStore
import com.yugahashimoto.andcode.runtime.RuntimeRegistry
import com.yugahashimoto.andcode.runtime.RuntimeState
import com.yugahashimoto.andcode.runtime.RuntimeTarget
import com.yugahashimoto.andcode.runtime.RuntimeType
import com.yugahashimoto.andcode.runtime.WorkspaceRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InboxViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class Fixture(
        val target: FakeTarget,
        val activity: RuntimeActivityRepository,
        val viewModel: InboxViewModel,
        val stopped: MutableList<String>,
    )

    private fun fixture(scope: TestScope): Fixture {
        val target = FakeTarget()
        val registry = RuntimeRegistry(store = FakeStore(target.id), localTarget = target, remoteFactory = { error("unused") })
        val catalog = RuntimeCatalogRepository(registry, scope)
        val activity = RuntimeActivityRepository(registry, scope)
        val stopped = mutableListOf<String>()
        val viewModel = InboxViewModel(catalog, activity, registry, MutableStateFlow(emptySet()), onStopped = { stopped += it })
        return Fixture(target, activity, viewModel, stopped)
    }

    @Test
    fun `answering an approval resolves it, and a refused answer says so`() =
        runTest(dispatcher) {
            val f = fixture(TestScope(dispatcher))
            advanceUntilIdle()
            f.target.events.emit(OpenCodeEvent.PermissionAsked(PermissionRequest("p1", "s1", "bash")))
            f.target.events.emit(OpenCodeEvent.PermissionAsked(PermissionRequest("p2", "s1", "edit")))
            advanceUntilIdle()
            assertEquals(2, f.viewModel.state.value.content.approvals.size)

            f.viewModel.respond(PermissionRequest("p1", "s1", "bash"), PermissionResponse.ONCE, remember = false)
            advanceUntilIdle()
            assertEquals(listOf("p2"), f.viewModel.state.value.content.approvals.map { it.request.id })
            assertEquals(listOf("s1/p1/once"), f.target.answers)

            f.target.acceptAnswers = false
            f.viewModel.respond(PermissionRequest("p2", "s1", "edit"), PermissionResponse.REJECT, remember = false)
            advanceUntilIdle()
            assertEquals(listOf("p2"), f.viewModel.state.value.content.approvals.map { it.request.id })
            assertEquals(InboxFailure.APPROVAL, f.viewModel.state.value.failure)
            assertTrue(f.viewModel.state.value.busyIds.isEmpty())
        }

    @Test
    fun `a reply goes to its chat and moves it from finished to running`() =
        runTest(dispatcher) {
            val f = fixture(TestScope(dispatcher))
            advanceUntilIdle()
            f.target.events.emit(OpenCodeEvent.SessionIdle("s1"))
            advanceUntilIdle()
            val chat = f.viewModel.state.value.content.finished.single()

            f.viewModel.reply(chat, "  now add a test  ")
            advanceUntilIdle()

            assertEquals(listOf("s1" to "now add a test"), f.target.prompts.map { it.first to it.second.text })
            assertTrue(f.viewModel.state.value.content.finished.isEmpty())
            assertEquals(listOf("s1"), f.viewModel.state.value.content.running.map { it.chat.sessionId })
        }

    @Test
    fun `stop ends the run at once and asks the runtime to abort`() =
        runTest(dispatcher) {
            val f = fixture(TestScope(dispatcher))
            advanceUntilIdle()
            f.activity.markSessionRunning("s1")
            advanceUntilIdle()
            val run = f.viewModel.state.value.content.running.single()

            f.viewModel.stop(run.chat)
            advanceUntilIdle()

            assertTrue(f.viewModel.state.value.content.running.isEmpty())
            assertEquals(listOf("s1"), f.target.aborted)
            assertEquals(listOf("s1"), f.stopped)
        }

    private class FakeStore(
        override var selectedRuntimeId: String?,
    ) : RuntimeConnectionStore {
        override fun connections(): List<ConnectionProfile> = emptyList()

        override fun upsertConnection(profile: ConnectionProfile) = Unit

        override fun deleteConnection(id: String) = Unit
    }

    private class FakeTarget : RuntimeTarget {
        override val id: String = "local"
        override val type: RuntimeType = RuntimeType.LOCAL
        override val displayName: String = id
        override val kind: BackendKind = BackendKind.LOCAL
        override val state = MutableStateFlow<RuntimeState>(RuntimeState.Connected("1.0"))
        val events = MutableSharedFlow<OpenCodeEvent>(extraBufferCapacity = 16)
        val answers = mutableListOf<String>()
        val prompts = mutableListOf<Pair<String, PromptRequest>>()
        val aborted = mutableListOf<String>()
        var acceptAnswers = true

        override suspend fun connect(): Result<OpenCodeHealth> = Result.success(OpenCodeHealth(true, "1.0"))

        override fun disconnect() = Unit

        override suspend fun health(): OpenCodeHealth = OpenCodeHealth(true, "1.0")

        override suspend fun listSessions(directory: String?): List<OpenCodeSession> =
            listOf(OpenCodeSession(id = "s1", title = "Fix login"))

        override suspend fun createSession(
            title: String?,
            directory: String?,
        ): OpenCodeSession = error("unused")

        override suspend fun listMessages(sessionId: String): List<OpenCodeMessage> = emptyList()

        override suspend fun listProviders(): ProviderCatalog = ProviderCatalog()

        override suspend fun listAgents(): List<OpenCodeAgent> = emptyList()

        override suspend fun listWorkspaces(): List<WorkspaceRef> = emptyList()

        override suspend fun sendMessage(
            sessionId: String,
            request: PromptRequest,
        ) {
            prompts += sessionId to request
        }

        override suspend fun abortSession(sessionId: String): Boolean {
            aborted += sessionId
            return true
        }

        override suspend fun respondToPermission(
            sessionId: String,
            permissionId: String,
            response: PermissionResponse,
            remember: Boolean,
        ): Boolean {
            answers += "$sessionId/$permissionId/${response.apiValue}"
            return acceptAnswers
        }

        override fun events(): Flow<OpenCodeEvent> = events
    }
}
