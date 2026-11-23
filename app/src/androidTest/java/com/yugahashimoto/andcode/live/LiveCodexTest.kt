package com.yugahashimoto.andcode.live

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.yugahashimoto.andcode.runtime.LocalAgent
import com.yugahashimoto.andcode.runtime.local.CodexModels
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Codex on the device with an OpenAI API key (secret `OPENAI_API_KEY`), signed in the way
 * Settings' "API key" sign-in does: `codex login --with-api-key` inside the sandbox.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class LiveCodexTest {
    @Before
    fun setUp() {
        val key = LiveAgents.requireKey(LiveAgents.ARG_OPENAI_KEY)
        LiveAgents.ensureRuntimeInstalled()
        val signedIn = runBlocking { LiveAgents.app.codexTarget.setProviderApiKey(CodexModels.PROVIDER_ID, key, emptyMap()) }
        assertTrue("Codex did not accept the API key", signedIn)
        LiveAgents.app.preferences.setAutoAcceptPermissions(true)
    }

    @After
    fun tearDown() {
        if (LiveAgents.argument(LiveAgents.ARG_OPENAI_KEY) != null) {
            runBlocking { runCatching { LiveAgents.app.codexTarget.removeProviderAuth(CodexModels.PROVIDER_ID) } }
        }
    }

    @Test
    fun createsTheFileItIsAskedFor() {
        LiveAgents.assertCreatesHelloFile(LocalAgent.CODEX)
    }
}
