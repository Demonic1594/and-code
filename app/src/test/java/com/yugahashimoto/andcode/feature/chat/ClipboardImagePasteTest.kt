package com.yugahashimoto.andcode.feature.chat

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardImagePasteTest {
    @Test
    fun `an image-only clipboard is pasted as an image`() {
        assertTrue(shouldPasteImage(hasText = false, hasImage = true))
    }

    @Test
    fun `text on the clipboard keeps the normal text paste`() {
        assertFalse(shouldPasteImage(hasText = true, hasImage = true))
        assertFalse(shouldPasteImage(hasText = true, hasImage = false))
    }

    @Test
    fun `an empty clipboard pastes nothing`() {
        assertFalse(shouldPasteImage(hasText = false, hasImage = false))
    }

    @Test
    fun `ctrl+v and shift+insert are the paste shortcuts`() {
        assertTrue(isPasteShortcut(Key.V, ctrl = true, shift = false))
        assertTrue(isPasteShortcut(Key.Insert, ctrl = false, shift = true))
    }

    @Test
    fun `other keys and unmodified keys are not paste shortcuts`() {
        assertFalse(isPasteShortcut(Key.V, ctrl = false, shift = false))
        assertFalse(isPasteShortcut(Key.C, ctrl = true, shift = false))
        assertFalse(isPasteShortcut(Key.Insert, ctrl = true, shift = false))
        assertFalse(isPasteShortcut(Key.V, ctrl = false, shift = true))
    }

    @Test
    fun `toolbar offers paste for a clipboard image the text field would not offer`() {
        val delegate = RecordingToolbar()
        var pasted = 0
        val toolbar = ImagePasteTextToolbar(delegate, canPasteImage = { true }, onPasteImage = { pasted++ })

        toolbar.showMenu(RECT, onPasteRequested = null)

        val offered = delegate.shownPaste
        assertNotNull(offered)
        offered!!.invoke()
        assertEquals(1, pasted)
        assertEquals("pasting must dismiss the menu like the stock paste action does", 1, delegate.hideCount)
    }

    @Test
    fun `toolbar leaves the text paste alone when the text field offers one`() {
        val delegate = RecordingToolbar()
        val textPaste: () -> Unit = {}
        val toolbar = ImagePasteTextToolbar(delegate, canPasteImage = { true }, onPasteImage = { error("not an image paste") })

        toolbar.showMenu(RECT, onPasteRequested = textPaste)

        assertSame(textPaste, delegate.shownPaste)
    }

    @Test
    fun `toolbar offers no paste when the clipboard has no image`() {
        val delegate = RecordingToolbar()
        val toolbar = ImagePasteTextToolbar(delegate, canPasteImage = { false }, onPasteImage = { error("not an image paste") })

        toolbar.showMenu(RECT, onPasteRequested = null)

        assertNull(delegate.shownPaste)
    }

    @Test
    fun `toolbar forwards the other actions and the status untouched`() {
        val delegate = RecordingToolbar()
        val copy: () -> Unit = {}
        val cut: () -> Unit = {}
        val selectAll: () -> Unit = {}
        val toolbar = ImagePasteTextToolbar(delegate, canPasteImage = { true }, onPasteImage = {})

        toolbar.showMenu(RECT, onCopyRequested = copy, onCutRequested = cut, onSelectAllRequested = selectAll)

        assertSame(copy, delegate.shownCopy)
        assertSame(cut, delegate.shownCut)
        assertSame(selectAll, delegate.shownSelectAll)
        assertEquals(TextToolbarStatus.Shown, toolbar.status)
        toolbar.hide()
        assertEquals(TextToolbarStatus.Hidden, toolbar.status)
    }

    private class RecordingToolbar : TextToolbar {
        var shownCopy: (() -> Unit)? = null
        var shownPaste: (() -> Unit)? = null
        var shownCut: (() -> Unit)? = null
        var shownSelectAll: (() -> Unit)? = null
        var hideCount = 0
        override var status: TextToolbarStatus = TextToolbarStatus.Hidden
            private set

        override fun showMenu(
            rect: Rect,
            onCopyRequested: (() -> Unit)?,
            onPasteRequested: (() -> Unit)?,
            onCutRequested: (() -> Unit)?,
            onSelectAllRequested: (() -> Unit)?,
        ) {
            shownCopy = onCopyRequested
            shownPaste = onPasteRequested
            shownCut = onCutRequested
            shownSelectAll = onSelectAllRequested
            status = TextToolbarStatus.Shown
        }

        override fun hide() {
            hideCount++
            status = TextToolbarStatus.Hidden
        }
    }

    private companion object {
        val RECT = Rect(0f, 0f, 10f, 10f)
    }
}
