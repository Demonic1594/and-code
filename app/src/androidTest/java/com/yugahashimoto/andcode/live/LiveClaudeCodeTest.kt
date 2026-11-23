package com.yugahashimoto.andcode.live

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.yugahashimoto.andcode.runtime.LocalAgent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Claude Code on the device with an Anthropic API key (secret `ANTHROPIC_API_KEY`).
 *
 * The app signs Claude Code in with an account; an API-key user sets `ANTHROPIC_API_KEY` in Claude
 * Code's own `~/.claude/settings.json` `env` instead, which is what this does, keeping the rest of
 * the file (the app's permission hooks) as it is.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class LiveClaudeCodeTest {
    private var settingsFile: File? = null

    @Before
    fun setUp() {
        val key = LiveAgents.requireKey(LiveAgents.ARG_ANTHROPIC_KEY)
        LiveAgents.ensureRuntimeInstalled()
        val rootfs = checkNotNull(LiveAgents.app.localRuntimeInstaller.installedRuntime()).rootfs
        val file = File(rootfs, SETTINGS_PATH).also { settingsFile = it }
        writeEnv(file) { env -> env + (ENV_NAME to JsonPrimitive(key)) }
        LiveAgents.app.preferences.setAutoAcceptPermissions(true)
    }

    @After
    fun tearDown() {
        settingsFile?.let { file -> writeEnv(file) { env -> env - ENV_NAME } }
    }

    @Test
    fun createsTheFileItIsAskedFor() {
        LiveAgents.assertCreatesHelloFile(LocalAgent.CLAUDE_CODE)
    }

    private fun writeEnv(
        file: File,
        change: (Map<String, kotlinx.serialization.json.JsonElement>) -> Map<String, kotlinx.serialization.json.JsonElement>,
    ) {
        val root = file.takeIf { it.isFile }?.readText()?.let { Json.parseToJsonElement(it).jsonObject } ?: JsonObject(emptyMap())
        val env = root["env"]?.jsonObject ?: JsonObject(emptyMap())
        val updated = JsonObject(root + ("env" to JsonObject(change(env))))
        file.parentFile?.mkdirs()
        file.writeText(Json.encodeToString(JsonObject.serializer(), updated))
    }

    private companion object {
        const val SETTINGS_PATH = "root/.claude/settings.json"
        const val ENV_NAME = "ANTHROPIC_API_KEY"
    }
}
