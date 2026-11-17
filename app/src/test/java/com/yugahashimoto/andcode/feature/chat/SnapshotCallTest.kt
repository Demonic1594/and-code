package com.yugahashimoto.andcode.feature.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** [snapshotCall]: a failed snapshot costs its result, never the send; cancellation still gets out. */
class SnapshotCallTest {
    @Test
    fun `gives the call's own result when it succeeds`() =
        runTest {
            assertEquals("restored", snapshotCall("fallback") { "restored" })
        }

    @Test
    fun `gives the fallback when the call fails`() =
        runTest {
            assertEquals("fallback", snapshotCall("fallback") { throw IOException("disk full") })
        }

    @Test
    fun `lets cancellation through instead of swallowing it`() =
        runTest {
            try {
                snapshotCall("fallback") { throw CancellationException("left the screen") }
                fail("cancellation was swallowed")
            } catch (cancelled: CancellationException) {
                assertTrue(cancelled.message == "left the screen")
            }
        }
}
