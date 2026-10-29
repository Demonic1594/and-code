package com.yugahashimoto.andcode.feature.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WrappedLinesTest {
    private fun rows(vararg texts: String) = texts.map { StyledLine(it) }

    private fun join(
        rows: List<StyledLine>,
        columns: Int,
        include: (StyledLine) -> Boolean = { true },
    ) = WrappedLines.join(rows, columns, include)

    @Test
    fun `rows that were not wrapped stay separate`() {
        assertEquals(listOf("one", "two", "three"), join(rows("one", "two", "three"), columns = 10))
    }

    @Test
    fun `a row that fills the width is joined to the one after it`() {
        // 10 columns: the first row is full, so the program's line continues on the second.
        assertEquals(listOf("0123456789tail", "next"), join(rows("0123456789", "tail", "next"), columns = 10))
    }

    @Test
    fun `a line wrapped over several rows is joined whole`() {
        assertEquals(listOf("aaaaabbbbbccc"), join(rows("aaaaa", "bbbbb", "ccc"), columns = 5))
    }

    @Test
    fun `only lines with an included row are returned`() {
        val old = StyledLine("0123456789")
        val tail = StyledLine("tail")
        val other = StyledLine("other")

        val found = join(listOf(old, tail, other), columns = 10) { it === tail }

        assertEquals(listOf("0123456789tail"), found)
    }

    @Test
    fun `a line that may go on has its trailing digits held back`() {
        // The row is full, so "80" may be the front of "8000": it must not be read as a port yet.
        assertEquals(listOf("http://localhost:"), join(rows("http://localhost:80"), columns = 19))
    }

    @Test
    fun `a line that ended keeps its trailing digits`() {
        assertEquals(listOf("http://localhost:8000"), join(rows("http://localhost:8000"), columns = 40))
    }

    @Test
    fun `a full row followed by an empty row is a finished line`() {
        // The empty row is what a newline after a full-width line leaves behind.
        assertEquals(listOf("http://localhost:80"), join(rows("http://localhost:80", ""), columns = 19))
    }

    @Test
    fun `a full row at the very end of the screen is the end of the group`() {
        assertEquals(listOf("abcde"), join(rows("abcde"), columns = 5))
    }

    @Test
    fun `without a usable width nothing is joined`() {
        assertEquals(listOf("a", "b"), join(rows("a", "b"), columns = 0))
        assertEquals(listOf("a", "b"), join(rows("a", "b"), columns = -3))
    }

    @Test
    fun `no rows means no lines`() {
        assertTrue(join(emptyList(), columns = 10).isEmpty())
    }
}
