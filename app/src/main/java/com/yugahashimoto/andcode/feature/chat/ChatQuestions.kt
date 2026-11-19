package com.yugahashimoto.andcode.feature.chat

import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.QuestionPrompt
import com.yugahashimoto.andcode.core.api.QuestionRequest
import com.yugahashimoto.andcode.core.util.safeMessage
import com.yugahashimoto.andcode.runtime.OpenCodeBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val PENDING_QUESTION_REFRESH_ATTEMPTS = 3
private const val PENDING_QUESTION_REFRESH_RETRY_DELAY_MS = 3000L

data class PendingQuestionUi(
    val request: QuestionRequest,
    val selectedAnswers: List<List<String>>,
    val isSubmitting: Boolean = false,
    val error: String? = null,
) {
    val canSubmit: Boolean
        get() =
            request.questions.indices.all { index ->
                val prompt = request.questions[index]
                sanitizeQuestionAnswer(
                    prompt = prompt,
                    answers = selectedAnswers.getOrElse(index) { emptyList() },
                    multiple = prompt.multiple,
                ).isNotEmpty()
            }

    companion object {
        fun from(request: QuestionRequest) =
            PendingQuestionUi(
                request = request,
                selectedAnswers = request.questions.map { emptyList() },
            )
    }
}

/**
 * The questions the agent asks in the open chat, for [ChatViewModel]: the cards, the answers being
 * chosen, submitting or declining them, hiding one, and recovering ones asked while this client was
 * not listening. Launches on the view model's scope and writes into its state;
 * [onQuestionResolved] lets the app cancel the question's notification.
 */
internal class ChatQuestions(
    private val backend: OpenCodeBackend?,
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<ChatUiState>,
    private val onQuestionResolved: (String) -> Unit,
    /** Shown on a card submitted without an answer to every question; read when it is shown. */
    private val answerRequiredMessage: () -> String,
    /** Shown on a card whose answer or decline the runtime refused; read when it is shown. */
    private val failedMessage: () -> String,
) {
    /**
     * Questions the user hid with [dismissQuestion]. A recovery fetch must not resurrect them:
     * they are still open server-side, so without this every refetch would put the card back.
     */
    private val dismissedQuestionIds = mutableSetOf<String>()

    fun selectQuestionAnswer(
        questionId: String,
        questionIndex: Int,
        answer: String,
    ) {
        uiState.update { state ->
            state.copy(
                pendingQuestions =
                    state.pendingQuestions.map { pending ->
                        if (pending.request.id != questionId) return@map pending
                        val prompt = pending.request.questions.getOrNull(questionIndex) ?: return@map pending
                        val updatedAnswers = pending.selectedAnswers.toMutableList()
                        updatedAnswers[questionIndex] =
                            updateQuestionAnswerSelection(
                                prompt = prompt,
                                current = pending.selectedAnswers.getOrElse(questionIndex) { emptyList() },
                                answer = answer,
                                multiple = prompt.multiple,
                            )
                        pending.copy(selectedAnswers = updatedAnswers, error = null)
                    },
            )
        }
    }

    fun submitQuestion(questionId: String) {
        val currentBackend = backend ?: return
        val pendingQuestion = uiState.value.pendingQuestions.firstOrNull { it.request.id == questionId } ?: return
        val answers =
            pendingQuestion.request.questions.indices.map { index ->
                val prompt = pendingQuestion.request.questions[index]
                sanitizeQuestionAnswer(
                    prompt = prompt,
                    answers = pendingQuestion.selectedAnswers.getOrElse(index) { emptyList() },
                    multiple = prompt.multiple,
                )
            }
        if (answers.any { it.isEmpty() }) {
            val message = answerRequiredMessage()
            uiState.update { state ->
                state.copy(
                    pendingQuestions =
                        state.pendingQuestions.map { pending ->
                            if (pending.request.id == questionId) {
                                pending.copy(error = message)
                            } else {
                                pending
                            }
                        },
                )
            }
            return
        }

        uiState.update { state ->
            state.copy(
                pendingQuestions =
                    state.pendingQuestions.map { pending ->
                        if (pending.request.id == questionId) {
                            pending.copy(isSubmitting = true, error = null)
                        } else {
                            pending
                        }
                    },
            )
        }

        scope.launch {
            runCatching {
                currentBackend.answerQuestion(
                    requestId = questionId,
                    answers = answers,
                    directory = pendingQuestion.workspaceDirectory(),
                )
            }.onSuccess { accepted ->
                if (accepted) onQuestionResolved(questionId)
                val message = if (accepted) null else failedMessage()
                uiState.update { state ->
                    if (accepted) {
                        state.copy(
                            pendingQuestions = state.pendingQuestions.filterNot { it.request.id == questionId },
                        )
                    } else {
                        state.copy(
                            pendingQuestions =
                                state.pendingQuestions.map { pending ->
                                    if (pending.request.id == questionId) {
                                        pending.copy(isSubmitting = false, error = message)
                                    } else {
                                        pending
                                    }
                                },
                        )
                    }
                }
            }.onFailure { error ->
                uiState.update { state ->
                    state.copy(
                        pendingQuestions =
                            state.pendingQuestions.map { pending ->
                                if (pending.request.id == questionId) {
                                    pending.copy(isSubmitting = false, error = error.safeMessage("OpenCode operation failed"))
                                } else {
                                    pending
                                }
                            },
                    )
                }
            }
        }
    }

    /**
     * The directory the question routes have to be scoped to. What the event stream reported is
     * authoritative; the open session's own directory is the next best source; the selected
     * workspace is the last fallback for the older per-instance `/event` stream, whose frames
     * carry no directory.
     */
    private fun PendingQuestionUi.workspaceDirectory(): String? =
        request.directory
            ?: uiState.value.sessionDirectory
            ?: uiState.value.selectedWorkspacePath

    /**
     * Recovers questions that are already waiting for an answer. A question reaches the chat as an
     * event and nowhere else, so one asked while this client was not listening — in another chat,
     * before the app opened the session, or across a dropped event stream — would otherwise leave
     * the turn blocked with nothing on screen to unblock it.
     *
     * The question routes are scoped to the instance that owns the session, so the directory is
     * resolved from the session itself: the workspace the composer happens to have selected often
     * belongs to another project entirely, and querying with it finds nothing.
     */
    fun refreshPendingQuestions(sessionId: String) {
        val currentBackend = backend ?: return
        scope.launch {
            val directory =
                runCatching { currentBackend.session(sessionId).directory }
                    .getOrNull()
                    ?: uiState.value.selectedWorkspacePath
            if (directory != null && uiState.value.sessionId == sessionId) {
                uiState.update { it.copy(sessionDirectory = directory) }
            }
            var fetched: List<QuestionRequest>? = null
            for (attempt in 0 until PENDING_QUESTION_REFRESH_ATTEMPTS) {
                if (uiState.value.sessionId != sessionId) return@launch
                fetched = runCatching { currentBackend.pendingQuestions(directory) }.getOrNull()
                if (fetched != null) break
                if (attempt < PENDING_QUESTION_REFRESH_ATTEMPTS - 1) delay(PENDING_QUESTION_REFRESH_RETRY_DELAY_MS)
            }
            val pending = (fetched ?: return@launch).filter { it.sessionId == sessionId }
            if (pending.isEmpty()) return@launch
            uiState.update { state ->
                if (state.sessionId != sessionId) return@update state
                val known = state.pendingQuestions.map { it.request.id }.toSet()
                state.copy(
                    pendingQuestions =
                        state.pendingQuestions +
                            pending
                                .filterNot { it.id in known || it.id in dismissedQuestionIds }
                                .map(PendingQuestionUi::from),
                )
            }
        }
    }

    fun dismissQuestion(questionId: String) {
        dismissedQuestionIds += questionId
        uiState.update { state ->
            state.copy(
                pendingQuestions = state.pendingQuestions.filterNot { it.request.id == questionId },
            )
        }
    }

    /**
     * Declines the question. Its card stays, its buttons waiting, until the runtime has taken the
     * decline: the agent is still blocked on the question until then, and the card is the only way
     * left to answer it.
     */
    fun cancelQuestion(questionId: String) {
        val pendingQuestion = uiState.value.pendingQuestions.firstOrNull { it.request.id == questionId } ?: return
        val currentBackend = backend ?: return
        updatePendingQuestion(questionId) { it.copy(isSubmitting = true, error = null) }
        scope.launch {
            runCatching {
                currentBackend.rejectQuestion(
                    requestId = questionId,
                    directory = pendingQuestion.workspaceDirectory(),
                )
            }.onSuccess { rejected ->
                if (rejected) {
                    onQuestionResolved(questionId)
                    uiState.update { state ->
                        state.copy(pendingQuestions = state.pendingQuestions.filterNot { it.request.id == questionId })
                    }
                } else {
                    val message = failedMessage()
                    updatePendingQuestion(questionId) { it.copy(isSubmitting = false, error = message) }
                }
            }.onFailure { error ->
                updatePendingQuestion(questionId) { it.copy(isSubmitting = false) }
                val message = error.safeMessage(failedMessage())
                uiState.update { it.copy(error = message) }
            }
        }
    }

    private fun updatePendingQuestion(
        questionId: String,
        transform: (PendingQuestionUi) -> PendingQuestionUi,
    ) {
        uiState.update { state ->
            state.copy(
                pendingQuestions = state.pendingQuestions.map { if (it.request.id == questionId) transform(it) else it },
            )
        }
    }

    /** A question the agent asks, shown unless the user hid it. */
    fun onQuestionAsked(
        event: OpenCodeEvent.QuestionAsked,
        activeSession: String?,
    ) {
        if (event.request.sessionId != activeSession) return
        if (event.request.id in dismissedQuestionIds) return
        uiState.update { state ->
            state.copy(
                pendingQuestions =
                    state.pendingQuestions
                        .filterNot { it.request.id == event.request.id } + PendingQuestionUi.from(event.request),
                isThinking = false,
            )
        }
    }

    /** The composer moved to another chat, whose questions are all offered again. */
    fun forgetDismissals() {
        dismissedQuestionIds.clear()
    }
}

private fun updateQuestionAnswerSelection(
    prompt: QuestionPrompt,
    current: List<String>,
    answer: String,
    multiple: Boolean,
): List<String> {
    // Keep the raw text while the user is typing — trimming here would strip a trailing space
    // the moment it's typed, since this feeds straight back into the (controlled) text field's
    // value and silently joins words together. Trimming happens once, at submit time, in
    // sanitizeQuestionAnswer.
    val optionLabels = prompt.options.map { it.label }.toSet()
    if (prompt.options.isEmpty()) {
        return answer.takeIf { it.isNotEmpty() }?.let(::listOf).orEmpty()
    }

    val optionAnswers = current.filter { it in optionLabels }
    val fallback = current.lastOrNull { it !in optionLabels }
    if (answer in optionLabels) {
        return if (multiple) {
            val toggled =
                if (answer in optionAnswers) {
                    optionAnswers.filterNot { it == answer }
                } else {
                    optionAnswers + answer
                }
            toggled + listOfNotNull(fallback?.takeIf { it.isNotEmpty() })
        } else {
            listOf(answer)
        }
    }

    val nextFallback = answer.takeIf { it.isNotEmpty() }
    return if (multiple) {
        optionAnswers + listOfNotNull(nextFallback)
    } else {
        listOfNotNull(nextFallback)
    }
}

private fun sanitizeQuestionAnswer(
    prompt: QuestionPrompt,
    answers: List<String>,
    multiple: Boolean,
): List<String> {
    val normalizedAnswers = answers.map(String::trim).filter { it.isNotEmpty() }
    if (prompt.options.isEmpty()) {
        return normalizedAnswers.take(1)
    }

    val optionLabels = prompt.options.map { it.label }.toSet()
    val selectedOptions = normalizedAnswers.filter { it in optionLabels }.distinct()
    // A prompt with `custom` off only accepts its own options, so never submit a typed answer.
    val fallback = normalizedAnswers.lastOrNull { it !in optionLabels }?.takeIf { prompt.custom }
    return if (multiple) {
        selectedOptions + listOfNotNull(fallback)
    } else {
        selectedOptions.firstOrNull()?.let(::listOf)
            ?: fallback?.let(::listOf)
            ?: emptyList()
    }
}
