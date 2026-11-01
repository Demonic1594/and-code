package com.yugahashimoto.andcode.feature.workspace

import com.yugahashimoto.andcode.feature.workspace.editor.EditorPalette
import com.yugahashimoto.andcode.feature.workspace.editor.Token
import com.yugahashimoto.andcode.feature.workspace.editor.TokenKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A minified file can put most of itself on one line. Such a line is shown without token colours,
 * and with a bounded number of search marks, so composing it does not build a span per token.
 */
class CodeViewerLongLineTest {
    private val palette = EditorPalette.forKey(null)

    private fun tokensOf(text: String): List<Token> =
        Regex("\\w+").findAll(text).map { Token(it.range.first, it.range.last + 1, TokenKind.KEYWORD) }.toList()

    private fun matchesOf(
        text: String,
        query: String,
    ): List<IntRange> = Regex(Regex.escape(query)).findAll(text).map { it.range }.toList()

    @Test
    fun `an ordinary line keeps its token colours and every search mark`() {
        val line = "val a = b ".repeat(LongLineLimits.STYLED_CHARS / 10)
        assertEquals(LongLineLimits.STYLED_CHARS, line.length)
        val matches = matchesOf(line, "a")

        val styled = highlightRange(line, 0, line.length, tokensOf(line), palette, matches, -1)

        val marks = styled.spanStyles.count { it.item.background == palette.match }
        assertEquals(matches.size, marks)
        assertTrue(styled.spanStyles.size > marks)
    }

    @Test
    fun `a long line has no token colours and at most the capped search marks, current one included`() {
        val line = "val a = b ".repeat(LongLineLimits.STYLED_CHARS / 10 + 1)
        val matches = matchesOf(line, " ")
        assertTrue(matches.size > LongLineLimits.SEARCH_MARKS + 1)
        val current = matches.lastIndex

        val styled = highlightRange(line, 0, line.length, tokensOf(line), palette, matches, current)

        val marks = styled.spanStyles.filter { it.item.background == palette.match }
        val currentMarks = styled.spanStyles.filter { it.item.background == palette.currentMatch }
        assertEquals(LongLineLimits.SEARCH_MARKS, marks.size)
        assertEquals(listOf(matches[current].first), currentMarks.map { it.start })
        assertEquals(marks.size + currentMarks.size, styled.spanStyles.size)
    }

    @Test
    fun `only the matches on the line are marked, at offsets within it`() {
        val content = "foo bar\nbar foo bar\nfoo"
        val secondLineStart = content.indexOf('\n') + 1
        val secondLineEnd = content.indexOf('\n', secondLineStart)
        val matches = matchesOf(content, "bar")

        val styled = highlightRange(content, secondLineStart, secondLineEnd, emptyList(), palette, matches, -1)

        assertEquals(listOf(0 until 3, 8 until 11), styled.spanStyles.map { it.start until it.end })
    }

    @Test
    fun `a line is displayed whole up to the cap and truncated at it`() {
        // Whole while it fits the cap, wherever the line starts in the file: absolute offsets.
        assertEquals(20, displayEnd(3, 20))
        assertEquals(10 + LongLineLimits.DISPLAY_CHARS, displayEnd(10, 10 + LongLineLimits.DISPLAY_CHARS))
        // Truncated to the cap when the line runs past it.
        assertEquals(LongLineLimits.DISPLAY_CHARS, displayEnd(0, LongLineLimits.DISPLAY_CHARS + 50))
        assertEquals(10 + LongLineLimits.DISPLAY_CHARS, displayEnd(10, 10 + LongLineLimits.DISPLAY_CHARS + 50))
    }

    @Test
    fun `a current match past the cap moves the window to just before it`() {
        val cap = LongLineLimits.DISPLAY_CHARS
        val lineEnd = 10 + cap * 3
        // No match, or one inside the head of the line: the head is shown, as before.
        assertEquals(10..10 + cap, displayRange(10, lineEnd, null))
        assertEquals(10..10 + cap, displayRange(10, lineEnd, 500..504))
        // A match further in: the window starts a little before it, so it is near the row's start.
        val far = 10 + cap * 2
        assertEquals(far - LongLineLimits.MATCH_CONTEXT..far - LongLineLimits.MATCH_CONTEXT + cap, displayRange(10, lineEnd, far..far + 4))
        // One that straddles the cap counts as hidden too.
        val straddle = 10 + cap - 2
        assertEquals(straddle - LongLineLimits.MATCH_CONTEXT, displayRange(10, lineEnd, straddle..straddle + 4).first)
        // Near the end of the line the window stops at the line's end.
        assertEquals(lineEnd, displayRange(10, lineEnd, lineEnd - 3..lineEnd - 1).last)
        // A match on another line leaves this one alone, and a short line is always whole.
        assertEquals(10..10 + cap, displayRange(10, lineEnd, lineEnd + 50..lineEnd + 55))
        assertEquals(3..20, displayRange(3, 20, 5..7))
    }

    @Test
    fun `the match shown past the cap is highlighted as the current one`() {
        val cap = LongLineLimits.DISPLAY_CHARS
        val content = "a".repeat(cap * 2) + "needle" + "b".repeat(100)
        val match = cap * 2 until cap * 2 + 6
        val shown = displayRange(0, content.length, match)

        val styled = highlightRange(content, shown.first, shown.last, emptyList(), palette, listOf(match), 0)

        assertEquals("needle", styled.text.substring(styled.spanStyles.single().start, styled.spanStyles.single().end))
    }
}
