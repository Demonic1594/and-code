package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val bin = temp.newFolder("bin")
        val script = File(bin, "tool").apply { writeText("old\n") }

        replaceGuestScript(script, "#!/bin/sh\necho new\n".byteInputStream())

        assertEquals("#!/bin/sh\necho new\n", script.readText())
        assertTrue(script.canExecute())
        assertEquals("no temporary file is left beside it", listOf("tool"), bin.list()!!.toList())
    }

    /** The guest can turn a script into a link to one of the app's own files; that file must stay. */
    @Test
    fun `a script the guest turned into a link is replaced, not written through`() {
        val bin = temp.newFolder("bin")
        val appFile = temp.newFile("shared_prefs.xml").apply { writeText("<map/>") }
        val script = File(bin, "tool")
        Files.createSymbolicLink(script.toPath(), appFile.toPath())

        replaceGuestScript(script, "new\n".byteInputStream())

        assertEquals("<map/>", appFile.readText())
        assertFalse(Files.isSymbolicLink(script.toPath()))
        assertEquals("new\n", script.readText())
    }

    /** A running shell reads its script as it goes; it keeps the file it opened, not a half-written one. */
    @Test
    fun `a reader of the old script keeps reading the old one`() {
        val bin = temp.newFolder("bin")
        val script = File(bin, "tool").apply { writeText("old content\n") }
        script.inputStream().use { reader ->
            replaceGuestScript(script, "new\n".byteInputStream())

            assertEquals("old content\n", reader.readBytes().decodeToString())
        }
        assertEquals("new\n", script.readText())
    }
}
