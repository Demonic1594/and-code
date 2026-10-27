package com.yugahashimoto.andcode.feature.workspace.editor

/** What a stretch of code is, for colouring. */
enum class TokenKind { KEYWORD, BUILTIN, TYPE, STRING, COMMENT, NUMBER, FUNCTION, VARIABLE, ANNOTATION, TAG, ATTRIBUTE, PROPERTY, HEADING, PUNCTUATION }

data class Token(
    val start: Int,
    /** Exclusive. */
    val end: Int,
    val kind: TokenKind,
)

/**
 * A small single-pass lexer: enough to colour keywords, strings, comments, numbers, calls, types
 * and markup the way an editor does, without a grammar per language. It reads the whole text, so a
 * string or comment that spans lines is coloured to its end, unlike a line-by-line regex.
 *
 * Plain Kotlin and allocation-light, since the editor runs it on every keystroke.
 */
object SyntaxHighlighter {
    /** Past this size a file is shown uncoloured: lexing it on each keystroke would make typing lag. */
    const val MAX_HIGHLIGHT_CHARS = 250_000

    fun tokenize(
        text: String,
        language: CodeLanguage,
    ): List<Token> {
        if (text.length > MAX_HIGHLIGHT_CHARS) return emptyList()
        return when (language.style) {
            CodeLanguage.Style.PLAIN -> emptyList()
            CodeLanguage.Style.MARKUP -> Markup(text, language).run()
            CodeLanguage.Style.MARKDOWN -> markdown(text)
            else -> Code(text, language).run()
        }
    }

    private class Code(
        val text: String,
        val lang: CodeLanguage,
    ) {
        val out = ArrayList<Token>()
        var i = 0
        val n = text.length

        /**
         * How deep in `{}` the lexer is: in CSS, `a:hover` outside braces is a selector and
         * `color: red` inside them a declaration, told apart in one pass.
         */
        var braceDepth = 0

        fun run(): List<Token> {
            while (i < n) {
                val c = text[i]
                when {
                    lang.blockComment != null && text.startsWith(lang.blockComment.first, i) -> {
                        val end = text.indexOf(lang.blockComment.second, i + lang.blockComment.first.length)
                        emit(i, if (end < 0) n else end + lang.blockComment.second.length, TokenKind.COMMENT)
                    }
                    lang.lineComment != null &&
                        text.startsWith(
                            lang.lineComment,
                            i,
                        ) && lineCommentStarts() -> emit(i, lineEnd(i), TokenKind.COMMENT)
                    lang.tripleQuotes && (text.startsWith("\"\"\"", i) || text.startsWith("'''", i)) -> {
                        val quote = text.substring(i, i + 3)
                        val end = text.indexOf(quote, i + 3)
                        emit(i, if (end < 0) n else end + 3, TokenKind.STRING)
                    }
                    c in lang.quotes -> string(c)
                    c.isDigit() -> number()
                    c == '@' && lang.annotations && i + 1 < n && text[i + 1].isJavaIdentifierStart() ->
                        emit(
                            i,
                            wordEnd(i + 1),
                            TokenKind.ANNOTATION,
                        )
                    c == '$' && lang.dollarVariables -> dollar()
                    c == '#' && lang.style == CodeLanguage.Style.CSS && i + 1 < n && text[i + 1].isLetterOrDigit() -> {
                        val end = wordEnd(i + 1)
                        emit(
                            i,
                            end,
                            if (text.substring(
                                    i + 1,
                                    end,
                                ).all { it.isLetterOrDigit() && it.lowercaseChar() in HEX }
                            ) {
                                TokenKind.NUMBER
                            } else {
                                TokenKind.TYPE
                            },
                        )
                    }
                    c.isJavaIdentifierStart() || (c == '-' && lang.style == CodeLanguage.Style.CSS && i + 1 < n && text[i + 1].isLetter()) -> word()
                    c in BRACKETS -> {
                        if (c == '{') {
                            braceDepth++
                        } else if (c == '}') {
                            braceDepth = (braceDepth - 1).coerceAtLeast(0)
                        }
                        emit(i, i + 1, TokenKind.PUNCTUATION)
                    }
                    else -> i++
                }
            }
            return out
        }

        // In a shell `#` starts a comment only at a word boundary: `${#x}` and `a#b` are not comments.
        // Python, Ruby and TOML have no such rule.
        fun lineCommentStarts(): Boolean =
            !lang.dollarVariables || lang.lineComment != "#" || i == 0 || text[i - 1].isWhitespace() || text[i - 1] == ';'

        fun emit(
            start: Int,
            end: Int,
            kind: TokenKind,
        ) {
            if (end > start) out += Token(start, end, kind)
            i = maxOf(end, start + 1)
        }

        fun lineEnd(from: Int): Int = text.indexOf('\n', from).let { if (it < 0) n else it }

        fun wordEnd(from: Int): Int {
            var j = from
            while (j < n && (text[j].isJavaIdentifierPart() || (lang.style == CodeLanguage.Style.CSS && text[j] == '-'))) j++
            return j
        }

        fun string(quote: Char) {
            val start = i
            var j = i + 1
            // A quote other than a backtick ends at the line's end if it is never closed, so one
            // stray apostrophe does not colour the rest of the file.
            while (j < n && text[j] != quote && (quote == '`' || text[j] != '\n')) {
                j += if (text[j] == '\\' && j + 1 < n) 2 else 1
            }
            val end = if (j < n && text[j] == quote) j + 1 else j
            val kind = if (lang.style == CodeLanguage.Style.JSON && isKey(end)) TokenKind.PROPERTY else TokenKind.STRING
            emit(start, end, kind)
        }

        fun isKey(end: Int): Boolean {
            var j = end
            while (j < n && (text[j] == ' ' || text[j] == '\t')) j++
            return j < n && text[j] == ':'
        }

        fun number() {
            val start = i
            var j = i
            if (text.startsWith("0x", j, ignoreCase = true)) {
                j += 2
                while (j < n && (text[j].isLetterOrDigit() || text[j] == '_')) j++
            } else {
                while (j < n && (text[j].isDigit() || text[j] == '_' || text[j] == '.' && j + 1 < n && text[j + 1].isDigit())) j++
                if (j < n && (text[j] == 'e' || text[j] == 'E') && j + 1 < n && (text[j + 1].isDigit() || text[j + 1] == '-')) {
                    j += 2
                    while (j < n && text[j].isDigit()) j++
                }
                // Suffixes and CSS units: 10L, 1.5f, 12px, 50%.
                while (j < n && (text[j].isLetter() || text[j] == '%')) j++
            }
            // A digit inside a word (`x1`) is part of the word, handled by word().
            if (start > 0 && text[start - 1].isJavaIdentifierPart()) {
                i = j
                return
            }
            emit(start, j, TokenKind.NUMBER)
        }

        fun dollar() {
            val start = i
            if (i + 1 < n && text[i + 1] == '{') {
                val end = text.indexOf('}', i + 2)
                if (end in 0 until lineEnd(i)) return emit(start, end + 1, TokenKind.VARIABLE)
            }
            var j = i + 1
            while (j < n && (text[j].isLetterOrDigit() || text[j] == '_')) j++
            if (j == i + 1 && j < n && text[j] in "?#@!*0123456789") j++
            emit(start, j, TokenKind.VARIABLE)
        }

        fun word() {
            val start = i
            val end = wordEnd(i + 1)
            val word = text.substring(start, end)
            val key = if (lang.caseInsensitive) word.lowercase() else word
            var after = end
            while (after < n && text[after] == ' ') after++
            val kind =
                when {
                    lang.style == CodeLanguage.Style.CSS && after < n && text[after] == ':' && braceDepth > 0 -> TokenKind.PROPERTY
                    lang.style == CodeLanguage.Style.JSON && after < n && text[after] == ':' && lineStartsWith(start) -> TokenKind.PROPERTY
                    key in lang.keywords -> TokenKind.KEYWORD
                    key in lang.builtins -> TokenKind.BUILTIN
                    after < n && text[after] == '(' -> TokenKind.FUNCTION
                    word[0].isUpperCase() && word.length > 1 && word.any { it.isLowerCase() } -> TokenKind.TYPE
                    else -> null
                }
            if (kind == null) i = end else emit(start, end, kind)
        }

        // A YAML key is the first thing on its line (after indent or a list dash).
        fun lineStartsWith(at: Int): Boolean {
            var j = at - 1
            while (j >= 0 && (text[j] == ' ' || text[j] == '\t' || text[j] == '-')) j--
            return j < 0 || text[j] == '\n' || text[j] == '{' || text[j] == ','
        }
    }

    private class Markup(
        val text: String,
        val lang: CodeLanguage,
    ) {
        val out = ArrayList<Token>()

        fun run(): List<Token> {
            val n = text.length
            var i = 0
            while (i < n) {
                when {
                    text.startsWith("<!--", i) -> {
                        val end = text.indexOf("-->", i + 4).let { if (it < 0) n else it + 3 }
                        out += Token(i, end, TokenKind.COMMENT)
                        i = end
                    }
                    text[i] == '<' && i + 1 < n && (text[i + 1].isLetter() || text[i + 1] == '/' || text[i + 1] == '!' || text[i + 1] == '?') ->
                        i =
                            tag(
                                i,
                            )
                    text[i] == '&' -> {
                        // An entity is short; looking further would make a page of bare `&`s quadratic.
                        var end = -1
                        for (j in i + 2..minOf(n - 1, i + 10)) {
                            if (text[j] == ';') {
                                end = j
                                break
                            }
                        }
                        if (end in (i + 2)..(i + 10)) {
                            out += Token(i, end + 1, TokenKind.NUMBER)
                            i = end + 1
                        } else {
                            i++
                        }
                    }
                    else -> i++
                }
            }
            return out
        }

        fun tag(open: Int): Int {
            val n = text.length
            var j = open + 1
            while (j < n && text[j] in "/!?") j++
            val nameStart = j
            while (j < n && (text[j].isLetterOrDigit() || text[j] in "-:._")) j++
            out += Token(open, j, TokenKind.TAG)
            val name = text.substring(nameStart, j).lowercase()
            while (j < n && text[j] != '>') {
                val c = text[j]
                when {
                    c == '"' || c == '\'' -> {
                        val end = text.indexOf(c, j + 1).let { if (it < 0) n else it + 1 }
                        out += Token(j, end, TokenKind.STRING)
                        j = end
                    }
                    c.isLetter() -> {
                        val start = j
                        while (j < n && (text[j].isLetterOrDigit() || text[j] in "-:_@.")) j++
                        out += Token(start, j, TokenKind.ATTRIBUTE)
                    }
                    else -> j++
                }
            }
            if (j < n) {
                out += Token(j, j + 1, TokenKind.TAG)
                j++
            }
            // The inside of <script> and <style> is code, not markup.
            if ((name == "script" || name == "style") && text[open + 1] != '/') {
                val close = text.indexOf("</$name", j, ignoreCase = true).let { if (it < 0) n else it }
                val inner = if (name == "script") CodeLanguage.JAVASCRIPT else CodeLanguage.CSS
                Code(text.substring(j, close), inner).run().forEach { out += Token(it.start + j, it.end + j, it.kind) }
                return close
            }
            return j
        }
    }

    private fun markdown(text: String): List<Token> {
        val out = ArrayList<Token>()
        var lineStart = 0
        var inFence = false
        while (lineStart <= text.length) {
            val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
            val line = text.substring(lineStart, lineEnd)
            val trimmed = line.trimStart()
            when {
                trimmed.startsWith("```") || trimmed.startsWith("~~~") -> {
                    out += Token(lineStart, lineEnd, TokenKind.COMMENT)
                    inFence = !inFence
                }
                inFence -> out += Token(lineStart, lineEnd, TokenKind.STRING)
                trimmed.startsWith("#") -> out += Token(lineStart, lineEnd, TokenKind.HEADING)
                trimmed.startsWith(">") -> out += Token(lineStart, lineEnd, TokenKind.COMMENT)
                else -> {
                    val bullet = BULLET.find(line)
                    if (bullet != null) {
                        out +=
                            Token(
                                lineStart + bullet.groups[1]!!.range.first,
                                lineStart + bullet.groups[1]!!.range.last + 1,
                                TokenKind.KEYWORD,
                            )
                    }
                    INLINE.findAll(line).forEach { match ->
                        val kind =
                            when {
                                match.value.startsWith("`") -> TokenKind.STRING
                                match.value.startsWith("[") || match.value.startsWith("!") -> TokenKind.FUNCTION
                                else -> TokenKind.TYPE
                            }
                        out += Token(lineStart + match.range.first, lineStart + match.range.last + 1, kind)
                    }
                }
            }
            if (lineEnd == text.length) break
            lineStart = lineEnd + 1
        }
        return out
    }

    private const val HEX = "0123456789abcdef"
    private const val BRACKETS = "{}[]()"
    private val BULLET = Regex("^\\s*([-*+]|\\d+[.)])\\s")
    private val INLINE = Regex("`[^`\\n]+`|!?\\[[^\\]\\n]*]\\([^)\\n]*\\)|\\*\\*[^*\\n]+\\*\\*|__[^_\\n]+__")
}
