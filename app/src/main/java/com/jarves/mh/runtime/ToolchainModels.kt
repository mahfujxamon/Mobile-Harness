package com.jarves.mh.runtime

/**
 * A snapshot of toolchains installed in Mobile Harness' ARM64 Ubuntu runtime.
 *
 * Versions live side-by-side. "Selected" versions set terminal defaults; Gradle
 * projects still honour their own ndkVersion and cmake.version declarations.
 */
data class ToolchainInventory(
    val installedGccVersions: List<String> = emptyList(),
    val selectedGccVersion: String = "10",
    val installedSdkPlatforms: List<Int> = emptyList(),
    val selectedSdkApi: Int? = null,
    val installedBuildToolsVersions: List<String> = emptyList(),
    val selectedBuildToolsVersion: String? = null,
    val installedCmakeVersions: List<String> = emptyList(),
    val selectedCmakeVersion: String? = null,
    val installedNdkVersions: List<String> = emptyList(),
    val selectedNdkVersion: String? = null,
    val runtimeAvailable: Boolean = false,
)

/** Optional per-project overrides. Null means inherit the global default for that tool. */
data class ToolchainProjectProfile(
    val gccVersion: String? = null,
    val sdkApi: Int? = null,
    val buildToolsVersion: String? = null,
    val cmakeVersion: String? = null,
    val ndkVersion: String? = null,
)

/** Snapshot returned after a toolchain inventory/profile refresh. */
data class ToolchainManagerSnapshot(
    val inventory: ToolchainInventory,
    val projectId: String?,
    val projectProfileSaved: Boolean,
    val projectProfile: ToolchainProjectProfile?,
)

/** User actions exposed by the Settings > Versioned toolchains panel. */
sealed class ToolchainAction {
    object Refresh : ToolchainAction()
    data class InstallGcc(val version: String) : ToolchainAction()
    data class SelectGcc(val version: String) : ToolchainAction()
    data class RemoveGcc(val version: String) : ToolchainAction()
    data class InstallSdkPlatform(val api: Int) : ToolchainAction()
    data class SelectSdkPlatform(val api: Int) : ToolchainAction()
    data class RemoveSdkPlatform(val api: Int) : ToolchainAction()
    data class InstallBuildTools(val version: String) : ToolchainAction()
    data class SelectBuildTools(val version: String) : ToolchainAction()
    data class RemoveBuildTools(val version: String) : ToolchainAction()
    data class InstallCmake(
        val version: String,
        val url: String = "",
        val sha256: String = "",
    ) : ToolchainAction()
    data class SelectCmake(val version: String) : ToolchainAction()
    data class RemoveCmake(val version: String) : ToolchainAction()
    data class InstallNdk(
        val version: String,
        val url: String,
        val sha256: String,
    ) : ToolchainAction()
    data class SelectNdk(val version: String) : ToolchainAction()
    data class RemoveNdk(val version: String) : ToolchainAction()
    data class SaveProjectProfile(
        val projectId: String,
        val gccVersion: String? = null,
        val sdkApi: Int? = null,
        val cmakeVersion: String? = null,
        val ndkVersion: String? = null,
    ) : ToolchainAction()
    data class ClearProjectProfile(val projectId: String) : ToolchainAction()
}
