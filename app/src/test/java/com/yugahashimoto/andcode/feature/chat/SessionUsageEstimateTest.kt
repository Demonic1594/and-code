package com.yugahashimoto.andcode.feature.chat

import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodeMessageInfo
import com.yugahashimoto.andcode.core.api.OpenCodeModelReference
import com.yugahashimoto.andcode.core.api.OpenCodeSessionTokens
import com.yugahashimoto.andcode.core.api.OpenCodeTime
import com.yugahashimoto.andcode.core.pricing.ModelPrice
import com.yugahashimoto.andcode.core.pricing.PriceCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionUsageEstimateTest {
    private val catalog =
        PriceCatalog(
            prices = mapOf("zai" to mapOf("glm-5.3" to ModelPrice(input = 1.0, output = 2.0))),
        )

    private fun message(
        cost: Double?,
        tokens: OpenCodeSessionTokens? = OpenCodeSessionTokens(input = 500_000, output = 250_000),
        model: OpenCodeModelReference? = OpenCodeModelReference("zai-coding-plan", "glm-5.3"),
    ) = OpenCodeMessage(
        OpenCodeMessageInfo(
            id = "m",
            sessionId = "s",
            role = "assistant",
            time = OpenCodeTime(created = 1),
            model = model,
            tokens = tokens,
            cost = cost,
        ),
    )

    @Test
    fun `a turn the runtime priced at nothing is estimated at list prices`() {
        val usage = summarizeSessionUsage(listOf(message(cost = 0.0)), catalog)!!

        // 0.5M input + 0.25M output at the first-party fallback 1.0/2.0 per M.
        assertEquals(1.0, usage.estimatedCostUsd!!, 1e-9)
        assertEquals(0.0, usage.costUsd!!, 1e-9)
    }

    @Test
    fun `a turn with a real cost is not re-estimated`() {
        val usage = summarizeSessionUsage(listOf(message(cost = 2.5)), catalog)!!

        assertNull(usage.estimatedCostUsd)
        assertEquals(2.5, usage.costUsd!!, 1e-9)
    }

    @Test
    fun `without a catalog there is no estimate`() {
        val usage = summarizeSessionUsage(listOf(message(cost = 0.0)))!!

        assertNull(usage.estimatedCostUsd)
    }
}
