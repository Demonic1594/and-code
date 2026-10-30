package com.yugahashimoto.andcode.feature.chat

import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodeMessageInfo
import com.yugahashimoto.andcode.core.pricing.PriceCatalog
import java.util.Locale

/**
 * Running totals for one chat: every assistant turn's tokens and, where the runtime reports it, what
 * the provider charged. The context ring answers "how full is the window right now"; this answers
 * "what has this chat used so far", the way Claude Code's `/cost` or Cline's task header do.
 */
data class SessionUsage(
    val inputTokens: Long,
    val outputTokens: Long,
    val reasoningTokens: Long,
    val cacheReadTokens: Long,
    /** Null when no turn carried a cost, as opposed to a known zero (a free model). */
    val costUsd: Double?,
    /** The same turns at list prices, for the turns that reported nothing; see [PriceCatalog]. */
    val estimatedCostUsd: Double? = null,
) {
    val totalTokens: Long get() = inputTokens + outputTokens + reasoningTokens + cacheReadTokens
}

/** Null when no assistant turn reported tokens at all, so the UI can hide the row. */
internal fun summarizeSessionUsage(
    messages: List<OpenCodeMessage>,
    catalog: PriceCatalog? = null,
): SessionUsage? {
    val assistant = messages.filterNot { it.info.role.equals("user", ignoreCase = true) }
    val withTokens = assistant.mapNotNull { it.info.tokens }
    if (withTokens.isEmpty()) return null
    val costs = assistant.mapNotNull { it.info.cost }

    // Turns the runtime priced at nothing or not at all are the ones a catalog estimates.
    fun estimateOf(info: OpenCodeMessageInfo): Double? {
        val price = catalog ?: return null
        if ((info.cost != null && info.cost > 0.0) || info.tokens == null) return null
        val model = info.effectiveModel ?: return null
        if (model.providerId.isEmpty()) return null
        return price.estimateOf(
            model.providerId,
            model.modelId,
            info.tokens.input,
            info.tokens.output,
            info.tokens.reasoning,
            info.tokens.cache?.read ?: 0L,
            info.tokens.cache?.write ?: 0L,
        )
    }
    val estimates = if (catalog == null) emptyList() else assistant.mapNotNull { estimateOf(it.info) }
    return SessionUsage(
        inputTokens = withTokens.sumOf { it.input },
        outputTokens = withTokens.sumOf { it.output },
        reasoningTokens = withTokens.sumOf { it.reasoning },
        cacheReadTokens = withTokens.sumOf { it.cache?.read ?: 0L },
        costUsd = costs.takeIf { it.isNotEmpty() }?.sum(),
        estimatedCostUsd = estimates.takeIf { it.isNotEmpty() }?.sum(),
    )
}

/** `950`, `12.3k`, `4.1M`: compact enough for a menu row. */
internal fun formatUsageTokens(count: Long): String =
    when {
        count < 1_000 -> count.toString()
        count < 1_000_000 -> String.format(Locale.US, "%.1fk", count / 1_000.0)
        else -> String.format(Locale.US, "%.1fM", count / 1_000_000.0)
    }

/** Cents for anything a cent or more, four decimals below that so a cheap turn isn't shown as $0.00. */
internal fun formatUsd(amount: Double): String =
    if (amount >= 0.01 || amount == 0.0) {
        String.format(Locale.US, "$%.2f", amount)
    } else {
        String.format(Locale.US, "$%.4f", amount)
    }
