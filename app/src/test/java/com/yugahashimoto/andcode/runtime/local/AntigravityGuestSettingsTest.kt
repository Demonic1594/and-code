package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class AntigravityGuestSettingsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `settings the guest linked to an app file are replaced, not written through`() {
        val rootfs = tmp.newFolder("antigravity-rootfs")
        val appFile = File(tmp.newFolder("shared_prefs"), "settings.xml").apply { writeText("<map/>") }
        val settings = File(rootfs, "root/.gemini/antigravity-cli/settings.json")
        settings.parentFile.mkdirs()
        Files.createSymbolicLink(settings.toPath(), appFile.toPath())

        AntigravityGuestSettings.write(rootfs)

        assertEquals("<map/>", appFile.readText())
        assertFalse(Files.isSymbolicLink(settings.toPath()))
        assertEquals(AntigravityGuestSettings.content, settings.readText())
    }
}
