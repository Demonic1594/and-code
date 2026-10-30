package com.yugahashimoto.andcode.core.pricing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PriceCatalogTest {
    @Test
    fun `an exact provider entry is used as-is`() {
        val catalog =
            PriceCatalog(
                prices =
                    mapOf(
                        "zai" to mapOf("glm-5.3" to ModelPrice(input = 1.4, output = 4.4, cacheRead = 0.26)),
                    ),
            )

        assertEquals(ModelPrice(input = 1.4, output = 4.4, cacheRead = 0.26), catalog.priceOf("zai", "glm-5.3"))
    }

    @Test
    fun `an entry priced at nothing falls back to the first-party price of the same model`() {
        val catalog =
            PriceCatalog(
                prices =
                    mapOf(
                        // Plan access: per-token billing does not apply, so the catalog marks it 0.
                        "zai-coding-plan" to mapOf("glm-5.3" to ModelPrice()),
                        "zai" to mapOf("glm-5.3" to ModelPrice(input = 1.4, output = 4.4, cacheRead = 0.26)),
                        // A reseller's zero-price mirror must not be picked instead.
                        "random-router" to mapOf("glm-5.3" to ModelPrice()),
                    ),
            )

        assertEquals(ModelPrice(input = 1.4, output = 4.4, cacheRead = 0.26), catalog.priceOf("zai-coding-plan", "glm-5.3"))
    }

    @Test
    fun `an unknown model has no price`() {
        val catalog = PriceCatalog(prices = mapOf("zai" to mapOf("glm-5.3" to ModelPrice(input = 1.4, output = 4.4))))

        assertNull(catalog.priceOf("zai", "glm-9.9"))
        assertNull(catalog.priceOf("nowhere", "glm-5.3"))
    }

    @Test
    fun `the estimate prices tokens, cache and reasoning`() {
        val catalog =
            PriceCatalog(
                prices =
                    mapOf(
                        "zai" to mapOf("glm-5.3" to ModelPrice(input = 1.0, output = 2.0, cacheRead = 0.5, cacheWrite = 0.25)),
                    ),
            )

        // 1M input + 1M output + 0.5M reasoning (billed as output) + 1M cache read + 2M cache write.
        val estimate =
            catalog.estimateOf(
                providerId = "zai",
                modelId = "glm-5.3",
                input = 1_000_000,
                output = 1_000_000,
                reasoning = 500_000,
                cacheRead = 1_000_000,
                cacheWrite = 2_000_000,
            )

        assertEquals(1.0 + 2.0 + 1.0 + 0.5 + 0.5, estimate!!, 1e-9)
    }

    @Test
    fun `the full models dev document keeps only priced costs`() {
        val document =
            """
            {
              "_meta": {"skip": true},
              "zai": {
                "name": "Z.AI",
                "models": {
                  "glm-5.3": {"name": "GLM 5.3", "cost": {"input": 1.4, "output": 4.4, "cache_read": 0.26, "cache_write": 0}},
                  "glm-free": {"name": "Free", "cost": {"input": 0, "output": 0, "cache_read": 0, "cache_write": 0}},
                  "no-cost": {"name": "Nothing said"}
                }
              },
              "mirror": {
                "models": {"glm-5.3": {"cost": {"input": 0, "output": 0}}}
              }
            }
            """.trimIndent()

        val catalog = PriceCatalog.fromModelsDev(document, fetchedAt = 123L)!!

        assertEquals(123L, catalog.fetchedAt)
        // The all-zero entries (free tier, mirror) are dropped from the stored form; the
        // fallback for them goes through the first-party provider instead.
        assertEquals(mapOf("glm-5.3" to ModelPrice(input = 1.4, output = 4.4, cacheRead = 0.26)), catalog.prices["zai"])
        assertNull(catalog.prices["mirror"])
        // A mirror priced at nothing still estimates at the first-party rate.
        assertEquals(1.4, catalog.priceOf("mirror", "glm-5.3")!!.input, 1e-9)
    }

    @Test
    fun `a broken document parses to nothing rather than a half catalog`() {
        assertNull(PriceCatalog.fromModelsDev("not json", fetchedAt = 0))
        assertNull(PriceCatalog.parseSnapshot("also not json"))
    }

    @Test
    fun `the snapshot form round-trips`() {
        val catalog = PriceCatalog(fetchedAt = 5L, prices = mapOf("zai" to mapOf("glm-5.3" to ModelPrice(input = 1.4, output = 4.4))))

        val parsed = PriceCatalog.parseSnapshot(catalogJson.encodeToString(PriceCatalog.serializer(), catalog))!!

        assertEquals(catalog, parsed)
    }

    private companion object {
        private val catalogJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    }
}
