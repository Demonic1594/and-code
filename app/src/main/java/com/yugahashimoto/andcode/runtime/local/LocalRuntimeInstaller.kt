package com.yugahashimoto.andcode.runtime.local

import android.content.Context
import android.system.Os
import android.util.Log
import androidx.annotation.StringRes
import com.yugahashimoto.andcode.BuildConfig
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.core.locale.AppLanguage
import com.yugahashimoto.andcode.runtime.LocalAgent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

class LocalRuntimeInstaller(
    private val context: Context,
    private val runtimeDirectory: File,
    private val abi: String,
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val manifestReader: LocalRuntimeManifestReader = LocalRuntimeManifestReader(context),
    private val downloader: VerifiedRuntimeDownloader = VerifiedRuntimeDownloader(httpClient),
    private val accessCoordinator: LocalRuntimeAccessCoordinator = LocalRuntimeAccessCoordinator(),
) {
    /**
     * Where step text is read: the language picked now. The steps reach the runtime's notification
     * and screen, and [context] is the Application's, which keeps the one the process started in.
     */
    private val text: Context get() = AppLanguage.current(context)

    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }

    data class InstalledRuntime(
        val metadata: LocalRuntimeMetadata,
        val commandSuite: EmbeddedCommandSuite.Paths,
        val rootfs: File,
        /** Null when the sandbox was provisioned without the OpenCode agent. */
        val openCode: File?,
        /** Debian Bookworm rootfs for the glibc-linked official Antigravity binary. */
        val antigravityRootfs: File? = null,
    )

    /**
     * Provisions the shared Alpine sandbox and the requested [agents].
     *
     * Agents already recorded in the current install are carried over, so adding one never silently
     * removes another. The OpenCode binary — by far the largest download — is fetched only when
     * OpenCode is actually among the resulting agents.
     */
    suspend fun install(
        agents: Set<LocalAgent> = setOf(LocalAgent.OPEN_CODE),
        installFullDevelopmentTools: Boolean = false,
        /**
         * Progress, the step to show, and which agent that step belongs to - null for the shared
         * Alpine environment every agent runs in. One install provisions the whole selection, so
         * without the third argument the setup guide attributed every step to OpenCode and showed
         * "Installing Claude Code" underneath the OpenCode heading.
         */
        onProgress: (Float?, String, LocalAgent?) -> Unit = { _, _, _ -> },
    ): InstalledRuntime =
        withContext(Dispatchers.IO) {
            // The shared environment's own steps, which belong to no single agent.
            val onShared: (Float?, String) -> Unit = { progress, step -> onProgress(progress, step, null) }
            val onClaude: (Float?, String) -> Unit = { progress, step -> onProgress(progress, step, LocalAgent.CLAUDE_CODE) }
            val onAntigravity: (Float?, String) -> Unit = { progress, step -> onProgress(progress, step, LocalAgent.ANTIGRAVITY) }
            val onCodex: (Float?, String) -> Unit = { progress, step -> onProgress(progress, step, LocalAgent.CODEX) }
            runtimeDirectory.mkdirs()
            onShared(0.02f, text.getString(R.string.install_step_preparing_command_env))
            val existingMetadata = installedMetadata()
            val requestedAgents =
                agents + (
                    existingMetadata?.let {
                            existing ->
                        LocalAgent.entries.filter(existing::has)
                    } ?: emptyList()
                )
            // Runtimes created before this option existed already contain the full toolchain, and
            // adding another agent must not silently remove it by rebuilding a smaller rootfs.
            val includeFullDevelopmentTools =
                installFullDevelopmentTools || existingMetadata?.fullDevelopmentToolsInstalled == true
            require(requestedAgents.isNotEmpty()) { "At least one agent must be selected" }
            val commandSuite = EmbeddedCommandSuite(context, runtimeDirectory, abi).ensureInstalled()
            val manifest = manifestReader.read()
            val architecture = manifest.architecture(abi)
            val cache = File(runtimeDirectory, "cache").apply { mkdirs() }
            val staging = File(runtimeDirectory, "environment.staging")
            val active = File(runtimeDirectory, "environment")
            val rollback = File(runtimeDirectory, "environment.rollback")
            accessCoordinator.write {
                recoverInterruptedRuntimeEnvironment(
                    active = active,
                    rollback = rollback,
                    topLevelMetadata = File(runtimeDirectory, METADATA_FILE),
                )
            }
            // Never File.deleteRecursively: a staging left by a failed install holds guest files,
            // links among them, and that walks into a linked folder.
            GuestFiles.deleteTree(staging)
            staging.mkdirs()

            try {
                val alpineArchive = File(cache, "alpine-${manifest.alpineVersion}-$abi.tar.gz")
                download(
                    architecture.alpineUrl,
                    alpineArchive,
                    architecture.alpineSha256,
                    0.05f,
                    0.22f,
                    R.string.install_step_downloading_alpine,
                    onShared,
                )
                val withOpenCode = LocalAgent.OPEN_CODE in requestedAgents
                val openCodeArchive =
                    File(cache, "opencode-${manifest.openCodeVersion}-$abi.tar.gz").takeIf { withOpenCode }?.also { archive ->
                        download(
                            architecture.openCodeUrl,
                            archive,
                            architecture.openCodeSha256,
                            0.24f,
                            0.72f,
                            R.string.install_step_downloading_opencode,
                            onShared,
                        )
                    }

                val rootfs = File(staging, "rootfs").apply { mkdirs() }
                onShared(0.75f, text.getString(R.string.install_step_extracting_linux_env))
                alpineArchive.inputStream().use { RuntimeArchive.extractTarGz(it, rootfs) }

                val openCodeBinary =
                    openCodeArchive?.let { archive ->
                        onShared(0.85f, text.getString(R.string.install_step_extracting_opencode))
                        extractOpenCode(archive, staging, rootfs)
                    }
                configureRootfs(rootfs, commandSuite)
                val antigravityRootfs =
                    if (LocalAgent.ANTIGRAVITY in requestedAgents) {
                        onAntigravity(0.90f, text.getString(R.string.install_step_preparing_antigravity_rootfs))
                        DebianRootfsInstaller(runtimeDirectory, abi, downloader, httpClient, commandSuite).installInto(
                            File(staging, "antigravity-rootfs"),
                            installFullDevelopmentTools = includeFullDevelopmentTools,
                        ) { progress ->
                            onAntigravity(0.90f + progress * 0.03f, text.getString(R.string.install_step_preparing_antigravity_rootfs))
                        }
                    } else {
                        null
                    }
                if (antigravityRootfs != null) {
                    ensureAndCodeAgentContext(antigravityRootfs, context)
                    copyCaCertificates(rootfs, antigravityRootfs)
                    // Keep the Android-vision tool surface added for Claude/OpenCode available
                    // to agy's Debian tool runner as well. The scripts still fail closed when adb
                    // or Pillow is not installed; they are never silently replaced by a fork.
                    installAndroidHelperScripts(antigravityRootfs)
                    provisionBrowserMcp(antigravityRootfs)
                    provisionScheduleMcp(antigravityRootfs)
                }
                // Credentials and agent config live under /root inside the rootfs. Activation swaps
                // the whole environment directory, so without this the user is signed out of every
                // agent whenever another one is added or the runtime is reinstalled.
                carryOverHomeDirectory(File(active, "rootfs"), rootfs)
                ensureAndCodeAgentContext(rootfs, context)
                onShared(
                    0.91f,
                    text.getString(
                        if (includeFullDevelopmentTools) {
                            R.string.install_step_installing_dev_tools
                        } else {
                            R.string.install_step_installing_runtime_tools
                        },
                    ),
                )
                installPackages(
                    rootfs = rootfs,
                    suite = commandSuite,
                    packages =
                        if (includeFullDevelopmentTools) {
                            REQUIRED_RUNTIME_PACKAGES + OPTIONAL_DEVELOPMENT_PACKAGES
                        } else {
                            REQUIRED_RUNTIME_PACKAGES
                        },
                )
                if (LocalAgent.CLAUDE_CODE in requestedAgents) {
                    onClaude(0.93f, text.getString(R.string.install_step_installing_claude_code))
                    ClaudeCodeInstaller.installInto(rootfs, commandSuite, runtimeDirectory)
                    provisionClaudePermissionHook(rootfs)
                }
                if (LocalAgent.CODEX in requestedAgents) {
                    onCodex(0.935f, text.getString(R.string.install_step_downloading_codex))
                    // Into the staging rootfs like every other agent here, so it is swapped in with the
                    // environment and is not lost when a later install rebuilds the sandbox.
                    CodexInstaller.install(rootfs, abi, runtimeDirectory, accessCoordinator, httpClient)
                }
                if (LocalAgent.ANTIGRAVITY in requestedAgents) {
                    onAntigravity(0.94f, text.getString(R.string.install_step_downloading_antigravity))
                    val antigravityRelease = resolveAntigravityRelease(abi, httpClient)
                    AntigravityInstaller(runtimeDirectory, downloader).installInto(
                        antigravityRootfs ?: rootfs,
                        { progress ->
                            onAntigravity(0.94f + progress * 0.04f, text.getString(R.string.install_step_installing_antigravity))
                        },
                        antigravityRelease,
                    )
                }

                val metadata =
                    LocalRuntimeMetadata(
                        version = if (withOpenCode) manifest.openCodeVersion else "",
                        port = manifest.port + BuildConfig.LOCAL_RUNTIME_PORT_OFFSET,
                        installedAt = System.currentTimeMillis(),
                        runtimeVersion = manifest.runtimeVersion,
                        abi = abi,
                        components = requestedAgents.map(LocalAgent::id).toSet(),
                        fullDevelopmentToolsInstalled = includeFullDevelopmentTools,
                        fullDebianDevelopmentToolsInstalled = includeFullDevelopmentTools && antigravityRootfs != null,
                    )
                File(staging, METADATA_FILE).writeText(json.encodeToString(metadata))
                onShared(0.96f, text.getString(R.string.install_step_activating_runtime))
                accessCoordinator.write {
                    activateRuntimeEnvironment(
                        active = active,
                        staging = staging,
                        rollback = rollback,
                        finalizeActivation = { activated ->
                            replaceFileAtomically(
                                source = File(activated, METADATA_FILE),
                                destination = File(runtimeDirectory, METADATA_FILE),
                            )
                        },
                    )
                }
                onShared(1f, text.getString(R.string.install_step_done))
                InstalledRuntime(
                    metadata,
                    commandSuite,
                    File(active, "rootfs"),
                    openCodeBinary?.let { File(active, "rootfs/usr/local/bin/opencode") },
                    File(active, "antigravity-rootfs").takeIf { LocalAgent.ANTIGRAVITY in requestedAgents && it.isDirectory },
                )
            } catch (error: Throwable) {
                GuestFiles.deleteTree(staging)
                throw error
            }
        }

    fun recoverInterruptedActivation(): Boolean =
        accessCoordinator.write {
            recoverInterruptedRuntimeEnvironment(
                active = File(runtimeDirectory, "environment"),
                rollback = File(runtimeDirectory, "environment.rollback"),
                topLevelMetadata = File(runtimeDirectory, METADATA_FILE),
            )
        }

    /**
     * The provisioned sandbox, or null when no usable Linux environment exists yet.
     *
     * The OpenCode binary is no longer part of the liveness check: a Claude Code-only sandbox is a
     * complete, usable runtime and callers that need OpenCode check [InstalledRuntime.openCode].
     */
    fun installedRuntime(): InstalledRuntime? =
        accessCoordinator.write {
            val active = File(runtimeDirectory, "environment")
            normalizeRuntimeSymlinks(active)
            val metadataFile = File(runtimeDirectory, METADATA_FILE)
            val rootfs = File(active, "rootfs")
            if (!metadataFile.isFile || !rootfs.isDirectory) return@write null
            val metadata =
                runCatching {
                    json.decodeFromString<LocalRuntimeMetadata>(metadataFile.readText())
                }.getOrNull() ?: return@write null
            val commandSuite =
                runCatching {
                    EmbeddedCommandSuite(context, runtimeDirectory, abi).ensureInstalled()
                }.getOrNull() ?: return@write null
            InstalledRuntime(
                metadata,
                commandSuite,
                rootfs,
                File(rootfs, "usr/local/bin/opencode").takeIf { it.isFile },
                File(active, "antigravity-rootfs").takeIf { metadata.has(LocalAgent.ANTIGRAVITY) && it.isDirectory },
            )
        }?.also { installed ->
            listOfNotNull(installed.rootfs, installed.antigravityRootfs).forEach { rootfs ->
                ensureAndCodeAgentContext(rootfs, context)
                refreshHelperScriptsOnce(rootfs)
            }
        }

    /**
     * Copies this app version's guest scripts (the browser and schedule MCP servers among them) into
     * [rootfs], once per process. Every local agent passes through [installedRuntime], whereas the full
     * re-seed runs only when OpenCode's server starts: without this, a phone running Claude Code or
     * Antigravity alone would keep the scripts of the version it was installed with after an update,
     * and a browser tool that writes where this app no longer looks. An update starts a new process.
     * Each script is replaced by a rename, so one already running keeps its own copy. Callers that come
     * while the copy runs wait for it, and a copy that fails is tried again by the next one.
     */
    private fun refreshHelperScriptsOnce(rootfs: File) {
        val key = rootfs.absolutePath
        if (key in refreshedScriptRootfs) return
        synchronized(refreshedScriptRootfs) {
            if (key !in refreshedScriptRootfs && runCatching { installAndroidHelperScripts(rootfs) }.isSuccess) {
                refreshedScriptRootfs += key
            }
        }
    }

    /** Metadata of the active install, without requiring the command suite to be extractable. */
    fun installedMetadata(): LocalRuntimeMetadata? =
        accessCoordinator.read {
            val metadataFile = File(runtimeDirectory, METADATA_FILE)
            if (!metadataFile.isFile) return@read null
            runCatching { json.decodeFromString<LocalRuntimeMetadata>(metadataFile.readText()) }.getOrNull()
        }

    /** Records [agent] as provisioned, so a later reinstall keeps it. */
    fun recordAgent(agent: LocalAgent) {
        accessCoordinator.write {
            val metadataFile = File(runtimeDirectory, METADATA_FILE)
            val metadata =
                runCatching { json.decodeFromString<LocalRuntimeMetadata>(metadataFile.readText()) }.getOrNull()
                    ?: return@write
            metadataFile.writeText(json.encodeToString(metadata.with(agent)))
        }
    }

    /** Installs the optional toolchain into the active sandbox without rebuilding the runtime. */
    suspend fun installFullDevelopmentTools(onProgress: (Float?, String, LocalAgent?) -> Unit = { _, _, _ -> }): LocalRuntimeMetadata =
        withContext(Dispatchers.IO) {
            accessCoordinator.write {
                val active = File(runtimeDirectory, "environment")
                val rootfs = File(active, "rootfs")
                val metadataFile = File(runtimeDirectory, METADATA_FILE)
                if (!rootfs.isDirectory || !metadataFile.isFile) throw InstallerErrors.Environment.missing()
                val metadata =
                    json.decodeFromString<LocalRuntimeMetadata>(metadataFile.readText())
                if (metadata.hasFullDevelopmentTools()) return@write metadata

                val suite = EmbeddedCommandSuite(context, runtimeDirectory, abi).ensureInstalled()
                onProgress(null, text.getString(R.string.install_step_installing_dev_tools), null)
                if (!metadata.fullDevelopmentToolsInstalled) installPackages(rootfs, suite, OPTIONAL_DEVELOPMENT_PACKAGES)
                if (metadata.has(LocalAgent.ANTIGRAVITY) && !metadata.fullDebianDevelopmentToolsInstalled) {
                    val antigravityRootfs = File(active, "antigravity-rootfs")
                    if (!antigravityRootfs.isDirectory) throw InstallerErrors.Environment.antigravityMissing()
                    DebianRootfsInstaller(runtimeDirectory, abi, downloader, httpClient, suite)
                        .installFullDevelopmentTools(antigravityRootfs)
                }

                val updated =
                    metadata.copy(
                        fullDevelopmentToolsInstalled = true,
                        fullDebianDevelopmentToolsInstalled = metadata.has(LocalAgent.ANTIGRAVITY),
                    )
                val encoded = json.encodeToString(updated)
                File(active, METADATA_FILE).writeText(encoded)
                replaceFileAtomically(File(active, METADATA_FILE), metadataFile)
                onProgress(1f, text.getString(R.string.install_step_done), null)
                updated
            }
        }

    fun bundledOpenCodeVersion(): String = manifestReader.read().openCodeVersion

    /**
     * Brings the active sandbox's Antigravity up to the newest official release.
     *
     * The release is resolved at update time — GitHub's `latest`, with this build's pin only as the
     * fallback when the lookup fails — so users no longer wait for an app release to receive new
     * Antigravity versions. When the guest already runs the resolved release (or something newer,
     * which a pin-degraded lookup can report) the ~90 MB download is skipped. Going through
     * [install] for any of this would provision a whole new environment directory to replace one
     * file; [AntigravityInstaller] swaps `agy` in place instead, leaving the Debian rootfs and
     * every agent's credentials untouched.
     *
     * Not serialised against [install]: that one builds a staging directory and swaps it in, so the
     * worst a concurrent run can do is discard this binary, which the next update reinstates. The
     * controller that owns both actions runs one at a time.
     */
    suspend fun updateAntigravity(onProgress: (Float) -> Unit = {}): String {
        val runtime = installedRuntime() ?: throw InstallerErrors.Environment.missing()
        val rootfs = runtime.antigravityRootfs ?: runtime.rootfs
        val release = resolveAntigravityRelease(abi, httpClient)
        val installed = AntigravityInstaller.installedVersion(rootfs)
        if (!shouldReplaceInstalledAntigravity(installed, release.version)) return installed!!
        return AntigravityInstaller(runtimeDirectory, downloader).installInto(rootfs, onProgress, release)
    }

    private fun extractOpenCode(
        archive: File,
        staging: File,
        rootfs: File,
    ): File {
        val extractDir = File(staging, "opencode-extract").apply { mkdirs() }
        try {
            archive.inputStream().use { RuntimeArchive.extractTarGz(it, extractDir) }
            val sourceBinary =
                extractDir.walkTopDown()
                    .firstOrNull { it.isFile && it.name == "opencode" }
                    ?: throw InstallerErrors.Environment.openCodeBinaryMissing()
            val binary = File(rootfs, "usr/local/bin/opencode")
            binary.parentFile?.mkdirs()
            sourceBinary.copyTo(binary, overwrite = true)
            if (!binary.setExecutable(true, false) && !binary.canExecute()) throw InstallerErrors.Environment.openCodeNotExecutable()
            return binary
        } finally {
            extractDir.deleteRecursively()
        }
    }

    /**
     * Copies the current guest home into the new rootfs. Through [GuestFiles.copyTree], which copies
     * a link as a link: `copyRecursively` read through it, so a link the guest planted in its home
     * pulled the file or folder it named - this app's own preferences, say - into the new guest.
     */
    private fun carryOverHomeDirectory(
        currentRootfs: File,
        stagingRootfs: File,
    ) {
        // A missing home copies nothing. A failure leaves the new environment without the old home
        // (agents then ask to sign in again), so it is logged rather than silently dropped.
        try {
            GuestFiles.copyTree(File(currentRootfs, "root"), File(stagingRootfs, "root"))
        } catch (refused: GuestPathRefusedException) {
            Log.w(LOG_TAG, "Home not carried over, /root is a link: ${refused.message}")
        } catch (failure: IOException) {
            Log.w(LOG_TAG, "Home not carried over", failure)
        }
    }

    /** [label] is read for every progress update, so a language picked mid-download shows at once. */
    private suspend fun download(
        url: String,
        destination: File,
        expectedSha256: String,
        startProgress: Float,
        endProgress: Float,
        @StringRes label: Int,
        onProgress: (Float?, String) -> Unit,
    ) {
        if (destination.isFile) {
            runCatching { RuntimeArchive.verifySha256(destination, expectedSha256) }
                .onSuccess {
                    onProgress(endProgress, text.getString(label))
                    return
                }
            destination.delete()
        }
        downloader.download(
            url = url,
            destination = destination,
            expectedSha256 = expectedSha256,
            onProgress = { fraction ->
                onProgress(
                    fraction?.let {
                        startProgress + (endProgress - startProgress) * it.coerceIn(0f, 1f)
                    },
                    text.getString(label),
                )
            },
        )
        onProgress(endProgress, text.getString(label))
    }

    private fun installPackages(
        rootfs: File,
        suite: EmbeddedCommandSuite.Paths,
        packages: List<String>,
    ) {
        val prootTmp = File(runtimeDirectory, "proot-tmp").apply { mkdirs() }
        val apkCache = File(runtimeDirectory, "cache/apk").apply { mkdirs() }
        File(rootfs, "var/cache/apk").mkdirs()
        val command =
            listOf(
                suite.proot.absolutePath,
                "--kill-on-exit",
                "--link2symlink",
                "-0",
                "-r",
                rootfs.absolutePath,
                "-b",
                "/dev",
                "-b",
                "/proc",
                "-b",
                "/sys",
                "-b",
                "/system",
                "-b",
                "${apkCache.absolutePath}:/var/cache/apk",
                "-w",
                "/root",
                "/bin/sh",
                "-lc",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin " +
                    "/sbin/apk --cache-dir /var/cache/apk add ${packages.joinToString(" ")} && " +
                    "/usr/sbin/update-ca-certificates",
            )
        val installLog =
            File(runtimeDirectory, "logs/tool-install.log").apply {
                parentFile?.mkdirs()
                delete()
            }
        val process =
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(installLog))
                .apply {
                    // The Android environment (CLASSPATH, BOOTCLASSPATH, ANDROID_*...) is not for
                    // the guest; every other launcher here clears it first. The guest login shell
                    // re-exports what it needs.
                    environment().clear()
                    environment().putAll(suite.environment())
                    environment()["PROOT_TMP_DIR"] = prootTmp.absolutePath
                }
                .start()
        val completed = process.waitFor(15, java.util.concurrent.TimeUnit.MINUTES)
        if (!completed) {
            process.destroyForcibly()
            // Reap, and take the guest package manager with us: a wedged apk holding rootfs
            // locks would survive the tracer kill. See [killManagedProcessTrees].
            process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            runCatching { killManagedProcessTrees(runtimeDirectory, process) }
            throw InstallerErrors.Environment.toolsTimedOut()
        }
        if (process.exitValue() != 0) {
            // The hint sits between the headline and the raw log, or a 4000-character tail scrolls
            // it off the screen the error is read on.
            throw InstallerErrors.Environment.packagesFailed(readTail(installLog, 4000))
        }
    }

    /** Reads the last [characters] of [file] without allocating the whole file for them. */
    private fun readTail(
        file: File,
        characters: Int,
    ): String =
        runCatching {
            java.io.RandomAccessFile(file, "r").use { random ->
                val window = (characters.toLong() * 4).coerceAtMost(random.length())
                random.seek(random.length() - window)
                val bytes = ByteArray(window.toInt())
                random.readFully(bytes)
                bytes.decodeToString().takeLast(characters)
            }
        }.getOrDefault("")

    private fun configureRootfs(
        rootfs: File,
        suite: EmbeddedCommandSuite.Paths,
    ) {
        File(rootfs, "root").mkdirs()
        File(rootfs, "tmp").apply {
            mkdirs()
            setWritable(true, false)
            setExecutable(true, false)
        }
        File(rootfs, "workspace").mkdirs()
        File(rootfs, "etc/resolv.conf").apply {
            parentFile?.mkdirs()
            writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        }
        File(rootfs, "etc/hosts").writeText("127.0.0.1 localhost\n::1 localhost\n")
        File(rootfs, "etc/profile.d/android-code.sh").apply {
            parentFile?.mkdirs()
            writeText(
                "export HOME=/root\n" +
                    "export TMPDIR=/tmp\n" +
                    "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/system/bin:/system/xbin\n" +
                    "export JAVA_HOME=/usr/lib/jvm/java-17-openjdk\n" +
                    "export OPENCODE_CONFIG_DIR=/root/.config/opencode\n",
            )
        }
        File(rootfs, "root/.config/opencode").mkdirs()
        File(rootfs, "root/.local/share/opencode").mkdirs()
        // The compact Alpine archive intentionally omits a few SONAME symlinks. apk loads
        // libapk.so.3 by SONAME, so restore the link before installing agent dependencies.
        val libApk = File(rootfs, "usr/lib/libapk.so.3")
        if (!libApk.exists() && File(rootfs, "usr/lib/libapk.so.3.0.0").isFile) {
            Os.symlink("libapk.so.3.0.0", libApk.absolutePath)
        }
        installAndroidHelperScripts(rootfs)
        provisionBrowserMcp(rootfs)
        provisionScheduleMcp(rootfs)
        if (!suite.proot.isFile) throw InstallerErrors.Environment.prootMissing()
    }

    /**
     * Re-seeds the guest MCP servers (browser + schedule) and agent registrations on runtimes that
     * were installed before the provisioning existed. Idempotent and safe to call on every start.
     */
    fun provisionGuestCapabilitiesForExistingInstall() {
        val active = File(runtimeDirectory, "environment")
        listOf(File(active, "rootfs"), File(active, "antigravity-rootfs"))
            .filter(File::isDirectory)
            .forEach { rootfs ->
                // Each step on its own: one the guest blocked (a linked folder GuestFiles refuses)
                // must not keep the others, or the other rootfs, from being provisioned.
                runCatching { installAndroidHelperScripts(rootfs) }
                runCatching { provisionBrowserMcp(rootfs) }
                runCatching { provisionScheduleMcp(rootfs) }
                provisionClaudePermissionHook(rootfs)
            }
    }

    /** Installs the Claude Code PermissionRequest hook into an Alpine rootfs. */
    fun provisionClaudePermissionHook(rootfs: File = File(runtimeDirectory, "environment/rootfs")) {
        if (!rootfs.isDirectory) return
        runCatching {
            val script =
                context.assets.open("scripts/and-code-claude-permission-hook.sh").bufferedReader().use { it.readText() }
            ClaudePermissionHooks.installInto(rootfs, script)
        }
    }

    /**
     * Registers the guest-browser MCP server with every agent (OpenCode, Claude Code,
     * Antigravity) so they all expose the same browser_* tools. User-added servers are kept.
     */
    private fun provisionBrowserMcp(rootfs: File) {
        mergeGuestJsonConfig(rootfs, "root/.config/opencode/opencode.json") { root ->
            val mcp = root.optJSONObject("mcp") ?: JSONObject()
            mcp.put(BROWSER_MCP_NAME, browserMcpEntry("opencode"))
            root.put("mcp", mcp)
        }
        mergeGuestJsonConfig(rootfs, "root/.claude.json") { root ->
            val servers = root.optJSONObject("mcpServers") ?: JSONObject()
            servers.put(BROWSER_MCP_NAME, browserMcpEntry("claude"))
            root.put("mcpServers", servers)
        }
        mergeGuestJsonConfig(rootfs, "root/.gemini/config/mcp_config.json") { root ->
            val servers = root.optJSONObject("mcpServers") ?: JSONObject()
            servers.put(BROWSER_MCP_NAME, browserMcpEntry("antigravity"))
            root.put("mcpServers", servers)
        }
    }

    private fun browserMcpEntry(agent: String): JSONObject =
        when (agent) {
            "claude" ->
                JSONObject()
                    .put("type", "stdio")
                    .put("command", BROWSER_MCP_BIN)
                    .put("args", JSONArray())
            "antigravity" -> JSONObject().put("command", BROWSER_MCP_BIN)
            else ->
                JSONObject()
                    .put("type", "local")
                    .put("command", JSONArray(listOf(BROWSER_MCP_BIN)))
                    .put("enabled", true)
                    .put("timeout", BROWSER_MCP_TIMEOUT_MILLIS)
        }

    /**
     * Registers the guest-schedule MCP server with every agent (OpenCode, Claude Code, Antigravity)
     * so they all expose the same schedule_* tools. User-added servers are kept.
     */
    private fun provisionScheduleMcp(rootfs: File) {
        mergeGuestJsonConfig(rootfs, "root/.config/opencode/opencode.json") { root ->
            val mcp = root.optJSONObject("mcp") ?: JSONObject()
            mcp.put(SCHEDULE_MCP_NAME, scheduleMcpEntry("opencode"))
            root.put("mcp", mcp)
        }
        mergeGuestJsonConfig(rootfs, "root/.claude.json") { root ->
            val servers = root.optJSONObject("mcpServers") ?: JSONObject()
            servers.put(SCHEDULE_MCP_NAME, scheduleMcpEntry("claude"))
            root.put("mcpServers", servers)
        }
        mergeGuestJsonConfig(rootfs, "root/.gemini/config/mcp_config.json") { root ->
            val servers = root.optJSONObject("mcpServers") ?: JSONObject()
            servers.put(SCHEDULE_MCP_NAME, scheduleMcpEntry("antigravity"))
            root.put("mcpServers", servers)
        }
    }

    private fun scheduleMcpEntry(agent: String): JSONObject =
        when (agent) {
            "claude" ->
                JSONObject()
                    .put("type", "stdio")
                    .put("command", SCHEDULE_MCP_BIN)
                    .put("args", JSONArray())
            "antigravity" -> JSONObject().put("command", SCHEDULE_MCP_BIN)
            else ->
                JSONObject()
                    .put("type", "local")
                    .put("command", JSONArray(listOf(SCHEDULE_MCP_BIN)))
                    .put("enabled", true)
                    .put("timeout", SCHEDULE_MCP_TIMEOUT_MILLIS)
        }

    private fun installAndroidHelperScripts(rootfs: File) {
        GuestFiles.delete(rootfs, "usr/local/bin/android-ui")
        listOf(
            "android-vision.sh" to "android-vision",
            "android-screenshot.sh" to "android-screenshot",
            "android-instrument.sh" to "android-instrument",
            "android-app.sh" to "android-app",
            "android-build-setup.sh" to "android-build-setup",
            "andcode-browser-mcp.py" to "andcode-browser-mcp.py",
            "andcode-schedule-mcp.py" to "andcode-schedule-mcp.py",
            "andcode-run.py" to "andcode-run",
        ).forEach {
                (assetName, scriptName) ->
            context.assets.open("scripts/$assetName").use { input -> replaceGuestScript(rootfs, "usr/local/bin/$scriptName", input) }
        }
    }

    private fun copyCaCertificates(
        alpineRootfs: File,
        debianRootfs: File,
    ) {
        val bundle = runCatching { GuestFiles.readBytes(alpineRootfs, CA_BUNDLE_PATH) }.getOrNull() ?: return
        GuestFiles.write(debianRootfs, CA_BUNDLE_PATH, bundle)
    }

    internal companion object {
        private const val LOG_TAG = "LocalRuntimeInstaller"

        /** The rootfs folders whose guest scripts this process has refreshed (see refreshHelperScriptsOnce). */
        private val refreshedScriptRootfs: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        private const val METADATA_FILE = "metadata.json"
        private const val CA_BUNDLE_PATH = "etc/ssl/certs/ca-certificates.crt"
        private const val BROWSER_MCP_NAME = "and-code-browser"
        private const val BROWSER_MCP_BIN = "/usr/local/bin/andcode-browser-mcp.py"

        // Above browser_handoff's own 110 s deadline (it blocks until the user finishes signing
        // in), so the server's timed-out reply reaches the agent before the client kills the call.
        private const val BROWSER_MCP_TIMEOUT_MILLIS = 120000
        private const val SCHEDULE_MCP_NAME = "and-code-schedule"
        private const val SCHEDULE_MCP_BIN = "/usr/local/bin/andcode-schedule-mcp.py"
        private const val SCHEDULE_MCP_TIMEOUT_MILLIS = 120000

        /** Required by OpenCode and AndCode's built-in Git, MCP, and Android-device features. */
        val REQUIRED_RUNTIME_PACKAGES =
            listOf(
                "bash",
                "git",
                "curl",
                "wget",
                "jq",
                "openssh-client",
                "ripgrep",
                "ca-certificates",
                "libstdc++",
                "android-tools",
                "python3",
                "py3-pillow",
            )

        /** Project-specific compilers, language SDKs, editors, and convenience utilities. */
        val OPTIONAL_DEVELOPMENT_PACKAGES =
            listOf(
                "tree",
                "file",
                "less",
                "nano",
                "vim",
                "github-cli",
                "openjdk17",
                "gradle",
                "py3-pip",
                "nodejs",
                "npm",
                "make",
                "cmake",
                "gcc",
                "g++",
                "musl-dev",
                "pkgconf",
                "patch",
                "zip",
                "unzip",
                "sqlite",
                "go",
                "gcompat",
                "util-linux",
            )
    }
}

/**
 * Claude Code's `~/.claude.json` keeps a history per project and grows with use, so the cap is far
 * past GuestFiles' default: a heavy user's file must still get AndCode's MCP servers.
 */
private const val MAX_AGENT_CONFIG_BYTES = 16L * 1024 * 1024

/**
 * Applies [mutate] to the JSON object in [relativePath] under [rootfs] and writes it back when that
 * changed it; a missing or malformed file starts from an empty object.
 *
 * Through [GuestFiles]: the guest can make the file, or a folder above it, a link to one of this
 * app's own files, which used to be read in here and written back with AndCode's entries merged in.
 * Such a file is skipped instead, left exactly as it is, and false returned, so one agent's linked
 * config does not keep the others from being registered.
 */
internal fun mergeGuestJsonConfig(
    rootfs: File,
    relativePath: String,
    mutate: (JSONObject) -> Unit,
): Boolean {
    try {
        val root =
            GuestFiles.readText(rootfs, relativePath, MAX_AGENT_CONFIG_BYTES)?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?: JSONObject()
        val before = root.toString()
        mutate(root)
        if (root.toString() != before) GuestFiles.write(rootfs, relativePath, root.toString(2) + "\n")
        return true
    } catch (refused: GuestPathRefusedException) {
        return false
    }
}
