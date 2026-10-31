package com.yugahashimoto.andcode.runtime.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/** One `cloudflared` binary the app can download and verify. */
data class CloudflaredRelease(
    val version: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
)

/**
 * Resolves Cloudflare's newest `cloudflared` release from GitHub, pinned to the SHA-256 digest
 * GitHub reports for the asset, the same trust model [AntigravityReleaseClient] uses. A release
 * without a digest is refused rather than run unverified.
 */
class CloudflaredReleaseClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val endpoint: HttpUrl = OFFICIAL_RELEASE_ENDPOINT.toHttpUrl(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun latest(abi: String): CloudflaredRelease =
        withContext(Dispatchers.IO) {
            val assetName = requireNotNull(ASSET_BY_ABI[abi]) { "cloudflared is not published for $abi" }
            val request =
                Request.Builder().url(endpoint).header("Accept", GITHUB_ACCEPT).header("User-Agent", USER_AGENT).get().build()
            val release =
                httpClient.newCall(request).execute().use { response ->
                    require(response.isSuccessful) { "cloudflared release check failed with HTTP ${response.code}" }
                    json.decodeFromString<ReleaseDto>(requireNotNull(response.body).string())
                }
            val asset = requireNotNull(release.assets.firstOrNull { it.name == assetName }) { "cloudflared release has no $assetName" }
            val digest = asset.digest
            require(digest != null && SHA256_DIGEST.matches(digest)) { "cloudflared release asset has no valid SHA-256 digest" }
            val url = asset.downloadUrl.toHttpUrl()
            require(url.isHttps && asset.size > 0) { "cloudflared release asset is not a valid HTTPS download" }
            CloudflaredRelease(release.tagName, url.toString(), digest.removePrefix("sha256:"), asset.size)
        }

    @Serializable
    private data class ReleaseDto(
        @SerialName("tag_name") val tagName: String,
        @SerialName("assets") val assets: List<AssetDto> = emptyList(),
    )

    @Serializable
    private data class AssetDto(
        @SerialName("name") val name: String,
        @SerialName("size") val size: Long,
        @SerialName("browser_download_url") val downloadUrl: String,
        @SerialName("digest") val digest: String? = null,
    )

    companion object {
        const val OFFICIAL_RELEASE_ENDPOINT = "https://api.github.com/repos/cloudflare/cloudflared/releases/latest"
        private const val GITHUB_ACCEPT = "application/vnd.github+json"
        private const val USER_AGENT = "AndCode"
        private val SHA256_DIGEST = Regex("^sha256:[a-f0-9]{64}$")

        /** Static Linux builds, which run in the guest whatever its libc. */
        internal val ASSET_BY_ABI =
            mapOf(
                "arm64-v8a" to "cloudflared-linux-arm64",
                "armeabi-v7a" to "cloudflared-linux-arm",
                "x86_64" to "cloudflared-linux-amd64",
                "x86" to "cloudflared-linux-386",
            )
    }
}

/** Puts a verified `cloudflared` at `/usr/local/bin/cloudflared` in the guest, once. */
class CloudflaredInstaller(
    private val runtimeDirectory: File,
    private val rootfs: () -> File?,
    private val abi: String,
    private val releases: CloudflaredReleaseClient = CloudflaredReleaseClient(),
    private val downloader: VerifiedRuntimeDownloader = VerifiedRuntimeDownloader(),
) {
    fun installed(): Boolean = rootfs()?.let { File(it, BINARY).canExecute() } == true

    /** Installs it when missing; throws with a message for the user when it cannot. */
    suspend fun ensureInstalled() {
        if (installed()) return
        val guest = requireNotNull(rootfs()) { "the local runtime is not installed" }
        val release = releases.latest(abi)
        val cache = File(runtimeDirectory, "cache").apply { mkdirs() }
        val download = File(cache, "cloudflared-${release.version}")
        downloader.download(release.url, download, release.sha256, release.sizeBytes)
        withContext(Dispatchers.IO) {
            val destination = File(guest, BINARY)
            destination.parentFile?.mkdirs()
            val candidate = File(destination.parentFile, "cloudflared.new")
            download.copyTo(candidate, overwrite = true)
            require(candidate.setExecutable(true, false)) { "could not make cloudflared executable" }
            require(candidate.renameTo(destination)) { "could not install cloudflared" }
            download.delete()
        }
    }

    companion object {
        const val BINARY = "usr/local/bin/cloudflared"
    }
}
