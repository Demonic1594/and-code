package com.yugahashimoto.andcode.ui.components

import android.view.HapticFeedbackConstants
import org.junit.Assert.assertEquals
import org.junit.Test

class HapticsTest {
    @Test
    fun `confirm and reject use the system patterns from Android 11`() {
        assertEquals(HapticFeedbackConstants.CONFIRM, Haptics.constantFor(HapticEvent.CONFIRM, 30))
        assertEquals(HapticFeedbackConstants.REJECT, Haptics.constantFor(HapticEvent.REJECT, 30))
    }

    @Test
    fun `older releases fall back to patterns they have`() {
        assertEquals(HapticFeedbackConstants.VIRTUAL_KEY, Haptics.constantFor(HapticEvent.CONFIRM, 29))
        assertEquals(HapticFeedbackConstants.LONG_PRESS, Haptics.constantFor(HapticEvent.REJECT, 29))
    }
}
