package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.runtime.FileEditException
import com.yugahashimoto.andcode.runtime.FileEditFailure
import com.yugahashimoto.andcode.runtime.FileEditPolicy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class WorkspaceFileWriterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var root: File

    @Before
    fun setUp() {
        root = temporaryFolder.newFolder("project")
        file("src/App.kt", "fun main() = Unit\n")
        file(".env", "API_URL=http://localhost:3000\n")
    }

    @Test
    fun `replaces the contents of an existing file`() {
        WorkspaceFileWriter.write(root, "src/App.kt", "fun main() = println()\n", expectedContent = null)

        assertEquals("fun main() = println()\n", File(root, "src/App.kt").readText())
    }

    @Test
    fun `edits dotfiles such as env`() {
        WorkspaceFileWriter.write(root, ".env", "API_URL=https://example.test\n", expectedContent = "API_URL=http://localhost:3000\n")

        assertEquals("API_URL=https://example.test\n", File(root, ".env").readText())
    }

    @Test
    fun `leaves no temporary files behind`() {
        WorkspaceFileWriter.write(root, "src/App.kt", "changed", expectedContent = null)

        assertEquals(listOf("App.kt"), File(root, "src").list().orEmpty().toList())
    }

    @Test
    fun `keeps the executable bit of the file it replaces`() {
        val script = file("run.sh", "#!/bin/sh\necho old\n")
        script.setExecutable(true)
        assumeTrue(script.canExecute())

        WorkspaceFileWriter.write(root, "run.sh", "#!/bin/sh\necho new\n", expectedContent = null)

        assertTrue(script.canExecute())
        assertEquals("#!/bin/sh\necho new\n", script.readText())
    }

    @Test
    fun `round trips a BOM, CRLF line endings and non-ASCII text byte for byte`() {
        val original = "title=café\r\nlabel=日本語\r\n"
        val target = file("notes.txt", original)

        WorkspaceFileWriter.write(root, "notes.txt", original + "extra=ü\r\n", expectedContent = original)

        assertArrayEquals((original + "extra=ü\r\n").toByteArray(Charsets.UTF_8), target.readBytes())
    }

    @Test
    fun `refuses a path that climbs out of the workspace`() {
        File(temporaryFolder.root, "outside.txt").writeText("not in the project")

        assertEquals(FileEditFailure.OUTSIDE_WORKSPACE, failureOf { WorkspaceFileWriter.write(root, "../outside.txt", "x", null) })
        assertEquals(FileEditFailure.OUTSIDE_WORKSPACE, failureOf { WorkspaceFileWriter.write(root, "src/../../outside.txt", "x", null) })
        assertEquals("not in the project", File(temporaryFolder.root, "outside.txt").readText())
    }

    @Test
    fun `refuses an absolute path that points elsewhere`() {
        val outside = File(temporaryFolder.root, "outside.txt").apply { writeText("not in the project") }

        // File(root, "/abs") keeps the parent on the JVM, so this lands inside root and finds nothing.
        failureOf { WorkspaceFileWriter.write(root, outside.absolutePath, "x", null) }

        assertEquals("not in the project", outside.readText())
    }

    @Test
    fun `refuses a symlink that points outside the workspace`() {
        val outside = File(temporaryFolder.root, "outside.txt").apply { writeText("not in the project") }
        symlink(File(root, "escape.txt"), outside)

        assertEquals(FileEditFailure.OUTSIDE_WORKSPACE, failureOf { WorkspaceFileWriter.write(root, "escape.txt", "x", null) })
        assertEquals("not in the project", outside.readText())
    }

    @Test
    fun `refuses a file reached through a symlinked directory that leaves the workspace`() {
        val outsideDir = temporaryFolder.newFolder("elsewhere")
        File(outsideDir, "secret.txt").writeText("secret")
        symlink(File(root, "linked"), outsideDir)

        assertEquals(FileEditFailure.OUTSIDE_WORKSPACE, failureOf { WorkspaceFileWriter.write(root, "linked/secret.txt", "x", null) })
        assertEquals("secret", File(outsideDir, "secret.txt").readText())
    }

    @Test
    fun `writes through a symlink that stays inside the workspace and keeps the link`() {
        val link = File(root, "app-link.kt")
        symlink(link, File(root, "src/App.kt"))

        WorkspaceFileWriter.write(root, "app-link.kt", "via link", expectedContent = null)

        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertEquals("via link", File(root, "src/App.kt").readText())
    }

    @Test
    fun `refuses a missing file and a directory`() {
        assertEquals(FileEditFailure.NOT_FOUND, failureOf { WorkspaceFileWriter.write(root, "src/Missing.kt", "x", null) })
        assertEquals(FileEditFailure.NOT_FOUND, failureOf { WorkspaceFileWriter.write(root, "src", "x", null) })
        assertEquals(listOf("App.kt"), File(root, "src").list().orEmpty().toList())
    }

    @Test
    fun `refuses binary files and leaves them untouched`() {
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0, 0x47, 1, 2)
        val image = File(root, "logo.png").apply { writeBytes(bytes) }

        assertEquals(FileEditFailure.NOT_TEXT, failureOf { WorkspaceFileWriter.write(root, "logo.png", "x", null) })
        assertArrayEquals(bytes, image.readBytes())
    }

    @Test
    fun `refuses files that are not valid UTF-8 rather than corrupting them`() {
        val latin1 = byteArrayOf(0x63, 0x61, 0x66, 0xE9.toByte())
        val legacy = File(root, "legacy.txt").apply { writeBytes(latin1) }

        assertEquals(FileEditFailure.NOT_TEXT, failureOf { WorkspaceFileWriter.write(root, "legacy.txt", "café", null) })
        assertArrayEquals(latin1, legacy.readBytes())
    }

    @Test
    fun `refuses files over the size cap`() {
        val large = "a".repeat(FileEditPolicy.MAX_EDIT_BYTES.toInt() + 1)
        val target = file("large.txt", large)

        assertEquals(FileEditFailure.TOO_LARGE, failureOf { WorkspaceFileWriter.write(root, "large.txt", "small", null) })
        assertEquals(large, target.readText())
    }

    @Test
    fun `accepts a file exactly at the size cap`() {
        // Many ordinary lines, not one huge one: the per-line cap would refuse that itself.
        val atCap = "a\n".repeat(FileEditPolicy.MAX_EDIT_BYTES.toInt() / 2)
        val replacement = "b\n".repeat(FileEditPolicy.MAX_EDIT_BYTES.toInt() / 2)
        file("cap.txt", atCap)

        WorkspaceFileWriter.write(root, "cap.txt", replacement, expectedContent = atCap)

        assertEquals(replacement, File(root, "cap.txt").readText())
    }

    @Test
    fun `refuses a file whose single line is past the per-line cap`() {
        val minified = "a".repeat(FileEditPolicy.MAX_LINE_BYTES.toInt() + 1)
        val target = file("minified.js", minified)

        assertEquals(FileEditFailure.LINE_TOO_LONG, failureOf { WorkspaceFileWriter.write(root, "minified.js", "small", null) })
        assertEquals(minified, target.readText())
    }

    @Test
    fun `refuses new content over the size cap`() {
        val tooBig = "é".repeat(FileEditPolicy.MAX_EDIT_BYTES.toInt() / 2 + 1)

        assertEquals(FileEditFailure.TOO_LARGE, failureOf { WorkspaceFileWriter.write(root, "src/App.kt", tooBig, null) })
        assertEquals("fun main() = Unit\n", File(root, "src/App.kt").readText())
    }

    @Test
    fun `refuses new content with a NUL character`() {
        assertEquals(FileEditFailure.NOT_TEXT, failureOf { WorkspaceFileWriter.write(root, "src/App.kt", "a\u0000b", null) })
        assertEquals("fun main() = Unit\n", File(root, "src/App.kt").readText())
    }

    @Test
    fun `refuses to overwrite a file that changed since it was loaded`() {
        val loaded = "fun main() = Unit\n"
        File(root, "src/App.kt").writeText("fun main() = agentEdit()\n")

        assertEquals(FileEditFailure.CHANGED_ON_DISK, failureOf { WorkspaceFileWriter.write(root, "src/App.kt", "mine", loaded) })
        assertEquals("fun main() = agentEdit()\n", File(root, "src/App.kt").readText())
    }

    @Test
    fun `saves when the file still holds what the editor loaded`() {
        WorkspaceFileWriter.write(root, "src/App.kt", "mine", expectedContent = "fun main() = Unit\n")

        assertEquals("mine", File(root, "src/App.kt").readText())
    }

    @Test
    fun `keeps the original when the replacement cannot be created`() {
        val locked = File(root, "locked").apply { mkdirs() }
        File(locked, "a.txt").writeText("old")
        locked.setWritable(false)
        try {
            // Root ignores directory permissions, so the failure cannot be provoked that way.
            assumeFalse(locked.canWrite())

            assertEquals(FileEditFailure.WRITE_FAILED, failureOf { WorkspaceFileWriter.write(root, "locked/a.txt", "new", null) })

            assertEquals("old", File(locked, "a.txt").readText())
            assertEquals(listOf("a.txt"), locked.list().orEmpty().toList())
        } finally {
            locked.setWritable(true)
        }
    }

    @Test
    fun `refuses a read-only file`() {
        val readOnly = file("pinned.txt", "pinned")
        readOnly.setWritable(false)
        try {
            assumeFalse(readOnly.canWrite())

            assertEquals(FileEditFailure.READ_ONLY, failureOf { WorkspaceFileWriter.write(root, "pinned.txt", "new", null) })
            assertEquals(FileEditFailure.READ_ONLY, failureOf { WorkspaceFileWriter.readForEditing(root, "pinned.txt") })

            assertEquals("pinned", readOnly.readText())
        } finally {
            readOnly.setWritable(true)
        }
    }

    @Test
    fun `resolveInside accepts files below the root and rejects the rest`() {
        assertEquals(File(root.canonicalFile, "src/App.kt"), WorkspaceFileWriter.resolveInside(root, "src/App.kt"))
        assertEquals(File(root.canonicalFile, "src/App.kt"), WorkspaceFileWriter.resolveInside(root, "./src//App.kt"))
        assertNull(WorkspaceFileWriter.resolveInside(root, ".."))
        assertNull(WorkspaceFileWriter.resolveInside(root, "src/../.."))
        assertNull(WorkspaceFileWriter.resolveInside(root, ""))
        assertNull(WorkspaceFileWriter.resolveInside(root, "."))
    }

    @Test
    fun `resolveInside does not treat a sibling with the same prefix as inside`() {
        val sibling = temporaryFolder.newFolder("project-evil")
        File(sibling, "x.txt").writeText("x")

        assertNull(WorkspaceFileWriter.resolveInside(root, "../project-evil/x.txt"))
    }

    @Test
    fun `reads the exact text of a file for editing, trailing newline and CRLF included`() {
        file("exact.env", "A=1\r\nB=2\r\n\n")

        assertEquals("A=1\r\nB=2\r\n\n", WorkspaceFileWriter.readForEditing(root, "exact.env"))
        assertEquals("API_URL=http://localhost:3000\n", WorkspaceFileWriter.readForEditing(root, ".env"))
    }

    @Test
    fun `reading for editing applies the same refusals as saving`() {
        File(root, "logo.png").writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0, 0x47))
        File(root, "legacy.txt").writeBytes(byteArrayOf(0x63, 0x61, 0x66, 0xE9.toByte()))
        file("large.txt", "a".repeat(FileEditPolicy.MAX_EDIT_BYTES.toInt() + 1))

        assertEquals(FileEditFailure.NOT_TEXT, failureOf { WorkspaceFileWriter.readForEditing(root, "logo.png") })
        assertEquals(FileEditFailure.NOT_TEXT, failureOf { WorkspaceFileWriter.readForEditing(root, "legacy.txt") })
        assertEquals(FileEditFailure.TOO_LARGE, failureOf { WorkspaceFileWriter.readForEditing(root, "large.txt") })
        assertEquals(FileEditFailure.NOT_FOUND, failureOf { WorkspaceFileWriter.readForEditing(root, "src") })
        assertEquals(FileEditFailure.OUTSIDE_WORKSPACE, failureOf { WorkspaceFileWriter.readForEditing(root, "../project-x") })
    }

    @Test
    fun `text read for editing can be saved straight back without a conflict`() {
        val text = WorkspaceFileWriter.readForEditing(root, ".env")

        WorkspaceFileWriter.write(root, ".env", text + "EXTRA=1\n", expectedContent = text)

        assertEquals("API_URL=http://localhost:3000\nEXTRA=1\n", File(root, ".env").readText())
    }

    @Test
    fun `policy accepts plain text and rejects what it would damage`() {
        assertNull(FileEditPolicy.refusalFor("API_KEY=abc\n".toByteArray()))
        assertEquals(FileEditFailure.NOT_TEXT, FileEditPolicy.refusalFor(byteArrayOf(0x61, 0, 0x62)))
        assertEquals(FileEditFailure.NOT_TEXT, FileEditPolicy.refusalFor(byteArrayOf(0xC3.toByte(), 0x28)))
        assertEquals(FileEditFailure.TOO_LARGE, FileEditPolicy.refusalFor(ByteArray(FileEditPolicy.MAX_EDIT_BYTES.toInt() + 1) { 0x61 }))
    }

    private fun file(
        path: String,
        content: String,
    ): File =
        File(root, path).apply {
            parentFile?.mkdirs()
            writeText(content)
        }

    private fun symlink(
        link: File,
        target: File,
    ) {
        val created = runCatching { Files.createSymbolicLink(link.toPath(), target.toPath()) }.isSuccess
        assumeTrue("symbolic links are not available here", created)
    }

    private fun failureOf(block: () -> Unit): FileEditFailure = assertThrows(FileEditException::class.java) { block() }.failure
}
