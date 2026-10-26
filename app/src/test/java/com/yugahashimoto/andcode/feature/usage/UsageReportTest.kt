package com.yugahashimoto.andcode.feature.usage

import com.yugahashimoto.andcode.core.api.OpenCodeCacheTokens
import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodeMessageInfo
import com.yugahashimoto.andcode.core.api.OpenCodeModelReference
import com.yugahashimoto.andcode.core.api.OpenCodeSession
import com.yugahashimoto.andcode.core.api.OpenCodeSessionTokens
import com.yugahashimoto.andcode.core.api.OpenCodeTime
import com.yugahashimoto.andcode.data.repository.ChatUsage
import com.yugahashimoto.andcode.data.repository.RuntimeSessionRef
import com.yugahashimoto.andcode.data.repository.TurnUsage
import com.yugahashimoto.andcode.data.repository.UsageRepository
import com.yugahashimoto.andcode.runtime.LocalAgent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class UsageReportTest {
    private val zone = ZoneOffset.UTC
    private val day = 86_400_000L

    // Noon UTC on 2026-10-26.
    private val now = LocalDate.of(2026, 10, 26).atStartOfDay(zone).toInstant().toEpochMilli() + day / 2

    private fun turn(
        daysAgo: Int,
        input: Long = 100,
        output: Long = 10,
        cacheRead: Long = 0,
        cost: Double? = null,
        model: String = "anthropic/claude",
    ) = TurnUsage(now - daysAgo * day, model, input, output, 0, cacheRead, 0, cost)

    private fun chat(
        id: String,
        agent: LocalAgent? = LocalAgent.CLAUDE_CODE,
        vararg turns: TurnUsage,
    ) = ChatUsage(RuntimeSessionRef("rt", agent, OpenCodeSession(id = id, title = id)), turns.toList())

    @Test
    fun `totals cover only the turns inside the range`() {
        val chats = listOf(chat("a", LocalAgent.CLAUDE_CODE, turn(0), turn(6), turn(7), turn(40)))

        val week = UsageReport.build(chats, UsageRange.WEEK, now, zone)
        val all = UsageReport.build(chats, UsageRange.ALL, now, zone)

        assertEquals(2, week.totals.turns)
        assertEquals(220L, week.totals.tokens)
        assertEquals(4, all.totals.turns)
    }

    @Test
    fun `every day in the range has a bar, empty days included`() {
        val report = UsageReport.build(listOf(chat("a", LocalAgent.CLAUDE_CODE, turn(0), turn(3))), UsageRange.WEEK, now, zone)

        assertEquals(7, report.days.size)
        assertEquals(LocalDate.of(2026, 10, 20), report.days.first().date)
        assertEquals(LocalDate.of(2026, 10, 26), report.days.last().date)
        assertEquals(listOf(0L, 0L, 0L, 110L, 0L, 0L, 110L), report.days.map { it.totals.tokens })
        assertEquals(220L / 7, report.dailyAverage)
    }

    @Test
    fun `all time starts the chart at the first day with use`() {
        val report = UsageReport.build(listOf(chat("a", LocalAgent.CLAUDE_CODE, turn(4))), UsageRange.ALL, now, zone)

        assertEquals(5, report.days.size)
        assertEquals(LocalDate.of(2026, 10, 22), report.busiestDay?.date)
    }

    @Test
    fun `cost stays unknown until a turn reports one, and a free turn counts as zero`() {
        val unknown = UsageReport.build(listOf(chat("a", LocalAgent.CLAUDE_CODE, turn(0))), UsageRange.ALL, now, zone)
        val free = UsageReport.build(listOf(chat("a", LocalAgent.CLAUDE_CODE, turn(0, cost = 0.0), turn(1))), UsageRange.ALL, now, zone)
        val paid =
            UsageReport.build(
                listOf(chat("a", LocalAgent.CLAUDE_CODE, turn(0, cost = 0.25), turn(1, cost = 0.5))),
                UsageRange.ALL,
                now,
                zone,
            )

        assertNull(unknown.totals.cost)
        assertEquals(0.0, free.totals.cost!!, 0.0)
        assertEquals(0.75, paid.totals.cost!!, 1e-9)
    }

    @Test
    fun `models and agents are ranked by tokens and count their chats`() {
        val chats =
            listOf(
                chat("a", LocalAgent.CLAUDE_CODE, turn(0, input = 1_000, model = "anthropic/opus"), turn(1, model = "anthropic/haiku")),
                chat("b", LocalAgent.CODEX, turn(0, input = 500, model = "openai/gpt")),
                chat("c", null, turn(0, input = 2_000, model = "anthropic/opus")),
            )

        val report = UsageReport.build(chats, UsageRange.ALL, now, zone)

        assertEquals(listOf("anthropic/opus", "openai/gpt", "anthropic/haiku"), report.models.map { it.key })
        assertEquals(2, report.models.first().chats)
        assertEquals(listOf("", "claude-code", "codex"), report.agents.map { it.key })
        assertEquals(listOf("c", "a", "b"), report.topChats.map { it.ref.session.id })
        assertEquals(3, report.activeChats)
    }

    @Test
    fun `cache hit rate is the share of the prompt read from cache`() {
        val report =
            UsageReport.build(
                listOf(chat("a", LocalAgent.CLAUDE_CODE, turn(0, input = 250, cacheRead = 750))),
                UsageRange.ALL,
                now,
                zone,
            )

        assertEquals(0.75, report.totals.cacheHitRate!!, 1e-9)
        assertNull(UsageTotals().cacheHitRate)
    }

    @Test
    fun `unreadable chats are counted and chats without use in range are left out`() {
        val chats =
            listOf(
                ChatUsage(RuntimeSessionRef("gone", null, OpenCodeSession(id = "x")), emptyList(), unreadable = true),
                chat("old", LocalAgent.CLAUDE_CODE, turn(100)),
            )

        val report = UsageReport.build(chats, UsageRange.MONTH, now, zone)

        assertEquals(1, report.unreadableChats)
        assertEquals(0, report.activeChats)
        assertTrue(report.topChats.isEmpty())
    }

    @Test
    fun `turns are read from assistant messages with tokens or a cost`() {
        fun message(
            role: String,
            tokens: OpenCodeSessionTokens?,
            cost: Double? = null,
        ) = OpenCodeMessage(
            OpenCodeMessageInfo(
                id = role,
                sessionId = "s",
                role = role,
                time = OpenCodeTime(created = 42),
                model = OpenCodeModelReference("anthropic", "opus"),
                tokens = tokens,
                cost = cost,
            ),
        )

        val turns =
            UsageRepository.turnsOf(
                listOf(
                    message("user", OpenCodeSessionTokens(input = 5)),
                    message("assistant", OpenCodeSessionTokens(1, 2, 3, OpenCodeCacheTokens(read = 4, write = 5)), cost = 0.1),
                    message("assistant", null),
                ),
            )

        assertEquals(1, turns.size)
        assertEquals(TurnUsage(42, "anthropic/opus", 1, 2, 3, 4, 5, 0.1), turns.single())
        assertEquals(15L, turns.single().tokens)
    }
}
