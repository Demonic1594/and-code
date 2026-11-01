package com.yugahashimoto.andcode.core.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class OpenCodeHealth(
    val healthy: Boolean,
    val version: String,
)

@Serializable
data class OpenCodeTime(
    val created: Long = 0L,
    val updated: Long? = null,
    val completed: Long? = null,
    /**
     * Epoch ms when the session was archived, or null while it is active. The server's
     * `GET /session` keeps returning archived sessions, so this field is what tells them apart —
     * the app hides them everywhere a chat list is shown.
     */
    val archived: Long? = null,
)

@Serializable
data class OpenCodeSession(
    val id: String,
    val slug: String? = null,
    @SerialName("projectID") val projectId: String? = null,
    @SerialName("parentID") val parentId: String? = null,
    val directory: String? = null,
    val path: String? = null,
    val title: String = "",
    val version: String? = null,
    val time: OpenCodeTime = OpenCodeTime(),
    val tokens: OpenCodeSessionTokens? = null,
    val share: OpenCodeSessionShare? = null,
    /** Set while the session is rewound ([OpenCodeApi.revertSession]); null otherwise. */
    val revert: OpenCodeSessionRevert? = null,
)

/** Where a rewound session stands: [messageId] and every message after it are reverted. */
@Serializable
data class OpenCodeSessionRevert(
    @SerialName("messageID") val messageId: String,
    @SerialName("partID") val partId: String? = null,
)

@Serializable
data class OpenCodeSessionShare(
    val url: String,
)

@Serializable
data class OpenCodeSessionTokens(
    val input: Long = 0L,
    val output: Long = 0L,
    val reasoning: Long = 0L,
    val cache: OpenCodeCacheTokens? = null,
) {
    val contextUsed: Long
        get() = input + (cache?.read ?: 0L)
}

@Serializable
data class OpenCodeCacheTokens(
    val read: Long = 0L,
    val write: Long = 0L,
)

@Serializable
data class OpenCodeModelReference(
    @SerialName("providerID") val providerId: String,
    @SerialName("modelID") val modelId: String,
)

@Serializable
data class OpenCodeMessageInfo(
    val id: String,
    @SerialName("sessionID") val sessionId: String,
    val role: String,
    val time: OpenCodeTime = OpenCodeTime(),
    val agent: String? = null,
    val model: OpenCodeModelReference? = null,
    /**
     * The nested `model` object is not the only shape a server sends: the local runtime's
     * OpenCode (1.18.x) stores and serves `modelID`/`providerID` as top-level message fields,
     * verified against its own sqlite store. [effectiveModel] reads either shape, so the usage
     * breakdown and the reply-model lookup do not come back empty for every local turn.
     */
    @SerialName("modelID") private val topLevelModelId: String? = null,
    @SerialName("providerID") private val topLevelProviderId: String? = null,
    val tokens: OpenCodeSessionTokens? = null,
    /** What the provider charged for this turn in USD, when the runtime reports it (OpenCode does). */
    val cost: Double? = null,
    /** Provider/session failure recorded on the message, e.g. `ApiError` with `statusCode` 429. */
    val error: OpenCodeMessageError? = null,
) {
    /** The message's model, from the nested object when present, else the top-level fields. */
    val effectiveModel: OpenCodeModelReference?
        get() = model ?: topLevelModelId?.let { id -> OpenCodeModelReference(topLevelProviderId.orEmpty(), id) }
}

/**
 * Names the runtimes give an error that only records a turn being stopped on purpose — by the stop
 * button, or by a replacement prompt sent mid-turn. Stopping a run is a decision, not a failure, so
 * these must never read as one.
 */
val AbortErrorNames: Set<String> = setOf("MessageAbortedError", "AbortError")

/** A typed message error, e.g. `ApiError`; rendered in the chat when a turn fails. */
@Serializable
data class OpenCodeMessageError(
    val name: String? = null,
    val data: Map<String, JsonElement>? = null,
) {
    val message: String?
        get() =
            data?.get("message")
                ?.takeIf { it is JsonPrimitive }
                ?.let { (it as JsonPrimitive).content }
                ?.takeIf { it.isNotBlank() }

    /** OpenCode records a stop the user asked for as an error; it is not a failure to report. */
    val isAbort: Boolean
        get() = name in AbortErrorNames
}

@Serializable
data class OpenCodePart(
    val id: String? = null,
    @SerialName("sessionID") val sessionId: String? = null,
    @SerialName("messageID") val messageId: String? = null,
    val type: String,
    val text: String? = null,
    val filename: String? = null,
    val mime: String? = null,
    val url: String? = null,
    val tool: String? = null,
    val callID: String? = null,
    val state: Map<String, JsonElement>? = null,
)

@Serializable
data class OpenCodeMessage(
    val info: OpenCodeMessageInfo,
    val parts: List<OpenCodePart> = emptyList(),
) {
    val text: String
        get() = parts.filter { it.type == "text" }.mapNotNull { it.text }.joinToString("")
}

@Serializable
data class OpenCodeModel(
    val id: String,
    @SerialName("providerID") val providerId: String? = null,
    val name: String = id,
    val status: String? = null,
    val limit: OpenCodeModelLimit? = null,
    val variants: Map<String, JsonElement> = emptyMap(),
    /**
     * Codex only: the service-tier id that means "Fast" for this model (the app-server's `priority`
     * tier, or a legacy `fast`), or null when the model offers no fast mode. Named so no OpenCode
     * payload carries the key; left serialized so the disk catalogue cache keeps it.
     */
    val codexFastServiceTier: String? = null,
    /** Codex only: the server's description of the fast tier, shown beside the fast-mode toggle. */
    val codexFastDescription: String? = null,
    /** Codex only: true when the server defaults this model to the fast tier. */
    val codexFastByDefault: Boolean = false,
    /**
     * Codex only: the model's `defaultReasoningEffort`. Sent explicitly on every turn, because the
     * app-server applies `effort` to subsequent turns too and omitting it would inherit the previous one.
     */
    val codexDefaultEffort: String? = null,
    /**
     * Codex only: the model's `defaultServiceTier`, or null for standard speed. Sent explicitly on
     * every turn for the same reason as [codexDefaultEffort].
     */
    val codexDefaultServiceTier: String? = null,
    /**
     * Claude Code only: true when the model can run in ultracode, Claude Code's standing
     * multi-agent workflow mode. Named so no OpenCode payload carries the key.
     */
    val claudeUltracode: Boolean = false,
)

@Serializable
data class OpenCodeModelLimit(
    val context: Long = 0L,
    val output: Long = 0L,
)

@Serializable
data class OpenCodeProvider(
    val id: String,
    val name: String = id,
    val models: Map<String, OpenCodeModel> = emptyMap(),
)

@Serializable
data class ProviderCatalog(
    val all: List<OpenCodeProvider> = emptyList(),
    val default: Map<String, String> = emptyMap(),
    val connected: List<String> = emptyList(),
)

@Serializable
data class OpenCodeAgent(
    val name: String,
    val description: String? = null,
    val mode: String? = null,
    val native: Boolean = false,
    /**
     * Nullable on purpose: OpenCode sends `"hidden": null` for plugin agents (oh-my-opencode-slim's
     * `orchestrator`, `fixer`, ...), and a non-null Boolean made the whole `GET /agent` list fail to
     * decode, leaving the mode picker with nothing to offer. Only `true` hides an agent.
     */
    val hidden: Boolean? = null,
)

/**
 * The agents a user can start a chat with: OpenCode's primary (and `all`) agents, minus the hidden
 * internal ones (compaction, title, summary). Plugin-defined primaries such as oh-my-opencode-slim's
 * `orchestrator` are included.
 *
 * Server order is kept on purpose: OpenCode's `GET /agent` sorts its configured `default_agent`
 * first (or `build` when none is set), so [defaultAgentName] follows the server's choice instead of
 * hard-coding `build`.
 */
fun List<OpenCodeAgent>.selectablePrimaryAgents(): List<OpenCodeAgent> =
    filter { (it.mode == null || it.mode == "primary" || it.mode == "all") && it.hidden != true }

/** The server's default agent: the first selectable primary it lists, or null when it offers none. */
fun List<OpenCodeAgent>.defaultAgentName(): String? = selectablePrimaryAgents().firstOrNull()?.name

@Serializable
data class OpenCodeProject(
    val id: String,
    val worktree: String,
    val name: String? = null,
)

@Serializable
data class OpenCodePathInfo(
    val home: String,
    val state: String,
    val config: String,
    val worktree: String,
    val directory: String,
)

@Serializable
data class OpenCodeFileNode(
    val name: String,
    val path: String,
    val absolute: String,
    val type: String,
    val ignored: Boolean = false,
)

@Serializable
data class OpenCodePatchHunk(
    val oldStart: Int,
    val oldLines: Int,
    val newStart: Int,
    val newLines: Int,
    val lines: List<String> = emptyList(),
)

@Serializable
data class OpenCodeFilePatch(
    val oldFileName: String,
    val newFileName: String,
    val oldHeader: String? = null,
    val newHeader: String? = null,
    val hunks: List<OpenCodePatchHunk> = emptyList(),
    val index: String? = null,
)

@Serializable
data class OpenCodeFileContent(
    val type: String,
    val content: String,
    val diff: String? = null,
    val patch: OpenCodeFilePatch? = null,
    val encoding: String? = null,
    val mimeType: String? = null,
)

@Serializable
data class OpenCodeSearchText(
    val text: String,
)

@Serializable
data class OpenCodeSearchSubmatch(
    val match: OpenCodeSearchText,
    val start: Int,
    val end: Int,
)

@Serializable
data class OpenCodeSearchMatch(
    val path: OpenCodeSearchText,
    val lines: OpenCodeSearchText,
    @SerialName("line_number") val lineNumber: Int,
    @SerialName("absolute_offset") val absoluteOffset: Int,
    val submatches: List<OpenCodeSearchSubmatch> = emptyList(),
)

@Serializable
data class OpenCodeFileChange(
    val file: String? = null,
    val path: String? = null,
    val patch: String? = null,
    val additions: Double = 0.0,
    val deletions: Double = 0.0,
    val added: Int = 0,
    val removed: Int = 0,
    val status: String? = null,
) {
    val displayPath: String
        get() = file ?: path.orEmpty()
}

@Serializable
data class OpenCodeTodo(
    val content: String,
    val status: String,
    val priority: String,
)

@Serializable
data class OpenCodeVcsInfo(
    val branch: String? = null,
    @SerialName("default_branch") val defaultBranch: String? = null,
)

@Serializable
data class PromptRequest(
    val text: String,
    val providerId: String? = null,
    val modelId: String? = null,
    val agent: String? = null,
    val variant: String? = null,
    val attachments: List<PromptAttachment> = emptyList(),
    val noReply: Boolean = false,
    /**
     * Codex only: the service tier for this turn. Null leaves the choice to Codex; `"default"`
     * forces standard speed. Built field by field for OpenCode, so it is never sent to that server.
     */
    val serviceTier: String? = null,
    /**
     * Claude Code only: run the session in ultracode, or null to keep what the session last ran
     * with. Replies from a notification, the Inbox, a schedule or a skill leave it null, so they do
     * not restart an ultracode session without it. Never sent to OpenCode or Codex.
     */
    val ultracode: Boolean? = null,
)

@Serializable
data class PromptAttachment(
    val filename: String,
    val mime: String,
    val url: String,
    /**
     * Guest path of a copy of this file inside the workspace, set when the file's MIME type cannot
     * be sent as a model file part. The agent reads it from disk instead; [url] is blank then.
     */
    val workspacePath: String? = null,
)

@Serializable
data class PermissionRequest(
    val id: String,
    val sessionId: String,
    val permission: String,
    val patterns: List<String> = emptyList(),
    val metadata: Map<String, JsonElement> = emptyMap(),
    /**
     * Whether an "Always allow" answer can persist anything for this call. False when the approval
     * store could only keep something it deliberately does not (a shell command that chains, pipes,
     * redirects or runs other code is approved once by design), so the button is hidden rather
     * than tapped for no effect.
     */
    val canAlwaysAllow: Boolean = true,
)

@Serializable
data class QuestionOption(
    val label: String,
    val description: String? = null,
)

@Serializable
data class QuestionPrompt(
    val question: String,
    val header: String? = null,
    val options: List<QuestionOption> = emptyList(),
    val placeholder: String? = null,
    /** Whether more than one of [options] may be selected. OpenCode sets this per prompt. */
    val multiple: Boolean = false,
    /** Whether an answer outside [options] is accepted. OpenCode defaults this to true. */
    val custom: Boolean = true,
)

@Serializable
data class QuestionRequest(
    val id: String,
    @SerialName("sessionID") val sessionId: String,
    val questions: List<QuestionPrompt>,
    /**
     * Workspace the request belongs to. The question routes are scoped to a single OpenCode
     * instance, so replying needs the directory the question was asked in — it is not carried
     * by the request itself, but by the `/global/event` envelope that delivered it.
     */
    val directory: String? = null,
)

/**
 * A provider call that failed in a way the runtime retries on its own (a rate limit, an overloaded
 * model, a dropped connection). While it waits out the backoff the run produces no output at all,
 * which from the chat looks identical to a model that is thinking hard.
 */
data class RunRetry(
    /** Which attempt just failed, counting from 1. */
    val attempt: Int,
    /** The provider's own words for the failure, when the runtime passed them on. */
    val message: String?,
)

sealed interface OpenCodeEvent {
    data object ServerConnected : OpenCodeEvent

    data class MessageUpdated(val info: OpenCodeMessageInfo) : OpenCodeEvent

    data class MessagePartUpdated(val part: OpenCodePart) : OpenCodeEvent

    data class MessagePartDelta(
        val sessionId: String,
        val messageId: String,
        val partId: String,
        val field: String,
        val delta: String,
        /**
         * How many upstream deltas this one folds together. The stream delivers one event per
         * chunk; `coalesceStreamingUpdates` concatenates consecutive chunks for a part into a
         * single event and reports the count, so per-chunk consumers (the connection quality's
         * token rate) keep measuring the real rate.
         */
        val mergeCount: Int = 1,
    ) : OpenCodeEvent

    data class PermissionAsked(val request: PermissionRequest) : OpenCodeEvent

    data class PermissionReplied(val sessionId: String, val requestId: String) : OpenCodeEvent

    data class QuestionAsked(val request: QuestionRequest) : OpenCodeEvent

    data class SessionIdle(val sessionId: String) : OpenCodeEvent

    /** A new session appeared. Its [OpenCodeSession.parentId] names the session that spawned it. */
    data class SessionCreated(val session: OpenCodeSession) : OpenCodeEvent

    /** A session's metadata changed; carries the same payload as [SessionCreated]. */
    data class SessionUpdated(val session: OpenCodeSession) : OpenCodeEvent

    /**
     * Replacement for the deprecated `session.idle`: status is `idle`, `busy` or `retry`. A `retry`
     * carries [retry], the provider failure the runtime is waiting out before it tries again.
     */
    data class SessionStatusChanged(
        val sessionId: String,
        val status: String,
        val retry: RunRetry? = null,
    ) : OpenCodeEvent

    /**
     * A run stopped on purpose — stop button, or interrupted by a replacement prompt — is a decision,
     * not a failure. The error that arrives here must never read as one.
     */
    data class SessionError(val sessionId: String?, val message: String?, val name: String? = null) : OpenCodeEvent {
        /** An abort the user asked for arrives here too; it must not read as a failure. */
        val isAbort: Boolean
            get() = name in AbortErrorNames
    }

    data class Unknown(val type: String, val rawJson: String) : OpenCodeEvent
}

/**
 * The session an event concerns, or null for the events that are about the server rather than one
 * conversation. Used to tell which run an event proves is still alive.
 */
fun OpenCodeEvent.sessionIdOrNull(): String? =
    when (this) {
        is OpenCodeEvent.MessageUpdated -> info.sessionId
        is OpenCodeEvent.MessagePartUpdated -> part.sessionId
        is OpenCodeEvent.MessagePartDelta -> sessionId
        is OpenCodeEvent.PermissionAsked -> request.sessionId
        is OpenCodeEvent.PermissionReplied -> sessionId
        is OpenCodeEvent.QuestionAsked -> request.sessionId
        is OpenCodeEvent.SessionIdle -> sessionId
        is OpenCodeEvent.SessionCreated -> session.id
        is OpenCodeEvent.SessionUpdated -> session.id
        is OpenCodeEvent.SessionStatusChanged -> sessionId
        is OpenCodeEvent.SessionError -> sessionId
        OpenCodeEvent.ServerConnected -> null
        is OpenCodeEvent.Unknown -> null
    }

@Serializable
data class McpServer(
    val name: String,
    val status: String? = null,
    val type: String? = null,
    val command: String? = null,
    val url: String? = null,
    val tools: List<String> = emptyList(),
    val error: String? = null,
    /**
     * Set by AndCode, never by a server: the program a failed local server runs that is not
     * installed in the Linux environment, which is why it could not start.
     */
    val missingProgram: String? = null,
)

@Serializable
data class McpAuthStart(
    val authorizationUrl: String,
    val oauthState: String,
)

@Serializable
data class McpAuthStatus(
    val status: String,
    val error: String? = null,
)

@Serializable
data class McpAuthRemoval(
    val success: Boolean,
)

@Serializable
data class OpenCodeConfig(
    val config: JsonElement? = null,
)

@Serializable
data class ConfiguredProvider(
    val id: String,
    val name: String = id,
    @SerialName("default_model") val defaultModel: String? = null,
    val connected: Boolean = false,
)

@Serializable
data class OpenCodeCommand(
    val name: String,
    val description: String? = null,
    val template: String? = null,
    val source: String? = null,
)

@Serializable
data class OpenCodeSkill(
    val name: String,
    val description: String? = null,
    val location: String? = null,
)

class OpenCodeApiException(
    val statusCode: Int,
    message: String,
) : Exception(message)
