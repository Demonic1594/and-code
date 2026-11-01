package com.yugahashimoto.andcode.runtime.local

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * Installs AndCode's Claude Code hooks into the guest settings and binary path: the PermissionRequest
 * hook that asks the phone, and the same script as a PreToolUse hook for Bash, which refuses blocked
 * commands before every call whatever the permission mode.
 */
object ClaudePermissionHooks {
    const val HOOK_GUEST_PATH = "/usr/local/bin/and-code-claude-permission-hook.sh"
    const val HOOK_MARKER = "and-code-claude-permission"

    /**
     * How long Claude Code lets the hook block a tool call, in seconds. A question may wait almost
     * an hour for a user who is away from the device; the old six minutes expired long before
     * that, denying the turn while the card was still on its way. The hook script's own timeout
     * stays just under this so it gets to deny gracefully first.
     */
    const val HOOK_TIMEOUT_SEC = 3600

    /** The blocking check reads a small file and answers at once. */
    const val BLOCK_CHECK_TIMEOUT_SEC = 30
    const val BLOCK_CHECK_COMMAND = "$HOOK_GUEST_PATH --pre-tool-use"
    private const val SETTINGS_RELATIVE = "root/.claude/settings.json"
    private const val HOOK_RELATIVE = "usr/local/bin/and-code-claude-permission-hook.sh"

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            prettyPrint = true
        }

    fun settingsFragment(): String =
        """
        {
          "hooks": {
            "PermissionRequest": [
              {
                "matcher": "*",
                "hooks": [
                  {
                    "type": "command",
                    "command": "$HOOK_GUEST_PATH",
                    "timeout": $HOOK_TIMEOUT_SEC,
                    "statusMessage": "Waiting for AndCode approval"
                  }
                ]
              }
            ],
            "PreToolUse": [
              {
                "matcher": "Bash",
                "hooks": [
                  {
                    "type": "command",
                    "command": "$BLOCK_CHECK_COMMAND",
                    "timeout": $BLOCK_CHECK_TIMEOUT_SEC
                  }
                ]
              }
            ]
          }
        }
        """.trimIndent()

    /** [groups] with AndCode's own earlier groups replaced by [ours]; the user's own hooks stay. */
    private fun withOurGroup(
        groups: kotlinx.serialization.json.JsonElement?,
        ours: JsonObject,
    ): JsonArray {
        val kept =
            (groups as? JsonArray).orEmpty().filterNot { group ->
                (group as? JsonObject)?.get("hooks")?.jsonArray?.any { handler ->
                    handler.jsonObject["command"]?.jsonPrimitive?.contentOrNull?.contains(HOOK_MARKER) == true
                } == true
            }
        return JsonArray(kept + ours)
    }

    private fun hookGroup(
        matcher: String,
        command: String,
        timeout: Int,
        statusMessage: String? = null,
    ): JsonObject =
        buildJsonObject {
            put("matcher", matcher)
            put(
                "hooks",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "command")
                            put("command", command)
                            put("timeout", timeout)
                            statusMessage?.let { put("statusMessage", it) }
                        },
                    )
                },
            )
        }

    fun mergeSettingsJson(existing: String?): String {
        val root =
            if (existing.isNullOrBlank()) {
                buildJsonObject {}
            } else {
                runCatching { json.parseToJsonElement(existing).jsonObject }
                    .getOrDefault(buildJsonObject {})
            }
        val hooks = root["hooks"]?.jsonObject ?: buildJsonObject {}
        val permissionGroups =
            withOurGroup(
                hooks["PermissionRequest"],
                hookGroup("*", HOOK_GUEST_PATH, HOOK_TIMEOUT_SEC, "Waiting for AndCode approval"),
            )
        val blockGroups = withOurGroup(hooks["PreToolUse"], hookGroup("Bash", BLOCK_CHECK_COMMAND, BLOCK_CHECK_TIMEOUT_SEC))
        val mergedHooks =
            buildJsonObject {
                hooks.forEach { (key, value) ->
                    if (key != "PermissionRequest" && key != "PreToolUse") put(key, value)
                }
                put("PermissionRequest", permissionGroups)
                put("PreToolUse", blockGroups)
            }
        return json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                root.forEach { (key, value) ->
                    if (key != "hooks") put(key, value)
                }
                put("hooks", mergedHooks)
            },
        )
    }

    /**
     * Writes the hook script and merges settings into [rootfs].
     *
     * @return true when the guest is ready for interactive approvals.
     */
    fun installInto(
        rootfs: File,
        hookScript: String,
    ): Boolean {
        return runCatching {
            val scriptFile = File(rootfs, HOOK_RELATIVE).apply { parentFile?.mkdirs() }
            scriptFile.writeText(hookScript)
            scriptFile.setExecutable(true, false)
            val settingsFile = File(rootfs, SETTINGS_RELATIVE).apply { parentFile?.mkdirs() }
            val existing = settingsFile.takeIf { it.isFile }?.readText()
            settingsFile.writeText(mergeSettingsJson(existing))
            scriptFile.isFile && settingsFile.isFile
        }.getOrDefault(false)
    }

    fun isInstalled(rootfs: File): Boolean {
        val script = File(rootfs, HOOK_RELATIVE)
        val settings = File(rootfs, SETTINGS_RELATIVE)
        if (!script.isFile) return false
        val text = settings.takeIf { it.isFile }?.readText().orEmpty()
        return text.contains(HOOK_MARKER)
    }
}
