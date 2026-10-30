package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.ConfiguredProvider
import com.yugahashimoto.andcode.core.api.McpAuthRemoval
import com.yugahashimoto.andcode.core.api.McpAuthStart
import com.yugahashimoto.andcode.core.api.McpAuthStatus
import com.yugahashimoto.andcode.core.api.McpServer
import com.yugahashimoto.andcode.core.api.OpenCodeAgent
import com.yugahashimoto.andcode.core.api.OpenCodeCommand
import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.OpenCodeFileChange
import com.yugahashimoto.andcode.core.api.OpenCodeFileContent
import com.yugahashimoto.andcode.core.api.OpenCodeFileNode
import com.yugahashimoto.andcode.core.api.OpenCodeHealth
import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodePathInfo
import com.yugahashimoto.andcode.core.api.OpenCodeProject
import com.yugahashimoto.andcode.core.api.OpenCodeProtocol
import com.yugahashimoto.andcode.core.api.OpenCodeSearchMatch
import com.yugahashimoto.andcode.core.api.OpenCodeSession
import com.yugahashimoto.andcode.core.api.OpenCodeSkill
import com.yugahashimoto.andcode.core.api.OpenCodeTodo
import com.yugahashimoto.andcode.core.api.OpenCodeVcsInfo
import com.yugahashimoto.andcode.core.api.PromptRequest
import com.yugahashimoto.andcode.core.api.ProtocolDetectingOpenCodeApi
import com.yugahashimoto.andcode.core.api.ProviderAuthAuthorization
import com.yugahashimoto.andcode.core.api.ProviderAuthMethod
import com.yugahashimoto.andcode.core.api.ProviderCatalog
import com.yugahashimoto.andcode.core.api.QuestionRequest
import com.yugahashimoto.andcode.data.connection.ConnectionProfile
import com.yugahashimoto.andcode.runtime.BackendKind
import com.yugahashimoto.andcode.runtime.OpenCodeBackend
import com.yugahashimoto.andcode.runtime.PermissionResponse
import com.yugahashimoto.andcode.runtime.remote.RemoteOpenCodeBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

class LocalOpenCodeBackend(
    private val portProvider: () -> Int?,
    private val passwordProvider: () -> String? = { null },
    private val backendFactory: (ConnectionProfile) -> RemoteOpenCodeBackend = { profile ->
        RemoteOpenCodeBackend(profile, ProtocolDetectingOpenCodeApi(profile))
    },
) : OpenCodeBackend {
    constructor(runtimeManager: LocalRuntimeManager) : this(
        portProvider = runtimeManager::installedPort,
        passwordProvider = runtimeManager::localServerPassword,
    )

    override val id: String = "local-android"
    override val displayName: String = "Android local"
    override val kind: BackendKind = BackendKind.LOCAL

    @Volatile
    private var cached: CachedDelegate? = null
    private val lock = Any()

    internal fun delegate(): RemoteOpenCodeBackend {
        val port =
            portProvider()
                ?: error("Android local OpenCode runtime is not installed")
        val password = passwordProvider()
        cached?.takeIf { it.port == port && it.password == password }?.let { return it.backend }

        synchronized(lock) {
            cached?.takeIf { it.port == port && it.password == password }?.let { return it.backend }
            val backend =
                backendFactory(
                    ConnectionProfile(
                        id = id,
                        name = displayName,
                        baseUrl = "http://127.0.0.1:$port/",
                        username = LocalServerAuth.USERNAME,
                        password = password,
                        allowInsecureLan = true,
                    ),
                )
            cached = CachedDelegate(port, password, backend)
            return backend
        }
    }

    /**
     * [delegate], resolved off the caller's thread.
     *
     * Resolving reads the runtime metadata and the server password from disk on every call, and
     * ChatViewModel calls in from the main thread, where a slow disk showed up as an ANR in
     * Crashlytics. Only the resolution moves: the request itself still runs where the caller put it.
     */
    private suspend fun backend(): RemoteOpenCodeBackend = withContext(Dispatchers.IO) { delegate() }

    fun invalidate() {
        cached = null
    }

    /**
     * Protocol of the current local server, or null when the runtime is not installed yet or has
     * not been probed. Reads only the already-built delegate: capability checks run from Compose on
     * the main thread, and building a delegate reads runtime metadata and the password from disk.
     */
    val protocol: OpenCodeProtocol?
        get() = cached?.backend?.protocol

    override suspend fun health(): OpenCodeHealth = backend().health()

    override suspend fun listSessions(directory: String?): List<OpenCodeSession> = backend().listSessions(directory)

    override suspend fun session(sessionId: String): OpenCodeSession = backend().session(sessionId)

    override suspend fun createSession(
        title: String?,
        directory: String?,
    ): OpenCodeSession = backend().createSession(title, directory)

    override suspend fun listMessages(sessionId: String): List<OpenCodeMessage> = backend().listMessages(sessionId)

    override suspend fun deleteMessage(
        sessionId: String,
        messageId: String,
    ): Boolean = backend().deleteMessage(sessionId, messageId)

    override suspend fun listProviders(): ProviderCatalog = backend().listProviders()

    override suspend fun listAgents(): List<OpenCodeAgent> = backend().listAgents()

    override suspend fun providerAuthMethods(): Map<String, List<ProviderAuthMethod>> = backend().providerAuthMethods()

    override suspend fun authorizeProvider(
        providerId: String,
        methodIndex: Int,
        inputs: Map<String, String>,
    ): ProviderAuthAuthorization = backend().authorizeProvider(providerId, methodIndex, inputs)

    override suspend fun setProviderApiKey(
        providerId: String,
        apiKey: String,
        metadata: Map<String, String>,
    ): Boolean = backend().setProviderApiKey(providerId, apiKey, metadata)

    override suspend fun removeProviderAuth(providerId: String): Boolean = backend().removeProviderAuth(providerId)

    override suspend fun completeProviderOAuth(
        providerId: String,
        methodIndex: Int,
        code: String?,
    ): Boolean = backend().completeProviderOAuth(providerId, methodIndex, code)

    override suspend fun listProjects(directory: String?): List<OpenCodeProject> = backend().listProjects(directory)

    override suspend fun currentProject(directory: String?): OpenCodeProject = backend().currentProject(directory)

    override suspend fun pathInfo(directory: String?): OpenCodePathInfo = backend().pathInfo(directory)

    override suspend fun listFiles(
        directory: String,
        path: String,
    ): List<OpenCodeFileNode> = backend().listFiles(directory, path)

    override suspend fun readFile(
        directory: String,
        path: String,
    ): OpenCodeFileContent = backend().readFile(directory, path)

    override suspend fun fileStatus(directory: String): List<OpenCodeFileChange> = backend().fileStatus(directory)

    override suspend fun searchText(
        directory: String,
        pattern: String,
    ): List<OpenCodeSearchMatch> = backend().searchText(directory, pattern)

    override suspend fun findFiles(
        directory: String,
        query: String,
        includeDirectories: Boolean?,
        type: String?,
        limit: Int?,
    ): List<String> = backend().findFiles(directory, query, includeDirectories, type, limit)

    override suspend fun vcsInfo(directory: String): OpenCodeVcsInfo = backend().vcsInfo(directory)

    override suspend fun vcsStatus(directory: String): List<OpenCodeFileChange> = backend().vcsStatus(directory)

    override suspend fun vcsDiff(
        directory: String,
        mode: String,
        context: Int?,
    ): List<OpenCodeFileChange> = backend().vcsDiff(directory, mode, context)

    override suspend fun sessionDiff(
        sessionId: String,
        directory: String?,
        messageId: String?,
    ): List<OpenCodeFileChange> = backend().sessionDiff(sessionId, directory, messageId)

    override suspend fun sessionTodo(
        sessionId: String,
        directory: String?,
    ): List<OpenCodeTodo> = backend().sessionTodo(sessionId, directory)

    override suspend fun sendMessage(
        sessionId: String,
        request: PromptRequest,
    ) = backend().sendMessage(sessionId, request)

    override suspend fun summarizeSession(
        sessionId: String,
        providerId: String,
        modelId: String,
    ): Boolean = backend().summarizeSession(sessionId, providerId, modelId)

    override suspend fun abortSession(sessionId: String): Boolean = backend().abortSession(sessionId)

    override suspend fun renameSession(
        sessionId: String,
        title: String,
    ): OpenCodeSession = backend().renameSession(sessionId, title)

    override suspend fun deleteSession(sessionId: String): Boolean = backend().deleteSession(sessionId)

    override suspend fun respondToPermission(
        sessionId: String,
        permissionId: String,
        response: PermissionResponse,
        remember: Boolean,
    ): Boolean = backend().respondToPermission(sessionId, permissionId, response, remember)

    override suspend fun answerQuestion(
        requestId: String,
        answers: List<List<String>>,
        directory: String?,
    ): Boolean = backend().answerQuestion(requestId, answers, directory)

    override suspend fun rejectQuestion(
        requestId: String,
        directory: String?,
    ): Boolean = backend().rejectQuestion(requestId, directory)

    override suspend fun pendingQuestions(directory: String?): List<QuestionRequest> = backend().pendingQuestions(directory)

    override suspend fun archiveSession(sessionId: String): OpenCodeSession = backend().archiveSession(sessionId)

    override suspend fun mcpServers(): List<McpServer> = backend().mcpServers()

    override suspend fun addMcpServer(body: JsonObject): McpServer = backend().addMcpServer(body)

    override suspend fun connectMcpServer(name: String): Boolean = backend().connectMcpServer(name)

    override suspend fun disconnectMcpServer(name: String): Boolean = backend().disconnectMcpServer(name)

    override suspend fun removeMcpAuth(name: String): McpAuthRemoval = backend().removeMcpAuth(name)

    override suspend fun mcpAuth(name: String): McpAuthStart = backend().mcpAuth(name)

    override suspend fun mcpAuthCallback(
        name: String,
        code: String,
    ): McpAuthStatus = backend().mcpAuthCallback(name, code)

    override suspend fun config(): JsonElement = backend().config()

    override suspend fun updateConfig(patch: JsonObject): JsonElement = backend().updateConfig(patch)

    override suspend fun configProviders(): List<ConfiguredProvider> = backend().configProviders()

    override suspend fun commands(): List<OpenCodeCommand> = backend().commands()

    override suspend fun skills(): List<OpenCodeSkill> = backend().skills()

    override suspend fun executeCommand(
        sessionId: String,
        command: String,
        arguments: String,
    ) = backend().executeCommand(sessionId, command, arguments)

    override fun events(): Flow<OpenCodeEvent> = flow { emitAll(backend().events()) }

    private data class CachedDelegate(
        val port: Int,
        val password: String?,
        val backend: RemoteOpenCodeBackend,
    )
}
