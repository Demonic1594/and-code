package com.yugahashimoto.andcode.core.notification

import com.yugahashimoto.andcode.core.api.OpenCodePart
import com.yugahashimoto.andcode.data.repository.toolStep
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveRunContentTest {
    private val labels =
        LiveRunLabels(
            untitled = "New chat",
            working = "Working…",
            manyTitle = { "$it chats running" },
        )

    private fun run(
        id: String,
        title: String = "Fix login crash",
        step: String? = null,
        startedAt: Long = 1_000,
        agent: String? = "Claude Code",
    ) = LiveRun(sessionId = id, runtimeId = "rt", title = title, agentName = agent, step = step, startedAt = startedAt)

    @Test
    fun `nothing running posts nothing`() {
        assertNull(liveRunContent(emptyList(), labels))
    }

    @Test
    fun `one run shows its title, agent and current step`() {
        val content = liveRunContent(listOf(run("a", step = "bash · ./gradlew test")), labels)!!
        assertEquals("Fix login crash", content.title)
        assertEquals("Claude Code · bash · ./gradlew test", content.text)
        assertEquals("a", content.single?.sessionId)
        assertEquals(emptyList<String>(), content.lines)
    }

    @Test
    fun `a run with no step yet says it is working, and an untitled chat gets a name`() {
        val content = liveRunContent(listOf(run("a", title = "", agent = null)), labels)!!
        assertEquals("New chat", content.title)
        assertEquals("Working…", content.text)
    }

    @Test
    fun `several runs collapse into one, oldest first, with no single target`() {
        val content =
            liveRunContent(
                listOf(
                    run("b", title = "Write release notes", startedAt = 3_000),
                    run("a", title = "Fix login crash", step = "edit · Login.kt", startedAt = 1_000),
                ),
                labels,
            )!!
        assertEquals("2 chats running", content.title)
        assertEquals(listOf("Fix login crash — edit · Login.kt", "Write release notes — Working…"), content.lines)
        assertNull(content.single)
        assertEquals(1_000L, content.since)
    }

    @Test
    fun `a tool step names the tool and its title, and finished calls are not steps`() {
        fun part(
            status: String?,
            title: String?,
        ) = OpenCodePart(
            type = "tool",
            tool = "bash",
            state = listOfNotNull(status?.let { "status" to JsonPrimitive(it) }, title?.let { "title" to JsonPrimitive(it) }).toMap(),
        )
        assertEquals("bash · git push", toolStep(part("running", "git push")))
        assertEquals("bash", toolStep(part("pending", null)))
        assertEquals("bash", toolStep(part("running", "bash")))
        assertNull(toolStep(part("completed", "git push")))
        assertNull(toolStep(part("error", "git push")))
        assertNull(toolStep(OpenCodePart(type = "tool", tool = null)))
    }
}
