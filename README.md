# Vendroid

Vendroid is a no-root Android app for installing Ventoy onto external USB drives and managing boot image files when Android exposes the Ventoy data partition through the Storage Access Framework.

This repository currently adapts EtchDroid's Kotlin/Compose Android app and USB mass-storage layer, with Vendroid-specific Ventoy installer code under `app/src/main/java/io/github/xntso/vendroid/ventoy`.

## Scope

- External USB Ventoy installer and manager.
- MBR-only MVP layout.
- exFAT data partition.
- 512-byte logical USB block devices only.
- Disks over 2 TiB are rejected.
- Secure Boot is enabled by default.
- Phone-as-USB, root gadget mode, persistence plugins, and advanced theme/plugin editing are out of scope for this phase.

## Ventoy Payload

The app bundles a pinned Ventoy payload, currently `1.1.16`.

Download the official Linux release archive and run:

```sh
mkdir -p .vendroid-cache
curl -fL -o .vendroid-cache/ventoy-1.1.16-linux.tar.gz \
  https://github.com/ventoy/Ventoy/releases/download/v1.1.16/ventoy-1.1.16-linux.tar.gz
./gradlew :app:prepareVentoyPayload
```

The Gradle task imports:

- `boot/boot.img`
- `boot/core.img.xz`
- `ventoy/ventoy.disk.img.xz`
- `ventoy/version`
- `payload.manifest` with file sizes and SHA-256 hashes

## Build

Vendroid keeps EtchDroid's Android stack: Kotlin, Compose, min SDK 23, target SDK 36, package `io.github.xntso.vendroid`.

```sh
./gradlew :app:assembleFossDebug
```

On ARM64 Linux hosts, Android Gradle Plugin's Maven-provided `aapt2` binary may be x86-64-only. In that case Kotlin compilation can pass, but resource processing, APK assembly, and unit-test tasks that process resources require an ARM64-compatible `aapt2` or x86-64 emulation.
