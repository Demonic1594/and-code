package com.yugahashimoto.andcode.feature.workspace

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yugahashimoto.andcode.core.util.safeMessage
import com.yugahashimoto.andcode.runtime.FileEditException
import com.yugahashimoto.andcode.runtime.OpenCodeBackend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CodeViewerUiState(
    val filePath: String,
    val content: String = "",
    val isLoading: Boolean = true,
    val isBinary: Boolean = false,
    val error: String? = null,
    /** True when the file is inside the workspace on a runtime that can write it, so edit is offered. */
    val canEdit: Boolean = false,
    /** Reading the file's exact text for the editor. */
    val isPreparingEdit: Boolean = false,
    val isEditing: Boolean = false,
    /** What the file held when editing began; the save is refused if it no longer does. */
    val editBase: String = "",
    val draft: String = "",
    val isSaving: Boolean = false,
    /** Why the last attempt to start editing or to save failed. */
    val editError: String? = null,
) {
    /** What the unsaved-changes guard watches: leaving now would throw this away. */
    val hasUnsavedChanges: Boolean get() = isEditing && draft != editBase
}

/** The folder a write has to stay in, and the file's path below it. */
internal data class EditTarget(
    val root: String,
    val relativePath: String,
)

/**
 * Where [filePath], opened while browsing [directory], sits below [editableRoot] - or null when it
 * must not be edited: no root was granted, the root is the whole filesystem, or the file is not
 * under it.
 *
 * The explorer lets the user browse above the workspace, so the folder being viewed is not the
 * boundary; the workspace is. `..` is refused outright because [WorkspaceFolders.normalize] leaves
 * it in place and a prefix test on such a path proves nothing. This is the lexical answer that
 * decides whether to offer the edit at all; the host side re-checks the real, symlink-resolved
 * paths before it writes anything.
 */
internal fun editTargetOf(
    editableRoot: String?,
    directory: String,
    filePath: String,
): EditTarget? {
    val root =
        editableRoot?.let(WorkspaceFolders::normalize)?.takeIf { it != WorkspaceFolders.GUEST_ROOT }
            ?: return null
    if ((directory.split('/') + filePath.split('/')).any { it == ".." }) return null
    val file = WorkspaceFolders.childOf(directory, filePath)
    val prefix = "$root/"
    return if (file.startsWith(prefix)) EditTarget(root, file.removePrefix(prefix)) else null
}

class CodeViewerViewModel(
    private val backend: OpenCodeBackend,
    private val workspacePath: String,
    filePath: String,
    /**
     * The workspace folder this file may be edited under, or null to keep the viewer read-only.
     * Callers pass it only for runtimes that can write files - a remote OpenCode server cannot.
     */
    editableRoot: String? = null,
    private val messages: CodeViewerMessages = CodeViewerMessages,
) : ViewModel() {
    private val mutableState = MutableStateFlow(CodeViewerUiState(filePath = filePath))
    val state: StateFlow<CodeViewerUiState> = mutableState.asStateFlow()

    private val editTarget = editTargetOf(editableRoot, workspacePath, filePath)

    init {
        load()
    }

    fun load() {
        mutableState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            runCatching { backend.readFile(workspacePath, mutableState.value.filePath) }
                .onSuccess { file ->
                    val binary = file.type == "binary" || file.encoding == "base64"
                    mutableState.update {
                        it.copy(
                            content = file.content,
                            isLoading = false,
                            isBinary = binary,
                            canEdit = editTarget != null && !binary,
                        )
                    }
                }.onFailure { error ->
                    mutableState.update {
                        it.copy(
                            isLoading = false,
                            error = error.safeMessage("OpenCode workspace operation failed"),
                        )
                    }
                }
        }
    }

    /**
     * Switches to the editor, starting from the file's exact text as it is on disk now.
     *
     * Read again rather than taken from [CodeViewerUiState.content]: the viewer's copy may have been
     * trimmed by the server, and the agent may have changed the file since it was opened. A file the
     * editor cannot safely save back is turned away here, with the reason, before any typing.
     */
    fun startEditing() {
        val target = editTarget ?: return
        val current = mutableState.value
        if (!current.canEdit || current.isEditing || current.isPreparingEdit) return
        mutableState.update { it.copy(isPreparingEdit = true, editError = null) }
        viewModelScope.launch {
            runCatching { backend.readFileForEditing(target.root, target.relativePath) }
                .onSuccess { text ->
                    mutableState.update {
                        it.copy(isPreparingEdit = false, isEditing = true, editBase = text, draft = text)
                    }
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    mutableState.update { it.copy(isPreparingEdit = false, editError = failureMessage(error)) }
                }
        }
    }

    fun updateDraft(text: String) {
        mutableState.update { if (it.isEditing && !it.isSaving) it.copy(draft = text, editError = null) else it }
    }

    /** Leaves the editor and drops the draft. The caller confirms first when there are unsaved changes. */
    fun stopEditing() {
        mutableState.update { if (it.isSaving) it else it.copy(isEditing = false, editBase = "", draft = "", editError = null) }
    }

    /**
     * Writes the draft. On success the editor closes and the viewer shows what was saved; on failure
     * the draft is kept, with the reason, so nothing the user typed is lost to a refused save.
     */
    fun save() {
        val target = editTarget ?: return
        val snapshot = mutableState.value
        if (!snapshot.hasUnsavedChanges || snapshot.isSaving) return
        mutableState.update { it.copy(isSaving = true, editError = null) }
        viewModelScope.launch {
            runCatching { backend.writeFile(target.root, target.relativePath, snapshot.draft, snapshot.editBase) }
                .onSuccess {
                    mutableState.update { state ->
                        state.copy(content = snapshot.draft, isEditing = false, editBase = "", draft = "", isSaving = false)
                    }
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    mutableState.update { it.copy(isSaving = false, editError = failureMessage(error)) }
                }
        }
    }

    /** The user only gets the reason; the cause (an IOException for a failed write, say) goes to the log. */
    private fun failureMessage(error: Throwable): String {
        Log.w(TAG, "File edit failed", error)
        return (error as? FileEditException)?.let { messages.editFailure(it.failure) } ?: messages.editFailed
    }

    private companion object {
        const val TAG = "CodeViewer"
    }
}
