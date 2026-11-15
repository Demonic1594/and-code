package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.data.connection.SecureSettingsRepository
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File

class LocalProviderCredentialStore(
    private val load: () -> Map<String, String>,
    private val save: (Map<String, String>) -> Unit,
    private val loadManagedProviderIds: () -> Set<String> = { load().keys },
    private val saveManagedProviderIds: (Set<String>) -> Unit = {},
    private val json: Json = defaultJson,
) {
    constructor(settings: SecureSettingsRepository, json: Json = defaultJson) : this(
        load = { settings.providerApiKeys() },
        save = { settings.providerApiKeys = it },
        loadManagedProviderIds = {
            if (settings.hasManagedProviderApiKeyIds) {
                settings.managedProviderApiKeyIds
            } else {
                settings.providerApiKeys().keys
            }
        },
        saveManagedProviderIds = { settings.managedProviderApiKeyIds = it },
        json = json,
    )

    fun credentials(): Map<String, String> = load()

    fun managedProviderIds(): Set<String> =
        loadManagedProviderIds()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toSet()

    fun setCredential(
        providerId: String,
        apiKey: String?,
    ) {
        val normalizedId = providerId.trim()
        require(normalizedId.isNotEmpty()) { "Provider id is required" }

        val updatedCredentials = credentials().toMutableMap()
        val normalizedKey = apiKey?.trim().orEmpty()
        if (normalizedKey.isEmpty()) {
            updatedCredentials.remove(normalizedId)
        } else {
            updatedCredentials[normalizedId] = normalizedKey
        }
        val updatedManagedIds = managedProviderIds() + normalizedId

        save(updatedCredentials)
        saveManagedProviderIds(updatedManagedIds)
    }

    fun clearCredential(providerId: String) = setCredential(providerId, null)

    fun unmanageProvider(providerId: String) {
        val normalizedId = providerId.trim()
        if (normalizedId.isEmpty()) return
        saveManagedProviderIds(managedProviderIds() - normalizedId)
    }

    fun hasCredential(providerId: String): Boolean = !credentials()[providerId.trim()].isNullOrBlank()

    /**
     * Merges the managed providers' keys into the guest's `auth.json`, keeping every other entry.
     *
     * Through [GuestFiles]: the guest can make `auth.json`, or a folder above it, a link to one of
     * this app's own files, which would otherwise be read in here and written back out with the
     * keys merged into it. A linked `auth.json` is refused like an invalid one, and left alone.
     */
    fun syncToRuntime(rootfs: File): File {
        val authFile = File(rootfs, AUTH_PATH)
        val managedIds = managedProviderIds()
        if (managedIds.isEmpty()) return authFile

        val existingPayload = readExistingPayload(rootfs)
        val currentCredentials = credentials()
        val mutableEntries = existingPayload.toMutableMap()
        managedIds.forEach { providerId ->
            val apiKey = currentCredentials[providerId]?.trim().orEmpty()
            if (apiKey.isEmpty()) {
                mutableEntries.remove(providerId)
            } else {
                mutableEntries[providerId] =
                    buildJsonObject {
                        put("type", "api")
                        put("key", apiKey)
                    }
            }
        }
        GuestFiles.write(rootfs, AUTH_PATH, json.encodeToString<JsonObject>(JsonObject(mutableEntries)), GuestFiles.OWNER_READ_WRITE)
        return authFile
    }

    private fun readExistingPayload(rootfs: File): JsonObject {
        val existing = GuestFiles.readText(rootfs, AUTH_PATH) ?: return JsonObject(emptyMap())
        return runCatching {
            json.parseToJsonElement(existing).jsonObject
        }.getOrElse { error ->
            throw IllegalStateException(
                "Existing OpenCode auth.json is invalid and was not modified",
                error,
            )
        }
    }

    companion object {
        private const val AUTH_PATH = "root/.local/share/opencode/auth.json"

        private val defaultJson: Json =
            Json {
                ignoreUnknownKeys = true
                isLenient = true
                encodeDefaults = true
            }

        fun decodeMap(
            raw: String?,
            json: Json = defaultJson,
        ): Map<String, String> {
            if (raw.isNullOrBlank()) return emptyMap()
            return runCatching {
                json.decodeFromString<Map<String, String>>(raw)
                    .mapKeys { it.key.trim() }
                    .filter { it.key.isNotEmpty() && it.value.isNotBlank() }
                    .mapValues { it.value.trim() }
            }.getOrDefault(emptyMap())
        }

        fun encodeMap(
            map: Map<String, String>,
            json: Json = defaultJson,
        ): String = json.encodeToString(map)
    }
}
