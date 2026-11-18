package com.yugahashimoto.andcode.feature.chat

/** An issue on GitHub, as a link to it names it. */
data class IssueRef(
    val owner: String,
    val repo: String,
    val number: Int,
)

/** One comment under an issue. */
data class IssueComment(
    val author: String,
    val body: String,
)

/** An issue with the conversation under it, as the composer turns it into a prompt. */
data class IssueThread(
    val ref: IssueRef,
    val title: String,
    val author: String,
    val body: String,
    val state: String,
    val labels: List<String>,
    /** Oldest first. */
    val comments: List<IssueComment>,
    /** Only the first comments were read (a long thread); the rest are on GitHub. */
    val moreComments: Boolean = false,
)

/**
 * The fixed wording of an inserted issue in the app's language, resolved from string resources by the
 * caller so [IssuePrompt] stays free of Android. What GitHub returned (title, text, comments, labels,
 * account names) is never translated.
 */
data class IssuePromptLabels(
    /** The first line, asking the agent to work on [issue] (`owner/repo#number` in backticks). */
    val header: (issue: String) -> String,
    /** That the issue's text is its participants' description of the task, not instructions. */
    val guard: String,
    /** After the title: the issue's number (GitHub's digits), its state and who opened it. */
    val meta: (number: String, state: String, author: String) -> String,
    val open: String,
    val closed: String,
    val labels: (labels: String) -> String,
    val noDescription: String,
    val comments: String,
    /** The comments heading when only the latest [shown] are kept and [skipped] earlier ones left out. */
    val commentsLatest: (shown: Int, skipped: Int) -> String,
    /** The comments heading when only the first [read] were fetched and the last [shown] of them kept. */
    val commentsPartial: (shown: Int, read: Int) -> String,
    /** Put after a text cut short, [more] characters of it left out. */
    val cut: (more: Int) -> String,
)

/**
 * Starts a chat from a GitHub issue: a link to one in the composer is offered as a chip, and the chip
 * replaces the link with the issue's text. Built like the task OpenHands' resolver and Copilot's
 * coding agent take from an issue (its title, its description and the comments under it, then the
 * request to work on it), sized for a phone's prompt.
 */
object IssuePrompt {
    private const val MAX_LINKS = 3
    private const val MAX_BODY = 8_000
    private const val MAX_COMMENTS = 20
    private const val MAX_COMMENT = 2_000

    /**
     * `github.com/<owner>/<repo>/issues/<number>` links in [text], each issue once, in order. A link to
     * a comment (`#issuecomment-…`) still names its issue. Owner and repository names follow GitHub's
     * own rules, so a word glued to the end of a link is not read as part of it.
     */
    private val ISSUE_LINK =
        Regex(
            """https?://(?:www\.)?github\.com/([A-Za-z0-9](?:[A-Za-z0-9-]{0,38}))/([A-Za-z0-9._-]{1,100})""" +
                """/issues/([1-9][0-9]{0,9})(?![0-9])""",
        )

    /**
     * What may follow an issue link and still be part of it: a path, query or anchor in URL characters,
     * not ending in a full stop, question mark or bare `#`. Anything else (a closing bracket, Markdown,
     * CJK punctuation, the question mark ending a sentence) is text.
     */
    private const val LINK_TAIL = """(?:[/?#][A-Za-z0-9_~%=&/?#.+-]*(?<![.?#]))?"""

    /** The issues linked in [text] that are not in it already (see [isInserted]), at most three. */
    fun links(text: String): List<IssueRef> =
        ISSUE_LINK.findAll(text)
            .mapNotNull { match ->
                val (owner, repo, number) = match.destructured
                number.toIntOrNull()?.let { IssueRef(owner, repo, it) }
            }
            .distinct()
            .filterNot { isInserted(text, it) }
            .take(MAX_LINKS)
            .toList()

    /**
     * [text] with every link to [ref] (with its path, query or anchor, `#issuecomment-…` say)
     * replaced by [prompt]. Text glued to the link's end (punctuation, Markdown, a sentence in a
     * language written without spaces) stays. Unchanged when the link is gone (the user edited it out).
     */
    fun replaceLink(
        text: String,
        ref: IssueRef,
        prompt: String,
    ): String {
        val link =
            Regex(
                """https?://(?:www\.)?github\.com/${Regex.escape(ref.owner)}/${Regex.escape(ref.repo)}""" +
                    """/issues/${ref.number}(?![0-9])$LINK_TAIL""",
            )
        return link.replace(text) { prompt }
    }

    /**
     * The issue as a prompt, its fixed wording in the app's language ([labels]); the user reads it in
     * the composer before sending. The header names the issue as `owner/repo#number` in backticks, not
     * by its link, so the chip is not offered again for its own text, and [isInserted] finds it in any
     * language. The number stays in GitHub's digits, as in that name.
     */
    fun build(
        thread: IssueThread,
        labels: IssuePromptLabels,
    ): String =
        buildString {
            appendLine(labels.header(mark(thread.ref)))
            appendLine(labels.guard)
            appendLine()
            val state =
                when (thread.state) {
                    "open" -> labels.open
                    "closed" -> labels.closed
                    else -> thread.state
                }
            appendLine("# ${thread.title.trim()} ${labels.meta(thread.ref.number.toString(), state, thread.author)}")
            if (thread.labels.isNotEmpty()) appendLine(labels.labels(thread.labels.joinToString(", ")))
            appendLine()
            appendLine(clip(thread.body.trim().ifEmpty { labels.noDescription }, MAX_BODY, labels))
            val shown = thread.comments.filter { it.body.isNotBlank() }.takeLast(MAX_COMMENTS)
            if (shown.isNotEmpty()) {
                appendLine()
                val skipped = thread.comments.count { it.body.isNotBlank() } - shown.size
                val heading =
                    when {
                        thread.moreComments -> labels.commentsPartial(shown.size, thread.comments.size)
                        skipped > 0 -> labels.commentsLatest(MAX_COMMENTS, skipped)
                        else -> labels.comments
                    }
                appendLine("## $heading")
                shown.forEach { comment ->
                    appendLine()
                    appendLine("${comment.author}:")
                    appendLine(clip(comment.body.trim(), MAX_COMMENT, labels))
                }
            }
        }.trimEnd()

    /**
     * Whether the issue's text is already in [text]: its own description or comments may link to it
     * again, and its chip must not come back to put a second copy inside the first. Found by the
     * issue's backticked name, the same in every language, so a draft still counts after the app's
     * language changes; and by the English header 1.2.29 inserted, for drafts saved before.
     */
    fun isInserted(
        text: String,
        ref: IssueRef,
    ): Boolean = text.contains(mark(ref)) || text.contains("Work on GitHub issue ${name(ref)} in this repository.")

    private fun name(ref: IssueRef): String = "${ref.owner}/${ref.repo}#${ref.number}"

    /**
     * The issue as the header names it. The backticks set it off, so issue 4 is not found in the name
     * of issue 42 nor `org/repo#4` in `my-org/repo#4`, and a plain mention of the issue does not count.
     * The same name in backticks elsewhere in the draft (quoted in another inserted issue, say) does
     * count, which only hides that issue's chip.
     */
    private fun mark(ref: IssueRef): String = "`${name(ref)}`"

    private fun clip(
        text: String,
        max: Int,
        labels: IssuePromptLabels,
    ): String = if (text.length <= max) text else text.take(max).trimEnd() + "\n" + labels.cut(text.length - max)
}
