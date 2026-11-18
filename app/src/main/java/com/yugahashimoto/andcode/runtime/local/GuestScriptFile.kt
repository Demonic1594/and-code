package com.yugahashimoto.andcode.runtime.local

import java.io.File
import java.io.InputStream

/**
 * Puts [content] at [relativePath] in the guest [rootfs] as an executable script, through
 * [GuestFiles.write]: written to a temporary file beside it, then renamed over it. The rename
 * replaces whatever is there, so a link the guest made to one of this app's own files is replaced,
 * never written through, and a linked folder on the way is refused; and a script that is running
 * keeps reading the file it opened, never one cut short mid-write.
 */
internal fun replaceGuestScript(
    rootfs: File,
    relativePath: String,
    content: InputStream,
) {
    GuestFiles.write(rootfs, relativePath, GuestFiles.EXECUTABLE) { output -> content.copyTo(output) }
}
