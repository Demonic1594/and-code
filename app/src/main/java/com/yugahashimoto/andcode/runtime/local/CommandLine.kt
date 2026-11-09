package com.yugahashimoto.andcode.runtime.local

/**
 * The words of a command line as a shell would split them: on whitespace outside quotes, with single
 * and double quotes grouping and a backslash escaping the next character (not inside single quotes).
 * For a command the user typed into a form, which an agent's config then needs as a program and its
 * arguments. Variables, globs and operators are not expanded; they stay literal text.
 */
internal fun splitCommandLine(command: String): List<String> {
    val parts = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    var escaped = false

    fun finishPart() {
        if (current.isNotEmpty()) {
            parts += current.toString()
            current.clear()
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
            quote == null && (character == '\'' || character == '"') -> quote = character
            quote == null && character.isWhitespace() -> finishPart()
            else -> current.append(character)
        }
    }
    if (escaped) current.append('\\')
    finishPart()
    return parts
}
