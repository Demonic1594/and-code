package com.yugahashimoto.andcode.feature.git

/** What a commit message is written from: the changes about to be committed and how this repository words its commits. */
data class CommitMessageRequest(
    /** `git diff --stat` of the changes, so every file is named even when [diff] is cut short. */
    val stat: String,
    val diff: String,
    /** Only the start of the diff is in [diff]. */
    val diffTruncated: Boolean,
    /** New files git does not track yet: committing with nothing staged adds them too. */
    val untracked: List<String>,
    /** The latest commit subjects, newest first, so the message follows the repository's own style. */
    val recentSubjects: List<String>,
)

/** How a request for a written commit message ended. */
sealed interface CommitMessageResult {
    data class Written(val message: String) : CommitMessageResult

    /** No agent that can write one is installed on this device. */
    data object Unavailable : CommitMessageResult

    /** The agent ran but gave no usable message (not signed in, offline, timed out). */
    data object Failed : CommitMessageResult
}

/** Writes a commit message for [CommitMessageRequest]; the app's implementation asks Claude Code once, with no tools. */
fun interface CommitMessageWriter {
    suspend fun write(request: CommitMessageRequest): CommitMessageResult
}

/**
 * The instructions for one commit message. Modelled on Aider's commit prompt (imperative mood, a
 * subject of at most 72 characters, the message and nothing else), with the repository's recent
 * subjects as examples so a project that uses Conventional Commits gets them and one that does not
 * is not forced into them.
 */
object CommitMessagePrompt {
    const val SYSTEM =
        "You write git commit messages. Reply with the commit message only: no quotes, no code fences, no explanations."

    private const val MAX_SUBJECTS = 10
    private const val MAX_UNTRACKED = 50

    fun build(request: CommitMessageRequest): String =
        buildString {
            appendLine("Write a git commit message for the changes below.")
            appendLine("- First line: a summary of at most 72 characters, in the imperative mood (\"Add\", not \"Added\").")
            appendLine("- If the change needs explaining, add a blank line and a short body (wrapped at 72 characters).")
            appendLine("- Say what changed and why; do not list every file.")
            val subjects = request.recentSubjects.map(String::trim).filter(String::isNotEmpty).take(MAX_SUBJECTS)
            if (subjects.isEmpty()) {
                appendLine("- Write it in English.")
            } else {
                appendLine("- Follow the style and language of this repository's recent commit subjects:")
                subjects.forEach { appendLine("  $it") }
            }
            appendLine()
            appendLine("Files changed:")
            appendLine(request.stat.trim().ifEmpty { "(none tracked)" })
            if (request.untracked.isNotEmpty()) {
                appendLine()
                appendLine("New files:")
                request.untracked.take(MAX_UNTRACKED).forEach { appendLine(it) }
                if (request.untracked.size > MAX_UNTRACKED) appendLine("… and ${request.untracked.size - MAX_UNTRACKED} more")
            }
            appendLine()
            appendLine(if (request.diffTruncated) "Diff (only its beginning; the rest was cut):" else "Diff:")
            append(request.diff.trimEnd())
        }

    /**
     * The message as it goes into the box: code fences, a "Commit message:" label and quotes around
     * the whole reply are taken off, lines are trimmed at the end, and blank lines collapse to one.
     * Null when nothing is left.
     */
    fun clean(output: String?): String? {
        var lines = output.orEmpty().replace("\r\n", "\n").lines().map(String::trimEnd)
        lines = lines.filterNot { it.trim().startsWith("```") }
        val text =
            lines
                .joinToString("\n")
                .trim()
                .replace(Regex("^(commit message|message)\\s*:\\s*", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\n{3,}"), "\n\n")
                .trim()
        val unquoted =
            if (text.length >= 2 && text.first() == text.last() && text.first() in "\"'`") {
                text.substring(
                    1,
                    text.length - 1,
                ).trim()
            } else {
                text
            }
        return unquoted.takeIf(String::isNotEmpty)
    }
}
