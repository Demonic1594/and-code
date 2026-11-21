package com.yugahashimoto.andcode.feature.chat

import com.yugahashimoto.andcode.runtime.OpenCodeBackend
import com.yugahashimoto.andcode.runtime.RuntimeTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the chat opens over itself to look at, for [ChatViewModel]: the diff behind a tapped changes
 * card, from the runtime or else from the turn's snapshots in [restorePoints], and the preview of an
 * artifact the agent wrote. Launches on the view model's scope and writes into its state.
 */
internal class ChatPreviews(
    private val backend: OpenCodeBackend?,
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<ChatUiState>,
    private val turnSnapshots: TurnSnapshots?,
    private val restorePoints: RestorePoints,
) {
    fun openPatchDiff(patch: ChatPart.Patch) {
        uiState.update { it.copy(patchDiff = PatchDiffState.Loading(patch.id, patch.files)) }
        val currentBackend = backend
        val sessionId = uiState.value.sessionId
        val runtime = currentBackend as? RuntimeTarget
        val diffCapable = runtime?.capabilities?.diffCapable == true
        if (sessionId == null || (!diffCapable && turnSnapshots == null)) {
            uiState.update { it.copy(patchDiff = PatchDiffState.Unavailable(patch.id, patch.files)) }
            return
        }
        val directory = uiState.value.sessionDirectory
        scope.launch {
            // The runtime's own diff when it has one; otherwise, or when it has nothing to say (a
            // folder that is not a git repository), the turn's snapshots.
            val fromRuntime =
                if (diffCapable) {
                    snapshotCall(emptyList()) { runtime?.sessionDiff(sessionId, directory, patch.messageId).orEmpty() }
                } else {
                    emptyList()
                }
            val changes = fromRuntime.ifEmpty { restorePoints.snapshotChanges(sessionId, patch) }
            // A dialog dismissed and reopened on a different patch while this fetch was still in
            // flight must not have its answer land on the wrong card.
            uiState.update {
                if (it.patchDiff?.partId != patch.id) return@update it
                it.copy(
                    patchDiff =
                        if (changes.isEmpty()) {
                            PatchDiffState.Unavailable(patch.id, patch.files)
                        } else {
                            PatchDiffState.Loaded(patch.id, patch.files, changes)
                        },
                )
            }
        }
    }

    /** Bumped by every [openArtifact], so only the latest read fills the preview. */
    private var artifactRequest = 0L

    fun openArtifact(artifact: Artifact.File) {
        val request = ++artifactRequest
        uiState.update { it.copy(artifactPreview = ArtifactPreviewState.Loading(artifact)) }
        val currentBackend = backend
        val directory = uiState.value.sessionDirectory ?: uiState.value.selectedWorkspacePath
        if (currentBackend == null || directory == null) {
            uiState.update { it.copy(artifactPreview = ArtifactPreviewState.Failed(artifact, null)) }
            return
        }
        scope.launch {
            val read = artifactReadPair(artifact.path, directory)
            val result = runCatching { currentBackend.readFile(read.first, read.second) }
            uiState.update {
                // Dismissed, or another artifact opened, while the file was on its way. An equal
                // one opened since is a newer read's to fill: the same file read again, or another
                // workspace's file at the same relative path.
                if (request != artifactRequest || it.artifactPreview?.artifact != artifact) return@update it
                it.copy(
                    artifactPreview =
                        result.fold(
                            onSuccess = { file ->
                                ArtifactPreviewState.Loaded(
                                    artifact = artifact,
                                    content = file.content,
                                    base64 = file.encoding == "base64" || file.type == "binary",
                                )
                            },
                            // The runtime's own words when it has some; otherwise the screen says it.
                            onFailure = { error -> ArtifactPreviewState.Failed(artifact, error.message?.takeIf { it.isNotBlank() }) },
                        ),
                )
            }
        }
    }

    fun dismissArtifact() {
        uiState.update { it.copy(artifactPreview = null) }
    }

    fun dismissPatchDiff() {
        uiState.update { it.copy(patchDiff = null) }
    }
}
