# Project instructions: Vendroid

Vendroid is a no-root Android application for installing and upgrading Ventoy on external USB drives. Treat all block-device code as destructive and verify boundaries, alignment, and failure handling carefully.

## Project structure

- `app/src/main/` — Kotlin sources, Compose UI, and the Ventoy installer under `vendroid/ventoy/`.
- `app/src/foss/` — FOSS-flavor overrides.
- `app/src/test/` — JUnit 5 and Robolectric unit tests.
- `appium-tests/` — Python/Appium end-to-end tests, including QEMU virtual-USB tests.
- `fastlane/` — Android store metadata.

## Workflow

- Keep pull requests focused and commits concise. Conventional Commit messages are preferred.
- Rebase feature branches onto `main`; do not merge `main` into a feature branch.
- Preserve EtchDroid attribution when changing inherited code.
- Never add a release archive or generated payload assets to Git.

## Build and test

The official Ventoy Linux archive must exist at `.vendroid-cache/ventoy-1.1.16-linux.tar.gz`, or its location must be supplied with `-PventoyPayloadArchive=/path/to/archive`. Gradle verifies the pinned SHA-256 before extracting any payload.

Run:

```sh
./gradlew assembleFossDebug
./gradlew testFossDebugUnitTest
./gradlew lint
```

The first Gradle run can take several minutes. Do not use `--scan`.

## End-to-end tests

Appium tests require Android SDK tools, `uv`, Node/npm, QEMU/KVM, and the Bliss OS VM described in `appium-tests/README.md`.

```sh
cd appium-tests
npm ci
uv run pytest -sv
```

QEMU tests exercise both the inherited raw-image writer and Vendroid's Ventoy installer against a virtual USB disk. Tests against a physical device can erase the attached USB drive; identify it explicitly and use disposable media.

If proxy variables are set, exclude the local Appium endpoints:

```sh
NO_PROXY=127.0.0.1,localhost,127.0.0.10 \
no_proxy=127.0.0.1,localhost,127.0.0.10 \
uv run pytest -sv
```
