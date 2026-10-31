package com.yugahashimoto.andcode.core.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockStateTest {
    private var now = 0L
    private val lock = AppLockState(clock = { now }, graceMillis = 30_000L)

    @Test
    fun `a fresh process with the lock on starts locked`() {
        lock.setEnabled(true)
        assertTrue(lock.locked.value)
        lock.unlock()
        assertFalse(lock.locked.value)
    }

    @Test
    fun `with the lock off nothing ever locks`() {
        lock.setEnabled(false)
        lock.onBackground()
        now += 60 * 60_000L
        lock.onForeground()
        assertFalse(lock.locked.value)
    }

    @Test
    fun `turning it on while in the app does not lock what is open`() {
        lock.setEnabled(false)
        lock.setEnabled(true)
        assertFalse(lock.locked.value)
    }

    @Test
    fun `coming back within the grace period stays unlocked, after it locks`() {
        lock.setEnabled(true)
        lock.unlock()

        lock.onBackground()
        now += 30_000L
        lock.onForeground()
        assertFalse("exactly the grace period is still within it", lock.locked.value)

        lock.onBackground()
        now += 30_001L
        lock.onForeground()
        assertTrue(lock.locked.value)
    }

    @Test
    fun `a foreground without a background before it never locks`() {
        lock.setEnabled(true)
        lock.unlock()
        now += 10 * 60_000L
        lock.onForeground()
        assertFalse(lock.locked.value)
    }

    @Test
    fun `unlocking with the device pin, which leaves the app, does not lock again on return`() {
        lock.setEnabled(true)
        lock.onBackground()
        now += 60_000L
        lock.unlock()
        lock.onForeground()
        assertFalse(lock.locked.value)
    }

    @Test
    fun `a slow pin entry that returns before the unlock callback ends unlocked`() {
        lock.setEnabled(true)
        lock.unlock()
        lock.onBackground()
        now += 60_000L
        lock.onForeground()
        assertTrue("away longer than the grace period", lock.locked.value)
        lock.unlock()
        assertFalse(lock.locked.value)
    }

    @Test
    fun `turning it off unlocks`() {
        lock.setEnabled(true)
        lock.setEnabled(false)
        assertFalse(lock.locked.value)
    }
}
