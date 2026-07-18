# Project instructions: Vendroid

Vendroid is a no-root Android application for installing and managing Ventoy on external USB drives.

## Repository structure

- `app/src/main/` – Kotlin, Compose UI, USB storage, and Ventoy implementation.
- `app/src/foss/` – the only product-flavor override.
- `app/src/test/` – unit tests.
- `appium-tests/` – Python/Appium end-to-end tests, including QEMU USB tests.
- `fastlane/` – Android store metadata inherited from the project history.
- `.github/workflows/` – authoritative build and verification automation.

There is no GPlay flavor, Sentry build, donation site, or Vendroid website. Do not add links or instructions for those services unless the project owner explicitly enables them.

## Development conventions

- Keep history linear and rebase onto `main`; do not merge `main` into a branch.
- Keep commits atomic and use Conventional Commit messages.
- Separate cleanup from functional changes.
- Preserve unrelated working-tree changes.
- Keep Ventoy version changes synchronized between the Gradle payload configuration and GitHub Actions.
- Increment `versionCode` for every distributed APK. `versionName` is the user-visible release version.

## Verification

Do not run Gradle builds, lint, unit tests, or Appium/QEMU tests on this workstation. GitHub Actions is the authoritative verification environment and runs:

- FOSS debug and optimized builds.
- FOSS unit tests.
- Android lint.
- Appium tests in Bliss OS/QEMU.
- Signed release APK metadata and signature checks.

## Release signing

Release credentials must never be committed. GitHub Actions expects these secrets:

- `VENDROID_KEYSTORE_BASE64`
- `VENDROID_KEYSTORE_PASSWORD`
- `VENDROID_KEY_ALIAS`
- `VENDROID_KEY_PASSWORD`

Gradle receives the decoded keystore path through `VENDROID_KEYSTORE_PATH`. Use the same signing key for every release so Android can update existing installations.
