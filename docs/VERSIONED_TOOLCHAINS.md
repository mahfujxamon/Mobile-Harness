# Versioned toolchains in Mobile Harness

Mobile Harness runs developer tools inside an Ubuntu 20.04 ARM64 PRoot runtime. Toolchain versions are managed separately from the NDK/CMake versions used to compile the Mobile Harness Android APK itself.

## Settings → Versioned toolchains

The manager detects installed versions and lets you install, select, or remove compatible versions side-by-side:

- **GCC/G++:** Ubuntu packages for GCC 9 and 10 can coexist. GCC/G++ 10 is retained as the safe default for Node.js 24 native modules and Graft's C++20 build.
- **Android SDK platforms:** install multiple `platforms;android-N` entries using the bundled `sdkmanager`. The selected API is exported as `ANDROID_PLATFORM_API`, `ANDROID_COMPILE_SDK`, `ANDROID_PLATFORM`, and `API` for new terminal processes.
- **CMake:** install a Kitware Linux/AArch64 release or a custom HTTPS archive with its SHA-256 checksum. The manager checks the downloaded checksum and ELF machine architecture before activating the version. The Ubuntu apt-managed CMake is shown as a protected fallback.
- **Android NDK:** the manager accepts a custom HTTPS archive and SHA-256, verifies `source.properties`, the Linux/AArch64 Clang executable, and the sysroot, then installs it under `/root/android-sdk/ndk/<version>`. It deliberately rejects Google's standard Linux x86-64 NDK packages because they cannot run as host tools in the ARM64 runtime.

Versions live in distinct directories; installing an alternate version does not overwrite the existing one. A selected version cannot be removed, and removal requires confirmation. GCC/G++ 10 is protected to preserve the Node/Graft native-build baseline.

## What version selection changes

Selections are persisted in the guest runtime and affect newly launched terminal/build processes. The environment exposes the selected native NDK and CMake binaries first on `PATH`, sets `ANDROID_NDK_HOME` and `ANDROID_NDK_ROOT`, and selects `CC`/`CXX` for the chosen GCC/G++ pair.

An Android project may explicitly pin its own versions in Gradle. For example, `android { ndkVersion "..." }` and `externalNativeBuild.cmake.version` remain authoritative for that Gradle build; a global terminal selection cannot rewrite a project's build files. Legacy shell-based projects such as FFmpeg scripts can instead consume the selected environment variables, and can still set their own variables explicitly.

## Compatibility and safety limits

- This feature does not promise that every upstream toolchain version is available for Linux/AArch64. In particular, official Android NDK Linux downloads are typically x86-64 host binaries; use a trusted, verifiable Linux/AArch64-host NDK build for device-side compilation.
- Custom CMake/NDK archives must come from a trusted HTTPS source and have a correct SHA-256 checksum. A checksum verifies downloaded bytes, not the trustworthiness of the publisher.
- Toolchain removal can break projects that explicitly depend on a removed version. Review the confirmation dialog and keep old versions until every dependent project has been migrated.
- Compiler/SDK actions require an already installed runtime; Android SDK platform actions also require the Android stack and its `sdkmanager`.
- Existing terminal processes keep the environment with which they started. Open a new terminal/build process after changing the selected version.
