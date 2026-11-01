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
}
