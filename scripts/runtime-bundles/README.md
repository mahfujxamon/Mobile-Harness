# PocketDev runtime bundles

Runtime bundles are release artifacts, not device backups. They are built from
the official Ubuntu ARM64 base and architecture-matched upstream tool archives,
so they are portable across Android phones that expose the `arm64-v8a` ABI.

The rooted phone is only an ARM64 build host. No Motorola system files, Android
partitions, Magisk files, device identifiers, or user data are included.

## Bundle layout

- `core`: Ubuntu, Node, npm, Git, and shared runtime support. It contains no
  coding agent.
- `python`: Python, pip, venv, and build tools. Downloaded only when selected.
- `android`: a portable JDK 17, Android SDK, ARM64 build tools, Gradle, the
  offline Maven repository, and PocketDev's global ARM64 AAPT2 configuration.
- `cpp`: GCC, G++, make, CMake, and GDB.
- `php`: PHP CLI, common extensions, and Composer.
- `claude`: Anthropic's checksum-verified Claude Code ARM64 binary. Build it
  with `scripts/runtime-bundles/build-claude-from-installed-android.sh`; the
  export excludes authentication, settings, conversations, projects, and all
  device data. Online builds download it only when selected, while offline
  builds embed the same verified overlay.
- `dsh`: the pinned official DeepSeek Harness npm payload and launcher. Build it
  from a verified PocketDev installation with
  `scripts/runtime-bundles/build-dsh-from-installed-android.sh`; the export
  intentionally excludes provider settings, credentials, sessions, and projects.
  Online builds download this artifact from the runtime GitHub release; offline
  builds embed the identical checksum-verified artifact in the APK.
- `agy`: Google's checksum-pinned official Linux ARM64 Antigravity CLI binary.
  Build it with `scripts/runtime-bundles/build-agy-from-official.sh`. The overlay
  excludes OAuth credentials, settings, conversations, projects, and all device
  data. Review Google's redistribution terms before publishing an APK containing
  this binary.

The current artifact metadata and SHA-256 checksums live in
`dist/runtime-bundles/manifest.json`. Large `.tar.zst` files and downloaded
source archives are intentionally ignored by Git and must be published as
release assets or copied into the app's release asset input.

## Compatibility

These artifacts are universal for 64-bit ARM Android devices; they are not tied
to the phone used to build them. They do not support x86/x86_64 emulators or
32-bit-only ARM devices. PocketDev must continue checking for `arm64-v8a` before
installation.

## Native npm packages on Android ARM64

The Ubuntu guest uses Node.js 24. Native npm dependencies (for example, older
Tree-sitter bindings) may invoke node-gyp and compile against Node's V8 headers.
Select the **C/C++** developer stack before installing packages that compile
native addons. That stack installs GCC/G++ 10, Python 3, Make, CMake, and GDB.
The runtime exports npm_config_python=/usr/bin/python3; when GCC/G++ 10 are
installed it also exports CC=/usr/bin/gcc-10, CXX=/usr/bin/g++-10, and
CXXFLAGS=-std=gnu++20 to every newly started guest process. A command can
override these defaults with its own environment assignments.

This removes the common Node 24/C++20 configuration failure, but cannot make
every third-party package portable: dependencies that require x86 binaries,
unsupported system libraries, incompatible Node APIs, or unavailable upstream
ARM64 builds can still fail. Build tools are guest packages; installing the
C/C++ stack may require network access and additional storage.

## Release requirements

1. Build on a clean ARM64 Linux environment or a rooted ARM64 Android phone
   using a clean Ubuntu chroot.
2. Verify every downloaded source archive against its pinned SHA-256 checksum.
3. Remove package caches, logs, temporary files, resolver state, SSH host keys,
   machine IDs, and shell histories.
4. Create deterministic archives with numeric root ownership and a fixed mtime.
5. Extract each completed archive into a fresh directory and execute its tools.
6. Publish the archive and manifest together. Sign the manifest with a private
   release key kept outside this repository before production distribution.
