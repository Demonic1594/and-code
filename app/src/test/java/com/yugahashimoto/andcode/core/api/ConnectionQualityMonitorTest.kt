package com.yugahashimoto.andcode.core.api

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the foreground gate added to the 30 s probe loop: a backgrounded chat has nobody to show
 * the result to, so the loop must park on `awaitForeground` rather than keep polling.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionQualityMonitorTest {
    @Test
    fun `probe loop parks until the app is back in the foreground`() =
        runTest {
            // The probe loop runs forever by design, so it is launched on its own scope - cancelled
            // by hand at the end - rather than runTest's own, which requires every coroutine it
            // launches to have completed once the test body returns.
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            var probes = 0
            val foreground = CompletableDeferred<Unit>()
            val monitor = ConnectionQualityMonitor(scope = scope, awaitForeground = { foreground.await() })
            try {
                monitor.startMonitoring { probes++ }
                // Long past the probe interval: with nobody resolving the gate, no probe may fire.
                advanceTimeBy(60_000L)
                runCurrent()
                assertEquals(0, probes)

                foreground.complete(Unit)
                runCurrent()
                assertEquals(1, probes)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `default gate never parks the probe loop`() =
        runTest {
            // The no-op default is what keeps every other test - which never wires a gate - polling
            // on its virtual clock exactly as before this change: the very first probe must not
            // wait on anything.
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            var probes = 0
            val monitor = ConnectionQualityMonitor(scope = scope)
            try {
                monitor.startMonitoring { probes++ }
                runCurrent()

                assertEquals(1, probes)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `recordStreamTokens counts chunks and closes the rate window on the injected clock`() =
        runTest {
            var nowNanos = 0L
            val monitor =
                ConnectionQualityMonitor(
                    scope = TestScope(StandardTestDispatcher(testScheduler)),
                    clockNanos = { nowNanos },
                )

            // Three coalesced events carrying 4, 5 and 1 chunks; non-positive counts are noise.
            monitor.recordStreamTokens(4)
            monitor.recordStreamTokens(5)
            monitor.recordStreamTokens(0)
            monitor.recordStreamTokens(-1)
            monitor.recordStreamToken()

            // Still inside the 1 s window: nothing published yet.
            assertEquals(0.0, monitor.quality.value.tokensPerSecond, 0.0)

            // The next token arrives 1.1 s later and closes the window. It counts too - the
            // implementation adds before computing the rate - so 11 chunks over 1.1 s.
            nowNanos += 1_100_000_000L
            monitor.recordStreamToken()
            assertEquals(11.0 / 1.1, monitor.quality.value.tokensPerSecond, 1e-9)
        }

    @Test
    fun `a silence longer than the window closes it on the active span, not the gap`() =
        runTest {
            var nowNanos = 0L
            val monitor =
                ConnectionQualityMonitor(
                    scope = TestScope(StandardTestDispatcher(testScheduler)),
                    clockNanos = { nowNanos },
                )

            // A burst that streams for 0.2 s and then goes quiet for a tool call.
            monitor.recordStreamTokens(5)
            nowNanos += 100_000_000L
            monitor.recordStreamTokens(5)
            nowNanos += 100_000_000L
            monitor.recordStreamTokens(5)
            assertEquals(0.0, monitor.quality.value.tokensPerSecond, 0.0)

            // The next chunk only arrives a minute later. The stale window must be closed on
            // the 0.2 s it was actually active (15 / 0.2 = 75 tok/s), never on the 59.8 s gap -
            // dividing by the gap read rates 10-100x low and poisoned the EMA for turns after.
            nowNanos += 59_800_000_000L
            monitor.recordStreamTokens(1)
            assertEquals(75.0, monitor.quality.value.tokensPerSecond, 1e-9)
        }

    @Test
    fun `flushStreamRate publishes a short turn without waiting for a closing chunk`() =
        runTest {
            var nowNanos = 0L
            val monitor =
                ConnectionQualityMonitor(
                    scope = TestScope(StandardTestDispatcher(testScheduler)),
                    clockNanos = { nowNanos },
                )

            // Most replies stream for well under the 1 s window.
            monitor.recordStreamTokens(8)
            nowNanos += 300_000_000L
            monitor.recordStreamTokens(8)

            monitor.flushStreamRate()

            assertEquals(16.0 / 0.3, monitor.quality.value.tokensPerSecond, 1e-9)

            // Flushing again with no window open is a no-op, so a run-end hook can call it
            // freely on every isRunning transition.
            monitor.flushStreamRate()
            assertEquals(16.0 / 0.3, monitor.quality.value.tokensPerSecond, 1e-9)
        }

    @Test
    fun `a burst shorter than the minimum span is not published`() =
        runTest {
            var nowNanos = 0L
            val monitor =
                ConnectionQualityMonitor(
                    scope = TestScope(StandardTestDispatcher(testScheduler)),
                    clockNanos = { nowNanos },
                )

            monitor.recordStreamTokens(3)
            monitor.flushStreamRate()

            assertEquals(0.0, monitor.quality.value.tokensPerSecond, 0.0)
        }
}
