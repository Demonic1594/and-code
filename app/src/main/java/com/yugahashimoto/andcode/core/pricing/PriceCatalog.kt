package com.yugahashimoto.andcode.core.pricing

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What a model charges per million tokens, in USD. A missing field prices nothing. */
@Serializable
data class ModelPrice(
    val input: Double = 0.0,
    val output: Double = 0.0,
    val cacheRead: Double = 0.0,
    val cacheWrite: Double = 0.0,
)

/** The compact stored form of a snapshot: when it was taken, and provider → model → price. */
@Serializable
data class PriceCatalog(
    /** Epoch ms of the fetch this snapshot came from; null when unknown. */
    val fetchedAt: Long? = null,
    val prices: Map<String, Map<String, ModelPrice>> = emptyMap(),
) {
    val isEmpty: Boolean get() = prices.isEmpty()

    /**
     * The price of [modelId] at [providerId]. An entry priced at nothing on every field — the
     * way the catalog marks plan-based access, where per-token billing does not apply — reads as
     * absent so the caller falls through to the same model's first-party list price, which is
     * the honest answer to "what would this have cost".
     */
    fun priceOf(
        providerId: String,
        modelId: String,
    ): ModelPrice? {
        prices[providerId]?.get(modelId)?.takeIf { it.priced }?.let { return it }
        return firstPartyPriceOf(modelId)
    }

    /** The first-party price of [modelId], from whichever lab's provider entry has one. */
    private fun firstPartyPriceOf(modelId: String): ModelPrice? =
        FIRST_PARTY_PROVIDERS.firstNotNullOfOrNull { provider ->
            prices[provider]?.get(modelId)?.takeIf { it.priced }
        }

    /**
     * The estimated cost of a turn, in USD: tokens × price / 1e6. Reasoning tokens bill at the
     * output rate, the way providers that price thinking as output do. Null when no price fits.
     */
    fun estimateOf(
        providerId: String,
        modelId: String,
        input: Long,
        output: Long,
        reasoning: Long,
        cacheRead: Long,
        cacheWrite: Long,
    ): Double? {
        val price = priceOf(providerId, modelId) ?: return null
        return (
            input * price.input +
                (output + reasoning) * price.output +
                cacheRead * price.cacheRead +
                cacheWrite * price.cacheWrite
        ) / 1_000_000.0
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Parses the compact snapshot form ([PriceCatalog] itself is that form). */
        fun parseSnapshot(text: String): PriceCatalog? = runCatching { json.decodeFromString(serializer(), text) }.getOrNull()

        /**
         * Parses the full models.dev `api.json` document into a [PriceCatalog], keeping only the
         * cost fields — a few hundred kilobytes of prices out of megabytes of model metadata.
         * [fetchedAt] is the caller's clock: the document carries no fetch time of its own.
         */
        fun fromModelsDev(
            text: String,
            fetchedAt: Long,
        ): PriceCatalog? =
            runCatching {
                val root = json.parseToJsonElement(text).let { it as? kotlinx.serialization.json.JsonObject } ?: return null
                val prices =
                    buildMap {
                        for ((providerId, provider) in root) {
                            if (providerId.startsWith("_")) continue
                            val models =
                                (provider as? kotlinx.serialization.json.JsonObject)?.get(
                                    "models",
                                ) as? kotlinx.serialization.json.JsonObject ?: continue
                            val priced =
                                buildMap {
                                    for ((modelId, model) in models) {
                                        val cost =
                                            (model as? kotlinx.serialization.json.JsonObject)?.get(
                                                "cost",
                                            ) as? kotlinx.serialization.json.JsonObject ?: continue
                                        val price =
                                            ModelPrice(
                                                input = cost.doubleOf("input") ?: 0.0,
                                                output = cost.doubleOf("output") ?: 0.0,
                                                cacheRead = cost.doubleOf("cache_read") ?: 0.0,
                                                cacheWrite = cost.doubleOf("cache_write") ?: 0.0,
                                            )
                                        if (price.priced) put(modelId, price)
                                    }
                                }
                            if (priced.isNotEmpty()) put(providerId, priced)
                        }
                    }
                PriceCatalog(fetchedAt = fetchedAt, prices = prices)
            }.getOrNull()

        private fun kotlinx.serialization.json.JsonObject.doubleOf(key: String): Double? =
            (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()
    }
}

private val ModelPrice.priced: Boolean
    get() = input > 0.0 || output > 0.0 || cacheRead > 0.0 || cacheWrite > 0.0

/**
 * The labs themselves, in fallback order: when a provider entry prices a model at nothing (plan
 * access) or a mirror names a model the catalog has never heard of, the same model id at its
 * lab is the price the estimate uses. Routers and resellers are deliberately absent — a random
 * zero-price mirror must not decide the estimate.
 */
private val FIRST_PARTY_PROVIDERS =
    listOf(
        "openai",
        "anthropic",
        "google",
        "zai",
        "zhipuai",
        "xai",
        "moonshotai",
        "deepseek",
        "alibaba",
        "mistral",
        "minimax",
        "meta",
        "qwen",
    )
