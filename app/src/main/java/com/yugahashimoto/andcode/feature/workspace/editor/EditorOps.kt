package com.yugahashimoto.andcode.feature.workspace.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * The editing commands of the code editor, as plain functions from one [TextFieldValue] to the
 * next: insert, auto-indent, indent and outdent, comment, duplicate, delete and move lines, cursor
 * moves, and finding text. Nothing here touches Compose state, so each is tested on its own.
 */
object EditorOps {
    private const val PAIRS = "()[]{}\"\"''``"
    private const val OPENERS = "([{"
    private const val CLOSERS = ")]}"

    fun insert(
        value: TextFieldValue,
        insertion: String,
    ): TextFieldValue {
        val start = value.selection.min
        val end = value.selection.max
        val text = value.text.substring(0, start) + insertion + value.text.substring(end)
        return TextFieldValue(text, TextRange(start + insertion.length))
    }

    /** Wraps a selection in the pair, or puts the cursor between a new pair. */
    fun insertPair(
        value: TextFieldValue,
        open: Char,
    ): TextFieldValue {
        val index = PAIRS.indexOf(open)
        if (index < 0 || index % 2 == 1) return insert(value, open.toString())
        val close = PAIRS[index + 1]
        val start = value.selection.min
        val end = value.selection.max
        if (start != end) {
            val text = value.text.substring(0, start) + open + value.text.substring(start, end) + close + value.text.substring(end)
            return TextFieldValue(text, TextRange(start + 1, end + 1))
        }
        val text = value.text.substring(0, start) + open + close + value.text.substring(end)
        return TextFieldValue(text, TextRange(start + 1))
    }

    fun lineStart(
        text: String,
        offset: Int,
    ): Int = text.lastIndexOf('\n', (offset - 1).coerceAtLeast(-1)).let { if (offset == 0) 0 else it + 1 }

    fun lineEnd(
        text: String,
        offset: Int,
    ): Int = text.indexOf('\n', offset).let { if (it < 0) text.length else it }

    fun leadingWhitespace(line: String): String = line.takeWhile { it == ' ' || it == '\t' }

    /**
     * A newline that keeps the current line's indent, adds one level after an opening bracket (or
     * `:` in Python and YAML), and when the cursor sits between a bracket pair puts the closer on
     * its own line at the original indent.
     */
    fun newline(
        value: TextFieldValue,
        language: CodeLanguage,
    ): TextFieldValue {
        val text = value.text
        val cursor = value.selection.min
        val line = text.substring(lineStart(text, cursor), cursor)
        val base = leadingWhitespace(line)
        val before = line.trimEnd().lastOrNull()
        val after = text.getOrNull(value.selection.max)
        val opens = before != null && (before in OPENERS || (before == ':' && language.indentsAfterColon))
        if (!opens) return insert(value, "\n$base")
        val inner = base + language.indent
        if (after != null && before in OPENERS && CLOSERS.indexOf(after) == OPENERS.indexOf(before)) {
            val inserted = insert(value, "\n$inner\n$base")
            return inserted.copy(selection = TextRange(cursor + 1 + inner.length))
        }
        return insert(value, "\n$inner")
    }

    /**
     * Detects a single newline typed by the keyboard (the text grew by exactly `\n` at the old
     * cursor) and returns the auto-indented result instead, or null for any other edit.
     */
    fun autoIndent(
        old: TextFieldValue,
        new: TextFieldValue,
        language: CodeLanguage,
    ): TextFieldValue? {
        if (!old.selection.collapsed || new.text.length != old.text.length + 1) return null
        val at = old.selection.start
        if (new.text.getOrNull(at) != '\n' || new.selection != TextRange(at + 1)) return null
        if (new.text.substring(0, at) != old.text.substring(0, at) || new.text.substring(at + 1) != old.text.substring(at)) return null
        return newline(old, language)
    }

    /** The first and last line starts covered by the selection. */
    private fun selectedLines(value: TextFieldValue): List<Int> {
        val text = value.text
        val first = lineStart(text, value.selection.min)
        // A selection ending at the start of a line does not include that line.
        val endOffset =
            if (value.selection.max > value.selection.min && text.getOrNull(
                    value.selection.max - 1,
                ) == '\n'
            ) {
                value.selection.max - 1
            } else {
                value.selection.max
            }
        val starts = mutableListOf(first)
        var at = text.indexOf('\n', first)
        while (at in first until endOffset) {
            starts += at + 1
            at = text.indexOf('\n', at + 1)
        }
        return starts
    }

    /** Applies [edit] to every selected line, keeping the selection over the same lines. */
    private fun editLines(
        value: TextFieldValue,
        edit: (String) -> String,
    ): TextFieldValue {
        val text = value.text
        val starts = selectedLines(value)
        val from = starts.first()
        val to = lineEnd(text, starts.last())
        val lines = text.substring(from, to).split('\n')
        val edited = lines.map(edit)
        val replaced = edited.joinToString("\n")
        val newText = text.substring(0, from) + replaced + text.substring(to)
        val selection =
            if (value.selection.collapsed) {
                val shift = edited.first().length - lines.first().length
                TextRange((value.selection.start + shift).coerceIn(from, from + edited.first().length))
            } else {
                TextRange(from, from + replaced.length)
            }
        return TextFieldValue(newText, selection)
    }

    /** Tab: one indent at the cursor, or one more level on every selected line. */
    fun indent(
        value: TextFieldValue,
        language: CodeLanguage,
    ): TextFieldValue {
        if (value.selection.collapsed) return insert(value, language.indent)
        return editLines(value) { if (it.isBlank()) it else language.indent + it }
    }

    fun outdent(
        value: TextFieldValue,
        language: CodeLanguage,
    ): TextFieldValue =
        editLines(value) { line ->
            when {
                line.startsWith(language.indent) -> line.removePrefix(language.indent)
                line.startsWith("\t") -> line.removePrefix("\t")
                else -> line.trimStart(' ').let { trimmed -> line.substring(minOf(language.indent.length, line.length - trimmed.length)) }
            }
        }

    /**
     * Comments the selected lines out with the language's line comment, at their shared indent, or
     * back in when every non-blank one already is. A language with only block comments wraps them.
     */
    fun toggleComment(
        value: TextFieldValue,
        language: CodeLanguage,
    ): TextFieldValue {
        val marker = language.lineComment
        if (marker == null) {
            val (open, close) = language.blockComment ?: return value
            val starts = selectedLines(value)
            val from = starts.first()
            val to = lineEnd(value.text, starts.last())
            val inner = value.text.substring(from, to)
            val trimmed = inner.trim()
            val replaced =
                if (trimmed.length >= open.length + close.length && trimmed.startsWith(open) && trimmed.endsWith(close)) {
                    leadingWhitespace(inner) + trimmed.removePrefix(open).removeSuffix(close).trim()
                } else {
                    leadingWhitespace(inner) + "$open ${inner.trimStart()} $close"
                }
            return TextFieldValue(
                value.text.substring(0, from) + replaced + value.text.substring(to),
                TextRange(from, from + replaced.length),
            )
        }
        val starts = selectedLines(value)
        val lines = starts.map { value.text.substring(it, lineEnd(value.text, it)) }
        val nonBlank = lines.filter { it.isNotBlank() }
        if (nonBlank.isEmpty()) return value
        val uncomment = nonBlank.all { it.trimStart().startsWith(marker) }
        val column = nonBlank.minOf { leadingWhitespace(it).length }
        return editLines(value) { line ->
            when {
                line.isBlank() -> line
                uncomment -> {
                    val at = line.indexOf(marker)
                    val after = at + marker.length
                    line.substring(0, at) + line.substring(if (line.getOrNull(after) == ' ') after + 1 else after)
                }
                else -> line.substring(0, column) + "$marker " + line.substring(column)
            }
        }
    }

    fun duplicateLines(value: TextFieldValue): TextFieldValue {
        val starts = selectedLines(value)
        val from = starts.first()
        val to = lineEnd(value.text, starts.last())
        val block = value.text.substring(from, to)
        val text = value.text.substring(0, to) + "\n" + block + value.text.substring(to)
        val shift = block.length + 1
        return TextFieldValue(text, TextRange(value.selection.start + shift, value.selection.end + shift))
    }

    fun deleteLines(value: TextFieldValue): TextFieldValue {
        val starts = selectedLines(value)
        val from = starts.first()
        val to = lineEnd(value.text, starts.last())
        val (cutFrom, cutTo) = if (to < value.text.length) from to to + 1 else (from - 1).coerceAtLeast(0) to to
        val text = value.text.removeRange(cutFrom, cutTo)
        return TextFieldValue(text, TextRange(cutFrom.coerceAtMost(text.length)))
    }

    /** Moves the selected lines one line up or down, the selection with them. */
    fun moveLines(
        value: TextFieldValue,
        down: Boolean,
    ): TextFieldValue {
        val text = value.text
        val starts = selectedLines(value)
        val from = starts.first()
        val to = lineEnd(text, starts.last())
        val block = text.substring(from, to)
        if (down) {
            if (to >= text.length) return value
            val nextEnd = lineEnd(text, to + 1)
            val next = text.substring(to + 1, nextEnd)
            val newText = text.substring(0, from) + next + "\n" + block + text.substring(nextEnd)
            val shift = next.length + 1
            return TextFieldValue(newText, TextRange(value.selection.start + shift, value.selection.end + shift))
        }
        if (from == 0) return value
        val prevStart = lineStart(text, from - 1)
        val prev = text.substring(prevStart, from - 1)
        val newText = text.substring(0, prevStart) + block + "\n" + prev + text.substring(to)
        val shift = prev.length + 1
        return TextFieldValue(newText, TextRange(value.selection.start - shift, value.selection.end - shift))
    }

    /** Arrow keys: left and right by a character, up and down by a line keeping the column. */
    fun moveCursor(
        value: TextFieldValue,
        direction: Direction,
    ): TextFieldValue {
        val text = value.text
        val cursor = value.selection.end
        val target =
            when (direction) {
                Direction.LEFT -> if (value.selection.collapsed) cursor - 1 else value.selection.min
                Direction.RIGHT -> if (value.selection.collapsed) cursor + 1 else value.selection.max
                Direction.UP, Direction.DOWN -> {
                    val start = lineStart(text, cursor)
                    val column = cursor - start
                    if (direction == Direction.UP) {
                        if (start == 0) 0 else lineStart(text, start - 1).let { prev -> prev + minOf(column, start - 1 - prev) }
                    } else {
                        val end = lineEnd(text, cursor)
                        if (end >= text.length) text.length else (end + 1).let { next -> next + minOf(column, lineEnd(text, next) - next) }
                    }
                }
                Direction.HOME ->
                    lineStart(text, cursor).let {
                            start ->
                        start + leadingWhitespace(text.substring(start, lineEnd(text, cursor))).length
                    }
                Direction.END -> lineEnd(text, cursor)
            }
        return value.copy(selection = TextRange(target.coerceIn(0, text.length)))
    }

    enum class Direction { LEFT, RIGHT, UP, DOWN, HOME, END }

    /** The offset where 1-based [line] starts, clamped to the text. */
    fun offsetOfLine(
        text: String,
        line: Int,
    ): Int {
        var offset = 0
        repeat((line - 1).coerceAtLeast(0)) {
            val next = text.indexOf('\n', offset)
            if (next < 0) return offset
            offset = next + 1
        }
        return offset
    }

    /** 1-based line and column of [offset]. */
    fun lineAndColumn(
        text: String,
        offset: Int,
    ): Pair<Int, Int> {
        val at = offset.coerceIn(0, text.length)
        var line = 1
        for (i in 0 until at) if (text[i] == '\n') line++
        return line to at - lineStart(text, at) + 1
    }

    data class FindOptions(
        val matchCase: Boolean = false,
        val wholeWord: Boolean = false,
        val regex: Boolean = false,
    )

    /** Every match of [query]; an invalid regular expression finds nothing rather than throwing. */
    fun findAll(
        text: String,
        query: String,
        options: FindOptions,
        limit: Int = 10_000,
    ): List<IntRange> {
        if (query.isEmpty()) return emptyList()
        val pattern = patternOf(query, options) ?: return emptyList()
        return pattern.findAll(text).filter { !it.range.isEmpty() }.take(limit).map { it.range }.toList()
    }

    fun patternOf(
        query: String,
        options: FindOptions,
    ): Regex? {
        var source = if (options.regex) query else Regex.escape(query)
        if (options.wholeWord) source = "\\b(?:$source)\\b"
        return runCatching { Regex(source, if (options.matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE)) }.getOrNull()
    }

    /**
     * Replaces the match at [range], with `$1`-style groups when [FindOptions.regex] is on. The
     * pattern runs against the whole text from the match on, so lookarounds see their context.
     */
    fun replaceMatch(
        value: TextFieldValue,
        range: IntRange,
        query: String,
        replacement: String,
        options: FindOptions,
    ): TextFieldValue {
        val pattern = patternOf(query, options) ?: return value
        val matcher =
            pattern.toPattern().matcher(value.text).apply {
                region(range.first, value.text.length)
                useTransparentBounds(true)
                useAnchoringBounds(false)
            }
        if (!matcher.find() || matcher.start() != range.first || matcher.end() == matcher.start()) return value
        // StringBuffer: the StringBuilder overload of appendReplacement needs API 34.
        val out = StringBuffer()
        val ok =
            runCatching {
                matcher.appendReplacement(
                    out,
                    if (options.regex) replacement else java.util.regex.Matcher.quoteReplacement(replacement),
                )
            }
        if (ok.isFailure) return value
        val replaced = out.substring(range.first)
        val text = value.text.substring(0, range.first) + replaced + value.text.substring(matcher.end())
        return TextFieldValue(text, TextRange(range.first + replaced.length))
    }

    /**
     * Replaces every non-empty match. Empty matches (from `a*` and the like) are skipped, as
     * [findAll] skips them, so what is replaced is exactly what was counted and highlighted.
     */
    fun replaceAll(
        text: String,
        query: String,
        replacement: String,
        options: FindOptions,
    ): Pair<String, Int> {
        val pattern = patternOf(query, options) ?: return text to 0
        val matcher = pattern.toPattern().matcher(text)
        val literal = if (options.regex) replacement else java.util.regex.Matcher.quoteReplacement(replacement)
        val out = StringBuffer()
        var count = 0
        val result =
            runCatching {
                while (matcher.find()) {
                    if (matcher.end() == matcher.start()) continue
                    matcher.appendReplacement(out, literal)
                    count++
                }
                matcher.appendTail(out)
                out.toString()
            }.getOrElse { return text to 0 }
        return if (count == 0) text to 0 else result to count
    }

    /** The bracket matching the one at or just before [offset], skipping nested pairs, or null. */
    fun matchingBracket(
        text: String,
        offset: Int,
    ): Pair<Int, Int>? {
        val at =
            listOf(offset, offset - 1).firstOrNull { it in text.indices && (text[it] in OPENERS || text[it] in CLOSERS) }
                ?: return null
        val c = text[at]
        val forward = c in OPENERS
        val open = if (forward) c else OPENERS[CLOSERS.indexOf(c)]
        val close = if (forward) CLOSERS[OPENERS.indexOf(c)] else c
        var depth = 0
        var i = at
        var steps = 0
        while (i in text.indices && steps++ < MAX_BRACKET_SCAN) {
            when (text[i]) {
                open -> depth += if (forward) 1 else -1
                close -> depth += if (forward) -1 else 1
            }
            if (depth == 0) return at to i
            i += if (forward) 1 else -1
        }
        return null
    }

    private const val MAX_BRACKET_SCAN = 100_000
}

private val CodeLanguage.indentsAfterColon: Boolean get() = name == "Python" || name == "YAML"
