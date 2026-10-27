package com.yugahashimoto.andcode.feature.terminal

import kotlin.math.max
import kotlin.math.min

/**
 * A small VT100/xterm interpreter that turns a shell's byte stream into styled lines.
 *
 * It is deliberately line oriented: there is one growing list of lines (the scrollback) and a
 * cursor inside it. That is enough for everything a shell session does - prompts, line editing,
 * colours, progress bars that rewrite a line, `clear` - without a fixed-size screen. What it does
 * not do is full-screen programs: there is no alternate screen, scroll region or mouse support, so
 * `vim` or `htop` will not render. Sequences it does not know are consumed and ignored rather than
 * printed, so they never corrupt the text.
 *
 * Not thread-safe; callers serialise [feed], [clear] and [snapshot].
 *
 * @param columns the width the program was told about; text wraps there like on a real terminal
 * @param rows the height the program was told about; only absolute cursor addressing uses it
 * @param maxLines how many lines of scrollback to keep; the oldest are dropped first
 */
class AnsiTerminalEmulator(
    private val columns: Int = DEFAULT_COLUMNS,
    private val rows: Int = DEFAULT_ROWS,
    private val maxLines: Int = DEFAULT_MAX_LINES,
) {
    init {
        require(columns > 0) { "columns must be positive" }
        require(rows > 0) { "rows must be positive" }
        require(maxLines > 0) { "maxLines must be positive" }
    }

    private enum class Mode { GROUND, ESCAPE, CSI, STRING, STRING_ESCAPE, SKIP_ONE }

    private val lines = ArrayDeque<Row>().apply { addLast(Row()) }
    private val styleTable = arrayListOf(TerminalStyle())
    private val styleIds = hashMapOf(TerminalStyle() to DEFAULT_STYLE)

    private var mode = Mode.GROUND
    private val csiBuffer = StringBuilder()

    private var cursorRow = 0
    private var cursorColumn = 0

    /** True after a character landed in the last column: the wrap happens on the next one. */
    private var wrapPending = false
    private var savedRow = 0
    private var savedColumn = 0

    private var style = TerminalStyle()
    private var styleId = DEFAULT_STYLE

    private var revision = 0L
    private var cachedSnapshot: TerminalSnapshot? = null

    fun feed(chunk: String) {
        if (chunk.isEmpty()) return
        chunk.forEach(::process)
        revision++
    }

    /** Empties the scrollback and homes the cursor; the current text style is kept. */
    fun clear() {
        lines.clear()
        lines.addLast(Row())
        cursorRow = 0
        cursorColumn = 0
        savedRow = 0
        savedColumn = 0
        wrapPending = false
        revision++
    }

    fun snapshot(): TerminalSnapshot {
        cachedSnapshot?.takeIf { it.revision == revision }?.let { return it }
        return TerminalSnapshot(
            lines = lines.map { it.styled(styleTable) },
            cursorRow = cursorRow,
            cursorColumn = cursorColumn,
            revision = revision,
        ).also { cachedSnapshot = it }
    }

    private fun process(ch: Char) {
        when (mode) {
            Mode.GROUND -> ground(ch)
            Mode.ESCAPE -> escape(ch)
            Mode.CSI -> csi(ch)
            Mode.STRING -> string(ch)
            Mode.STRING_ESCAPE -> stringEscape(ch)
            Mode.SKIP_ONE -> mode = Mode.GROUND
        }
    }

    private fun ground(ch: Char) {
        when {
            ch == ESC -> mode = Mode.ESCAPE
            ch == '\r' -> carriageReturn()
            ch == '\n' || ch == VERTICAL_TAB || ch == FORM_FEED -> lineFeed()
            ch == '\b' -> moveColumn(cursorColumn - 1)
            ch == '\t' -> moveColumn(min(((cursorColumn / TAB_WIDTH) + 1) * TAB_WIDTH, columns - 1))
            ch < ' ' || ch == DELETE -> Unit
            else -> print(ch)
        }
    }

    private fun escape(ch: Char) {
        mode = Mode.GROUND
        when (ch) {
            '[' -> {
                csiBuffer.setLength(0)
                mode = Mode.CSI
            }
            ']', 'P', 'X', '^', '_' -> mode = Mode.STRING
            '(', ')', '*', '+', '#' -> mode = Mode.SKIP_ONE
            '7' -> saveCursor()
            '8' -> restoreCursor()
            'D' -> lineFeed()
            'E' -> {
                carriageReturn()
                lineFeed()
            }
            'M' -> moveRow(cursorRow - 1)
            'c' -> reset()
            ESC -> mode = Mode.ESCAPE
            else -> Unit
        }
    }

    private fun csi(ch: Char) {
        when {
            ch == ESC -> mode = Mode.ESCAPE
            ch == CAN || ch == SUB -> mode = Mode.GROUND
            ch in '@'..'~' -> {
                mode = Mode.GROUND
                dispatchCsi(ch, csiBuffer.toString())
            }
            csiBuffer.length >= MAX_CSI_LENGTH -> mode = Mode.GROUND
            else -> csiBuffer.append(ch)
        }
    }

    /** Operating-system and device-control strings (window titles and the like) carry no text. */
    private fun string(ch: Char) {
        when (ch) {
            BEL, CAN, SUB -> mode = Mode.GROUND
            ESC -> mode = Mode.STRING_ESCAPE
        }
    }

    private fun stringEscape(ch: Char) {
        if (ch == '\\') {
            mode = Mode.GROUND
        } else {
            mode = Mode.GROUND
            escape(ch)
        }
    }

    private fun dispatchCsi(
        command: Char,
        raw: String,
    ) {
        // Private modes (`?25l`, `?2004h`, `?1049h`...) switch features this emulator does not have.
        if (raw.isNotEmpty() && raw[0] in "?<=>") return
        // The stream is untrusted (a `cat` of any file will do): an absurd count must clamp, not
        // overflow the arithmetic below. Digits beyond Int already parse as "absent".
        val params = raw.split(';', ':').map { it.toIntOrNull()?.coerceIn(0, MAX_PARAMETER) }
        val first = params.firstOrNull()
        val count = max(1, first ?: 1)
        when (command) {
            'A' -> moveRow(cursorRow - count)
            'B' -> moveRow(cursorRow + count)
            'C' -> moveColumn(cursorColumn + count)
            'D' -> moveColumn(cursorColumn - count)
            'E' -> {
                moveRow(cursorRow + count)
                moveColumn(0)
            }
            'F' -> {
                moveRow(cursorRow - count)
                moveColumn(0)
            }
            'G' -> moveColumn(count - 1)
            'H', 'f' -> position(row = max(1, first ?: 1), column = max(1, params.getOrNull(1) ?: 1))
            'd' -> position(row = count, column = cursorColumn + 1)
            'J' -> eraseDisplay(first ?: 0)
            'K' -> eraseLine(first ?: 0)
            'P' -> current().delete(cursorColumn, count)
            '@' -> current().insert(cursorColumn, count, columns)
            'X' -> current().erase(cursorColumn, cursorColumn + count - 1)
            'm' -> sgr(params)
            's' -> saveCursor()
            'u' -> restoreCursor()
            else -> Unit
        }
    }

    private fun print(ch: Char) {
        if (wrapPending) {
            carriageReturn()
            lineFeed()
        }
        current().write(cursorColumn, ch, styleId)
        if (cursorColumn >= columns - 1) {
            wrapPending = true
        } else {
            cursorColumn++
        }
    }

    private fun carriageReturn() {
        cursorColumn = 0
        wrapPending = false
    }

    private fun lineFeed() {
        wrapPending = false
        if (cursorRow >= lines.size - 1) {
            lines.addLast(Row())
            cursorRow = lines.size - 1
            trimScrollback()
        } else {
            cursorRow++
        }
    }

    private fun trimScrollback() {
        while (lines.size > maxLines) {
            lines.removeFirst()
            cursorRow = max(0, cursorRow - 1)
            savedRow = max(0, savedRow - 1)
        }
    }

    private fun moveColumn(target: Int) {
        wrapPending = false
        cursorColumn = target.coerceIn(0, columns - 1)
    }

    private fun moveRow(target: Int) {
        wrapPending = false
        cursorRow = target.coerceIn(0, lines.size - 1)
    }

    /** Addresses the last [rows] lines, which is what an "absolute" position means with scrollback. */
    private fun position(
        row: Int,
        column: Int,
    ) {
        val windowTop = max(0, lines.size - rows)
        val target = windowTop + min(row, rows) - 1
        while (lines.size <= target) lines.addLast(Row())
        trimScrollback()
        moveRow(min(target, lines.size - 1))
        moveColumn(column - 1)
    }

    private fun eraseLine(mode: Int) {
        val row = current()
        when (mode) {
            0 -> row.truncate(cursorColumn)
            1 -> row.erase(0, cursorColumn)
            else -> row.truncate(0)
        }
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> {
                current().truncate(cursorColumn)
                while (lines.size > cursorRow + 1) lines.removeLast()
            }
            1 -> {
                val windowTop = max(0, lines.size - rows)
                for (index in windowTop until cursorRow) lines[index].truncate(0)
                current().erase(0, cursorColumn)
            }
            else -> {
                lines.clear()
                lines.addLast(Row())
                cursorRow = 0
                savedRow = 0
                wrapPending = false
            }
        }
    }

    private fun saveCursor() {
        savedRow = cursorRow
        savedColumn = cursorColumn
    }

    private fun restoreCursor() {
        moveRow(savedRow)
        moveColumn(savedColumn)
    }

    private fun reset() {
        clear()
        style = TerminalStyle()
        styleId = DEFAULT_STYLE
    }

    private fun current(): Row = lines[cursorRow]

    private fun sgr(params: List<Int?>) {
        var next = style
        var index = 0
        val codes = params.ifEmpty { listOf(0) }
        while (index < codes.size) {
            val code = codes[index] ?: 0
            index++
            when (code) {
                0 -> next = TerminalStyle()
                1 -> next = next.copy(bold = true)
                3 -> next = next.copy(italic = true)
                4 -> next = next.copy(underline = true)
                7 -> next = next.copy(inverse = true)
                22 -> next = next.copy(bold = false)
                23 -> next = next.copy(italic = false)
                24 -> next = next.copy(underline = false)
                27 -> next = next.copy(inverse = false)
                in 30..37 -> next = next.copy(foreground = TerminalPalette.ansi(code - 30))
                in 40..47 -> next = next.copy(background = TerminalPalette.ansi(code - 40))
                in 90..97 -> next = next.copy(foreground = TerminalPalette.ansi(code - 90 + 8))
                in 100..107 -> next = next.copy(background = TerminalPalette.ansi(code - 100 + 8))
                39 -> next = next.copy(foreground = null)
                49 -> next = next.copy(background = null)
                38, 48 -> {
                    val color = extendedColor(codes, index)
                    index = color.nextIndex
                    if (color.value != null) {
                        next = if (code == 38) next.copy(foreground = color.value) else next.copy(background = color.value)
                    }
                }
            }
        }
        style = next
        styleId = styleIds[next] ?: internStyle(next)
    }

    /**
     * Styles are interned so a cell costs one int. A stream that never repeats a colour (a truecolour
     * gradient, or random bytes that happen to spell SGR codes) would grow the table without end, so
     * past [MAX_STYLES] new styles are drawn in the default style instead.
     */
    private fun internStyle(next: TerminalStyle): Int {
        if (styleTable.size >= MAX_STYLES) return DEFAULT_STYLE
        styleTable.add(next)
        return styleTable.lastIndex.also { styleIds[next] = it }
    }

    private class ExtendedColor(val value: Int?, val nextIndex: Int)

    /** Reads the `5;n` or `2;r;g;b` that follows a 38/48 code, tolerating a truncated sequence. */
    private fun extendedColor(
        codes: List<Int?>,
        start: Int,
    ): ExtendedColor =
        when (codes.getOrNull(start)) {
            EXTENDED_INDEXED ->
                ExtendedColor(
                    value = codes.getOrNull(start + 1)?.let { TerminalPalette.indexed(it.coerceIn(0, 255)) },
                    nextIndex = start + 2,
                )
            EXTENDED_RGB ->
                ExtendedColor(
                    value =
                        if (start + 3 < codes.size) {
                            TerminalPalette.rgb(codes[start + 1] ?: 0, codes[start + 2] ?: 0, codes[start + 3] ?: 0)
                        } else {
                            null
                        },
                    nextIndex = start + 4,
                )
            else -> ExtendedColor(value = null, nextIndex = start)
        }

    /** One line of cells: characters and, per character, an index into the style table. */
    private class Row {
        private var chars = CharArray(INITIAL_CAPACITY)
        private var styles = IntArray(INITIAL_CAPACITY)
        private var length = 0
        private var cached: StyledLine? = null

        fun write(
            column: Int,
            ch: Char,
            styleId: Int,
        ) {
            ensureCapacity(column + 1)
            if (column > length) fill(length, column, ' ', DEFAULT_STYLE)
            chars[column] = ch
            styles[column] = styleId
            if (column >= length) length = column + 1
            cached = null
        }

        fun truncate(column: Int) {
            if (column < length) {
                length = column
                cached = null
            }
        }

        /** Blanks `[from, toInclusive]`; when that reaches the end of the text the blanks are dropped. */
        fun erase(
            from: Int,
            toInclusive: Int,
        ) {
            if (from >= length) return
            val end = min(toInclusive, length - 1)
            fill(from, end + 1, ' ', DEFAULT_STYLE)
            if (end >= length - 1) length = from
            cached = null
        }

        fun delete(
            column: Int,
            count: Int,
        ) {
            if (column >= length) return
            val removed = min(count, length - column)
            System.arraycopy(chars, column + removed, chars, column, length - column - removed)
            System.arraycopy(styles, column + removed, styles, column, length - column - removed)
            length -= removed
            cached = null
        }

        fun insert(
            column: Int,
            count: Int,
            maxLength: Int,
        ) {
            if (column >= length) return
            val newLength = min(length + count, maxLength)
            ensureCapacity(newLength)
            val moved = newLength - (column + count)
            if (moved > 0) {
                System.arraycopy(chars, column, chars, column + count, moved)
                System.arraycopy(styles, column, styles, column + count, moved)
            }
            fill(column, min(column + count, newLength), ' ', DEFAULT_STYLE)
            length = newLength
            cached = null
        }

        fun styled(table: List<TerminalStyle>): StyledLine = cached ?: build(table).also { cached = it }

        private fun build(table: List<TerminalStyle>): StyledLine {
            val spans = ArrayList<TerminalSpan>()
            var start = 0
            while (start < length) {
                val id = styles[start]
                var end = start + 1
                while (end < length && styles[end] == id) end++
                if (id != DEFAULT_STYLE) spans += TerminalSpan(start, end, table[id])
                start = end
            }
            return StyledLine(String(chars, 0, length), spans)
        }

        private fun fill(
            from: Int,
            until: Int,
            ch: Char,
            styleId: Int,
        ) {
            for (index in from until until) {
                chars[index] = ch
                styles[index] = styleId
            }
        }

        private fun ensureCapacity(needed: Int) {
            if (needed <= chars.size) return
            val grown = max(needed, chars.size * 2)
            chars = chars.copyOf(grown)
            styles = styles.copyOf(grown)
        }

        private companion object {
            const val INITIAL_CAPACITY = 16
        }
    }

    companion object {
        const val DEFAULT_COLUMNS = 80
        const val DEFAULT_ROWS = 24
        const val DEFAULT_MAX_LINES = 10_000

        private const val ESC = '\u001b'
        private const val BEL = '\u0007'
        private const val CAN = '\u0018'
        private const val SUB = '\u001a'
        private const val DELETE = '\u007f'
        private const val VERTICAL_TAB = '\u000b'
        private const val FORM_FEED = '\u000c'
        private const val TAB_WIDTH = 8
        private const val MAX_CSI_LENGTH = 128
        private const val MAX_PARAMETER = 9_999
        private const val MAX_STYLES = 4_096
        private const val DEFAULT_STYLE = 0
        private const val EXTENDED_INDEXED = 5
        private const val EXTENDED_RGB = 2
    }
}
