package com.yugahashimoto.andcode.feature.usage

import com.yugahashimoto.andcode.data.repository.ChatUsage
import com.yugahashimoto.andcode.data.repository.RuntimeSessionRef
import com.yugahashimoto.andcode.data.repository.TurnUsage
import com.yugahashimoto.andcode.runtime.LocalAgent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** How far back the usage page looks. */
enum class UsageRange(
    val days: Int?,
) {
    WEEK(7),
    MONTH(30),
    QUARTER(90),
    ALL(null),
}

/** Tokens and cost summed over some set of turns. */
data class UsageTotals(
    val input: Long = 0,
    val output: Long = 0,
    val reasoning: Long = 0,
    val cacheRead: Long = 0,
    val cacheWrite: Long = 0,
    /** Null while no turn in the set carried a cost, as opposed to a known zero. */
    val cost: Double? = null,
    val turns: Int = 0,
) {
    val tokens: Long get() = input + output + reasoning + cacheRead + cacheWrite

    /** Share of the prompt side served from cache, 0..1; null with no prompt tokens at all. */
    val cacheHitRate: Double?
        get() {
            val prompt = input + cacheRead + cacheWrite
            return if (prompt == 0L) null else cacheRead.toDouble() / prompt
        }

    operator fun plus(turn: TurnUsage) =
        UsageTotals(
            input = input + turn.input,
            output = output + turn.output,
            reasoning = reasoning + turn.reasoning,
            cacheRead = cacheRead + turn.cacheRead,
            cacheWrite = cacheWrite + turn.cacheWrite,
            cost = if (turn.cost == null) cost else (cost ?: 0.0) + turn.cost,
            turns = turns + 1,
        )
}

data class DayUsage(
    val date: LocalDate,
    val totals: UsageTotals,
)

/** A model's or an agent's part of the whole. */
data class UsageShare(
    /** `provider/model` for a model; the [LocalAgent] id, or empty for a remote server, for an agent. */
    val key: String,
    val totals: UsageTotals,
    val chats: Int,
)

data class ChatUsageRow(
    val ref: RuntimeSessionRef,
    val totals: UsageTotals,
    val lastActive: Long,
)

data class UsageReport(
    val totals: UsageTotals,
    /** One entry per calendar day in the range, oldest first, zero days included so a chart has no gaps. */
    val days: List<DayUsage>,
    val models: List<UsageShare>,
    val agents: List<UsageShare>,
    /** The chats that used the most tokens in the range. */
    val topChats: List<ChatUsageRow>,
    val activeChats: Int,
    /** Chats whose runtime could not be read, so their use is missing from the totals. */
    val unreadableChats: Int,
    /** Chats with an agent that does not report usage. */
    val untrackedChats: Int = 0,
    /**
     * How many days the totals cover: the range's length, or for all time the days since the first
     * use. Not capped like [days], so the average matches the totals.
     */
    val spanDays: Int = days.size,
) {
    val busiestDay: DayUsage? get() = days.maxByOrNull { it.totals.tokens }?.takeIf { it.totals.tokens > 0 }

    /** Average tokens per day over the days the totals cover. */
    val dailyAverage: Long get() = if (spanDays <= 0) 0 else totals.tokens / spanDays

    companion object {
        const val TOP_CHATS = 10

        /** The longest a chart goes back for [UsageRange.ALL], so a years-old first chat doesn't squash it. */
        const val MAX_CHART_DAYS = 365

        fun build(
            chats: List<ChatUsage>,
            range: UsageRange,
            now: Long,
            zone: ZoneId = ZoneId.systemDefault(),
        ): UsageReport {
            val today = dayOf(now, zone)
            val firstDay = range.days?.let { today.minusDays(it - 1L) }

            // A turn stamped in the future (a clock that was wrong) counts as today.
            fun dayOfTurn(turn: TurnUsage) = minOf(dayOf(turn.time, zone), today)

            fun inRange(turn: TurnUsage) = firstDay == null || !dayOfTurn(turn).isBefore(firstDay)

            var totals = UsageTotals()
            val byDay = HashMap<LocalDate, UsageTotals>()
            val byModel = HashMap<String, Pair<UsageTotals, MutableSet<String>>>()
            val byAgent = HashMap<String, Pair<UsageTotals, MutableSet<String>>>()
            val rows = mutableListOf<ChatUsageRow>()

            chats.forEach { chat ->
                val chatKey = "${chat.ref.runtimeId}/${chat.ref.session.id}"
                var chatTotals = UsageTotals()
                var last = 0L
                chat.turns.filter(::inRange).forEach { turn ->
                    totals += turn
                    chatTotals += turn
                    last = maxOf(last, turn.time)
                    val day = dayOfTurn(turn)
                    byDay[day] = (byDay[day] ?: UsageTotals()) + turn
                    byModel.add(turn.model, turn, chatKey)
                    byAgent.add(chat.ref.agent?.id.orEmpty(), turn, chatKey)
                }
                if (chatTotals.turns > 0) rows += ChatUsageRow(chat.ref, chatTotals, last)
            }

            val firstUse = byDay.keys.minOrNull() ?: today
            val chartStart = firstDay ?: maxOf(firstUse, today.minusDays(MAX_CHART_DAYS - 1L))
            val days =
                generateSequence(chartStart) { it.plusDays(1) }
                    .takeWhile { !it.isAfter(today) }
                    .map { DayUsage(it, byDay[it] ?: UsageTotals()) }
                    .toList()

            return UsageReport(
                totals = totals,
                days = days,
                models = byModel.toShares(),
                agents = byAgent.toShares(),
                topChats = rows.sortedByDescending { it.totals.tokens }.take(TOP_CHATS),
                activeChats = rows.size,
                unreadableChats = chats.count { it.unreadable },
                untrackedChats = chats.count { it.untracked },
                spanDays = range.days ?: (ChronoUnit.DAYS.between(firstUse, today).toInt() + 1),
            )
        }

        private fun dayOf(
            millis: Long,
            zone: ZoneId,
        ): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

        private fun HashMap<String, Pair<UsageTotals, MutableSet<String>>>.add(
            key: String,
            turn: TurnUsage,
            chat: String,
        ) {
            val (sum, chats) = this[key] ?: (UsageTotals() to mutableSetOf())
            chats += chat
            this[key] = (sum + turn) to chats
        }

        private fun HashMap<String, Pair<UsageTotals, MutableSet<String>>>.toShares(): List<UsageShare> =
            map { (key, value) -> UsageShare(key, value.first, value.second.size) }
                .sortedWith(compareByDescending<UsageShare> { it.totals.tokens }.thenBy { it.key })
    }
}
