package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.data.connection.SecureSettingsRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File

/** A provider OpenCode's built-in catalogue does not know about: an OpenAI-compatible endpoint. */
@Serializable
data class CustomProviderDefinition(
    val id: String,
    val name: String,
    val baseUrl: String,
    val models: List<String>,
)

internal fun CustomProviderDefinition.normalized(): CustomProviderDefinition =
    copy(
        id = id.trim(),
        name = name.trim().ifEmpty { id.trim() },
        baseUrl = baseUrl.trim(),
        models = models.map(String::trim).filter(String::isNotEmpty).distinct(),
    )

/**
 * Providers the user typed in by hand because OpenCode's own catalogue does not offer their
 * platform, stored so a reinstalled or restarted local runtime does not forget them.
 *
 * Definitions live here rather than only in the running OpenCode process because the local
 * runtime's config is regenerated from this app's state, the same reasoning behind
 * [LocalProviderCredentialStore]. [syncToRuntime] writes them into `opencode.json` as a `provider`
 * entry using OpenCode's own OpenAI-compatible AI SDK adapter, on every local runtime start.
 */
class CustomProviderStore(
    private val load: () -> List<CustomProviderDefinition>,
    private val save: (List<CustomProviderDefinition>) -> Unit,
    private val loadSyncedIds: () -> Set<String> = { emptySet() },
    private val saveSyncedIds: (Set<String>) -> Unit = {},
    private val json: Json = defaultJson,
) {
    constructor(settings: SecureSettingsRepository, json: Json = defaultJson) : this(
        load = { settings.customProviders },
        save = { settings.customProviders = it },
        loadSyncedIds = { settings.syncedCustomProviderIds },
        saveSyncedIds = { settings.syncedCustomProviderIds = it },
        json = json,
    )

    fun definitions(): List<CustomProviderDefinition> = load()

    fun upsert(definition: CustomProviderDefinition) {
        val normalized = definition.normalized()
        require(normalized.id.isNotEmpty()) { "Provider id is required" }
        require(normalized.name.isNotEmpty()) { "Provider name is required" }
        require(normalized.baseUrl.isNotEmpty()) { "Base URL is required" }
        require(normalized.models.isNotEmpty()) { "At least one model id is required" }
        save(definitions().filterNot { it.id == normalized.id } + normalized)
    }

    fun remove(id: String) {
        val normalizedId = id.trim()
        save(definitions().filterNot { it.id == normalizedId })
    }

    /**
     * Merges stored definitions into the runtime's `opencode.json`, removing only the entries this
     * store previously wrote there itself (tracked via [loadSyncedIds]/[saveSyncedIds]) so a
     * provider defined some other way — or by hand-editing the file — is left untouched.
     *
     * Through [GuestFiles], so a guest that made the file or a folder above it a link to one of
     * this app's own files gets a refusal (like an invalid file) instead of a merge into that file.
     */
    fun syncToRuntime(rootfs: File): File {
        val configFile = File(rootfs, OPENCODE_CONFIG_PATH)
        val existingRoot = readExistingPayload(rootfs)
        val providers = (existingRoot["provider"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()

        val current = definitions()
        val currentIds = current.map { it.id }.toSet()
        (loadSyncedIds() - currentIds).forEach(providers::remove)
        current.forEach { definition ->
            val configured = definition.toProviderConfig()
            val existingModels = (providers[definition.id] as? JsonObject)?.get("models") as? JsonObject
            // Starting the runtime must not erase models added outside the app, or their custom
            // limits/options. Stored definitions supply missing ids, not an authoritative allowlist.
            providers[definition.id] =
                JsonObject(
                    configured +
                        ("models" to JsonObject(configured["models"]!!.jsonObject + existingModels.orEmpty())),
                )
        }

        val updatedRoot = JsonObject(existingRoot + ("provider" to JsonObject(providers)))
        if (updatedRoot != existingRoot) {
            GuestFiles.write(rootfs, OPENCODE_CONFIG_PATH, json.encodeToString(updatedRoot), GuestFiles.OWNER_READ_WRITE)
        }
        saveSyncedIds(currentIds)
        return configFile
    }

    private fun readExistingPayload(rootfs: File): JsonObject {
        val existing = GuestFiles.readText(rootfs, OPENCODE_CONFIG_PATH) ?: return JsonObject(emptyMap())
        return runCatching {
            json.parseToJsonElement(existing).jsonObject
        }.getOrElse { error ->
            throw IllegalStateException(
                "Existing OpenCode opencode.json is invalid and was not modified",
                error,
            )
        }
    }

    companion object {
        private const val OPENCODE_CONFIG_PATH = "root/.config/opencode/opencode.json"

        private val defaultJson: Json =
            Json {
                ignoreUnknownKeys = true
                isLenient = true
                encodeDefaults = true
            }

        fun encodeList(
            definitions: List<CustomProviderDefinition>,
            json: Json = defaultJson,
        ): String = json.encodeToString(definitions)

        fun decodeList(
            raw: String?,
            json: Json = defaultJson,
        ): List<CustomProviderDefinition> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching { json.decodeFromString<List<CustomProviderDefinition>>(raw) }.getOrDefault(emptyList())
        }
    }
}

/** The OpenCode `provider.<id>` config shape for this definition, using its OpenAI-compatible AI SDK adapter. */
internal fun CustomProviderDefinition.toProviderConfig(): JsonObject =
    buildJsonObject {
        put("npm", "@ai-sdk/openai-compatible")
        put("name", name)
        put("options", buildJsonObject { put("baseURL", baseUrl) })
        put(
            "models",
            buildJsonObject {
                models.forEach { modelId -> put(modelId, buildJsonObject { put("name", modelId) }) }
            },
        )
    }
