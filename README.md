# Vendroid

Vendroid is a no-root Android app for installing Ventoy on external USB drives and managing boot image files from Android.

## Features

- Install, repair, and update an MBR Ventoy layout on a USB drive.
- Experiment with GPT installs in Vendroid beta.
- Keep multiple boot images on the drive's exFAT partition. Vendroid does not copy them; use a computer or a file manager that can open the drive.
- Bundle and verify a pinned official Ventoy payload.
- Optionally download a newer compatible Ventoy payload through Vendroid's update mirror,
  which republishes official releases, verify its SHA-256, and keep it in private app storage
  for future offline use.
- Work directly with USB mass-storage devices without root access.

## Current limitations

- External USB drives only; Android does not expose internal SD card slots as writable block devices.
- Stable builds support MBR only; Vendroid beta contains experimental GPT support.
- MBR installs reject drives larger than 2 TiB.
- The data partition uses exFAT.
- Secure Boot support is enabled by default.
- Phone-as-USB mode, persistence plugins, and advanced Ventoy theme/plugin editing are not included yet.

## Build

Vendroid currently has one `foss` product flavor. It requires Java 17 and the Android SDK.

The `fossPreview` build type is Vendroid beta. It shares the stable package
`io.github.xntso.vendroid` and launcher name, uses an optimized build, and adds `-beta`
to its version name. Beta replaces the installed stable app. The former separate
V-Preview package does not migrate automatically. See [Google Play publishing](docs/google-play.md)
for first-time setup, shared version codes, and automatic uploads from published GitHub releases.

The app bundles Ventoy `1.1.16`. CI rebuilds the pinned USB libraries for 16 KB pages
before Gradle resolves dependencies. These commands document the CI inputs; builds
and tests run in GitHub Actions:

```sh
mkdir -p .vendroid-cache
sdkmanager 'ndk;28.2.13676358' 'cmake;3.22.1'
python3 scripts/native/prepare.py --sdk-root "$ANDROID_HOME"
curl -fL -o .vendroid-cache/ventoy-1.1.16-linux.tar.gz \
  https://github.com/ventoy/Ventoy/releases/download/v1.1.16/ventoy-1.1.16-linux.tar.gz
./gradlew assembleFossDebug
```

Builds, unit tests, lint, and Appium/QEMU tests run in GitHub Actions. Signed release APKs require the repository signing secrets described in `AGENTS.md`.

## Upstream and license

Vendroid uses components and prior work from [Ventoy](https://github.com/ventoy/Ventoy) and [EtchDroid](https://github.com/EtchDroid/EtchDroid). Vendroid's source code is distributed under the [GNU GPLv3](LICENSE). Third-party components retain their respective licenses.
