package com.yugahashimoto.andcode.core.util

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

private const val INTERVAL_MILLIS = 250L

/**
 * Covers the gate every always-on file poll in the app now sits behind: a loop that has nothing to
 * watch for must be a suspended coroutine, not a timer that keeps waking the process every few
 * hundred milliseconds for the rest of its life.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PollWhileActiveTest {
    @Test
    fun `never polls while the gate stays closed`() =
        runTest {
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            val gate = MutableStateFlow(false)
            var polls = 0
            try {
                scope.launch { gate.pollWhileActive(INTERVAL_MILLIS) { polls++ } }
                advanceTimeBy(100 * INTERVAL_MILLIS)
                runCurrent()

                assertEquals(0, polls)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `polls immediately when the gate is open and then once per interval`() =
        runTest {
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            val gate = MutableStateFlow(true)
            var polls = 0
            try {
                scope.launch { gate.pollWhileActive(INTERVAL_MILLIS) { polls++ } }
                runCurrent()
                assertEquals(1, polls)

                advanceTimeBy(INTERVAL_MILLIS)
                runCurrent()
                assertEquals(2, polls)

                advanceTimeBy(INTERVAL_MILLIS)
                runCurrent()
                assertEquals(3, polls)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `stops polling as soon as the gate closes`() =
        runTest {
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            val gate = MutableStateFlow(true)
            var polls = 0
            try {
                scope.launch { gate.pollWhileActive(INTERVAL_MILLIS) { polls++ } }
                runCurrent()
                assertEquals(1, polls)

                gate.value = false
                runCurrent()
                advanceTimeBy(100 * INTERVAL_MILLIS)
                runCurrent()

                assertEquals(1, polls)
            } finally {
                scope.cancel()
            }
        }

    /**
     * Returning to the app has to pick up whatever arrived while it was away, so a reopened gate
     * polls at once instead of first waiting out an interval.
     */
    @Test
    fun `polls straight away when the gate reopens`() =
        runTest {
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            val gate = MutableStateFlow(true)
            var polls = 0
            try {
                scope.launch { gate.pollWhileActive(INTERVAL_MILLIS) { polls++ } }
                runCurrent()
                gate.value = false
                runCurrent()
                advanceTimeBy(10 * INTERVAL_MILLIS)
                runCurrent()
                assertEquals(1, polls)

                gate.value = true
                runCurrent()

                assertEquals(2, polls)
            } finally {
                scope.cancel()
            }
        }
}
