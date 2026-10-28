package com.yugahashimoto.andcode.feature.terminal

/**
 * Puts back together the lines the terminal wrapped.
 *
 * A program prints one line; the emulator breaks it at the screen width into several rows. An address
 * that crosses the break is whole in none of them, so searching row by row misses it (or reads half of
 * a port number as a port). A row that fills the width is taken to continue on the next row, which is
 * exactly what the emulator does when more text arrives after the last column.
 */
object WrappedLines {
    /**
     * The lines of [rows] that contain at least one row for which [include] is true, each as one string.
     * [columns] is the width the rows were wrapped at; with no usable width every row is its own line.
     *
     * A line whose last row is full may still be growing in output that has not arrived, so its trailing
     * digits are left out: they could be the front of a longer number. Once the line ends - the next row
     * is not full, even if only because it is empty - they are included.
     */
    fun join(
        rows: List<StyledLine>,
        columns: Int,
        include: (StyledLine) -> Boolean,
    ): List<String> {
        if (columns <= 0) return rows.filter(include).map { it.text }
        val lines = ArrayList<String>()
        var start = 0
        while (start < rows.size) {
            var end = start
            while (end + 1 < rows.size && rows[end].text.length >= columns) end++
            val group = rows.subList(start, end + 1)
            if (group.any(include)) {
                val text = group.joinToString(separator = "") { it.text }
                lines += if (rows[end].text.length >= columns) text.trimEnd { it.isDigit() } else text
            }
            start = end + 1
        }
        return lines
    }
}
