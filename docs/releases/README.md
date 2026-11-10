# Release notes

Each stable release has a file here named after its version, such as `0.2.0.md`. The tag workflow uses that file as the body of the draft GitHub release. Beta prereleases are written by hand and follow the same outline.

## Outline

1. A heading with the version.
2. What changed, for the people who use the app.
3. What to be careful about, such as erasing the drive.
4. What was and was not tested.
5. The third-party source section below.

## Third-party source section

Every release that carries an APK must include this section, stable or beta. Copy it and keep the version, tag, and commit equal to [`third_party/ventoy/source-lock.json`](../../third_party/ventoy/source-lock.json). CI fails when the notes for the checked-in app version name another tag or commit.

```markdown
## Third-party source

This release bundles the unmodified boot files of Ventoy 1.1.16, which is GPLv3 software. Their source is tag [`v1.1.16`](https://github.com/ventoy/Ventoy/tree/v1.1.16), commit [`2f66a309e4a3f0e08953aabfb0f1cf7e12b39f5d`](https://github.com/ventoy/Ventoy/commit/2f66a309e4a3f0e08953aabfb0f1cf7e12b39f5d), of the Ventoy repository.

The assets below include a copy of that source as `ventoy-1.1.16-source.tar.gz`, Ventoy's license texts as `ventoy-1.1.16-licenses.zip`, and the BusyBox source that Ventoy's binaries come from as `busybox-*.tar.bz2`. `THIRD-PARTY-SOURCES.md` explains each file, and `third-party-sources.sha256` lists their checksums. The same license texts are inside the app.
```

The workflows attach those files. The stable tag workflow adds them to the draft release, and the Google Play workflow adds them to a published prerelease together with the beta APK. Check that they are present before announcing a release. See [licensing](../licensing.md) for what they cover.
