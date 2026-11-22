package com.yugahashimoto.andcode.feature.chat

import android.graphics.Bitmap
import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodeMessageError
import com.yugahashimoto.andcode.core.api.OpenCodePart
import com.yugahashimoto.andcode.core.api.PromptAttachment
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The newer of a reply as streamed here ([live]) and as the transcript has it ([snapshot]). Either
 * can be ahead: the transcript lags the stream, but a stream that died quietly lags the transcript.
 * A finished snapshot is the whole reply. Otherwise each part keeps whichever copy is further on -
 * streamed text only ever grows, so a longer snapshot that starts with the live text is newer, and
 * a tool the snapshot shows finished is done - and parts only one side has are kept.
 */
internal fun fresherReply(
    live: ChatMessage,
    snapshot: ChatMessage,
): ChatMessage {
    if (snapshot.completedAt != null) return snapshot
    val livePartsById = live.parts.associateBy { it.id }
    val merged = snapshot.parts.map { part -> livePartsById[part.id]?.let { fresherPart(it, part) } ?: part }
    val snapshotIds = snapshot.parts.map { it.id }.toSet()
    return live.copy(parts = merged + live.parts.filter { it.id !in snapshotIds })
}

private fun fresherPart(
    live: ChatPart,
    snapshot: ChatPart,
): ChatPart {
    fun ahead(
        liveText: String,
        snapshotText: String,
    ) = snapshotText.length > liveText.length && snapshotText.startsWith(liveText)
    return when {
        live is ChatPart.Text && snapshot is ChatPart.Text -> if (ahead(live.text, snapshot.text)) snapshot else live
        live is ChatPart.Reasoning && snapshot is ChatPart.Reasoning -> if (ahead(live.text, snapshot.text)) snapshot else live
        // An early delta is kept as text until its part says it was reasoning.
        live is ChatPart.Text && snapshot is ChatPart.Reasoning ->
            if (ahead(
                    live.text,
                    snapshot.text,
                )
            ) {
                snapshot
            } else {
                snapshot.copy(text = live.text)
            }
        snapshot is ChatPart.Tool && (snapshot.status == ToolStatus.COMPLETED || snapshot.status == ToolStatus.ERROR) -> snapshot
        else -> live
    }
}

/**
 * Rebuilds the timeline from the runtime's persisted transcript, keeping what only this client has.
 *
 * [retainIds] names messages streamed here that the transcript may not carry yet. A turn interrupted
 * by a replacement prompt is finalized asynchronously, and some runtimes never persist its partial
 * output at all; reloading wholesale dropped the bubble the user had been reading, so anything
 * listed here survives until the transcript produces its own copy under the same id (which then
 * wins, keeping the two from doubling up).
 */
internal fun mergeReloadedMessages(
    reloaded: List<ChatMessage>,
    existing: List<ChatMessage>,
    previewsByFilename: Map<String, Bitmap> = emptyMap(),
    retainIds: Set<String> = emptySet(),
): List<ChatMessage> {
    // Nothing persisted yet means nothing to reconcile against: keeping what is on screen is both
    // correct and what callers did with an empty result before this could ever retain anything.
    if (reloaded.isEmpty()) return existing
    val usedExisting = mutableSetOf<Int>()
    val merged =
        reloaded.map { message ->
            val existingIndex =
                existing.indices.firstOrNull { it !in usedExisting && existing[it].id == message.id }
                    ?: existing.indices.firstOrNull { index ->
                        val candidate = existing[index]
                        index !in usedExisting &&
                            message.isUser && candidate.isUser &&
                            candidate.text == message.text &&
                            (message.text.isNotBlank() || candidate.attachments.isNotEmpty())
                    }
            val previous = existingIndex?.let { index -> existing[index].also { usedExisting += index } }
            val reloadedImages = message.attachments.filter { it.mime.startsWith("image/") }
            val reloadedImageNames = reloadedImages.map { it.filename }.toSet()
            // A runtime that echoes the prompt back (Codex) rebuilds the image attachment from the
            // data URL it was sent, under a filename of its own: matching by filename alone would
            // read that image as "missing from the transcript" and append the optimistic copy,
            // showing it twice. The URL is the same bytes either way, so it is the reliable key.
            val reloadedImageUrls = reloadedImages.map { it.url }.toSet()
            val missingImages =
                previous?.attachments.orEmpty().filter {
                    it.mime.startsWith("image/") &&
                        it.filename !in reloadedImageNames &&
                        it.url !in reloadedImageUrls
                }
            val attachments = message.attachments + missingImages
            val previews =
                previous?.imagePreviews.orEmpty().ifEmpty {
                    attachments.mapNotNull { previewsByFilename[it.filename] }
                }
            message.copy(attachments = attachments, imagePreviews = previews)
        }.toMutableList()
    if (retainIds.isNotEmpty()) {
        val reloadedIds = reloaded.map { it.id }.toSet()
        existing
            .asSequence()
            .filter { message ->
                !message.isUser && message.parts.isNotEmpty() && message.id in retainIds && message.id !in reloadedIds
            }
            .forEach { message ->
                // The bubble belongs where it streamed, ahead of everything that came after it,
                // not pinned to the bottom of a timeline that has moved on.
                val index = merged.indexOfFirst { it.timestamp > message.timestamp }
                if (index >= 0) {
                    merged.add(index, message)
                } else {
                    merged.add(message)
                }
            }
    }
    return merged
}

private const val MAX_TOOL_OUTPUT_CHARS = 4000

internal fun OpenCodePart.toChatPart(messageId: String): ChatPart? {
    val partId = id ?: return null
    val stateMap = state.orEmpty()
    return when (type) {
        "text" -> ChatPart.Text(partId, text.orEmpty())
        "reasoning" -> ChatPart.Reasoning(partId, extractReasoningText(text, stateMap))
        "file", "image" -> {
            val partUrl = url.orEmpty()
            val partMime = imageMime(mime, partUrl)
            if (partMime != null && partUrl.isNotBlank()) {
                ChatPart.Image(id = partId, mime = partMime, url = partUrl, filename = filename)
            } else {
                null
            }
        }
        "tool" -> {
            val inputText = formatToolInput(stateMap["input"])
            val rawOutput = stateMap["output"]?.jsonPrimitiveOrNull()
            val truncated = rawOutput != null && rawOutput.length > MAX_TOOL_OUTPUT_CHARS
            val toolName = tool ?: "tool"
            val parsedTodos = if (toolName == "todowrite") parseTodosFromInput(stateMap["input"]) else emptyList()
            val status = parseToolStatus(stateMap["status"]?.jsonPrimitiveOrNull())
            // A write that failed left no file to show, so it names none for the artifact list.
            val writesFiles = toolName.lowercase() in WRITING_TOOLS && status != ToolStatus.ERROR
            ChatPart.Tool(
                id = partId,
                name = toolName,
                status = status,
                title =
                    stateMap["title"]?.jsonPrimitiveOrNull()?.takeIf { it.isNotBlank() }
                        ?: inputText?.lineSequence()?.firstOrNull(),
                input = inputText,
                output = if (truncated) rawOutput?.takeLast(MAX_TOOL_OUTPUT_CHARS) else rawOutput,
                outputTruncated = truncated,
                error = stateMap["error"]?.jsonPrimitiveOrNull(),
                todos = parsedTodos,
                filePaths = if (writesFiles) toolFilePaths(stateMap["input"]) else emptyList(),
            )
        }
        "patch" -> ChatPart.Patch(partId, extractPatchFiles(stateMap), messageId)
        else -> null
    }
}

/**
 * Reasoning text is normally the part's top-level `text`, but some servers and OpenAI-compatible
 * providers persist it under `state` instead (e.g. `state.reasoningDetails[*].text`) while leaving
 * the top level empty. Without this fallback every such part rendered as a "Thinking" card that
 * showed nothing when expanded.
 */
internal fun extractReasoningText(
    topLevelText: String?,
    state: Map<String, JsonElement>,
): String {
    if (!topLevelText.isNullOrBlank()) return topLevelText
    state["text"]?.jsonPrimitiveOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
    state["content"]?.jsonPrimitiveOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
    state["reasoning"]?.jsonPrimitiveOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
    for (key in listOf("reasoningDetails", "reasoning_details", "details")) {
        extractReasoningDetailsText(state[key])?.takeIf { it.isNotBlank() }?.let { return it }
    }
    return ""
}

private fun extractReasoningDetailsText(element: JsonElement?): String? {
    val array = element as? JsonArray ?: return null
    val texts =
        array.mapNotNull { entry ->
            val obj = entry as? JsonObject ?: return@mapNotNull null
            obj["text"]?.jsonPrimitiveOrNull()?.takeIf { it.isNotBlank() }
                ?: obj["content"]?.jsonPrimitiveOrNull()?.takeIf { it.isNotBlank() }
                ?: obj["summary"]?.jsonPrimitiveOrNull()?.takeIf { it.isNotBlank() }
        }
    return texts.joinToString("").takeIf { it.isNotBlank() }
}

/** Image-producing tools do not consistently include a MIME field in their file parts. */
internal fun imageMime(
    declaredMime: String?,
    url: String,
): String? {
    declaredMime?.takeIf { it.startsWith("image/") }?.let { return it }
    // A data URL has no file extension to read: the payload is base64, and looking for one inside it
    // copied the whole payload (see dataImageMime).
    if (url.startsWith("data:")) return dataImageMime(url)
    val extension = url.substringBefore('?').substringBefore('#').substringAfterLast('.').lowercase()
    return when (extension) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "svg" -> "image/svg+xml"
        else -> null
    }
}

/**
 * Appends the message-level error reported by the runtime when the turn itself carries no error
 * part. A failed provider request is persisted with the error on the message (`info.error`) and
 * often nothing else, so without this the transcript would silently drop the failed turn.
 *
 * User-initiated aborts are excluded: OpenCode records them as `MessageAbortedError`, and stopping
 * a turn is not a failure the chat should flag red.
 */
internal fun List<ChatPart>.withMessageError(
    messageId: String,
    error: OpenCodeMessageError?,
): List<ChatPart> {
    if (error == null || error.isAbort) return this
    val message = error.message ?: return this
    if (any { it is ChatPart.Error }) return this
    return this + ChatPart.Error("$messageId-error", message)
}

private fun JsonElement.jsonPrimitiveOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull ?: (this as? JsonPrimitive)?.content

private fun parseToolStatus(value: String?): ToolStatus =
    when (value) {
        "pending" -> ToolStatus.PENDING
        "running" -> ToolStatus.RUNNING
        "completed" -> ToolStatus.COMPLETED
        "error" -> ToolStatus.ERROR
        else -> ToolStatus.UNKNOWN
    }

private fun formatToolInput(input: JsonElement?): String? {
    val obj = input as? JsonObject ?: return null
    if (obj.isEmpty()) return null
    obj["command"]?.jsonPrimitiveOrNull()?.let { return it }
    obj["filePath"]?.jsonPrimitiveOrNull()?.let { path ->
        val extra = obj.entries.filterNot { it.key == "filePath" }
        return if (extra.isEmpty()) {
            path
        } else {
            path + "\n" + extra.joinToString("\n") { (key, value) -> "$key: ${value.jsonPrimitiveOrNull() ?: value}" }
        }
    }
    return obj.entries.joinToString("\n") { (key, value) -> "$key: ${value.jsonPrimitiveOrNull() ?: value}" }
}

private fun parseTodosFromInput(input: JsonElement?): List<TodoItem> {
    val obj = input as? JsonObject ?: return emptyList()
    val todosArray = obj["todos"] as? JsonArray ?: return emptyList()
    return todosArray.mapNotNull { element ->
        val todoObj = element as? JsonObject ?: return@mapNotNull null
        TodoItem(
            content = todoObj["content"]?.jsonPrimitiveOrNull() ?: return@mapNotNull null,
            status = todoObj["status"]?.jsonPrimitiveOrNull() ?: "pending",
            priority = todoObj["priority"]?.jsonPrimitiveOrNull() ?: "medium",
        )
    }
}

private fun extractPatchFiles(state: Map<String, JsonElement>): List<String> {
    return when (val files = state["files"]) {
        is JsonArray ->
            files.mapNotNull { entry ->
                when (entry) {
                    is JsonPrimitive -> entry.content
                    is JsonObject ->
                        entry["path"]?.jsonPrimitiveOrNull()
                            ?: entry["file"]?.jsonPrimitiveOrNull()
                            ?: entry["filename"]?.jsonPrimitiveOrNull()
                    else -> null
                }
            }
        is Map<*, *> -> files.keys.mapNotNull { it?.toString() }
        else -> emptyList()
    }
}

/**
 * The chat's view of one message from the runtime's transcript: its parts, the files it carries as
 * attachments, and its times. Null when it has nothing to show. Which messages are wanted at all
 * (not those a rewind dropped) is the caller's business.
 */
internal fun toChatMessage(message: OpenCodeMessage): ChatMessage? {
    val parts = message.parts.mapNotNull { it.toChatPart(message.info.id) }.withMessageError(message.info.id, message.info.error)
    val attachments =
        message.parts.mapNotNull { part ->
            if (part.type != "file") return@mapNotNull null
            PromptAttachment(
                filename = part.filename ?: "attachment",
                mime = part.mime ?: "application/octet-stream",
                url = part.url ?: "",
            )
        }
    if (parts.isEmpty() && attachments.isEmpty()) return null
    return ChatMessage(
        id = message.info.id,
        isUser = message.info.role == "user",
        parts = parts,
        attachments = attachments,
        timestamp = message.info.time.created,
        completedAt = message.info.time.completed,
        isStreaming = false,
        model = message.info.effectiveModel,
        agent = message.info.agent,
        tokens = message.info.tokens,
        costUsd = message.info.cost,
    )
}
