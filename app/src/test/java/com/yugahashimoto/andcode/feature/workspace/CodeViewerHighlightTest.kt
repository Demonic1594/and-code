package com.yugahashimoto.andcode.feature.workspace

import androidx.compose.ui.graphics.Color
import com.yugahashimoto.andcode.ui.theme.OneDarkSyntax
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The code viewer highlights each visible line while composing, on the main thread. A minified file
 * can put a megabyte on one line, and tokenizing it into tens of thousands of styled spans there
 * showed up as an ANR in `highlightLine` in Crashlytics, so the work per line has to be bounded.
 */
class CodeViewerHighlightTest {
    @Test
    fun `highlights the tokens of an ordinary line`() {
        val result = highlightLine("val x = 1", OneDarkSyntax, "")

        assertEquals("val x = 1", result.text)
        assertTrue(result.spanStyles.any { it.item.color == OneDarkSyntax.keyword })
        assertTrue(result.spanStyles.any { it.item.color == OneDarkSyntax.number })
    }

    @Test
    fun `a line exactly at the limit is still highlighted`() {
        val line = "val " + "x".repeat(MAX_HIGHLIGHTED_LINE_CHARS - 4)

        val result = highlightLine(line, OneDarkSyntax, "")

        assertEquals(MAX_HIGHLIGHTED_LINE_CHARS, line.length)
        assertTrue(result.spanStyles.any { it.item.color == OneDarkSyntax.keyword })
    }

    @Test
    fun `a line past the limit is shown whole but is not tokenized`() {
        // Every `"a" ` is a string token, so tokenizing this line would add thousands of spans.
        val line = "\"a\" ".repeat(MAX_HIGHLIGHTED_LINE_CHARS)

        val result = highlightLine(line, OneDarkSyntax, "")

        assertTrue(line.length > MAX_HIGHLIGHTED_LINE_CHARS)
        assertEquals(line, result.text)
        assertTrue(result.spanStyles.isEmpty())
    }

    @Test
    fun `a line one character past the limit is not tokenized`() {
        val line = "val " + "x".repeat(MAX_HIGHLIGHTED_LINE_CHARS - 3)

        val result = highlightLine(line, OneDarkSyntax, "")

        assertEquals(MAX_HIGHLIGHTED_LINE_CHARS + 1, line.length)
        assertTrue(result.spanStyles.isEmpty())
    }

    @Test
    fun `token styling and search marks coexist on an ordinary line`() {
        val result = highlightLine("val x", OneDarkSyntax, "x")

        assertTrue(result.spanStyles.any { it.item.color == OneDarkSyntax.keyword })
        assertTrue(result.spanStyles.any { it.item.background != Color.Unspecified })
    }

    @Test
    fun `search marks on a very long line are capped`() {
        // One-character query on a long line: tens of thousands of matches would each add a span.
        val line = "x ".repeat(MAX_HIGHLIGHTED_LINE_CHARS * 10)

        val result = highlightLine(line, OneDarkSyntax, "x")

        assertEquals(MAX_SEARCH_MARKS_ON_LONG_LINE, result.spanStyles.size)
    }

    @Test
    fun `an ordinary line keeps every search mark`() {
        val line = "x ".repeat(900)

        val result = highlightLine(line, OneDarkSyntax, "x")

        assertTrue(line.length <= MAX_HIGHLIGHTED_LINE_CHARS)
        assertEquals(900, result.spanStyles.size)
    }

    @Test
    fun `search matches are still marked on a line past the limit`() {
        val line = "x ".repeat(MAX_HIGHLIGHTED_LINE_CHARS) + "needle"

        val result = highlightLine(line, OneDarkSyntax, "needle")

        assertEquals(1, result.spanStyles.size)
        assertEquals(line.length - "needle".length, result.spanStyles.single().start)
    }
}
