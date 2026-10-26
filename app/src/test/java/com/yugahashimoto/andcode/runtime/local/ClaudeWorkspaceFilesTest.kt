package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.storage.DeviceStorage
import com.yugahashimoto.andcode.runtime.FileEditException
import com.yugahashimoto.andcode.runtime.FileEditFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ClaudeWorkspaceFilesTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var workspace: File
    private lateinit var files: ClaudeWorkspaceFiles

    @Before
    fun setUp() {
        workspace = temporaryFolder.newFolder("workspace")
        files = ClaudeWorkspaceFiles(workspace)
        write("project/README.md", "# Title\nhello world\n")
        write("project/src/App.kt", "fun main() = Unit\n")
        write("project/.hidden", "secret")
        write("outside.txt", "not in the project")
    }

    @Test
    fun `a picture is read as base64 so it can be shown`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0, 0, 0, 0x0D)
        File(workspace, "project/logo.png").writeBytes(png)

        val file = files.read("/workspace/project", "logo.png")

        assertEquals("base64", file.encoding)
        assertEquals("image/png", file.mimeType)
        assertEquals(png.toList(), java.util.Base64.getDecoder().decode(file.content).toList())
    }

    @Test
    fun `lists directories before files and reports sandbox paths`() {
        val nodes = files.list("/workspace/project", "")

        assertEquals(listOf("src", ".hidden", "README.md"), nodes.map { it.name })
        assertEquals("directory", nodes[0].type)
        assertEquals("/workspace/project/README.md", nodes.last().absolute)
        assertTrue(nodes[1].ignored)
    }

    @Test
    fun `reads a text file`() {
        val content = files.read("/workspace/project", "README.md")

        assertEquals("text", content.type)
        assertTrue(content.content.orEmpty().contains("hello world"))
    }

    @Test
    fun `reports a file with NUL bytes as binary without returning its contents`() {
        File(workspace, "project/image.bin").writeBytes(byteArrayOf(0x89.toByte(), 0, 1, 2))

        val content = files.read("/workspace/project", "image.bin")

        assertEquals("binary", content.type)
        assertEquals("", content.content)
    }

    @Test
    fun `refuses to read outside the workspace`() {
        assertThrows(IllegalArgumentException::class.java) {
            files.read("/workspace/project", "../outside.txt")
        }
    }

    @Test
    fun `lists nothing for a path that escapes the workspace`() {
        assertTrue(files.list("/workspace/project", "../..").isEmpty())
    }

    @Test
    fun `saves an edited file under its sandbox path`() {
        files.write("/workspace/project", "src/App.kt", "fun main() = println()\n", expectedContent = "fun main() = Unit\n")

        assertEquals("fun main() = println()\n", File(workspace, "project/src/App.kt").readText())
    }

    @Test
    fun `saves a dotfile that the explorer lists`() {
        assertTrue(files.list("/workspace/project", ".").any { it.name == ".hidden" })

        files.write("/workspace/project", ".hidden", "SECRET=1\n", expectedContent = "secret")

        assertEquals("SECRET=1\n", File(workspace, "project/.hidden").readText())
        assertEquals("SECRET=1\n", files.read("/workspace/project", ".hidden").content)
    }

    @Test
    fun `refuses to save outside the editing root`() {
        val failure =
            assertThrows(FileEditException::class.java) {
                files.write("/workspace/project", "../outside.txt", "changed", expectedContent = null)
            }

        assertEquals(FileEditFailure.OUTSIDE_WORKSPACE, failure.failure)
        assertEquals("not in the project", File(workspace, "outside.txt").readText())
    }

    @Test
    fun `refuses to save into a folder that does not exist`() {
        val failure =
            assertThrows(FileEditException::class.java) {
                files.write("/root/missing", "notes.md", "changed", expectedContent = null)
            }

        assertEquals(FileEditFailure.NOT_FOUND, failure.failure)
    }

    @Test
    fun `finds files by name`() {
        assertEquals(listOf("src/App.kt"), files.find("/workspace/project", "app", includeDirectories = false, limit = null))
    }

    @Test
    fun `returns nothing for a blank query`() {
        assertTrue(files.find("/workspace/project", " ", includeDirectories = null, limit = null).isEmpty())
    }

    @Test
    fun `searches file contents and reports where the match is`() {
        val matches = files.search("/workspace/project", "hello")

        val match = matches.single()
        assertEquals("README.md", match.path.text)
        assertEquals(2, match.lineNumber)
        assertEquals(0, match.absoluteOffset)
    }

    @Test
    fun `counts the lines of a file so an untracked change has a size`() {
        assertEquals(2, files.countLines("/workspace/project", "README.md"))
    }

    @Test
    fun `counts nothing for a directory or a path outside the workspace`() {
        assertNull(files.countLines("/workspace/project", "src"))
        assertNull(files.countLines("/workspace/project", "../outside.txt"))
    }

    private fun write(
        path: String,
        content: String,
    ) {
        File(workspace, path).apply {
            parentFile?.mkdirs()
            writeText(content)
        }
    }
}

class ClaudeWorkspaceFilesRootfsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var workspace: File
    private lateinit var rootfs: File

    @Before
    fun setUp() {
        workspace = temporaryFolder.newFolder("workspace")
        rootfs = temporaryFolder.newFolder("rootfs")
        File(rootfs, "root/project").mkdirs()
        File(rootfs, "root/project/notes.md").writeText("todo")
    }

    @Test
    fun `browses a folder outside workspace via the rootfs`() {
        val files = ClaudeWorkspaceFiles(workspace, rootfsHostDir = rootfs)

        val nodes = files.list("/root/project", ".")

        assertEquals(listOf("notes.md"), nodes.map { it.name })
        assertEquals("/root/project/notes.md", nodes.single().absolute)
    }

    @Test
    fun `listing the guest root includes a synthetic workspace entry`() {
        val files = ClaudeWorkspaceFiles(workspace, rootfsHostDir = rootfs)

        val nodes = files.list("/", ".")

        assertTrue(nodes.any { it.name == "workspace" && it.type == "directory" && it.absolute == "/workspace" })
        assertTrue(nodes.any { it.name == "root" })
    }

    @Test
    fun `listing the guest root includes mounted device storage`() {
        val storage = temporaryFolder.newFolder("sdcard")
        val files =
            ClaudeWorkspaceFiles(
                workspace,
                rootfsHostDir = rootfs,
                deviceStorage = { DeviceStorage.Mounts(sharedStorage = storage) },
            )

        val nodes = files.list("/", ".")

        assertTrue(nodes.any { it.name == "sdcard" })
    }

    @Test
    fun `browses device storage once mounted`() {
        val storage = temporaryFolder.newFolder("sdcard")
        File(storage, "Download").mkdirs()
        val files =
            ClaudeWorkspaceFiles(
                workspace,
                rootfsHostDir = rootfs,
                deviceStorage = { DeviceStorage.Mounts(sharedStorage = storage) },
            )

        val nodes = files.list("/sdcard", ".")

        assertEquals(listOf("Download"), nodes.map { it.name })
    }

    @Test
    fun `finds nothing outside the mounted directories when the rootfs is unavailable`() {
        val files = ClaudeWorkspaceFiles(workspace, rootfsHostDir = null)

        assertTrue(files.list("/root/project", ".").isEmpty())
    }

    @Test
    fun `saves a file in a rootfs project and keeps the write inside it`() {
        val files = ClaudeWorkspaceFiles(workspace, rootfsHostDir = rootfs)
        File(rootfs, "root/other.txt").writeText("elsewhere in the rootfs")

        files.write("/root/project", "notes.md", "done", expectedContent = "todo")

        assertEquals("done", File(rootfs, "root/project/notes.md").readText())
        val failure =
            assertThrows(FileEditException::class.java) {
                files.write("/root/project", "../other.txt", "changed", expectedContent = null)
            }
        assertEquals(FileEditFailure.OUTSIDE_WORKSPACE, failure.failure)
        assertEquals("elsewhere in the rootfs", File(rootfs, "root/other.txt").readText())
    }
}
