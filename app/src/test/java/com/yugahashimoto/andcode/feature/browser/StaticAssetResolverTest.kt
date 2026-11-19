package com.yugahashimoto.andcode.feature.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StaticAssetResolverTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var root: File
    private lateinit var resolver: StaticAssetResolver

    @Before
    fun setUp() {
        root = temporaryFolder.newFolder()
        File(root, "index.html").writeText("<html></html>")
        File(root, "assets").mkdirs()
        File(root, "assets/app.js").writeText("console.log(1)")
        File(root, "logo.svg").writeText("<svg/>")
        resolver = StaticAssetResolver(root)
    }

    @Test
    fun `serves the root document and typed assets`() {
        assertEquals("index.html", resolver.resolve("")?.relativePath)
        assertEquals("text/html", resolver.resolve("/")?.mimeType)
        assertEquals("application/javascript", resolver.resolve("/assets/app.js")?.mimeType)
        assertEquals("image/svg+xml", resolver.resolve("/logo.svg")?.mimeType)
    }

    @Test
    fun `falls back to index for client-side routes`() {
        assertEquals("index.html", resolver.resolve("/some/spa/route")?.relativePath)
    }

    @Test
    fun `refuses to escape the site root`() {
        File(root.parentFile, "secret.txt").writeText("secret")

        assertNull(resolver.resolve("/../secret.txt"))
    }

    @Test
    fun `missing asset with an extension is not served`() {
        assertNull(resolver.resolve("/missing.png"))
    }

    @Test
    fun `resolves root-relative assets and spa fallback for nested entry`() {
        val nestedRoot = temporaryFolder.newFolder()
        File(nestedRoot, "dist/assets").mkdirs()
        File(nestedRoot, "dist/index.html").writeText("<html></html>")
        File(nestedRoot, "dist/assets/app.js").writeText("console.log(2)")
        File(nestedRoot, "shared.css").writeText("body {}")
        val nestedResolver = StaticAssetResolver(nestedRoot, entry = "dist/index.html")

        assertEquals("dist/index.html", nestedResolver.resolve("/dist/index.html")?.relativePath)
        assertEquals("dist/assets/app.js", nestedResolver.resolve("/assets/app.js")?.relativePath)
        assertEquals("shared.css", nestedResolver.resolve("/shared.css")?.relativePath)
        assertEquals("dist/index.html", nestedResolver.resolve("/about")?.relativePath)
        assertNull(nestedResolver.resolve("/../secret.txt"))
    }

    /** The workspace is the guest's to write: a link in it is never followed to one of the app's files. */
    @Test
    fun `a link in the site is not served, wherever it points`() {
        val secret = File(temporaryFolder.newFolder(), "settings.xml").apply { writeText("secret") }
        java.nio.file.Files.createSymbolicLink(File(root, "leak.txt").toPath(), secret.toPath())
        java.nio.file.Files.createSymbolicLink(File(root, "linked").toPath(), secret.parentFile.toPath())

        assertNull(resolver.resolve("/leak.txt"))
        assertNull(resolver.resolve("/linked/settings.xml"))
    }
}
