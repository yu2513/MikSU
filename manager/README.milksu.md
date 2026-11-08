# MilkSU Manager

MilkSU Manager provides Root authorization, application profiles, module
management, KPM controls and SUSFS settings.

Application ID: `io.milksu.manager`.
Kotlin, JNI and AIDL namespace: `io.milksu.manager`.

The application ID is MilkSU's installed identity and must match
`KSU_PACKAGE_NAME` in the kernel and signing metadata. Builds reject upstream
KernelSU/KernelSU-Next application IDs. Keep the MilkSU application ID and
signing certificate stable for updates.

Kotlin/Java classes, JNI symbols, AIDL descriptors, manifest components and the
daemon's explicit launch component all use the MilkSU namespace. The installed
application ID and signing certificate remain unchanged by this code migration.
Hidden Manager packages retain the MilkSU class names while their installed
identity is rewritten.

KernelSU-compatible paths and protocols, including `/data/adb/ksu`, the driver
descriptor, module variables and WebUI interfaces, remain stable so existing
profiles, modules and tools keep working.

## Build

The Manager requires the compiled daemon at
`app/src/main/jniLibs/arm64-v8a/libksud.so`.

The release pipeline provides this asset and runs the Android build:

```sh
bash tools/build-release.sh manager
```

`version.properties` defines the shared Manager, daemon and kernel version.
See the [build documentation](../docs/MILKSU_TECHNICAL.md) for prerequisites
and the complete native asset build sequence.

## Signing

The Android signing plugin reads `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`,
`KEY_ALIAS` and `KEY_PASSWORD` from the private build configuration.

Official builds use `MILKSU_SIGNING_PROFILE=release` and
`MILKSU_REQUIRE_RELEASE_SIGNING=1`. The selected APK certificate must match
the Manager certificate parameters compiled into the root modules.

Use the same signing identity for subsequent APK updates. Keystores and
passwords are excluded from public source and artifact packages.

## KPM And SUSFS

The main pages are Home, Superuser, Modules and Settings. Enabling KPM
integration adds the KPM page between Home and Superuser.

KPM supports document import, loading confirmation, temporary loading,
automatic loading, control and unload operations. Temporary loading leaves
automatic loading disabled.

Enabling SUSFS starts the guard immediately and enables automatic loading.
Home displays its support status, version and variant while integration is
enabled. The AVC log spoofing setting controls the kernel feature and saves
its boot configuration.

KPM image changes require a full reboot. A kernel module that remains
resident is displayed as pending removal until reboot.

## Backend Contracts

KPM commands use the `ksud kpm` JSON envelope. SUSFS status uses a direct JSON
object from `ksud susfs status`; mutations use command output and exit status.
Operations run serially and query the backend afterward.

Imported documents are copied to a bounded app-private staging file. The
staged file is removed after the daemon processes it.

## Updates

The Manager checks stable Releases of `Xiaogugtfgdfhgrh/MikSU`.
`MILKSU_UPDATE_URL` can be set through a Gradle property or environment
variable.

A stable tag such as `v0.2.1` must contain an APK named
`MilkSU_0.2.1_32801.apk`, with a version code higher than the installed
version. Drafts, prereleases, missing APKs and malformed responses are
ignored. Downloads are constrained to the configured repository, and
reported file size and SHA-256 are checked when supplied.

## Tests

The release pipeline runs unit tests and lint. For Android-independent
protocol, state and navigation checks, use:

```powershell
powershell -File tools/Test-SystemProtocols.ps1
```

This protocol runner complements the Android build and does not replace it.
