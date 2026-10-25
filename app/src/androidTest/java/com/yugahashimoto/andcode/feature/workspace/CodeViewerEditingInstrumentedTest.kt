package com.yugahashimoto.andcode.feature.workspace

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.core.api.OpenCodeAgent
import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.OpenCodeFileContent
import com.yugahashimoto.andcode.core.api.OpenCodeHealth
import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodeSession
import com.yugahashimoto.andcode.core.api.PromptRequest
import com.yugahashimoto.andcode.core.api.ProviderCatalog
import com.yugahashimoto.andcode.runtime.BackendKind
import com.yugahashimoto.andcode.runtime.OpenCodeBackend
import com.yugahashimoto.andcode.runtime.PermissionResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Drives the real [CodeViewerScreen] with the real [CodeViewerViewModel] over a fake backend: the
 * parts a JVM test cannot see - the edit action's visibility, the text field, and the back
 * handling that guards unsaved edits.
 */
@RunWith(AndroidJUnit4::class)
class CodeViewerEditingInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val backend = RecordingBackend()
    private var backs = 0

    /** Looked up once: the scenario refuses to hand the activity out again after it has finished. */
    private lateinit var activity: ComponentActivity

    @Test
    fun noEditActionWhenTheRuntimeGrantsNoEditingRoot() {
        show(editableRoot = null)

        composeRule.onNodeWithContentDescription(string(R.string.file_edit_action)).assertDoesNotExist()
    }

    @Test
    fun savingWritesTheTypedTextToTheWorkspaceFile() {
        show(editableRoot = "/workspace/app")
        startEditing()

        composeRule.onAllNodes(hasSetTextAction()).onFirst().performTextReplacement("API_URL=https://example.test\n")
        composeRule.onNodeWithContentDescription(string(R.string.file_edit_save)).performClick()

        composeRule.waitUntil(WAIT_MS) { backend.writes.isNotEmpty() }
        assertEquals(
            listOf(Write("/workspace/app", ".env", "API_URL=https://example.test\n", "API_URL=http://localhost\n")),
            backend.writes.toList(),
        )
    }

    @Test
    fun leavingWithUnsavedEditsAsksFirstAndKeepsThemUnlessDiscarded() {
        show(editableRoot = "/workspace/app")
        startEditing()
        composeRule.onAllNodes(hasSetTextAction()).onFirst().performTextReplacement("CHANGED=1\n")
        composeRule.onNodeWithContentDescription(string(R.string.file_edit_save)).assertIsEnabled()
        assertTrue("unsaved edits should be guarded by a back callback", activity.onBackPressedDispatcher.hasEnabledCallbacks())

        pressBack()
        assertFalse("back must not close the screen while edits are unsaved", activity.isFinishing)
        composeRule.onNodeWithText(string(R.string.file_edit_discard_title)).assertIsDisplayed()
        assertEquals(0, backs)

        composeRule.onNodeWithText(string(R.string.file_edit_keep_editing)).performClick()
        composeRule.onNodeWithText(string(R.string.file_edit_discard_title)).assertDoesNotExist()
        assertEquals(0, backs)
        assertTrue(composeRule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty())

        pressBack()
        assertFalse("back must still ask after the user chose to keep editing", activity.isFinishing)
        composeRule.onNodeWithText(string(R.string.file_edit_discard_confirm)).performClick()
        composeRule.waitUntil(WAIT_MS) { backs == 1 }
        assertTrue(backend.writes.isEmpty())
    }

    @Test
    fun leavingWithoutEditsDoesNotAsk() {
        show(editableRoot = "/workspace/app")
        startEditing()

        composeRule.onNodeWithContentDescription(string(R.string.cd_back)).performClick()

        composeRule.waitUntil(WAIT_MS) { backs == 1 }
        composeRule.onNodeWithText(string(R.string.file_edit_discard_title)).assertDoesNotExist()
    }

    private fun show(editableRoot: String?) {
        activity = composeRule.activity
        val viewModel =
            CodeViewerViewModel(
                backend = backend,
                workspacePath = "/workspace/app",
                filePath = ".env",
                editableRoot = editableRoot,
            )
        composeRule.setContent {
            val state by viewModel.state.collectAsState()
            MaterialTheme {
                CodeViewerScreen(
                    state = state,
                    onBack = { backs++ },
                    onStartEditing = viewModel::startEditing,
                    onDraftChange = viewModel::updateDraft,
                    onSave = viewModel::save,
                    onStopEditing = viewModel::stopEditing,
                )
            }
        }
        composeRule.waitUntil(WAIT_MS) {
            composeRule.onAllNodes(hasText("API_URL", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun startEditing() {
        composeRule.onNodeWithContentDescription(string(R.string.file_edit_action)).performClick()
        composeRule.waitUntil(WAIT_MS) { composeRule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun pressBack() {
        // The edit has to have reached the screen's state, and so the back handler's enabled flag,
        // before the press: without this the press can win the race against that recomposition.
        composeRule.waitForIdle()
        composeRule.runOnUiThread { activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
    }

    private fun string(id: Int): String = activity.getString(id)

    private data class Write(
        val directory: String,
        val path: String,
        val content: String,
        val expectedContent: String?,
    )

    private class RecordingBackend : OpenCodeBackend {
        override val id = "fake"
        override val displayName = "Fake"
        override val kind = BackendKind.LOCAL
        val writes = CopyOnWriteArrayList<Write>()

        override suspend fun readFile(
            directory: String,
            path: String,
        ) = OpenCodeFileContent("text", "API_URL=http://localhost")

        override suspend fun readFileForEditing(
            directory: String,
            path: String,
        ) = "API_URL=http://localhost\n"

        override suspend fun writeFile(
            directory: String,
            path: String,
            content: String,
            expectedContent: String?,
        ) {
            writes += Write(directory, path, content, expectedContent)
        }

        override suspend fun health() = OpenCodeHealth(true, "1.18.5")

        override suspend fun listSessions(directory: String?): List<OpenCodeSession> = emptyList()

        override suspend fun createSession(
            title: String?,
            directory: String?,
        ): OpenCodeSession = error("unused")

        override suspend fun listMessages(sessionId: String): List<OpenCodeMessage> = emptyList()

        override suspend fun listProviders() = ProviderCatalog()

        override suspend fun listAgents(): List<OpenCodeAgent> = emptyList()

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

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
