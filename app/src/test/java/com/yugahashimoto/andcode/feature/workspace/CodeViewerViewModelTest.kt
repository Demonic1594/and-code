package com.yugahashimoto.andcode.feature.workspace

import com.yugahashimoto.andcode.core.api.OpenCodeAgent
import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.OpenCodeFileContent
import com.yugahashimoto.andcode.core.api.OpenCodeHealth
import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodeSession
import com.yugahashimoto.andcode.core.api.PromptRequest
import com.yugahashimoto.andcode.core.api.ProviderCatalog
import com.yugahashimoto.andcode.runtime.BackendKind
import com.yugahashimoto.andcode.runtime.FileEditException
import com.yugahashimoto.andcode.runtime.FileEditFailure
import com.yugahashimoto.andcode.runtime.OpenCodeBackend
import com.yugahashimoto.andcode.runtime.PermissionResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CodeViewerViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `loads file from workspace`() =
        runTest(dispatcher) {
            val backend = FakeBackend(OpenCodeFileContent("text", "fun main() = Unit"))
            val viewModel = CodeViewerViewModel(backend, "/repo path", "src/Main.kt")

            advanceUntilIdle()

            assertEquals("/repo path", backend.directory)
            assertEquals("src/Main.kt", backend.path)
            assertEquals("fun main() = Unit", viewModel.state.value.content)
            assertFalse(viewModel.state.value.isLoading)
            assertFalse(viewModel.state.value.isBinary)
        }

    @Test
    fun `marks binary content without exposing it as text`() =
        runTest(dispatcher) {
            val viewModel =
                CodeViewerViewModel(
                    FakeBackend(OpenCodeFileContent("text", "AAEC", encoding = "base64")),
                    "/repo",
                    "image.png",
                )

            advanceUntilIdle()

            assertTrue(viewModel.state.value.isBinary)
            assertFalse(viewModel.state.value.isLoading)
        }

    @Test
    fun `reports read failures and stops loading`() =
        runTest(dispatcher) {
            val viewModel = CodeViewerViewModel(FakeBackend(null), "/repo", "missing.kt")

            advanceUntilIdle()

            assertEquals("file unavailable", viewModel.state.value.error)
            assertFalse(viewModel.state.value.isLoading)
        }

    @Test
    fun `stays read-only when the runtime grants no editing root`() =
        runTest(dispatcher) {
            val backend = FakeBackend(OpenCodeFileContent("text", "API_URL=x"))
            val viewModel = CodeViewerViewModel(backend, "/workspace/app", ".env", editableRoot = null)
            advanceUntilIdle()

            viewModel.startEditing()
            advanceUntilIdle()

            assertFalse(viewModel.state.value.canEdit)
            assertFalse(viewModel.state.value.isEditing)
            assertTrue(backend.editReads.isEmpty())
        }

    @Test
    fun `offers editing for a text file inside the workspace`() =
        runTest(dispatcher) {
            val viewModel = editorFor(FakeBackend(OpenCodeFileContent("text", "API_URL=x")), file = ".env")

            assertTrue(viewModel.state.value.canEdit)
            assertFalse(viewModel.state.value.isEditing)
        }

    @Test
    fun `does not offer editing for binary files or files above the workspace`() =
        runTest(dispatcher) {
            val binary =
                editorFor(FakeBackend(OpenCodeFileContent("text", "AAEC", encoding = "base64")), file = "logo.png")
            val above =
                CodeViewerViewModel(
                    FakeBackend(OpenCodeFileContent("text", "x")),
                    workspacePath = "/workspace",
                    filePath = "other/notes.txt",
                    editableRoot = "/workspace/app",
                )
            advanceUntilIdle()

            assertFalse(binary.state.value.canEdit)
            assertFalse(above.state.value.canEdit)
        }

    @Test
    fun `starts the editor from the exact text on disk rather than the viewer's copy`() =
        runTest(dispatcher) {
            val backend = FakeBackend(OpenCodeFileContent("text", "A=1"), editText = "A=1\n")
            val viewModel = editorFor(backend, file = ".env")

            viewModel.startEditing()
            advanceUntilIdle()

            assertEquals(listOf("/workspace/app" to "src/.env"), backend.editReads)
            val state = viewModel.state.value
            assertTrue(state.isEditing)
            assertEquals("A=1\n", state.draft)
            assertEquals("A=1\n", state.editBase)
            assertFalse(state.hasUnsavedChanges)
        }

    @Test
    fun `reports why a file cannot be edited and stays in the viewer`() =
        runTest(dispatcher) {
            val backend =
                FakeBackend(
                    OpenCodeFileContent("text", "caf?"),
                    editFailure = FileEditException(FileEditFailure.NOT_TEXT, "not utf-8"),
                )
            val viewModel = editorFor(backend, file = "legacy.txt")

            viewModel.startEditing()
            advanceUntilIdle()

            val state = viewModel.state.value
            assertFalse(state.isEditing)
            assertFalse(state.isPreparingEdit)
            assertEquals(CodeViewerMessages.editFailure(FileEditFailure.NOT_TEXT), state.editError)
        }

    @Test
    fun `tracks unsaved changes while typing`() =
        runTest(dispatcher) {
            val viewModel = editorFor(FakeBackend(OpenCodeFileContent("text", "A=1"), editText = "A=1\n"), file = ".env")
            viewModel.startEditing()
            advanceUntilIdle()

            viewModel.updateDraft("A=2\n")
            assertTrue(viewModel.state.value.hasUnsavedChanges)

            viewModel.updateDraft("A=1\n")
            assertFalse(viewModel.state.value.hasUnsavedChanges)
        }

    @Test
    fun `saves the draft under the workspace root and shows the saved text`() =
        runTest(dispatcher) {
            val backend = FakeBackend(OpenCodeFileContent("text", "A=1"), editText = "A=1\n")
            val viewModel = editorFor(backend, file = ".env")
            viewModel.startEditing()
            advanceUntilIdle()
            viewModel.updateDraft("A=2\n")

            viewModel.save()
            advanceUntilIdle()

            assertEquals(listOf(Write("/workspace/app", "src/.env", "A=2\n", "A=1\n")), backend.writes)
            val state = viewModel.state.value
            assertFalse(state.isEditing)
            assertFalse(state.isSaving)
            assertEquals("A=2\n", state.content)
            assertNull(state.editError)
        }

    @Test
    fun `keeps the draft and says why when a save is refused`() =
        runTest(dispatcher) {
            val backend =
                FakeBackend(
                    OpenCodeFileContent("text", "A=1"),
                    editText = "A=1\n",
                    writeFailure = FileEditException(FileEditFailure.CHANGED_ON_DISK, "changed"),
                )
            val viewModel = editorFor(backend, file = ".env")
            viewModel.startEditing()
            advanceUntilIdle()
            viewModel.updateDraft("A=2\n")

            viewModel.save()
            advanceUntilIdle()

            val state = viewModel.state.value
            assertTrue(state.isEditing)
            assertFalse(state.isSaving)
            assertEquals("A=2\n", state.draft)
            assertEquals(CodeViewerMessages.editFailure(FileEditFailure.CHANGED_ON_DISK), state.editError)
            assertEquals("A=1", state.content)
        }

    @Test
    fun `uses the generic message for a failure it does not recognise`() =
        runTest(dispatcher) {
            val backend =
                FakeBackend(
                    OpenCodeFileContent("text", "A=1"),
                    editText = "A=1\n",
                    writeFailure = IllegalStateException("disk exploded"),
                )
            val viewModel = editorFor(backend, file = ".env")
            viewModel.startEditing()
            advanceUntilIdle()
            viewModel.updateDraft("A=2\n")

            viewModel.save()
            advanceUntilIdle()

            assertEquals(CodeViewerMessages.editFailed, viewModel.state.value.editError)
        }

    @Test
    fun `does not write when nothing changed`() =
        runTest(dispatcher) {
            val backend = FakeBackend(OpenCodeFileContent("text", "A=1"), editText = "A=1\n")
            val viewModel = editorFor(backend, file = ".env")
            viewModel.startEditing()
            advanceUntilIdle()

            viewModel.save()
            advanceUntilIdle()

            assertTrue(backend.writes.isEmpty())
            assertTrue(viewModel.state.value.isEditing)
        }

    @Test
    fun `stopping the editor discards the draft`() =
        runTest(dispatcher) {
            val backend = FakeBackend(OpenCodeFileContent("text", "A=1"), editText = "A=1\n")
            val viewModel = editorFor(backend, file = ".env")
            viewModel.startEditing()
            advanceUntilIdle()
            viewModel.updateDraft("A=2\n")

            viewModel.stopEditing()

            val state = viewModel.state.value
            assertFalse(state.isEditing)
            assertFalse(state.hasUnsavedChanges)
            assertEquals("A=1", state.content)
            assertTrue(backend.writes.isEmpty())
        }

    @Test
    fun `ignores typing, stopping and a second save while a save is in flight`() =
        runTest(dispatcher) {
            val backend = FakeBackend(OpenCodeFileContent("text", "A=1"), editText = "A=1\n")
            backend.writeGate = CompletableDeferred()
            val viewModel = editorFor(backend, file = ".env")
            viewModel.startEditing()
            advanceUntilIdle()
            viewModel.updateDraft("A=2\n")

            viewModel.save()
            runCurrent()
            assertTrue(viewModel.state.value.isSaving)
            viewModel.updateDraft("A=3\n")
            viewModel.stopEditing()
            viewModel.save()
            runCurrent()

            assertEquals("A=2\n", viewModel.state.value.draft)
            assertTrue(viewModel.state.value.isEditing)

            backend.writeGate?.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf(Write("/workspace/app", "src/.env", "A=2\n", "A=1\n")), backend.writes)
            assertEquals("A=2\n", viewModel.state.value.content)
            assertFalse(viewModel.state.value.isEditing)
        }

    @Test
    fun `typing bursts undo as one step and redo restores them`() =
        runTest(dispatcher) {
            var now = 0L
            val viewModel = editorFor(FakeBackend(OpenCodeFileContent("text", "a"), editText = "a"), file = "a.kt", clock = { now })
            viewModel.startEditing()
            advanceUntilIdle()

            now = 1_000
            viewModel.updateDraft("ab")
            now = 1_100
            viewModel.updateDraft("abc") // same burst
            now = 5_000
            viewModel.updateDraft("abcd") // new step

            viewModel.undo()
            assertEquals("abc", viewModel.state.value.draft)
            viewModel.undo()
            assertEquals("a", viewModel.state.value.draft)
            assertFalse(viewModel.state.value.canUndo)
            viewModel.redo()
            assertEquals("abc", viewModel.state.value.draft)
            assertTrue(viewModel.state.value.canRedo)

            viewModel.updateDraft("abcX")
            assertFalse(viewModel.state.value.canRedo)
        }

    @Test
    fun `undo history is bounded by total size`() =
        runTest(dispatcher) {
            var now = 0L
            val big = "x".repeat(900_000)
            val viewModel = editorFor(FakeBackend(OpenCodeFileContent("text", big), editText = big), file = "a.kt", clock = { now })
            viewModel.startEditing()
            advanceUntilIdle()
            repeat(12) { step ->
                now += 10_000
                viewModel.updateDraft(big + step)
            }

            // ~0.9M chars per snapshot against an 8M cap: only the newest eight survive.
            var undone = 0
            while (viewModel.state.value.canUndo) {
                viewModel.undo()
                undone++
            }
            assertEquals(8, undone)
        }

    @Test
    fun `replace all counts matches, honours case and undoes in one step`() =
        runTest(dispatcher) {
            val text = "val foo = Foo(foo)"
            val viewModel = editorFor(FakeBackend(OpenCodeFileContent("text", text), editText = text), file = "a.kt")
            viewModel.startEditing()
            advanceUntilIdle()

            assertEquals(2, viewModel.replaceAll("foo", "bar", ignoreCase = false))
            assertEquals("val bar = Foo(bar)", viewModel.state.value.draft)
            assertEquals(1, viewModel.replaceAll("FOO", "Baz", ignoreCase = true))
            assertEquals("val bar = Baz(bar)", viewModel.state.value.draft)
            // Literal text, not a regex: "(" is just a parenthesis.
            assertEquals(1, viewModel.replaceAll("(", "[", ignoreCase = false))
            assertEquals("val bar = Baz[bar)", viewModel.state.value.draft)
            assertEquals(0, viewModel.replaceAll("missing", "x", ignoreCase = false))

            repeat(3) { viewModel.undo() }
            assertEquals("val foo = Foo(foo)", viewModel.state.value.draft)
        }

    @Test
    fun `typing again clears the error of a refused save and keeps the draft`() =
        runTest(dispatcher) {
            val backend =
                FakeBackend(
                    OpenCodeFileContent("text", "A=1"),
                    editText = "A=1\n",
                    writeFailure = FileEditException(FileEditFailure.WRITE_FAILED, "disk full"),
                )
            val viewModel = editorFor(backend, file = ".env")
            viewModel.startEditing()
            advanceUntilIdle()
            viewModel.updateDraft("A=2\n")
            viewModel.save()
            advanceUntilIdle()
            assertTrue(viewModel.state.value.editError != null)

            viewModel.updateDraft("A=3\n")

            assertNull(viewModel.state.value.editError)
            assertEquals("A=3\n", viewModel.state.value.draft)
        }

    @Test
    fun `edit target is the file's path below the workspace root`() {
        assertEquals(
            EditTarget("/workspace/app", "src/Main.kt"),
            editTargetOf("/workspace/app", "/workspace/app/src", "Main.kt"),
        )
        assertEquals(
            EditTarget("/workspace/app", ".env"),
            editTargetOf("/workspace/app/", "/workspace/app", ".env"),
        )
        assertEquals(
            EditTarget("/workspace/my app", "docs/read me.md"),
            editTargetOf("/workspace/my app", "/workspace/my app/docs", "read me.md"),
        )
        assertEquals(
            EditTarget("/workspace/app", "src/Main.kt"),
            editTargetOf("/workspace/app", "/workspace", "app/src/Main.kt"),
        )
    }

    @Test
    fun `edit target is refused outside the workspace root`() {
        assertNull(editTargetOf(null, "/workspace/app", ".env"))
        assertNull(editTargetOf("/", "/workspace/app", ".env"))
        assertNull(editTargetOf("/workspace/app", "/workspace", "other/notes.txt"))
        assertNull(editTargetOf("/workspace/app", "/", "etc/passwd"))
        // A sibling that merely shares the root's name as a prefix is not inside it.
        assertNull(editTargetOf("/workspace/app", "/workspace/app2", "x.txt"))
    }

    @Test
    fun `edit target is refused for paths that climb with dot-dot`() {
        assertNull(editTargetOf("/workspace/app", "/workspace/app/src", "../../other/x.txt"))
        assertNull(editTargetOf("/workspace/app", "/workspace/app/../other", "x.txt"))
        assertNull(editTargetOf("/workspace/app", "/workspace/app", "src/../.env"))
    }

    /** A viewer for [file] in `src` of the `/workspace/app` workspace, with the initial load finished. */
    private fun TestScope.editorFor(
        backend: FakeBackend,
        file: String,
        clock: () -> Long = { 0L },
    ): CodeViewerViewModel {
        val viewModel =
            CodeViewerViewModel(
                backend,
                workspacePath = "/workspace/app/src",
                filePath = file,
                editableRoot = "/workspace/app",
                clock = clock,
            )
        advanceUntilIdle()
        return viewModel
    }

    private data class Write(
        val directory: String,
        val path: String,
        val content: String,
        val expectedContent: String?,
    )

    private class FakeBackend(
        private val file: OpenCodeFileContent?,
        private val editText: String = "",
        private val editFailure: Throwable? = null,
        private val writeFailure: Throwable? = null,
    ) : OpenCodeBackend {
        override val id = "fake"
        override val displayName = "Fake"
        override val kind = BackendKind.LOCAL
        var directory: String? = null
        var path: String? = null
        val editReads = mutableListOf<Pair<String, String>>()
        val writes = mutableListOf<Write>()

        /** When set, a write suspends until it is completed, so a test can act mid-save. */
        var writeGate: CompletableDeferred<Unit>? = null

        override suspend fun health() = OpenCodeHealth(true, "1.18.5")

        override suspend fun listSessions(directory: String?): List<OpenCodeSession> = emptyList()

        override suspend fun createSession(
            title: String?,
            directory: String?,
        ): OpenCodeSession = error("unused")

        override suspend fun listMessages(sessionId: String): List<OpenCodeMessage> = emptyList()

        override suspend fun listProviders() = ProviderCatalog()

        override suspend fun listAgents(): List<OpenCodeAgent> = emptyList()

        override suspend fun readFile(
            directory: String,
            path: String,
        ): OpenCodeFileContent {
            this.directory = directory
            this.path = path
            return file ?: error("file unavailable")
        }

        override suspend fun readFileForEditing(
            directory: String,
            path: String,
        ): String {
            editReads += directory to path
            editFailure?.let { throw it }
            return editText
        }

        override suspend fun writeFile(
            directory: String,
            path: String,
            content: String,
            expectedContent: String?,
        ) {
            writeGate?.await()
            writeFailure?.let { throw it }
            writes += Write(directory, path, content, expectedContent)
        }

        override suspend fun sendMessage(
            sessionId: String,
            request: PromptRequest,
        ) = Unit

        override suspend fun abortSession(sessionId: String) = true

        override suspend fun respondToPermission(
            sessionId: String,
            permissionId: String,
            response: PermissionResponse,
            remember: Boolean,
        ) = true

        override fun events(): Flow<OpenCodeEvent> = emptyFlow()
    }
}
