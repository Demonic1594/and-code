package com.yugahashimoto.andcode.feature.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PreviewEntryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `prefers the shallowest index html`() {
        val root = temporaryFolder.newFolder()
        File(root, "index.html").writeText("")
        File(root, "public").mkdirs()
        File(root, "public/index.html").writeText("")

        assertEquals("index.html", findPreviewEntry(root))
    }

    @Test
    fun `finds a nested entry when the root has none`() {
        val root = temporaryFolder.newFolder()
        File(root, "public").mkdirs()
        File(root, "public/index.html").writeText("")

        assertEquals("public/index.html", findPreviewEntry(root))
    }

    @Test
    fun `skips vendored directories`() {
        val root = temporaryFolder.newFolder()
        File(root, "node_modules/pkg").mkdirs()
        File(root, "node_modules/pkg/index.html").writeText("")
        File(root, "site").mkdirs()
        File(root, "site/index.html").writeText("")

        assertEquals("site/index.html", findPreviewEntry(root))
    }

    @Test
    fun `returns null without an entry page`() {
        val root = temporaryFolder.newFolder()
        File(root, "app.js").writeText("")

        assertNull(findPreviewEntry(root))
    }

    /** The site is served without following links, so an entry behind one is not offered either. */
    @Test
    fun `an entry behind a linked folder is not found, and a loop ends`() {
        val root = temporaryFolder.newFolder()
        val elsewhere = temporaryFolder.newFolder().apply { File(this, "index.html").writeText("<html></html>") }
        java.nio.file.Files.createSymbolicLink(File(root, "site").toPath(), elsewhere.toPath())
        java.nio.file.Files.createSymbolicLink(File(root, "loop").toPath(), root.toPath())

        assertNull(findPreviewEntry(root))
    }

    /** A linked entry would be offered and then not served: the next regular one is offered instead. */
    @Test
    fun `a linked index html is passed over for a regular one`() {
        val root = temporaryFolder.newFolder()
        val elsewhere = temporaryFolder.newFile("page.html").apply { writeText("<html></html>") }
        java.nio.file.Files.createSymbolicLink(File(root, "index.html").toPath(), elsewhere.toPath())
        File(root, "public").mkdirs()
        File(root, "public/index.html").writeText("")

        assertEquals("public/index.html", findPreviewEntry(root))
    }
}
