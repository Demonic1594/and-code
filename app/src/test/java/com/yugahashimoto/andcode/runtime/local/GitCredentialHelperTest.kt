package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory

class GitCredentialHelperTest {
    @Test
    fun `install writes executable helper and remove deletes it`() {
        val root = createTempDirectory("git-helper").toFile()
        try {
            val helper = GitCredentialHelper(root) { "test-token" }
            val file = helper.install()

            assertTrue(file.isFile)
            assertTrue(file.path.endsWith("usr/local/bin/git-credential-opencode"))
            assertTrue(file.readText().contains("x-access-token"))
            assertFalse(file.readText().contains("secret"))
            val gitConfig = root.resolve("root/.gitconfig")
            assertTrue(gitConfig.isFile)
            assertTrue(gitConfig.readText().contains("helper = opencode"))
            assertFalse(gitConfig.readText().contains("\\t"))
            val gitCredentials = root.resolve("root/.git-credentials")
            assertTrue(gitCredentials.isFile)
            assertTrue(gitCredentials.readText().contains("x-access-token:test-token@github.com"))

            helper.remove()
            assertFalse(file.exists())
            assertFalse(gitConfig.exists())
            assertFalse(gitCredentials.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `install repairs malformed gitconfig`() {
        val root = createTempDirectory("git-helper").toFile()
        try {
            val gitConfig = root.resolve("root/.gitconfig")
            gitConfig.parentFile?.mkdirs()
            gitConfig.writeText("[credential \"https://github.com\"]\n\\thelper = opencode\n")

            GitCredentialHelper(root) { "test-token" }.install()

            assertFalse(gitConfig.readText().contains("\\t"))
            assertTrue(gitConfig.readText().contains("\thelper = opencode"))
        } finally {
            root.deleteRecursively()
        }
    }

    /** An agent can point any of these paths at one of the app's own files; that file must stay as it was. */
    @Test
    fun `a credential store the guest linked to an app file is replaced, not written through`() {
        val base = createTempDirectory("git-helper-link").toFile()
        try {
            val rootfs = File(base, "rootfs")
            val appFile =
                File(base, "shared_prefs/settings.xml").apply {
                    parentFile.mkdirs()
                    writeText("<map/>")
                }
            File(rootfs, "root").mkdirs()
            Files.createSymbolicLink(File(rootfs, "root/.git-credentials").toPath(), appFile.toPath())

            GitCredentialHelper(rootfs) { "test-token" }.install()

            assertEquals("<map/>", appFile.readText())
            val store = File(rootfs, "root/.git-credentials")
            assertFalse(Files.isSymbolicLink(store.toPath()))
            assertTrue(store.readText().contains("x-access-token:test-token@github.com"))
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `a linked gitconfig is neither read nor overwritten`() {
        val base = createTempDirectory("git-helper-config").toFile()
        try {
            val rootfs = File(base, "rootfs")
            val appFile =
                File(base, "files/private.conf").apply {
                    parentFile.mkdirs()
                    writeText("[private]\n\tsecret = 1\n")
                }
            File(rootfs, "root").mkdirs()
            val gitConfig = File(rootfs, "root/.gitconfig")
            Files.createSymbolicLink(gitConfig.toPath(), appFile.toPath())

            runCatching { GitCredentialHelper(rootfs) { "test-token" }.install() }

            assertEquals("[private]\n\tsecret = 1\n", appFile.readText())
            assertTrue("the user's link stays", Files.isSymbolicLink(gitConfig.toPath()))
            assertTrue("the store is still written", File(rootfs, "root/.git-credentials").isFile)
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `a linked home folder never receives the token`() {
        val base = createTempDirectory("git-helper-home").toFile()
        try {
            val rootfs = File(base, "rootfs").apply { mkdirs() }
            val appFolder = File(base, "shared_prefs").apply { mkdirs() }
            Files.createSymbolicLink(File(rootfs, "root").toPath(), appFolder.toPath())

            runCatching { GitCredentialHelper(rootfs) { "test-token" }.install() }

            assertEquals(emptyList<String>(), appFolder.list()!!.toList())
        } finally {
            base.deleteRecursively()
        }
    }
}
