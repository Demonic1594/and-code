package com.yugahashimoto.andcode.runtime.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Keeps the OpenCode server's SQLite store small enough to stay writable.
 *
 * The runtime persists every `message.part.updated` it publishes - and each of those carries the
 * part's *full accumulated* payload again - so a long streaming session writes the same content
 * hundreds of times into the `event` table. Left alone, that table grows without bound: on-device
 * it reached 1.5 GB of a 2.14 GB database, which crossed the signed 32-bit file-offset boundary
 * (~2.1 GB) and made every further write fail. The write-ahead log grew in step because a
 * checkpoint can never complete against a database that busy, and a 590 MB WAL followed.
 *
 * The `event` table is a live-stream, not a transcript: reconnects and reloads read from the
 * `message` and `part` tables, so the log can be pruned at any time - including mid-session, WAL
 * locking keeps the server's concurrent writes safe - and deleting it was verified in production
 * to leave an in-flight session fully working. This class prunes it on a schedule, truncates the
 * WAL, and vacuums the file back down whenever pruning alone has left it oversized.
 *
 * All shell work goes through [shellRunner] (the same PRoot channel every other guest command
 * uses), so nothing here touches the guest filesystem directly.
 */
class OpencodeDatabaseMaintenance(
    private val shellRunner: suspend (command: String, timeoutSeconds: Long) -> LocalRuntimeCommandResult,
    private val markerFile: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val minIntervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
    private val vacuumThresholdBytes: Long = DEFAULT_VACUUM_THRESHOLD_BYTES,
    private val timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
) {
    init {
        require(minIntervalMillis >= 0L)
        require(vacuumThresholdBytes >= 0L)
        require(timeoutSeconds > 0L)
    }

    /**
     * Serializes runs: the startup trigger and every session-idle transition can call
     * [runIfDue] concurrently, and without this two of them can both read a stale marker and
     * both execute the script.
     */
    private val runMutex = Mutex()

    sealed interface Result {
        /** The previous run is more recent than [minIntervalMillis]; nothing was executed. */
        data object SkippedRecentlyRun : Result

        /** No runtime is installed; there is no database to maintain yet. */
        data object SkippedRuntimeUnavailable : Result

        /**
         * The maintenance script ran. [output] is its (usually empty) tail; a non-zero exit from
         * the script itself is tolerated - a locked or transiently missing database must not
         * escalate, and the marker is still advanced so the next attempt waits a full interval.
         */
        data class Ran(val output: String) : Result

        /**
         * The maintenance could not even be launched: the shell runner threw instead of returning
         * an exit code. The realistic causes - `createTempFile` failing on a full disk, the proot
         * binary failing to spawn - occur precisely on the degraded devices this class serves, and
         * the callers launch [runIfDue] bare on application-level scopes, so an escaping exception
         * would take the whole process down. The marker is deliberately NOT advanced: the failure
         * is environmental and may resolve on its own, so the next trigger retries immediately.
         */
        data class Failed(val reason: String) : Result
    }

    /**
     * Runs the maintenance when the marker file says the last run is older than
     * [minIntervalMillis]. Cheap enough to call from every session-idle transition: the not-due
     * path is one file read.
     */
    suspend fun runIfDue(): Result =
        runMutex.withLock {
            val now = clock()
            val lastRun = readMarker()
            if (lastRun != null && now - lastRun < minIntervalMillis) return@withLock Result.SkippedRecentlyRun
            val result =
                runCatching {
                        withContext(Dispatchers.IO) {
                            shellRunner(maintenanceScript(), timeoutSeconds)
                        }
                    }.getOrElse { failure ->
                        return@withLock Result.Failed(failure.message ?: failure.javaClass.simpleName)
                    }
            when {
                result.exitCode == RUNTIME_UNAVAILABLE_EXIT_CODE -> Result.SkippedRuntimeUnavailable
                else -> {
                    writeMarker(now)
                    Result.Ran(result.output)
                }
            }
        }

    private fun readMarker(): Long? = runCatching { markerFile.readText().trim().toLongOrNull() }.getOrNull()

    private fun writeMarker(timestamp: Long) {
        runCatching {
            markerFile.parentFile?.mkdirs()
            val staging = File.createTempFile("db-maintenance-", ".tmp", markerFile.parentFile)
            staging.writeText(timestamp.toString())
            if (!staging.renameTo(markerFile)) {
                markerFile.writeText(timestamp.toString())
                staging.delete()
            }
        }
    }

    /**
     * POSIX sh, executed inside the guest rootfs. `sqlite3` missing or the database not yet
     * created both exit 0: a runtime without the tool, or one that has never opened a session,
     * has nothing to maintain. `busy_timeout` makes every statement wait for the server's locks
     * instead of failing against them.
     *
     * The `message` update removes the entire `summary` object from pathologically large
     * messages: opencode's session summarizer attaches the full working-tree diff to a message,
     * and a big enough repository produced a 279 MB patch inside one row - loading that session
     * then needed a single ~293 MB string in the app's heap and OOMed regardless of heap size.
     * The whole `summary` key has to go, not just `diffs`: the server's schema requires `diffs`
     * whenever `summary` is present, so a stripped `summary: {}` object makes every later
     * write of that message fail validation. The diff is a preview of changes git itself holds;
     * only rows past [SUMMARY_STRIP_THRESHOLD_BYTES] are touched, far beyond any healthy summary.
     */
    internal fun maintenanceScript(): String {
        val vacuumThreshold = vacuumThresholdBytes
        val stripThreshold = SUMMARY_STRIP_THRESHOLD_BYTES
        return """
            DB="${'$'}HOME/.local/share/opencode/opencode.db"
            command -v sqlite3 >/dev/null 2>&1 || exit 0
            [ -f "${'$'}DB" ] || exit 0
            sqlite3 "${'$'}DB" "PRAGMA busy_timeout=30000; DELETE FROM event; DELETE FROM event_sequence; UPDATE message SET data = json_remove(data, '\$.summary') WHERE length(data) > $stripThreshold AND json_valid(data); PRAGMA wal_checkpoint(TRUNCATE);"
            if [ "$(wc -c < "${'$'}DB" 2>/dev/null || echo 0)" -gt $vacuumThreshold ]; then
              sqlite3 "${'$'}DB" "PRAGMA busy_timeout=30000; VACUUM; PRAGMA wal_checkpoint(TRUNCATE);"
            fi
            """.trimIndent()
    }

    companion object {
        /** Once a day: pruning is cheap, but it does take the runner's lock for a moment. */
        const val DEFAULT_INTERVAL_MILLIS: Long = 24L * 60L * 60L * 1000L

        /**
         * A healthy pruned database holds its transcripts in a few hundred MB; past half a gigabyte
         * it is carrying reclaimable bulk, and staying far under the 2 GB offset boundary is the
         * point of the exercise.
         */
        const val DEFAULT_VACUUM_THRESHOLD_BYTES: Long = 512L * 1024L * 1024L

        /** Generous on purpose: VACUUM of a badly grown file can legitimately take minutes. */
        const val DEFAULT_TIMEOUT_SECONDS: Long = 300L

        /**
         * Only summary diffs past this size are stripped. A healthy session summary's diff is
         * kilobytes; the threshold sits far above anything legitimate and far below the size at
         * which a single row breaks the app's heap (~250 MB).
         */
        const val SUMMARY_STRIP_THRESHOLD_BYTES: Long = 50L * 1024L * 1024L

        /** [LocalRuntimeCommandRunner] reports this exit code when nothing is installed. */
        private const val RUNTIME_UNAVAILABLE_EXIT_CODE = 127
    }
}
