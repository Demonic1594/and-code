package com.yugahashimoto.andcode.feature.chat

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test

class EditedInputTest {
    private fun typed(text: String) = TextFieldValue(text, TextRange(text.length))

    @Test
    fun `an empty composer takes the draft whatever its source`() {
        assertEquals(typed("held"), editedInput(typed("  "), "held", joins = true, rewound = false))
        assertEquals(typed("edited"), editedInput(typed(""), "edited", joins = false, rewound = true))
    }

    @Test
    fun `a held prompt taken back joins what is being typed`() {
        assertEquals(typed("half a thought\n\nheld"), editedInput(typed("half a thought  "), "held", joins = true, rewound = false))
    }

    @Test
    fun `a rewind keeps what is typed, an edit replaces it`() {
        assertEquals(typed("typing"), editedInput(typed("typing"), "rewound", joins = false, rewound = true))
        assertEquals(typed("edited"), editedInput(typed("typing"), "edited", joins = false, rewound = false))
    }
}
