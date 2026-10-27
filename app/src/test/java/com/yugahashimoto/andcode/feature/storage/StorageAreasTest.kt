package com.yugahashimoto.andcode.feature.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class StorageAreasTest {
    @get:Rule val temp = TemporaryFolder()

    private lateinit var files: File
    private lateinit var cache: File
    private lateinit var areas: StorageAreas

    private fun write(
        base: File,
        path: String,
        bytes: Int,
    ): File =
        File(base, path).apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(bytes))
        }

    @Before
    fun setUp() {
        files = temp.newFolder("files")
        cache = temp.newFolder("cache")
        areas = StorageAreas(files, cache)
        write(files, "runtime/workspace/app/src/main.kt", 1_000)
        write(files, "runtime/workspace/.andcode-attachments/s1/shot.png", 300)
        write(files, "runtime/environment/rootfs/usr/bin/bash", 5_000)
        write(files, "runtime/environment/rootfs/root/.npm/_cacache/blob", 2_000)
        write(files, "runtime/environment/rootfs/root/.claude/projects/x.jsonl", 400)
        write(files, "runtime/environment/rootfs/root/.local/share/claude/versions/2.1", 3_000)
        write(files, "runtime/environment/rootfs/usr/local/bin/codex", 700)
        write(files, "runtime/cache/debian.tar.gz", 6_000)
        write(files, "runtime/logs/claude-stderr.log", 50)
        write(files, "runtime/update-staging-1.2.3/rootfs/a", 800)
        write(files, "runtime/environment.rollback/rootfs/b", 900)
        write(files, "runtime/claude-messages.json", 120)
        write(files, "vosk/model.bin", 1_500)
        write(files, "misc/other.bin", 10)
        write(cache, "exports/chat.md", 40)
    }

    private fun scan() = StorageScanner.scan(areas.roots)

    @Test
    fun `areas divide the total without counting anything twice`() {
        val scan = scan()
        val usage = areas.breakdown(scan).associate { it.area to it.bytes }

        assertEquals(1_000L, usage[StorageArea.PROJECTS])
        assertEquals(300L, usage[StorageArea.ATTACHMENTS])
        assertEquals(3_700L, usage[StorageArea.AGENTS])
        assertEquals(520L, usage[StorageArea.AGENT_DATA])
        assertEquals(8_040L, usage[StorageArea.CACHES])
        assertEquals(5_000L, usage[StorageArea.LINUX])
        assertEquals(800L, usage[StorageArea.LEFTOVERS])
        assertEquals(900L, usage[StorageArea.BACKUP])
        assertEquals(1_500L, usage[StorageArea.VOICE])
        assertEquals(10L, usage[StorageArea.OTHER])
        assertEquals(scan.bytes, usage.values.sum())
    }

    @Test
    fun `cleaning empties caches and logs, removes leftovers, and never touches the backup`() {
        val cleanups = areas.cleanups(scan()).associateBy { it.area }
        assertEquals(setOf(StorageArea.CACHES, StorageArea.LEFTOVERS, StorageArea.LOGS), cleanups.keys)

        val freed = cleanups.values.sumOf { areas.clean(it) }

        assertEquals(8_040L + 800L + 50L, freed)
        assertTrue("cache folders stay for the app to reuse", File(files, "runtime/cache").isDirectory)
        assertFalse(File(files, "runtime/update-staging-1.2.3").exists())
        assertTrue(File(files, "runtime/environment.rollback/rootfs/b").exists())
        assertTrue(File(files, "runtime/workspace/app/src/main.kt").exists())
    }

    @Test
    fun `only projects and refetchable places can be deleted from the browser`() {
        assertTrue(areas.deletable(File(files, "runtime/workspace/app")))
        assertTrue(areas.deletable(File(files, "runtime/cache/debian.tar.gz")))
        assertTrue(areas.deletable(File(files, "runtime/update-staging-1.2.3")))
        assertFalse("the workspace itself stays", areas.deletable(File(files, "runtime/workspace")))
        assertFalse(areas.deletable(File(files, "runtime/environment/rootfs/usr/bin/bash")))
        assertFalse(areas.deletable(File(files, "runtime/environment.rollback")))
        assertFalse(areas.deletable(File(files, "runtime/environment/rootfs/root/.claude")))
        assertFalse(areas.deletable(File(files, "runtime/workspace/../environment/rootfs")))
    }

    @Test
    fun `the scan does not follow links, and deleting a link leaves its target`() {
        val outside = temp.newFolder("outside")
        write(outside, "precious.txt", 4_000)
        Files.createSymbolicLink(File(files, "runtime/workspace/link").toPath(), outside.toPath())

        val scan = scan()
        assertEquals(1_300L, scan.find(File(files, "runtime/workspace"))?.bytes)

        StorageScanner.delete(File(files, "runtime/workspace"))
        assertTrue(File(outside, "precious.txt").exists())
    }

    @Test
    fun `cleaning keeps hard-link data that projects point into`() {
        write(files, "runtime/environment/rootfs/root/.npm/_cacache/.l2s.blob0001", 500)
        val cleanup = areas.cleanups(scan()).first { it.area == StorageArea.CACHES }

        areas.clean(cleanup)

        assertTrue(File(files, "runtime/environment/rootfs/root/.npm/_cacache/.l2s.blob0001").exists())
        assertFalse(File(files, "runtime/environment/rootfs/root/.npm/_cacache/blob").exists())
    }

    @Test
    fun `deleting from the browser keeps hard-link data too`() {
        val data = write(files, "runtime/environment/rootfs/root/.cache/pip/http/.l2s.wheel0001", 500)
        write(files, "runtime/environment/rootfs/root/.cache/pip/http/wheel", 200)

        areas.delete(File(files, "runtime/environment/rootfs/root/.cache/pip/http"))

        assertTrue(data.exists())
        assertFalse(File(data.parentFile, "wheel").exists())
    }

    @Test
    fun `a cache folder that is a link is left alone, and so is what it points at`() {
        val project = write(files, "runtime/workspace/app/keep.txt", 100).parentFile!!
        val logs = File(files, "runtime/logs")
        logs.deleteRecursively()
        Files.createSymbolicLink(logs.toPath(), project.toPath())

        areas.cleanups(scan()).forEach { areas.clean(it) }

        assertTrue(File(project, "keep.txt").exists())
        assertTrue(File(files, "runtime/workspace/app/src/main.kt").exists())
        assertFalse("nothing is browsed through the link", areas.withoutLinks(File(logs, "keep.txt")))
        assertFalse(areas.deletable(File(logs, "keep.txt")))
    }

    @Test
    fun `leftovers go through a trash folder that does not stay behind`() {
        val leftovers = areas.cleanups(scan()).first { it.area == StorageArea.LEFTOVERS }

        assertEquals(800L, areas.clean(leftovers))
        assertFalse(File(files, "runtime/update-staging-1.2.3").exists())
        assertTrue(files.listFiles().orEmpty().none { it.name.startsWith(".storage-trash-") })
    }

    @Test
    fun `a clean stops at the next folder once something gets busy`() {
        write(files, "diagnostics/last-crash.txt", 30)
        val logs = areas.cleanups(scan()).first { it.area == StorageArea.LOGS }
        var asked = 0

        val freed = areas.clean(logs) { asked++ > 0 }

        assertEquals(50L, freed)
        assertTrue(File(files, "diagnostics/last-crash.txt").exists())
    }

    @Test
    fun `the largest files come first`() {
        val largest = scan().largest.take(3).map { it.file.name to it.bytes }
        assertEquals(listOf("debian.tar.gz" to 6_000L, "bash" to 5_000L, "2.1" to 3_000L), largest)
    }
}
