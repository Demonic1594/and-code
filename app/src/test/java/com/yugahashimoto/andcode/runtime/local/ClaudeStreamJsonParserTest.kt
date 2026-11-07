package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.RetryReason
import com.yugahashimoto.andcode.core.api.RunActivity
import com.yugahashimoto.andcode.core.api.RunRetry
import com.yugahashimoto.andcode.feature.chat.toolFilePaths
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeStreamJsonParserTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun parser() = ClaudeStreamJsonParser("session-1", json)

    @Test
    fun `reads the session id, model and catalogue from the init line`() {
        val parsed =
            parser().parse(
                """
                {"type":"system","subtype":"init","session_id":"abc","model":"claude-sonnet-5",
                 "slash_commands":["/review","/ship"],"skills":["pdf"]}
                """.trimIndent().replace("\n", ""),
            )

        assertEquals("abc", parsed.claudeSessionId)
        assertEquals("claude-sonnet-5", parsed.resolvedModel)
        assertEquals(listOf("/review", "/ship"), parsed.slashCommands)
        assertEquals(listOf("pdf"), parsed.skills)
        assertNull("no tools listed, nothing learned", parsed.workflowsAvailable)
    }

    @Test
    fun `the init line tells whether the Workflow tool that ultracode needs is offered`() {
        val withWorkflows =
            parser().parse("""{"type":"system","subtype":"init","session_id":"a","tools":["Bash","Workflow","Read"]}""")
        val without = parser().parse("""{"type":"system","subtype":"init","session_id":"a","tools":["Bash","Read"]}""")
        val status = parser().parse("""{"type":"system","subtype":"status","status":"requesting"}""")

        assertEquals(true, withWorkflows.workflowsAvailable)
        assertEquals(false, without.workflowsAvailable)
        assertNull(status.workflowsAvailable)
    }

    @Test
    fun `an api retry becomes the retry status, with the failure named`() {
        val parsed =
            parser().parse(
                """{"type":"system","subtype":"api_retry","attempt":2,"max_retries":10,"retry_delay_ms":8000,""" +
                    """"error_status":429,"error":"rate_limit","session_id":"abc"}""",
            )

        val status = parsed.events.single() as OpenCodeEvent.SessionStatusChanged
        assertEquals("session-1", status.sessionId)
        assertEquals("retry", status.status)
        assertEquals(RunRetry(attempt = 2, message = null, maxAttempts = 10, reason = RetryReason.RATE_LIMITED), status.retry)
        assertEquals("abc", parsed.claudeSessionId)
        assertNull("a notice is not a session start", parsed.slashCommands)
    }

    @Test
    fun `a retry Claude Code cannot name keeps the status code, and no answer at all says so`() {
        fun retry(line: String) = (parser().parse(line).events.single() as OpenCodeEvent.SessionStatusChanged).retry

        assertEquals(
            RunRetry(attempt = 1, message = "HTTP 500", maxAttempts = null, reason = null),
            retry("""{"type":"system","subtype":"api_retry","attempt":0,"error_status":500,"error":"unknown"}"""),
        )
        assertEquals(
            RetryReason.NO_RESPONSE,
            retry(
                """{"type":"system","subtype":"api_retry","attempt":3,"max_retries":10,"error_status":null,""" +
                    """"error":"unknown","no_response":{"waited_ms":60000,"retry_wait_ms":2000}}""",
            )?.reason,
        )
        assertEquals(
            RetryReason.OVERLOADED,
            retry("""{"type":"system","subtype":"api_retry","attempt":1,"error_status":529,"error":"overloaded"}""")?.reason,
        )
        assertEquals(
            RetryReason.AUTHENTICATION,
            retry("""{"type":"system","subtype":"api_retry","attempt":1,"error_status":403,"error":"cloud_credential_error"}""")?.reason,
        )
    }

    @Test
    fun `compacting is an activity of the run until its boundary`() {
        val parser = parser()

        fun activity(line: String) = (parser.parse(line).events.single() as OpenCodeEvent.RunActivityChanged).activity

        assertEquals(RunActivity.Compacting, activity("""{"type":"system","subtype":"status","status":"compacting"}"""))
        assertNull(activity("""{"type":"system","subtype":"status","status":"requesting"}"""))
        assertNull(activity("""{"type":"system","subtype":"status","status":null}"""))
        assertNull(
            activity(
                """{"type":"system","subtype":"compact_boundary","compact_metadata":{"trigger":"auto","pre_tokens":150000}}""",
            ),
        )
    }

    @Test
    fun `a model fallback names both models, and one missing a name is ignored`() {
        val parsed =
            parser().parse(
                """{"type":"system","subtype":"model_fallback","trigger":"overloaded","original_model":"claude-opus-5",""" +
                    """"fallback_model":"claude-sonnet-5","content":"..."}""",
            )

        val activity = (parsed.events.single() as OpenCodeEvent.RunActivityChanged).activity
        assertEquals(RunActivity.ModelSwitched(from = "claude-opus-5", to = "claude-sonnet-5"), activity)
        assertNull("a turn-scoped switch is not the model the chat asked for", parsed.resolvedModel)
        assertTrue(parser().parse("""{"type":"system","subtype":"model_fallback","original_model":"a"}""").events.isEmpty())
    }

    @Test
    fun `turns assistant text into a message part`() {
        val parsed =
            parser().parse(
                """{"type":"assistant","message":{"id":"m1","model":"claude-opus-5","content":[{"type":"text","text":"hi"}]}}""",
            )

        assertEquals("claude-opus-5", parsed.resolvedModel)
        assertEquals(1, parsed.messages.size)
        assertEquals("assistant", parsed.messages[0].info.role)
        assertEquals("hi", parsed.messages[0].parts[0].text)
    }

    @Test
    fun `reports tool results as assistant activity rather than a user turn`() {
        val parsed =
            parser().parse(
                """{"type":"user","message":{"id":"m2","content":[{"type":"tool_result","tool_use_id":"t1","content":"done"}]}}""",
            )

        assertEquals("assistant", parsed.messages.single().info.role)
        assertEquals("t1", parsed.messages.single().parts.single().callID)
    }

    @Test
    fun `takes the todo list from a TodoWrite call`() {
        val parsed =
            parser().parse(
                """
                {"type":"assistant","message":{"id":"m3","content":[{"type":"tool_use","id":"t2","name":"TodoWrite",
                 "input":{"todos":[{"content":"Write tests","status":"in_progress","priority":"high"},
                 {"content":"Ship","status":"pending"}]}}]}}
                """.trimIndent().replace("\n", ""),
            )

        val todos = requireNotNull(parsed.todos)
        assertEquals(listOf("Write tests", "Ship"), todos.map { it.content })
        assertEquals(listOf("in_progress", "pending"), todos.map { it.status })
        // Claude omits the priority; the UI still needs one.
        assertEquals("medium", todos[1].priority)
    }

    @Test
    fun `leaves the todo list untouched for other tools`() {
        val parsed =
            parser().parse("""{"type":"assistant","message":{"id":"m4","content":[{"type":"tool_use","id":"t3","name":"Read"}]}}""")

        assertNull(parsed.todos)
    }

    @Test
    fun `streams partial text against the message being written`() {
        val parser = parser()
        parser.parse("""{"type":"assistant","message":{"id":"m5","content":[{"type":"text","text":""}]}}""")

        val parsed = parser.parse("""{"type":"stream_event","event":{"delta":{"type":"text_delta","text":"chunk"}}}""")

        val delta = parsed.events.single() as OpenCodeEvent.MessagePartDelta
        assertEquals("m5", delta.messageId)
        assertEquals("chunk", delta.delta)
        assertEquals("text", delta.field)
    }

    @Test
    fun `finishes the turn on a successful result`() {
        val parsed = parser().parse("""{"type":"result","subtype":"success","session_id":"abc"}""")

        assertTrue(parsed.turnFinished)
        assertNull(parsed.errorMessage)
        assertTrue(parsed.events.single() is OpenCodeEvent.SessionIdle)
    }

    @Test
    fun `turnFinished tracks the live turn and resets on beginTurn`() {
        val parser = parser()
        parser.parse("""{"type":"result","subtype":"success","session_id":"abc"}""")
        assertTrue(parser.turnFinished)

        parser.beginTurn()
        assertFalse(parser.turnFinished)
    }

    @Test
    fun `surfaces a failed result as an error and then idles`() {
        val parsed = parser().parse("""{"type":"result","subtype":"error_during_execution","result":"boom"}""")

        assertEquals("boom", parsed.errorMessage)
        assertTrue(parsed.events.first() is OpenCodeEvent.SessionError)
        assertTrue(parsed.events.last() is OpenCodeEvent.SessionIdle)
    }

    @Test
    fun `a result after a requested interrupt idles quietly instead of reporting an error`() {
        val parser = parser()
        parser.beginTurn()
        parser.parse("""{"type":"assistant","message":{"id":"m9","content":[{"type":"tool_use","id":"t1","name":"Bash","input":{}}]}}""")
        parser.requestInterrupt()

        val parsed = parser.parse("""{"type":"result","subtype":"error_during_execution","is_error":true}""")

        assertTrue(parser.turnFinished)
        assertNull(parsed.errorMessage)
        assertTrue(parsed.events.none { it is OpenCodeEvent.SessionError })
        assertTrue(parsed.events.last() is OpenCodeEvent.SessionIdle)
    }

    @Test
    fun `an interrupt request does not leak into the next turn`() {
        val parser = parser()
        parser.requestInterrupt()
        parser.beginTurn()

        val parsed = parser.parse("""{"type":"result","subtype":"error_during_execution","result":"boom"}""")

        assertEquals("boom", parsed.errorMessage)
    }

    @Test
    fun `merges the final assistant message onto the streamed text instead of duplicating it`() {
        val parser = parser()
        parser.parse("""{"type":"assistant","message":{"id":"m5","content":[{"type":"text","text":""}]}}""")
        parser.parse("""{"type":"stream_event","event":{"delta":{"type":"text_delta","text":"chunk"}}}""")

        // Claude Code replays the full text as a final "assistant" line once streaming for the
        // block is done; a text content block never carries an "id", so this must land on the same
        // synthesized part id the deltas used above rather than becoming a second, duplicate part.
        val replayed =
            parser.parse("""{"type":"assistant","message":{"id":"m5","content":[{"type":"text","text":"chunk"}]}}""")

        val parts = replayed.messages.single().parts
        assertEquals(1, parts.size)
        assertEquals("chunk", parts.single().text)
    }

    @Test
    fun `routes a tool_result back onto the message that started the tool instead of stranding it`() {
        val parser = parser()
        parser.parse(
            """{"type":"assistant","message":{"id":"m-tool","content":[{"type":"tool_use","id":"t1","name":"Bash","input":{}}]}}""",
        )

        // The result line reports its own, unrelated message id ("m-result"), the way Claude Code's
        // CLI actually behaves; the running tool card lives on "m-tool" and must be updated there.
        val resultParsed =
            parser.parse(
                """{"type":"user","message":{"id":"m-result","content":[{"type":"tool_result","tool_use_id":"t1","content":"done"}]}}""",
            )

        val message = resultParsed.messages.single()
        assertEquals("m-tool", message.info.id)
        val tool = message.parts.single { it.id == "t1" }
        assertEquals("completed", tool.state?.get("status")?.jsonPrimitive?.content)
    }

    @Test
    fun `a finished tool keeps its name and input, so a written file is still known`() {
        val parser = parser()
        parser.parse(
            """{"type":"assistant","message":{"id":"m-tool","content":[{"type":"tool_use","id":"t1","name":"Write",""" +
                """"input":{"file_path":"/workspace/index.html","content":"<h1>hi</h1>"}}]}}""",
        )

        val result =
            parser.parse(
                """{"type":"user","message":{"id":"m-r","content":[{"type":"tool_result","tool_use_id":"t1","content":"ok"}]}}""",
            )

        val tool = result.messages.single().parts.single { it.id == "t1" }
        assertEquals("Write", tool.tool)
        assertEquals("completed", tool.state?.get("status")?.jsonPrimitive?.content)
        assertEquals("ok", tool.state?.get("output")?.jsonPrimitive?.content)
        assertTrue(tool.state?.get("input").toString().contains("/workspace/index.html"))
        // The update event the chat applies carries the same, whole part.
        val event = result.events.filterIsInstance<OpenCodeEvent.MessagePartUpdated>().single()
        assertEquals("Write", event.part.tool)
    }

    @Test
    fun `an image a tool returns is a picture, not base64 or the size note in its output`() {
        val parser = parser()
        parser.parse(
            """{"type":"assistant","message":{"id":"m-tool","content":[{"type":"tool_use","id":"t1","name":"Read",""" +
                """"input":{"file_path":"/workspace/shot.png"}}]}}""",
        )

        val result =
            parser.parse(
                """{"type":"user","message":{"id":"m-r","content":[{"type":"tool_result","tool_use_id":"t1","content":[""" +
                    """{"type":"image","source":{"type":"base64","media_type":"image/png","data":"iVBORw0KGgo="}},""" +
                    """{"type":"text","text":"[Image: original 1280x2130, displayed at 1202x2000. """ +
                    """Multiply coordinates by 1.06 to map to original image.]"},""" +
                    """{"type":"text","text":"[Image: a caption the tool wrote]"}""" +
                    """]}]}}""",
            )

        val parts = result.messages.single().parts
        val tool = parts.single { it.id == "t1" }
        // Only Claude Code's size note goes; other text the tool wrote stays.
        assertEquals("[Image: a caption the tool wrote]", tool.state?.get("output")?.jsonPrimitive?.content)
        val image = parts.single { it.type == "file" }
        assertEquals("image/png", image.mime)
        assertEquals("data:image/png;base64,iVBORw0KGgo=", image.url)
    }

    @Test
    fun `two pictures sent on separate lines of one message are both kept`() {
        val parser = parser()
        parser.parse(
            """{"type":"assistant","message":{"id":"m1","content":[""" +
                """{"type":"image","source":{"type":"base64","media_type":"image/png","data":"AAAA"}}]}}""",
        )
        val second =
            parser.parse(
                """{"type":"assistant","message":{"id":"m1","content":[""" +
                    """{"type":"image","source":{"type":"base64","media_type":"image/png","data":"BBBB"}}]}}""",
            )

        assertEquals(2, second.messages.single().parts.count { it.type == "file" })
    }

    @Test
    fun `the size note stays when a tool result has no picture`() {
        val parser = parser()
        parser.parse("""{"type":"assistant","message":{"id":"m","content":[{"type":"tool_use","id":"t1","name":"Bash","input":{}}]}}""")
        val result =
            parser.parse(
                """{"type":"user","message":{"id":"r","content":[{"type":"tool_result","tool_use_id":"t1",""" +
                    """"content":[{"type":"text","text":"[Image: not really]"}]}]}}""",
            )

        val tool = result.messages.single().parts.single { it.id == "t1" }
        assertEquals("[Image: not really]", tool.state?.get("output")?.jsonPrimitive?.content)
    }

    @Test
    fun `an image the agent includes in its own message is a picture`() {
        val parsed =
            parser().parse(
                """{"type":"assistant","message":{"id":"m1","content":[{"type":"text","text":"Here it is"},""" +
                    """{"type":"image","source":{"type":"base64","media_type":"image/jpeg","data":"/9j/4AAQ"}}]}}""",
            )

        val parts = parsed.messages.single().parts
        assertEquals(listOf("text", "file"), parts.map { it.type })
        assertEquals("data:image/jpeg;base64,/9j/4AAQ", parts[1].url)
    }

    @Test
    fun `an https image is shown, while http and unusable sources are not`() {
        val parsed =
            parser().parse(
                """{"type":"assistant","message":{"id":"m1","content":[""" +
                    """{"type":"image","source":{"type":"url","url":"https://example.com/a.png"}},""" +
                    """{"type":"image","source":{"type":"url","url":"http://example.com/b.png"}},""" +
                    """{"type":"image","source":{"type":"base64","media_type":"image/png","data":""}},""" +
                    """{"type":"image","source":{"type":"base64","media_type":"text/plain","data":"AAAA"}},""" +
                    """{"type":"image","source":{"type":"file","file_id":"f1"}},""" +
                    """{"type":"image"}]}}""",
            )

        // The chat's image loader only fetches https, so an http picture would be a broken one.
        assertEquals(listOf("https://example.com/a.png"), parsed.messages.single().parts.map { it.url })
    }

    @Test
    fun `the size note stays when a tool's picture cannot be shown`() {
        val parser = parser()
        parser.parse("""{"type":"assistant","message":{"id":"m","content":[{"type":"tool_use","id":"t1","name":"Read","input":{}}]}}""")
        val result =
            parser.parse(
                """{"type":"user","message":{"id":"r","content":[{"type":"tool_result","tool_use_id":"t1","content":[""" +
                    """{"type":"image","source":{"type":"url","url":"http://example.com/b.png"}},""" +
                    """{"type":"text","text":"[Image: original 10x10, displayed at 10x10]"}]}]}}""",
            )

        val parts = result.messages.single().parts
        assertTrue(parts.none { it.type == "file" })
        val tool = parts.single { it.id == "t1" }
        assertEquals("[Image: original 10x10, displayed at 10x10]", tool.state?.get("output")?.jsonPrimitive?.content)
    }

    @Test
    fun `a result for a call never seen is still shown as a finished tool`() {
        val result =
            parser().parse(
                """{"type":"user","message":{"id":"r","content":[{"type":"tool_result","tool_use_id":"lost","content":"done"}]}}""",
            )

        val tool = result.messages.single().parts.single()
        assertEquals("lost", tool.id)
        assertEquals("tool", tool.tool)
        assertEquals("completed", tool.state?.get("status")?.jsonPrimitive?.content)
        assertEquals("done", tool.state?.get("output")?.jsonPrimitive?.content)
    }

    @Test
    fun `a tool_result keeps the name and input of the tool it answers`() {
        val parser = parser()
        parser.parse(toolUses("""{"type":"tool_use","id":"t1","name":"Write","input":{"file_path":"/workspace/page.html"}}"""))

        val resultParsed = parser.parse(toolResults("""{"type":"tool_result","tool_use_id":"t1","content":"written"}"""))

        val stored = resultParsed.messages.single().parts.single { it.id == "t1" }
        val sent = (resultParsed.events.single() as OpenCodeEvent.MessagePartUpdated).part
        for (part in listOf(stored, sent)) {
            assertEquals("Write", part.tool)
            assertEquals("completed", part.state?.get("status")?.jsonPrimitive?.content)
            assertEquals("written", part.state?.get("output")?.jsonPrimitive?.content)
            // What the chat's artifact list reads the written file from.
            assertEquals(listOf("/workspace/page.html"), toolFilePaths(part.state?.get("input")))
        }
    }

    @Test
    fun `a failed tool_result keeps the tool's name and reports the error`() {
        val parser = parser()
        parser.parse(toolUses("""{"type":"tool_use","id":"t1","name":"Write","input":{"file_path":"/workspace/page.html"}}"""))

        val resultParsed =
            parser.parse(toolResults("""{"type":"tool_result","tool_use_id":"t1","content":"denied","is_error":true}"""))

        val sent = (resultParsed.events.single() as OpenCodeEvent.MessagePartUpdated).part
        assertEquals("Write", sent.tool)
        assertEquals("error", sent.state?.get("status")?.jsonPrimitive?.content)
    }

    @Test
    fun `a tool_result only sends an event for the tool it answers`() {
        val parser = parser()
        parser.parse(
            toolUses(
                """{"type":"text","text":"Writing"}""",
                """{"type":"tool_use","id":"t1","name":"Write","input":{}}""",
                """{"type":"tool_use","id":"t2","name":"Bash","input":{}}""",
            ),
        )

        val resultParsed = parser.parse(toolResults("""{"type":"tool_result","tool_use_id":"t2","content":"done"}"""))

        val sent = resultParsed.events.map { (it as OpenCodeEvent.MessagePartUpdated).part }
        assertEquals(listOf("t2"), sent.map { it.id })
        assertEquals("Bash", sent.single().tool)
    }

    @Test
    fun `results for parallel tools each settle onto their own tool`() {
        val parser = parser()
        parser.parse(
            toolUses(
                """{"type":"tool_use","id":"t1","name":"Write","input":{}}""",
                """{"type":"tool_use","id":"t2","name":"Edit","input":{}}""",
            ),
        )

        val resultParsed =
            parser.parse(
                toolResults(
                    """{"type":"tool_result","tool_use_id":"t1","content":"one"}""",
                    """{"type":"tool_result","tool_use_id":"t2","content":"two"}""",
                ),
            )

        val sent = resultParsed.events.map { (it as OpenCodeEvent.MessagePartUpdated).part }
        assertEquals(listOf("Write", "Edit"), sent.map { it.tool })
        assertEquals(listOf("one", "two"), sent.map { it.state?.get("output")?.jsonPrimitive?.content })
    }

    private fun toolUses(vararg blocks: String) =
        """{"type":"assistant","message":{"id":"m-tool","content":[${blocks.joinToString(",")}]}}"""

    private fun toolResults(vararg blocks: String) =
        """{"type":"user","message":{"id":"m-result","content":[${blocks.joinToString(",")}]}}"""

    @Test
    fun `settles open tools when a turn ends with an error`() {
        val parser = parser()
        parser.parse(
            """{"type":"assistant","message":{"id":"m-tool","content":[{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"grep"}}]}}""",
        )

        val failed =
            parser.parse(
                """{"type":"result","subtype":"error_during_execution","result":"API disconnected","session_id":"abc"}""",
            )

        val recovered = failed.messages.single()
        val tool = recovered.parts.single()
        assertEquals("m-tool", recovered.info.id)
        assertEquals("t1", tool.id)
        assertEquals("error", tool.state?.get("status")?.jsonPrimitive?.content)
        assertEquals("API disconnected", tool.state?.get("error")?.jsonPrimitive?.content)
        assertTrue(failed.events.any { event -> event is OpenCodeEvent.MessagePartUpdated })
    }

    @Test
    fun `ignores a line that is not JSON`() {
        val parsed = parser().parse("not json at all")

        assertTrue(parsed.events.isEmpty())
        assertTrue(parsed.messages.isEmpty())
    }

    private fun step(
        id: String,
        input: Int,
        cacheRead: Int,
    ) = """{"type":"assistant","message":{"id":"$id","content":[{"type":"text","text":"x"}],""" +
        """"usage":{"input_tokens":$input,"output_tokens":1,"cache_read_input_tokens":$cacheRead,"cache_creation_input_tokens":5}}}"""

    private fun result(
        output: Int,
        total: Double?,
    ) = """{"type":"result","subtype":"success","usage":{"input_tokens":1,"output_tokens":$output}""" +
        (total?.let { ""","total_cost_usd":$it""" } ?: "") + "}"

    @Test
    fun `records each step's input and cache, and the turn's output and cost on its last step`() {
        val parser = parser()
        parser.beginTurn()
        val first = parser.parse(step("m1", input = 10, cacheRead = 100)).messages.single()
        parser.parse(step("m1", input = 10, cacheRead = 100))
        parser.parse(step("m2", input = 20, cacheRead = 200))

        val closed = parser.parse(result(output = 345, total = 0.25))

        assertEquals(10L, first.info.tokens?.input)
        assertEquals(0L, first.info.tokens?.output)
        val last = closed.messages.single()
        assertEquals("m2", last.info.id)
        assertEquals(20L, last.info.tokens?.input)
        assertEquals(200L, last.info.tokens?.cache?.read)
        assertEquals(5L, last.info.tokens?.cache?.write)
        assertEquals(345L, last.info.tokens?.output)
        assertEquals(0.25, last.info.cost!!, 1e-9)
        assertEquals(0.25, closed.costTotal!!, 1e-9)
    }

    @Test
    fun `a running total becomes each turn's own cost`() {
        val parser = ClaudeStreamJsonParser("s", json, costBaseline = 1.0)
        parser.beginTurn()
        parser.parse(step("a", 1, 0))
        assertEquals(0.5, parser.parse(result(1, total = 1.5)).messages.single().info.cost!!, 1e-9)
        parser.beginTurn()
        parser.parse(step("b", 1, 0))
        assertEquals(0.25, parser.parse(result(1, total = 1.75)).messages.single().info.cost!!, 1e-9)
        // The CLI started counting again (a /clear): the total is this turn's cost.
        parser.beginTurn()
        parser.parse(step("c", 1, 0))
        assertEquals(0.1, parser.parse(result(1, total = 0.1)).messages.single().info.cost!!, 1e-9)
    }

    @Test
    fun `no cost is guessed without a baseline or from a crash's zeroed total`() {
        val unknown = ClaudeStreamJsonParser("s", json, costBaseline = null)
        unknown.beginTurn()
        unknown.parse(step("a", 1, 0))
        val first = unknown.parse(result(7, total = 3.0))
        assertNull(first.messages.single().info.cost)
        assertEquals(7L, first.messages.single().info.tokens?.output)
        unknown.beginTurn()
        unknown.parse(step("b", 1, 0))
        assertEquals(0.5, unknown.parse(result(1, total = 3.5)).messages.single().info.cost!!, 1e-9)

        unknown.beginTurn()
        unknown.parse(step("c", 1, 0))
        val crashed = unknown.parse("""{"type":"result","subtype":"error_during_execution","is_error":true,"total_cost_usd":0}""")
        assertTrue(crashed.messages.none { it.info.cost != null && it.info.id == "c" })
        assertEquals(3.5, crashed.costTotal!!, 1e-9)
    }
}
