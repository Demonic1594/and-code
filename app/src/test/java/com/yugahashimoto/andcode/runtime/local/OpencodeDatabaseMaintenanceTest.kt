package com.yugahashimoto.andcode.runtime.local

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpencodeDatabaseMaintenanceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private class Recorder(
        var result: LocalRuntimeCommandResult = LocalRuntimeCommandResult(0, ""),
    ) {
        val commands = mutableListOf<String>()
        val timeouts = mutableListOf<Long>()

        val runner: suspend (String, Long) -> LocalRuntimeCommandResult = { command, timeoutSeconds ->
            commands += command
            timeouts += timeoutSeconds
            result
        }
    }

    @Test
    fun `runs when no marker exists and writes the marker`() =
        runTest {
            val marker = temporaryFolder.newFile("marker")
            marker.delete()
            val recorder = Recorder()
            val maintenance =
                OpencodeDatabaseMaintenance(
                    shellRunner = recorder.runner,
                    markerFile = marker,
                    clock = { 1_000L },
                )

            val result = maintenance.runIfDue()

            assertEquals(OpencodeDatabaseMaintenance.Result.Ran("")::class, result::class)
            assertEquals(1, recorder.commands.size)
            assertTrue(marker.readText().trim() == "1000")
        }

    @Test
    fun `skips while the last run is inside the interval`() =
        runTest {
            val marker = temporaryFolder.newFile("marker")
            marker.writeText("5000")
            val recorder = Recorder()
            val maintenance =
                OpencodeDatabaseMaintenance(
                    shellRunner = recorder.runner,
                    markerFile = marker,
                    clock = { 5_000L + 60_000L },
                )

            val result = maintenance.runIfDue()

            assertEquals(OpencodeDatabaseMaintenance.Result.SkippedRecentlyRun, result)
            assertTrue(recorder.commands.isEmpty())
        }

    @Test
    fun `runs again once the interval has elapsed`() =
        runTest {
            val marker = temporaryFolder.newFile("marker")
            marker.writeText("0")
            val recorder = Recorder()
            val maintenance =
                OpencodeDatabaseMaintenance(
                    shellRunner = recorder.runner,
                    markerFile = marker,
                    clock = { OpencodeDatabaseMaintenance.DEFAULT_INTERVAL_MILLIS + 1L },
                )

            val result = maintenance.runIfDue()

            assertEquals(OpencodeDatabaseMaintenance.Result.Ran("")::class, result::class)
            assertEquals(1, recorder.commands.size)
        }

    @Test
    fun `runtime not installed skips without advancing the marker`() =
        runTest {
            val marker = temporaryFolder.newFile("marker")
            marker.delete()
            val recorder = Recorder(result = LocalRuntimeCommandResult(127, "not installed"))
            val maintenance =
                OpencodeDatabaseMaintenance(
                    shellRunner = recorder.runner,
                    markerFile = marker,
                    clock = { 1_000L },
                )

            val result = maintenance.runIfDue()

            assertEquals(OpencodeDatabaseMaintenance.Result.SkippedRuntimeUnavailable, result)
            assertFalse(marker.exists())
        }

    @Test
    fun `script prunes events checkpoints the wal and vacuums above the threshold`() {
        val marker = temporaryFolder.newFile("marker")
        val maintenance =
            OpencodeDatabaseMaintenance(
                shellRunner = Recorder().runner,
                markerFile = marker,
                vacuumThresholdBytes = 1_234L,
            )

        val script = maintenance.maintenanceScript()

        assertTrue(script.contains("DELETE FROM event;"))
        assertTrue(script.contains("DELETE FROM event_sequence;"))
        // The path survives shell quoting with a backslash before the $; assert on the
        // exact quoted fragment to pin the whole-summary removal.
        assertTrue(script.contains("""json_remove(data, '\$.summary')"""))
        assertTrue(script.contains("length(data) > 52428800"))
        assertTrue(script.contains("PRAGMA wal_checkpoint(TRUNCATE);"))
        assertTrue(script.contains("PRAGMA busy_timeout=30000;"))
        assertTrue(script.contains("-gt 1234"))
        assertTrue(script.contains("VACUUM;"))
        // The guest path, not a host path: maintenance must run inside the sandbox.
        assertTrue(script.contains("\$HOME/.local/share/opencode/opencode.db"))
        // A runtime without sqlite3, or a database that does not exist yet, is a clean no-op.
        assertTrue(script.contains("command -v sqlite3"))
        assertTrue(script.contains("|| exit 0"))
    }

    @Test
    fun `a throwing runner fails softly and leaves the marker alone`() =
        runTest {
            val marker = temporaryFolder.newFile("marker")
            marker.delete()
            var attempts = 0
            val throwingRunner: suspend (String, Long) -> LocalRuntimeCommandResult = { _, _ ->
                attempts++
                throw java.io.IOException("No space left on device")
            }
            val maintenance =
                OpencodeDatabaseMaintenance(
                    shellRunner = throwingRunner,
                    markerFile = marker,
                    clock = { 1_000L },
                )

            val result = maintenance.runIfDue()

            assertEquals(OpencodeDatabaseMaintenance.Result.Failed("No space left on device"), result)
            assertEquals(1, attempts)
            // Not advanced: the failure is environmental, so the next trigger retries at once.
            assertFalse(marker.exists())
        }

    @Test
    fun `an unreadable marker does not block the run`() =
        runTest {
            val marker = temporaryFolder.newFile("marker")
            marker.writeText("not-a-number")
            val recorder = Recorder()
            val maintenance =
                OpencodeDatabaseMaintenance(
                    shellRunner = recorder.runner,
                    markerFile = marker,
                    clock = { 1_000L },
                )

            val result = maintenance.runIfDue()

            assertEquals(OpencodeDatabaseMaintenance.Result.Ran("")::class, result::class)
            assertEquals(1, recorder.commands.size)
        }

    @Test
    fun `marker is written into a directory that did not exist yet`() =
        runTest {
            val marker = temporaryFolder.root.resolve("runtime").resolve("nested/marker")
            val recorder = Recorder()
            val maintenance =
                OpencodeDatabaseMaintenance(
                    shellRunner = recorder.runner,
                    markerFile = marker,
                    clock = { 42L },
                )

            maintenance.runIfDue()

            assertTrue(marker.isFile)
            assertEquals("42", marker.readText())
        }
}
