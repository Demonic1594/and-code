package com.yugahashimoto.andcode.data.repository

import com.yugahashimoto.andcode.core.pricing.ModelPrice
import com.yugahashimoto.andcode.core.pricing.PriceCatalog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class PriceCatalogRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val seed =
        PriceCatalog(
            fetchedAt = 1_000L,
            prices = mapOf("zai" to mapOf("glm-5.3" to ModelPrice(input = 1.4, output = 4.4))),
        ).let { catalogJson.encodeToString(PriceCatalog.serializer(), it) }

    private val fetched =
        """
        {"zai": {"models": {"glm-5.3": {"cost": {"input": 2.0, "output": 5.0}}}}}
        """.trimIndent()

    private fun repository(
        scope: TestScope,
        now: () -> Long,
        fetch: suspend () -> String?,
    ): PriceCatalogRepository =
        PriceCatalogRepository(
            scope = scope,
            seedJson = { seed },
            storageDir = folder.root,
            fetch = fetch,
            now = now,
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        )

    @Test
    fun `the bundled snapshot serves until the first refresh`() =
        test {
            val repo = repository(this, now = { 60_000L }, fetch = { error("not due") })

            // The seed loads off the caller's thread; drive the scheduler to it.
            advanceUntilIdle()
            val catalog = repo.catalog.value

            assertNotNull(catalog)
            assertEquals(1.4, catalog!!.priceOf("zai", "glm-5.3")!!.input, 1e-9)
        }

    @Test
    fun `a stale catalog refreshes, stores and publishes`() =
        test {
            var fetches = 0
            val repo =
                repository(
                    this,
                    // The seed is a week and a day old.
                    now = { 1_000L + PriceCatalogRepository.DEFAULT_REFRESH_INTERVAL_MILLIS + 1 },
                    fetch = {
                        fetches++
                        fetched
                    },
                )

            repo.refreshIfDue()
            advanceUntilIdle()

            assertEquals(1, fetches)
            val catalog = repo.catalog.value!!
            assertEquals(2.0, catalog.priceOf("zai", "glm-5.3")!!.input, 1e-9)
            // Stored for the next start, atomically: no tmp file left behind.
            val stored = folder.root.resolve(PriceCatalogRepository.FILE_NAME)
            assertEquals(catalog, PriceCatalog.parseSnapshot(stored.readText()))
            assertNull(folder.root.resolve("${PriceCatalogRepository.FILE_NAME}.tmp").takeIf { it.exists() })
        }

    @Test
    fun `a fresh catalog does not fetch`() =
        test {
            var fetches = 0
            val repo =
                repository(
                    this,
                    now = { 1_000L + 60_000L },
                    fetch = {
                        fetches++
                        fetched
                    },
                )

            // The initial load lands first; refreshIfDue ran before it in principle, but the
            // refresh re-checks freshness under the mutex and finds nothing to do.
            repo.refreshIfDue()
            advanceUntilIdle()

            assertEquals(0, fetches)
        }

    @Test
    fun `a failed refresh keeps the last good copy`() =
        test {
            val repo =
                repository(
                    this,
                    now = { 1_000L + PriceCatalogRepository.DEFAULT_REFRESH_INTERVAL_MILLIS + 1 },
                    fetch = { null },
                )

            repo.refreshIfDue()
            advanceUntilIdle()

            // Still the seed, and still due, so the next open retries.
            assertEquals(1.4, repo.catalog.value!!.priceOf("zai", "glm-5.3")!!.input, 1e-9)
        }

    @Test
    fun `a stored catalog from a previous start wins over the bundled seed`() =
        test {
            // First start: refresh and store.
            val first =
                repository(
                    this,
                    now = { 1_000L + PriceCatalogRepository.DEFAULT_REFRESH_INTERVAL_MILLIS + 1 },
                    fetch = { fetched },
                )
            first.refreshIfDue()
            advanceUntilIdle()
            val stored = first.catalog.value

            // Second start, same storage: the stored copy loads, and it is fresh.
            var fetches = 0
            val second =
                repository(
                    this,
                    now = { 1_000L + PriceCatalogRepository.DEFAULT_REFRESH_INTERVAL_MILLIS + 2 },
                    fetch = {
                        fetches++
                        fetched
                    },
                )
            // The stored copy loads off this thread too; drive the scheduler before reading.
            advanceUntilIdle()
            assertEquals(stored, second.catalog.value)
            second.refreshIfDue()
            advanceUntilIdle()
            assertEquals(0, fetches)
        }

    /**
     * The repository loads its stored copy eagerly in `init` and launches refreshes on the
     * injected scope, so everything runs inside one [TestScope] driven to quiescence by the
     * body's own [advanceUntilIdle] calls.
     */
    private fun test(body: suspend TestScope.() -> Unit) = runTest { body() }

    private companion object {
        private val catalogJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    }
}
