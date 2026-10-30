package com.yugahashimoto.andcode.runtime.local

import java.io.File

/**
 * Kills the guest processes a destroyed proot child leaves behind, by walking `/proc` for this
 * runtime's managed trees.
 *
 * `Process.destroyForcibly()` SIGKILLs only the direct child - proot itself. Its guest processes
 * are ptrace tracees: when the tracer dies they detach and keep running, and `--kill-on-exit`
 * cannot help because it is an exit handler inside a proot that never exits. The one-shot command
 * paths (diagnostics, clones, package installs) that skipped this walk after a timeout kill left
 * live guest processes burning CPU, holding rootfs and apk locks, and writing into the shared
 * workspace until the device rebooted.
 *
 * The same walk [LocalRuntimeProcessLauncher.terminate] performs for the long-lived server, minus
 * its graceful-destroy attempt: by the time a caller reaches for this, the direct child is
 * already gone.
 *
 * NB: no `android.os.Process` import here on purpose - `Process` must stay the java.lang one.
 */
internal fun killManagedProcessTrees(
    runtimeDirectory: File,
    child: Process? = null,
    procRoot: File = File("/proc"),
    signal: (Long) -> Unit = { pid -> android.os.Process.killProcess(pid.toInt()) },
) {
    val roots =
        linkedSetOf<Long>().apply {
            child?.let { processId(it)?.let(::add) }
            addAll(findManagedRuntimeRootPids(runtimeDirectory, procRoot))
        }
    roots
        .flatMap { rootPid -> processTreePostOrder(rootPid) { pid -> readDirectChildPids(pid, procRoot) } }
        .distinct()
        .forEach { pid -> runCatching { signal(pid) } }
}
