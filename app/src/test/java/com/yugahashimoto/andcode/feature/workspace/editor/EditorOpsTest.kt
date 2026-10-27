package com.yugahashimoto.andcode.feature.workspace.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorOpsTest {
    private val kotlin = CodeLanguage.KOTLIN
    private val python = CodeLanguage.PYTHON

    /** `|` marks the cursor; `[` and `]` a selection. */
    private fun v(marked: String): TextFieldValue {
        val start = marked.indexOf('[')
        if (start >= 0) {
            val end = marked.indexOf(']') - 1
            return TextFieldValue(marked.replace("[", "").replace("]", ""), TextRange(start, end))
        }
        val at = marked.indexOf('|')
        return TextFieldValue(marked.replace("|", ""), TextRange(at))
    }

    private fun TextFieldValue.marked(): String =
        if (selection.collapsed) {
            text.substring(0, selection.start) + "|" + text.substring(selection.start)
        } else {
            text.substring(0, selection.min) + "[" + text.substring(selection.min, selection.max) + "]" + text.substring(selection.max)
        }

    @Test
    fun `newline keeps the indent and adds a level after an opener`() {
        assertEquals("    a\n    |", EditorOps.newline(v("    a|"), kotlin).marked())
        assertEquals("fun x() {\n    |", EditorOps.newline(v("fun x() {|"), kotlin).marked())
        assertEquals("  f(\n      |\n  )", EditorOps.newline(v("  f(|)"), kotlin).marked())
        assertEquals("if x:\n    |", EditorOps.newline(v("if x:|"), python).marked())
        assertEquals("val m = a:\n|", EditorOps.newline(v("val m = a:|"), kotlin).marked())
    }

    @Test
    fun `a typed newline is auto-indented and other edits are left alone`() {
        val old = v("  if (a) {|}")
        val typed = TextFieldValue("  if (a) {\n}", TextRange(11))
        assertEquals("  if (a) {\n      |\n  }", EditorOps.autoIndent(old, typed, kotlin)?.marked())
        assertNull(EditorOps.autoIndent(old, TextFieldValue("  if (a) {x}", TextRange(11)), kotlin))
        assertNull(EditorOps.autoIndent(v("a\nb|"), TextFieldValue("a\nb\n\n", TextRange(5)), kotlin))
    }

    @Test
    fun `pairs wrap a selection or open around the cursor`() {
        assertEquals("f(|)", EditorOps.insertPair(v("f|"), '(').marked())
        assertEquals("\"[word]\"", EditorOps.insertPair(v("[word]"), '"').marked())
    }

    @Test
    fun `indent and outdent work on every selected line`() {
        assertEquals("[    a\n    b]\nc", EditorOps.indent(v("[a\nb]\nc"), kotlin).marked())
        assertEquals("[a\nb]", EditorOps.outdent(v("[    a\n  b]"), kotlin).marked())
        assertEquals("a    |b", EditorOps.indent(v("a|b"), kotlin).marked())
        assertEquals("a|", EditorOps.outdent(v("    a|"), kotlin).marked())
    }

    @Test
    fun `comment toggles at the shared indent and back`() {
        val commented = EditorOps.toggleComment(v("[  a\n    b]"), kotlin)
        assertEquals("[  // a\n  //   b]", commented.marked())
        assertEquals("[  a\n    b]", EditorOps.toggleComment(commented, kotlin).marked())
        assertEquals("# x|", EditorOps.toggleComment(v("x|"), python).marked())
        val html = EditorOps.toggleComment(v("  <b>|"), CodeLanguage.HTML)
        assertEquals("  <!-- <b> -->", html.text)
        assertEquals("  <b>", EditorOps.toggleComment(html, CodeLanguage.HTML).text)
    }

    @Test
    fun `lines duplicate, delete and move with the cursor`() {
        assertEquals("a\nb\nb|\nc", EditorOps.duplicateLines(v("a\nb|\nc")).marked())
        assertEquals("a\n|c", EditorOps.deleteLines(v("a\nb|\nc")).marked())
        assertEquals("a|", EditorOps.deleteLines(v("a\nb|")).marked())
        assertEquals("b|\na\nc", EditorOps.moveLines(v("a\nb|\nc"), down = false).marked())
        assertEquals("a\nc\nb|", EditorOps.moveLines(v("a\nb|\nc"), down = true).marked())
        assertEquals("a|\nb", EditorOps.moveLines(v("a|\nb"), down = false).marked())
    }

    @Test
    fun `the cursor moves by line keeping its column`() {
        assertEquals("abc\nde|", EditorOps.moveCursor(v("ab|c\nde"), EditorOps.Direction.DOWN).marked())
        assertEquals("a|bc\nde", EditorOps.moveCursor(v("abc\nd|e"), EditorOps.Direction.UP).marked())
        assertEquals("  |x", EditorOps.moveCursor(v("  x|"), EditorOps.Direction.HOME).marked())
    }

    @Test
    fun `lines and columns are one-based`() {
        assertEquals(2 to 3, EditorOps.lineAndColumn("ab\ncde", 5))
        assertEquals(3, EditorOps.offsetOfLine("ab\ncde", 2))
        assertEquals(3, EditorOps.offsetOfLine("ab\ncde", 99))
    }

    @Test
    fun `find honours case, whole words and regular expressions`() {
        val text = "foo Foo food foo"
        assertEquals(4, EditorOps.findAll(text, "foo", EditorOps.FindOptions()).size)
        assertEquals(listOf(0..2, 13..15), EditorOps.findAll(text, "foo", EditorOps.FindOptions(matchCase = true, wholeWord = true)))
        assertEquals(1, EditorOps.findAll(text, "fo+d", EditorOps.FindOptions(regex = true)).size)
        assertTrue(EditorOps.findAll(text, "(", EditorOps.FindOptions(regex = true)).isEmpty())
        assertEquals(
            "bar Foo food bar" to 2,
            EditorOps.replaceAll(text, "foo", "bar", EditorOps.FindOptions(matchCase = true, wholeWord = true)),
        )
        assertEquals("a\$b" to 1, EditorOps.replaceAll("a.b", ".", "\$", EditorOps.FindOptions()))
        assertEquals("b-a" to 1, EditorOps.replaceAll("a-b", "(a)-(b)", "$2-$1", EditorOps.FindOptions(regex = true)))
    }

    @Test
    fun `brackets match across nesting`() {
        val text = "f(a(b)c)"
        assertEquals(1 to 7, EditorOps.matchingBracket(text, 1))
        assertEquals(7 to 1, EditorOps.matchingBracket(text, 8))
        assertNull(EditorOps.matchingBracket("f(a", 2))
    }

    @Test
    fun `replace all skips empty matches, as find does`() {
        assertEquals("aXb" to 1, EditorOps.replaceAll("a1b", "\\d*", "X", EditorOps.FindOptions(regex = true)))
        assertEquals(1, EditorOps.findAll("a1b", "\\d*", EditorOps.FindOptions(regex = true)).size)
    }

    @Test
    fun `a single replace keeps lookaround context and moves past the replacement`() {
        val value = v("|x.name = name")
        val range = EditorOps.findAll(value.text, "(?<=\\.)name", EditorOps.FindOptions(regex = true)).single()
        assertEquals(
            "x.label| = name",
            EditorOps.replaceMatch(value, range, "(?<=\\.)name", "label", EditorOps.FindOptions(regex = true)).marked(),
        )
        val log = v("|log(a)")
        assertEquals("logger|(a)", EditorOps.replaceMatch(log, 0..2, "log", "logger", EditorOps.FindOptions()).marked())
    }
}
