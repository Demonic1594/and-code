package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.runtime.local.InstallerErrors.Antigravity
import com.yugahashimoto.andcode.runtime.local.InstallerErrors.Claude
import com.yugahashimoto.andcode.runtime.local.InstallerErrors.Codex
import com.yugahashimoto.andcode.runtime.local.InstallerErrors.Debian
import com.yugahashimoto.andcode.runtime.local.InstallerErrors.Environment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.io.IOException
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Installer failures reach the user in the app's language, read from the raw `strings.xml` files so
 * this runs on the JVM, while their English message stays what the logs always showed.
 */
class InstallerErrorsTest {
    private val logTail = "E: Unable to fetch some archives, maybe run apt-get update"
    private val refused = IOException("etc/apt is a link")

    private val all: List<InstallerException> =
        listOf(
            Debian.tokenHttp(401),
            Debian.tokenMissing(),
            Debian.activateFailed(),
            Debian.ptyMissing(),
            Debian.aptRefused(refused),
            Debian.toolsTimedOut(),
            Debian.packagesFailed(logTail),
            Debian.githubCliTimedOut(),
            Debian.githubCliFailed(logTail),
            Debian.glibcLoaderMissing("ld-linux-aarch64.so.1"),
            Environment.toolsTimedOut(),
            Environment.packagesFailed(logTail),
            Environment.missing(),
            Environment.antigravityMissing(),
            Environment.openCodeBinaryMissing(),
            Environment.openCodeNotExecutable(),
            Environment.prootMissing(),
            Claude.timedOut(15),
            Claude.failed(
                update = false,
                exitCode = 1,
                primary = "ERROR: unable to select packages:",
                details = "\n--- log tail ---\n$logTail",
            ),
            Claude.failed(update = true, exitCode = 2, primary = "ERROR: boom", details = ""),
            Claude.binaryMissing("/usr/local/bin/claude"),
            Codex.unsupportedAbi("x86"),
            Codex.downloadHttp(503),
            Codex.downloadEmpty(),
            Codex.checksumMismatch("abc=", "def="),
            Codex.tarballIncomplete("package/vendor/aarch64-unknown-linux-musl/bin/codex"),
            Codex.binaryMissing("usr/local/bin/codex"),
            Antigravity.lowSpace(123_456_789L),
            Antigravity.binaryMissing(),
        )

    @Test
    fun `each failure reads in English exactly as the message the logs keep`() {
        val english = Strings("values", "en")
        all.forEach { error ->
            assertEquals(english.nameOf(error.messageRes), error.message, error.localized(english::format))
        }
    }

    @Test
    fun `the shared retry hint is the one the logs carry`() {
        assertEquals(PACKAGE_INSTALL_RETRY_HINT, Strings("values", "en").format(R.string.installer_error_retry_hint, emptyArray()))
    }

    @Test
    fun `every language words every failure and keeps what the installer reported`() {
        LOCALES.forEach { (dir, tag) ->
            val strings = Strings(dir, tag)
            all.forEach { error ->
                val name = strings.nameOf(error.messageRes)
                val text = error.localized(strings::format)
                assertFalse("$dir: $name is formatted: $text", text.contains('%'))
                error.formatArgs.filterIsInstance<String>().forEach { arg ->
                    assertTrue("$dir: $name keeps \"$arg\"", text.contains(arg))
                }
                if (dir != "values") assertNotEquals("$dir: $name is translated", error.message, text)
            }
        }
    }

    @Test
    fun `a failure that asks for a retry says so in the same language`() {
        val japanese = Strings("values-ja", "ja")
        val hint = japanese.format(R.string.installer_error_retry_hint, emptyArray())
        listOf(Debian.toolsTimedOut(), Environment.packagesFailed(logTail), Claude.timedOut(15))
            .forEach { error -> assertTrue(error.localized(japanese::format).contains(hint)) }
    }

    @Test
    fun `a refused apt path keeps its cause`() {
        assertSame(refused, Debian.aptRefused(refused).cause)
    }

    @Test
    fun `without an Android context the English message is what is shown`() {
        val error = Debian.githubCliTimedOut()
        assertEquals("GitHub CLI installation timed out", LocalRuntimeMessages.describe(error))
        assertEquals("GitHub CLI installation timed out", ClaudeMessages.describe(error))
        assertEquals("plain", LocalRuntimeMessages.describe(IllegalStateException("plain")))
    }

    @Test
    fun `a Claude Code failure puts the hint between its headline and the log`() {
        val log =
            File.createTempFile("claude-install", ".log").apply {
                deleteOnExit()
                writeText("OK: 28595 distinct packages available\nERROR: unable to select packages:\n")
            }
        val error = ClaudeCodeInstaller.failure(update = false, exitCode = 1, log = log)
        assertEquals(R.string.installer_error_claude_install_failed, error.messageRes)
        val french = error.localized(Strings("values-fr", "fr")::format)
        val lines = french.lines()
        assertTrue(lines.first().contains("ERROR: unable to select packages:"))
        val hint = Strings("values-fr", "fr").format(R.string.installer_error_retry_hint, emptyArray())
        assertTrue(lines.indexOf(hint) in 1 until lines.indexOf("--- log tail ---"))
    }

    @Test
    fun `a damaged Codex download fails with its own wording`() {
        val file = File.createTempFile("codex", ".tgz").apply { deleteOnExit() }
        val error = assertThrows(InstallerException::class.java) { CodexInstaller.verifySha512(file, "sha512-AAAA") }
        assertEquals(R.string.installer_error_codex_checksum, error.messageRes)
        assertTrue(error.message.orEmpty().contains("mismatch"))
    }

    /** One language's strings, unescaped the way Android's resource compiler does it. */
    private class Strings(
        dir: String,
        tag: String,
    ) {
        private val locale: Locale = Locale.forLanguageTag(tag)
        private val values: Map<String, String> =
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(resDir(), "$dir/strings.xml")).documentElement
                .getElementsByTagName("string")
                .let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }
                .associate { it.getAttribute("name") to unescape(it.textContent) }

        fun nameOf(res: Int): String = R.string::class.java.fields.first { it.getInt(null) == res }.name

        fun format(
            res: Int,
            args: Array<Any>,
        ): String {
            val name = nameOf(res)
            return String.format(locale, values[name] ?: error("No $name in $locale"), *args)
        }

        private fun unescape(raw: String): String =
            raw.trim()
                .removeSurrounding("\"")
                .replace("\\'", "'")
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\@", "@")
                .replace("\\?", "?")

        private fun resDir(): File {
            var dir = File(".").canonicalFile
            repeat(8) {
                File(dir, "app/src/main/res").takeIf { it.isDirectory }?.let { return it }
                File(dir, "src/main/res").takeIf { it.isDirectory }?.let { return it }
                dir = dir.parentFile ?: return@repeat
            }
            error("No res directory above ${File(".").canonicalPath}")
        }
    }

    private companion object {
        val LOCALES =
            mapOf(
                "values" to "en",
                "values-ar" to "ar",
                "values-es" to "es",
                "values-fr" to "fr",
                "values-ja" to "ja",
                "values-pt-rBR" to "pt-BR",
                "values-ru" to "ru",
                "values-zh-rCN" to "zh-CN",
            )
    }
}
