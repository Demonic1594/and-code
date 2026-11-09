package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.ContextBreakdown
import com.yugahashimoto.andcode.core.api.ContextUsage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CodexContextUsageTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    private fun body(text: String) = json.parseToJsonElement(text) as JsonObject

    @Test
    fun `the last call's total is what fills the window`() {
        // Shaped by ThreadTokenUsageUpdatedNotification in codex app-server 0.167.0's schema.
        val usage =
            CodexContextUsage.parse(
                body(
                    """
                    {"threadId":"t1","turnId":"turn-1","tokenUsage":{
                      "total":{"totalTokens":120000,"inputTokens":110000,"cachedInputTokens":90000,"outputTokens":10000,"reasoningOutputTokens":4000},
                      "last":{"totalTokens":41000,"inputTokens":40000,"cachedInputTokens":36000,"outputTokens":1000,"reasoningOutputTokens":300},
                      "modelContextWindow":258400}}
                    """.trimIndent(),
                ),
            )

        assertEquals(ContextUsage(usedTokens = 41_000, windowTokens = 258_400), usage)
    }

    @Test
    fun `no window size means nothing to measure against`() {
        val last = """"last":{"totalTokens":5,"inputTokens":5,"cachedInputTokens":0,"outputTokens":0,"reasoningOutputTokens":0}"""
        assertNull(CodexContextUsage.parse(body("""{"threadId":"t1","tokenUsage":{$last,"total":{},"modelContextWindow":null}}""")))
        assertNull(CodexContextUsage.parse(body("""{"threadId":"t1","tokenUsage":{$last,"total":{}}}""")))
        assertNull(CodexContextUsage.parse(body("""{"threadId":"t1"}""")))
    }

    @Test
    fun `usage survives a restart, and a deleted chat's is dropped`() {
        val file = folder.root.resolve("codex-context-usage.json")
        val store = CodexContextUsage(file, json)
        store.put("t1", ContextUsage(1_000, 200_000))
        store.put("t2", ContextUsage(2_000, 200_000))
        store.flush()

        val reloaded = CodexContextUsage(file, json)
        assertEquals(ContextUsage(1_000, 200_000), reloaded.get("t1"))
        reloaded.remove("t1")
        assertNull(CodexContextUsage(file, json).get("t1"))
        assertEquals(ContextUsage(2_000, 200_000), CodexContextUsage(file, json).get("t2"))
    }

    @Test
    fun `only the most recently updated threads are kept`() {
        val store = CodexContextUsage(folder.root.resolve("usage.json"), json)
        repeat(201) { store.put("t$it", ContextUsage(it.toLong(), 1_000)) }
        // t0 was the least recently updated; touching t1 keeps it.
        store.put("t1", ContextUsage(9, 1_000))
        store.put("t201", ContextUsage(1, 1_000))

        assertNull(store.get("t0"))
        assertEquals(ContextUsage(9, 1_000), store.get("t1"))
        assertNull(store.get("t2"))
    }

    @Test
    fun `the sheet shows what is in use and what is free`() {
        val breakdown = CodexContextUsage.breakdown(ContextUsage(60_000, 258_000))

        assertEquals(60_000L, breakdown.usedTokens)
        assertEquals(258_000L, breakdown.maxTokens)
        assertEquals(
            listOf(
                ContextBreakdown.Category("in_use", "", 60_000, ContextBreakdown.Kind.USED),
                ContextBreakdown.Category("free_space", "", 198_000, ContextBreakdown.Kind.FREE),
            ),
            breakdown.categories,
        )
        assertNull(breakdown.autoCompactAt)
        // A reading over the window does not draw a negative free share.
        assertEquals(0L, CodexContextUsage.breakdown(ContextUsage(300_000, 258_000)).categories.last().tokens)
    }
}
