# Vendroid

Vendroid is a no-root Android app for installing Ventoy on external USB drives and managing boot image files from Android.

## Features

- Install, repair, and update an MBR Ventoy layout on a USB drive.
- Manage supported boot image files through Android's Storage Access Framework.
- Bundle and verify a pinned official Ventoy payload.
- Work directly with USB mass-storage devices without root access.

## Current limitations

- External USB drives only; Android does not expose internal SD card slots as writable block devices.
- MBR layout and 512-byte logical sectors only.
- Drives larger than 2 TiB are rejected.
- The data partition uses exFAT.
- Secure Boot support is enabled by default.
- Phone-as-USB mode, persistence plugins, and advanced Ventoy theme/plugin editing are not included yet.

## Build

Vendroid currently has one `foss` product flavor. It requires Java 17 and the Android SDK.

The app bundles Ventoy `1.1.16`. Download the official Linux archive before building:

```sh
mkdir -p .vendroid-cache
curl -fL -o .vendroid-cache/ventoy-1.1.16-linux.tar.gz \
  https://github.com/ventoy/Ventoy/releases/download/v1.1.16/ventoy-1.1.16-linux.tar.gz
./gradlew assembleFossDebug
```

Builds, unit tests, lint, and Appium/QEMU tests run in GitHub Actions. Signed release APKs require the repository signing secrets described in `AGENTS.md`.

## Upstream and license

Vendroid uses components and prior work from [Ventoy](https://github.com/ventoy/Ventoy) and [EtchDroid](https://github.com/EtchDroid/EtchDroid). Vendroid's source code is distributed under the [GNU GPLv3](LICENSE). Third-party components retain their respective licenses.
