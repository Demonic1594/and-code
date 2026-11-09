package com.yugahashimoto.andcode.runtime.local

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ApprovalLogTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var clock = 1_000L

    private fun log() = ApprovalLog(folder.root, now = { clock++ })

    @Test
    fun `the app's answers and the hook's decisions read as one history, newest first`() {
        val log = log()
        log.record("Bash", "git push", allowed = false, how = ApprovalLog.PHONE)
        File(folder.root, ApprovalLog.HOOK_FILE).writeText(
            """{"timeMs":1500,"tool":"Read","summary":"/root/a.kt","allowed":true,"how":"kind","rule":"read"}""" + "\n" +
                "not json\n" +
                """{"timeMs":500,"tool":"Bash","summary":"rm -rf /","allowed":false,"how":"block","rule":"rm -rf"}""" + "\n",
        )
        log.record("Bash", "npm test", allowed = true, how = ApprovalLog.PHONE, rule = "npm test")

        assertEquals(
            listOf("Read /root/a.kt", "Bash npm test", "Bash git push", "Bash rm -rf /"),
            log.entries.value.map { "${it.tool} ${it.summary}" },
        )
        assertEquals("rm -rf", log.entries.value.last().rule)
    }

    @Test
    fun `a file is cut back to its newest entries`() {
        val log = log()
        repeat(401) { log.record("Bash", "echo $it", allowed = true, how = ApprovalLog.RULE, rule = "echo") }

        val kept = File(folder.root, ApprovalLog.APP_FILE).readLines()
        assertEquals(200, kept.size)
        assertTrue(kept.last().contains("echo 400"))
        assertEquals(100, log.entries.value.size)
        assertEquals("echo 400", log.entries.value.first().summary)
    }

    @Test
    fun `the hook's file is cut back when the log opens, and long fields it wrote are clipped`() {
        val hookFile = File(folder.root, ApprovalLog.HOOK_FILE)
        val long = "y".repeat(500)
        hookFile.writeText(
            (0..400).joinToString("\n", postfix = "\n") { i ->
                """{"timeMs":$i,"tool":"Read","summary":"$long","allowed":true,"how":"rule","rule":"$long"}"""
            },
        )

        val log = log()
        log.refresh()

        assertEquals(200, hookFile.readLines().size)
        assertEquals(400L, log.entries.value.first().timeMs)
        assertEquals(200, log.entries.value.first().summary.length)
        assertEquals(200, log.entries.value.first().rule?.length)
    }

    @Test
    fun `clearing forgets both files`() {
        val log = log()
        log.record("Bash", "ls", allowed = true, how = ApprovalLog.PHONE)
        File(
            folder.root,
            ApprovalLog.HOOK_FILE,
        ).writeText("""{"timeMs":1,"tool":"Read","summary":"a","allowed":true,"how":"kind"}""" + "\n")

        log.clear()
        log.refresh()

        assertTrue(log.entries.value.isEmpty())
        assertFalse(File(folder.root, ApprovalLog.HOOK_FILE).exists())
    }

    @Test
    fun `a call is summed up by its command, file, URL or pattern, else its input`() {
        fun summary(input: String) = ApprovalLog.summaryOf(Json.parseToJsonElement(input))

        assertEquals("git status", summary("""{"command":"git status","description":"x"}"""))
        assertEquals("src/a.kt", summary("""{"file_path":"src/a.kt","content":"..."}"""))
        assertEquals("https://x.dev", summary("""{"url":"https://x.dev"}"""))
        assertEquals("""{"query":"kotlin"}""", summary("""{"query":"kotlin"}"""))
        assertEquals(200, summary("""{"command":"${"x".repeat(500)}"}""").length)
    }
}
