package com.jarves.mh.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.system.Os
import com.jarves.mh.BuildConfig
import com.jarves.mh.R
import com.jarves.mh.ui.AppStrings
import com.jarves.mh.ui.localizedLabel
import com.jarves.mh.ui.localizedTitle
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext
import com.jarves.mh.model.DevStack
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import org.json.JSONObject

data class InstalledRuntime(
    val proot: File,
    val rootfs: File,
)

data class RuntimeInstallProgress(
    val message: String,
    val fraction: Float,
    val downloadedBytes: Long? = null,
    val totalBytes: Long? = null,
    val terminalLine: String? = null,
    val indeterminate: Boolean = false,
    val event: RuntimeInstallEvent = RuntimeInstallEvent.STAGE,
)

data class AgentUpdateInfo(
    val installedVersion: String,
    val latestVersion: String,
)

internal fun isSupportedDshVersion(version: String): Boolean =
    version == RuntimeInstaller.DSH_VERSION

enum class RuntimeInstallEvent { STAGE, COMMAND, OUTPUT, DOWNLOAD, COMMAND_COMPLETED, COMPLETED }

private data class RuntimeBundle(
    val label: String,
    val fileName: String,
    val sha256: String,
    val compressedBytes: Long,
)

class RuntimeInstaller(private val context: Context) {
    private val runtimeDir = File(context.filesDir, "runtime")
    private val rootfs = File(runtimeDir, "ubuntu")
    private val downloads = File(context.cacheDir, "runtime-downloads")
    private val coreReadyMarker = File(rootfs, ".pocket-runtime-ready")
    private val claudeMarker = File(rootfs, ".pocket-claude-version")
    // Read only for migration from Core bundles that embedded Claude Code.
    private val bundledClaudeMarker = File(rootfs, ".pocket-bundled-claude-version")
    private val rootfsMarker = File(rootfs, ".pocket-rootfs-version")
    private val languageToolsMarker = File(rootfs, ".pocket-language-tools-version")
    private val coreToolsMarker = File(rootfs, ".pocket-core-tools-version")
    private val systemUpgradeMarker = File(rootfs, ".pocket-system-upgrade-version")
    private val devStacksFile = File(rootfs, ".pocket-dev-stacks.json")
    private val dshMarker = File(rootfs, ".pocket-dsh-version")
    private val agyMarker = File(rootfs, ".pocket-agy-version")
    private val codexMarker = File(rootfs, ".pocket-codex-version")
    private val githubCliMarker = File(rootfs, ".pocket-github-cli-version")
    private val dshAndroidCompatibilityMarker = File(rootfs, ".pocket-dsh-android-compat-version")
    private val macosMetadataRepairMarker = File(rootfs, ".pocket-macos-metadata-repair")

    /** Resolves a user-visible installer label in the language picked inside the app. */
    private fun str(@androidx.annotation.StringRes id: Int, vararg args: Any): String =
        AppStrings.get(context, id, *args)

    fun isInstalled(): Boolean {
        val proot = File(context.applicationInfo.nativeLibraryDir, "libproot.so")
        val rootfsLayoutReady = ensureRootfsCompatibilityLinks()
        // Devices set up before staged toolchains keep working through the legacy marker;
        // fresh installs require the new core-tools marker instead.
        val legacyLanguageTools = languageToolsMarker.readTextOrNull() == LANGUAGE_TOOLS_VERSION
        val coreToolsReady = File(rootfs, "usr/bin/git").exists() && isSupportedCoreToolsVersion()
        val ready = rootfsLayoutReady &&
            proot.canExecute() &&
            File(rootfs, "usr/bin/bash").exists() &&
            rootfsMarker.readTextOrNull() == ROOTFS_VERSION &&
            File(rootfs, "usr/local/bin/node").exists() &&
            (legacyLanguageTools || coreToolsReady) &&
            coreReadyMarker.exists()
        if (ready) repairLegacyMacosMetadata()
        return ready
    }

    /**
     * One-shot cleanup for devices that already extracted a tarball built on
     * macOS without the `--no-mac-metadata` flag. Such bundles contain
     * `._filename` AppleDouble metadata files that crash Python 3.8 when it
     * reads every `.pth` file in site-packages. After the first successful
     * cleanup we write a marker so we never walk the entire rootfs again.
     */
    private fun repairLegacyMacosMetadata() {
        if (macosMetadataRepairMarker.isFile) return
        if (!rootfs.isDirectory) return
        stripMacosMetadataArtifacts(rootfs)
        macosMetadataRepairMarker.parentFile?.mkdirs()
        macosMetadataRepairMarker.writeText("1")
    }

    /** Returns the already verified runtime without performing network or update checks. */
    fun installedRuntime(): InstalledRuntime {
        check(isInstalled()) { str(R.string.rt_install_incomplete) }
        return InstalledRuntime(
            proot = File(context.applicationInfo.nativeLibraryDir, "libproot.so"),
            rootfs = rootfs,
        )
    }

    /** Removes only scaffolding written automatically by earlier PocketDev alpha builds. */
    fun cleanupLegacyWorkspaceScaffolding() {
        val workspaces = File(context.filesDir, "workspaces")
        workspaces.listFiles { file -> file.isDirectory }.orEmpty().forEach { workspace ->
            File(workspace, "README.md").deleteIfExact(LEGACY_README)
            File(workspace, "index.html").deleteIfExact(LEGACY_INDEX)

            listOf(
                File(workspace, ".claude/settings.json"),
                File(workspace, ".claude.json"),
            ).forEach { settings ->
                if (settings.isFile && settings.readTextOrNull()?.contains("/opt/pocket/permission-hook.sh") == true) {
                    settings.delete()
                }
            }
            File(workspace, ".claude").takeIf { it.isDirectory && it.list().isNullOrEmpty() }?.delete()
        }
    }

    private fun File.deleteIfExact(expected: String) {
        if (isFile && runCatching { readText() }.getOrNull() == expected) delete()
    }

    suspend fun ensureInstalled(
        selectedStacks: Set<DevStack> = emptySet(),
        agent: com.jarves.mh.model.AgentKind = com.jarves.mh.model.AgentKind.CLAUDE_CODE,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ): InstalledRuntime {
        require(
            supportsArm64Runtime(
                android.os.Build.SUPPORTED_ABIS,
                System.getProperty("os.arch"),
            ),
        ) { str(R.string.rt_install_unsupported_arch) }
        val proot = File(context.applicationInfo.nativeLibraryDir, "libproot.so")
        require(proot.canExecute()) { "The embedded PRoot launcher is unavailable" }

        val staging = File(runtimeDir, "ubuntu.installing")
        val repairBackup = File(runtimeDir, "ubuntu.repair-backup")
        RootfsRepair.reconcileInterruptedSwap(
            rootfs = rootfs,
            staging = staging,
            backup = repairBackup,
            validate = ::isValidCoreRootfs,
        )
        val layoutBroken = rootfs.isDirectory && !ensureRootfsCompatibilityLinks()
        if (layoutBroken ||
            !File(rootfs, "usr/bin/bash").exists() ||
            rootfsMarker.readTextOrNull() != ROOTFS_VERSION
        ) {
            onProgress(RuntimeInstallProgress(str(R.string.rt_install_preparing_runtime), 0.03f))
            val archive = obtainRuntimeBundle(
                CORE_BUNDLE,
                preferEmbedded = BuildConfig.OFFLINE_RUNTIME_BUNDLES,
                from = 0.03f,
                to = 0.25f,
                onProgress,
            )
            onProgress(RuntimeInstallProgress(str(R.string.rt_install_unpacking_core), 0.28f))
            staging.deleteRecursively()
            staging.mkdirs()
            extractZstdTar(archive, staging)
            stripMacosMetadataArtifacts(staging)
            RootfsRepair.activate(
                rootfs = rootfs,
                staging = staging,
                backup = repairBackup,
                validate = ::isValidCoreRootfs,
            )
            writeResolver()
            if (archive.parentFile == downloads) archive.delete()
        }

        check(ensureRootfsCompatibilityLinks()) { "Core runtime has an invalid Linux filesystem layout" }

        migrateLegacyClaudeMarker()
        ensureSettingsAndHooks()

        // Native npm packages used by common developer tools (including Graft's
        // Tree-sitter bindings) need a working compiler on Linux ARM64. Install
        // the C/C++ toolchain with Core so a user does not hit node-gyp errors
        // merely because they did not know to select an optional developer stack.
        // Other language and Android stacks remain opt-in.
        val coreNeeded = !File(rootfs, "usr/bin/git").exists() || !isSupportedCoreToolsVersion()
        if (coreNeeded) {
            installNodeIfNeeded(proot, 0.58f, 0.66f, onProgress)
        }
        if (systemUpgradeMarker.readTextOrNull() != SYSTEM_UPGRADE_VERSION) {
            runSystemMaintenance(proot, onProgress)
            systemUpgradeMarker.writeText(SYSTEM_UPGRADE_VERSION)
        }
        if (coreNeeded) {
            aptInstall(
                proot,
                listOf("git", "ca-certificates"),
                str(R.string.rt_install_git_base_tools),
                0.70f,
                onProgress,
            )
            writeResolver()
            verifyGuest(proot, "git --version", str(R.string.rt_install_verify_failed, "Git"))
            coreToolsMarker.writeText(CORE_TOOLS_VERSION)
        }

        val requiredStacks = selectedStacks + DevStack.CPP
        val missingStacks = requiredStacks.filterNot(::isStackInstalled)
        missingStacks.forEachIndexed { index, stack ->
            val slice = 0.26f / maxOf(1, missingStacks.size)
            val from = 0.72f + index * slice
            applyStack(proot, stack, from, from + slice, onProgress)
        }

        when (agent) {
            com.jarves.mh.model.AgentKind.CLAUDE_CODE -> ensureClaudeInstalled(proot, 0.985f, onProgress)
            com.jarves.mh.model.AgentKind.DEEPSEEK_HARNESS -> ensureDshInstalled(proot, 0.985f, onProgress)
            com.jarves.mh.model.AgentKind.ANTIGRAVITY -> ensureAgyInstalled(proot, 0.985f, onProgress)
            com.jarves.mh.model.AgentKind.CODEX_CLI -> ensureCodexInstalled(proot, 0.95f, onProgress)
        }
        onProgress(RuntimeInstallProgress(str(R.string.rt_install_setup_complete), 1f))
        return InstalledRuntime(proot, rootfs)
    }

    /**
     * Installs one coding agent on demand. Safe to call again: an already-installed
     * agent returns immediately without network access. Every coding agent is a
     * separate overlay and is fetched or loaded only when selected.
     */
    suspend fun ensureAgentInstalled(
        agent: com.jarves.mh.model.AgentKind,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        val runtime = installedRuntime()
        when (agent) {
            com.jarves.mh.model.AgentKind.CLAUDE_CODE -> ensureClaudeInstalled(runtime.proot, 0.05f, onProgress)
            com.jarves.mh.model.AgentKind.DEEPSEEK_HARNESS -> ensureDshInstalled(runtime.proot, 0.05f, onProgress)
            com.jarves.mh.model.AgentKind.ANTIGRAVITY -> ensureAgyInstalled(runtime.proot, 0.05f, onProgress)
            com.jarves.mh.model.AgentKind.CODEX_CLI -> ensureCodexInstalled(runtime.proot, 0.05f, onProgress)
        }
        onProgress(RuntimeInstallProgress(str(R.string.rt_install_ready, agent.localizedTitle(context)), 1f))
    }

    fun isAgentInstalled(agent: com.jarves.mh.model.AgentKind): Boolean {
        return when (agent) {
            com.jarves.mh.model.AgentKind.CLAUDE_CODE -> {
                migrateLegacyClaudeMarker()
                isInstalled() && File(rootfs, CLAUDE_GUEST_PATH.removePrefix("/")).canExecute() &&
                    !claudeMarker.readTextOrNull().isNullOrBlank()
            }
            com.jarves.mh.model.AgentKind.DEEPSEEK_HARNESS -> isInstalled() &&
                // /usr/local/bin/dsh is an absolute guest symlink. File.exists() follows it
                // against Android's host root and therefore reports false outside PRoot.
                File(rootfs, "usr/local/lib/dsh/node_modules/.bin/dsh").isFile &&
                isSupportedDshVersion(dshMarker.readTextOrNull().orEmpty())
            com.jarves.mh.model.AgentKind.ANTIGRAVITY -> isInstalled() &&
                File(rootfs, AGY_GUEST_PATH.removePrefix("/")).canExecute() &&
                !agyMarker.readTextOrNull().isNullOrBlank()
            com.jarves.mh.model.AgentKind.CODEX_CLI -> isInstalled() &&
                File(rootfs, CODEX_GUEST_PATH.removePrefix("/")).canExecute() &&
                !codexMarker.readTextOrNull().isNullOrBlank()
        }
    }

    val dshVersion: String get() = dshMarker.readTextOrNull().orEmpty()

    val claudeVersion: String get() {
        migrateLegacyClaudeMarker()
        return claudeMarker.readTextOrNull().orEmpty()
    }

    val agyVersion: String get() = agyMarker.readTextOrNull().orEmpty()

    val githubCliVersion: String get() = githubCliMarker.readTextOrNull().orEmpty()

    fun isGitHubCliInstalled(): Boolean = isInstalled() &&
        File(rootfs, GITHUB_CLI_GUEST_PATH.removePrefix("/")).canExecute() &&
        githubCliMarker.readTextOrNull() == GITHUB_CLI_VERSION

    /** Installs GitHub's official ARM64 CLI on demand; it is not bundled in the APK. */
    suspend fun ensureGitHubCliInstalled(onProgress: suspend (RuntimeInstallProgress) -> Unit) {
        if (isGitHubCliInstalled()) return
        check(!BuildConfig.OFFLINE_RUNTIME_BUNDLES) {
            str(R.string.rt_install_github_needs_online)
        }
        writeResolver()
        downloads.mkdirs()
        val downloaded = File(downloads, "gh-$GITHUB_CLI_VERSION-linux-arm64.tar.gz")
        onProgress(RuntimeInstallProgress(str(R.string.rt_install_downloading_github_cli), 0.05f))
        val githubDownloadLabel = str(R.string.rt_install_downloading_version, "GitHub CLI", GITHUB_CLI_VERSION)
        downloadVerified(GITHUB_CLI_RELEASE_URL, downloaded, GITHUB_CLI_RELEASE_SHA256) { bytes, total ->
            val ratio = if (total > 0L) bytes.toFloat() / total else 0f
            onProgress(
                RuntimeInstallProgress(
                    message = githubDownloadLabel,
                    fraction = 0.05f + ratio * 0.75f,
                    downloadedBytes = bytes,
                    totalBytes = total.takeIf { it > 0L },
                    event = RuntimeInstallEvent.DOWNLOAD,
                ),
            )
        }
        onProgress(RuntimeInstallProgress(str(R.string.rt_install_installing_version, "GitHub CLI", GITHUB_CLI_VERSION), 0.85f, indeterminate = true))
        val destination = File(rootfs, GITHUB_CLI_GUEST_PATH.removePrefix("/"))
        destination.parentFile?.mkdirs()
        var found = false
        TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(downloaded.inputStream()))).use { archive ->
            var entry = archive.nextEntry
            while (entry != null) {
                if (entry.isFile && entry.name.removePrefix("./").endsWith("/bin/gh")) {
                    val staged = File(destination.parentFile, ".gh-$GITHUB_CLI_VERSION.installing")
                    FileOutputStream(staged).use { archive.copyTo(it) }
                    Os.chmod(staged.absolutePath, 0b111101101)
                    Os.rename(staged.absolutePath, destination.absolutePath)
                    found = true
                    break
                }
                entry = archive.nextEntry
            }
        }
        check(found) { "Official GitHub CLI archive did not contain the expected binary" }
        downloaded.delete()
        verifyGuest(proot = installedRuntime().proot, command = "$GITHUB_CLI_GUEST_PATH --version", failureMessage = str(R.string.rt_install_verify_failed, "GitHub CLI"))
        githubCliMarker.writeText(GITHUB_CLI_VERSION)
        check(isGitHubCliInstalled()) { "GitHub CLI installation is incomplete" }
        onProgress(RuntimeInstallProgress(str(R.string.rt_install_ready, "GitHub CLI"), 1f))
    }

    /**
     * Versions recorded after each real agent binary has been installed and verified.
     * This intentionally reports what is present in PRoot, even when a newer app build
     * would subsequently offer an agent update.
     */
    fun installedAgentVersions(): Map<com.jarves.mh.model.AgentKind, String> = buildMap {
        migrateLegacyClaudeMarker()
        claudeMarker.readTextOrNull()
            ?.trim()
            ?.takeIf { isAgentInstalled(com.jarves.mh.model.AgentKind.CLAUDE_CODE) && it.matches(CLAUDE_VERSION_PATTERN) }
            ?.let { put(com.jarves.mh.model.AgentKind.CLAUDE_CODE, it) }

        dshMarker.readTextOrNull()
            ?.trim()
            ?.takeIf { isAgentInstalled(com.jarves.mh.model.AgentKind.DEEPSEEK_HARNESS) }
            ?.let { put(com.jarves.mh.model.AgentKind.DEEPSEEK_HARNESS, it) }

        agyMarker.readTextOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() && File(rootfs, AGY_GUEST_PATH.removePrefix("/")).canExecute() }
            ?.let { put(com.jarves.mh.model.AgentKind.ANTIGRAVITY, it) }

        codexMarker.readTextOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() && isAgentInstalled(com.jarves.mh.model.AgentKind.CODEX_CLI) }
            ?.let { put(com.jarves.mh.model.AgentKind.CODEX_CLI, it) }
    }

    /** Checks each installed agent against its own authoritative release source. */
    suspend fun checkAgentUpdates(): Map<com.jarves.mh.model.AgentKind, AgentUpdateInfo> {
        val installed = installedAgentVersions()
        return buildMap {
            installed[com.jarves.mh.model.AgentKind.CLAUDE_CODE]?.let { current ->
                runCatching {
                    JSONObject(fetchText("https://registry.npmjs.org/@anthropic-ai/claude-code/latest")).getString("version")
                }.getOrNull()?.takeIf { isVersionNewer(it, current) }?.let { latest ->
                    put(com.jarves.mh.model.AgentKind.CLAUDE_CODE, AgentUpdateInfo(current, latest))
                }
            }
            installed[com.jarves.mh.model.AgentKind.DEEPSEEK_HARNESS]?.let { current ->
                // DSH needs Android-specific filesystem patches. Only advertise the
                // release validated and bundled with this PocketDev build; npm's
                // latest tag may point at a source layout we cannot safely patch.
                DSH_VERSION.takeIf { isVersionNewer(it, current) }?.let { compatible ->
                    put(com.jarves.mh.model.AgentKind.DEEPSEEK_HARNESS, AgentUpdateInfo(current, compatible))
                }
            }
            installed[com.jarves.mh.model.AgentKind.ANTIGRAVITY]?.let { current ->
                runCatching { fetchAgyManifest().getString("version") }.getOrNull()
                    ?.takeIf { isVersionNewer(it, current) }?.let { latest ->
                        put(com.jarves.mh.model.AgentKind.ANTIGRAVITY, AgentUpdateInfo(current, latest))
                    }
            }
            installed[com.jarves.mh.model.AgentKind.CODEX_CLI]?.let { current ->
                runCatching { JSONObject(fetchText("https://registry.npmjs.org/@openai%2Fcodex/latest")).getString("version") }
                    .getOrNull()?.takeIf { isVersionNewer(it, current) }?.let { latest ->
                        put(com.jarves.mh.model.AgentKind.CODEX_CLI, AgentUpdateInfo(current, latest))
                    }
            }
        }
    }

    suspend fun updateAgent(
        agent: com.jarves.mh.model.AgentKind,
        expectedVersion: String,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        val runtime = installedRuntime()
        when (agent) {
            com.jarves.mh.model.AgentKind.CLAUDE_CODE -> updateClaude(runtime, expectedVersion, onProgress)
            com.jarves.mh.model.AgentKind.DEEPSEEK_HARNESS -> updateDsh(runtime, expectedVersion, onProgress)
            com.jarves.mh.model.AgentKind.ANTIGRAVITY -> updateAgy(runtime, expectedVersion, onProgress)
            com.jarves.mh.model.AgentKind.CODEX_CLI -> updateCodex(runtime.proot, expectedVersion, onProgress)
        }
        onProgress(RuntimeInstallProgress(str(R.string.rt_install_version_ready, agent.localizedTitle(context), expectedVersion), 1f, event = RuntimeInstallEvent.COMPLETED))
    }

    private suspend fun updateClaude(
        runtime: InstalledRuntime,
        expectedVersion: String,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        val latest = JSONObject(fetchText("https://registry.npmjs.org/@anthropic-ai/claude-code/latest")).getString("version")
        check(latest == expectedVersion) { str(R.string.rt_install_newer_release, "Claude Code") }
        val base = "https://downloads.claude.ai/claude-code-releases/$latest"
        val manifest = JSONObject(fetchText("$base/manifest.json"))
        val checksum = manifest.getJSONObject("platforms").getJSONObject("linux-arm64").getString("checksum")
        val downloaded = File(downloads, "claude-$latest")
        val claudeDownloadLabel = str(R.string.rt_install_downloading_version, "Claude Code", latest)
        downloadVerified("$base/linux-arm64/claude", downloaded, checksum) { bytes, total ->
            val ratio = if (total > 0L) bytes.toFloat() / total else 0f
            onProgress(RuntimeInstallProgress(claudeDownloadLabel, ratio * 0.9f, bytes, total.takeIf { it > 0L }, event = RuntimeInstallEvent.DOWNLOAD))
        }
        val claude = File(rootfs, CLAUDE_GUEST_PATH.removePrefix("/"))
        claude.parentFile?.mkdirs()
        val staged = File(claude.parentFile, ".claude-$latest.installing")
        downloaded.copyTo(staged, overwrite = true)
        Os.chmod(staged.absolutePath, 0b111101101)
        Os.rename(staged.absolutePath, claude.absolutePath)
        downloaded.delete()
        verifyGuest(runtime.proot, "$CLAUDE_GUEST_PATH --version", str(R.string.rt_install_update_verify_failed, "Claude Code"))
        claudeMarker.writeText(latest)
    }

    private suspend fun updateAgy(
        runtime: InstalledRuntime,
        expectedVersion: String,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        val manifest = fetchAgyManifest()
        val latest = manifest.getString("version")
        check(latest == expectedVersion) { str(R.string.rt_install_newer_release, "Antigravity") }
        val downloaded = File(downloads, "antigravity-$latest-linux-arm64.tar.gz")
        val agyDownloadLabel = str(R.string.rt_install_downloading_version, "Antigravity CLI", latest)
        downloadVerified(manifest.getString("url"), downloaded, manifest.getString("sha512"), algorithm = "SHA-512") { bytes, total ->
            val ratio = if (total > 0L) bytes.toFloat() / total else 0f
            onProgress(RuntimeInstallProgress(agyDownloadLabel, ratio * 0.9f, bytes, total.takeIf { it > 0L }, event = RuntimeInstallEvent.DOWNLOAD))
        }
        val destination = File(rootfs, AGY_GUEST_PATH.removePrefix("/"))
        var found = false
        TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(downloaded.inputStream()))).use { archive ->
            var entry = archive.nextEntry
            while (entry != null) {
                if (entry.isFile && entry.name.removePrefix("./") == "antigravity") {
                    val staged = File(destination.parentFile, ".agy-$latest.installing")
                    FileOutputStream(staged).use { archive.copyTo(it) }
                    Os.chmod(staged.absolutePath, 0b111101101)
                    Os.rename(staged.absolutePath, destination.absolutePath)
                    found = true
                    break
                }
                entry = archive.nextEntry
            }
        }
        downloaded.delete()
        check(found) { "Antigravity update archive is incomplete" }
        verifyGuest(runtime.proot, "$AGY_GUEST_PATH --version", str(R.string.rt_install_update_verify_failed, "Antigravity CLI"))
        agyMarker.writeText(latest)
    }

    private suspend fun updateDsh(
        runtime: InstalledRuntime,
        expectedVersion: String,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        check(isSupportedDshVersion(expectedVersion)) {
            "DeepSeek Harness $expectedVersion is not compatible with this PocketDev build"
        }
        val quotedVersion = expectedVersion.replace(Regex("[^0-9A-Za-z.+-]"), "")
        check(quotedVersion == expectedVersion) { "Invalid DeepSeek Harness version" }
        runGuestCommand(
            proot = runtime.proot,
            command = "set -e; next=/usr/local/lib/dsh.updating; " +
                "rm -rf \"${'$'}next\"; mkdir -p \"${'$'}next\"; " +
                "cd \"${'$'}next\"; npm init -y >/dev/null; " +
                "npm install --omit=dev --no-audit --no-fund @deepseek-ai/dsh@$quotedVersion",
            displayCommand = "npm install @deepseek-ai/dsh@$quotedVersion",
            fraction = 0.55f,
            timeoutMs = 20 * 60 * 1_000L,
            onProgress = onProgress,
            failureMessage = str(R.string.rt_install_dsh_update_failed),
        )
        val staged = File(rootfs, "usr/local/lib/dsh.updating")
        applyDshAndroidCompatibility(staged, expectedVersion)
        runGuestCommand(
            proot = runtime.proot,
            command = "set -e; old=/usr/local/lib/dsh.previous; rm -rf \"${'$'}old\"; " +
                "mv /usr/local/lib/dsh \"${'$'}old\"; " +
                "if mv /usr/local/lib/dsh.updating /usr/local/lib/dsh; then :; " +
                "else mv \"${'$'}old\" /usr/local/lib/dsh; exit 1; fi",
            displayCommand = "Activate DeepSeek Harness $quotedVersion",
            fraction = 0.9f,
            timeoutMs = 60_000L,
            onProgress = onProgress,
            failureMessage = str(R.string.rt_install_dsh_update_failed),
        )
        val verified = runCatching {
            verifyGuest(runtime.proot, "/usr/local/bin/dsh --profile headless --help", str(R.string.rt_install_update_verify_failed, "DeepSeek Harness"))
        }
        if (verified.isFailure) {
            runCatching {
                runGuestCommand(
                    proot = runtime.proot,
                    command = "set -e; rm -rf /usr/local/lib/dsh; mv /usr/local/lib/dsh.previous /usr/local/lib/dsh",
                    displayCommand = "Restore previous DeepSeek Harness",
                    fraction = 0.95f,
                    timeoutMs = 60_000L,
                    onProgress = onProgress,
                    failureMessage = str(R.string.rt_install_dsh_update_failed),
                )
            }
            verified.getOrThrow()
        }
        File(rootfs, "usr/local/lib/dsh.previous").deleteRecursively()
        dshMarker.writeText(expectedVersion)
        dshAndroidCompatibilityMarker.writeText(DSH_ANDROID_COMPATIBILITY_VERSION)
    }

    private fun fetchAgyManifest(): JSONObject = JSONObject(
        fetchText("https://antigravity-cli-auto-updater-974169037036.us-central1.run.app/manifests/linux_arm64.json"),
    )

    private fun isVersionNewer(candidate: String, current: String): Boolean {
        fun parts(value: String) = Regex("\\d+").findAll(value).map { it.value.toIntOrNull() ?: 0 }.toList()
        val left = parts(candidate)
        val right = parts(current)
        repeat(maxOf(left.size, right.size)) { index ->
            val comparison = (left.getOrElse(index) { 0 }).compareTo(right.getOrElse(index) { 0 })
            if (comparison != 0) return comparison > 0
        }
        return candidate != current && !candidate.contains("alpha", true) && !candidate.contains("rc", true)
    }

    /**
     * Older Core bundles stored Claude Code and its version in Core-owned markers.
     * Preserve that verified installation when upgrading the app, while all fresh
     * installs use the independent Claude overlay and marker.
     */
    private fun migrateLegacyClaudeMarker() {
        if (claudeMarker.isFile) return
        val claude = File(rootfs, CLAUDE_GUEST_PATH.removePrefix("/"))
        if (!claude.isFile) return
        val legacyVersion = sequenceOf(
            coreReadyMarker.readTextOrNull(),
            bundledClaudeMarker.readTextOrNull(),
        ).mapNotNull { it?.trim() }.firstOrNull { it.matches(CLAUDE_VERSION_PATTERN) } ?: return
        claudeMarker.writeText(legacyVersion)
    }

    private fun isSupportedCoreToolsVersion(): Boolean = coreToolsMarker.readTextOrNull() in setOf(
        CORE_TOOLS_VERSION,
        LEGACY_CORE_TOOLS_VERSION,
    )

    private suspend fun ensureCodexInstalled(
        proot: File,
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        if (isAgentInstalled(com.jarves.mh.model.AgentKind.CODEX_CLI)) return
        val version = JSONObject(fetchText("https://registry.npmjs.org/@openai%2Fcodex/latest")).getString("version")
        installCodexVersion(proot, version, fraction, onProgress)
    }

    private suspend fun updateCodex(
        proot: File,
        expectedVersion: String,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        val latest = JSONObject(fetchText("https://registry.npmjs.org/@openai%2Fcodex/latest")).getString("version")
        check(latest == expectedVersion) { str(R.string.rt_install_newer_release, "Codex CLI") }
        installCodexVersion(proot, expectedVersion, 0.05f, onProgress)
    }

    private suspend fun installCodexVersion(
        proot: File,
        version: String,
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        require(version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?"))) {
            "Invalid Codex CLI version returned by npm registry"
        }
        runGuestCommand(
            proot = proot,
            command = "npm install --global --no-audit --no-fund @openai/codex@$version",
            displayCommand = "npm install -g @openai/codex@$version",
            fraction = fraction,
            timeoutMs = 10 * 60_000L,
            onProgress = onProgress,
            failureMessage = "Codex CLI installation failed",
        )
        verifyGuest(proot, "$CODEX_GUEST_PATH --version", str(R.string.rt_install_verify_failed, "Codex CLI"))
        codexMarker.writeText(version)
        check(isAgentInstalled(com.jarves.mh.model.AgentKind.CODEX_CLI)) { "Codex CLI installation is incomplete" }
    }

    private suspend fun ensureClaudeInstalled(
        proot: File,
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        migrateLegacyClaudeMarker()
        if (isAgentInstalled(com.jarves.mh.model.AgentKind.CLAUDE_CODE)) return
        installRuntimeOverlay(
            bundle = CLAUDE_BUNDLE,
            message = str(R.string.rt_install_installing_version, "Claude Code", CLAUDE_BUNDLED_VERSION),
            from = fraction,
            to = 0.995f,
            onProgress = onProgress,
        )
        verifyGuest(proot, "$CLAUDE_GUEST_PATH --version", str(R.string.rt_install_verify_failed, "Claude Code"))
        require(claudeMarker.readTextOrNull() == CLAUDE_BUNDLED_VERSION) {
            "The Claude Code runtime bundle is incomplete"
        }
    }

    private suspend fun ensureAgyInstalled(
        proot: File,
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        if (isAgentInstalled(com.jarves.mh.model.AgentKind.ANTIGRAVITY)) return
        installRuntimeOverlay(
            bundle = AGY_BUNDLE,
            message = str(R.string.rt_install_installing_version, "Antigravity CLI", AGY_VERSION),
            from = fraction,
            to = 0.995f,
            onProgress = onProgress,
            forceEmbedded = true,
        )
        verifyGuest(proot, "$AGY_GUEST_PATH --version", str(R.string.rt_install_verify_failed, "Antigravity CLI"))
        agyMarker.writeText(AGY_VERSION)
        require(isAgentInstalled(com.jarves.mh.model.AgentKind.ANTIGRAVITY)) {
            "Antigravity CLI installation is incomplete"
        }
    }

    private suspend fun ensureDshInstalled(
        proot: File,
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        if (isAgentInstalled(com.jarves.mh.model.AgentKind.DEEPSEEK_HARNESS)) {
            ensureDshAndroidCompatibility()
            return
        }
        // Remove partial or unsupported npm updates before restoring the bundled,
        // Android-validated release. The user can recover simply by launching DSH
        // again; project files and conversations live outside this overlay.
        File(rootfs, "usr/local/lib/dsh").deleteRecursively()
        File(rootfs, "usr/local/lib/dsh.updating").deleteRecursively()
        File(rootfs, "usr/local/lib/dsh.previous").deleteRecursively()
        dshMarker.delete()
        dshAndroidCompatibilityMarker.delete()
        installRuntimeOverlay(
            bundle = DSH_BUNDLE,
            message = str(R.string.rt_install_installing_version, "DeepSeek Harness", DSH_VERSION),
            from = fraction,
            to = 0.995f,
            onProgress = onProgress,
        )
        ensureDshAndroidCompatibility()
        verifyGuest(proot, "/usr/local/bin/dsh --profile headless --help", str(R.string.rt_install_verify_failed, "DeepSeek Harness"))
        require(isAgentInstalled(com.jarves.mh.model.AgentKind.DEEPSEEK_HARNESS)) {
            "The DeepSeek Harness runtime bundle is incomplete"
        }
    }

    /**
     * DSH uses POSIX hard links for no-clobber publication of new session and
     * workspace files. Android blocks that syscall inside PRoot. PRoot's
     * `--link2symlink` workaround is unsuitable here because DSH immediately
     * deletes its staging file, leaving the published symlink dangling.
     *
     * The bundled, pinned DSH build can use COPYFILE_EXCL for the same
     * no-clobber guarantee. Existing-file edits continue to use atomic rename.
     */
    fun ensureDshAndroidCompatibility() {
        if (!isAgentInstalled(com.jarves.mh.model.AgentKind.DEEPSEEK_HARNESS)) return
        applyDshAndroidCompatibility(File(rootfs, "usr/local/lib/dsh"), dshMarker.readTextOrNull().orEmpty())
        dshAndroidCompatibilityMarker.writeText(DSH_ANDROID_COMPATIBILITY_VERSION)
    }

    private fun applyDshAndroidCompatibility(dshRoot: File, version: String) {
        check(isSupportedDshVersion(version)) {
            "DeepSeek Harness $version is not compatible with this PocketDev build"
        }
        val persistence = File(
            dshRoot,
            "node_modules/@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js",
        )
        val localFs = File(
            dshRoot,
            "node_modules/@deepseek-ai/dsh-fs-local/lib/index.js",
        )
        patchDshHardLinkPublication(
            file = persistence,
            importBefore = "import { link, mkdir, mkdtemp, open, readFile, readdir, realpath, rm, stat, truncate } from \"node:fs/promises\";",
            importAfter = "import { copyFile, link, mkdir, mkdtemp, open, readFile, readdir, realpath, rm, stat, truncate } from \"node:fs/promises\";",
            callBefore = "await link(tmp, finalPath);",
            callAfter = "await copyFile(tmp, finalPath, 1);",
        )
        patchDshHardLinkPublication(
            file = localFs,
            importBefore = "import { chmod, link, lstat, mkdir, open, readFile, readdir, realpath, rename, rm, stat } from \"node:fs/promises\";",
            importAfter = "import { chmod, copyFile, link, lstat, mkdir, open, readFile, readdir, realpath, rename, rm, stat } from \"node:fs/promises\";",
            callBefore = "await linkFile(tempPath, absolutePath);",
            callAfter = "await copyFile(tempPath, absolutePath, 1);",
        )
    }

    private fun patchDshHardLinkPublication(
        file: File,
        importBefore: String,
        importAfter: String,
        callBefore: String,
        callAfter: String,
    ) {
        check(file.isFile) { "DeepSeek Harness compatibility file is missing: ${file.name}" }
        var source = file.readText()
        if (callAfter in source && importAfter in source) return
        check(callBefore in source && importBefore in source) {
            "Installed DeepSeek Harness source is not compatible with this PocketDev build"
        }
        source = source.replace(importBefore, importAfter).replace(callBefore, callAfter)
        file.writeText(source)
    }

    /**
     * Installs one optional development stack inside Ubuntu. Safe to call again:
     * already-installed stacks return immediately without network access.
     */
    suspend fun ensureStackInstalled(
        stack: DevStack,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        val runtime = installedRuntime()
        // Android's installer also repairs executable bits, Gradle configuration,
        // and compatibility links. Let it run when the bundle is already present;
        // installAndroidToolchain avoids downloading complete artifacts again.
        if (isStackInstalled(stack) && stack != DevStack.ANDROID) return
        applyStack(runtime.proot, stack, 0.05f, 0.95f, onProgress)
        onProgress(RuntimeInstallProgress(str(R.string.rt_install_stack_ready, stack.localizedLabel(context)), 1f))
    }

    /**
     * Removes an optional development stack without touching projects or the core
     * Node.js/Git runtime. Package-backed stacks are purged through dpkg; bundled
     * stacks remove only their dedicated SDK/language directories.
     */
    suspend fun removeStack(
        stack: DevStack,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        require(stack != DevStack.WEB) { str(R.string.rt_install_web_not_removable) }
        val runtime = installedRuntime()
        if (!isStackInstalled(stack)) return

        onProgress(RuntimeInstallProgress(str(R.string.rt_install_removing_stack, stack.localizedLabel(context)), 0.1f, indeterminate = true))
        when (stack) {
            DevStack.WEB -> Unit
            DevStack.PYTHON -> removePythonStack()
            DevStack.ANDROID -> removeAndroidStack()
            DevStack.CPP -> aptRemove(
                runtime.proot,
                listOf("build-essential", "gcc", "g++", "gcc-10", "g++-10", "make", "cmake", "gdb"),
                0.45f,
                onProgress,
            )
            DevStack.PHP -> {
                aptRemove(
                    runtime.proot,
                    listOf("php-cli", "php-mbstring", "php-xml", "php-curl", "php-zip"),
                    0.45f,
                    onProgress,
                )
                removePath(File(rootfs, "usr/local/bin/composer"))
                removePath(File(rootfs, "root/.cache/composer"))
                removePath(File(rootfs, "root/.composer"))
            }
        }

        writeDevStackState(readDevStackState().apply { put(stack.name, false) })
        onProgress(RuntimeInstallProgress(str(R.string.rt_install_stack_removed, stack.localizedLabel(context)), 1f))
    }

    private fun removePythonStack() {
        listOf(
            ".pocket-python-tools-version",
            "usr/bin/python3",
            "usr/bin/python3.8",
            "usr/bin/pip",
            "usr/bin/pip3",
            "usr/lib/aarch64-linux-gnu/libpython3.8.so.1",
            "usr/lib/aarch64-linux-gnu/libpython3.8.so.1.0",
            "usr/lib/python3",
            "usr/lib/python3.8",
            "usr/local/lib/python3.8",
            "usr/share/python3",
            "usr/share/python-wheels",
            "root/.cache/pip",
        ).forEach { removePath(File(rootfs, it)) }
    }

    private fun removeAndroidStack() {
        listOf(
            "root/android-sdk",
            "root/maven",
            "root/.pocket-android-tools-version",
            "root/.gradle/caches",
            "root/.gradle/daemon",
            "root/.gradle/native",
            "root/.gradle/notifications",
            "root/.gradle/wrapper/dists",
            "root/.gradle/init.d/pocketdev-android.gradle",
            "opt/gradle",
            "opt/jdk-17.0.20.1+1",
            "usr/local/bin/jar",
            "usr/local/bin/jarsigner",
            "usr/local/bin/java",
            "usr/local/bin/javac",
            "usr/local/bin/javadoc",
            "usr/local/bin/keytool",
        ).forEach { removePath(File(rootfs, it)) }
        removeAndroidGradleProperty()
    }

    private fun removeAndroidGradleProperty() {
        val properties = File(rootfs, "root/.gradle/gradle.properties")
        if (!properties.isFile) return
        val remaining = properties.readLines().filterNot(::isManagedAndroidGradleProperty)
        if (remaining.isEmpty()) {
            properties.delete()
        } else {
            properties.writeText(remaining.joinToString("\n").trimEnd() + "\n")
        }
    }

    private fun removePath(file: File) {
        if (file.isDirectory && !java.nio.file.Files.isSymbolicLink(file.toPath())) {
            check(file.deleteRecursively()) { "Could not remove ${file.name}" }
        } else if (file.exists() || java.nio.file.Files.isSymbolicLink(file.toPath())) {
            check(file.delete()) { "Could not remove ${file.name}" }
        }
    }

    private suspend fun applyStack(
        proot: File,
        stack: DevStack,
        from: Float,
        to: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        var verified = true
        when (stack) {
            DevStack.WEB -> {
                onProgress(RuntimeInstallProgress(str(R.string.rt_install_checking_node), from))
                verifyGuest(proot, "node --version && npm --version", str(R.string.rt_install_verify_failed, "Node.js"))
            }
            DevStack.PYTHON -> {
                installRuntimeOverlay(PYTHON_BUNDLE, str(R.string.rt_install_python), from, to, onProgress)
                runCatching {
                    verifyGuest(proot, "python3 --version && pip3 --version", str(R.string.rt_install_verify_failed, "Python"))
                }.onFailure { error ->
                    verified = false
                    onProgress(
                        RuntimeInstallProgress(
                            str(R.string.rt_install_python_verify_failed, error.message?.take(120).orEmpty()),
                            to,
                        ),
                    )
                }
            }
            DevStack.ANDROID -> {
                installAndroidToolchain(proot, from, to, onProgress)
            }
            DevStack.CPP -> {
                aptInstall(
                    proot,
                    listOf("build-essential", "gcc-10", "g++-10", "python3", "make", "cmake", "gdb"),
                    str(R.string.rt_install_cpp),
                    from,
                    onProgress,
                )
                verifyGuest(
                    proot,
                    "gcc --version && g++ --version && make --version && cmake --version",
                    str(R.string.rt_install_verify_failed, "C/C++"),
                )
            }
            DevStack.PHP -> {
                aptInstall(
                    proot,
                    listOf("php-cli", "php-mbstring", "php-xml", "php-curl", "php-zip", "unzip"),
                    str(R.string.rt_install_php),
                    from,
                    onProgress,
                )
                installComposer(proot, from, onProgress)
                verifyGuest(proot, "php --version && composer --version", str(R.string.rt_install_verify_failed, "PHP"))
            }
        }
        if (!verified) return
        writeDevStackState(readDevStackState().apply { put(stack.name, true) })
        onProgress(RuntimeInstallProgress(str(R.string.rt_install_stack_installed, stack.localizedLabel(context)), to))
    }

    /**
     * Installs Composer into Ubuntu from the official latest-stable release,
     * verified against getcomposer.org's published SHA-256 checksum.
     */
    private suspend fun installComposer(
        proot: File,
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        val composer = File(rootfs, "usr/local/bin/composer")
        if (composer.isFile) return
        onProgress(RuntimeInstallProgress(str(R.string.rt_install_downloading_named, "Composer"), fraction))
        downloads.mkdirs()
        val staged = File(downloads, "composer.phar")
        val checksum = fetchText("https://getcomposer.org/download/latest-stable/composer.phar.sha256sum")
            .lineSequence()
            .firstOrNull()
            ?.trim()
            ?.substringBefore(' ')
            ?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
            ?: error("Composer checksum was not found")
        downloadVerified(
            "https://getcomposer.org/download/latest-stable/composer.phar",
            staged,
            checksum,
        ) { _, _ -> }
        composer.parentFile?.mkdirs()
        if (composer.exists()) composer.delete()
        // cacheDir and filesDir can live on different mounts: copy instead of rename.
        staged.inputStream().use { input -> FileOutputStream(composer).use { input.copyTo(it) } }
        staged.delete()
        Os.chmod(composer.absolutePath, 0b111101101)
        onProgress(RuntimeInstallProgress(str(R.string.rt_install_installing_named, "Composer"), fraction))
    }

    private suspend fun installAndroidToolchain(
        proot: File,
        from: Float,
        to: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        val marker = File(rootfs, "root/.pocket-android-tools-version")
        val androidHome = File(rootfs, "root/android-sdk")
        val gradleHome = File(rootfs, "opt/gradle")
        val localMaven = File(rootfs, "root/maven/localMvnRepository")
        if (marker.readTextOrNull() != ANDROID_TOOLS_VERSION ||
            !File(androidHome, "platforms/android-36/android.jar").isFile ||
            !File(androidHome, "build-tools/35.0.0/aapt2").isFile ||
            !File(gradleHome, "gradle-8.14.3/bin/gradle").isFile ||
            !localMaven.isDirectory) {
            installRuntimeOverlay(
                ANDROID_BUNDLE,
                str(R.string.rt_install_android_tools),
                from,
                to,
                onProgress,
            )
            makeAndroidToolsExecutable(androidHome, gradleHome)
            marker.parentFile?.mkdirs()
            marker.writeText(ANDROID_TOOLS_VERSION)
        }
        // Keep this outside the download/install branch so app updates repair
        // existing Android toolchains without downloading the bundles again.
        writeAndroidGradleConfiguration(rootfs)

        verifyGuest(
            proot,
            "java -version 2>&1 | grep -E '\"17\\.|version 17' && " +
                "gradle --version && aapt2 version && test -f \"${'$'}ANDROID_HOME/platforms/android-36/android.jar\"",
            str(R.string.rt_install_verify_failed, "Android SDK, Gradle, Java"),
        )
    }

    private suspend fun installRuntimeOverlay(
        bundle: RuntimeBundle,
        message: String,
        from: Float,
        to: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
        forceEmbedded: Boolean = false,
    ) {
        // Stack overlays honor the same offline/online flavor as the Core bundle:
        // the offline APK ships every stack bundle inside its assets, while the
        // online APK fetches each one from the release URL on demand.
        val archive = obtainRuntimeBundle(bundle, preferEmbedded = forceEmbedded || BuildConfig.OFFLINE_RUNTIME_BUNDLES, from, to * 0.8f + from * 0.2f, onProgress)
        onProgress(RuntimeInstallProgress(message, to * 0.8f + from * 0.2f, indeterminate = true))
        extractZstdTar(archive, rootfs)
        stripMacosMetadataArtifacts(rootfs)
        if (archive.parentFile == downloads) archive.delete()
        onProgress(RuntimeInstallProgress(str(R.string.rt_install_bundle_tools_installed, bundle.label), to))
    }

    /**
     * Removes AppleDouble-style metadata files (`._*`) that some on-device
     * tar builders and macOS tarballs embed alongside the real files. They
     * are harmless to most tools, but Python's site module reads every
     * `.pth` file in site-packages and crashes when one of them is a
     * binary metadata blob.
     */
    private fun stripMacosMetadataArtifacts(root: File) {
        if (!root.isDirectory) return
        val queue = ArrayDeque<File>()
        queue.add(root)
        var removed = 0
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val children = current.listFiles() ?: continue
            for (child in children) {
                if (child.name.startsWith("._")) {
                    if (child.isDirectory) child.deleteRecursively() else child.delete()
                    removed++
                } else if (child.isDirectory && !java.nio.file.Files.isSymbolicLink(child.toPath())) {
                    queue.add(child)
                }
            }
        }
        if (removed > 0) {
            android.util.Log.i("RuntimeInstaller", "Stripped $removed macOS metadata artifacts")
        }
    }

    private suspend fun obtainRuntimeBundle(
        bundle: RuntimeBundle,
        preferEmbedded: Boolean,
        from: Float,
        to: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ): File {
        downloads.mkdirs()
        val destination = File(downloads, bundle.fileName)
        val useEmbedded = preferEmbedded || BuildConfig.OFFLINE_RUNTIME_BUNDLES
        if (useEmbedded) {
            val loadingLabel = str(R.string.rt_install_loading_bundle, bundle.label)
            onProgress(RuntimeInstallProgress(loadingLabel, from, 0, bundle.compressedBytes))
            val temporary = File(downloads, "${bundle.fileName}.part")
            context.assets.open("runtime/${bundle.fileName}").use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var copied = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        val ratio = (copied.toFloat() / bundle.compressedBytes).coerceIn(0f, 1f)
                        onProgress(RuntimeInstallProgress(loadingLabel, from + ratio * (to - from), copied, bundle.compressedBytes))
                    }
                }
            }
            require(digest(temporary, "SHA-256").equals(bundle.sha256, ignoreCase = true)) {
                "${bundle.label} bundle checksum mismatch"
            }
            if (destination.exists()) destination.delete()
            check(temporary.renameTo(destination)) { "Could not stage the ${bundle.label} bundle" }
            return destination
        }

        val url = "${BuildConfig.RUNTIME_RELEASE_BASE_URL}/${bundle.fileName}"
        val downloadingLabel = str(R.string.rt_install_downloading_bundle, bundle.label)
        downloadVerified(url, destination, bundle.sha256) { downloaded, total ->
            val ratio = if (total > 0) downloaded.toFloat() / total else 0f
            onProgress(RuntimeInstallProgress(downloadingLabel, from + ratio * (to - from), downloaded, total.takeIf { it > 0 }))
        }
        return destination
    }

    private suspend fun installZipAsset(
        url: String,
        checksum: String,
        archiveName: String,
        destination: File,
        message: String,
        from: Float,
        to: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        downloads.mkdirs()
        val archive = File(downloads, archiveName)
        downloadVerified(url, archive, checksum) { downloaded, total ->
            val ratio = if (total > 0) downloaded.toFloat() / total else 0f
            onProgress(RuntimeInstallProgress(message, from + ratio * (to - from), downloaded, total.takeIf { it > 0 }))
        }
        onProgress(RuntimeInstallProgress(str(R.string.rt_install_installing_named, archiveName.removeSuffix(".zip")), to, indeterminate = true))
        val staging = File(destination.parentFile, "${destination.name}.installing")
        staging.deleteRecursively()
        staging.mkdirs()
        extractZipArchive(archive, staging)
        destination.deleteRecursively()
        check(staging.renameTo(destination)) { "Could not activate ${destination.name}" }
        archive.delete()
    }

    private fun extractZipArchive(archive: File, destination: File) {
        ZipInputStream(BufferedInputStream(archive.inputStream())).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name.removePrefix("./").trimStart('/')
                if (name.isNotBlank()) {
                    val target = safeChild(destination, name)
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { output -> zip.copyTo(output) }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    private fun makeAndroidToolsExecutable(androidHome: File, gradleHome: File) {
        val buildTools = File(androidHome, "build-tools/35.0.0")
        listOf("aapt", "aapt2", "aidl", "apksigner", "d8", "dexdump", "split-select", "zipalign")
            .map { File(buildTools, it) }
            .plus(File(gradleHome, "gradle-8.14.3/bin/gradle"))
            .filter(File::isFile)
            .forEach { Os.chmod(it.absolutePath, 0b111101101) }
    }

    private fun writeAndroidGradleInitScript(runtimeRootfs: File) {
        val script = File(runtimeRootfs, "root/.gradle/init.d/pocketdev-android.gradle")
        script.parentFile?.mkdirs()
        script.writeText(
            """
            def pocketMaven = uri('/root/maven/localMvnRepository')
            beforeSettings { settings ->
                settings.pluginManagement.repositories {
                    maven { url = pocketMaven }
                    google()
                    mavenCentral()
                    gradlePluginPortal()
                }
            }
            settingsEvaluated { settings ->
                settings.dependencyResolutionManagement.repositories {
                    maven { url = pocketMaven }
                }
            }
            gradle.beforeProject { project ->
                project.extensions.extraProperties.set(
                    'android.aapt2FromMavenOverride',
                    System.getenv('POCKETDEV_AAPT2_OVERRIDE') ?: '$ANDROID_AAPT2_GUEST_PATH'
                )
                project.buildscript.repositories {
                    maven { url = pocketMaven }
                }
            }
            """.trimIndent() + "\n",
        )
    }

    @Synchronized
    private fun writeAndroidGradleConfiguration(runtimeRootfs: File) {
        val aapt2 = File(runtimeRootfs, ANDROID_AAPT2_HOST_PATH)
        if (!aapt2.isFile) return

        writeAndroidGradleInitScript(runtimeRootfs)
        val gradleDir = File(runtimeRootfs, "root/.gradle").apply { mkdirs() }
        val properties = File(gradleDir, "gradle.properties")
        val existingLines = properties.readTextOrNull()?.lineSequence()?.toList().orEmpty()
        val managedLines = listOf(
            "$ANDROID_AAPT2_PROPERTY=$ANDROID_AAPT2_GUEST_PATH",
            "$GRADLE_JVMARGS_PROPERTY=-Xmx512m -XX:MaxMetaspaceSize=256m -Dfile.encoding=UTF-8",
            "$GRADLE_WORKERS_PROPERTY=2",
            "$GRADLE_PARALLEL_PROPERTY=false",
            "$KOTLIN_EXECUTION_PROPERTY=in-process",
        )
        val updatedLines = existingLines.filterNot(::isManagedAndroidGradleProperty) + managedLines
        if (existingLines == updatedLines) return

        val temporary = File(gradleDir, "gradle.properties.pocketdev.tmp")
        temporary.writeText(updatedLines.joinToString("\n").trimEnd() + "\n")
        Os.rename(temporary.absolutePath, properties.absolutePath)
    }

    private fun isManagedAndroidGradleProperty(line: String): Boolean =
        MANAGED_ANDROID_GRADLE_PROPERTIES.any { key ->
            Regex("^\\s*${Regex.escape(key)}\\s*[:=].*$").matches(line)
        }

    /**
     * One-time upgrade path from the old single-bundle layout: devices that already
     * installed every tool keep all stacks without re-downloading anything.
     */
    fun migrateLegacyToolMarkers() {
        if (!File(rootfs, "usr/bin/bash").isFile) return
        if (languageToolsMarker.readTextOrNull() != LANGUAGE_TOOLS_VERSION) return
        if (coreToolsMarker.readTextOrNull() != CORE_TOOLS_VERSION) coreToolsMarker.writeText(CORE_TOOLS_VERSION)
        val state = readDevStackState()
        DevStack.entries.forEach { stack -> if (!state.containsKey(stack.name)) state[stack.name] = true }
        writeDevStackState(state)
    }


    /** Returns the versions actually installed in the ARM64 runtime. */
    fun toolchainInventory(): ToolchainInventory {
        val binDir = File(rootfs, "usr/bin")
        val gccVersions = binDir.listFiles().orEmpty()
            .mapNotNull { Regex("""^gcc-(\d+)$""").matchEntire(it.name)?.groupValues?.get(1) }
            .filter { File(binDir, "gcc-$it").canExecute() && File(binDir, "g++-$it").canExecute() }
            .distinct().sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }
        val selectedGcc = File(rootfs, "root/.pocket-default-gcc-version").readTextOrNull()
            ?.takeIf { it in gccVersions }
            ?: if ("10" in gccVersions) "10" else gccVersions.lastOrNull() ?: "10"

        val sdk = File(rootfs, "root/android-sdk")
        val sdkPlatforms = File(sdk, "platforms").listFiles().orEmpty()
            .mapNotNull { Regex("""^android-(\d+)$""").matchEntire(it.name)?.groupValues?.get(1)?.toIntOrNull() }
            .filter { File(sdk, "platforms/android-$it/android.jar").isFile }
            .distinct().sorted()
        val requestedApi = File(rootfs, "root/.pocket-default-android-api").readTextOrNull()?.toIntOrNull()
        val selectedApi = requestedApi?.takeIf { it in sdkPlatforms }
            ?: 36.takeIf { 36 in sdkPlatforms } ?: sdkPlatforms.lastOrNull()

        val buildToolsVersions = File(sdk, "build-tools").listFiles().orEmpty()
            .filter(::isValidAndroidBuildTools)
            .map { it.name }
            .sortedWith(versionComparator())
        val requestedBuildTools = File(rootfs, "root/.pocket-default-build-tools-version").readTextOrNull()
        val selectedBuildTools = requestedBuildTools?.takeIf { it in buildToolsVersions }
            ?: "35.0.0".takeIf { it in buildToolsVersions }
            ?: buildToolsVersions.lastOrNull()

        val sdkCmakeVersions = File(sdk, "cmake").listFiles().orEmpty()
            .filter { dir ->
                val executable = File(dir, "bin/cmake")
                executable.isFile && executable.canExecute() && isAarch64Elf(executable)
            }.map { it.name }
        // Ubuntu's apt CMake remains a usable fallback but is managed by apt, not the versioned archive UI.
        val systemCmake = File(rootfs, "usr/bin/cmake")
            .takeIf { it.isFile && it.canExecute() && isAarch64Elf(it) }
        val cmakeVersions = (sdkCmakeVersions + listOfNotNull("system".takeIf { systemCmake != null }))
            .distinct().sortedWith(versionComparator())
        val selectedCmake = File(rootfs, "root/.pocket-default-cmake-version").readTextOrNull()
            ?.takeIf { it in cmakeVersions }
            ?: "system".takeIf { it in cmakeVersions }
        val ndkVersions = File(sdk, "ndk").listFiles().orEmpty()
            .filter(::isValidNativeNdk).map { it.name }.sortedWith(versionComparator())
        val selectedNdk = File(rootfs, "root/.pocket-default-ndk-version").readTextOrNull()
            ?.takeIf { it in ndkVersions }

        return ToolchainInventory(
            installedGccVersions = gccVersions,
            selectedGccVersion = selectedGcc,
            installedSdkPlatforms = sdkPlatforms,
            selectedSdkApi = selectedApi,
            installedBuildToolsVersions = buildToolsVersions,
            selectedBuildToolsVersion = selectedBuildTools,
            installedCmakeVersions = cmakeVersions,
            selectedCmakeVersion = selectedCmake,
            installedNdkVersions = ndkVersions,
            selectedNdkVersion = selectedNdk,
            runtimeAvailable = File(rootfs, "usr/bin/bash").isFile,
        )
    }


    /** True when this project has a saved version override profile in app-private storage. */
    fun hasProjectToolchainProfile(projectId: String): Boolean =
        runCatching { projectToolchainProfileFile(projectId).isFile }.getOrDefault(false)

    /**
     * Saves the current global selections as overrides for one Mobile Harness project.
     * The profile stays outside the repository, so it cannot accidentally be committed.
     */
    fun loadProjectToolchainProfile(projectId: String): ToolchainProjectProfile? = runCatching {
        val profileFile = projectToolchainProfileFile(projectId)
        if (!profileFile.isFile) return@runCatching null
        val profile = JSONObject(profileFile.readText())
        if (profile.optInt("schemaVersion", 0) != 1) return@runCatching null
        val inventory = toolchainInventory()
        ToolchainProjectProfile(
            gccVersion = profile.optString("gcc").takeIf { it in inventory.installedGccVersions },
            sdkApi = profile.optInt("sdkApi", -1).takeIf { it in inventory.installedSdkPlatforms },
            buildToolsVersion = profile.optString("buildTools").takeIf { it in inventory.installedBuildToolsVersions },
            cmakeVersion = profile.optString("cmake").takeIf { it in inventory.installedCmakeVersions },
            ndkVersion = profile.optString("ndk").takeIf { it in inventory.installedNdkVersions },
        )
    }.getOrNull()

    fun saveProjectToolchainProfile(projectId: String, selections: ToolchainProjectProfile) {
        val profileFile = projectToolchainProfileFile(projectId)
        val workspaceRoot = File(context.filesDir, "workspaces").canonicalFile
        val projectRoot = File(workspaceRoot, projectId).canonicalFile
        require(projectRoot.parentFile == workspaceRoot && projectRoot.isDirectory) {
            "The selected project workspace is unavailable; open the project again and retry."
        }
        val inventory = toolchainInventory()
        require(inventory.runtimeAvailable) { "Install the Linux runtime before saving a toolchain profile." }
        require(
            selections.gccVersion == null || selections.gccVersion in inventory.installedGccVersions,
        ) { "Selected project GCC/G++ version is not installed." }
        require(
            selections.sdkApi == null || selections.sdkApi in inventory.installedSdkPlatforms,
        ) { "Selected project Android SDK API is not installed." }
        require(
            selections.buildToolsVersion == null || selections.buildToolsVersion in inventory.installedBuildToolsVersions,
        ) { "Selected project Android Build Tools version is not installed as a compatible ARM64 package." }
        require(
            selections.cmakeVersion == null || selections.cmakeVersion in inventory.installedCmakeVersions,
        ) { "Selected project CMake version is not installed as a compatible ARM64 toolchain." }
        require(
            selections.ndkVersion == null || selections.ndkVersion in inventory.installedNdkVersions,
        ) { "Selected project NDK is not installed as a compatible ARM64 toolchain." }
        require(
            selections.gccVersion != null || selections.sdkApi != null || selections.buildToolsVersion != null ||
                selections.cmakeVersion != null || selections.ndkVersion != null,
        ) { "Choose at least one project override, or clear the profile to use global defaults." }

        val profile = JSONObject().put("schemaVersion", 1)
        selections.gccVersion?.let { profile.put("gcc", it) }
        selections.sdkApi?.let { profile.put("sdkApi", it) }
        selections.buildToolsVersion?.let { profile.put("buildTools", it) }
        selections.cmakeVersion?.let { profile.put("cmake", it) }
        selections.ndkVersion?.let { profile.put("ndk", it) }
        profileFile.parentFile?.mkdirs()
        val temporary = File(profileFile.parentFile, profileFile.name + ".tmp")
        temporary.writeText(profile.toString(2))
        if (profileFile.exists()) check(profileFile.delete()) { "Could not replace project toolchain profile." }
        check(temporary.renameTo(profileFile)) { "Could not save project toolchain profile." }
    }

    /** Removes only the saved project override; global selections remain unchanged. */
    fun clearProjectToolchainProfile(projectId: String) {
        val profileFile = projectToolchainProfileFile(projectId)
        if (profileFile.exists()) check(profileFile.delete()) { "Could not remove project toolchain profile." }
    }


    private fun isToolchainVersionUsedByProjectProfiles(key: String, version: String): Boolean {
        val directory = File(context.filesDir, "toolchain-profiles")
        return directory.listFiles().orEmpty().any { file ->
            file.isFile && file.extension == "json" && runCatching {
                JSONObject(file.readText()).optString(key) == version
            }.getOrDefault(false)
        }
    }

    private fun isSdkApiUsedByProjectProfiles(api: Int): Boolean {
        val directory = File(context.filesDir, "toolchain-profiles")
        return directory.listFiles().orEmpty().any { file ->
            file.isFile && file.extension == "json" && runCatching {
                JSONObject(file.readText()).optInt("sdkApi", -1) == api
            }.getOrDefault(false)
        }
    }

    private fun projectToolchainProfileFile(projectId: String): File {
        require(Regex("""^[A-Za-z0-9_-]{1,128}$""").matches(projectId)) { "Invalid project identifier." }
        return File(context.filesDir, "toolchain-profiles/$projectId.json")
    }

    private fun readProjectToolchainProfile(workspace: File): JSONObject? = runCatching {
        val workspaceRoot = File(context.filesDir, "workspaces").canonicalFile.toPath()
        val path = workspace.canonicalFile.toPath()
        if (!path.startsWith(workspaceRoot)) return@runCatching null
        val relative = workspaceRoot.relativize(path)
        if (relative.nameCount < 1) return@runCatching null
        val projectId = relative.getName(0).toString()
        val profileFile = projectToolchainProfileFile(projectId)
        if (!profileFile.isFile) return@runCatching null
        val profile = JSONObject(profileFile.readText())
        if (profile.optInt("schemaVersion", 0) != 1) return@runCatching null
        profile
    }.getOrNull()

    suspend fun installCompilerVersion(version: String, onProgress: suspend (RuntimeInstallProgress) -> Unit) {
        require(version in SUPPORTED_GCC_VERSIONS) {
            "This Ubuntu 20.04 ARM64 runtime currently manages GCC versions " +
                SUPPORTED_GCC_VERSIONS.joinToString() + " from its configured repositories."
        }
        val proot = installedRuntime().proot
        aptInstall(proot, listOf("gcc-$version", "g++-$version"), "Installing GCC/G++ $version side-by-side", 0.15f, onProgress)
        verifyGuest(proot, "gcc-$version --version && g++-$version --version", str(R.string.rt_install_verify_failed, "GCC/G++ $version"))
    }

    fun selectCompilerVersion(version: String) {
        require(version in toolchainInventory().installedGccVersions) { "GCC/G++ $version is not installed" }
        File(rootfs, "root/.pocket-default-gcc-version").writeText(version)
    }

    suspend fun removeCompilerVersion(version: String, onProgress: suspend (RuntimeInstallProgress) -> Unit) {
        require(version in toolchainInventory().installedGccVersions) { "GCC/G++ $version is not installed" }
        require(version != "10") { "GCC/G++ 10 is retained for Node 24 native-addon builds and Graft compatibility." }
        require(!isToolchainVersionUsedByProjectProfiles("gcc", version)) {
            "Project toolchain profile(s) still require GCC/G++ $version. Clear or update those profiles first."
        }
        require(toolchainInventory().selectedGccVersion != version) {
            "Select another GCC/G++ version before removing $version."
        }
        aptRemove(installedRuntime().proot, listOf("gcc-$version", "g++-$version"), 0.2f, onProgress)
    }


    suspend fun installBuildToolsVersion(version: String, onProgress: suspend (RuntimeInstallProgress) -> Unit) {
        require(TOOL_VERSION_PATTERN.matches(version)) { "Invalid Android Build Tools version: $version" }
        require(version !in toolchainInventory().installedBuildToolsVersions) { "Android Build Tools $version is already installed." }
        val runtime = installedRuntime()
        val sdkManager = File(rootfs, "root/android-sdk/cmdline-tools/latest/bin/sdkmanager")
        check(sdkManager.isFile && sdkManager.canExecute()) {
            "Android sdkmanager is unavailable. Reinstall the Android toolchain first."
        }
        runGuestCommand(
            proot = runtime.proot,
            command = "yes | /root/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=/root/android-sdk --install 'build-tools;$version'",
            displayCommand = "sdkmanager --install build-tools;$version",
            fraction = 0.15f,
            timeoutMs = 45 * 60 * 1_000L,
            onProgress = onProgress,
            failureMessage = "Could not install Android Build Tools $version",
        )
        if (version !in toolchainInventory().installedBuildToolsVersions) {
            // Upstream Linux Build Tools are often x86-64 binaries. Don't leave an
            // unusable package installed on the ARM64 runtime after an architecture mismatch.
            runCatching {
                runGuestCommand(
                    proot = runtime.proot,
                    command = "yes | /root/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=/root/android-sdk --uninstall 'build-tools;$version'",
                    displayCommand = "sdkmanager --uninstall build-tools;$version (unsupported host binaries)",
                    fraction = 0.9f,
                    timeoutMs = 20 * 60 * 1_000L,
                    onProgress = onProgress,
                    failureMessage = "Could not roll back incompatible Build Tools $version",
                )
            }
            error("Android Build Tools $version did not provide a verified native Linux/AArch64 aapt2. The package may contain x86-64 host tools.")
        }
        val selected = File(rootfs, "root/.pocket-default-build-tools-version")
        if (selected.readTextOrNull().isNullOrBlank()) selected.writeText(version)
    }

    fun selectBuildToolsVersion(version: String) {
        require(version in toolchainInventory().installedBuildToolsVersions) {
            "Android Build Tools $version is not installed as a compatible ARM64 package."
        }
        File(rootfs, "root/.pocket-default-build-tools-version").writeText(version)
    }

    suspend fun removeBuildToolsVersion(version: String, onProgress: suspend (RuntimeInstallProgress) -> Unit) {
        require(version != "35.0.0") {
            "Build Tools 35.0.0 is retained as the Android runtime's verified baseline and Gradle AAPT2 fallback."
        }
        require(version in toolchainInventory().installedBuildToolsVersions) { "Android Build Tools $version is not installed." }
        require(!isToolchainVersionUsedByProjectProfiles("buildTools", version)) {
            "A project toolchain profile still requires Build Tools $version. Clear or update that profile first."
        }
        require(toolchainInventory().selectedBuildToolsVersion != version) {
            "Select another Build Tools version before removing $version."
        }
        val sdkManager = File(rootfs, "root/android-sdk/cmdline-tools/latest/bin/sdkmanager")
        check(sdkManager.isFile && sdkManager.canExecute()) { "Android sdkmanager is unavailable." }
        runGuestCommand(
            proot = installedRuntime().proot,
            command = "yes | /root/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=/root/android-sdk --uninstall 'build-tools;$version'",
            displayCommand = "sdkmanager --uninstall build-tools;$version",
            fraction = 0.2f,
            timeoutMs = 30 * 60 * 1_000L,
            onProgress = onProgress,
            failureMessage = "Could not remove Android Build Tools $version",
        )
    }

    suspend fun installSdkPlatform(api: Int, onProgress: suspend (RuntimeInstallProgress) -> Unit) {
        require(api in 21..100) { "Android API level must be between 21 and 100." }
        val runtime = installedRuntime()
        val sdkManager = File(rootfs, "root/android-sdk/cmdline-tools/latest/bin/sdkmanager")
        check(sdkManager.isFile && sdkManager.canExecute()) { "Android sdkmanager is unavailable. Reinstall the Android toolchain first." }
        runGuestCommand(
            proot = runtime.proot,
            command = "yes | /root/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=/root/android-sdk --install 'platforms;android-$api'",
            displayCommand = "sdkmanager --install platforms;android-$api",
            fraction = 0.15f,
            timeoutMs = 45 * 60 * 1_000L,
            onProgress = onProgress,
            failureMessage = "Could not install Android SDK platform API $api",
        )
        verifyGuest(runtime.proot, "test -f /root/android-sdk/platforms/android-$api/android.jar", "SDK platform API $api did not install completely")
        if (File(rootfs, "root/.pocket-default-android-api").readTextOrNull().isNullOrBlank()) {
            File(rootfs, "root/.pocket-default-android-api").writeText(api.toString())
        }
    }

    fun selectSdkPlatform(api: Int) {
        require(api in toolchainInventory().installedSdkPlatforms) { "Android SDK platform API $api is not installed" }
        File(rootfs, "root/.pocket-default-android-api").writeText(api.toString())
    }

    suspend fun removeSdkPlatform(api: Int, onProgress: suspend (RuntimeInstallProgress) -> Unit) {
        require(api in toolchainInventory().installedSdkPlatforms) { "Android SDK platform API $api is not installed" }
        val selected = File(rootfs, "root/.pocket-default-android-api").readTextOrNull()?.toIntOrNull()
            ?: toolchainInventory().selectedSdkApi
        require(selected != api) { "Choose another Android SDK API before removing API $api." }
        require(!isSdkApiUsedByProjectProfiles(api)) {
            "A project toolchain profile still requires Android SDK API $api. Clear or update that profile first."
        }
        val sdkManager = File(rootfs, "root/android-sdk/cmdline-tools/latest/bin/sdkmanager")
        check(sdkManager.isFile && sdkManager.canExecute()) { "Android sdkmanager is unavailable." }
        runGuestCommand(
            proot = installedRuntime().proot,
            command = "yes | /root/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=/root/android-sdk --uninstall 'platforms;android-$api'",
            displayCommand = "sdkmanager --uninstall platforms;android-$api",
            fraction = 0.2f,
            timeoutMs = 30 * 60 * 1_000L,
            onProgress = onProgress,
            failureMessage = "Could not remove Android SDK platform API $api",
        )
    }


    /**
     * Installs a Linux/AArch64 CMake archive side-by-side. The default source is
     * Kitware's release archive and published SHA-256; custom archives need a checksum.
     */
    suspend fun installCmakeVersion(
        version: String,
        customUrl: String = "",
        customSha256: String = "",
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        require(TOOL_VERSION_PATTERN.matches(version)) { "Invalid CMake version: $version" }
        val filename = "cmake-$version-linux-aarch64.tar.gz"
        val url = customUrl.trim().ifEmpty { "https://github.com/Kitware/CMake/releases/download/v$version/$filename" }
        require(url.startsWith("https://", ignoreCase = true)) { "Toolchain downloads must use HTTPS." }
        val checksum = when {
            customUrl.isNotBlank() -> validateSha256(customSha256)
            customSha256.isNotBlank() -> validateSha256(customSha256)
            else -> {
                val text = fetchText("https://github.com/Kitware/CMake/releases/download/v$version/cmake-$version-SHA-256.txt")
                text.lineSequence().firstOrNull { it.contains(filename) }
                    ?.let { line -> Regex("""[a-fA-F0-9]{64}""").find(line)?.value }
                    ?: error("Kitware has no Linux AArch64 archive/checksum for CMake $version. Supply a custom ARM64 archive and SHA-256.")
            }
        }
        val runtime = installedRuntime()
        val destination = File(rootfs, "root/android-sdk/cmake/$version")
        require(!destination.exists()) { "CMake $version is already installed." }
        val archive = File(downloads, "cmake-$version-linux-aarch64.tar.gz")
        onProgress(RuntimeInstallProgress("Downloading CMake $version (Linux ARM64)", 0.05f, indeterminate = true))
        downloadVerified(url, archive, checksum) { downloaded, total ->
            val ratio = if (total > 0L) downloaded.toFloat() / total else 0f
            onProgress(RuntimeInstallProgress(
                message = "Downloading CMake $version",
                fraction = 0.05f + ratio * 0.55f,
                downloadedBytes = downloaded,
                totalBytes = total.takeIf { it > 0L },
                event = RuntimeInstallEvent.DOWNLOAD,
            ))
        }
        val staging = File(runtimeDir, "cmake-$version.installing")
        staging.deleteRecursively()
        staging.mkdirs()
        var activated = false
        try {
            extractVersionedArchive(archive, staging)
            val candidate = staging.walkTopDown().firstOrNull { it.isDirectory && File(it, "bin/cmake").isFile }
                ?: error("Archive does not contain a CMake bin/cmake executable.")
            check(isAarch64Elf(File(candidate, "bin/cmake"))) {
                "CMake archive is not a Linux AArch64 build. x86_64 CMake cannot run in this ARM64 runtime."
            }
            destination.parentFile?.mkdirs()
            check(candidate.renameTo(destination)) { "Could not activate CMake $version" }
            activated = true
            staging.deleteRecursively()
            verifyGuest(runtime.proot, "'/root/android-sdk/cmake/$version/bin/cmake' --version", "CMake $version failed post-install verification")
            val defaultFile = File(rootfs, "root/.pocket-default-cmake-version")
            if (defaultFile.readTextOrNull().isNullOrBlank()) defaultFile.writeText(version)
            archive.delete()
            onProgress(RuntimeInstallProgress("CMake $version installed and verified", 1f, event = RuntimeInstallEvent.COMPLETED))
        } catch (error: Throwable) {
            staging.deleteRecursively()
            if (activated) destination.deleteRecursively()
            throw error
        }
    }

    fun selectCmakeVersion(version: String) {
        require(version in toolchainInventory().installedCmakeVersions) { "CMake $version is not installed as an ARM64 toolchain" }
        File(rootfs, "root/.pocket-default-cmake-version").writeText(version)
    }

    suspend fun removeCmakeVersion(version: String, onProgress: suspend (RuntimeInstallProgress) -> Unit) {
        require(version != "system") { "The Ubuntu apt-managed CMake fallback cannot be removed by the versioned archive manager." }
        require(!isToolchainVersionUsedByProjectProfiles("cmake", version)) {
            "A project toolchain profile still requires CMake $version. Clear or update that profile first."
        }
        require(version in toolchainInventory().installedCmakeVersions) { "CMake $version is not installed" }
        require(File(rootfs, "root/.pocket-default-cmake-version").readTextOrNull() != version) {
            "Select another CMake version before removing $version."
        }
        onProgress(RuntimeInstallProgress("Removing CMake $version", 0.2f, indeterminate = true))
        removePath(File(rootfs, "root/android-sdk/cmake/$version"))
        onProgress(RuntimeInstallProgress("CMake $version removed", 1f, event = RuntimeInstallEvent.COMPLETED))
    }

    /** Installs a custom Linux/AArch64 NDK only after checking checksum, version and ELF machine type. */
    suspend fun installNdkArchive(
        version: String,
        url: String,
        sha256: String,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        require(TOOL_VERSION_PATTERN.matches(version)) { "Invalid NDK version: $version" }
        require(url.startsWith("https://", ignoreCase = true)) { "NDK downloads must use HTTPS." }
        val checksum = validateSha256(sha256)
        val archivePath = runCatching { java.net.URI(url).path.lowercase() }.getOrDefault(url.substringBefore('?').lowercase())
        val isZip = archivePath.endsWith(".zip")
        require(isZip || archivePath.endsWith(".tar.gz") || archivePath.endsWith(".tgz")) {
            "NDK archive must be .zip, .tar.gz, or .tgz."
        }
        val runtime = installedRuntime()
        val destination = File(rootfs, "root/android-sdk/ndk/$version")
        require(!destination.exists()) { "NDK $version is already installed." }
        val archiveName = if (isZip) "ndk-$version.zip" else "ndk-$version.tar.gz"
        val archive = File(downloads, archiveName)
        onProgress(RuntimeInstallProgress("Downloading NDK $version (Linux ARM64)", 0.05f, indeterminate = true))
        downloadVerified(url, archive, checksum) { downloaded, total ->
            val ratio = if (total > 0L) downloaded.toFloat() / total else 0f
            onProgress(RuntimeInstallProgress(
                message = "Downloading NDK $version",
                fraction = 0.05f + ratio * 0.55f,
                downloadedBytes = downloaded,
                totalBytes = total.takeIf { it > 0L },
                event = RuntimeInstallEvent.DOWNLOAD,
            ))
        }
        val staging = File(runtimeDir, "ndk-$version.installing")
        staging.deleteRecursively()
        staging.mkdirs()
        var activated = false
        try {
            extractVersionedArchive(archive, staging)
            val candidate = staging.walkTopDown().firstOrNull { isNdkLayout(it) }
                ?: error("Archive is not a complete Android NDK with a Linux ARM64 LLVM toolchain.")
            val properties = File(candidate, "source.properties")
            val declaredVersion = Regex("""(?m)^Pkg\.Revision\s*=\s*(\S+)""").find(properties.readText())?.groupValues?.get(1)
            require(declaredVersion == version) { "NDK archive reports version '$declaredVersion', not '$version'." }
            val clang = File(candidate, "toolchains/llvm/prebuilt/linux-aarch64/bin/clang")
            check(clang.canExecute() && isAarch64Elf(clang)) {
                "This NDK is not built for a Linux AArch64 host. Google's standard Linux NDK archives are x86_64 and are incompatible."
            }
            check(File(candidate, "toolchains/llvm/prebuilt/linux-aarch64/sysroot/usr/include").isDirectory) {
                "NDK $version does not contain a usable Android sysroot."
            }
            destination.parentFile?.mkdirs()
            check(candidate.renameTo(destination)) { "Could not activate NDK $version" }
            activated = true
            staging.deleteRecursively()
            verifyGuest(runtime.proot, "'/root/android-sdk/ndk/$version/toolchains/llvm/prebuilt/linux-aarch64/bin/clang' --version", "NDK $version failed post-install verification")
            val defaultFile = File(rootfs, "root/.pocket-default-ndk-version")
            if (defaultFile.readTextOrNull().isNullOrBlank()) defaultFile.writeText(version)
            archive.delete()
            onProgress(RuntimeInstallProgress("NDK $version installed and verified", 1f, event = RuntimeInstallEvent.COMPLETED))
        } catch (error: Throwable) {
            staging.deleteRecursively()
            if (activated) destination.deleteRecursively()
            throw error
        }
    }

    fun selectNdkVersion(version: String) {
        require(version in toolchainInventory().installedNdkVersions) { "NDK $version is not installed as a native ARM64 toolchain" }
        File(rootfs, "root/.pocket-default-ndk-version").writeText(version)
    }

    suspend fun removeNdkVersion(version: String, onProgress: suspend (RuntimeInstallProgress) -> Unit) {
        require(version in toolchainInventory().installedNdkVersions) { "NDK $version is not installed" }
        require(!isToolchainVersionUsedByProjectProfiles("ndk", version)) {
            "A project toolchain profile still requires NDK $version. Clear or update that profile first."
        }
        require(File(rootfs, "root/.pocket-default-ndk-version").readTextOrNull() != version) {
            "Select another NDK version before removing $version."
        }
        onProgress(RuntimeInstallProgress("Removing NDK $version", 0.2f, indeterminate = true))
        removePath(File(rootfs, "root/android-sdk/ndk/$version"))
        onProgress(RuntimeInstallProgress("NDK $version removed", 1f, event = RuntimeInstallEvent.COMPLETED))
    }


    /** Upstream packages are accepted only if the native tools can execute on this ARM64 host. */
    private fun isValidAndroidBuildTools(directory: File): Boolean = runCatching {
        if (!directory.isDirectory || !TOOL_VERSION_PATTERN.matches(directory.name)) return@runCatching false
        val aapt2 = File(directory, "aapt2")
        aapt2.isFile && aapt2.canExecute() && isAarch64Elf(aapt2)
    }.getOrDefault(false)

    private fun validateSha256(value: String): String {
        val normalized = value.trim().lowercase()
        require(Regex("""^[a-f0-9]{64}$""").matches(normalized)) {
            "Enter the archive's 64-character SHA-256 checksum."
        }
        return normalized
    }

    private fun isAarch64Elf(file: File): Boolean = runCatching {
        if (!file.isFile) return@runCatching false
        val header = ByteArray(20)
        file.inputStream().use { input ->
            var offset = 0
            while (offset < header.size) {
                val count = input.read(header, offset, header.size - offset)
                if (count < 0) return@runCatching false
                offset += count
            }
        }
        header[0] == 0x7f.toByte() && header[1] == 'E'.code.toByte() &&
            header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte() &&
            header[4] == 2.toByte() && header[5] == 1.toByte() &&
            (header[18].toInt() and 0xff) == 183
    }.getOrDefault(false)

    private fun isNdkLayout(directory: File): Boolean =
        directory.isDirectory &&
            File(directory, "source.properties").isFile &&
            File(directory, "toolchains/llvm/prebuilt/linux-aarch64/bin/clang").isFile

    private fun isValidNativeNdk(directory: File): Boolean = runCatching {
        isNdkLayout(directory) &&
            File(directory, "toolchains/llvm/prebuilt/linux-aarch64/bin/clang").let { it.canExecute() && isAarch64Elf(it) }
    }.getOrDefault(false)

    private fun versionComparator(): Comparator<String> = Comparator { left, right ->
        if (left == "system") return@Comparator 1
        if (right == "system") return@Comparator -1
        val a = left.split(Regex("""[.\-+]""")).map { it.toIntOrNull() ?: 0 }
        val b = right.split(Regex("""[.\-+]""")).map { it.toIntOrNull() ?: 0 }
        for (index in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(index) { 0 }
            val y = b.getOrElse(index) { 0 }
            if (x != y) return@Comparator x.compareTo(y)
        }
        left.compareTo(right)
    }

    /** Safe tar.gz/zip extraction; refuses path traversal and absolute symbolic links. */
    private fun extractVersionedArchive(archive: File, destination: File) {
        destination.mkdirs()
        if (archive.name.lowercase().endsWith(".zip")) {
            ZipInputStream(BufferedInputStream(archive.inputStream())).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val relative = entry.name.removePrefix("./")
                    if (relative.isNotBlank()) {
                        val target = safeChild(destination, relative)
                        if (entry.isDirectory) target.mkdirs() else {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { output -> zip.copyTo(output) }
                            if (relative.split('/').any { it == "bin" }) runCatching { Os.chmod(target.absolutePath, 0b111101101) }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            return
        }
        val rootPath = destination.canonicalFile.toPath().normalize()
        val deferredLinks = mutableListOf<Pair<File, File>>()
        TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(archive.inputStream()))).use { tar ->
            var entry: TarArchiveEntry? = tar.nextEntry
            while (entry != null) {
                val relative = entry.name.removePrefix("./")
                if (relative.isNotBlank()) {
                    val target = safeChild(destination, relative)
                    when {
                        entry.isDirectory -> target.mkdirs()
                        entry.isSymbolicLink -> {
                            val linkPath = java.nio.file.Paths.get(entry.linkName)
                            require(!linkPath.isAbsolute) { "Archive contains an absolute symlink: $relative" }
                            val resolved = target.toPath().parent.resolve(linkPath).normalize()
                            require(resolved.startsWith(rootPath)) { "Archive symlink escapes installation directory: $relative" }
                            target.parentFile?.mkdirs()
                            if (target.exists() || java.nio.file.Files.isSymbolicLink(target.toPath())) target.delete()
                            Os.symlink(entry.linkName, target.absolutePath)
                        }
                        entry.isLink -> {
                            val linkTarget = safeChild(destination, entry.linkName.removePrefix("./"))
                            target.parentFile?.mkdirs()
                            if (linkTarget.isFile) linkTarget.inputStream().use { input -> FileOutputStream(target).use { input.copyTo(it) } }
                            else deferredLinks += target to linkTarget
                        }
                        entry.isFile -> {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { output -> tar.copyTo(output) }
                            runCatching { Os.chmod(target.absolutePath, entry.mode and 0b111111111) }
                        }
                    }
                }
                entry = tar.nextEntry
            }
        }
        deferredLinks.forEach { (target, linkTarget) ->
            require(linkTarget.isFile) { "Archive hard-link target is missing" }
            target.parentFile?.mkdirs()
            linkTarget.inputStream().use { input -> FileOutputStream(target).use { input.copyTo(it) } }
            runCatching { Os.chmod(target.absolutePath, Os.stat(linkTarget.absolutePath).st_mode) }
        }
    }

    fun installedStacks(): Set<DevStack> = readDevStackState()
        .filterValues { it }
        .keys
        .mapNotNull { name -> runCatching { DevStack.valueOf(name) }.getOrNull() }
        .filter(::isStackInstalled)
        .toSet()

    fun isStackInstalled(stack: DevStack): Boolean {
        if (readDevStackState()[stack.name] != true) return false
        return when (stack) {
            DevStack.CPP ->
                File(rootfs, "usr/bin/gcc-10").canExecute() &&
                    File(rootfs, "usr/bin/g++-10").canExecute() &&
                    File(rootfs, "usr/bin/python3").canExecute()
            DevStack.ANDROID ->
                File(rootfs, "root/.pocket-android-tools-version").readTextOrNull() == ANDROID_TOOLS_VERSION &&
                    File(rootfs, "root/android-sdk/platforms/android-36/android.jar").isFile &&
                    File(rootfs, "root/android-sdk/build-tools/35.0.0/aapt2").isFile &&
                    File(rootfs, "opt/gradle/gradle-8.14.3/bin/gradle").isFile &&
                    File(rootfs, "root/maven/localMvnRepository").let { it.isDirectory && !it.list().isNullOrEmpty() } &&
                    File(rootfs, "root/.gradle/init.d/pocketdev-android.gradle").isFile
            else -> true
        }
    }

    private fun readDevStackState(): MutableMap<String, Boolean> {
        if (!devStacksFile.isFile) return mutableMapOf()
        return runCatching {
            val obj = JSONObject(devStacksFile.readText())
            mutableMapOf<String, Boolean>().apply {
                DevStack.entries.forEach { stack ->
                    if (obj.has(stack.name)) put(stack.name, obj.optBoolean(stack.name))
                }
            }
        }.getOrDefault(mutableMapOf())
    }

    private fun writeDevStackState(state: Map<String, Boolean>) {
        devStacksFile.parentFile?.mkdirs()
        val obj = JSONObject()
        state.forEach { (name, value) -> obj.put(name, value) }
        devStacksFile.writeText(obj.toString())
    }

    private suspend fun installNodeIfNeeded(
        proot: File,
        from: Float,
        to: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        if (File(rootfs, "usr/local/bin/node").exists()) return
        val nodeDownloadLabel = str(R.string.rt_install_downloading_node, NODE_VERSION)
        onProgress(RuntimeInstallProgress(nodeDownloadLabel, from))
        downloads.mkdirs()
        val nodeFileName = "node-$NODE_VERSION-linux-arm64.tar.gz"
        val nodeBaseUrl = "https://nodejs.org/dist/$NODE_VERSION"
        val checksum = fetchText("$nodeBaseUrl/SHASUMS256.txt")
            .lineSequence()
            .map(String::trim)
            .firstOrNull { it.endsWith("  $nodeFileName") }
            ?.substringBefore(' ')
            ?: error("Node.js checksum was not found")
        val nodeArchive = File(downloads, nodeFileName)
        downloadVerified("$nodeBaseUrl/$nodeFileName", nodeArchive, checksum) { downloaded, total ->
            val ratio = if (total > 0) downloaded.toFloat() / total else 0f
            onProgress(
                RuntimeInstallProgress(
                    nodeDownloadLabel,
                    from + ratio * (to - from),
                    downloaded,
                    total.takeIf { it > 0 },
                ),
            )
        }
        onProgress(RuntimeInstallProgress(str(R.string.rt_install_node), to))
        val nodeStaging = File(runtimeDir, "node.installing")
        nodeStaging.deleteRecursively()
        nodeStaging.mkdirs()
        extractNodeArchive(nodeArchive, nodeStaging)
        val nodeHome = File(rootfs, "usr/local/lib/nodejs")
        nodeHome.deleteRecursively()
        nodeHome.parentFile?.mkdirs()
        check(nodeStaging.renameTo(nodeHome)) { "Could not activate Node.js" }
        val localBin = File(rootfs, "usr/local/bin").apply { mkdirs() }
        listOf("node", "npm", "npx", "corepack").forEach { command ->
            val link = File(localBin, command)
            if (link.exists() || java.nio.file.Files.isSymbolicLink(link.toPath())) link.delete()
            Os.symlink("../lib/nodejs/bin/$command", link.absolutePath)
        }
        nodeArchive.delete()
    }

    private suspend fun aptInstall(
        proot: File,
        packages: List<String>,
        message: String,
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        onProgress(RuntimeInstallProgress(message, fraction))
        aptInstallInternal(proot, packages, fraction, onProgress)
    }

    private suspend fun runSystemMaintenance(
        proot: File,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        onProgress(
            RuntimeInstallProgress(
                message = str(R.string.rt_install_updating_ubuntu),
                fraction = 0.69f,
                indeterminate = true,
            ),
        )
        writeResolver()
        val command = "export DEBIAN_FRONTEND=noninteractive; " +
            "dpkg --configure -a && " +
            "apt-get -o DPkg::Lock::Timeout=120 -f install -y && " +
            "apt-get -o DPkg::Lock::Timeout=120 update && " +
            "apt-get -o DPkg::Lock::Timeout=120 upgrade -y"
        runGuestCommand(
            proot = proot,
            command = command,
            displayCommand = "dpkg --configure -a && apt-get -f install -y && apt-get update && apt-get upgrade -y",
            fraction = 0.69f,
            timeoutMs = 35 * 60 * 1_000L,
            onProgress = onProgress,
            failureMessage = str(R.string.rt_install_ubuntu_maintenance_failed),
        )
    }

    private suspend fun aptInstallInternal(
        proot: File,
        packages: List<String>,
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        require(packages.isNotEmpty()) { "No packages selected" }
        writeResolver()
        val packageNames = packages.joinToString(" ")
        val command = "export DEBIAN_FRONTEND=noninteractive; " +
            "dpkg --configure -a && " +
            "apt-get -o DPkg::Lock::Timeout=120 -f install -y && " +
            "apt-get -o DPkg::Lock::Timeout=120 update && " +
            "apt-get -o DPkg::Lock::Timeout=120 install -y --no-install-recommends $packageNames && " +
            "apt-get clean && rm -rf /var/lib/apt/lists/*"
        runGuestCommand(
            proot = proot,
            command = command,
            displayCommand = "dpkg --configure -a && apt-get -f install -y && apt-get install -y $packageNames",
            fraction = fraction,
            timeoutMs = 30 * 60 * 1_000L,
            onProgress = onProgress,
            failureMessage = str(R.string.rt_install_could_not_install, packageNames),
        )
    }

    private suspend fun aptRemove(
        proot: File,
        packages: List<String>,
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        require(packages.isNotEmpty()) { "No packages selected" }
        val packageNames = packages.joinToString(" ")
        val command = "export DEBIAN_FRONTEND=noninteractive; " +
            "apt-get -o DPkg::Lock::Timeout=120 purge -y $packageNames && " +
            "apt-get clean && rm -rf /var/lib/apt/lists/*"
        runGuestCommand(
            proot = proot,
            command = command,
            displayCommand = "apt-get purge -y $packageNames",
            fraction = fraction,
            timeoutMs = 20 * 60 * 1_000L,
            onProgress = onProgress,
            failureMessage = str(R.string.rt_install_could_not_remove, packageNames),
        )
    }

    private suspend fun runGuestCommand(
        proot: File,
        command: String,
        displayCommand: String,
        fraction: Float,
        timeoutMs: Long,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
        failureMessage: String,
    ) {
        onProgress(
            RuntimeInstallProgress(
                message = displayCommand,
                fraction = fraction,
                terminalLine = "root@pocket:~# $displayCommand",
                indeterminate = true,
                event = RuntimeInstallEvent.COMMAND,
            ),
        )
        val running = process(
            proot = proot,
            rootfs = rootfs,
            workspace = File(rootfs, "root"),
            environment = emptyMap(),
            guestCommand = listOf("/usr/bin/env", "bash", "-lc", command),
        )
        val native = running as? NativeSpawnProcess
        val collected = StringBuilder()
        try {
            withTimeout(timeoutMs) {
                var offset = 0L
                var pending = ""
                while (running.isAlive || (native?.outputFile?.length() ?: 0L) > offset) {
                    coroutineContext.ensureActive()
                    val file = native?.outputFile
                    if (file != null && file.length() > offset) {
                        RandomAccessFile(file, "r").use { input ->
                            input.seek(offset)
                            val available = (input.length() - offset).coerceAtMost(256 * 1024).toInt()
                            val bytes = ByteArray(available)
                            input.readFully(bytes)
                            offset += available
                            pending += bytes.toString(Charsets.UTF_8).replace('\r', '\n')
                        }
                        val parts = pending.split('\n')
                        pending = parts.last()
                        for (raw in parts.dropLast(1)) {
                            val line = sanitizeTerminalLine(raw)
                            if (line.isNotBlank()) {
                                collected.appendLine(line)
                                if (collected.length > MAX_COLLECTED_OUTPUT) collected.delete(0, collected.length - MAX_COLLECTED_OUTPUT)
                                onProgress(
                                    RuntimeInstallProgress(
                                        message = line,
                                        fraction = fraction,
                                        terminalLine = line,
                                        indeterminate = true,
                                        event = RuntimeInstallEvent.OUTPUT,
                                    ),
                                )
                            }
                        }
                    } else {
                        delay(80)
                    }
                }
                sanitizeTerminalLine(pending).takeIf(String::isNotBlank)?.let { line ->
                    collected.appendLine(line)
                    onProgress(RuntimeInstallProgress(line, fraction, terminalLine = line, indeterminate = true, event = RuntimeInstallEvent.OUTPUT))
                }
            }
        } finally {
            if (running.isAlive) running.destroy()
        }
        val exit = running.waitFor()
        onProgress(
            RuntimeInstallProgress(
                message = if (exit == 0) str(R.string.rt_install_command_completed) else str(R.string.rt_install_command_failed, exit),
                fraction = fraction,
                terminalLine = "[exit $exit] $displayCommand",
                event = RuntimeInstallEvent.COMMAND_COMPLETED,
            ),
        )
        check(exit == 0) { actionableProcessError(collected.toString(), failureMessage) }
    }

    private fun sanitizeTerminalLine(raw: String): String = raw
        .replace(ANSI_ESCAPE, "")
        .filter { it == '\t' || it.code >= 32 }
        .take(MAX_TERMINAL_LINE)

    private suspend fun verifyGuest(proot: File, command: String, failureMessage: String) {
        val verify = process(
            proot = proot,
            rootfs = rootfs,
            workspace = File(rootfs, "root"),
            environment = emptyMap(),
            guestCommand = listOf("/usr/bin/env", "bash", "-lc", command),
        )
        withTimeout(60_000L) {
            while (verify.isAlive) delay(50)
        }
        val exit = verify.waitFor()
        val output = (verify as? NativeSpawnProcess)?.outputFile
            ?.let(::readProcessOutputSafely)
            .orEmpty()
            .trim()
        check(exit == 0) { actionableProcessError(output, failureMessage) }
    }

    /**
     * Ubuntu 20.04 uses a merged-/usr layout. A partially extracted or upgraded
     * runtime can lose these top-level links while all readiness markers remain,
     * making every ELF executable misleadingly fail with ENOENT. Restore only
     * the known Ubuntu compatibility links and never replace real directories.
     */
    private fun ensureRootfsCompatibilityLinks(targetRootfs: File = rootfs): Boolean {
        if (!targetRootfs.isDirectory) return false
        val links = mapOf(
            "bin" to "usr/bin",
            "lib" to "usr/lib",
            "sbin" to "usr/sbin",
        )
        return runCatching {
            links.forEach { (name, destination) ->
                val link = File(targetRootfs, name)
                val path = link.toPath()
                if (java.nio.file.Files.isSymbolicLink(path)) {
                    if (java.nio.file.Files.readSymbolicLink(path).toString() != destination) {
                        java.nio.file.Files.delete(path)
                        Os.symlink(destination, link.absolutePath)
                    }
                } else if (link.exists()) {
                    check(link.isDirectory) { "Linux /$name is not a directory or symbolic link" }
                } else {
                    Os.symlink(destination, link.absolutePath)
                }
            }
            File(targetRootfs, "usr/bin/env").canExecute() &&
                File(targetRootfs, "usr/bin/bash").canExecute() &&
                File(targetRootfs, "lib/ld-linux-aarch64.so.1").exists()
        }.onFailure {
            android.util.Log.e("RuntimeInstaller", "Could not repair Linux compatibility links", it)
        }.getOrDefault(false)
    }

    private fun isValidCoreRootfs(targetRootfs: File): Boolean =
        ensureRootfsCompatibilityLinks(targetRootfs) &&
            File(targetRootfs, "usr/bin/bash").isFile &&
            File(targetRootfs, ".pocket-rootfs-version").readTextOrNull() == ROOTFS_VERSION

    private fun actionableProcessError(output: String, fallback: String): String {
        val lines = output.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        val primaryProotError = lines.firstOrNull {
            it.startsWith("proot error:") && !it.contains("can't chmod")
        } ?: lines.firstOrNull { it.startsWith("proot error:") }
        return primaryProotError ?: output.trim().takeLast(1_000).ifBlank { fallback }
    }

    /**
     * Reads a captured PRoot process output file as UTF-8 with replacement,
     * so a stray non-UTF-8 byte in the bundled runtime (for example a binary
     * .pth file from a tarball built on a non-UTF-8 filesystem) does not
     * abort setup.
     */
    private fun readProcessOutputSafely(file: File): String = runCatching {
        file.readTailText(MAX_PROCESS_OUTPUT_BYTES)
    }.getOrElse { error ->
        android.util.Log.w("RuntimeInstaller", "Could not decode process output as UTF-8: ${error.message}")
        ""
    }

    suspend fun initializeExisting(onProgress: suspend (RuntimeInstallProgress) -> Unit): InstalledRuntime {
        val installed = installedRuntime()
        onProgress(RuntimeInstallProgress(str(R.string.rt_install_checking_files), 0.15f))
        writeResolver()
        ensureSettingsAndHooks()
        File(context.filesDir, "runtime-bridge").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        onProgress(RuntimeInstallProgress(str(R.string.rt_install_preparing_bridge), 0.42f))
        check(File(rootfs, "usr/local/bin/node").canExecute()) { "Core runtime is missing Node.js" }
        check(File(rootfs, "usr/bin/git").canExecute()) { "Core runtime is missing Git" }
        onProgress(RuntimeInstallProgress(str(R.string.rt_install_runtime_ready), 1f))
        return installed
    }

    fun process(
        proot: File,
        rootfs: File,
        workspace: File,
        environment: Map<String, String>,
        guestCommand: List<String>,
        guestWorkspacePath: String = "/workspace",
        emulateHardLinks: Boolean = true,
        outputFile: File = File(context.cacheDir, "runtime-output-${System.nanoTime()}.log"),
        pseudoTerminal: Boolean = false,
        ptyRows: Int = 40,
        ptyColumns: Int = 120,
    ): Process {
        check(ensureRootfsCompatibilityLinks()) { "Core runtime has an invalid Linux filesystem layout" }
        require(
            guestWorkspacePath == "/workspace" ||
                Regex("^/workspace/[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?$").matches(guestWorkspacePath),
        ) { "Invalid project workspace path" }
        workspace.mkdirs()
        File(rootfs, guestWorkspacePath.removePrefix("/")).mkdirs()
        // Self-heal devices whose Android tools were installed by an older app
        // version before the global AAPT2 override was persisted.
        writeAndroidGradleConfiguration(rootfs)
        ensureWorkspaceTrust(guestWorkspacePath)
        val bridge = File(context.filesDir, "runtime-bridge").apply { mkdirs() }
        val args = buildList {
            add(proot.absolutePath)
            if (emulateHardLinks) add("--link2symlink")
            add("-0")
            add("-r")
            add(rootfs.absolutePath)
            add("-b")
            add("/dev")
            add("-b")
            add("/proc")
            add("-b")
            add("/sys")
            // ARM64 Android build tools (notably aapt2) use Bionic's
            // /system/bin/linker64 and, on newer releases, APEX libraries.
            listOf("/system", "/apex", "/vendor", "/product").forEach { hostPath ->
                if (File(hostPath).exists()) {
                    File(rootfs, hostPath.removePrefix("/")).mkdirs()
                    add("-b")
                    add(hostPath)
                }
            }
            add("-b")
            add("${workspace.absolutePath}:$guestWorkspacePath")
            add("-b")
            add("${bridge.absolutePath}:/pocket-bridge")
            add("-w")
            add(guestWorkspacePath)
            addAll(guestCommand)
        }
        val prootTemp = File(context.cacheDir, "proot-tmp").apply { mkdirs() }
        return NativeSpawnProcess.start(
            argv = args,
            environment = buildMap {
                put("HOME", "/root")
                val androidReady = File(rootfs, "root/.pocket-android-tools-version").readTextOrNull() == ANDROID_TOOLS_VERSION
                val basePath = "/usr/local/lib/nodejs/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
                val sdkRoot = File(rootfs, "root/android-sdk")
                val projectProfile = readProjectToolchainProfile(workspace)
                val defaultCmake = File(rootfs, "root/.pocket-default-cmake-version").readTextOrNull()
                val projectCmake = projectProfile?.optString("cmake")?.takeIf { version ->
                    version == "system" || File(sdkRoot, "cmake/$version/bin/cmake").let { executable ->
                        executable.isFile && executable.canExecute() && isAarch64Elf(executable)
                    }
                }
                val requestedCmake = projectCmake ?: defaultCmake
                val selectedCmake = requestedCmake?.takeIf { it != "system" }
                    ?.takeIf { File(sdkRoot, "cmake/$it/bin/cmake").let { executable -> executable.isFile && executable.canExecute() && isAarch64Elf(executable) } }
                val defaultNdk = File(rootfs, "root/.pocket-default-ndk-version").readTextOrNull()
                val projectNdk = projectProfile?.optString("ndk")?.takeIf { isValidNativeNdk(File(sdkRoot, "ndk/$it")) }
                val selectedNdk = projectNdk ?: defaultNdk?.takeIf { isValidNativeNdk(File(sdkRoot, "ndk/$it")) }
                val defaultGcc = File(rootfs, "root/.pocket-default-gcc-version").readTextOrNull()
                val projectGcc = projectProfile?.optString("gcc")?.takeIf {
                    File(rootfs, "usr/bin/gcc-$it").canExecute() && File(rootfs, "usr/bin/g++-$it").canExecute()
                }
                val selectedGcc = projectGcc ?: defaultGcc
                    ?.takeIf { File(rootfs, "usr/bin/gcc-$it").canExecute() && File(rootfs, "usr/bin/g++-$it").canExecute() }
                    ?: "10"
                val defaultApi = File(rootfs, "root/.pocket-default-android-api").readTextOrNull()?.toIntOrNull()
                val projectApi = projectProfile?.optInt("sdkApi", -1)?.takeIf {
                    File(sdkRoot, "platforms/android-$it/android.jar").isFile
                }
                val selectedApi = projectApi ?: defaultApi?.takeIf {
                    File(sdkRoot, "platforms/android-$it/android.jar").isFile
                } ?: 36.takeIf { File(sdkRoot, "platforms/android-36/android.jar").isFile }
                val defaultBuildTools = File(rootfs, "root/.pocket-default-build-tools-version").readTextOrNull()
                val profileBuildTools = projectProfile?.optString("buildTools")?.takeIf {
                    isValidAndroidBuildTools(File(sdkRoot, "build-tools/$it"))
                }
                val selectedBuildTools = profileBuildTools ?: defaultBuildTools?.takeIf {
                    isValidAndroidBuildTools(File(sdkRoot, "build-tools/$it"))
                } ?: "35.0.0".takeIf { isValidAndroidBuildTools(File(sdkRoot, "build-tools/$it")) }
                val extraPath = buildList {
                    selectedNdk?.let { add("/root/android-sdk/ndk/$it/toolchains/llvm/prebuilt/linux-aarch64/bin") }
                    selectedCmake?.let { add("/root/android-sdk/cmake/$it/bin") }
                    selectedBuildTools?.let { add("/root/android-sdk/build-tools/$it") }
                }
                val androidPath = if (androidReady) listOf(
                    "/opt/gradle/gradle-8.14.3/bin",
                    "/root/android-sdk/cmdline-tools/latest/bin",
                ) else emptyList()
                put("PATH", (extraPath + androidPath + basePath.split(":")).distinct().joinToString(":"))
                if (androidReady) {
                    put("ANDROID_HOME", "/root/android-sdk")
                    put("ANDROID_SDK_ROOT", "/root/android-sdk")
                    put("GRADLE_HOME", "/opt/gradle/gradle-8.14.3")
                    put("GRADLE_USER_HOME", "/root/.gradle")
                }
                selectedBuildTools?.let {
                    put("ANDROID_BUILD_TOOLS_VERSION", it)
                    put("ANDROID_BUILD_TOOLS_HOME", "/root/android-sdk/build-tools/$it")
                    put("POCKETDEV_AAPT2_OVERRIDE", "/root/android-sdk/build-tools/$it/aapt2")
                    put("ORG_GRADLE_PROJECT_android.aapt2FromMavenOverride", "/root/android-sdk/build-tools/$it/aapt2")
                } ?: put("POCKETDEV_AAPT2_OVERRIDE", ANDROID_AAPT2_GUEST_PATH)
                selectedCmake?.let { put("CMAKE_HOME", "/root/android-sdk/cmake/$it") }
                selectedNdk?.let { version ->
                    val ndkRoot = "/root/android-sdk/ndk/$version"
                    val ndkBin = File(sdkRoot, "ndk/$version/toolchains/llvm/prebuilt/linux-aarch64/bin")
                    put("ANDROID_NDK_HOME", ndkRoot)
                    put("ANDROID_NDK_ROOT", ndkRoot)
                    // Keep host CC/CXX pointed at GCC by default for Node/Graft. Expose the
                    // selected NDK's matching API wrappers separately for explicit cross-builds.
                    val ndkApis = ndkBin.listFiles().orEmpty().mapNotNull { executable ->
                        Regex("""^aarch64-linux-android(\d+)-clang$""")
                            .matchEntire(executable.name)?.groupValues?.get(1)?.toIntOrNull()
                    }.distinct().sorted()
                    val requestedNdkApi = selectedApi ?: 21
                    val ndkApi = ndkApis.filter { api -> api <= requestedNdkApi }.maxOrNull()
                        ?: ndkApis.minOrNull()
                    ndkApi?.let { api ->
                        val androidCc = File(ndkBin, "aarch64-linux-android$api-clang")
                        val androidCxx = File(ndkBin, "aarch64-linux-android$api-clang++")
                        if (androidCc.canExecute() && androidCxx.canExecute()) {
                            put("ANDROID_CC", androidCc.absolutePath)
                            put("ANDROID_CXX", androidCxx.absolutePath)
                            put("ANDROID_NDK_API", api.toString())
                        }
                    }
                    listOf("llvm-ar" to "ANDROID_AR", "llvm-ranlib" to "ANDROID_RANLIB", "llvm-strip" to "ANDROID_STRIP")
                        .forEach { (binary, key) ->
                            File(ndkBin, binary).takeIf { it.canExecute() }?.let { put(key, it.absolutePath) }
                        }
                }
                selectedApi?.let {
                    put("ANDROID_PLATFORM_API", it.toString())
                    put("ANDROID_COMPILE_SDK", it.toString())
                    put("ANDROID_PLATFORM", "android-$it")
                    // FFmpeg and several legacy NDK build scripts read API directly.
                    put("API", it.toString())
                }
                // GCC 10 remains installed for Node 24 / Graft C++20 addons, while legacy builds
                // may select another installed compiler from Settings.
                if (File(rootfs, "usr/bin/python3").canExecute()) put("PYTHON", "/usr/bin/python3")
                if (File(rootfs, "usr/bin/gcc-$selectedGcc").canExecute() && File(rootfs, "usr/bin/g++-$selectedGcc").canExecute()) {
                    put("CC", "/usr/bin/gcc-$selectedGcc")
                    put("CXX", "/usr/bin/g++-$selectedGcc")
                    if (selectedGcc == "10") put("CXXFLAGS", "-std=gnu++20")
                }
                put("LANG", "C.UTF-8")
                put("TERM", "xterm-256color")
                put("LD_LIBRARY_PATH", context.applicationInfo.nativeLibraryDir)
                put("PROOT_NO_SECCOMP", "1")
                put("PROOT_TMP_DIR", prootTemp.absolutePath)
                put("PROOT_LOADER", File(context.applicationInfo.nativeLibraryDir, "libprootloader.so").absolutePath)
                // Also protects any glibc helper Claude starts later.
                put("GLIBC_TUNABLES", "glibc.pthread.rseq=0")
                putAll(environment)
            },
            cwd = context.filesDir.absolutePath,
            outputFile = outputFile,
            pseudoTerminal = pseudoTerminal,
            ptyRows = ptyRows,
            ptyColumns = ptyColumns,
        )
    }

    fun ensureSettingsAndHooks() {
        val hook = File(rootfs, "opt/pocket/permission-hook.sh")
        hook.parentFile?.mkdirs()
        hook.writeText(
            """#!/bin/sh
cat > /dev/null
printf '%s\n' '{"hookSpecificOutput":{"hookEventName":"PermissionRequest","decision":{"behavior":"allow"}}}'
""",
        )
        Os.chmod(hook.absolutePath, 0b111101101)

        val settingsContent = JSONObject()
            .put("disableAllHooks", false)
            .put(
                "permissions",
                JSONObject()
                    .put("allow", claudeWorkspaceToolRules())
                    .put("defaultMode", "acceptEdits"),
            )
            .put(
                "hooks",
                JSONObject().put(
                    "PermissionRequest",
                    org.json.JSONArray().put(
                        JSONObject()
                            .put("matcher", "Bash|Edit|Write|NotebookEdit")
                            .put(
                                "hooks",
                                org.json.JSONArray().put(
                                    JSONObject().put("type", "command").put("command", "/opt/pocket/permission-hook.sh"),
                                ),
                            ),
                    ),
                ),
            )
            .toString()

        val settingsPaths = listOf(
            File(rootfs, "root/.claude/pocket-settings.json"),
            File(rootfs, "root/.claude/settings.json"),
            File(rootfs, "etc/claude/settings.json"),
        )
        for (target in settingsPaths) {
            target.parentFile?.mkdirs()
            target.writeText(settingsContent)
        }
        ensureWorkspaceTrust("/workspace")
    }

    private fun ensureWorkspaceTrust(workspacePath: String) {
        val stateFile = File(rootfs, "root/.claude.json")
        val state = runCatching { JSONObject(stateFile.readText()) }.getOrElse { JSONObject() }
        // Older alpha builds incorrectly wrote settings into Claude's state file.
        // Keep Claude's generated state, but remove only those stale settings keys.
        listOf("disableAllHooks", "permissions", "hooks", "allowedTools", "autoApprove")
            .forEach(state::remove)
        val projects = state.optJSONObject("projects") ?: JSONObject()
        val workspace = projects.optJSONObject(workspacePath) ?: JSONObject()
        workspace.put("hasTrustDialogAccepted", true)
        projects.put(workspacePath, workspace)
        state.put("projects", projects)
        stateFile.writeText(state.toString())
    }

    private fun claudeWorkspaceToolRules() = org.json.JSONArray().apply {
        put("Bash")
        put("Edit")
        put("Write")
        put("NotebookEdit")
        put("Read")
        put("Glob")
        put("Grep")
    }

    private fun writeResolver() {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val dns = manager.getLinkProperties(manager.activeNetwork)?.dnsServers.orEmpty()
        val servers = dns.mapNotNull { it.hostAddress }.ifEmpty { listOf("8.8.8.8", "1.1.1.1") }
        File(rootfs, "etc/resolv.conf").writeText(servers.joinToString("\n") { "nameserver $it" } + "\n")
    }

    private fun extractRootfs(archive: File, destination: File) {
        val deferredLinks = mutableListOf<Pair<File, File>>()
        TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(archive.inputStream()))).use { tar ->
            var entry: TarArchiveEntry? = tar.nextEntry
            while (entry != null) {
                val cleanName = entry.name.removePrefix("./")
                val target = safeChild(destination, cleanName)
                when {
                    entry.isDirectory -> target.mkdirs()
                    entry.isSymbolicLink -> {
                        target.parentFile?.mkdirs()
                        if (target.exists() || java.nio.file.Files.isSymbolicLink(target.toPath())) target.delete()
                        Os.symlink(entry.linkName, target.absolutePath)
                    }
                    entry.isLink -> {
                        target.parentFile?.mkdirs()
                        val linkTarget = safeChild(destination, entry.linkName.removePrefix("./"))
                        if (linkTarget.exists()) {
                            linkTarget.inputStream().use { input -> FileOutputStream(target).use { input.copyTo(it) } }
                        } else {
                            deferredLinks += target to linkTarget
                        }
                    }
                    entry.isFile -> {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { output -> tar.copyTo(output) }
                        runCatching { Os.chmod(target.absolutePath, entry.mode and 0b111111111) }
                    }
                }
                entry = tar.nextEntry
            }
        }
        deferredLinks.forEach { (target, linkTarget) ->
            require(linkTarget.isFile) { "Archive hard-link target is missing" }
            target.parentFile?.mkdirs()
            linkTarget.inputStream().use { input -> FileOutputStream(target).use { input.copyTo(it) } }
            runCatching { Os.chmod(target.absolutePath, android.system.Os.stat(linkTarget.absolutePath).st_mode) }
        }
    }

    private fun extractZstdTar(archive: File, destination: File) {
        val deferredLinks = mutableListOf<Pair<File, File>>()
        TarArchiveInputStream(
            ZstdCompressorInputStream(BufferedInputStream(archive.inputStream())),
        ).use { tar ->
            var entry: TarArchiveEntry? = tar.nextEntry
            while (entry != null) {
                val cleanName = entry.name.removePrefix("./")
                if (cleanName.isNotBlank()) {
                    val target = safeChild(destination, cleanName)
                    when {
                        entry.isDirectory -> target.mkdirs()
                        entry.isSymbolicLink -> {
                            target.parentFile?.mkdirs()
                            if (target.exists() || java.nio.file.Files.isSymbolicLink(target.toPath())) target.delete()
                            Os.symlink(entry.linkName, target.absolutePath)
                        }
                        entry.isLink -> {
                            target.parentFile?.mkdirs()
                            val linkTarget = safeChild(destination, entry.linkName.removePrefix("./"))
                            if (linkTarget.exists()) {
                                linkTarget.inputStream().use { input -> FileOutputStream(target).use { input.copyTo(it) } }
                            } else {
                                deferredLinks += target to linkTarget
                            }
                        }
                        entry.isFile -> {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { output -> tar.copyTo(output) }
                            runCatching { Os.chmod(target.absolutePath, entry.mode and 0b111111111) }
                        }
                    }
                }
                entry = tar.nextEntry
            }
        }
        deferredLinks.forEach { (target, linkTarget) ->
            require(linkTarget.isFile) { "Archive hard-link target is missing" }
            target.parentFile?.mkdirs()
            linkTarget.inputStream().use { input -> FileOutputStream(target).use { input.copyTo(it) } }
            runCatching { Os.chmod(target.absolutePath, android.system.Os.stat(linkTarget.absolutePath).st_mode) }
        }
    }

    private fun extractNodeArchive(archive: File, destination: File) {
        val deferredLinks = mutableListOf<Pair<File, File>>()
        TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(archive.inputStream()))).use { tar ->
            var entry: TarArchiveEntry? = tar.nextEntry
            while (entry != null) {
                val relative = entry.name.removePrefix("./").substringAfter('/', "")
                if (relative.isNotBlank()) {
                    val target = safeChild(destination, relative)
                    when {
                        entry.isDirectory -> target.mkdirs()
                        entry.isSymbolicLink -> {
                            target.parentFile?.mkdirs()
                            if (target.exists() || java.nio.file.Files.isSymbolicLink(target.toPath())) target.delete()
                            Os.symlink(entry.linkName, target.absolutePath)
                        }
                        entry.isLink -> {
                            val relativeLink = entry.linkName.removePrefix("./").substringAfter('/', "")
                            val linkTarget = safeChild(destination, relativeLink)
                            target.parentFile?.mkdirs()
                            if (linkTarget.exists()) {
                                linkTarget.inputStream().use { input -> FileOutputStream(target).use { input.copyTo(it) } }
                            } else {
                                deferredLinks += target to linkTarget
                            }
                        }
                        entry.isFile -> {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { output -> tar.copyTo(output) }
                            runCatching { Os.chmod(target.absolutePath, entry.mode and 0b111111111) }
                        }
                    }
                }
                entry = tar.nextEntry
            }
        }
        deferredLinks.forEach { (target, linkTarget) ->
            require(linkTarget.isFile) { "Node.js archive hard-link target is missing" }
            target.parentFile?.mkdirs()
            linkTarget.inputStream().use { input -> FileOutputStream(target).use { input.copyTo(it) } }
            runCatching { Os.chmod(target.absolutePath, android.system.Os.stat(linkTarget.absolutePath).st_mode) }
        }
    }

    private fun safeChild(root: File, relative: String): File {
        require(relative.isNotBlank() && !relative.startsWith('/')) { "Unsafe archive path" }
        val file = File(root, relative)
        val rootPath = root.canonicalFile.toPath()
        val parentPath = (file.parentFile ?: root).canonicalFile.toPath()
        require(parentPath.startsWith(rootPath)) { "Archive path escapes runtime" }
        return file
    }

    private suspend fun downloadVerified(
        url: String,
        destination: File,
        expectedChecksum: String,
        algorithm: String = "SHA-256",
        onBytes: suspend (downloaded: Long, total: Long) -> Unit,
    ) {
        destination.parentFile?.mkdirs()
        if (destination.isFile && digest(destination, algorithm).equals(expectedChecksum, ignoreCase = true)) {
            onBytes(destination.length(), destination.length())
            return
        }
        val temporary = File(destination.parentFile, "${destination.name}.part")
        var existing = temporary.takeIf(File::isFile)?.length() ?: 0L
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 120_000
        connection.instanceFollowRedirects = true
        if (existing > 0L) connection.setRequestProperty("Range", "bytes=$existing-")
        check(connection.responseCode in 200..299) { str(R.string.rt_install_download_failed_http, connection.responseCode) }
        val resumed = connection.responseCode == HttpURLConnection.HTTP_PARTIAL && existing > 0L
        if (!resumed) {
            temporary.delete()
            existing = 0L
        }
        val total = connection.contentLengthLong.takeIf { it >= 0L }?.plus(existing) ?: -1L
        connection.inputStream.use { input ->
            FileOutputStream(temporary, resumed).use { output ->
                val buffer = ByteArray(128 * 1024)
                var downloaded = existing
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    downloaded += count
                    onBytes(downloaded, total)
                }
            }
        }
        connection.disconnect()
        val actual = digest(temporary, algorithm)
        if (!actual.equals(expectedChecksum, ignoreCase = true)) {
            temporary.delete()
            error("Downloaded file checksum did not match")
        }
        destination.delete()
        check(temporary.renameTo(destination)) { "Could not finish download" }
    }

    private fun fetchText(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Accept", "application/json")
        check(connection.responseCode in 200..299) { "Request failed with HTTP ${connection.responseCode}" }
        return connection.inputStream.bufferedReader().use { it.readText() }.also { connection.disconnect() }
    }

    private fun digest(file: File, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun File.readTextOrNull(): String? = runCatching { readText().trim() }.getOrNull()

    companion object {
        const val AGY_GUEST_PATH = "/root/.local/bin/agy"
        const val GITHUB_CLI_GUEST_PATH = "/root/.local/bin/gh"
        const val CODEX_GUEST_PATH = "/usr/local/lib/nodejs/bin/codex"
        private const val AGY_VERSION = "1.1.27"
        private const val AGY_RELEASE_URL = "https://storage.googleapis.com/antigravity-public/antigravity-cli/1.1.27-5211191891591168/linux-arm/cli_linux_arm64.tar.gz"
        private const val AGY_RELEASE_SHA512 = "ed45f6930785aa4b42f14e07ace1c9d91a94fb76e760f54acbd7d3d3951e1f957fd456a0dae2a3124dd9a3b689bf7afb7c9303a3e4ba95037fc10063424d9bf9"
        private const val GITHUB_CLI_VERSION = "2.100.0"
        private const val GITHUB_CLI_RELEASE_URL = "https://github.com/cli/cli/releases/download/v2.100.0/gh_2.100.0_linux_arm64.tar.gz"
        private const val GITHUB_CLI_RELEASE_SHA256 = "ea4e7a581a32ccad6cc7923cb1576ac5859ba4b9a16ab22eb8f8a96e78e2e961"
        private const val LEGACY_README = "# Pocket Dev project\n\nThis project is managed locally on Android.\n"
        private const val LEGACY_INDEX = "<!doctype html><title>Pocket Dev</title><h1>Hello from Android</h1>\n"
        private const val ROOTFS_VERSION = "ubuntu-20.04.5-arm64"
        private const val ROOTFS_FILE = "ubuntu-base-20.04.5-base-arm64.tar.gz"
        private const val ROOTFS_URL = "https://cdimage.ubuntu.com/ubuntu-base/releases/20.04/release/$ROOTFS_FILE"
        private const val ROOTFS_SHA256 = "f9b999afb4c4b10193087ea8c11be36d688f19e609b05179b571f29357954b52"
        private const val NODE_VERSION = "v24.19.0"
        private const val LANGUAGE_TOOLS_VERSION = "node-v24.19.0-python3-v1"
        private const val CORE_TOOLS_VERSION = "core-bundle-2026.09.5"
        private const val LEGACY_CORE_TOOLS_VERSION = "core-bundle-2026.09.4"
        private const val SYSTEM_UPGRADE_VERSION = "ubuntu-maintenance-v1"
        private const val ANDROID_TOOLS_VERSION = "sdk36-build-tools35-gradle8.14.3-maven-2026.09"
        private val SUPPORTED_GCC_VERSIONS = listOf("9", "10")
        private val TOOL_VERSION_PATTERN = Regex("""^\d+\.\d+(?:\.\d+)?(?:[-+][A-Za-z0-9._-]+)?$""")
        private const val ANDROID_ASSET_BASE = "https://appdevforall.org/dev-assets/debug"
        private const val ANDROID_SDK_URL = "$ANDROID_ASSET_BASE/android-sdk-arm64-v8a.zip"
        private const val ANDROID_SDK_SHA256 = "bfe5bc940a7ede14735817a40962256666ce4152b9f3135f34a4ab9bccb87c3f"
        private const val ANDROID_GRADLE_URL = "$ANDROID_ASSET_BASE/gradle-8.14.3-bin.zip"
        private const val ANDROID_GRADLE_SHA256 = "8e228b640319a7c739c0a93d002facdeb08e8f3ba394d57d74ee355a5e93072c"
        private const val ANDROID_MAVEN_URL = "$ANDROID_ASSET_BASE/localMvnRepository.zip"
        private const val ANDROID_MAVEN_SHA256 = "3ba89892b43377d60743568d1b9004f172c2eab75dac059064f82dec497819f9"
        private const val ANDROID_AAPT2_PROPERTY = "android.aapt2FromMavenOverride"
        private const val GRADLE_JVMARGS_PROPERTY = "org.gradle.jvmargs"
        private const val GRADLE_WORKERS_PROPERTY = "org.gradle.workers.max"
        private const val GRADLE_PARALLEL_PROPERTY = "org.gradle.parallel"
        private const val KOTLIN_EXECUTION_PROPERTY = "kotlin.compiler.execution.strategy"
        private val MANAGED_ANDROID_GRADLE_PROPERTIES = setOf(
            ANDROID_AAPT2_PROPERTY,
            GRADLE_JVMARGS_PROPERTY,
            GRADLE_WORKERS_PROPERTY,
            GRADLE_PARALLEL_PROPERTY,
            KOTLIN_EXECUTION_PROPERTY,
        )
        private const val ANDROID_AAPT2_GUEST_PATH = "/root/android-sdk/build-tools/35.0.0/aapt2"
        private const val ANDROID_AAPT2_HOST_PATH = "root/android-sdk/build-tools/35.0.0/aapt2"
        private val CLAUDE_VERSION_PATTERN = Regex("[0-9]+\\.[0-9]+\\.[0-9]+")
        /** Pinned DeepSeek Harness release installed via npm inside the guest (verified 2026-09-06). */
        const val DSH_VERSION = "0.1.2-rc.1"
        private const val DSH_ANDROID_COMPATIBILITY_VERSION = "copyfile-excl-v1"
        private val CORE_BUNDLE = RuntimeBundle(
            label = "Core",
            fileName = "pocketdev-core-arm64-2026.09.5.tar.zst",
            sha256 = "df0cf7251c74f82d424231e3804114a4ca66b16130eea9abab11e220dc7ac012",
            compressedBytes = 72_185_773L,
        )
        private const val CLAUDE_BUNDLED_VERSION = "2.1.263"
        private const val CLAUDE_GUEST_PATH = "/usr/local/bin/claude"
        private val CLAUDE_BUNDLE = RuntimeBundle(
            label = "Claude Code",
            fileName = "pocketdev-claude-arm64-2026.09.1.tar.zst",
            sha256 = "0f68e15630e8c0fc941afe3f61ab5a3eb4407b334018de6dbdabfa5eca627724",
            compressedBytes = 75_289_800L,
        )
        private val PYTHON_BUNDLE = RuntimeBundle(
            label = "Python",
            fileName = "pocketdev-python-arm64-2026.09.2.tar.zst",
            sha256 = "6b3f56f7743fec142bc045db3ea561ee83fe89af87c2799165ef60351164ef85",
            compressedBytes = 55_419_626L,
        )
        private val ANDROID_BUNDLE = RuntimeBundle(
            label = "Android",
            fileName = "pocketdev-android-arm64-2026.09.1.tar.zst",
            sha256 = "01bea058ebcb17416d1eb08c0211b3782da3228eb3c8348eb3719f2d61dd3ec6",
            compressedBytes = 569_652_007L,
        )
        private val DSH_BUNDLE = RuntimeBundle(
            label = "DeepSeek Harness",
            fileName = "pocketdev-dsh-arm64-2026.09.1.tar.zst",
            sha256 = "88e6a23ba74e1cd74a2c923b7e0d6bd78ba4b7e5f8a9b649f12bf4ffe158cce5",
            compressedBytes = 27_752_194L,
        )
        private val AGY_BUNDLE = RuntimeBundle(
            label = "Antigravity CLI",
            fileName = "pocketdev-agy-arm64-2026.09.1.tar.zst",
            sha256 = "a659ab9188956fc4721ca86fb21b5118e0e489f47a5e02ae6b4f2fb423659d78",
            compressedBytes = 41_870_025L,
        )
        private const val MAX_TERMINAL_LINE = 500
        private const val MAX_COLLECTED_OUTPUT = 24_000
        private const val MAX_PROCESS_OUTPUT_BYTES = 256 * 1024
        private val ANSI_ESCAPE = Regex("\\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\\u0007]*(?:\\u0007|\\u001B\\\\))")
    }
}
