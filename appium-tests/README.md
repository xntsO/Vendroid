# Vendroid end-to-end tests

Vendroid uses Python, pytest, Appium, and QEMU to exercise the Android UI and USB mass-storage flows.

## Test layout

- `tests/test_vendroid_smoke.py` checks the Vendroid home screen and primary Ventoy action.
- `tests/test_qemu.py` covers QEMU USB hotplugging and Ventoy operations.
- `tests/test_generic.py` contains inherited raw-image tests marked `legacy_etchdroid`.

Pytest excludes `legacy_etchdroid` tests by default because they do not match the current Vendroid Ventoy flow.

## Continuous integration

GitHub Actions is the authoritative test environment. It:

1. Builds the optimized FOSS APK.
2. Restores or prepares a Bliss OS VM.
3. Starts QEMU with an emulated USB drive.
4. Installs the APK through ADB.
5. Runs the applicable pytest suite and uploads diagnostic output after failures.

## Requirements for another environment

- Android SDK and ADB.
- Python 3.12 or newer and [`uv`](https://docs.astral.sh/uv/).
- Node.js and npm for Appium.
- `7z`, `qemu-img`, and `qemu-system-x86_64` for VM tests.

Install Appium dependencies from this directory with `npm ci`. Prepare and start the VM with the scripts under `scripts/`.

> [!WARNING]
> Tests run against a physical device can completely erase the connected USB drive. The QEMU environment is preferred because its virtual drive is disposable.

If proxy variables are set, exclude the local Appium and ADB endpoints with `NO_PROXY=127.0.0.1,localhost,127.0.0.10`.
