package com.yugahashimoto.andcode.feature.terminal

/**
 * How a run of cells is drawn. A null colour means "the terminal's default", which the screen
 * resolves against its own theme - the emulator only records what the program asked for.
 */
data class TerminalStyle(
    val foreground: Int? = null,
    val background: Int? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val inverse: Boolean = false,
)

/** A styled run inside one line: cells `[start, end)` are drawn with [style]. */
data class TerminalSpan(
    val start: Int,
    val end: Int,
    val style: TerminalStyle,
)

/** One immutable screen line. Runs in the default style have no span. */
data class StyledLine(
    val text: String,
    val spans: List<TerminalSpan> = emptyList(),
)

/**
 * Everything the screen needs to draw the terminal at one moment. Lines are immutable and shared
 * between snapshots, so keeping an old snapshot around is cheap and never changes under the reader.
 */
data class TerminalSnapshot(
    val lines: List<StyledLine>,
    val cursorRow: Int,
    val cursorColumn: Int,
    /** Moves whenever the screen may have changed, so equal revisions mean equal snapshots. */
    val revision: Long,
)

/** Keys the soft keyboard cannot type, as the bytes a terminal sends for them. */
enum class TerminalKey(val sequence: String) {
    ESCAPE("\u001b"),
    TAB("\t"),
    CTRL_C("\u0003"),
    CTRL_D("\u0004"),
    UP("\u001b[A"),
    DOWN("\u001b[B"),
    RIGHT("\u001b[C"),
    LEFT("\u001b[D"),
}

/** The 16 ANSI colours plus the xterm 256-colour extension, as opaque ARGB ints. */
object TerminalPalette {
    private val base =
        intArrayOf(
            0xFF000000.toInt(),
            0xFFCD3131.toInt(),
            0xFF0DBC79.toInt(),
            0xFFE5E510.toInt(),
            0xFF2472C8.toInt(),
            0xFFBC3FBC.toInt(),
            0xFF11A8CD.toInt(),
            0xFFE5E5E5.toInt(),
            0xFF666666.toInt(),
            0xFFF14C4C.toInt(),
            0xFF23D18B.toInt(),
            0xFFF5F543.toInt(),
            0xFF3B8EEA.toInt(),
            0xFFD670D6.toInt(),
            0xFF29B8DB.toInt(),
            0xFFFFFFFF.toInt(),
        )

    private val cubeLevels = intArrayOf(0, 95, 135, 175, 215, 255)

    /** One of the 16 ANSI colours: 0-7 normal, 8-15 bright. */
    fun ansi(index: Int): Int = base[index.coerceIn(0, base.lastIndex)]

    /** The xterm 256-colour table: the 16 ANSI colours, a 6x6x6 cube, then 24 grays. */
    fun indexed(index: Int): Int =
        when {
            index < 16 -> ansi(index)
            index < 232 -> {
                val cube = index - 16
                rgb(cubeLevels[cube / 36], cubeLevels[(cube / 6) % 6], cubeLevels[cube % 6])
            }
            else -> {
                val level = 8 + (index.coerceAtMost(255) - 232) * 10
                rgb(level, level, level)
            }
        }

    fun rgb(
        red: Int,
        green: Int,
        blue: Int,
    ): Int = (0xFF shl 24) or (red.coerceIn(0, 255) shl 16) or (green.coerceIn(0, 255) shl 8) or blue.coerceIn(0, 255)
}
