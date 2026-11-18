package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class GuestScriptFileTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `a script is written whole and executable`() {
        val rootfs = temp.newFolder("rootfs")
        val bin = File(rootfs, "bin").apply { mkdirs() }
        val script = File(bin, "tool").apply { writeText("old\n") }

        replaceGuestScript(rootfs, "bin/tool", "#!/bin/sh\necho new\n".byteInputStream())

        assertEquals("#!/bin/sh\necho new\n", script.readText())
        assertTrue(script.canExecute())
        assertEquals("no temporary file is left beside it", listOf("tool"), bin.list()!!.toList())
    }

    /** The guest can turn a script into a link to one of the app's own files; that file must stay. */
    @Test
    fun `a script the guest turned into a link is replaced, not written through`() {
        val rootfs = temp.newFolder("rootfs")
        val bin = File(rootfs, "bin").apply { mkdirs() }
        val appFile = temp.newFile("shared_prefs.xml").apply { writeText("<map/>") }
        val script = File(bin, "tool")
        Files.createSymbolicLink(script.toPath(), appFile.toPath())

        replaceGuestScript(rootfs, "bin/tool", "new\n".byteInputStream())

        assertEquals("<map/>", appFile.readText())
        assertFalse(Files.isSymbolicLink(script.toPath()))
        assertEquals("new\n", script.readText())
    }

    /** A running shell reads its script as it goes; it keeps the file it opened, not a half-written one. */
    @Test
    fun `a reader of the old script keeps reading the old one`() {
        val rootfs = temp.newFolder("rootfs")
        val script =
            File(rootfs, "bin/tool").apply {
                parentFile.mkdirs()
                writeText("old content\n")
            }
        script.inputStream().use { reader ->
            replaceGuestScript(rootfs, "bin/tool", "new\n".byteInputStream())

            assertEquals("old content\n", reader.readBytes().decodeToString())
        }
        assertEquals("new\n", script.readText())
    }

    /** The guest can also make the folder a link, so the script would land among the app's own files. */
    @Test
    fun `a bin folder the guest turned into a link gets no script`() {
        val rootfs = temp.newFolder("rootfs")
        val appFolder = temp.newFolder("shared_prefs")
        Files.createSymbolicLink(File(rootfs, "bin").toPath(), appFolder.toPath())

        assertThrows(GuestPathRefusedException::class.java) {
            replaceGuestScript(rootfs, "bin/tool", "new\n".byteInputStream())
        }

        assertEquals(emptyList<String>(), appFolder.list()!!.toList())
    }
}
