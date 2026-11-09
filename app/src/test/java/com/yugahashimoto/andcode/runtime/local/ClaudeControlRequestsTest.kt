package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.ContextBreakdown
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeControlRequestsTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun idOf(line: String): String = json.parseToJsonElement(line).jsonObject["request_id"]!!.jsonPrimitive.content

    @Test
    fun `an answer reaches the request waiting on its id, and other lines are left to the turn`() =
        runTest {
            val requests = ClaudeControlRequests(json)
            var sent = ""
            val asked =
                async {
                    requests.ask(ClaudeControlRequests.CONTEXT_USAGE, 5_000) { line ->
                        sent = line
                        true
                    }
                }
            testScheduler.runCurrent()

            assertTrue(sent.contains("\"subtype\":\"get_context_usage\""))
            assertFalse(requests.answer("""{"type":"assistant","message":{"content":[]}}"""))
            // An interrupt's answer, which nothing waits on, is still not part of the turn.
            val interruptAnswer = """{"type":"control_response","response":{"subtype":"success","request_id":"interrupt-1"}}"""
            assertTrue(requests.answer(interruptAnswer))
            assertTrue(
                requests.answer(
                    """{"type":"control_response","response":""" +
                        """{"subtype":"success","request_id":"${idOf(sent)}","response":{"maxTokens":5}}}""",
                ),
            )

            assertEquals("5", asked.await()?.get("maxTokens")?.jsonPrimitive?.content)
        }

    @Test
    fun `a refused, unsent or unanswered request comes back empty`() =
        runTest {
            val requests = ClaudeControlRequests(json)
            var sent = ""
            val refused =
                async {
                    requests.ask(ClaudeControlRequests.CONTEXT_USAGE, 5_000) { line ->
                        sent = line
                        true
                    }
                }
            testScheduler.runCurrent()
            requests.answer("""{"type":"control_response","response":{"subtype":"error","request_id":"${idOf(sent)}","error":"no"}}""")

            assertNull(refused.await())
            assertNull(requests.ask(ClaudeControlRequests.CONTEXT_USAGE, 5_000) { false })
            assertNull(requests.ask(ClaudeControlRequests.CONTEXT_USAGE, 5_000) { true })
        }

    @Test
    fun `the context answer is read into categories, servers and files`() {
        // Trimmed from what Claude Code 2.1.319 answers, plus MCP tools and a memory file.
        val answer =
            json.parseToJsonElement(
                """
                {"categories":[
                  {"id":"system_prompt","name":"System prompt","tokens":2035,"color":"promptBorder","kind":"used"},
                  {"id":"system_tools_deferred","name":"System tools (deferred)","tokens":20452,"isDeferred":true,"kind":"deferred"},
                  {"id":"mcp_tools","name":"MCP tools","tokens":3100,"kind":"used"},
                  {"id":"autocompact_buffer","name":"Autocompact buffer","tokens":33000,"kind":"buffer"},
                  {"id":"free_space","name":"Free space","tokens":939524,"kind":"free"}],
                 "totalTokens":27476,"maxTokens":1000000,"percentage":3,"gridRows":[[]],
                 "memoryFiles":[{"path":"/root/project/CLAUDE.md","type":"Project","tokens":412}],
                 "mcpTools":[
                   {"name":"mcp__browser__read","serverName":"browser","tokens":900,"isLoaded":true},
                   {"name":"mcp__browser__click","serverName":"browser","tokens":700},
                   {"name":"mcp__browser__scroll","serverName":"browser","tokens":650,"isLoaded":false},
                   {"name":"mcp__docs__query","serverName":"docs","tokens":1500,"isLoaded":true}],
                 "autoCompactThreshold":967000,"isAutoCompactEnabled":true}
                """.trimIndent(),
            ) as JsonObject

        val breakdown = ClaudeControlRequests.contextBreakdown(answer)!!

        assertEquals(27_476L, breakdown.usedTokens)
        assertEquals(1_000_000L, breakdown.maxTokens)
        assertEquals(967_000L, breakdown.autoCompactAt)
        assertEquals(
            listOf(
                ContextBreakdown.Kind.USED,
                ContextBreakdown.Kind.DEFERRED,
                ContextBreakdown.Kind.USED,
                ContextBreakdown.Kind.BUFFER,
                ContextBreakdown.Kind.FREE,
            ),
            breakdown.categories.map { it.kind },
        )
        assertEquals(
            listOf(
                ContextBreakdown.McpServerShare("browser", 1_600, 3),
                ContextBreakdown.McpServerShare("docs", 1_500, 1),
            ),
            breakdown.mcpServers,
        )
        assertEquals(listOf(ContextBreakdown.MemoryFile("/root/project/CLAUDE.md", 412)), breakdown.memoryFiles)
    }

    @Test
    fun `no window size means nothing to show, and auto-compact off means no threshold`() {
        assertNull(ClaudeControlRequests.contextBreakdown(json.parseToJsonElement("""{"categories":[]}""") as JsonObject))

        val off =
            ClaudeControlRequests.contextBreakdown(
                json.parseToJsonElement(
                    """{"maxTokens":200000,"autoCompactThreshold":180000,"isAutoCompactEnabled":false}""",
                ) as JsonObject,
            )!!
        assertNull(off.autoCompactAt)
        assertEquals(0L, off.usedTokens)
    }
}
