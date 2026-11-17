package com.yugahashimoto.andcode.runtime.local

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * Imports a SAF document tree into the local runtime workspace directory so
 * PRoot/OpenCode can access files as plain paths under /workspace.
 */
class SafWorkspaceImporter(
    private val context: Context,
    private val runtimeDirectory: File = File(context.filesDir, "runtime"),
) {
    fun importTree(treeUri: Uri): File {
        val source =
            DocumentFile.fromTreeUri(context, treeUri)
                ?: error("Unable to open selected folder")
        val name =
            source.name?.replace(Regex("[^A-Za-z0-9._-]"), "_")
                ?.takeIf { it.isNotBlank() }
                ?: "imported-${System.currentTimeMillis()}"
        // Through GuestFiles: the workspace is the guest's. A folder of this name may hold links it
        // left (File.deleteRecursively would walk into them), and the copy must not follow one either.
        val workspace = File(runtimeDirectory, "workspace")
        val destination = File(workspace, name)
        check(GuestFiles.deleteTree(workspace, name)) { "Unable to replace the existing folder $name" }
        copyDocument(source, workspace, name)
        return destination
    }

    private fun copyDocument(
        source: DocumentFile,
        workspace: File,
        relativePath: String,
    ) {
        if (source.isDirectory) {
            GuestFiles.createDirectories(workspace, relativePath)
            source.listFiles().forEach { child ->
                // A provider's name that is not one plain path component is skipped, not imported.
                val name = child.name?.takeIf(::isPlainName) ?: return@forEach
                val childPath = "$relativePath/$name"
                if (child.isDirectory) {
                    copyDocument(child, workspace, childPath)
                } else if (child.isFile) {
                    copyFile(child, workspace, childPath)
                }
            }
        } else if (source.isFile) {
            copyFile(source, workspace, relativePath)
        }
    }

    private fun copyFile(
        source: DocumentFile,
        workspace: File,
        relativePath: String,
    ) {
        context.contentResolver.openInputStream(source.uri)?.use { input ->
            GuestFiles.write(workspace, relativePath) { output -> input.copyTo(output) }
        } ?: error("Unable to read ${source.uri}")
    }
}

private fun isPlainName(name: String): Boolean = name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\u0000' !in name
