package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.PermissionRequest
import com.yugahashimoto.andcode.core.api.QuestionOption
import com.yugahashimoto.andcode.core.api.QuestionPrompt
import com.yugahashimoto.andcode.core.api.QuestionRequest
import com.yugahashimoto.andcode.runtime.PermissionResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * File bridge between Claude Code's PermissionRequest hook (guest) and the Android approval UI.
 *
 * The hook writes a request under [hostRoot]/pending], the app surfaces it as a
 * [PermissionRequest]/[QuestionRequest], and [respond]/[answerQuestion] writes the matching
 * response the hook is polling for.
 *
 * It also keeps the user's command rules: "always allow" rules the hook applies without asking, and
 * blocking rules it applies first, so a blocked command is refused before anything else is asked.
 */
class ClaudePermissionBridge(
    hostRoot: File,
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        },
) {
    enum class Kind {
        PERMISSION,
        QUESTION,
        ELICITATION,
    }

    data class Request(
        val requestId: String,
        val androidSessionId: String,
        val kind: Kind,
        val toolName: String,
        val toolInputJson: String,
        val permissionLabel: String,
        val claudeSessionId: String? = null,
        /** The guest directory the call runs in, as the hook reported it; an always-allow rule is kept for it. */
        val cwd: String? = null,
    )

    @Serializable
    data class StoredRequest(
        val v: Int = 1,
        val kind: String,
        val requestId: String,
        val androidSessionId: String,
        val claudeSessionId: String? = null,
        val toolName: String,
        val toolInput: JsonElement = JsonObject(emptyMap()),
        val permissionLabel: String = "",
        val createdAtMs: Long = System.currentTimeMillis(),
        val cwd: String? = null,
    )

    @Serializable
    data class StoredResponse(
        val v: Int = 1,
        val decision: String,
        val remember: Boolean = false,
        val message: String? = null,
        val updatedInput: JsonElement? = null,
        val answers: JsonElement? = null,
    ) {
        val answersJson: String?
            get() = answers?.toString()
    }

    @Serializable
    private data class AlwaysRule(
        val toolName: String,
        val commandPrefix: String? = null,
        /**
         * The guest project directory the rule applies in (and below); null applies everywhere, as
         * every rule did before rules were kept per project. Blocking rules are always everywhere.
         */
        val project: String? = null,
    )

    @Serializable
    private data class AlwaysRulesFile(
        val rules: List<AlwaysRule> = emptyList(),
    )

    /** A remembered command rule as the settings list it. */
    data class CommandRule(
        val toolName: String,
        /** The command's words the rule names; null for an always-allow rule covering the whole tool. */
        val words: String?,
        /** True for a blocking rule, false for an always-allow one. */
        val blocks: Boolean,
        /** The project directory an always-allow rule is kept for; null when it applies everywhere. */
        val project: String? = null,
    )

    private val root = hostRoot.apply { mkdirs() }
    private val pendingDir = File(root, "pending").apply { mkdirs() }
    private val responsesDir = File(root, "responses").apply { mkdirs() }
    private val alwaysFile = File(root, "always-rules.json")
    private val blockFile = File(root, BLOCK_RULES_FILE)

    /** Kinds of calls (reading, editing files) the user allows without asking, in every project. */
    val toolClasses = ToolClassRules(root)
    private val mutableRules = MutableStateFlow(loadRules())

    /** Every remembered rule, blocking ones first. */
    val rules: StateFlow<List<CommandRule>> = mutableRules.asStateFlow()
    private val emitted = ConcurrentHashMap.newKeySet<String>()

    fun writeGuestRequest(request: Request) {
        val toolInput =
            runCatching { json.parseToJsonElement(request.toolInputJson) }
                .getOrDefault(JsonObject(emptyMap()))
        val stored =
            StoredRequest(
                kind = request.kind.name.lowercase(),
                requestId = request.requestId,
                androidSessionId = request.androidSessionId,
                claudeSessionId = request.claudeSessionId,
                toolName = request.toolName,
                toolInput = toolInput,
                permissionLabel = request.permissionLabel.ifBlank { request.toolName },
                cwd = request.cwd,
            )
        val file = File(pendingDir, "${request.requestId}.json")
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(stored))
    }

    fun pollPending(): List<Request> {
        val out = mutableListOf<Request>()
        for (stored in readPendingFiles()) {
            if (!emitted.add(stored.requestId)) continue
            out += toRequest(stored)
        }
        return out
    }

    /**
     * Everything still waiting on disk, regardless of whether it was already reported as an event.
     *
     * Recovery needs this: a request whose event was missed — the app showed another chat, or was
     * restarted — is only findable through its pending file. Unlike [pollPending] it does not mark
     * requests as emitted, so the watcher still emits them as events afterwards.
     */
    fun pendingRequests(): List<Request> = readPendingFiles().map(::toRequest)

    private fun readPendingFiles(): List<StoredRequest> =
        pendingDir
            .listFiles().orEmpty()
            .filter { it.extension == "json" }
            .sortedBy { it.name }
            .mapNotNull { file -> runCatching { json.decodeFromString<StoredRequest>(file.readText()) }.getOrNull() }

    private fun toRequest(stored: StoredRequest): Request =
        Request(
            requestId = stored.requestId,
            androidSessionId = stored.androidSessionId,
            kind =
                when (stored.kind.lowercase()) {
                    "question" -> Kind.QUESTION
                    "elicitation" -> Kind.ELICITATION
                    else -> Kind.PERMISSION
                },
            toolName = stored.toolName,
            toolInputJson = stored.toolInput.toString(),
            permissionLabel = stored.permissionLabel.ifBlank { stored.toolName },
            claudeSessionId = stored.claudeSessionId,
        )

    fun respond(
        requestId: String,
        response: PermissionResponse,
        remember: Boolean,
        message: String? = null,
    ): Boolean {
        val pending = File(pendingDir, "$requestId.json")
        if (!pending.isFile && !File(responsesDir, "$requestId.json").isFile) {
            // Still allow writing if the guest already cleaned pending but we know the id.
            if (!emitted.contains(requestId) && !pending.isFile) return false
        }
        val storedRequest =
            pending.takeIf { it.isFile }?.let {
                runCatching { json.decodeFromString<StoredRequest>(it.readText()) }.getOrNull()
            }
        // Blocked since it reached the approval sheet: an approval still refuses it.
        val blocked = storedRequest?.let { blockedBy(it.toolName, it.toolInput.toString()) }
        val decision =
            when {
                blocked != null || response == PermissionResponse.REJECT -> "deny"
                else -> "allow"
            }
        val rememberFlag = blocked == null && (remember || response == PermissionResponse.ALWAYS)
        if (rememberFlag && storedRequest != null) {
            rememberRule(storedRequest)
        }
        writeResponse(
            requestId,
            StoredResponse(
                decision = decision,
                remember = rememberFlag,
                message =
                    blocked?.let {
                        blockedMessage(
                            it.words.orEmpty(),
                        )
                    } ?: message ?: if (decision == "deny") DECLINED_MESSAGE else null,
            ),
        )
        return true
    }

    fun answerQuestion(
        requestId: String,
        questionsJson: String,
        answers: Map<String, String>,
    ): Boolean {
        val questions =
            runCatching { json.parseToJsonElement(questionsJson) }
                .getOrDefault(JsonArray(emptyList()))
        val answersElement =
            buildJsonObject {
                answers.forEach { (key, value) -> put(key, value) }
            }
        writeResponse(
            requestId,
            StoredResponse(
                decision = "allow",
                updatedInput =
                    buildJsonObject {
                        put("questions", questions)
                        put("answers", answersElement)
                    },
                answers = answersElement,
            ),
        )
        return true
    }

    fun readResponse(requestId: String): StoredResponse? {
        val file = File(responsesDir, "$requestId.json")
        if (!file.isFile) return null
        return runCatching { json.decodeFromString<StoredResponse>(file.readText()) }.getOrNull()
    }

    fun readPending(requestId: String): Request? {
        val file = File(pendingDir, "$requestId.json")
        if (!file.isFile) return null
        val stored = runCatching { json.decodeFromString<StoredRequest>(file.readText()) }.getOrNull() ?: return null
        return Request(
            requestId = stored.requestId,
            androidSessionId = stored.androidSessionId,
            kind =
                when (stored.kind.lowercase()) {
                    "question" -> Kind.QUESTION
                    "elicitation" -> Kind.ELICITATION
                    else -> Kind.PERMISSION
                },
            toolName = stored.toolName,
            toolInputJson = stored.toolInput.toString(),
            permissionLabel = stored.permissionLabel.ifBlank { stored.toolName },
            claudeSessionId = stored.claudeSessionId,
            cwd = stored.cwd,
        )
    }

    fun isAlwaysAllowed(
        toolName: String,
        toolInputJson: String,
        cwd: String? = null,
    ): Boolean {
        if (blockedBy(toolName, toolInputJson) != null) return false
        val rules = loadAlwaysRules()
        val command =
            runCatching {
                json.parseToJsonElement(toolInputJson).jsonObject["command"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
        // A tool-wide rule covers calls without a command; a call with one needs a rule naming its
        // first words, matched by ShellCommandRules so `git status; rm -rf ~` is not `git status`.
        return rules.any { rule ->
            val prefix = rule.commandPrefix
            rule.toolName == toolName && appliesIn(rule.project, cwd) &&
                if (command == null) prefix.isNullOrBlank() else !prefix.isNullOrBlank() && ShellCommandRules.matches(prefix, command)
        }
    }

    fun toPermissionRequest(request: Request): PermissionRequest =
        PermissionRequest(
            id = request.requestId,
            sessionId = request.androidSessionId,
            permission = request.permissionLabel.ifBlank { request.toolName },
            patterns = listOf(request.toolName),
            // "Always allow" has to be able to keep something: a call with no command becomes a
            // tool-wide rule, and a command only has a rule to keep when it is one program with its
            // arguments - one that chains, pipes, substitutes, redirects or runs other code is
            // approved once whatever the user taps ([rememberRule]). A blocked command is never
            // allowed at all, so it is not offered either.
            canAlwaysAllow =
                runCatching { json.parseToJsonElement(request.toolInputJson).jsonObject["command"] }
                    .getOrNull()
                    .let { element ->
                        when {
                            element == null -> true
                            // A command that is not a string has no rule to keep (rememberRule skips it).
                            element !is JsonPrimitive || !element.isString -> false
                            else ->
                                blockedByCommand(request.toolName, element.content) == null &&
                                    ShellCommandRules.ruleFor(element.content) != null
                        }
                    },
            metadata =
                mapOf(
                    "toolName" to JsonPrimitive(request.toolName),
                    "toolInput" to runCatching { json.parseToJsonElement(request.toolInputJson) }.getOrDefault(JsonObject(emptyMap())),
                    "kind" to JsonPrimitive(request.kind.name.lowercase()),
                ),
        )

    fun toQuestionRequest(request: Request): QuestionRequest? {
        if (request.kind != Kind.QUESTION) return null
        val root = runCatching { json.parseToJsonElement(request.toolInputJson).jsonObject }.getOrNull() ?: return null
        val questions =
            root["questions"]?.jsonArray?.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val question = obj["question"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val options =
                    obj["options"]?.jsonArray?.mapNotNull { opt ->
                        val o = opt as? JsonObject ?: return@mapNotNull null
                        val label = o["label"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        QuestionOption(label = label, description = o["description"]?.jsonPrimitive?.contentOrNull)
                    }.orEmpty()
                QuestionPrompt(
                    question = question,
                    header = obj["header"]?.jsonPrimitive?.contentOrNull,
                    options = options,
                    multiple = obj["multiSelect"]?.jsonPrimitive?.contentOrNull == "true",
                    custom = true,
                )
            }.orEmpty()
        if (questions.isEmpty()) return null
        return QuestionRequest(id = request.requestId, sessionId = request.androidSessionId, questions = questions)
    }

    private fun writeResponse(
        requestId: String,
        response: StoredResponse,
    ) {
        responsesDir.mkdirs()
        val file = File(responsesDir, "$requestId.json")
        val tmp = File(responsesDir, "$requestId.json.tmp")
        tmp.writeText(json.encodeToString(response))
        if (!tmp.renameTo(file)) {
            file.writeText(json.encodeToString(response))
            tmp.delete()
        }
    }

    @Synchronized
    private fun rememberRule(stored: StoredRequest) {
        val element = (stored.toolInput as? JsonObject)?.get("command")
        // A command that is not a string (an object, null) is approved once and never remembered:
        // reading it as text would throw before the answer is written, and leaving it out would
        // make a tool-wide rule.
        if (element != null && (element !is JsonPrimitive || !element.isString)) return
        val command = (element as? JsonPrimitive)?.content
        // A command that chains, pipes or redirects is approved once but never remembered: there is no
        // safe rule to keep for it, and a tool-wide rule would allow every command.
        val prefix =
            if (command == null) {
                null
            } else {
                ShellCommandRules.ruleFor(command) ?: return
            }
        // A blocked command is never allowed for good, even if it was let through once.
        if (command != null && blockedByCommand(stored.toolName, command) != null) return
        val existing = loadAlwaysRules().toMutableList()
        // Kept for the project it was approved in; a request without a directory keeps the old,
        // everywhere rule rather than none.
        val rule = AlwaysRule(toolName = stored.toolName, commandPrefix = prefix, project = projectOf(stored.cwd))
        if (existing.none { it.toolName == rule.toolName && it.commandPrefix == rule.commandPrefix && it.project == rule.project }) {
            existing += rule
            writeRules(alwaysFile, existing)
        }
    }

    /**
     * Blocks shell commands containing the words of [text] (`rm -rf`, `git push --force`). Returns
     * false when [text] names no single command, or when the rules file exists but cannot be read
     * (it is left as it is rather than replaced).
     */
    @Synchronized
    fun block(text: String): Boolean {
        val words = ShellCommandRules.blockRuleFor(text) ?: return false
        val existing = readRulesStrictly(blockFile) ?: return false
        if (existing.none { it.toolName == SHELL_TOOL && it.commandPrefix == words }) {
            writeRules(blockFile, existing + AlwaysRule(toolName = SHELL_TOOL, commandPrefix = words))
        }
        return true
    }

    /** Forgets [rule], whichever kind it is. */
    @Synchronized
    fun forget(rule: CommandRule) {
        val file = if (rule.blocks) blockFile else alwaysFile
        val existing = readRulesStrictly(file) ?: return
        val kept =
            existing.filterNot {
                it.toolName == rule.toolName && it.commandPrefix.orEmpty() == rule.words.orEmpty() &&
                    (rule.blocks || it.project == rule.project)
            }
        if (kept.size != existing.size) writeRules(file, kept)
    }

    /** The blocking rule refusing a [toolName] call with [toolInputJson], or null when none does. */
    fun blockedBy(
        toolName: String,
        toolInputJson: String,
    ): CommandRule? {
        val command =
            runCatching {
                json.parseToJsonElement(toolInputJson).jsonObject["command"]?.jsonPrimitive?.contentOrNull
            }.getOrNull() ?: return null
        return blockedByCommand(toolName, command)
    }

    private fun blockedByCommand(
        toolName: String,
        command: String,
    ): CommandRule? =
        loadBlockRules()
            .firstOrNull { rule ->
                rule.toolName == toolName && !rule.commandPrefix.isNullOrBlank() && ShellCommandRules.blocks(rule.commandPrefix, command)
            }?.let { CommandRule(it.toolName, it.commandPrefix, blocks = true) }

    private fun writeRules(
        file: File,
        rules: List<AlwaysRule>,
    ) {
        // No file means no rules, and the hook leaves at once without one: an empty list must not
        // keep it reading (or, without jq, refusing) every command.
        if (rules.isEmpty()) {
            file.delete()
            mutableRules.value = loadRules()
            return
        }
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(AlwaysRulesFile(rules)))
        if (!tmp.renameTo(file)) {
            file.writeText(json.encodeToString(AlwaysRulesFile(rules)))
            tmp.delete()
        }
        mutableRules.value = loadRules()
    }

    private fun loadRules(): List<CommandRule> =
        loadBlockRules().map { CommandRule(it.toolName, it.commandPrefix, blocks = true) } +
            loadAlwaysRules().map { CommandRule(it.toolName, it.commandPrefix, blocks = false, project = it.project) }

    private fun loadAlwaysRules(): List<AlwaysRule> = readRules(alwaysFile)

    private fun loadBlockRules(): List<AlwaysRule> = readRules(blockFile)

    /** The rules in [file], or null when it exists but cannot be read, so it is never overwritten. */
    private fun readRulesStrictly(file: File): List<AlwaysRule>? {
        if (!file.exists()) return emptyList()
        return runCatching { json.decodeFromString<AlwaysRulesFile>(file.readText()).rules }.getOrNull()
    }

    private fun readRules(file: File): List<AlwaysRule> {
        if (!file.isFile) return emptyList()
        return runCatching { json.decodeFromString<AlwaysRulesFile>(file.readText()).rules }
            .getOrDefault(emptyList())
    }

    companion object {
        const val GUEST_BRIDGE_PATH = "/root/.andcode/claude-bridge"
        const val HOST_DIR_NAME = "claude-bridge"

        /** Blocking rules, in the same shape as the always-allow file; the hook reads both. */
        const val BLOCK_RULES_FILE = "block-rules.json"

        /** The tool whose calls carry a shell command, the only kind a blocking rule names. */
        const val SHELL_TOOL = "Bash"

        /**
         * The project directory a rule approved in [cwd] is kept for, trailing slash dropped; null
         * (everywhere) for none, a relative path, or the root, which would cover every project.
         */
        fun projectOf(cwd: String?): String? =
            // Not trimmed: spaces belong to a directory's name, and the hook compares the path as is.
            cwd
                ?.trimEnd('/')
                ?.takeIf { it.startsWith("/") && it.length > 1 }

        /** Whether a rule kept for [project] applies to a call in [cwd]: that directory or one inside it. */
        fun appliesIn(
            project: String?,
            cwd: String?,
        ): Boolean {
            if (project == null) return true
            val dir = projectOf(cwd) ?: return false
            // `/proj/../other` would read as inside /proj; a path that climbs is never inside one.
            if (dir.split('/').contains("..")) return false
            return dir == project || dir.startsWith("$project/")
        }

        /** What the agent reads when a blocking rule refuses its call; the hook sends the same text. */
        fun blockedMessage(words: String): String =
            "The user blocked commands containing \"$words\" in AndCode, so this was not run. " +
                "Do not retry it or work around the block; continue another way or ask the user."

        /**
         * What the agent reads when the user rejects a call: that it was the user, and not to retry
         * the same call, which agents otherwise tend to do in a loop. The guest hook sends the same
         * text when it has no message of its own.
         */
        const val DECLINED_MESSAGE =
            "The user declined this action. Do not retry the same call; adjust your approach or ask the user what they want."
    }
}
