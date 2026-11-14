package com.yugahashimoto.andcode.runtime.local

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Puts [content] at [target], in a guest rootfs, as an executable script: written to a temporary file
 * beside it, then renamed over it. The rename replaces whatever is there, so a link the guest made to
 * one of this app's own files is replaced, never written through; and a script that is running keeps
 * reading the file it opened, never one cut short mid-write.
 */
internal fun replaceGuestScript(
    target: File,
    content: InputStream,
) {
    val temporary = File.createTempFile(".${target.name}.", ".tmp", target.parentFile)
    try {
        // Not through a link the guest may have swapped in for the new file meanwhile.
        Files
            .newOutputStream(temporary.toPath(), StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)
            .use { content.copyTo(it) }
        temporary.setReadable(true, false)
        temporary.setExecutable(true, false)
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        temporary.delete()
    }
}
