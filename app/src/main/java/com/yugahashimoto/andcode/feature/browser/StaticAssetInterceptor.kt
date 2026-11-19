package com.yugahashimoto.andcode.feature.browser

import android.net.Uri
import android.webkit.WebResourceResponse
import com.yugahashimoto.andcode.runtime.local.GuestFiles
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

/** Virtual host used to serve a static site from the workspace without opening a port. */
const val STATIC_PREVIEW_HOST = "andcode.preview"

/** Builds the virtual URL a static preview loads inside the WebView. */
fun staticPreviewUrl(entry: String): String {
    val encoded =
        entry
            .split('/')
            .filter(String::isNotBlank)
            .joinToString("/") { segment -> Uri.encode(segment) }
    return "https://$STATIC_PREVIEW_HOST/$encoded"
}

/**
 * Serves static preview files to the WebView and lets every other host reach the network.
 *
 * The site is the workspace, which the guest writes, so files are read through [GuestFiles]: a
 * link anywhere on the way is refused rather than followed, and a guest that links `index.html` to
 * one of this app's own files gets a 404 instead of that file in the page.
 */
class StaticAssetInterceptor(
    private val rootDir: File,
    entry: String = "index.html",
) {
    private val resolver = StaticAssetResolver(rootDir, entry)

    /** The site's file for [uri] on the virtual host, a 404 when there is none; null for every other host. */
    fun intercept(uri: Uri): WebResourceResponse? {
        val ours = uri.scheme.equals("https", ignoreCase = true) && uri.host.equals(STATIC_PREVIEW_HOST, ignoreCase = true)
        return if (ours) serve(uri.path.orEmpty()) else null
    }

    private fun serve(path: String): WebResourceResponse {
        val asset = resolver.resolve(path) ?: return notFound()
        val bytes =
            try {
                GuestFiles.readBytes(rootDir, asset.relativePath, MAX_ASSET_BYTES)
            } catch (error: IOException) {
                null
            } ?: return notFound()
        return WebResourceResponse(
            asset.mimeType,
            "UTF-8",
            200,
            "OK",
            mapOf("Cache-Control" to "no-store", "Pragma" to "no-cache"),
            ByteArrayInputStream(bytes),
        )
    }

    private fun notFound(): WebResourceResponse =
        WebResourceResponse(
            "text/plain",
            "UTF-8",
            404,
            "Not Found",
            emptyMap(),
            ByteArrayInputStream(ByteArray(0)),
        )

    private companion object {
        /** Far more than any page's asset; a bigger file is not read into memory to serve. */
        const val MAX_ASSET_BYTES = 64L * 1024 * 1024
    }
}

/**
 * Pure path resolution, kept apart from WebView types so it can be unit-tested on the JVM.
 *
 * A request path names a file under [rootDir] by plain names only: one that climbs out with `..` is
 * refused, and each candidate must be a regular file reached without passing a link ([GuestFiles]).
 */
internal class StaticAssetResolver(
    private val rootDir: File,
    entry: String = "index.html",
) {
    /** The folder the entry page is in, relative to [rootDir], for root-relative assets of a nested site. */
    private val entryDir: List<String> = namesOf(entry.substringBeforeLast('/', missingDelimiterValue = "")).orEmpty()

    fun resolve(path: String): ResolvedAsset? {
        if (!rootDir.isDirectory) return null
        val names = namesOf(path) ?: return null
        val relative =
            resolveUnder(emptyList(), names)
                ?: entryDir.takeIf { it.isNotEmpty() }?.let { resolveUnder(it, names) }
                ?: return null
        return ResolvedAsset(relative, mimeTypeFor(relative.substringAfterLast('/')))
    }

    private fun resolveUnder(
        base: List<String>,
        names: List<String>,
    ): String? {
        val direct = (base + names.ifEmpty { listOf(INDEX) }).joinToString("/")
        val folderIndex = names.takeIf { it.isNotEmpty() }?.let { (base + it + INDEX).joinToString("/") }
        val baseIndex = (base + INDEX).joinToString("/")
        return when {
            isFile(direct) -> direct
            folderIndex != null && isFile(folderIndex) -> folderIndex
            // Single-page apps route client-side: an extensionless path that is not a file falls back
            // to the entry folder's index instead of 404-ing.
            names.lastOrNull().orEmpty().substringAfterLast('.', "").isEmpty() && isFile(baseIndex) -> baseIndex
            else -> null
        }
    }

    private fun isFile(relativePath: String): Boolean = GuestFiles.isRegularFile(rootDir, relativePath)

    private companion object {
        const val INDEX = "index.html"

        /** The plain names in [path], or null when it climbs out of the root. */
        fun namesOf(path: String): List<String>? {
            val names = path.split('/').filter { it.isNotEmpty() && it != "." }
            return names.takeIf { list -> list.none { it == ".." || '\u0000' in it } }
        }
    }
}

internal data class ResolvedAsset(
    /** The file's path under the site's root, as [GuestFiles] takes it. */
    val relativePath: String,
    val mimeType: String,
)

private fun mimeTypeFor(name: String): String = MIME_TYPES[name.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"

private val MIME_TYPES =
    mapOf(
        "html" to "text/html",
        "htm" to "text/html",
        "css" to "text/css",
        "js" to "application/javascript",
        "mjs" to "application/javascript",
        "cjs" to "application/javascript",
        "json" to "application/json",
        "svg" to "image/svg+xml",
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "avif" to "image/avif",
        "ico" to "image/x-icon",
        "woff" to "font/woff",
        "woff2" to "font/woff2",
        "ttf" to "font/ttf",
        "otf" to "font/otf",
        "wasm" to "application/wasm",
        "xml" to "application/xml",
        "txt" to "text/plain",
        "map" to "application/json",
    )
