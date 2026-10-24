package com.yugahashimoto.andcode.feature.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yugahashimoto.andcode.core.util.safeMessage
import com.yugahashimoto.andcode.runtime.OpenCodeBackend
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
    /** Editing is offered only for text files on this device's runtime, under the save size cap. */
    val canEdit: Boolean = false,
    /** Non-null while the user is editing: the text being edited. */
    val draft: String? = null,
    val isSaving: Boolean = false,
    val saveError: String? = null,
    /** Bumped on each successful save so the screen can confirm it once. */
    val savedCount: Int = 0,
) {
    val isEditing: Boolean get() = draft != null
    val hasUnsavedChanges: Boolean get() = draft != null && draft != content
}

class CodeViewerViewModel(
    private val backend: OpenCodeBackend,
    private val workspacePath: String,
    filePath: String,
    /** Null for a remote runtime, which leaves the viewer read-only. */
    private val shell: WorkspaceShell? = null,
) : ViewModel() {
    private val mutableState = MutableStateFlow(CodeViewerUiState(filePath = filePath))
    val state: StateFlow<CodeViewerUiState> = mutableState.asStateFlow()

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
                            canEdit =
                                shell != null && !binary &&
                                    file.content.toByteArray(Charsets.UTF_8).size <= WorkspaceShell.MAX_WRITE_BYTES,
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

    fun startEditing() {
        if (!mutableState.value.canEdit) return
        mutableState.update { it.copy(draft = it.content, saveError = null) }
    }

    fun updateDraft(text: String) {
        mutableState.update { if (it.draft != null) it.copy(draft = text) else it }
    }

    fun cancelEditing() {
        mutableState.update { it.copy(draft = null, saveError = null) }
    }

    fun save() {
        val shell = shell ?: return
        val current = mutableState.value
        val draft = current.draft ?: return
        if (current.isSaving) return
        mutableState.update { it.copy(isSaving = true, saveError = null) }
        viewModelScope.launch {
            val result = shell.writeFile(WorkspaceShell.guestPath(workspacePath, current.filePath), draft)
            mutableState.update {
                if (result.isSuccess) {
                    it.copy(content = draft, draft = null, isSaving = false, savedCount = it.savedCount + 1)
                } else {
                    val error = result.exceptionOrNull()
                    // Over-size has its own localized message in the screen; git or shell errors are
                    // shown as the command printed them.
                    it.copy(
                        isSaving = false,
                        saveError = if (error is FileTooLargeException) SAVE_ERROR_TOO_LARGE else error?.safeMessage("Save failed"),
                    )
                }
            }
        }
    }
}

/** Marker [CodeViewerUiState.saveError] value the screen swaps for its localized "too large" text. */
internal const val SAVE_ERROR_TOO_LARGE = "\u0000too-large"
