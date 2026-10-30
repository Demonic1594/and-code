package com.yugahashimoto.andcode.feature.workspace

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yugahashimoto.andcode.feature.workspace.editor.CodeEditor
import com.yugahashimoto.andcode.feature.workspace.editor.CodeEditorState
import com.yugahashimoto.andcode.feature.workspace.editor.CodeLanguage
import com.yugahashimoto.andcode.feature.workspace.editor.EditorPalette
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A document taller than Compose constraints can carry (about 262,000 px) scrolls to its end. The
 * line-number gutter used to be sized to the text's height, which a long wrapped document (prose
 * Markdown, a few hundred lines on a phone) passes, and the editor broke part way down.
 */
@RunWith(AndroidJUnit4::class)
class CodeEditorLongDocumentInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun assertScrollsToTheEnd(
        text: String,
        wrap: Boolean,
    ) {
        val state = CodeEditorState()
        composeRule.setContent {
            CodeEditor(
                value = TextFieldValue(text),
                onValueChange = {},
                language = CodeLanguage.PLAIN,
                palette = EditorPalette.forKey(null),
                state = state,
                wrap = wrap,
                modifier = Modifier.fillMaxSize(),
            )
        }
        composeRule.waitForIdle()
        val layout = requireNotNull(state.layout)
        assertTrue("the document is taller than constraints can carry", layout.size.height > 262_143)
        composeRule.runOnIdle { runBlocking { state.vertical.scrollTo(state.vertical.maxValue) } }
        composeRule.waitForIdle()
        assertEquals(state.vertical.maxValue, state.vertical.value)
        assertTrue(state.vertical.maxValue >= layout.size.height - state.viewportHeight)
    }

    @Test
    fun wrappedProseScrollsToTheEnd() {
        val paragraph = "A long paragraph of prose, one line per paragraph, the way a Markdown report is written. ".repeat(8)
        assertScrollsToTheEnd((1..800).joinToString("\n") { "$it $paragraph" }, wrap = true)
    }

    @Test
    fun manyLinesScrollToTheEnd() {
        assertScrollsToTheEnd((1..12_000).joinToString("\n") { "line $it of a very long source file" }, wrap = false)
    }
}
