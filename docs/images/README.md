# README screenshots

These images are real app screens. Nothing here is a mock-up or an edited capture.

| File | Screen |
| --- | --- |
| `home.png` | Home screen. |
| `install-confirmation.png` | Confirmation screen for a blank drive, before installing. |
| `install-options.png` | Advanced options sheet. |

## Source

The optional screenshot check in the `Build and test` workflow
(`appium-tests/tests/test_store_screenshots.py`) captured them on the Bliss OS test virtual
machine with a blank virtual USB drive. The display is 1280x800, so they show the tablet
layout. No install was started.

- Run: [42697286012](https://github.com/xntsO/Vendroid/actions/runs/42697286012)
- Artifact: `qemu-diagnostics-preview-xhci`, folder `validation/store-screenshots/`
- Build: beta (`0.2.0-beta`), package `io.github.xntso.vendroid`
- Source: pull request 32 at `fb8e9282f2fa21008344d0e96b4da6082a1046b9`, tested as merge
  commit `b9b5cc52e697c0305e11b0fce5c227e40c961821` on `main` at `0b47633`

| File | SHA-256 |
| --- | --- |
| `home.png` | `94428860575484c67b68ca61acecc616f79afb36f079bdfc72c494c6610e383b` |
| `install-confirmation.png` | `2d78db84b8d9552dfc5b03470eed675358159780289a4ef2b65872a331e0e136` |
| `install-options.png` | `9800e97cae8bca30ca3de6bc2a63e53d0c874d2248d7e6a2310ae62233f0c8b0` |

## Updating them

Download the same artifact from a newer successful run and replace the files. Update the
run, commit, and hashes above.

Phone screenshots would suit the README better than the tablet layout. To add them, take
these on a phone in portrait and save them here with these names, then point the README at
them:

| File | Screen |
| --- | --- |
| `phone-home.png` | Home screen with **Install Ventoy**. |
| `phone-confirm.png` | Confirmation screen after granting USB access, showing the drive and the warning. |
| `phone-options.png` | **Advanced options** sheet. |
| `phone-progress.png` | Progress screen during an install. |
| `phone-success.png` | Success screen after the install. |
| `phone-update.png` | Confirmation screen for a drive that already has Ventoy, showing **Update** or **Repair**. |

Hide anything personal in the status bar first. Do not use mock-ups or edited captures.
