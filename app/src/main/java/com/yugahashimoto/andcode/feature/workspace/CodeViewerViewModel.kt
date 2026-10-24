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
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
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
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    // Edit history for undo/redo. A burst of typing is one step, not one per keystroke: a snapshot is
    // only taken when the previous edit is older than UNDO_COALESCE_MS, the way editors group typing.
    private val undoStack = ArrayDeque<String>()
    private val redoStack = ArrayDeque<String>()
    private var lastEditAt = 0L

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
        clearHistory()
        mutableState.update { it.copy(draft = it.content, saveError = null) }
    }

    fun updateDraft(text: String) {
        val current = mutableState.value.draft ?: return
        if (text == current) return
        val now = clock()
        if (undoStack.isEmpty() || now - lastEditAt > UNDO_COALESCE_MS) pushUndo(current)
        lastEditAt = now
        redoStack.clear()
        mutableState.update { it.copy(draft = text, canUndo = undoStack.isNotEmpty(), canRedo = false) }
    }

    fun undo() {
        val current = mutableState.value.draft ?: return
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(current)
        // The next keystroke starts a new step instead of folding into the one just undone.
        lastEditAt = 0L
        mutableState.update { it.copy(draft = previous, canUndo = undoStack.isNotEmpty(), canRedo = true) }
    }

    fun redo() {
        val current = mutableState.value.draft ?: return
        val next = redoStack.removeLastOrNull() ?: return
        pushUndo(current)
        lastEditAt = 0L
        mutableState.update { it.copy(draft = next, canUndo = true, canRedo = redoStack.isNotEmpty()) }
    }

    /**
     * Replaces every occurrence of [find] in the draft and returns how many there were. Literal text,
     * not a regex: a phone keyboard is a poor place to debug escaping. One undo step reverts it all.
     */
    fun replaceAll(
        find: String,
        replacement: String,
        ignoreCase: Boolean,
    ): Int {
        val current = mutableState.value.draft ?: return 0
        if (find.isEmpty()) return 0
        val count = Regex(Regex.escape(find), if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()).findAll(current).count()
        if (count == 0) return 0
        pushUndo(current)
        lastEditAt = 0L
        redoStack.clear()
        val updated = current.replace(find, replacement, ignoreCase)
        mutableState.update { it.copy(draft = updated, canUndo = true, canRedo = false) }
        return count
    }

    fun cancelEditing() {
        clearHistory()
        mutableState.update { it.copy(draft = null, saveError = null, canUndo = false, canRedo = false) }
    }

    // Each step is a full snapshot, so the history is bounded by total size as well as by count:
    // 200 steps of a 1 MB file would otherwise hold hundreds of megabytes.
    private fun pushUndo(text: String) {
        undoStack.addLast(text)
        while (undoStack.size > MAX_UNDO_STEPS || (undoStack.size > 1 && undoStack.sumOf { it.length } > MAX_UNDO_CHARS)) {
            undoStack.removeFirst()
        }
    }

    private fun clearHistory() {
        undoStack.clear()
        redoStack.clear()
        lastEditAt = 0L
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
                    clearHistory()
                    it.copy(
                        content = draft,
                        draft = null,
                        isSaving = false,
                        savedCount = it.savedCount + 1,
                        canUndo = false,
                        canRedo = false,
                    )
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

private const val UNDO_COALESCE_MS = 800L
private const val MAX_UNDO_STEPS = 200
private const val MAX_UNDO_CHARS = 8_000_000

/** Marker [CodeViewerUiState.saveError] value the screen swaps for its localized "too large" text. */
internal const val SAVE_ERROR_TOO_LARGE = "\u0000too-large"
