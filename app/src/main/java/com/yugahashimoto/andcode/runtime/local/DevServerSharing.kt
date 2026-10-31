package com.yugahashimoto.andcode.runtime.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Where sharing one local server stands. */
sealed interface ShareState {
    /** Fetching and verifying `cloudflared`, the first time only. */
    data object Installing : ShareState

    /** The tunnel runs; its public address is not known yet. */
    data object Starting : ShareState

    data class Live(val url: String) : ShareState

    /** The supervisor already runs as many processes as it allows. */
    data object TooManyProcesses : ShareState

    /** [reason] is the tunnel's own last words, or the installer's, for the user to read. */
    data class Failed(val reason: String) : ShareState
}

/**
 * Shares a server on this device with anyone who has the link, through a Cloudflare quick tunnel
 * (`cloudflared tunnel --url`): no account, a random `trycloudflare.com` address, gone when stopped.
 *
 * The tunnel is a [ProcessSupervisor] process like any server the agents start, so it gets the same
 * wake lock, notification with Stop all, time limit and log; the public address is read from that
 * log. It is never restarted on its own: a tunnel that died should be visible, not quietly renamed.
 */
class DevServerSharing(
    private val supervisor: ProcessSupervisor,
    private val install: suspend () -> Unit,
    private val scope: CoroutineScope,
    private val pollMillis: Long = POLL_MILLIS,
    private val startTimeoutMillis: Long = START_TIMEOUT_MILLIS,
    /**
     * Which loopback address the server answers on. A server bound to `localhost` may be IPv6 only
     * (Vite under recent Node often is); a tunnel pointed at the other family would serve a 502.
     */
    private val answeringAddress: suspend (port: Int) -> String? = LoopbackDevServerProbe()::answeringAddress,
) {
    private val mutableShares = MutableStateFlow<Map<Int, ShareState>>(emptyMap())

    /** The servers being shared, by port. A port that is not here is not shared. */
    val shares: StateFlow<Map<Int, ShareState>> = mutableShares.asStateFlow()

    private val jobs = mutableMapOf<Int, Job>()

    fun start(port: Int) {
        if (port !in 1..MAX_PORT) return
        synchronized(jobs) {
            if (jobs[port]?.isActive == true) return
            set(port, ShareState.Installing)
            jobs[port] = scope.launch { run(port) }
        }
    }

    fun stop(port: Int) {
        synchronized(jobs) { jobs.remove(port)?.cancel() }
        supervisor.stop(processName(port))
        mutableShares.update { it - port }
    }

    /** Ends the shares of servers that stopped answering: a tunnel must not outlive what it points at. */
    fun stopGone(stoppedPorts: Collection<Int>) {
        stoppedPorts.filter { it in mutableShares.value }.forEach(::stop)
    }

    private suspend fun run(port: Int) {
        try {
            share(port)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Whatever goes wrong ends this share, never the app with it. A share
            // already stopped leaves the tunnel alone: a new share on the same port may own it now.
            if (!currentCoroutineContext().isActive) return
            supervisor.stop(processName(port))
            set(port, ShareState.Failed(error.message ?: error.javaClass.simpleName))
        }
    }

    private suspend fun share(port: Int) {
        val problem = startTunnel(port)
        if (problem != null) {
            set(port, problem)
        } else if (currentCoroutineContext().isActive) {
            set(port, ShareState.Starting)
            watch(port, processName(port))
        } else {
            // Stopped while it was being started: take the tunnel down again rather than leave a
            // public link that nothing on screen knows about.
            supervisor.stop(processName(port))
        }
    }

    /** Installs cloudflared and starts the tunnel; what went wrong, or null when it runs. */
    private suspend fun startTunnel(port: Int): ShareState? {
        try {
            install()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return ShareState.Failed(error.message ?: error.javaClass.simpleName)
        }
        val address = answeringAddress(port) ?: return ShareState.Failed("nothing answers on port $port")
        val spec = tunnelSpec(port, address)
        return problemOf(supervisor.start(spec), spec)
    }

    private fun problemOf(
        result: StartResult,
        spec: SupervisedProcessSpec,
    ): ShareState? =
        when (result) {
            is StartResult.Started -> null
            // Only our own tunnel under this name counts; anything else must not be shown as its link.
            is StartResult.AlreadyRunning ->
                if (result.process.spec.command == spec.command) {
                    null
                } else {
                    ShareState.Failed(
                        "${spec.name} is already used by another process",
                    )
                }
            is StartResult.LimitReached -> ShareState.TooManyProcesses
            is StartResult.Failed -> ShareState.Failed(result.reason)
            else -> ShareState.Failed(result.javaClass.simpleName)
        }

    /** Waits for the address, then keeps the state true to the process until it ends. */
    private suspend fun watch(
        port: Int,
        name: String,
    ) {
        var waited = 0L
        while (true) {
            val process = supervisor.processes.value.firstOrNull { it.spec.name == name }
            val log = supervisor.logTail(name, LOG_CHARACTERS).orEmpty()
            val current = mutableShares.value[port] ?: return
            when {
                process == null || !process.isActive -> {
                    // Ended: by Stop all, its time limit, or a failure such as no network.
                    if (current is ShareState.Live) mutableShares.update { it - port } else set(port, ShareState.Failed(lastLine(log)))
                    return
                }
                current is ShareState.Starting -> {
                    val url = tunnelUrl(log)
                    if (url != null) {
                        set(port, ShareState.Live(url))
                    } else if (waited >= startTimeoutMillis) {
                        supervisor.stop(name)
                        set(port, ShareState.Failed(lastLine(log)))
                        return
                    }
                }
            }
            // Quick while waiting for the address; once it is live only the process's end matters.
            val pause = if (current is ShareState.Live) pollMillis * LIVE_POLL_FACTOR else pollMillis
            delay(pause)
            waited += pause
        }
    }

    /** Records [state] unless the share was stopped meanwhile, which a late update must not revive. */
    private fun set(
        port: Int,
        state: ShareState,
    ) {
        mutableShares.update { if (state == ShareState.Installing || port in it) it + (port to state) else it }
    }

    companion object {
        private const val POLL_MILLIS = 500L
        private const val LIVE_POLL_FACTOR = 4
        private const val START_TIMEOUT_MILLIS = 45_000L
        private const val LOG_CHARACTERS = 8_000
        private const val MAX_PORT = 65_535

        /** A quick tunnel is for trying something out, not for hosting: it ends after two hours. */
        private const val MAX_SHARE_MINUTES = 120

        // A quick tunnel's own address: not the API host cloudflared names in its errors
        // ("Post https://api.trycloudflare.com/tunnel"), and not followed by a path.
        private val TUNNEL_URL = Regex("""https://(?!api\.)[a-z0-9-]+\.trycloudflare\.com(?![\w./-])""")

        internal fun processName(port: Int) = "share-$port"

        /** Always a loopback literal, never `localhost`: what the tunnel exposes is this device only. */
        internal fun tunnelSpec(
            port: Int,
            address: String = "127.0.0.1",
        ): SupervisedProcessSpec {
            val host = if (address.contains(':')) "[$address]" else address
            return SupervisedProcessSpec(
                name = processName(port),
                command = "cloudflared tunnel --no-autoupdate --url http://$host:$port",
                directory = "/root",
                restart = RestartPolicy.NEVER,
                maxRunMinutes = MAX_SHARE_MINUTES,
                detectPort = false,
            )
        }

        /** The public address cloudflared prints once the tunnel is up. */
        internal fun tunnelUrl(log: String): String? = TUNNEL_URL.find(log)?.value

        internal fun lastLine(log: String): String = log.lineSequence().map(String::trim).lastOrNull(String::isNotEmpty).orEmpty()
    }
}
