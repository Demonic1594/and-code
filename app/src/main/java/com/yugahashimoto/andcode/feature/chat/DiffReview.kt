package com.yugahashimoto.andcode.feature.chat

/** One rendered row of a unified diff. */
data class DiffRow(
    val kind: Kind,
    val text: String,
    /** Line number in the old file; null for an added line, a hunk header or file metadata. */
    val oldLine: Int?,
    /** Line number in the new file; null for a removed line, a hunk header or file metadata. */
    val newLine: Int?,
) {
    enum class Kind { META, HUNK, CONTEXT, ADDED, REMOVED }

    /** Whether a review comment can be attached to this row. */
    val commentable: Boolean
        get() = kind == Kind.CONTEXT || kind == Kind.ADDED || kind == Kind.REMOVED
}

/** A parsed file diff: its rows and how many lines it adds and removes. */
data class ParsedDiff(
    val rows: List<DiffRow>,
    val additions: Int,
    val deletions: Int,
)

private val HUNK_HEADER = Regex("""^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@.*""")

/**
 * Parses a unified diff into rows with old and new line numbers, so the review screen can show a
 * gutter and say which line a comment is about. Lines before the first hunk (`diff --git`,
 * `index`, `---`, `+++`) are metadata; a malformed hunk header leaves numbering off until the next
 * good one rather than inventing numbers.
 */
fun parseUnifiedDiff(patch: String): ParsedDiff {
    val rows = mutableListOf<DiffRow>()
    var oldLine: Int? = null
    var newLine: Int? = null
    var inHunk = false
    var additions = 0
    var deletions = 0
    patch.lines().forEach { line ->
        when {
            line.startsWith("@@") -> {
                val match = HUNK_HEADER.matchEntire(line)
                oldLine = match?.groupValues?.get(1)?.toIntOrNull()
                newLine = match?.groupValues?.get(2)?.toIntOrNull()
                inHunk = true
                rows += DiffRow(DiffRow.Kind.HUNK, line, null, null)
            }
            !inHunk -> if (line.isNotEmpty()) rows += DiffRow(DiffRow.Kind.META, line, null, null)
            line.startsWith("+") -> {
                additions++
                rows += DiffRow(DiffRow.Kind.ADDED, line.substring(1), null, newLine)
                newLine = newLine?.plus(1)
            }
            line.startsWith("-") -> {
                deletions++
                rows += DiffRow(DiffRow.Kind.REMOVED, line.substring(1), oldLine, null)
                oldLine = oldLine?.plus(1)
            }
            line.startsWith("\\") -> Unit // "\ No newline at end of file"
            else -> {
                rows += DiffRow(DiffRow.Kind.CONTEXT, line.removePrefix(" "), oldLine, newLine)
                oldLine = oldLine?.plus(1)
                newLine = newLine?.plus(1)
            }
        }
    }
    // A trailing newline in the patch reads as one empty context line; it is not part of the file.
    if (rows.lastOrNull()?.let { it.kind == DiffRow.Kind.CONTEXT && it.text.isEmpty() } == true) {
        rows.removeAt(rows.lastIndex)
    }
    return ParsedDiff(rows, additions, deletions)
}

/** A comment the user left on one line of a file's diff. */
data class ReviewComment(
    val file: String,
    val row: DiffRow,
    val text: String,
)

/**
 * The message that hands review comments back to the agent, in file and line order.
 *
 * It names each line the way a person would point at it in a code review - path, line number in
 * the new file (or the old one for a removed line), and the code itself - so the agent can find
 * the spot without guessing. The wording is English on purpose: it is a prompt to the model, not
 * text the user reads.
 */
fun reviewCommentsMessage(comments: List<ReviewComment>): String {
    // Diff order: by file, then line, with a removed line ahead of the line that replaced it.
    val ordered =
        comments.sortedWith(
            compareBy({ it.file }, { it.row.newLine ?: it.row.oldLine ?: 0 }, { it.row.kind != DiffRow.Kind.REMOVED }),
        )
    return buildString {
        append("Please address these review comments on your changes:\n")
        ordered.forEachIndexed { index, comment ->
            val row = comment.row
            val where =
                when (row.kind) {
                    DiffRow.Kind.REMOVED -> "removed line ${row.oldLine ?: "?"}"
                    else -> "line ${row.newLine ?: "?"}"
                }
            val code = row.text.trim().take(MAX_QUOTED_CODE).replace("`", "'")
            append("\n${index + 1}. `${comment.file}` $where")
            if (code.isNotEmpty()) append(" (`$code`)")
            append(": ${comment.text.trim()}")
        }
    }
}

private const val MAX_QUOTED_CODE = 120
