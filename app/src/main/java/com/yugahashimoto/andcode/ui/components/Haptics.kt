package com.yugahashimoto.andcode.ui.components

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/** Whether the user left haptic feedback on in Settings → Appearance. Provided by AndCodeApp. */
val LocalHapticsEnabled = compositionLocalOf { true }

enum class HapticEvent {
    /** A send, an approval, a successful save or commit. */
    CONFIRM,

    /** A rejection, a stop, or an action that failed. */
    REJECT,

    /** A long-press that opened a menu. */
    LONG_PRESS,

    /** A small selection change: a chip, a toggle, a pin. */
    TICK,
}

/**
 * Thin wrapper over [View.performHapticFeedback]. It goes through the View rather than Compose's
 * HapticFeedback because the Compose BOM this app pins only exposes LongPress and TextHandleMove,
 * while CONFIRM/REJECT (API 30+) are the system's own patterns for "done" and "refused".
 */
class Haptics internal constructor(
    private val view: View,
    private val enabled: Boolean,
) {
    fun perform(event: HapticEvent) {
        if (!enabled) return
        view.performHapticFeedback(constantFor(event, Build.VERSION.SDK_INT))
    }

    internal companion object {
        fun constantFor(
            event: HapticEvent,
            sdkInt: Int,
        ): Int =
            when (event) {
                HapticEvent.CONFIRM ->
                    if (sdkInt >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
                HapticEvent.REJECT ->
                    if (sdkInt >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
                HapticEvent.LONG_PRESS -> HapticFeedbackConstants.LONG_PRESS
                HapticEvent.TICK -> HapticFeedbackConstants.CLOCK_TICK
            }
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    val enabled = LocalHapticsEnabled.current
    return remember(view, enabled) { Haptics(view, enabled) }
}
