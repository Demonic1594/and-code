package com.yugahashimoto.andcode.runtime.local

/**
 * The words of a command line as a shell would split them: on whitespace outside quotes, with single
 * and double quotes grouping and a backslash escaping the next character (not inside single quotes).
 * For a command the user typed into a form, which an agent's config then needs as a program and its
 * arguments. Variables, globs and operators are not expanded; they stay literal text. A quoted empty
 * argument (`''`) is kept, as a shell keeps it; whitespace alone gives no words.
 */
internal fun splitCommandLine(command: String): List<String> {
    val parts = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    var escaped = false

    // Whether the word being read opened a quote, so an empty one still counts as a word.
    var quoted = false

    fun finishPart() {
        if (current.isNotEmpty() || quoted) {
            parts += current.toString()
            current.clear()
            quoted = false
        }
    }

    command.trim().forEach { character ->
        when {
            escaped -> {
                current.append(character)
                escaped = false
            }
            character == '\\' && quote != '\'' -> escaped = true
            quote != null && character == quote -> quote = null
            quote == null && (character == '\'' || character == '"') -> {
                quote = character
                quoted = true
            }
            quote == null && character.isWhitespace() -> finishPart()
            else -> current.append(character)
        }
    }
    if (escaped) current.append('\\')
    finishPart()
    return parts
}

/**
 * [words] as one line that [splitCommandLine] reads back into the same words: a plain word as it is,
 * any other (empty, or with whitespace, a quote or a backslash) in single quotes, with each single
 * quote in it written `'\''`.
 */
internal fun joinCommandLine(words: List<String>): String =
    words.joinToString(" ") { word ->
        // Plain: nothing in it that the splitter would read as a quote, an escape or a break.
        if (word.isNotEmpty() && word.none { it.isWhitespace() || it in "'\"\\" }) {
            word
        } else {
            "'" + word.replace("'", "'\\''") + "'"
        }
    }
