package com.yugahashimoto.andcode.core.api

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The two message shapes a server sends its model in, verified against the local runtime's own
 * store (OpenCode 1.18.x): assistant messages carry top-level `modelID`/`providerID`, user
 * messages a nested `model` object. The usage breakdown reads assistant turns, so a decoder
 * that only reads the nested object labels every local turn "Unknown model".
 */
class OpenCodeMessageModelDecodeTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun decode(body: String): OpenCodeMessageInfo = json.decodeFromString(body)

    @Test
    fun `a nested model object is read as-is`() {
        val info =
            decode(
                """{"id":"m1","sessionID":"s1","role":"user",
                   "model":{"providerID":"zai-coding-plan","modelID":"glm-5.3"}}""",
            )

        assertEquals(OpenCodeModelReference("zai-coding-plan", "glm-5.3"), info.effectiveModel)
    }

    @Test
    fun `top-level model fields are read when the nested object is absent`() {
        val info =
            decode(
                """{"id":"m2","sessionID":"s1","role":"assistant",
                   "providerID":"zai-coding-plan","modelID":"glm-5.3","cost":0}""",
            )

        assertEquals(OpenCodeModelReference("zai-coding-plan", "glm-5.3"), info.effectiveModel)
    }

    @Test
    fun `the nested object wins over stray top-level fields`() {
        val info =
            decode(
                """{"id":"m3","sessionID":"s1","role":"assistant",
                   "providerID":"other","modelID":"other-model",
                   "model":{"providerID":"zai-coding-plan","modelID":"glm-5.3"}}""",
            )

        assertEquals(OpenCodeModelReference("zai-coding-plan", "glm-5.3"), info.effectiveModel)
    }

    @Test
    fun `a message with no model in either shape has none`() {
        val info = decode("""{"id":"m4","sessionID":"s1","role":"assistant","cost":0}""")

        assertNull(info.effectiveModel)
    }
}
