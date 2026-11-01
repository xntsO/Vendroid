# Vendroid validation

The owner has one physical USB drive. Routine regression checks run in GitHub Actions; a three-drive phone/tablet matrix is no longer a release prerequisite. Results must distinguish virtual coverage from the hardware actually tested.

## One command

From the checkout, with GitHub CLI signed in:

```powershell
.\scripts\verify.ps1 -Wait
```

This dispatches the build-and-test workflow on `main`, waits for its result, and prints the evidence download command. Before merge, pass `-Ref <pushed-branch>` to validate that branch. Omit `-Wait` to return immediately, or use `-RunId 123456 -Wait` to follow an existing run. The selected remote branch must contain this workflow revision. The script does not push local changes, build locally, or access a physical USB drive.

Pull requests to main and main pushes run the same checks automatically. Tag signing remains separate.

To build a signed GPT candidate after automatic validation passes, use:

```powershell
.\scripts\verify.ps1 -Ref <pushed-branch> -SignedCandidate -Wait
```

This requires the existing release-signing secrets and uploads an APK with checksums, package metadata, signer details, and the tested commit. It does not publish a release. The candidate uses the stable package `io.github.xntso.vendroid`, enables GPT, and keeps `IS_PREVIEW=false`, so it exercises the future stable path. Its name is `0.2.0-gpt-candidate`. CI uses debug signing for its Android lifecycle tests; the separately signed candidate is the APK for the physical check and upgrade check.

Candidate distribution consumes its versionCode. The first 0.2.0 candidate used code 30, following 0.1.2 code 29, the first `0.2.0-beta` used code 31, `0.2.0-beta.2` used code 32, and `0.2.0-beta.3` used code 33. A later distributed APK, including the final stable release, must use a greater code, and signed builds fail at or below `VENDROID_HIGHEST_DISTRIBUTED_CODE`. Record every candidate's actual metadata in the [Release 2 evidence record](release-evidence/0.2.0.md).

## Automated evidence

- Android debug, optimized, beta (the internal Preview build type), and GPT candidate builds, existing unit tests, and lint. The beta uses the same Vendroid package and an optimized non-debuggable build.
- Independent disk-image verifier tests, including invalid layout/CRC rejection.
- Fifty immediate virtual-USB reconnect cycles that verify explicit QEMU image-node cleanup before Android tests start.
- Stable MBR, Preview GPT, and GPT candidate with emulated UHCI and xHCI USB controllers. The candidate runs with the stable package and stable capacity gate.
- Installation with an explicit assertion of the requested partition style, exFAT label, allocation size, and reserved space.
- Primary and backup GPT CRCs, matching partition tables, disk identities, and partition boundaries.
- `fsck.exfat` from exfatprogs accepting the data partition before every host mount, independent of Vendroid's formatter and the FUSE driver. Volumes now use the recommended 5836-byte up-case table. Earlier Vendroid versions wrote a 128 KiB table, whose size overflows exfatprogs 1.2.2's checksum length and is falsely reported as corrupted.
- Ordinary nested files, including binary and Unicode-name files, hashed from the mounted exFAT partition before and after maintenance.
- USB detach and reattach, permission acquisition, and installed-version detection.
- Healthy repair and separate primary/backup GPT-header damage and repair.
- A real 1.1.15 payload followed by an app-driven update to bundled 1.1.16.
- A real 1.1.17 payload that the app must refuse to downgrade on two connections, with boot code, partition metadata, and ordinary file hashes preserved.
- SeaBIOS and OVMF boot of an ISO through the app-created Ventoy disk. Success requires a serial marker from inside that ISO, after installation, after each repair, and after update.
- A sparse 3 TiB drive: stable and the GPT candidate refuse it without Vendroid writes; Preview installs GPT and preserves files through reconnection. This tests capacity arithmetic and high-LBA metadata, not real large-drive compatibility or every data sector.

The old/new payload archives are pinned by version and SHA-256 from the upstream release assets. Update fixtures deliberately when the bundled version changes. The boot ISO is built in CI from Ubuntu GRUB packages and is a chainloading probe, not proof that every operating-system ISO works. OVMF tests run without Secure Boot; Secure Boot lifecycle belongs to Release 3.

The disk image is detached from QEMU and its block backend released before host inspection, filesystem mounting, or intentional corruption. Helpers accept regular image files, not physical block devices. Firmware checks use disposable snapshot writes and no network.

Android's normal storage services remain running. Bliss OS mounts GPT's VTOYEFI partition and runs `fsck_msdos`, so an entire-image hash can change independently of Vendroid. The downgrade gate checks the disabled action, newer version after reconnection, first MiB boot-region hash, partition metadata, and ordinary file hashes. Full-image hashes are retained as diagnostics. This does not prove zero I/O writes or unchanged VTOYEFI filesystem bytes. No ADB root or service shutdown is required.

## Google Play and native libraries

Language regression fixtures use separate Android API 23, 32, and 35 test classes for persisted Automatic/English/Arabic selection, primary-device-language detection, English fallback, resource strings, RTL, and notification wording. API 32 and 35 activity fixtures cover locale changes during progress and retained completion after recreation. The Appium language case switches the actual UI to Arabic, checks mirrored placement, relaunches without clearing data, then restores Automatic before subsequent USB tests. These cases do not require physical USB access.

See [Google Play publishing](google-play.md) for bundle preparation, signing enrollment, track selection, and first-listing requirements. CI rebuilds the pinned libaums JNI and libusb libraries with NDK 28 for 16 KB page compatibility before Gradle runs. Every packaged APK and Play AAB must pass the independent native-library checker. The checker fixtures cover malformed ELF files and insufficient alignment; publishing fixtures cover release routing, version-code collisions, metadata rejection, and failed API edits. These checks supplement the six USB lifecycle cases.

Play distribution reuses this full workflow on the same source revision before accessing signing credentials. Beta and production share one package, signer, and increasing version-code sequence; historical evidence for the former separate V-Preview remains historical.

The beta xHCI lifecycle optionally records its existing initial install for the Play connected-device foreground-service declaration. CI uses Appium's live MJPEG screenshot stream and host FFmpeg because the Android AVC encoder crashes in this Bliss VM. A separate CI fixture sends Appium's multipart wire format through the real recorder and verifies the resulting MP4. Evidence is saved under `validation/foreground-service-demo/`: the MP4, its hash and duration, observed UI milestones, the active WorkerService/notification dump, and the installed-success screenshot and UI XML. `recording.json` identifies virtual hardware and any capture gaps. Recorder or evidence errors do not change the required USB test result. Review the actual video before using it in a declaration; an unavailable or truncated recording needs a replacement demonstration.

## Read the result

Download the workflow artifacts. They retain APKs, unit/lint reports, JUnit, package metadata, APK checksums, the tested commit, lifecycle checkpoints, large-drive results, and boot serial logs for 30 days, including successful runs. Keep the release evidence before artifacts expire. A missing final `passed: true` or a missing expected report is not a pass. The workflow status and JUnit failures remain authoritative; earlier successful checkpoints do not override a later failure. All six lifecycle cases must report the expected package and partition style. A skipped signed-candidate job on a normal PR is expected; an explicitly requested signed-candidate job must pass.

Failed Android tests also attempt to save a screenshot and UI XML under `validation/failure-screens/`. Firmware failures save a QEMU screen capture when available. Logs remain available if screen capture fails.

An intentionally skipped second large-drive controller case avoids repeating the same capacity test. Existing `legacy_etchdroid` tests remain excluded because they exercise the inherited raw-image flow. CI does not claim those interruption cases passed.

## Small physical release check

Use the available USB on the available Android device. Fresh installation erases that USB, so use a backed-up disposable test drive. Record its model, Android version, APK hash, signer fingerprint, and tested commit once per storage-changing release candidate in the [Release 2 evidence record](release-evidence/0.2.0.md). Pending checkboxes are requests for evidence, not test results.

1. Install MBR, copy a small ISO and some ordinary files, then reconnect and confirm detection.
2. Run repair and compare file hashes. Boot the ISO on the available computer if possible.
3. Repeat with GPT in the signed GPT candidate. Confirm the version is the signed GPT candidate, rather than the beta: both now use the same Vendroid package. Before release publication, repeat a GPT smoke check with the final signed stable APK after promotion.

Reuse evidence for changes that do not affect storage, USB handling, payloads, or release packaging. Repeat affected checks after those areas change. UI wording changes do not require repeating the full physical sequence. Check an Android APK upgrade against the existing signed 0.1.2 installation when available, without uninstalling it first. Record that app launch and USB detection still work after the upgrade.

If no boot-capable computer or prior signed APK is available, record that check as untested. Seek additional device reports from Preview users; do not describe the one-device result as universal compatibility. Drives above 2 TiB remain Preview-only until real large-drive evidence is available. Ordinary GPT promotion must have a separate capacity gate.

## Signing continuity

The known 0.1.2 APKs use package `io.github.xntso.vendroid`, versionCode 29, and one signer with certificate SHA-256 `b388ad9d2290967480630cbd4a48f4f8c6784305c05da3541ad780821d9fbeb1`. The [evidence record](release-evidence/0.2.0.md) identifies two independently checked Actions artifacts and their APK hashes. This public certificate fingerprint is safe to commit. Keystores, passwords, aliases used as credentials, and secret values remain outside the repository.

Every signed candidate and release must pass `apksigner verify`, match that certificate fingerprint, and report the expected package, versionName, and versionCode. A valid signature alone does not prove continuity with an installed APK. The workflow must fail if the fingerprint changes. Preserve the old keystore through the existing `VENDROID_KEYSTORE_BASE64`, `VENDROID_KEYSTORE_PASSWORD`, `VENDROID_KEY_ALIAS`, and `VENDROID_KEY_PASSWORD` secrets; Gradle receives its decoded path through `VENDROID_KEYSTORE_PATH`.

The final signed stable APK, the owner's promotion decision, and the final physical smoke result remain required before publication. After promotion and a versionCode increment, pushing the version tag validates and signs the final APK and creates a draft GitHub release. Download the APK from that draft or its Actions artifact, complete and record the final physical smoke check, then publish the draft explicitly. A tag push does not publish it automatically. The artifact-only GPT candidate does not satisfy those final steps. Its signature and metadata evidence establish packaging and signing continuity, not a hardware pass.

## Coverage for upcoming releases

When implementing a phase, add its validation to the existing PR workflow in the same change. Keep earlier lifecycle checks running. Record the tested commit, expected outcomes, and supporting artifacts; a new feature is not validated merely because the existing suite passes. Update pinned payload fixtures when the bundled version changes, and test the stable package when a Preview feature is promoted.

| Release | Required additions to automated validation |
| --- | --- |
| 2: GPT | Cover scanner, downgrade, interrupted-update, and capacity regressions listed below. Validate the GPT candidate in the stable package before promotion, then validate the promoted optimized stable package and final signed APK. |
| 3: Secure Boot and Clear Ventoy | Test enabled and disabled Secure Boot states, detection, update and repair preservation, and boot with Secure Boot enforcement. Test Clear Ventoy recognition and confirmation, refusal of unsupported layouts, and both full-drive exFAT and unallocated outcomes. |
| 4: FAT32, NTFS, and UDF | Independently check each formatter's output, mountability, file copying, update preservation, and BIOS/UEFI boot. Exercise format selection, allocation settings, and FAT32 file-size limits. Gate each format on its own results. |
| 5: Non-destructive installation and shrinking | Start with read-only eligibility and installation using existing trailing free space. Test unhealthy and unsupported layouts, file hashes, write boundaries, cache synchronization, interrupted writes, disconnects, durable recovery, and Android battery/wake/cancellation behavior. Add filesystem-checker comparisons and fuzzing. Validate each shrinking engine separately as it is introduced. |

Release 3 retains the agreed check on two different real UEFI firmware implementations, including enrollment and reboot behavior. A second tester can supply the additional firmware evidence; one USB can be reused. Emulation does not satisfy that hardware gate. Release 5 custom shrinking engines also require review by a human filesystem or storage expert. Record outstanding evidence and keep the affected feature in Preview until its gate is met.

## Storage regressions and release limits

The initial 3 TiB checks reproduced a zero-capacity error in libaums 0.10.0. Vendroid now uses an adapted SCSI driver with unsigned READ CAPACITY(10), READ CAPACITY(16), true block counts, and READ(16)/WRITE(16) for high or boundary-crossing addresses. Transfers use bounded zero-offset buffers because libusbcommunication 0.3.0 ignores sliced buffer array offsets. Driver tests exercise serialized USB commands, capacity boundaries, high-address preservation, and transfer buffers. The full 3 TiB Appium cases remain required; use the current revision's CI result as evidence, not this implementation description. Sources: [upstream driver](https://github.com/magnusja/libaums/blob/af89120aa434ccd97b985a6d66420c1b7e30a1ad/libaums/src/main/java/me/jahnen/libaums/core/driver/scsi/ScsiBlockDevice.kt), [pinned USB transport source](https://repo.maven.apache.org/maven2/me/jahnen/libaums/libusbcommunication/0.3.0/libusbcommunication-0.3.0-sources.jar).

Focused regression tests also cover primary-header read failure with a valid GPT backup, editable GPT names, refusal to replace an unknown-version payload, metadata-only repair for unknown/newer GPT versions, and preserving sectors 2040 through 2047 by excluding them from update writes. Interrupted updates can still damage other boot or payload regions; these tests do not establish general power-loss recovery.

Further regression tests cover exFAT sizes whose cluster count would otherwise oscillate forever, a SCSI SYNCHRONIZE CACHE after the last write and before verification on install, update, and metadata repair, waiting up to one minute for a slow flush without a reset, tolerance of devices that report the optional command as unsupported, refusal of an unusable payload before any write, and refusal of raw image jobs at the worker. These tests exercise the serialized commands and their order. Emulated USB has no real volatile cache, so they do not prove that a physical drive's data survives unplugging. A device that rejects the command also gives no cache confirmation. Record that gap with the hardware evidence.

Stable GPT and large-capacity permissions are separate. Stable GPT remains disabled, and stable and the GPT candidate refuse drives above 2 TiB. Installation exposes the partition-style selector before Advanced options, with MBR Recommended as the default. The candidate allows ordinary GPT validation while retaining the stable capacity gate. When stable GPT is enabled, validate GPT in the optimized stable package and record the final physical promotion checks. No stable promotion or release publication is implied by a passing validation run.

References: [Ventoy control plugin](https://www.ventoy.net/en/plugin_control.html), [QEMU device removal](https://www.qemu.org/docs/master/interop/qemu-qmp-ref.html), [Ventoy 1.1.15](https://github.com/ventoy/Ventoy/releases/tag/v1.1.15), [Ventoy 1.1.17](https://github.com/ventoy/Ventoy/releases/tag/v1.1.17).
