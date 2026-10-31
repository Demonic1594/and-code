package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * [ClaudeCodeRuntime.permissionBridgeReady] backs the target's `capabilities` getter, which Compose
 * reads on the main thread. Crashlytics showed that thread parked on the runtime write lock for the
 * whole of an install, because the answer was derived through the installer.
 */
class ClaudePermissionBridgeReadinessTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun rootfs(): File = File(folder.root, "environment/rootfs").apply { mkdirs() }

    private fun runtime(
        coordinator: LocalRuntimeAccessCoordinator = LocalRuntimeAccessCoordinator(),
        provider: () -> LocalRuntimeInstaller.InstalledRuntime? = { error("a readiness check must not provision the runtime") },
    ) = ClaudeCodeRuntime(folder.root, installedRuntimeProvider = provider, accessCoordinator = coordinator)

    @Test
    fun `is ready when the hook is installed, without asking the installer`() {
        assertTrue(ClaudePermissionHooks.installInto(rootfs(), "#!/bin/sh\n"))

        assertTrue(runtime().permissionBridgeReady())
    }

    @Test
    fun `is not ready when the sandbox has no hook`() {
        rootfs()

        assertFalse(runtime().permissionBridgeReady())
    }

    @Test
    fun `is not ready when nothing is installed`() {
        assertFalse(runtime().permissionBridgeReady())
    }

    @Test
    fun `does not wait for an install that holds the runtime write lock`() {
        ClaudePermissionHooks.installInto(rootfs(), "#!/bin/sh\n")
        val coordinator = LocalRuntimeAccessCoordinator()
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val installer =
            Thread {
                coordinator.write {
                    holding.countDown()
                    release.await(30, TimeUnit.SECONDS)
                }
            }.apply { start() }
        assertTrue(holding.await(5, TimeUnit.SECONDS))
        // The provider takes the write lock exactly as LocalRuntimeInstaller.installedRuntime does.
        val caller = Executors.newSingleThreadExecutor()
        try {
            val answer = caller.submit<Boolean> { runtime(coordinator) { coordinator.write { null } }.permissionBridgeReady() }

            assertTrue(answer.get(3, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            installer.join()
            caller.shutdownNow()
        }
    }
}
