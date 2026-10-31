package com.yugahashimoto.andcode.core.api

import org.junit.Assert.assertEquals
import org.junit.Test

class ThinkingLevelsTest {
    @Test
    fun `known levels follow the shared scale, weakest first`() {
        assertEquals(
            listOf("none", "low", "medium", "high", "xhigh", "max"),
            orderThinkingLevels(listOf("max", "high", "low", "xhigh", "none", "medium")),
        )
    }

    @Test
    fun `case is ignored for ordering and kept in the result`() {
        assertEquals(listOf("Low", "HIGH"), orderThinkingLevels(listOf("HIGH", "Low")))
        // Keys that differ only in case are different values to send, so both stay.
        assertEquals(listOf("low", "LOW"), orderThinkingLevels(listOf("low", "LOW")))
    }

    @Test
    fun `unknown levels are kept after the known ones, in their own order`() {
        assertEquals(
            listOf("low", "high", "turbo", "deep"),
            orderThinkingLevels(listOf("turbo", "high", "deep", "low")),
        )
    }

    @Test
    fun `duplicates and an empty list`() {
        assertEquals(listOf("low", "high"), orderThinkingLevels(listOf("high", "low", "high")))
        assertEquals(emptyList<String>(), orderThinkingLevels(emptyList()))
    }
}
