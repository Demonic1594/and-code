package com.yugahashimoto.andcode.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebPageRecoveryTest {
    @Test
    fun `a live page keeps its view and loads what the screen opened with`() {
        val recovery = WebPageRecovery(firstLoad = "https://example.com/")

        assertTrue(recovery.showsView)
        assertNull(recovery.gone)
        assertEquals("https://example.com/", recovery.firstLoad)
    }

    @Test
    fun `once the renderer is gone the view goes, and the page it last showed is the one to reload`() {
        val gone = WebPageRecovery().rendererGone(lastUrl = "http://127.0.0.1:5173/app", fallbackUrl = "http://127.0.0.1:5173/")

        assertFalse(gone.showsView)
        assertEquals(GonePage("http://127.0.0.1:5173/app"), gone.gone)
    }

    @Test
    fun `a view that never showed a web page reloads the address the screen was on`() {
        listOf(null, "", "  ", "about:blank", "chrome://crash", "CHROME://kill").forEach { lastUrl ->
            val gone = WebPageRecovery().rendererGone(lastUrl = lastUrl, fallbackUrl = "https://example.com/")

            assertEquals("for $lastUrl", GonePage("https://example.com/"), gone.gone)
        }
    }

    @Test
    fun `an address in any letter case is still a web page`() {
        val gone = WebPageRecovery().rendererGone(lastUrl = "HTTPS://Example.com/", fallbackUrl = "")

        assertEquals(GonePage("HTTPS://Example.com/"), gone.gone)
    }

    @Test
    fun `with no web page to bring back, reload builds an empty view rather than end the next renderer too`() {
        val gone = WebPageRecovery().rendererGone(lastUrl = "chrome://crash", fallbackUrl = "chrome://kill")

        assertEquals(GonePage(""), gone.gone)
        assertEquals("", gone.rebuild().firstLoad)
    }

    @Test
    fun `a second report for the same dead page keeps the first`() {
        val first = WebPageRecovery().rendererGone(lastUrl = "https://a.example/", fallbackUrl = "")

        val second = first.rendererGone(lastUrl = "https://b.example/", fallbackUrl = "")

        assertEquals(first, second)
    }

    @Test
    fun `reload builds a fresh view that loads the page that went away`() {
        val gone = WebPageRecovery(firstLoad = "https://start.example/").rendererGone("https://later.example/", "")

        val reloaded = gone.rebuild()

        assertTrue(reloaded.showsView)
        assertEquals("https://later.example/", reloaded.firstLoad)
        // A new generation, so the screen keys a new view rather than keeping the dead one's holder.
        assertEquals(gone.generation + 1, reloaded.generation)
    }

    @Test
    fun `a reload without cache has the fresh view clear the cache first, and an ordinary one does not`() {
        val gone = WebPageRecovery().rendererGone("https://a.example/", "")

        val hard = gone.rebuild(clearCache = true)
        val plain = hard.rendererGone("https://a.example/", "").rebuild()

        assertTrue(hard.clearCacheFirst)
        assertEquals("https://a.example/", hard.firstLoad)
        assertFalse(plain.clearCacheFirst)
        assertFalse(WebPageRecovery().clearCacheFirst)
    }

    @Test
    fun `each rebuild is a generation of its own, and a page gone again can come back again`() {
        val once = WebPageRecovery().rendererGone("https://a.example/", "").rebuild()

        val twice = once.rendererGone("https://a.example/", "").rebuild()

        assertEquals(2, twice.generation)
        assertEquals("https://a.example/", twice.firstLoad)
    }

    @Test
    fun `another address opened while the page is gone loads in the fresh view instead`() {
        val gone = WebPageRecovery().rendererGone("https://crashed.example/", "")

        val opened = gone.rebuild("https://elsewhere.example/")

        assertTrue(opened.showsView)
        assertEquals("https://elsewhere.example/", opened.firstLoad)
    }
}
