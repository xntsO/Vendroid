# Vendroid validation

The owner has one physical USB drive. Routine regression checks run in GitHub Actions; a three-drive phone/tablet matrix is no longer a release prerequisite. Results must distinguish virtual coverage from the hardware actually tested.

## One command

From the Release 2 checkout, with GitHub CLI signed in:

```powershell
.\scripts\verify.ps1 -Wait
```

This dispatches the build-and-test workflow on `codex/release-2-gpt-stabilization`, waits for its result, and prints the evidence download command. Pass `-Ref main` after merge, omit `-Wait` to return immediately, or use `-RunId 123456 -Wait` to follow an existing run. The selected remote branch must contain this workflow revision. The script does not push local changes, build locally, or access a physical USB drive.

Pull requests to main and main pushes run the same checks automatically. Tag signing remains separate.

## Automated evidence

- Android debug, optimized, and Preview builds, existing unit tests, and lint.
- Independent disk-image verifier tests, including invalid layout/CRC rejection.
- Stable MBR and Preview GPT with emulated UHCI and xHCI USB controllers.
- Installation with an explicit assertion of the requested partition style, exFAT label, allocation size, and reserved space.
- Primary and backup GPT CRCs, matching partition tables, disk identities, and partition boundaries.
- Ordinary nested files, including binary and Unicode-name files, hashed from the mounted exFAT partition before and after maintenance.
- USB detach and reattach, permission acquisition, and installed-version detection.
- Healthy repair and separate primary/backup GPT-header damage and repair.
- A real 1.1.15 payload followed by an app-driven update to bundled 1.1.16.
- A real 1.1.17 payload that the app must refuse to downgrade, with the entire small test image unchanged.
- SeaBIOS and OVMF boot of an ISO through the app-created Ventoy disk. Success requires a serial marker from inside that ISO, after installation and after update.
- A sparse 3 TiB drive: stable refuses it without writes; Preview installs GPT and preserves files through reconnection. This tests capacity arithmetic and high-LBA metadata, not real large-drive compatibility or every data sector.

The old/new payload archives are pinned by version and SHA-256 from the upstream release assets. Update fixtures deliberately when the bundled version changes. The boot ISO is built in CI from Ubuntu GRUB packages and is a chainloading probe, not proof that every operating-system ISO works. OVMF tests run without Secure Boot; Secure Boot lifecycle belongs to Release 3.

The disk image is detached from QEMU and its block backend released before host inspection, filesystem mounting, or intentional corruption. Helpers accept regular image files, not physical block devices. Firmware checks use disposable snapshot writes and no network.

## Read the result

Download the workflow artifacts. They retain APKs, unit/lint reports, JUnit, package metadata, APK checksums, the tested commit, lifecycle checkpoints, large-drive results, and boot serial logs for 30 days, including successful runs. A missing final `passed: true` or a missing expected report is not a pass. The workflow status and JUnit failures remain authoritative; earlier successful checkpoints do not override a later failure.

An intentionally skipped second large-drive controller case avoids repeating the same capacity test. Existing `legacy_etchdroid` tests remain excluded because they exercise the inherited raw-image flow. CI does not claim those interruption cases passed.

## Small physical release check

Use the available USB on the available Android device. Fresh installation erases that USB, so use a backed-up disposable test drive. Record its model, Android version, APK hash, and tested commit once per storage-changing release candidate.

1. Install MBR, copy a small ISO and some ordinary files, then reconnect and confirm detection.
2. Run repair and compare file hashes. Boot the ISO on the available computer if possible.
3. Repeat with GPT in V-Preview. Before stable promotion, repeat a GPT smoke check with the promoted stable package.

Reuse evidence for changes that do not affect storage, USB handling, payloads, or release packaging. Repeat affected checks after those areas change. UI wording changes do not require repeating the full physical sequence. An Android APK upgrade should also be checked against an existing signed installation when one is available.

If no boot-capable computer or prior signed APK is available, record that check as untested. Seek additional device reports from Preview users; do not describe the one-device result as universal compatibility. Drives above 2 TiB remain Preview-only until real large-drive evidence is available. Ordinary GPT promotion must have a separate capacity gate.

## Remaining work outside this automation change

The prior source review identified independent fixes: separate the stable GPT and large-capacity gates; handle unknown installed versions without silent downgrade; recover when the primary-header read fails; tolerate editable GPT names; and avoid overwriting preserved boot-core bytes during updates. Passing this suite does not close scenarios it does not inject. Stable GPT also needs its own optimized-package CI matrix entry when enabled. No merge, promotion, or publication is implied by a validation run.

References: [Ventoy control plugin](https://www.ventoy.net/en/plugin_control.html), [QEMU device removal](https://www.qemu.org/docs/master/interop/qemu-qmp-ref.html), [Ventoy 1.1.15](https://github.com/ventoy/Ventoy/releases/tag/v1.1.15), [Ventoy 1.1.17](https://github.com/ventoy/Ventoy/releases/tag/v1.1.17).
