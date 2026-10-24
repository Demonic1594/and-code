package com.yugahashimoto.andcode.data.repository

import com.yugahashimoto.andcode.core.api.OpenCodeAgent
import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.OpenCodeHealth
import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodeSession
import com.yugahashimoto.andcode.core.api.OpenCodeTime
import com.yugahashimoto.andcode.core.api.PromptRequest
import com.yugahashimoto.andcode.core.api.ProviderCatalog
import com.yugahashimoto.andcode.runtime.BackendKind
import com.yugahashimoto.andcode.runtime.PermissionResponse
import com.yugahashimoto.andcode.runtime.RuntimeState
import com.yugahashimoto.andcode.runtime.RuntimeTarget
import com.yugahashimoto.andcode.runtime.RuntimeType
import com.yugahashimoto.andcode.runtime.WorkspaceRef
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatLifecycleRepositoryTest {
    @Test
    fun `scheduled delete hides the chat at once and deletes it after the undo window`() =
        runTest {
            val target = FakeTarget("claude", active = mutableListOf(session("a")))
            val forgotten = mutableListOf<String>()
            val lifecycle = lifecycle(this, listOf(target), forgotten = forgotten)

            lifecycle.scheduleDelete(setOf("a"))
            runCurrent()

            assertEquals(setOf("a"), lifecycle.pendingDeletion.value)
            assertTrue(target.deletedIds.isEmpty())

            advanceTimeBy(ChatLifecycleRepository.UNDO_WINDOW_MILLIS + 1)
            runCurrent()

            assertEquals(listOf("a"), target.deletedIds)
            assertEquals(listOf("a"), forgotten)
            assertTrue(lifecycle.pendingDeletion.value.isEmpty())
        }

    @Test
    fun `undo inside the window keeps the chat`() =
        runTest {
            val target = FakeTarget("claude", active = mutableListOf(session("a")))
            val lifecycle = lifecycle(this, listOf(target))

            lifecycle.scheduleDelete(setOf("a"))
            advanceTimeBy(1_000)
            lifecycle.undoDelete(setOf("a"))
            advanceUntilIdle()

            assertTrue(target.deletedIds.isEmpty())
            assertTrue(lifecycle.pendingDeletion.value.isEmpty())
        }

    @Test
    fun `a failed delete is reported with its reason and keeps local state`() =
        runTest {
            val target = FakeTarget("codex", active = mutableListOf(session("a"))).apply { deleteError = "thread/delete not supported" }
            val forgotten = mutableListOf<String>()
            val lifecycle = lifecycle(this, listOf(target), forgotten = forgotten)
            val reports = mutableListOf<ChatActionReport>()
            val collector = launch { lifecycle.reports.collect { reports += it } }
            runCurrent()

            val result = lifecycle.deleteNow(mapOf("a" to "codex"))

            assertEquals(mapOf("a" to "thread/delete not supported"), result.failed)
            assertTrue(forgotten.isEmpty())
            runCurrent()
            assertEquals(ChatAction.DELETE, reports.single().action)
            collector.cancel()
        }

    @Test
    fun `actions go only to the owning runtime when it is known`() =
        runTest {
            val owner = FakeTarget("opencode", active = mutableListOf(session("a")))
            val other = FakeTarget("claude", active = mutableListOf(session("a")))
            val lifecycle = lifecycle(this, listOf(owner, other), owners = mapOf("a" to "opencode"))

            lifecycle.archive(mapOf("a" to null))

            assertEquals(listOf("a"), owner.archivedIds)
            assertTrue(other.archivedIds.isEmpty())
        }

    @Test
    fun `restore that a backend cannot do is reported as unsupported`() =
        runTest {
            val target = FakeTarget("opencode", archived = mutableListOf(session("a"))).apply { canRestore = false }
            val restored = mutableListOf<String>()
            val lifecycle = lifecycle(this, listOf(target), restored = restored)

            val result = lifecycle.restore(mapOf("a" to "opencode"))

            assertEquals(mapOf("a" to ChatLifecycleRepository.UNSUPPORTED), result.failed)
            assertTrue(restored.isEmpty())
        }

    @Test
    fun `restore records the chat so auto-archive leaves it alone`() =
        runTest {
            val target = FakeTarget("claude", archived = mutableListOf(session("a")))
            val restored = mutableListOf<String>()
            val lifecycle = lifecycle(this, listOf(target), restored = restored)

            val result = lifecycle.restore(mapOf("a" to "claude"))

            assertEquals(setOf("a"), result.succeeded)
            assertEquals(listOf("a"), restored)
            assertTrue(target.archived.isEmpty())
        }

    @Test
    fun `archived chats from every runtime are listed newest archive first`() =
        runTest {
            val first = FakeTarget("claude", archived = mutableListOf(session("old", archivedAt = 10)))
            val second = FakeTarget("codex", archived = mutableListOf(session("new", archivedAt = 20)))
            val broken = FakeTarget("remote").apply { listError = true }
            val lifecycle = lifecycle(this, listOf(first, second, broken))

            val chats = lifecycle.listArchived()

            assertEquals(listOf("new", "old"), chats.map { it.session.id })
            assertEquals(listOf("codex", "claude"), chats.map { it.runtimeId })
        }

    private fun lifecycle(
        scope: TestScope,
        targets: List<RuntimeTarget>,
        owners: Map<String, String> = emptyMap(),
        forgotten: MutableList<String> = mutableListOf(),
        restored: MutableList<String> = mutableListOf(),
    ) = ChatLifecycleRepository(
        targets = { targets },
        ownerOf = owners::get,
        scope = scope.backgroundScope,
        forgetLocally = { forgotten += it },
        onRestored = { restored += it },
    )

    private fun session(
        id: String,
        archivedAt: Long? = null,
    ) = OpenCodeSession(id = id, title = id, time = OpenCodeTime(created = 1, updated = 1, archived = archivedAt))

    private class FakeTarget(
        override val id: String,
        val active: MutableList<OpenCodeSession> = mutableListOf(),
        val archived: MutableList<OpenCodeSession> = mutableListOf(),
    ) : RuntimeTarget {
        override val type: RuntimeType = RuntimeType.LOCAL
        override val displayName: String = id
        override val kind: BackendKind = BackendKind.LOCAL
        override val state = MutableStateFlow<RuntimeState>(RuntimeState.Disconnected)

        val deletedIds = mutableListOf<String>()
        val archivedIds = mutableListOf<String>()
        var deleteError: String? = null
        var canRestore = true
        var listError = false

        override suspend fun connect(): Result<OpenCodeHealth> = Result.success(OpenCodeHealth(true, "1.0"))

        override fun disconnect() = Unit

        override suspend fun health(): OpenCodeHealth = OpenCodeHealth(true, "1.0")

        override suspend fun listSessions(directory: String?): List<OpenCodeSession> = active

        override suspend fun listArchivedSessions(directory: String?): List<OpenCodeSession> {
            if (listError) error("unreachable")
            return archived
        }

        override suspend fun deleteSession(sessionId: String): Boolean {
            deleteError?.let { error(it) }
            val removed = active.removeAll { it.id == sessionId } or archived.removeAll { it.id == sessionId }
            if (removed) deletedIds += sessionId
            return removed
        }

        override suspend fun archiveSession(sessionId: String): OpenCodeSession {
            val session = active.first { it.id == sessionId }
            active.remove(session)
            archived += session
            archivedIds += sessionId
            return session
        }

        override suspend fun unarchiveSession(sessionId: String): OpenCodeSession {
            if (!canRestore) throw UnsupportedOperationException("no restore")
            val session = archived.first { it.id == sessionId }
            archived.remove(session)
            active += session
            return session
        }

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
        ) = Unit

        override suspend fun abortSession(sessionId: String): Boolean = true

        override suspend fun respondToPermission(
            sessionId: String,
            permissionId: String,
            response: PermissionResponse,
            remember: Boolean,
        ): Boolean = true

        override fun events(): Flow<OpenCodeEvent> = emptyFlow()
    }
}
