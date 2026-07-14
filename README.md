# Vendroid

Vendroid is an experimental, no-root Android app for installing Ventoy onto external USB drives and managing boot image files through Android's Storage Access Framework.

> [!CAUTION]
> Vendroid is alpha software. Installing or upgrading Ventoy rewrites partitioning and boot data on the selected USB drive. Back up the drive and verify that you selected the correct device before continuing.

The project adapts EtchDroid's Kotlin/Compose app and USB mass-storage layer. Vendroid-specific installation code lives under `app/src/main/java/io/github/xntso/vendroid/ventoy`.

## Current scope

- External USB Ventoy install and upgrade flows.
- MBR partition layout with an exFAT data partition.
- 512-byte logical USB block devices up to 2 TiB.
- Secure Boot support enabled by default.
- No root access or phone-as-USB gadget mode.
- No persistence-plugin or advanced theme/plugin editor yet.

## Ventoy payload

The app pins Ventoy `1.1.16`. Download the official Linux archive, verify it, and prepare the generated app assets:

```sh
mkdir -p .vendroid-cache
curl --fail --location --retry 3 \
  --output .vendroid-cache/ventoy-1.1.16-linux.tar.gz \
  https://github.com/ventoy/Ventoy/releases/download/v1.1.16/ventoy-1.1.16-linux.tar.gz
printf '%s  %s\n' \
  'a9ffd7bd5e26df486cafff924b8dbcb6caae20cbe2b179a009fe59ae740c7572' \
  '.vendroid-cache/ventoy-1.1.16-linux.tar.gz' | sha256sum --check --strict
./gradlew :app:prepareVentoyPayload
```

The Gradle task independently verifies the archive checksum and imports these files:

- `boot/boot.img`
- `boot/core.img.xz`
- `ventoy/ventoy.disk.img.xz`
- `ventoy/version`
- a generated `payload.manifest` containing file sizes and SHA-256 hashes

## Build and test

Vendroid uses Kotlin, Jetpack Compose, min SDK 23, target SDK 36, and package name `io.github.xntso.vendroid`.

```sh
./gradlew assembleFossDebug
./gradlew testFossDebugUnitTest
./gradlew lint
```

The GitHub Actions quality gate builds the app, runs unit tests and Android lint, then installs Ventoy on a QEMU-backed virtual USB drive and validates the resulting partition table and filesystem signature. Physical-device testing is still important because Android USB host implementations vary by vendor.

On ARM64 Linux hosts, Android Gradle Plugin's Maven-provided `aapt2` binary may be x86-64-only. Resource processing and APK assembly therefore require x86-64 emulation or a compatible `aapt2` binary.

## Credits and license

Vendroid is licensed under [GPL-3.0](LICENSE). It builds on [EtchDroid](https://github.com/EtchDroid/EtchDroid) and bundles selected files from [Ventoy](https://github.com/ventoy/Ventoy). See [Third-party notices](THIRD_PARTY_NOTICES.md) for details.

Contributions are welcome; read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request.
