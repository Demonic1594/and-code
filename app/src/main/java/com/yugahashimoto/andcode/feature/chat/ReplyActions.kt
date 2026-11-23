package com.yugahashimoto.andcode.feature.chat

import com.yugahashimoto.andcode.core.api.OpenCodeModelReference
import com.yugahashimoto.andcode.core.api.OpenCodeSessionTokens

/**
 * What the action row under a finished assistant reply works with: its text, the prompt that
 * produced it, and what the runtime reported about it. A reply is everything the assistant wrote
 * between one user message and the next; [groupConversationTimeline] puts it on the reply's
 * [TimelineEntry.Footer].
 */
data class ReplyContent(
    /** The reply's body texts, in order: one per text part, across the turn's messages. */
    val texts: List<String>,
    /** The user message the reply answers; null when none comes before it. */
    val promptMessageId: String?,
    /** Whether the turn edited files, which a retry offers to put back first. */
    val changedFiles: Boolean,
    /** Whether the reply's last message is complete: a reply still being written gets no row. */
    val finished: Boolean,
    /** The model that wrote the reply's last message, as `provider/model`, when the runtime says. */
    val model: String? = null,
    /** The reply's tokens over all its messages; null when no message reported any. */
    val tokens: ReplyTokens? = null,
    /** What the reply cost in USD; null when no message reported a cost, as opposed to a known zero. */
    val costUsd: Double? = null,
)

data class ReplyTokens(
    val input: Long,
    val output: Long,
    val reasoning: Long,
    val cacheRead: Long,
) {
    val total: Long get() = input + output + reasoning + cacheRead

    operator fun plus(tokens: OpenCodeSessionTokens): ReplyTokens =
        ReplyTokens(
            input = input + tokens.input,
            output = output + tokens.output,
            reasoning = reasoning + tokens.reasoning,
            cacheRead = cacheRead + (tokens.cache?.read ?: 0L),
        )

    companion object {
        val ZERO = ReplyTokens(0L, 0L, 0L, 0L)
    }
}

/** One row of a reply's Details sheet. */
sealed interface ReplyDetail {
    data class Model(val name: String) : ReplyDetail

    /** From the prompt going out to the reply's last message landing. */
    data class Duration(val millis: Long) : ReplyDetail

    data class Tokens(val tokens: ReplyTokens) : ReplyDetail

    data class Cost(val usd: Double) : ReplyDetail
}

/**
 * Gathers one reply's [ReplyContent] while [groupConversationTimeline] walks the transcript: the
 * texts, what the runtime reported for each message, and whether the turn edited files or failed.
 */
internal class ReplyCollector {
    private var promptId: String? = null
    private val texts = mutableListOf<String>()
    private var changedFiles = false
    private var endedInError = false
    private var lastDone = false
    private var lastStreaming = false
    private var model: String? = null
    private var tokens: ReplyTokens? = null
    private var cost: Double? = null

    /** A user message: the next reply answers it. */
    fun prompt(messageId: String) {
        reset()
        promptId = messageId
    }

    /** One of the reply's messages, before its parts. */
    fun message(message: ChatMessage) {
        lastDone = message.completedAt != null
        lastStreaming = message.isStreaming
        message.model?.let { model = it.label() }
        message.tokens?.let { tokens = (tokens ?: ReplyTokens.ZERO) + it }
        message.costUsd?.let { cost = (cost ?: 0.0) + it }
    }

    fun text(text: String) {
        texts += text
    }

    fun changedFiles() {
        changedFiles = true
    }

    /** The turn showed an error card: its footer trails that, so it carries no action row. */
    fun error() {
        endedInError = true
    }

    /** The reply so far, for its footer, starting afresh for whatever the turn writes after it. */
    fun take(): ReplyContent? {
        val reply =
            if (texts.isEmpty() || endedInError) {
                null
            } else {
                ReplyContent(
                    texts = texts.toList(),
                    promptMessageId = promptId,
                    changedFiles = changedFiles,
                    finished = lastDone && !lastStreaming,
                    model = model,
                    tokens = tokens,
                    costUsd = cost,
                )
            }
        reset()
        return reply
    }

    private fun reset() {
        texts.clear()
        changedFiles = false
        endedInError = false
        model = null
        tokens = null
        cost = null
    }
}

/** The model as the Details sheet names it: `provider/model`, or the model alone without a provider. */
internal fun OpenCodeModelReference.label(): String = if (providerId.isBlank()) modelId else "$providerId/$modelId"

/**
 * The footer of the transcript's latest reply: the last footer with no user message after it. Null
 * once a newer prompt is waiting for its answer, so Retry is never offered on an older reply.
 */
fun latestReplyFooterId(entries: List<TimelineEntry>): String? {
    for (entry in entries.asReversed()) {
        when (entry) {
            is TimelineEntry.UserMessage -> return null
            is TimelineEntry.Footer -> return entry.id
            else -> Unit
        }
    }
    return null
}

/**
 * Whether [footer]'s reply shows its action row now: a finished reply with text of its own. The
 * latest reply hides it while the agent still works, since another step can follow a finished one.
 */
fun showsReplyActions(
    footer: TimelineEntry.Footer,
    isLatest: Boolean,
    isRunning: Boolean,
): Boolean {
    val reply = footer.reply ?: return false
    return reply.finished && reply.texts.isNotEmpty() && !(isLatest && isRunning)
}

/**
 * Whether Retry is offered on [reply]: only the latest reply, only while the agent is idle and no
 * rewind or file restore ([isBusy]) is landing, and only when there is a prompt to send again.
 */
fun showsRetry(
    reply: ReplyContent,
    isLatest: Boolean,
    isRunning: Boolean,
    isBusy: Boolean,
): Boolean = isLatest && !isRunning && !isBusy && reply.finished && reply.promptMessageId != null

/** The reply as the screen shows it, without Markdown syntax, its parts a paragraph apart: for Copy and Share. */
fun replyPlainText(texts: List<String>): String = renderPlain(texts, codeOmitted = null)

/** The reply to read aloud: [replyPlainText] with every code block replaced by [codeOmitted]. */
fun replySpeechText(
    texts: List<String>,
    codeOmitted: String,
): String = renderPlain(texts, codeOmitted)

/** The reply's Markdown, as written, its parts a paragraph apart: for "Copy as Markdown". */
fun replyMarkdown(texts: List<String>): String = texts.joinToString("\n\n") { it.trim() }

/** A Markdown quote of the reply for the composer: every line behind "> ", then a blank line to type on. */
fun replyQuote(texts: List<String>): String =
    replyMarkdown(texts).lines().joinToString("\n") { line -> if (line.isBlank()) ">" else "> $line" } + "\n\n"

/** The Details rows for [reply], in order; anything the runtime did not report is left out. */
fun replyDetails(
    reply: ReplyContent,
    durationMs: Long,
): List<ReplyDetail> =
    buildList {
        reply.model?.let { add(ReplyDetail.Model(it)) }
        if (durationMs > 0L) add(ReplyDetail.Duration(durationMs))
        reply.tokens?.takeIf { it.total > 0L }?.let { add(ReplyDetail.Tokens(it)) }
        reply.costUsd?.let { add(ReplyDetail.Cost(it)) }
    }

/**
 * [text] in pieces of at most [maxLength] characters, for a speech engine that takes no more at
 * once: whole paragraphs where they fit, else whole sentences, else words, and a word longer than
 * the limit is cut.
 */
fun speechChunks(
    text: String,
    maxLength: Int,
): List<String> {
    require(maxLength > 0) { "maxLength must be positive" }
    val chunks = mutableListOf<String>()
    for (paragraph in text.split(PARAGRAPH_BREAK).map(String::trim).filter(String::isNotEmpty)) {
        if (paragraph.length <= maxLength) {
            chunks += paragraph
            continue
        }
        val pieces =
            paragraph.split(SENTENCE_END).flatMap {
                    sentence ->
                if (sentence.length <= maxLength) listOf(sentence) else words(sentence, maxLength)
            }
        var current = StringBuilder()
        for (piece in pieces) {
            if (current.isNotEmpty() && current.length + 1 + piece.length > maxLength) {
                chunks += current.toString()
                current = StringBuilder()
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(piece)
        }
        if (current.isNotEmpty()) chunks += current.toString()
    }
    return chunks
}

/**
 * The utterance ids for the [reading]th reading of the reply [key], spoken in [pieces] pieces. The
 * last is the reading's own id, and the others name it before [SPEECH_PIECE_SEPARATOR]. A new reading
 * of the same reply gets new ids, so a callback from an earlier one cannot end it.
 */
internal fun speechUtteranceIds(
    key: String,
    reading: Int,
    pieces: Int,
): List<String> {
    val readingId = "$key@$reading"
    return List(pieces) { index -> if (index == pieces - 1) readingId else "$readingId$SPEECH_PIECE_SEPARATOR$index" }
}

/** The reading an utterance id from [speechUtteranceIds] belongs to. */
internal fun speechReadingOf(utteranceId: String): String = utteranceId.substringBefore(SPEECH_PIECE_SEPARATOR)

private const val SPEECH_PIECE_SEPARATOR = "#piece"

/** [sentence] as words, a word longer than [maxLength] cut into pieces that fit. */
private fun words(
    sentence: String,
    maxLength: Int,
): List<String> = sentence.split(WHITESPACE).filter(String::isNotEmpty).flatMap { it.chunked(maxLength) }

private val PARAGRAPH_BREAK = Regex("\\n\\s*\\n")
private val SENTENCE_END = Regex("(?<=[.!?。！？])\\s+")
private val WHITESPACE = Regex("\\s+")

private fun renderPlain(
    texts: List<String>,
    codeOmitted: String?,
): String =
    texts
        .map { blocksText(MarkdownLite.parse(it), codeOmitted) }
        .filter(String::isNotBlank)
        .joinToString("\n\n")

private fun blocksText(
    blocks: List<MarkdownBlock>,
    codeOmitted: String?,
): String = blocks.mapNotNull { blockText(it, codeOmitted) }.filter(String::isNotBlank).joinToString("\n\n")

private fun blockText(
    block: MarkdownBlock,
    codeOmitted: String?,
): String? =
    when (block) {
        is MarkdownBlock.Heading -> inlinesText(block.inlines)
        is MarkdownBlock.Paragraph -> inlinesText(block.inlines)
        is MarkdownBlock.CodeBlock -> codeOmitted ?: block.code.trimEnd('\n')
        is MarkdownBlock.BulletList -> block.items.joinToString("\n") { listItemText("• ", it, codeOmitted) }
        is MarkdownBlock.OrderedList ->
            block.items.withIndex().joinToString("\n") { (index, item) -> listItemText("${block.start + index}. ", item, codeOmitted) }
        is MarkdownBlock.Blockquote -> blocksText(block.blocks, codeOmitted)
        is MarkdownBlock.Table ->
            (listOf(block.headers) + block.rows).joinToString("\n") { row -> row.joinToString("\t") { inlinesText(it) } }
        is MarkdownBlock.Footnote -> "[${block.label}] " + blocksText(block.blocks, codeOmitted)
        MarkdownBlock.HorizontalRule -> null
    }

/** A list item behind its [marker], a task box when it has one (not read aloud), its nested blocks indented under it. */
private fun listItemText(
    marker: String,
    item: MarkdownListItem,
    codeOmitted: String?,
): String {
    val speaking = codeOmitted != null
    val box =
        when {
            speaking || item.checked == null -> ""
            item.checked -> "☑ "
            else -> "☐ "
        }
    val head = marker + box + inlinesText(item.inlines)
    if (item.children.isEmpty()) return head
    val nested = blocksText(item.children, codeOmitted).lines().joinToString("\n") { "  $it" }
    return "$head\n$nested"
}

private fun inlinesText(inlines: List<MarkdownInline>): String = inlines.joinToString("") { it.text }.trim()
