package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.runtime.RuntimeWorkTracker
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.io.RandomAccessFile

/** What to do when a supervised process exits without being stopped. */
enum class RestartPolicy {
    /** Leave it stopped; the user or the agent starts it again. */
    NEVER,

    /** Start it again after a non-zero exit, up to [ProcessSupervisor.MAX_RESTARTS] in a row. */
    ON_CRASH,

    /** Start it again after any exit, with the same limit. */
    ALWAYS,
}

enum class SupervisedStatus { RUNNING, RESTARTING, CRASHED, EXITED, STOPPED }

/** A process to keep running: [command] runs under `sh -lc` in [directory], a guest path. */
data class SupervisedProcessSpec(
    val name: String,
    val command: String,
    val directory: String = "/workspace",
    val port: Int? = null,
    val restart: RestartPolicy = RestartPolicy.ON_CRASH,
    /** Stopped after this long, so a forgotten server cannot hold the wake lock for days; null is no limit. */
    val maxRunMinutes: Int? = DEFAULT_MAX_RUN_MINUTES,
) {
    companion object {
        const val DEFAULT_MAX_RUN_MINUTES = 240
        private val NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,39}$")

        fun isValidName(name: String): Boolean = NAME.matches(name)
    }
}

data class SupervisedProcess(
    val spec: SupervisedProcessSpec,
    val status: SupervisedStatus,
    val startedAtMillis: Long,
    val exitCode: Int? = null,
    val restarts: Int = 0,
    /** The port given at start, else the first local URL the process printed. */
    val port: Int? = spec.port,
)

/** A started guest process. */
interface GuestProcessHandle {
    /** Blocks until the process exits and returns its exit code. */
    fun waitFor(): Int

    /** Ends the process and everything it started. */
    fun kill()
}

fun interface GuestProcessLauncher {
    /** Starts [spec] with its output appended to [log]. Throws when it cannot start at all. */
    fun start(
        spec: SupervisedProcessSpec,
        log: File,
    ): GuestProcessHandle
}

/** Keeps the list of processes, so the app can offer to bring them back after it was closed. */
interface SupervisorStore {
    fun load(): List<SupervisedProcessSpec>

    fun save(specs: List<SupervisedProcessSpec>)
}

sealed interface StartResult {
    data class Started(val process: SupervisedProcess) : StartResult

    data class AlreadyRunning(val process: SupervisedProcess) : StartResult

    data class LimitReached(val limit: Int) : StartResult

    data class PortTaken(val port: Int, val by: String) : StartResult

    data object InvalidName : StartResult

    data class Failed(val reason: String) : StartResult
}

/**
 * Owns long-running guest processes (dev servers, watchers) for the agents, so they outlive the
 * agent's turn, the chat and the agent itself, and are visible and stoppable in the app.
 *
 * A server started with `nohup … &` from an agent's tool shell dies when the phone sleeps, because
 * nothing holds the wake lock for it, and nobody can see or stop it. Here each running process holds
 * a [RuntimeWorkTracker] lease, so the device stays awake exactly while something runs; a time limit
 * ends a forgotten one. At most [maxProcesses] run at once, because Android kills an app whose
 * process tree grows too large.
 */
class ProcessSupervisor(
    private val launcher: GuestProcessLauncher,
    private val logDirectory: File,
    private val store: SupervisorStore,
    private val runtimeWork: RuntimeWorkTracker,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxProcesses: Int = DEFAULT_MAX_PROCESSES,
    private val restartDelayMillis: Long = RESTART_DELAY_MILLIS,
) {
    private val mutableProcesses = MutableStateFlow<List<SupervisedProcess>>(emptyList())

    /** Every process started since the app started, running or not, in start order. */
    val processes: StateFlow<List<SupervisedProcess>> = mutableProcesses.asStateFlow()

    private val lock = Any()
    private val handles = mutableMapOf<String, GuestProcessHandle>()
    private val jobs = mutableMapOf<String, Job>()

    /** Names stopped on purpose: their exit is not a crash and is never restarted. */
    private val stopping = mutableSetOf<String>()

    /** The processes that were kept when the app last ran, to offer to start again. */
    fun saved(): List<SupervisedProcessSpec> = runCatching { store.load() }.getOrDefault(emptyList())

    fun start(spec: SupervisedProcessSpec): StartResult =
        synchronized(lock) {
            refusal(spec) ?: launch(spec)
        }

    /** Why [spec] may not start now, or null when it may. Called holding [lock]. */
    private fun refusal(spec: SupervisedProcessSpec): StartResult? {
        val running = mutableProcesses.value.filter { it.isActive }
        val sameName = running.firstOrNull { it.spec.name == spec.name }
        val samePort = spec.port?.let { port -> running.firstOrNull { it.port == port } }
        return when {
            !SupervisedProcessSpec.isValidName(spec.name) -> StartResult.InvalidName
            sameName != null -> StartResult.AlreadyRunning(sameName)
            running.size >= maxProcesses -> StartResult.LimitReached(maxProcesses)
            samePort != null && spec.port != null -> StartResult.PortTaken(spec.port, samePort.spec.name)
            else -> null
        }
    }

    /** Starts [spec] and begins watching it. Called holding [lock]. */
    private fun launch(spec: SupervisedProcessSpec): StartResult {
        val log = logFile(spec.name)
        rotate(log)
        val handle =
            try {
                launcher.start(spec, log)
            } catch (error: Exception) {
                return StartResult.Failed(error.message ?: error.javaClass.simpleName)
            }
        val process = SupervisedProcess(spec, SupervisedStatus.RUNNING, clock())
        stopping -= spec.name
        handles[spec.name] = handle
        mutableProcesses.update { list -> list.filterNot { it.spec.name == spec.name } + process }
        jobs[spec.name] = scope.launch { supervise(spec, handle) }
        persist()
        return StartResult.Started(process)
    }

    /** Stops [name]. Returns false when it was not running. */
    fun stop(name: String): Boolean {
        val handle =
            synchronized(lock) {
                val process = mutableProcesses.value.firstOrNull { it.spec.name == name && it.isActive } ?: return false
                stopping += name
                setStatus(name) { it.copy(status = SupervisedStatus.STOPPED) }
                // A process waiting out its restart delay has no handle; cancelling the job is enough.
                if (process.status == SupervisedStatus.RESTARTING) jobs[name]?.cancel()
                persist()
                handles.remove(name)
            }
        handle?.let { scope.launch(ioDispatcher) { runCatching { it.kill() } } }
        return true
    }

    fun stopAll() {
        mutableProcesses.value.filter { it.isActive }.forEach { stop(it.spec.name) }
    }

    /** Stops [name] if it runs and starts it again with the same settings. */
    fun restart(name: String): StartResult {
        val spec = mutableProcesses.value.firstOrNull { it.spec.name == name }?.spec ?: return StartResult.Failed("no process named $name")
        stop(name)
        synchronized(lock) { mutableProcesses.update { list -> list.filterNot { it.spec.name == name } } }
        return start(spec)
    }

    /** Forgets a process that is no longer running, from the list and from the saved ones. */
    fun remove(name: String): Boolean {
        synchronized(lock) {
            val process = mutableProcesses.value.firstOrNull { it.spec.name == name } ?: return false
            if (process.isActive) return false
            mutableProcesses.update { list -> list.filterNot { it.spec.name == name } }
            persist()
            return true
        }
    }

    /** The last [maxCharacters] of [name]'s output, or null when it never ran. */
    fun logTail(
        name: String,
        maxCharacters: Int = DEFAULT_LOG_CHARACTERS,
    ): String? {
        val log = logFile(name)
        if (!log.isFile) return null
        return readTail(log, maxCharacters)
    }

    fun logFile(name: String): File = File(logDirectory, "$name.log")

    private suspend fun supervise(
        initialSpec: SupervisedProcessSpec,
        initialHandle: GuestProcessHandle,
    ) {
        val name = initialSpec.name
        var handle = initialHandle
        var restarts = 0
        val lease = runtimeWork.acquire(LEASE_TAG)
        val timer =
            initialSpec.maxRunMinutes?.let { minutes ->
                scope.launch {
                    delay(minutes * MILLIS_PER_MINUTE)
                    stop(name)
                }
            }
        val watcher = scope.launch(ioDispatcher) { watchOutput(name) }
        try {
            while (true) {
                val exit = runCatching { runInterruptible(ioDispatcher) { handle.waitFor() } }.getOrDefault(-1)
                val next =
                    synchronized(lock) {
                        handles.remove(name)
                        if (name in stopping) return
                        val retry = shouldRestart(initialSpec.restart, exit) && restarts < MAX_RESTARTS
                        setStatus(name) {
                            it.copy(
                                status =
                                    if (retry) {
                                        SupervisedStatus.RESTARTING
                                    } else if (exit == 0) {
                                        SupervisedStatus.EXITED
                                    } else {
                                        SupervisedStatus.CRASHED
                                    },
                                exitCode = exit,
                            )
                        }
                        if (!retry) persist()
                        retry
                    }
                if (!next) return
                restarts++
                delay(restartDelayMillis * restarts)
                handle =
                    synchronized(lock) {
                        if (name in stopping) return
                        val started =
                            runCatching { launcher.start(initialSpec, logFile(name)) }.getOrElse {
                                setStatus(name) { process -> process.copy(status = SupervisedStatus.CRASHED) }
                                persist()
                                return
                            }
                        handles[name] = started
                        setStatus(name) { it.copy(status = SupervisedStatus.RUNNING, restarts = restarts, startedAtMillis = clock()) }
                        started
                    }
            }
        } finally {
            timer?.cancel()
            watcher.cancel()
            lease.release()
        }
    }

    /** Fills in the port from the output, and keeps the log from growing without end. */
    private suspend fun watchOutput(name: String) {
        while (true) {
            val log = logFile(name)
            val current = mutableProcesses.value.firstOrNull { it.spec.name == name }
            if (current?.port == null && log.isFile) {
                detectPort(readTail(log, PORT_SCAN_CHARACTERS))?.let { port -> setStatus(name) { it.copy(port = it.port ?: port) } }
            }
            if (log.length() > MAX_LOG_BYTES) trim(log)
            delay(WATCH_INTERVAL_MILLIS)
        }
    }

    private fun setStatus(
        name: String,
        change: (SupervisedProcess) -> SupervisedProcess,
    ) {
        mutableProcesses.update { list -> list.map { if (it.spec.name == name) change(it) else it } }
    }

    /** Saves the processes that should come back: those running, or meant to be restarted. */
    private fun persist() {
        val kept = mutableProcesses.value.filter { it.isActive }.map { it.spec }
        runCatching { store.save(kept) }
    }

    private fun rotate(log: File) {
        log.parentFile?.mkdirs()
        if (log.isFile) {
            val previous = File(log.parentFile, log.name + ".1")
            previous.delete()
            log.renameTo(previous)
        }
    }

    /**
     * Keeps the newest output. The process appends, so truncating the file in place is safe: its
     * next write lands at the new end.
     */
    private fun trim(log: File) {
        runCatching {
            val tail = readTail(log, KEPT_LOG_CHARACTERS)
            RandomAccessFile(log, "rw").use { it.setLength(0) }
            log.appendText(tail)
        }
    }

    companion object {
        const val DEFAULT_MAX_PROCESSES = 5
        const val MAX_RESTARTS = 5
        const val LEASE_TAG = "supervised-processes"
        private const val RESTART_DELAY_MILLIS = 2_000L
        private const val MILLIS_PER_MINUTE = 60_000L
        private const val WATCH_INTERVAL_MILLIS = 2_000L
        private const val DEFAULT_LOG_CHARACTERS = 4_000
        private const val PORT_SCAN_CHARACTERS = 16_000
        private const val MAX_LOG_BYTES = 2L * 1024 * 1024
        private const val KEPT_LOG_CHARACTERS = 200_000

        private val LOCAL_URL = Regex("""https?://(?:localhost|127\.0\.0\.1|0\.0\.0\.0|\[::1?])(?::(\d{2,5}))""")

        /** The port of the first local URL in [output], the way dev servers announce themselves. */
        internal fun detectPort(output: String): Int? =
            LOCAL_URL.find(output)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..65_535 }

        internal fun shouldRestart(
            policy: RestartPolicy,
            exitCode: Int,
        ): Boolean =
            when (policy) {
                RestartPolicy.NEVER -> false
                RestartPolicy.ON_CRASH -> exitCode != 0
                RestartPolicy.ALWAYS -> true
            }

        internal fun readTail(
            file: File,
            maxCharacters: Int,
        ): String =
            RandomAccessFile(file, "r").use { raf ->
                val window = (maxCharacters.toLong() * 4).coerceAtMost(raf.length())
                raf.seek(raf.length() - window)
                val bytes = ByteArray(window.toInt())
                raf.readFully(bytes)
                bytes.decodeToString().takeLast(maxCharacters)
            }
    }
}

/** Running, or about to run again. */
val SupervisedProcess.isActive: Boolean
    get() = status == SupervisedStatus.RUNNING || status == SupervisedStatus.RESTARTING
