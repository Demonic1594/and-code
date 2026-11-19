package com.yugahashimoto.andcode.feature.browser

import android.util.Log
import com.yugahashimoto.andcode.runtime.local.GuestFiles
import java.io.File
import java.io.IOException

private const val TAG = "AgentBrowserStatus"

/**
 * Writes the agent browser's status ([AgentBrowserState.statusJson]) into the guest home of each
 * installed rootfs, at [BROWSER_STATE_PATH], where the guest's browser tool reads it.
 *
 * The guest owns that home, so the write goes through [GuestFiles], like the handoff status beside
 * it: a link planted on the way is refused, one at the file is replaced rather than written through,
 * and the guest only ever reads a whole status, never half of one.
 */
internal class AgentBrowserStatusFile(
    private val runtimeDirectory: File,
) {
    fun write(json: String) {
        ROOTFS_DIRECTORIES.map { File(runtimeDirectory, it) }.filter { it.isDirectory }.forEach { rootfs ->
            try {
                GuestFiles.write(rootfs, BROWSER_STATE_PATH, json)
            } catch (error: IOException) {
                Log.w(TAG, "The browser status could not be written", error)
            }
        }
    }

    private companion object {
        val ROOTFS_DIRECTORIES = listOf("environment/rootfs", "environment/antigravity-rootfs")
    }
}
