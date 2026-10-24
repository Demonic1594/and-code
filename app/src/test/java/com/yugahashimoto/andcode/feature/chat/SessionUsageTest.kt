package com.yugahashimoto.andcode.feature.chat

import com.yugahashimoto.andcode.core.api.OpenCodeCacheTokens
import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodeMessageInfo
import com.yugahashimoto.andcode.core.api.OpenCodeSessionTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionUsageTest {
    private fun message(
        role: String,
        tokens: OpenCodeSessionTokens? = null,
        cost: Double? = null,
    ) = OpenCodeMessage(OpenCodeMessageInfo(id = role + tokens.hashCode(), sessionId = "s", role = role, tokens = tokens, cost = cost))

    @Test
    fun `sums assistant turns and ignores user messages`() {
        val usage =
            summarizeSessionUsage(
                listOf(
                    message("user", OpenCodeSessionTokens(input = 999)),
                    message(
                        "assistant",
                        OpenCodeSessionTokens(input = 100, output = 20, cache = OpenCodeCacheTokens(read = 5)),
                        cost = 0.01,
                    ),
                    message("assistant", OpenCodeSessionTokens(input = 200, output = 30, reasoning = 7), cost = 0.02),
                ),
            )!!
        assertEquals(300L, usage.inputTokens)
        assertEquals(50L, usage.outputTokens)
        assertEquals(7L, usage.reasoningTokens)
        assertEquals(5L, usage.cacheReadTokens)
        assertEquals(362L, usage.totalTokens)
        assertEquals(0.03, usage.costUsd!!, 1e-9)
    }

    @Test
    fun `no tokens reported means no usage`() {
        assertNull(summarizeSessionUsage(listOf(message("assistant"))))
    }

    @Test
    fun `cost stays unknown when no turn reports it`() {
        assertNull(summarizeSessionUsage(listOf(message("assistant", OpenCodeSessionTokens(input = 1))))!!.costUsd)
    }

    @Test
    fun `formats tokens and dollars compactly`() {
        assertEquals("950", formatUsageTokens(950))
        assertEquals("12.3k", formatUsageTokens(12_345))
        assertEquals("4.1M", formatUsageTokens(4_100_000))
        assertEquals("$1.25", formatUsd(1.25))
        assertEquals("$0.0034", formatUsd(0.0034))
        assertEquals("$0.00", formatUsd(0.0))
    }
}
