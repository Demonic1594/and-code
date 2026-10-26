package com.yugahashimoto.andcode.data.repository

import com.yugahashimoto.andcode.core.api.OpenCodeAgent
import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.OpenCodeHealth
import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodeMessageInfo
import com.yugahashimoto.andcode.core.api.OpenCodePart
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class MessageSearchRepositoryTest {
    private val target =
        FakeTarget(
            "claude",
            mapOf(
                "a" to
                    listOf(
                        message("a", "user", "How do I fix the gradle build?"),
                        message("a", "assistant", "Run ./gradlew clean first."),
                    ),
                "b" to
                    listOf(
                        message("b", "user", "Write a README"),
                        message("b", "assistant", "Here is a README for the Gradle plugin."),
                    ),
                "c" to listOf(message("c", "user", "Unrelated question")),
            ),
        )
    private val chats =
        listOf(
            ref("a", "Build problems", updated = 3),
            ref("b", "Docs", updated = 2),
            ref("c", "Gradle notes", updated = 1),
        )

    @Test
    fun `finds chats by message text and title, title matches first`() =
        runTest {
            val repository = MessageSearchRepository(targets = { listOf(target) })

            val result = repository.search("gradle", chats).last()

            assertTrue(result.done)
            assertEquals(listOf("c", "a", "b"), result.hits.map { it.ref.session.id })
            val a = result.hits.first { it.ref.session.id == "a" }
            assertEquals(2, a.messageMatches)
            assertEquals("gradle", a.snippet.substring(a.snippetMatchStart, a.snippetMatchStart + 6).lowercase())
        }

    @Test
    fun `a chat's text is read once while it is unchanged`() =
        runTest {
            val repository = MessageSearchRepository(targets = { listOf(target) })

            repository.search("gradle", chats).last()
            repository.search("readme", chats).last()
            assertEquals(3, target.reads)

            repository.search("readme", listOf(ref("a", "Build problems", updated = 4))).last()
            assertEquals(4, target.reads)
        }

    @Test
    fun `a chat that cannot be read is skipped, not fatal`() =
        runTest {
            val repository = MessageSearchRepository(targets = { listOf(target) })
            target.failFor = "b"

            val result = repository.search("readme", chats).last()

            assertTrue(result.hits.isEmpty())
            assertEquals(3, result.searched)
        }

    @Test
    fun `snippet is cut around the match with ellipses`() {
        val text = "x".repeat(100) + " needle " + "y".repeat(100)
        val (snippet, start) = MessageSearchRepository.snippet(text, "NEEDLE", radius = 10)

        assertTrue(snippet.startsWith("…"))
        assertTrue(snippet.endsWith("…"))
        assertEquals("needle", snippet.substring(start, start + 6))
    }

    @Test
    fun `blank query finds nothing`() =
        runTest {
            val result = MessageSearchRepository(targets = { listOf(target) }).search("  ", chats).last()

            assertTrue(result.hits.isEmpty())
            assertEquals(0, target.reads)
        }

    private fun ref(
        id: String,
        title: String,
        updated: Long,
    ) = RuntimeSessionRef("claude", null, OpenCodeSession(id = id, title = title, time = OpenCodeTime(created = 0, updated = updated)))

    private fun message(
        session: String,
        role: String,
        text: String,
    ) = OpenCodeMessage(
        info = OpenCodeMessageInfo(id = "$session-$role", sessionId = session, role = role),
        parts = listOf(OpenCodePart(type = "text", text = text)),
    )

    private class FakeTarget(
        override val id: String,
        private val messages: Map<String, List<OpenCodeMessage>>,
    ) : RuntimeTarget {
        override val type: RuntimeType = RuntimeType.LOCAL
        override val displayName: String = id
        override val kind: BackendKind = BackendKind.LOCAL
        override val state = MutableStateFlow<RuntimeState>(RuntimeState.Disconnected)

        // Reads run on several IO threads at once, so a plain counter could lose increments.
        private val readCount = AtomicInteger()
        val reads: Int get() = readCount.get()
        var failFor: String? = null

        override suspend fun listMessages(sessionId: String): List<OpenCodeMessage> {
            readCount.incrementAndGet()
            if (sessionId == failFor) error("unreadable")
            return messages[sessionId].orEmpty()
        }

        override suspend fun connect(): Result<OpenCodeHealth> = Result.success(OpenCodeHealth(true, "1.0"))

        override fun disconnect() = Unit

        override suspend fun health(): OpenCodeHealth = OpenCodeHealth(true, "1.0")

        override suspend fun listSessions(directory: String?): List<OpenCodeSession> = emptyList()

        override suspend fun createSession(
            title: String?,
            directory: String?,
        ): OpenCodeSession = error("unused")

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
