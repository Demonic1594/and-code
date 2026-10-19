package com.yugahashimoto.andcode.runtime.local

import java.io.File

/**
 * Which attachment MIME types a runtime can carry as an ordinary model file part, and how a guest
 * `/workspace` path maps onto the on-device runtime directory.
 *
 * Providers reject file parts outside the small set of types they actually read (for example
 * `'file part media type application/zip' functionality not supported`). Anything else is copied
 * into the workspace and referenced by path, which every coding-agent runtime can read with its own
 * file tools. Video is not listed: [AttachmentImporter] converts it to image frames before it ever
 * reaches here.
 */
object AttachmentSupport {
    private const val PDF_MIME = "application/pdf"
    private const val WORKSPACE_ROOT = "/workspace"

    fun isModelSendable(mime: String): Boolean = mime.startsWith("image/", ignoreCase = true) || mime.equals(PDF_MIME, ignoreCase = true)

    /**
     * Host directory for a guest workspace path such as `/workspace/my-project`, or null when the
     * path is not under `/workspace` (a remote runtime's own filesystem, for instance).
     */
    fun hostWorkspace(
        guestPath: String?,
        runtimeWorkspaceRoot: File,
    ): File? {
        val path = guestPath?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return null
        if (path != WORKSPACE_ROOT && !path.startsWith("$WORKSPACE_ROOT/")) return null
        val relative = path.removePrefix(WORKSPACE_ROOT).trim('/')
        if (relative.isEmpty()) return runtimeWorkspaceRoot
        val candidate = File(runtimeWorkspaceRoot, relative)
        // A session's directory is attacker-influenced, so re-check the resolved path stays inside
        // the local runtime directory: `..` segments must not copy the file elsewhere on the device.
        return runCatching {
            val root = runtimeWorkspaceRoot.canonicalPath.trimEnd(File.separatorChar)
            val resolved = candidate.canonicalPath
            candidate.takeIf { resolved == root || resolved.startsWith("$root${File.separator}") }
        }.getOrNull()
    }

    /**
     * Guest path for a host file inside the local runtime workspace, or null when the file is not
     * under it - the inverse of [hostWorkspace], used to tell the agent where a copied file landed.
     */
    fun guestWorkspacePath(
        hostFile: File,
        runtimeWorkspaceRoot: File,
    ): String? {
        val root = runtimeWorkspaceRoot.absolutePath.trimEnd('/')
        val path = hostFile.absolutePath
        if (path != root && !path.startsWith("$root/")) return null
        return WORKSPACE_ROOT + path.removePrefix(root)
    }
}
