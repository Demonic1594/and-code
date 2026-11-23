package com.yugahashimoto.andcode.live

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.yugahashimoto.andcode.runtime.LocalRuntimeStatus
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The local runtime installs from its real release assets and its OpenCode server comes up. Needs
 * no key, but downloads for many minutes, so it runs only in the live workflow.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class LiveRuntimeInstallTest {
    @Test
    fun installsAndStartsTheLocalRuntime() {
        LiveAgents.assumeLiveRun()

        LiveAgents.ensureRuntimeInstalled()

        val manager = LiveAgents.app.localRuntimeManager
        assertTrue("the runtime is not ready: ${manager.state.value}", manager.state.value is LocalRuntimeStatus.Ready)
        assertTrue("the OpenCode server does not answer on its port", manager.isHealthy())
    }
}
