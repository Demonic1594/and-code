package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * The guest can make any name in its trees a link to one of this app's own files. Each test plants
 * such a link where a call site would meet it and checks the app's file - "outside" here - is never
 * written, read or deleted through it.
 */
class GuestFilesTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val rootfs by lazy { temp.newFolder("environment", "rootfs") }
    private val outside by lazy { temp.newFolder("shared_prefs") }
    private val privateFile by lazy { File(outside, "secure.xml").apply { writeText("<map>secret</map>") } }

    private fun link(
        at: File,
        to: File,
    ) {
        at.parentFile.mkdirs()
        Files.createSymbolicLink(at.toPath(), to.toPath())
    }

    private fun isLink(file: File) = Files.isSymbolicLink(file.toPath())

    @Test
    fun `a write creates the missing folders and leaves no temporary file`() {
        GuestFiles.write(rootfs, "root/.config/opencode/opencode.json", "{}\n")

        assertEquals("{}\n", File(rootfs, "root/.config/opencode/opencode.json").readText())
        assertEquals(listOf("opencode.json"), File(rootfs, "root/.config/opencode").list()!!.toList())
    }

    /** The temporary name must fit the 255-byte limit however long, or multi-byte, the target's name is. */
    @Test
    fun `a write to a name near the length limit succeeds`() {
        val name = "\u3042".repeat(80) + ".json"

        GuestFiles.write(rootfs, "root/$name", "{}\n")

        assertEquals("{}\n", File(rootfs, "root/$name").readText())
    }

    @Test
    fun `a write replaces a link at the target instead of writing through it`() {
        val target = File(rootfs, "root/.git-credentials")
        link(target, privateFile)

        GuestFiles.write(rootfs, "root/.git-credentials", "token\n")

        assertEquals("<map>secret</map>", privateFile.readText())
        assertFalse(isLink(target))
        assertEquals("token\n", target.readText())
    }

    @Test
    fun `a write refuses a linked parent folder and creates nothing through it`() {
        link(File(rootfs, "root/.config"), outside)

        assertThrows(GuestPathRefusedException::class.java) {
            GuestFiles.write(rootfs, "root/.config/opencode/opencode.json", "{}")
        }

        assertEquals(listOf("secure.xml"), outside.also { privateFile }.list()!!.toList())
    }

    /** Even a link that stays inside the rootfs is refused: the host cannot resolve guest paths faithfully. */
    @Test
    fun `a write refuses a linked parent folder that points inside the rootfs`() {
        val elsewhere = File(rootfs, "home/user").apply { mkdirs() }
        link(File(rootfs, "root"), elsewhere)

        assertThrows(GuestPathRefusedException::class.java) { GuestFiles.write(rootfs, "root/.claude.json", "{}") }

        assertEquals(0, elsewhere.list()!!.size)
    }

    @Test
    fun `a root that is itself a link is refused`() {
        val realWorkspace = temp.newFolder("real-workspace")
        val workspace = File(temp.root, "workspace")
        link(workspace, outside)

        assertThrows(GuestPathRefusedException::class.java) { GuestFiles.write(workspace, ".and-code/x.json", "{}") }
        assertThrows(GuestPathRefusedException::class.java) { GuestFiles.readText(workspace, "secure.xml") }

        assertTrue(realWorkspace.list()!!.isEmpty())
        assertEquals("<map>secret</map>", privateFile.readText())
    }

    @Test
    fun `a parent that is a regular file is refused`() {
        File(rootfs, "root").mkdirs()
        File(rootfs, "root/.claude").writeText("not a folder")

        assertThrows(GuestPathRefusedException::class.java) { GuestFiles.write(rootfs, "root/.claude/settings.json", "{}") }
        assertThrows(GuestPathRefusedException::class.java) { GuestFiles.readText(rootfs, "root/.claude/settings.json") }
    }

    @Test
    fun `a write keeps the replaced file's permissions unless it is given some`() {
        val target = File(rootfs, "usr/local/bin/tool")
        target.parentFile.mkdirs()
        target.writeText("old")
        Files.setPosixFilePermissions(target.toPath(), PosixFilePermissions.fromString("rwxr-x---"))

        GuestFiles.write(rootfs, "usr/local/bin/tool", "new")
        assertEquals("rwxr-x---", PosixFilePermissions.toString(Files.getPosixFilePermissions(target.toPath())))

        GuestFiles.write(rootfs, "usr/local/bin/tool", "newer", GuestFiles.OWNER_READ_WRITE)
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(target.toPath())))
        assertEquals("newer", target.readText())
    }

    @Test
    fun `a write streams large content`() {
        val bytes = ByteArray(3 * 1024 * 1024) { (it % 251).toByte() }

        GuestFiles.write(rootfs, "usr/local/bin/codex", GuestFiles.EXECUTABLE) { output -> output.write(bytes) }

        val written = File(rootfs, "usr/local/bin/codex")
        assertArrayEquals(bytes, written.readBytes())
        assertTrue(written.canExecute())
    }

    @Test
    fun `a failed write leaves the old file and no temporary file`() {
        val target =
            File(rootfs, "root/.claude.json").apply {
                parentFile.mkdirs()
                writeText("old")
            }

        assertThrows(IllegalStateException::class.java) {
            GuestFiles.write(rootfs, "root/.claude.json") { output ->
                output.write("partial".toByteArray())
                error("boom")
            }
        }

        assertEquals("old", target.readText())
        assertEquals(listOf(".claude.json"), target.parentFile.list()!!.toList())
    }

    @Test
    fun `a missing file or folder reads as null`() {
        assertNull(GuestFiles.readText(rootfs, "root/.claude.json"))
        assertNull(GuestFiles.readText(rootfs, "root/.config/opencode/opencode.json"))
        assertFalse(GuestFiles.isRegularFile(rootfs, "root/.claude.json"))
    }

    @Test
    fun `a read refuses a link at the target and never reads the app's file`() {
        link(File(rootfs, "root/.config/opencode/opencode.json"), privateFile)

        assertThrows(GuestPathRefusedException::class.java) {
            GuestFiles.readText(rootfs, "root/.config/opencode/opencode.json")
        }
        assertFalse(GuestFiles.isRegularFile(rootfs, "root/.config/opencode/opencode.json"))
    }

    @Test
    fun `a read refuses a linked parent folder`() {
        link(File(rootfs, "root/.local/share/opencode"), outside)
        File(outside, "auth.json").writeText("{\"private\":true}")

        assertThrows(GuestPathRefusedException::class.java) {
            GuestFiles.readText(rootfs, "root/.local/share/opencode/auth.json")
        }
        assertFalse(GuestFiles.isRegularFile(rootfs, "root/.local/share/opencode/auth.json"))
    }

    @Test
    fun `a read refuses a file larger than the cap`() {
        File(rootfs, "pending").mkdirs()
        File(rootfs, "pending/big.json").writeBytes(ByteArray(2048))

        assertThrows(GuestPathRefusedException::class.java) { GuestFiles.readBytes(rootfs, "pending/big.json", maxBytes = 1024) }
        assertEquals(2048, GuestFiles.readBytes(rootfs, "pending/big.json", maxBytes = 2048)!!.size)
    }

    @Test
    fun `a read refuses a folder or a fifo in place of the file`() {
        File(rootfs, "pending/folder.json").mkdirs()
        assertThrows(GuestPathRefusedException::class.java) { GuestFiles.readText(rootfs, "pending/folder.json") }

        val fifo = File(rootfs, "pending/fifo.json")
        val made = runCatching { ProcessBuilder("mkfifo", fifo.path).start().waitFor() == 0 }.getOrDefault(false)
        assumeTrue("mkfifo is unavailable", made)
        // Opening a fifo for reading would block until a writer comes; it must be refused unopened.
        assertThrows(GuestPathRefusedException::class.java) { GuestFiles.readText(rootfs, "pending/fifo.json") }
    }

    @Test
    fun `paths that could leave the root are rejected outright`() {
        listOf("", "/etc/passwd", "../shared_prefs/secure.xml", "root/../../x", "root/./x", "root//x").forEach { path ->
            assertThrows(path, IllegalArgumentException::class.java) { GuestFiles.write(rootfs, path, "x") }
        }
    }

    @Test
    fun `delete removes a link itself, never what it points at`() {
        val target = File(rootfs, "usr/local/bin/android-ui")
        link(target, privateFile)

        assertTrue(GuestFiles.delete(rootfs, "usr/local/bin/android-ui"))

        assertFalse(target.toPath().let { Files.exists(it, LinkOption.NOFOLLOW_LINKS) })
        assertEquals("<map>secret</map>", privateFile.readText())
        assertFalse(GuestFiles.delete(rootfs, "usr/local/bin/android-ui"))
    }

    @Test
    fun `delete does nothing through a linked parent folder`() {
        privateFile
        link(File(rootfs, "root"), outside)

        assertFalse(GuestFiles.delete(rootfs, "root/secure.xml"))

        assertEquals("<map>secret</map>", privateFile.readText())
    }

    @Test
    fun `list names the regular files of a folder and refuses a linked one`() {
        File(rootfs, "pending").mkdirs()
        File(rootfs, "pending/a.json").writeText("{}")
        File(rootfs, "pending/sub").mkdirs()
        link(File(rootfs, "pending/b.json"), privateFile)

        assertEquals(listOf("a.json"), GuestFiles.listFiles(rootfs, "pending").map { it.name })
        assertEquals(emptyList<GuestFiles.Entry>(), GuestFiles.listFiles(rootfs, "missing"))

        link(File(rootfs, "responses"), outside)
        assertThrows(GuestPathRefusedException::class.java) { GuestFiles.listFiles(rootfs, "responses") }
    }

    @Test
    fun `deleteTree removes everything below without following links`() {
        privateFile
        val tree = File(rootfs, "root")
        File(tree, "project/src").mkdirs()
        File(tree, "project/src/main.kt").writeText("fun main() {}")
        link(File(tree, "project/prefs"), outside)
        link(File(tree, "project/secure.xml"), privateFile)

        assertTrue(GuestFiles.deleteTree(rootfs))

        assertFalse(rootfs.exists())
        assertEquals("<map>secret</map>", privateFile.readText())
    }

    @Test
    fun `deleteTree of a link removes only the link`() {
        privateFile
        val workspaceFolder = File(temp.root, "workspace/project")
        link(workspaceFolder, outside)

        assertTrue(GuestFiles.deleteTree(workspaceFolder))

        assertFalse(Files.exists(workspaceFolder.toPath(), LinkOption.NOFOLLOW_LINKS))
        assertEquals("<map>secret</map>", privateFile.readText())
    }

    @Test
    fun `deleteTree of nothing succeeds`() {
        assertTrue(GuestFiles.deleteTree(File(temp.root, "never-created")))
    }

    @Test
    fun `copyTree copies files and folders and keeps links as links`() {
        privateFile
        val home = File(rootfs, "root")
        File(home, ".config/opencode").mkdirs()
        File(home, ".config/opencode/opencode.json").writeText("{\"mine\":1}")
        Files.setPosixFilePermissions(File(home, ".config/opencode/opencode.json").toPath(), PosixFilePermissions.fromString("rwx------"))
        link(File(home, "prefs"), outside)
        link(File(home, "secure.xml"), privateFile)
        val staging = temp.newFolder("staging", "rootfs", "root")
        File(staging, "secure.xml").writeText("stale")

        GuestFiles.copyTree(home, staging)

        val copied = File(staging, ".config/opencode/opencode.json")
        assertEquals("{\"mine\":1}", copied.readText())
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(copied.toPath())))
        assertTrue(isLink(File(staging, "prefs")))
        assertEquals(outside.toPath(), Files.readSymbolicLink(File(staging, "prefs").toPath()))
        assertTrue(isLink(File(staging, "secure.xml")))
        assertEquals("the app's file is never copied in as content", "<map>secret</map>", privateFile.readText())
        assertEquals(listOf("secure.xml"), outside.list()!!.toList())
    }

    @Test
    fun `deleteTree relative to a root never reaches through a linked folder on the way`() {
        privateFile
        File(rootfs, "root/.claude").mkdirs()
        link(File(rootfs, "root/.claude/todos"), outside)

        assertFalse(GuestFiles.deleteTree(rootfs, "root/.claude/todos/secure.xml"))
        assertEquals("<map>secret</map>", privateFile.readText())

        File(rootfs, "root/.claude/file-history/abc/deep").mkdirs()
        link(File(rootfs, "root/.claude/file-history/abc/deep/prefs"), outside)
        assertTrue(GuestFiles.deleteTree(rootfs, "root/.claude/file-history/abc"))
        assertFalse(File(rootfs, "root/.claude/file-history/abc").exists())
        assertTrue(GuestFiles.deleteTree(rootfs, "root/.claude/missing/abc"))
        assertEquals("<map>secret</map>", privateFile.readText())
        assertEquals(listOf("todos", "file-history"), GuestFiles.listNames(rootfs, "root/.claude").sorted().reversed())
        assertEquals(listOf("file-history"), GuestFiles.listFolders(rootfs, "root/.claude"))
    }
}
