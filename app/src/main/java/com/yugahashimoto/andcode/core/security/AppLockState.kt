package com.yugahashimoto.andcode.core.security

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
 */
class AppLockState(
    private val clock: () -> Long = System::currentTimeMillis,
    private val graceMillis: Long = DEFAULT_GRACE_MILLIS,
) {
    private val mutableLocked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = mutableLocked.asStateFlow()

    private var enabled = false
    private var started = false
    private var backgroundedAt: Long? = null

    /** The setting changed, or was read for the first time. */
    fun setEnabled(value: Boolean) {
        val first = !started
        started = true
        enabled = value
        // Turning it on while using the app does not lock what is already open; a fresh process does.
        mutableLocked.value = if (value) first || mutableLocked.value else false
    }

    fun onBackground() {
        backgroundedAt = clock()
    }

    fun onForeground() {
        val since = backgroundedAt ?: return
        backgroundedAt = null
        if (enabled && clock() - since > graceMillis) mutableLocked.value = true
    }

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
