# Versioned toolchains in Mobile Harness

Mobile Harness runs developer tools inside an Ubuntu 20.04 ARM64 PRoot runtime. Toolchain versions are managed separately from the NDK/CMake versions used to compile the Mobile Harness Android APK itself.

## Settings → Versioned toolchains

The manager detects installed versions and lets you install, select, or remove compatible versions side-by-side:

- **GCC/G++:** Ubuntu packages for GCC 9 and 10 can coexist. GCC/G++ 10 is retained as the safe default for Node.js 24 native modules and Graft's C++20 build.
- **Android SDK platforms:** install multiple `platforms;android-N` entries using the bundled `sdkmanager`. The selected API is exported as `ANDROID_PLATFORM_API`, `ANDROID_COMPILE_SDK`, `ANDROID_PLATFORM`, and `API` for new terminal processes.
- **Android Build Tools:** install/select compatible versions side-by-side. The manager validates that `aapt2` is a Linux/AArch64 ELF binary before advertising a version and exposes the selected version through `ANDROID_BUILD_TOOLS_VERSION`, `ANDROID_BUILD_TOOLS_HOME`, and `PATH`. Build Tools 35.0.0 is retained as the baseline and fallback because the runtime currently depends on it.
- **CMake:** install a Kitware Linux/AArch64 release or a custom HTTPS archive with its SHA-256 checksum. The manager checks the downloaded checksum and ELF machine architecture before activating the version. The Ubuntu apt-managed CMake is shown as a protected fallback.
- **Android NDK:** the manager accepts a custom HTTPS archive and SHA-256, verifies `source.properties`, the Linux/AArch64 Clang executable, and the sysroot, then installs it under `/root/android-sdk/ndk/<version>`. It deliberately rejects Google's standard Linux x86-64 NDK packages because they cannot run as host tools in the ARM64 runtime.

Versions live in distinct directories; installing an alternate version does not overwrite the existing one. A selected version cannot be removed, and removal requires confirmation. GCC/G++ 10 is protected to preserve the Node/Graft native-build baseline.

## What version selection changes

Global selections are persisted in the guest runtime and affect newly launched terminal/build processes. Each Mobile Harness project can also save independent GCC/G++, SDK API, Build Tools, CMake, and NDK overrides from Settings. These profiles are stored in app-private storage rather than committed to the project's Git repository. Selected native NDK/CMake/Build Tools binaries are added to `PATH`; NDK uses `ANDROID_NDK_HOME`/`ANDROID_NDK_ROOT`. For Android cross-compilation, a compatible selected NDK also exposes API-matched `ANDROID_CC`, `ANDROID_CXX`, `ANDROID_AR`, `ANDROID_RANLIB`, and `ANDROID_STRIP`. Host `CC`/`CXX` deliberately remain GCC/G++ by default so Node.js and Graft native modules are not accidentally cross-compiled; FFmpeg or other Android-target build scripts can explicitly use `CC="$ANDROID_CC" CXX="$ANDROID_CXX"` when configuring their target build.

For Gradle builds, the runtime supplies the selected Build Tools `aapt2` path through its init script while leaving the project files untouched. Android projects may still pin versions in Gradle—for example, `android { ndkVersion "..." }`, `buildToolsVersion "..." `, `compileSdk`, and `externalNativeBuild.cmake.version`. These project declarations and plugin compatibility constraints must be checked when migrating a legacy project. Shell-driven FFmpeg build scripts can consume selected environment variables or set their own versions explicitly.

## Compatibility and safety limits

- This feature does not promise that every upstream toolchain version is available for Linux/AArch64. In particular, official Android NDK and Build Tools Linux downloads may contain x86-64 host binaries; use trusted, verifiable Linux/AArch64-compatible artifacts for device-side compilation. Incompatible Build Tools packages are uninstalled/rejected instead of marked usable.
- Custom CMake/NDK archives must come from a trusted HTTPS source and have a correct SHA-256 checksum. A checksum verifies downloaded bytes, not the trustworthiness of the publisher.
- Toolchain removal can break projects that explicitly depend on a removed version. Review the confirmation dialog and keep old versions until every dependent project has been migrated.
- Compiler/SDK actions require an already installed runtime; Android SDK platform actions also require the Android stack and its `sdkmanager`.
- Existing terminal processes keep the environment with which they started. Open a new terminal/build process after changing the selected version.


## Codex CLI agent

The Codex CLI is available as an optional agent in Settings → Coding agent. Install it there to add the official `@openai/codex` npm package to the ARM64 PRoot runtime. It uses the shared agent picker and chat UI. Configure an OpenAI-compatible endpoint and API key under the Codex agent's Custom API provider, or run `codex login` from the in-app terminal to authenticate with a supported ChatGPT account. The CLI's `codex exec --json` output is translated into the app's shared activity/chat events. The runtime currently uses Codex's non-interactive execution mode; review changes in the normal Mobile Harness diff UI.
