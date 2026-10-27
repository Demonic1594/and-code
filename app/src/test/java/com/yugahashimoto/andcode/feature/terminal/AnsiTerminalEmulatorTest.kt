package com.yugahashimoto.andcode.feature.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnsiTerminalEmulatorTest {
    private fun AnsiTerminalEmulator.texts(): List<String> = snapshot().lines.map { it.text }

    private fun emulator(
        columns: Int = 80,
        rows: Int = 24,
        maxLines: Int = 10_000,
    ) = AnsiTerminalEmulator(columns = columns, rows = rows, maxLines = maxLines)

    @Test
    fun `starts with a single empty line`() {
        assertEquals(listOf(""), emulator().texts())
    }

    @Test
    fun `carriage return and line feed start a new line`() {
        val terminal = emulator()
        terminal.feed("hello\r\nworld\r\n")
        assertEquals(listOf("hello", "world", ""), terminal.texts())
    }

    @Test
    fun `a bare line feed keeps the column like a real terminal`() {
        val terminal = emulator()
        terminal.feed("ab\ncd")
        assertEquals(listOf("ab", "  cd"), terminal.texts())
    }

    @Test
    fun `carriage return rewrites the line in place`() {
        val terminal = emulator()
        terminal.feed("10%\r50%\r100%")
        assertEquals(listOf("100%"), terminal.texts())

        val partial = emulator()
        partial.feed("abc\rX")
        assertEquals(listOf("Xbc"), partial.texts())
    }

    @Test
    fun `backspace moves left without erasing`() {
        val terminal = emulator()
        terminal.feed("abc\b\bX")
        assertEquals(listOf("aXc"), terminal.texts())
    }

    @Test
    fun `erase in line variants`() {
        val toEnd = emulator()
        toEnd.feed("hello\u001b[2D\u001b[K")
        assertEquals(listOf("hel"), toEnd.texts())

        val toStart = emulator()
        // Mode 1 erases up to and including the cursor cell, so the cell under it goes too.
        toStart.feed("hello\u001b[2D\u001b[1K")
        assertEquals(listOf("    o"), toStart.texts())

        val whole = emulator()
        whole.feed("hello\u001b[2K")
        assertEquals(listOf(""), whole.texts())
    }

    @Test
    fun `cursor forward and back`() {
        val back = emulator()
        back.feed("abc\u001b[2DX")
        assertEquals(listOf("aXc"), back.texts())

        val forward = emulator()
        forward.feed("a\u001b[3Cb")
        assertEquals(listOf("a   b"), forward.texts())
    }

    @Test
    fun `delete and insert characters`() {
        val delete = emulator()
        delete.feed("hello\u001b[3D\u001b[2P")
        assertEquals(listOf("heo"), delete.texts())

        val insert = emulator()
        insert.feed("hello\u001b[3D\u001b[2@")
        assertEquals(listOf("he  llo"), insert.texts())
    }

    @Test
    fun `cursor up rewrites an earlier line`() {
        val terminal = emulator()
        terminal.feed("one\r\ntwo\u001b[A\rONE")
        assertEquals(listOf("ONE", "two"), terminal.texts())
    }

    @Test
    fun `cursor movement is clamped to the buffer`() {
        val terminal = emulator()
        terminal.feed("abc\u001b[9A\u001b[9D\u001b[9BX")
        assertEquals(listOf("Xbc"), terminal.texts())
    }

    @Test
    fun `tab advances to the next eight column stop`() {
        val terminal = emulator()
        terminal.feed("a\tb")
        assertEquals(listOf("a       b"), terminal.texts())
    }

    @Test
    fun `text wraps at the column count without a blank line after an exact fit`() {
        val wrapped = emulator(columns = 5)
        wrapped.feed("abcdefgh")
        assertEquals(listOf("abcde", "fgh"), wrapped.texts())

        val exact = emulator(columns = 5)
        exact.feed("abcde\r\nxy")
        assertEquals(listOf("abcde", "xy"), exact.texts())
    }

    @Test
    fun `clear screen discards everything and homes the cursor`() {
        val terminal = emulator()
        terminal.feed("junk\r\nmore\r\n\u001b[H\u001b[2Jnew")
        assertEquals(listOf("new"), terminal.texts())

        val scrollback = emulator()
        scrollback.feed("junk\r\n\u001b[3J\u001b[Hnew")
        assertEquals(listOf("new"), scrollback.texts())
    }

    @Test
    fun `erase below clears from the cursor to the end`() {
        val terminal = emulator()
        terminal.feed("one\r\ntwo\r\nthree\u001b[2A\r\u001b[J")
        assertEquals(listOf(""), terminal.texts())
    }

    @Test
    fun `absolute cursor position is relative to the visible window`() {
        val terminal = emulator(rows = 3)
        terminal.feed("a\r\nb\r\nc\u001b[1;1HX")
        assertEquals(listOf("X", "b", "c"), terminal.texts())

        val column = emulator(rows = 3)
        column.feed("abcd\u001b[1;3HX")
        assertEquals(listOf("abXd"), column.texts())
    }

    @Test
    fun `scrollback keeps only the newest lines`() {
        val terminal = emulator(maxLines = 3)
        terminal.feed("1\r\n2\r\n3\r\n4\r\n5")
        assertEquals(listOf("3", "4", "5"), terminal.texts())
    }

    @Test
    fun `ignores bell and unknown private modes`() {
        val terminal = emulator()
        terminal.feed("a\u0007\u001b[?25l\u001b[?1049hb\u001b[?2004h")
        assertEquals(listOf("ab"), terminal.texts())
    }

    @Test
    fun `swallows window title and charset sequences`() {
        val bell = emulator()
        bell.feed("\u001b]0;title\u0007hi")
        assertEquals(listOf("hi"), bell.texts())

        val stringTerminator = emulator()
        stringTerminator.feed("\u001b]2;title\u001b\\hi")
        assertEquals(listOf("hi"), stringTerminator.texts())

        val charset = emulator()
        charset.feed("\u001b(B\u001b=\u001b>hi")
        assertEquals(listOf("hi"), charset.texts())
    }

    @Test
    fun `an escape sequence split across chunks is still understood`() {
        val terminal = emulator()
        terminal.feed("a\u001b[")
        terminal.feed("31mb")
        val spans = terminal.snapshot().lines.single().spans
        assertEquals("ab", terminal.texts().single())
        assertEquals(listOf(TerminalSpan(1, 2, TerminalStyle(foreground = TerminalPalette.ansi(1)))), spans)

        val lone = emulator()
        lone.feed("x\u001b")
        lone.feed("[Ky")
        assertEquals(listOf("xy"), lone.texts())
    }

    @Test
    fun `sgr sets and resets colours`() {
        val terminal = emulator()
        terminal.feed("\u001b[31mred\u001b[0m plain")

        val line = terminal.snapshot().lines.single()
        assertEquals("red plain", line.text)
        assertEquals(listOf(TerminalSpan(0, 3, TerminalStyle(foreground = TerminalPalette.ansi(1)))), line.spans)
    }

    @Test
    fun `sgr understands bright 256 colour and truecolour`() {
        val bright = emulator()
        bright.feed("\u001b[92mX")
        assertEquals(TerminalPalette.ansi(10), bright.snapshot().lines.single().spans.single().style.foreground)

        val indexed = emulator()
        indexed.feed("\u001b[38;5;196mX")
        assertEquals(0xFFFF0000.toInt(), indexed.snapshot().lines.single().spans.single().style.foreground)

        val gray = emulator()
        gray.feed("\u001b[48;5;232mX")
        assertEquals(0xFF080808.toInt(), gray.snapshot().lines.single().spans.single().style.background)

        val truecolour = emulator()
        truecolour.feed("\u001b[38;2;10;20;30;48;2;1;2;3mX")
        val style = truecolour.snapshot().lines.single().spans.single().style
        assertEquals(0xFF0A141E.toInt(), style.foreground)
        assertEquals(0xFF010203.toInt(), style.background)
    }

    @Test
    fun `sgr text attributes can be turned on and off`() {
        val terminal = emulator()
        terminal.feed("\u001b[1;3;4;7mA\u001b[22;23;24;27mB")

        val spans = terminal.snapshot().lines.single().spans
        assertEquals(
            listOf(TerminalSpan(0, 1, TerminalStyle(bold = true, italic = true, underline = true, inverse = true))),
            spans,
        )
    }

    @Test
    fun `default colour codes restore the terminal defaults`() {
        val terminal = emulator()
        terminal.feed("\u001b[31;42mA\u001b[39;49mB")

        val spans = terminal.snapshot().lines.single().spans
        assertEquals(
            listOf(
                TerminalSpan(
                    0,
                    1,
                    TerminalStyle(foreground = TerminalPalette.ansi(1), background = TerminalPalette.ansi(2)),
                ),
            ),
            spans,
        )
    }

    @Test
    fun `a style stays active across lines until it is reset`() {
        val terminal = emulator()
        terminal.feed("\u001b[32mfoo\r\nbar")

        val lines = terminal.snapshot().lines
        val green = TerminalStyle(foreground = TerminalPalette.ansi(2))
        assertEquals(listOf(TerminalSpan(0, 3, green)), lines[0].spans)
        assertEquals(listOf(TerminalSpan(0, 3, green)), lines[1].spans)
    }

    @Test
    fun `adjacent cells with the same style merge into one span`() {
        val terminal = emulator()
        terminal.feed("\u001b[31mab\u001b[31mcd\u001b[32mef")

        val spans = terminal.snapshot().lines.single().spans
        assertEquals(2, spans.size)
        assertEquals(0, spans[0].start)
        assertEquals(4, spans[0].end)
        assertEquals(4, spans[1].start)
        assertEquals(6, spans[1].end)
    }

    @Test
    fun `plain text has no spans`() {
        val terminal = emulator()
        terminal.feed("plain")
        assertTrue(terminal.snapshot().lines.single().spans.isEmpty())
    }

    @Test
    fun `reports the cursor position`() {
        val terminal = emulator()
        terminal.feed("ab\r\ncde")

        val snapshot = terminal.snapshot()
        assertEquals(1, snapshot.cursorRow)
        assertEquals(3, snapshot.cursorColumn)
    }

    @Test
    fun `an earlier snapshot is not changed by later output`() {
        val terminal = emulator()
        terminal.feed("first")
        val before = terminal.snapshot()

        terminal.feed("\r\nsecond")

        assertEquals(listOf("first"), before.lines.map { it.text })
        assertEquals(listOf("first", "second"), terminal.texts())
    }

    @Test
    fun `revision only moves when the screen changes`() {
        val terminal = emulator()
        val initial = terminal.snapshot().revision
        assertEquals(initial, terminal.snapshot().revision)

        terminal.feed("x")
        assertTrue(terminal.snapshot().revision > initial)
    }

    @Test
    fun `clear empties the buffer but keeps the current style`() {
        val terminal = emulator()
        terminal.feed("\u001b[31mold\r\nlines")
        terminal.clear()
        terminal.feed("new")

        val line = terminal.snapshot().lines.single()
        assertEquals("new", line.text)
        assertFalse(line.spans.isEmpty())
    }

    @Test
    fun `non ascii text is kept as is`() {
        val terminal = emulator()
        terminal.feed("日本語 ok")
        assertEquals(listOf("日本語 ok"), terminal.texts())
    }

    @Test
    fun `absurd numeric parameters are clamped instead of overflowing`() {
        // A cat of any file can put these on the screen; none of them may throw.
        val counts = listOf("2147483647", "2147483648", "99999999999999999999", "-1", "0", "9999999")
        val finals = listOf("@", "P", "X", "A", "B", "C", "D", "E", "F", "G", "H", "J", "K", "d", "m")
        for (count in counts) {
            for (final in finals) {
                val terminal = emulator()
                terminal.feed("abc\r\u001b[$count$final")
                terminal.feed("Z")
                assertTrue("ESC[$count$final", terminal.snapshot().lines.isNotEmpty())
            }
        }
    }

    @Test
    fun `a negative parameter is ignored rather than read as zero`() {
        val terminal = emulator()
        terminal.feed("\u001b[31ma\u001b[-1mb")

        // `ESC[0m` would have reset the colour; an invalid parameter must leave the style alone.
        val spans = terminal.snapshot().lines.single().spans
        assertEquals(listOf(TerminalSpan(0, 2, TerminalStyle(foreground = TerminalPalette.ansi(1)))), spans)
    }

    @Test
    fun `an insert of a huge count only blanks the line`() {
        val terminal = emulator(columns = 10)
        terminal.feed("abc\r\u001b[2147483647@Z")

        val text = terminal.texts().single()
        assertEquals(10, text.length)
        assertEquals('Z', text[0])
    }

    @Test
    fun `a cancel byte abandons a sequence in progress`() {
        val csi = emulator()
        csi.feed("a\u001b[31\u0018b")
        assertEquals(listOf("ab"), csi.texts())
        assertTrue(csi.snapshot().lines.single().spans.isEmpty())

        val string = emulator()
        string.feed("\u001b]0;ti\u001ahi")
        assertEquals(listOf("hi"), string.texts())
    }

    @Test
    fun `the number of distinct styles is bounded`() {
        val terminal = emulator()
        // Every character a different truecolour: more styles than the table is willing to keep.
        for (index in 0 until 5_000) {
            terminal.feed("\u001b[38;2;${index % 256};${index / 256};0mx")
        }

        val lines = terminal.snapshot().lines
        assertEquals(5_000, lines.sumOf { it.text.length })
        // Past the bound a style degrades to the default instead of growing the table.
        assertTrue(lines.last().spans.isEmpty())
    }
}
