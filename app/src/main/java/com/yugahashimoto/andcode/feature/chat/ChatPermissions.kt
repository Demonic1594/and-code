package com.yugahashimoto.andcode.feature.chat

import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.PermissionRequest
import com.yugahashimoto.andcode.core.util.safeMessage
import com.yugahashimoto.andcode.runtime.OpenCodeBackend
import com.yugahashimoto.andcode.runtime.PermissionResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The permission cards of the open chat, for [ChatViewModel]: what the agent asked, the answers the
 * user gives, the ones given elsewhere, and auto-accept. Launches on the view model's scope and
 * writes into its state; [onPermissionResolved] lets the app cancel the request's notification.
 */
internal class ChatPermissions(
    private val backend: OpenCodeBackend?,
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<ChatUiState>,
    private val onPermissionResolved: (String) -> Unit,
    /** Shown when the runtime refused an answer. */
    private val notAppliedMessage: String,
) {
    /** Drops the card of a permission answered from the notification or the activity screen. */
    fun followResolvedElsewhere(flow: Flow<String>) {
        scope.launch {
            flow.collect { permissionId ->
                uiState.update { state ->
                    state.copy(permissions = state.permissions.filterNot { it.id == permissionId })
                }
            }
        }
    }

    fun respondToPermission(
        permissionId: String,
        response: PermissionResponse,
        remember: Boolean,
    ) = answerPermission(permissionId) { target, request ->
        target.respondToPermission(request.sessionId, request.id, response, remember)
    }

    fun allowMcpServer(permissionId: String) =
        answerPermission(permissionId) { target, request -> target.allowMcpServer(request.sessionId, request.id) }

    private fun answerPermission(
        permissionId: String,
        answer: suspend (OpenCodeBackend, PermissionRequest) -> Boolean,
    ) {
        val currentBackend = backend ?: return
        val permission = uiState.value.permissions.firstOrNull { it.id == permissionId } ?: return
        scope.launch {
            runCatching { answer(currentBackend, permission) }.onSuccess { accepted ->
                if (accepted) {
                    uiState.update { state ->
                        state.copy(permissions = state.permissions.filterNot { it.id == permissionId })
                    }
                    onPermissionResolved(permissionId)
                } else {
                    uiState.update { it.copy(error = notAppliedMessage) }
                }
            }.onFailure { error ->
                uiState.update { it.copy(error = error.safeMessage("OpenCode operation failed")) }
            }
        }
    }

    /** A permission the agent asks for: answered at once under auto-accept, else shown as a card. */
    fun onPermissionAsked(
        event: OpenCodeEvent.PermissionAsked,
        activeSession: String?,
    ) {
        if (uiState.value.autoAcceptPermissions) {
            val request = event.request
            val autoBackend = backend ?: return
            scope.launch {
                runCatching {
                    autoBackend.respondToPermission(
                        request.sessionId,
                        request.id,
                        PermissionResponse.ONCE,
                        false,
                    )
                }.onSuccess { accepted ->
                    if (accepted) onPermissionResolved(request.id)
                }
            }
            return
        }
        if (event.request.sessionId != activeSession) return
        uiState.update { state ->
            state.copy(
                permissions = state.permissions.filterNot { it.id == event.request.id } + event.request,
                isThinking = false,
            )
        }
    }

    /** A permission answered elsewhere. */
    fun onPermissionReplied(
        event: OpenCodeEvent.PermissionReplied,
        activeSession: String?,
    ) {
        // Answered elsewhere — another device, or the TUI. Drop the stale card.
        if (event.sessionId != activeSession) return
        uiState.update { state ->
            state.copy(permissions = state.permissions.filterNot { it.id == event.requestId })
        }
    }
}
