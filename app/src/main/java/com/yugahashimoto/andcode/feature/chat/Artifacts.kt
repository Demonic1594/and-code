package com.yugahashimoto.andcode.feature.chat

import com.yugahashimoto.andcode.core.localServerUrls
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** What the app can show an artifact as, rather than as source text. */
enum class ArtifactKind { HTML, SVG, MARKDOWN, IMAGE }

/**
 * Something an agent produced in a chat that can be looked at rather than read: a web page, a
 * drawing, a document or a picture it wrote, or a local server it started (a dev server's
 * `http://localhost:5173`). The app's answer to the artifacts of desktop chat apps.
 */
sealed interface Artifact {
    val key: String

    data class File(
        val path: String,
        val kind: ArtifactKind,
    ) : Artifact {
        override val key: String get() = "file:$path"
        val name: String get() = path.substringAfterLast('/')
    }

    data class Server(
        val url: String,
    ) : Artifact {
        override val key: String get() = "server:$url"
    }
}

private val PREVIEWABLE =
    mapOf(
        "html" to ArtifactKind.HTML,
        "htm" to ArtifactKind.HTML,
        "svg" to ArtifactKind.SVG,
        "md" to ArtifactKind.MARKDOWN,
        "markdown" to ArtifactKind.MARKDOWN,
        "png" to ArtifactKind.IMAGE,
        "jpg" to ArtifactKind.IMAGE,
        "jpeg" to ArtifactKind.IMAGE,
        "gif" to ArtifactKind.IMAGE,
        "webp" to ArtifactKind.IMAGE,
    )

/** How [path] can be previewed, or null when it is only source to read. */
fun artifactKindOf(path: String): ArtifactKind? = PREVIEWABLE[path.substringAfterLast('.', "").lowercase()]

// The keys a file tool's input names its file by: OpenCode's `filePath`, Claude Code's `file_path`,
// Antigravity's `TargetFile`, and a plain `path`.
private val TOOL_FILE_PATH_KEYS = listOf("filePath", "file_path", "TargetFile", "targetFile", "target_file", "path")

// The lines an `apply_patch` patch names the files it creates, changes or moves a file to by.
private val PATCH_FILE_LINE = Regex("""^\*\*\* (Add File|Update File|Move to): (.+)$""", RegexOption.MULTILINE)

/**
 * The files a tool's raw [input] names: a file tool's path key, each entry of a Codex file change's
 * list, or each file an `apply_patch` patch adds or updates. Read from the input as the runtime sent
 * it, since what a [ChatPart.Tool] keeps as its input is text formatted for display.
 */
internal fun toolFilePaths(input: JsonElement?): List<String> =
    when (input) {
        is JsonObject ->
            TOOL_FILE_PATH_KEYS.firstNotNullOfOrNull { key -> input[key].stringOrNull() }?.let(::listOf)
                ?: listOf("patchText", "patch", "input")
                    .firstNotNullOfOrNull { key -> input[key].stringOrNull() }
                    ?.let(::patchFilePaths)
                    .orEmpty()
        is JsonArray -> input.mapNotNull { change -> (change as? JsonObject)?.let(::codexChangedPath) }
        is JsonPrimitive -> input.stringOrNull()?.let(::patchFilePaths).orEmpty()
        else -> emptyList()
    }

// A moved file is only where it was moved to: its old path no longer exists to preview.
private fun patchFilePaths(patch: String): List<String> {
    val paths = mutableListOf<String>()
    PATCH_FILE_LINE.findAll(patch).forEach { match ->
        val path = match.groupValues[2].trim()
        if (path.isEmpty()) return@forEach
        if (match.groupValues[1] == "Move to" && paths.isNotEmpty()) paths.removeAt(paths.lastIndex)
        paths += path
    }
    return paths
}

/**
 * Where a Codex file change leaves its file, or null when it deletes it. Its `kind` is either a bare
 * name or an object whose `type` names it, and an update that moves the file says where in
 * `move_path`.
 */
private fun codexChangedPath(change: JsonObject): String? {
    val kind = change["kind"]
    val kindName = (kind as? JsonObject)?.get("type").stringOrNull() ?: kind.stringOrNull()
    if (kindName == "delete") return null
    return (kind as? JsonObject)?.get("move_path").stringOrNull() ?: change["path"].stringOrNull()
}

private fun JsonElement?.stringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }

// Every runtime's names for a tool that writes files: OpenCode's and Claude Code's, Codex's `patch`,
// and Antigravity's.
internal val WRITING_TOOLS =
    setOf(
        "write",
        "edit",
        "multiedit",
        "patch",
        "apply_patch",
        "notebookedit",
        "str_replace_based_edit_tool",
        "str_replace_editor",
        "create_file",
        "write_to_file",
        "replace_file_content",
        "multi_replace_file_content",
    )

/**
 * Everything previewable the agent produced in [messages], newest first and each once: files it
 * wrote or changed that can be shown as what they are, and local servers it mentioned or started.
 * Only the assistant's side counts: a URL the user pasted is not something the agent made.
 */
fun collectArtifacts(messages: List<ChatMessage>): List<Artifact> {
    val found = LinkedHashMap<String, Artifact>()

    fun add(artifact: Artifact) {
        // Re-adding moves it to the end, so a file written again counts as recent.
        found.remove(artifact.key)
        found[artifact.key] = artifact
    }

    messages.filterNot { it.isUser }.forEach { message ->
        message.parts.forEach { part ->
            when (part) {
                is ChatPart.Patch -> part.files.forEach { path -> artifactKindOf(path)?.let { add(Artifact.File(path, it)) } }
                is ChatPart.Tool -> {
                    part.filePaths.forEach { path -> artifactKindOf(path)?.let { add(Artifact.File(path, it)) } }
                    part.output?.let { output -> localServerUrls(output).forEach { add(Artifact.Server(it)) } }
                }
                is ChatPart.Text -> localServerUrls(part.text).forEach { add(Artifact.Server(it)) }
                else -> Unit
            }
        }
    }
    return found.values.reversed()
}

/**
 * [path] relative to [directory] when it lies inside it - the shape the runtime's file read takes -
 * and unchanged otherwise.
 */
fun workspaceRelativePath(
    path: String,
    directory: String?,
): String {
    val root = directory?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return path
    return if (path.startsWith("$root/")) path.removePrefix("$root/") else path
}

/** The artifact preview, from opening through the file being read. */
sealed interface ArtifactPreviewState {
    val artifact: Artifact.File

    data class Loading(
        override val artifact: Artifact.File,
    ) : ArtifactPreviewState

    data class Loaded(
        override val artifact: Artifact.File,
        val content: String,
        /** True when [content] is base64 - a picture, not text. */
        val base64: Boolean,
    ) : ArtifactPreviewState

    data class Failed(
        override val artifact: Artifact.File,
        /** What the runtime said, or null when there was no workspace to read from. */
        val message: String?,
    ) : ArtifactPreviewState
}
