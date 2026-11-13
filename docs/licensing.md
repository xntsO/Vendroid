# Licensing and source availability

Vendroid is GPL-3.0 software that also distributes other people's programs: the Ventoy boot files it writes to a USB drive, and the libraries inside the app. This page records what is distributed, where its license text and source are, how CI checks that, and what a lawyer still needs to decide. It is an engineering record, not legal advice.

## Bundled Ventoy

The APK carries boot files from the official Ventoy Linux release pinned in `app/build.gradle.kts`: `boot/boot.img`, `boot/core.img.xz`, and `ventoy/ventoy.disk.img.xz`. Vendroid does not modify them. That release's tarball contains no license texts, so Vendroid supplies them.

| Item | Value |
| --- | --- |
| Bundled version | Ventoy 1.1.18 |
| Source tag | https://github.com/ventoy/Ventoy/tree/v1.1.18 |
| Source commit | https://github.com/ventoy/Ventoy/commit/6116894ab8abdf3ef8d43411a6732d34ee19214e |
| Source archive | https://github.com/ventoy/Ventoy/archive/refs/tags/v1.1.18.tar.gz |

[`third_party/ventoy/`](../third_party/ventoy) holds three things for exactly that release:

- `License/`: an unmodified copy of the `License` folder of the Ventoy source tree at that commit. A `.gitattributes` rule keeps its bytes unchanged.
- `NOTICE.txt`: Vendroid's notice with the tag, commit, and BusyBox source links.
- `source-lock.json`: the tag, commit, source archive checksum, a SHA-256 for every license file, and the BusyBox identification below.

Gradle packages that folder as `assets/ventoy_licenses/` in every APK and bundle. The About screen names the bundled version and links to the commit.

### Where the source goes

- **GitHub releases.** The `Verify third-party source for releases` job downloads the Ventoy source archive and the BusyBox archives, checks them, and keeps them as the `vendroid-third-party-source` artifact. The tag workflow attaches those files to the draft stable release. The Google Play workflow attaches them to each beta prerelease. A release is not created without them.
- **Update mirror.** `scripts/mirror/sync.py` publishes `ventoy-<version>-source.tar.gz` and the BusyBox archives for a Ventoy release before it advertises that release. Each version keeps its own list at `https://updates.vendroid.fhas.sa/v1/ventoy/source/<version>.json`. Nothing is deleted when a newer release arrives.
- **Texts.** The README, the store description, the release notes, and `NOTICE.txt` give the tag and commit.

### BusyBox

Ventoy's `License/license-busybox.txt` says Ventoy uses BusyBox unmodified and ships only binaries. The Ventoy repository has the build settings but not the BusyBox source ([ventoy/Ventoy#3224](https://github.com/ventoy/Ventoy/issues/3224)). The lock therefore pins the upstream source of each BusyBox release found in the payload.

| BusyBox | Files in the Ventoy tree | How the version was identified |
| --- | --- | --- |
| 1.27.1 | `IMG/cpio_x86/ventoy/busybox/ash` | Ventoy's `DOC/BuildVentoyFromSource.txt` 5.4, with a matching SHA-256 |
| 1.28.1 | `IMG/cpio_x86/ventoy/busybox/busybox32.xz` | Version string in the binary |
| 1.30.0 | `IMG/cpio_x86/ventoy/tool/ar`, `inotifyd`; `INSTALL/tool/i386/hexdump`, `xzcat` | Ventoy's document 5.2, 5.3, 5.6, 5.7, with matching SHA-256 |
| 1.31.0 | `IMG/cpio_x86/ventoy/busybox/busybox64.xz`; `INSTALL/tool/i386/ash` | Version string; Ventoy's document 5.11 |
| 1.32.0 | aarch64 and mips64el BusyBox, `64h`, `a64`, `m64`, the `xzcat` builds, `INSTALL/tool/{aarch64,mips64el,x86_64}` | Version string where present; otherwise Ventoy's `BUSYBOX/build.txt` and document 4.18 |

The `INSTALL/tool` files are in the Linux tarball that the mirror serves. They are not in the APK. On 2026-11-10 the `IMG/cpio_*/ventoy/busybox` binaries were found byte for byte inside the payload of the signed `0.2.0-beta.3` APK. `tool/ar` and `tool/inotifyd` were not found as plain byte runs there, so their presence in the APK payload is unconfirmed. The Ventoy 1.1.18 tree has the same binaries.

Checksums in the lock for BusyBox 1.30.0, 1.31.0 and 1.32.0 equal the `.sha256` files on busybox.net. busybox.net publishes no `.sha256` for 1.27.1 and 1.28.1, so those two values are what the site served on 2026-11-10.

## What CI checks

- `scripts/licenses/test_licenses.py` fails if the vendored License folder differs from the lock, if Gradle or either workflow pins another Ventoy version, or if the README, this page, the store description, or the current release notes stop naming the locked tag and commit.
- `scripts/licenses/verify_apk.py` runs on every built APK, the signed APK, and the Play bundle. It requires every vendored file under `assets/ventoy_licenses/`, byte for byte, and a bundled payload of the locked version.
- `scripts/licenses/prepare_sources.py` requires the pinned SHA-256 of each archive, the pinned commit recorded inside the Ventoy archive, a `License` folder identical to the vendored one, and the identified BusyBox binaries. A new file beside BusyBox fails the job until someone identifies it.
- A unit test reads the packaged assets. `AboutCreditsTest` checks that the About line links the word "GitHub" to the locked commit, and the Appium smoke test checks the line names the bundled version.

## Changing the bundled Ventoy version

Change these together, in one pull request:

1. `pinnedVentoyVersion` in `app/build.gradle.kts` and `VENTOY_VERSION` in both workflows.
2. `third_party/ventoy/License/` from the new tag, and `source-lock.json`: version, tag, commit, archive size and SHA-256, and license checksums.
3. The version, tag, and commit in `third_party/ventoy/NOTICE.txt`, the README, the store description, this page, and the release notes.
4. If `prepare_sources.py` reports a changed or new BusyBox binary, identify its version first, then update the lock and the archive list.

## Deploying the mirror change

The canonical handler in `services/ventoy-mirror/` refuses to advertise a release whose source list is missing. The sync publishes that list first. Deploy the handler before this sync runs. With the old handler the sync fails at the first source upload and leaves the mirror unchanged, and every scheduled run fails until the handler is deployed. After deployment, the first run adds the source for the release that is already advertised.

Before shipping an APK whose notice names the mirror's source list, read `https://updates.vendroid.fhas.sa/v1/ventoy/source/<version>.json` for the advertised version and download one archive from it.

The Ventoy source archive is about 81 MB. Cloudflare's 100 MB upload limit on Free and Pro plans leaves little room. A larger future archive would fail the sync without changing the mirror.

## Vendroid and EtchDroid

Vendroid is a modified version of EtchDroid, © Davide Depau and contributors, GPL-3.0. The repository history starts at the bootstrap commit `092104e` of 2026-07-04, which was made from EtchDroid commit `e2eda57136d46fd3c465dee2e5ab31cdcb019a6e` of 2026-07-03. That commit was EtchDroid's `main` from 2026-07-03 until 2026-11-04, and the bootstrap tree shares more files with it than with any earlier commit; the files that separate it from the previous commit are that day's translation updates.

[NOTICE](../NOTICE), the README and About credit EtchDroid, name that commit and the date modifications began, and state the no-warranty terms. The two mass-storage files adapted from libaums, `VendroidScsiBlockDevice.kt` and `VendroidUsbMassStorageDevice.kt`, say in their headers what EtchDroid and Vendroid changed, as Apache-2.0 section 4(b) asks. `scripts/licenses/test_notices.py` keeps these notices, the commit and the vendored license texts in place.

## Notices in the app

About has an **Open-source licenses** button. Its dialog shows, offline, Vendroid's `NOTICE`, the GPL-3.0 text from `LICENSE`, the LGPL-2.1 and Apache-2.0 texts from `third_party/licenses/`, the Ventoy notice, and every file of Ventoy's License folder. Gradle's `prepareOpenSourceNotices` task packages the first four under `assets/licenses/`, and `scripts/licenses/verify_apk.py` requires them in every APK and bundle, byte for byte, with no other files there.

`verifyDependencyLicenses` runs in CI and fails when the release runtime classpath gains a Maven group that `third_party/licenses/dependency-groups.txt` does not list. To add a dependency, find its license, add its group there, and add its notice to `NOTICE`.

## Open questions for a lawyer

These are not resolved by the changes above.

1. **Completeness of the Ventoy archive.** Ventoy's repository holds its own code, its patches, and prebuilt binaries. Its build document downloads other source separately: GRUB 2.04 (`https://ftp.gnu.org/gnu/grub/grub-2.04.tar.xz`), EDK II `edk2-stable201911`, exfat 1.3.0, libfuse 2.9.9, and the C libraries dietlibc 0.34 and musl 1.2.1. Vendroid attaches the Ventoy archive and BusyBox only. Whether GPL-3.0 section 6 needs the GRUB 2.04 tarball and the others beside it is undecided. They could be pinned the same way as BusyBox.
2. **Binaries Ventoy takes from other projects.** `memdisk` comes from syslinux 6.03 (GPL-2.0-or-later). The Secure Boot loaders `BOOTX64.EFI`, `mmx64.efi`, `BOOTIA32.EFI`, `grubia32.efi` and `mmia32.efi` come from a Rocky Linux ISO and from Super-UEFIinSecureBoot-Disk. ImDisk and 7-Zip binaries come from their projects. Ventoy's License folder has no entry for syslinux or for shim and MokManager, and Vendroid does not host their source.
3. **BusyBox build inputs.** Four of the BusyBox binaries are upstream prebuilt files from busybox.net. Their exact configuration and toolchain are not published with them. Ventoy's own builds have their `.config` files in the Ventoy tree. `xzcat32_musl` has no build note. GPL-2.0 section 3 asks for the scripts that control compilation.
4. **Statically linked C libraries.** The BusyBox and tool binaries are linked statically with uClibc (LGPL-2.1), musl (MIT), or dietlibc (GPL-2.0). No source or relinking material for those libraries is provided, and Ventoy's License folder does not list them.
5. **Ventoy's logo.** `License/license-logo.txt` says the Ventoy logo is under Creative Commons Attribution-NonCommercial-ShareAlike 3.0. Whether the boot files display it, and whether a non-commercial term matters for a free app, needs an answer.
6. **LGPL texts.** Ventoy's folder has `lgpl-3.0.txt` only, while 7-Zip states LGPL 2.1 or later and device-mapper refers to LGPL 2.1. Vendroid keeps the folder unmodified and adds no LGPL 2.1 text for those components.
7. **Earlier releases.** `0.1.x` and the `0.2.0` betas up to `0.2.0-beta.3` were distributed without these texts and without the source beside them. Whether to add the source to those GitHub releases or withdraw their APKs is the owner's decision.
8. **Mirror history.** The mirror publishes source for the release it advertises from now on. A release that was mirrored earlier and has since been replaced has no source list unless someone backfills it.
9. **Google Play.** Play delivers the APK without attachments. The source offer there rests on the in-app notice and the store description. Whether that satisfies GPL-3.0 section 6(d) for a network download from a different server is a legal question.
