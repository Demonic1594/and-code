package com.yugahashimoto.andcode.core.pricing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The bundled snapshot is what serves estimates until the first weekly refresh, so it must
 * actually parse — with priced entries, not the silent all-defaults decode a key mismatch
 * would produce (the reason this test exists).
 */
class PriceCatalogSnapshotTest {
    @Test
    fun `the bundled snapshot parses with priced models`() {
        val text = repositoryRoot().resolve("app/src/main/assets/pricing/models-dev-snapshot.json").readText()
        val catalog = PriceCatalog.parseSnapshot(text)

        assertTrue("snapshot failed to parse", catalog != null)
        val parsed = catalog!!
        assertTrue("snapshot has no prices", parsed.prices.isNotEmpty())
        assertTrue("snapshot looks like an all-defaults decode", parsed.prices.values.sumOf { it.size } > 1000)
        // The model that motivated the feature: present at the first-party provider, priced.
        val glm = parsed.priceOf("zai", "glm-5.3")
        assertTrue("zai/glm-5.3 missing from the snapshot", glm != null)
        assertTrue("zai/glm-5.3 is not priced", glm!!.output > 0.0)
        // The coding-plan entry exists and is all zero, which is what the fallback reads past.
        val plan = parsed.prices["zai-coding-plan"]?.get("glm-5.3")
        assertEquals(ModelPrice(), plan)
        assertTrue("snapshot carries no fetch date", parsed.fetchedAt != null)
    }

    private fun repositoryRoot(): File {
        var directory = File(System.getProperty("user.dir") ?: error("no working directory"))
        while (true) {
            if (directory.resolve(".git").exists() || directory.resolve("settings.gradle.kts").isFile) return directory
            directory = directory.parentFile ?: error("repository root not found")
        }
    }
}
