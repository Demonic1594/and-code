package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory

class GitCredentialHelperTest {
    private fun exists(file: File) = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    @Test
    fun `install writes the helper and the config, and no token store`() {
        val root = createTempDirectory("git-helper").toFile()
        try {
            val helper = GitCredentialHelper(root)
            val file = helper.install()

            assertTrue(file.isFile)
            assertTrue(file.path.endsWith("usr/local/bin/git-credential-opencode"))
            assertTrue(file.readText().contains("x-access-token"))
            val gitConfig = root.resolve("root/.gitconfig")
            assertTrue(gitConfig.isFile)
            assertTrue(gitConfig.readText().contains("helper = opencode"))
            assertFalse(gitConfig.readText().contains("\\t"))
            assertFalse("no store file", exists(root.resolve("root/.git-credentials")))

            helper.remove()
            assertFalse(file.exists())
            assertFalse(gitConfig.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    /** Builds before this one wrote the token to `~/.git-credentials`; the next start removes it. */
    @Test
    fun `a store an older build left behind is deleted`() {
        val root = createTempDirectory("git-helper-legacy").toFile()
        try {
            val store =
                root.resolve("root/.git-credentials").apply {
                    parentFile.mkdirs()
                    writeText("https://x-access-token:old-token@github.com\n")
                }

            GitCredentialHelper(root).install()

            assertFalse(exists(store))
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

            GitCredentialHelper(root).install()

            assertFalse(gitConfig.readText().contains("\\t"))
            assertTrue(gitConfig.readText().contains("\thelper = opencode"))
        } finally {
            root.deleteRecursively()
        }
    }

    /**
     * OpenCode and Claude Code get the token as `OPENCODE_GITHUB_TOKEN`, Codex only as `GH_TOKEN`;
     * with no store file left, the helper is what makes `git push` authenticate for all of them.
     */
    @Test
    fun `the helper answers git with the token from either variable`() {
        assumeTrue("needs sh", File("/bin/sh").canExecute())
        val root = createTempDirectory("git-helper-run").toFile()
        try {
            val script = GitCredentialHelper(root).install()

            fun ask(environment: Map<String, String>): String {
                val process =
                    ProcessBuilder("/bin/sh", script.path, "get")
                        .redirectErrorStream(true)
                        .apply {
                            environment().remove("OPENCODE_GITHUB_TOKEN")
                            environment().remove("GH_TOKEN")
                            environment().putAll(environment)
                        }.start()
                process.outputStream.use { it.write("https\ngithub.com\n".toByteArray()) }
                assertTrue(process.waitFor(10, TimeUnit.SECONDS))
                return process.inputStream.bufferedReader().readText()
            }

            assertTrue(ask(mapOf("OPENCODE_GITHUB_TOKEN" to "from-opencode")).contains("password=from-opencode"))
            assertTrue(ask(mapOf("GH_TOKEN" to "from-gh")).contains("password=from-gh"))
            assertTrue(ask(mapOf("OPENCODE_GITHUB_TOKEN" to "first", "GH_TOKEN" to "second")).contains("password=first"))
            assertEquals("", ask(emptyMap()))
        } finally {
            root.deleteRecursively()
        }
    }

    /** An agent can point the old store at one of the app's own files; removing it removes only the link. */
    @Test
    fun `a store the guest linked to an app file loses only the link`() {
        val base = createTempDirectory("git-helper-link").toFile()
        try {
            val rootfs = File(base, "rootfs")
            val appFile =
                File(base, "shared_prefs/settings.xml").apply {
                    parentFile.mkdirs()
                    writeText("<map/>")
                }
            File(rootfs, "root").mkdirs()
            val store = File(rootfs, "root/.git-credentials")
            Files.createSymbolicLink(store.toPath(), appFile.toPath())

            GitCredentialHelper(rootfs).install()

            assertEquals("<map/>", appFile.readText())
            assertFalse(exists(store))
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

            runCatching { GitCredentialHelper(rootfs).install() }

            assertEquals("[private]\n\tsecret = 1\n", appFile.readText())
            assertTrue("the user's link stays", Files.isSymbolicLink(gitConfig.toPath()))
            assertTrue("the helper is still written", File(rootfs, "usr/local/bin/git-credential-opencode").isFile)
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `a linked home folder gets nothing written into it`() {
        val base = createTempDirectory("git-helper-home").toFile()
        try {
            val rootfs = File(base, "rootfs").apply { mkdirs() }
            val appFolder = File(base, "shared_prefs").apply { mkdirs() }
            Files.createSymbolicLink(File(rootfs, "root").toPath(), appFolder.toPath())

            runCatching { GitCredentialHelper(rootfs).install() }

            assertEquals(emptyList<String>(), appFolder.list()!!.toList())
        } finally {
            base.deleteRecursively()
        }
    }
}
