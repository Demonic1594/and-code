package com.yugahashimoto.andcode.core.security

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the app is locked behind the device's biometric or screen lock.
 *
 * Lives on the Application, not the activity, so rotating the screen or the activity being
 * recreated never unlocks it. It locks when the process starts with the lock on, and when the app
 * comes back after more than [graceMillis] away. The grace period lets the share sheet, a browser
 * sign-in or a quick look at another app pass without asking again.
 *
 * Called from the main thread (the foreground events, the unlock prompt) and at start-up; the methods
 * are synchronized so a check and its update are never split by another call.
 */
class AppLockState(
    /** Time since boot, sleep included: changing the phone's clock can neither skip nor force the lock. */
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val graceMillis: Long = DEFAULT_GRACE_MILLIS,
) {
    private val mutableLocked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = mutableLocked.asStateFlow()

    private var enabled = false
    private var started = false
    private var backgroundedAt: Long? = null

    /** The setting changed, or was read for the first time. */
    @Synchronized
    fun setEnabled(value: Boolean) {
        val first = !started
        started = true
        enabled = value
        // Turning it on while using the app does not lock what is already open; a fresh process does.
        mutableLocked.value = if (value) first || mutableLocked.value else false
    }

    /**
     * The app left the screen. Both the activity's stop and the process's (delayed) stop report it;
     * the earliest one counts, so a phone put to sleep straight away still counts its sleep.
     */
    @Synchronized
    fun onBackground() {
        if (backgroundedAt == null) backgroundedAt = clock()
    }

    @Synchronized
    fun onForeground() {
        val since = backgroundedAt ?: return
        backgroundedAt = null
        if (enabled && clock() - since > graceMillis) mutableLocked.value = true
    }

    @Synchronized
    fun unlock() {
        mutableLocked.value = false
        // Entering the PIN takes the user to the system's own screen, which counts as leaving the
        // app; coming back from it must not lock again what was just unlocked.
        backgroundedAt = null
    }

    companion object {
        const val DEFAULT_GRACE_MILLIS = 30_000L
    }
}
