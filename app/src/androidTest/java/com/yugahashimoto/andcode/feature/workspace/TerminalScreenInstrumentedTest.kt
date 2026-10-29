package com.yugahashimoto.andcode.feature.workspace

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.feature.terminal.DevServer
import com.yugahashimoto.andcode.feature.terminal.DevServerStatus
import com.yugahashimoto.andcode.feature.terminal.StyledLine
import com.yugahashimoto.andcode.feature.terminal.TerminalKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Drives the real [TerminalScreen] over a plain state value: what a JVM test cannot see - that the
 * screen measures itself and reports a usable size, that the keys it offers send what they claim,
 * and that input is only possible while a shell is actually running.
 */
@RunWith(AndroidJUnit4::class)
class TerminalScreenInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val opened = CopyOnWriteArrayList<Pair<Int, Int>>()
    private val keys = CopyOnWriteArrayList<TerminalKey>()
    private val inputs = CopyOnWriteArrayList<String>()
    private var submits = 0
    private var restarts = 0
    private var clears = 0
    private val openedServers = CopyOnWriteArrayList<String>()

    private var state by mutableStateOf(TerminalUiState(status = TerminalStatus.RUNNING))

    /** What the screen is given to work with; shrinking it is what the soft keyboard does. */
    private var heightDp by mutableStateOf(640)

    private fun show(initial: TerminalUiState) {
        state = initial
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxWidth().height(heightDp.dp)) {
                    TerminalScreen(
                        state = state,
                        onOpen = { columns, rows -> opened += columns to rows },
                        onInputChange = { value ->
                            inputs += value
                            state = state.copy(currentInput = value)
                        },
                        onSubmit = { submits++ },
                        onKey = { keys += it },
                        onRestart = { restarts++ },
                        onClear = { clears++ },
                        onOpenServer = { openedServers += it },
                    )
                }
            }
        }
    }

    private fun lines(count: Int) = List(count) { StyledLine("line $it") }

    private fun update(change: () -> Unit) {
        composeRule.runOnIdle(change)
        composeRule.waitForIdle()
    }

    private fun isVisible(text: String): Boolean = runCatching { composeRule.onNodeWithText(text).assertIsDisplayed() }.isSuccess

    private fun string(id: Int): String = composeRule.activity.getString(id)

    @Test
    fun reportsAUsableSizeOnceItHasBeenMeasured() {
        show(TerminalUiState(status = TerminalStatus.IDLE))

        composeRule.waitUntil(WAIT_MS) { opened.isNotEmpty() }

        val (columns, rows) = opened.first()
        assertTrue("columns=$columns", columns in 10..300)
        assertTrue("rows=$rows", rows in 3..200)
    }

    @Test
    fun drawsTheShellOutput() {
        show(
            TerminalUiState(
                lines = listOf(StyledLine("server listening on :3000"), StyledLine("")),
                cursorRow = 1,
                status = TerminalStatus.RUNNING,
            ),
        )

        composeRule.onNodeWithText("server listening on :3000").assertIsDisplayed()
    }

    @Test
    fun aRunningServerIsOfferedAndOpensItsAddress() {
        show(TerminalUiState(status = TerminalStatus.RUNNING, servers = listOf(DevServer(5173, DevServerStatus.RUNNING))))

        composeRule.onNodeWithContentDescription(composeRule.activity.getString(R.string.cd_terminal_server_open, 5173)).apply {
            assertIsDisplayed()
            performClick()
        }

        assertEquals(listOf("http://localhost:5173/"), openedServers.toList())
    }

    @Test
    fun aStoppedServerIsShownAsStoppedAndHasNothingToOpen() {
        show(TerminalUiState(status = TerminalStatus.RUNNING, servers = listOf(DevServer(5173, DevServerStatus.STOPPED))))

        composeRule.onNodeWithContentDescription(composeRule.activity.getString(R.string.cd_terminal_server_stopped, 5173)).apply {
            assertIsDisplayed()
            assertHasNoClickAction()
        }
        composeRule.onNodeWithText(string(R.string.terminal_server_stopped)).assertIsDisplayed()
        assertTrue(openedServers.isEmpty())
    }

    @Test
    fun severalServersAreEachOffered() {
        show(
            TerminalUiState(
                status = TerminalStatus.RUNNING,
                servers = listOf(DevServer(3000, DevServerStatus.RUNNING), DevServer(5173, DevServerStatus.RUNNING)),
            ),
        )

        composeRule.onNodeWithTag("terminal-server-5173").performClick()
        composeRule.onNodeWithTag("terminal-server-3000").performClick()

        assertEquals(listOf("http://localhost:5173/", "http://localhost:3000/"), openedServers.toList())
    }

    @Test
    fun noServersMeansNoServerRow() {
        show(TerminalUiState(status = TerminalStatus.RUNNING))

        composeRule.onNodeWithTag("terminal-server-5173").assertDoesNotExist()
    }

    @Test
    fun theOffscreenKeysSendTheirSequences() {
        show(TerminalUiState(status = TerminalStatus.RUNNING))

        composeRule.onNodeWithContentDescription(string(R.string.cd_terminal_interrupt)).performClick()
        composeRule.onNodeWithContentDescription(string(R.string.cd_terminal_key_up)).performClick()
        composeRule.onNodeWithText("Tab").performClick()
        composeRule.onNodeWithText("Esc").performClick()
        composeRule.onNodeWithContentDescription(string(R.string.cd_terminal_end_of_input)).performClick()

        assertEquals(
            listOf(TerminalKey.CTRL_C, TerminalKey.UP, TerminalKey.TAB, TerminalKey.ESCAPE, TerminalKey.CTRL_D),
            keys.toList(),
        )
    }

    @Test
    fun typedTextIsReportedAndSendSubmitsIt() {
        show(TerminalUiState(status = TerminalStatus.RUNNING))

        composeRule.onAllNodes(hasSetTextAction()).onFirst().performTextReplacement("ls -la")
        composeRule.onNodeWithContentDescription(string(R.string.cd_send_command)).performClick()

        assertEquals(listOf("ls -la"), inputs.toList())
        assertEquals(1, submits)
    }

    @Test
    fun inputAndKeysAreDisabledUntilAShellIsRunning() {
        show(TerminalUiState(status = TerminalStatus.STARTING))

        composeRule.onNodeWithContentDescription(string(R.string.cd_send_command)).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(string(R.string.cd_terminal_interrupt)).assertIsNotEnabled()
        composeRule.onNodeWithText(string(R.string.terminal_starting)).assertIsDisplayed()
    }

    @Test
    fun anEndedSessionExplainsItselfAndOffersARestart() {
        show(TerminalUiState(status = TerminalStatus.EXITED, exitCode = 130))

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.terminal_session_ended, 130)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.terminal_restart)).assertIsEnabled().performClick()

        assertEquals(1, restarts)
    }

    @Test
    fun aMissingRuntimeIsExplained() {
        show(TerminalUiState(status = TerminalStatus.FAILED, failure = TerminalFailure.NOT_INSTALLED))

        composeRule.onNodeWithText(string(R.string.terminal_not_installed)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.terminal_restart)).assertIsDisplayed()
    }

    @Test
    fun clearIsAlwaysAvailable() {
        show(TerminalUiState(status = TerminalStatus.RUNNING))

        composeRule.onNodeWithContentDescription(string(R.string.cd_clear_terminal)).performClick()

        assertEquals(1, clears)
    }

    @Test
    fun followsNewOutputWhileTheReaderIsAtTheEnd() {
        show(TerminalUiState(lines = lines(200), cursorRow = 199, status = TerminalStatus.RUNNING))
        composeRule.waitForIdle()
        assertTrue(isVisible("line 199"))

        update { state = state.copy(lines = lines(205), cursorRow = 204) }

        assertTrue(isVisible("line 204"))
    }

    @Test
    fun keepsFollowingWhenTheKeyboardShrinksTheScreen() {
        show(TerminalUiState(lines = lines(200), cursorRow = 199, status = TerminalStatus.RUNNING))
        composeRule.waitForIdle()

        update { heightDp = 300 }
        assertTrue("the end stays in view when the viewport shrinks", isVisible("line 199"))

        update { state = state.copy(lines = lines(203), cursorRow = 202) }
        assertTrue("output keeps being followed after the keyboard came up", isVisible("line 202"))
    }

    @Test
    fun sendingInputBringsTheReaderBackToTheEnd() {
        show(TerminalUiState(lines = lines(200), cursorRow = 199, status = TerminalStatus.RUNNING))
        composeRule.waitForIdle()
        composeRule.onNode(hasScrollAction()).performTouchInput { swipeDown() }
        composeRule.waitForIdle()
        update { state = state.copy(lines = lines(210), cursorRow = 209) }
        assertFalse(isVisible("line 209"))

        composeRule.onNodeWithContentDescription(string(R.string.cd_send_command)).performClick()
        update { state = state.copy(lines = lines(211), cursorRow = 210) }

        assertTrue("typing something means wanting to see what comes back", isVisible("line 210"))
    }

    @Test
    fun stopsFollowingOnceTheReaderScrollsUpAndResumesAtTheEnd() {
        show(TerminalUiState(lines = lines(200), cursorRow = 199, status = TerminalStatus.RUNNING))
        composeRule.waitForIdle()

        composeRule.onNode(hasScrollAction()).performTouchInput { swipeDown() }
        composeRule.waitForIdle()
        update { state = state.copy(lines = lines(210), cursorRow = 209) }
        assertFalse("a reader who scrolled up is not yanked back down", isVisible("line 209"))

        repeat(6) { composeRule.onNode(hasScrollAction()).performTouchInput { swipeUp() } }
        composeRule.waitForIdle()
        update { state = state.copy(lines = lines(220), cursorRow = 219) }
        assertTrue("scrolling back to the end resumes following", isVisible("line 219"))
    }

    private companion object {
        const val WAIT_MS = 10_000L
    }
}
