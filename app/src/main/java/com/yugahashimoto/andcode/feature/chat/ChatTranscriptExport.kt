package com.yugahashimoto.andcode.feature.chat

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Headings for the exported transcript, resolved from string resources by the caller. */
internal data class TranscriptLabels(
    val user: String,
    val assistant: String,
    val tool: String,
    val error: String,
)

/**
 * Renders a chat as Markdown: one heading per turn, text as-is, tool calls as a one-line summary and
 * errors as a quote. Reasoning is left out on purpose - it is the model's scratchpad, long and
 * rarely what someone sharing a chat means to share.
 */
internal fun chatTranscriptMarkdown(
    title: String,
    messages: List<ChatMessage>,
    labels: TranscriptLabels,
    exportedAt: Date = Date(),
): String =
    buildString {
        append("# ").append(title.ifBlank { "AndCode chat" }).append("\n\n")
        append("_").append(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(exportedAt)).append("_\n")
        messages.forEach { message ->
            append("\n## ").append(if (message.isUser) labels.user else labels.assistant).append("\n\n")
            message.parts.forEach { part ->
                when (part) {
                    is ChatPart.Text -> if (part.text.isNotBlank()) append(part.text.trim()).append("\n\n")
                    is ChatPart.Tool -> {
                        append("- ").append(labels.tool).append(": `").append(part.name).append('`')
                        part.title?.takeIf { it.isNotBlank() }?.let { append(" - ").append(it.lineSequence().first()) }
                        append("\n\n")
                    }
                    is ChatPart.Patch ->
                        if (part.files.isNotEmpty()) {
                            part.files.forEach { append("- `").append(it).append("`\n") }
                            append('\n')
                        }
                    is ChatPart.Image -> append(imageMarkdown(part)).append("\n\n")
                    is ChatPart.Error -> append("> **").append(labels.error).append(":** ").append(part.message).append("\n\n")
                    is ChatPart.Reasoning -> Unit
                }
            }
        }
    }.trimEnd() + "\n"

/**
 * An image as Markdown. Attached photos travel as `data:` URLs, hundreds of kilobytes of base64
 * each; inlining them would bloat a shared file to megabytes and push a clipboard copy past the
 * binder limit, so those become a named placeholder. Real links are kept.
 */
internal fun imageMarkdown(part: ChatPart.Image): String {
    val name = part.filename?.takeIf(String::isNotBlank) ?: "image"
    return if (part.url.startsWith("data:", ignoreCase = true)) "![$name](attachment:$name)" else "![$name](${part.url})"
}

/**
 * Longest transcript put on the clipboard. The clipboard goes through binder, where a few hundred
 * kilobytes is already risky and around 1 MB crashes the app; anything longer is offered as a share.
 */
internal const val MAX_CLIPBOARD_TRANSCRIPT_CHARS = 200_000

/** A file name that survives every file system the share targets might write to. */
internal fun transcriptFileName(title: String): String {
    val slug =
        title
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(48)
            .ifBlank { "chat" }
    return "andcode-$slug.md"
}

/**
 * Writes [markdown] under cache/exports and opens the system share sheet for it. A file rather than
 * EXTRA_TEXT because a long chat easily passes the ~1 MB binder limit, which crashes the send.
 */
internal fun shareChatTranscript(
    context: Context,
    title: String,
    markdown: String,
    chooserTitle: String,
) {
    val directory = File(context.cacheDir, "exports").apply { mkdirs() }
    // One export at a time is enough; old ones would otherwise pile up in cache until Android clears it.
    directory.listFiles()?.forEach { it.delete() }
    val file = File(directory, transcriptFileName(title)).apply { writeText(markdown) }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
    val send =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/markdown"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    context.startActivity(Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
