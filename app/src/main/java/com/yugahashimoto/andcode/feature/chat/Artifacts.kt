package com.yugahashimoto.andcode.feature.chat

import com.yugahashimoto.andcode.core.localServerUrls

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

// The path a file tool wrote, in the shapes the runtimes use: OpenCode's `filePath`, Claude Code's
// `file_path`, and a plain `path`.
private val TOOL_FILE_PATH = Regex(""""(?:filePath|file_path|path)"\s*:\s*"((?:[^"\\]|\\.)+)"""")

private val WRITING_TOOLS = setOf("write", "edit", "multiedit", "patch", "str_replace_based_edit_tool")

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
                    if (part.name.lowercase() in WRITING_TOOLS) {
                        part.input?.let { input ->
                            TOOL_FILE_PATH.find(input)?.groupValues?.get(1)?.replace("\\/", "/")?.let { path ->
                                artifactKindOf(path)?.let { add(Artifact.File(path, it)) }
                            }
                        }
                    }
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
