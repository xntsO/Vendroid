import copy
import gzip
import hashlib
import io
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile
import unittest
from urllib.error import URLError
import zipfile

from prepare_sources import (
    GUIDE_NAME, SUMS_NAME, download_with_retries, open_pinned, prepare, verify_ventoy_tree,
)
from ventoy_lock import (
    ASSET_ROOT, REQUIRED_LICENSES, ROOT, VENDOR_DIRECTORY, LicenseCheckError, check_notices, check_pins,
    commit_url, load_lock, packaged_files, tag_url, validate_lock,
)
from verify_apk import inspect_artifact


VERSION = "9.9.9"
COMMIT = "c" * 40
LICENSES = {name: f"terms in {name}\r\n".encode() for name in sorted(REQUIRED_LICENSES)}
BINARIES = {"IMG/cpio_x86/ventoy/busybox/busybox64.xz": (b"full busybox", "1.31.0"),
            "INSTALL/tool/i386/ash": (b"ash applet", "1.27.1")}
OTHER = {"IMG/cpio_x86/ventoy/busybox/vtchmod64": b"ventoy chmod"}
BUSYBOX = {"1.27.1": b"busybox 1.27.1 source", "1.31.0": b"busybox 1.31.0 source"}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def ventoy_archive(*, commit=COMMIT, licenses=LICENSES, binaries=None, other=OTHER, extra=None, root=None):
    files = {f"License/{name}": data for name, data in licenses.items()}
    files.update({path: data for path, (data, _) in (BINARIES if binaries is None else binaries).items()})
    files.update(other)
    files.update(extra or {})
    root = root or f"Ventoy-{VERSION}"
    buffer = io.BytesIO()
    # A fixed gzip timestamp keeps equal trees byte-identical, as the pinned hash requires.
    with gzip.GzipFile(fileobj=buffer, mode="wb", mtime=0) as compressed, \
            tarfile.open(fileobj=compressed, mode="w", format=tarfile.PAX_FORMAT,
                         pax_headers={"comment": commit}) as archive:
        directory = tarfile.TarInfo(root)
        directory.type = tarfile.DIRTYPE
        archive.addfile(directory)
        for path, data in sorted(files.items()):
            info = tarfile.TarInfo(f"{root}/{path}")
            info.size = len(data)
            archive.addfile(info, io.BytesIO(data))
    return buffer.getvalue()


def pinned(name, url, data):
    return {"name": name, "url": url, "size": len(data), "sha256": digest(data)}


def fixture(directory, source=None):
    """Write a vendored folder and return its lock with the bytes each pinned URL serves."""
    source = ventoy_archive() if source is None else source
    vendor = Path(directory) / "third_party/ventoy"
    (vendor / "License").mkdir(parents=True)
    for name, data in LICENSES.items():
        (vendor / "License" / name).write_bytes(data)
    lock = {
        "schema": 1, "version": VERSION, "tag": f"v{VERSION}", "commit": COMMIT,
        "source_archive": pinned(f"ventoy-{VERSION}-source.tar.gz",
                                 f"https://github.com/ventoy/Ventoy/archive/refs/tags/v{VERSION}.tar.gz", source),
        "license_files": {name: digest(data) for name, data in LICENSES.items()},
        "busybox": {
            "sources": {release: pinned(f"busybox-{release}.tar.bz2",
                                        f"https://busybox.net/downloads/busybox-{release}.tar.bz2", data)
                        for release, data in BUSYBOX.items()},
            "binaries": {path: {"sha256": digest(data), "version": release, "evidence": "version-string"}
                         for path, (data, release) in BINARIES.items()},
            "other_tools": sorted(OTHER),
        },
    }
    write_lock(vendor, lock)
    responses = {lock["source_archive"]["url"]: source}
    responses.update({lock["busybox"]["sources"][release]["url"]: data for release, data in BUSYBOX.items()})
    return vendor, lock, responses


def write_lock(vendor, lock):
    notice = "\n".join([f"Ventoy {lock['version']}", tag_url(lock), commit_url(lock), lock["source_archive"]["url"],
                        lock["source_archive"]["name"]] +
                       [archive["url"] for archive in lock["busybox"]["sources"].values()]) + "\n"
    (vendor / "NOTICE.txt").write_text(notice, encoding="utf-8")
    (vendor / "source-lock.json").write_text(json.dumps(lock), encoding="utf-8")


def serve(responses):
    return lambda url: io.BytesIO(responses[url])


def artifact(path, vendor, *, version=VERSION, drop=None, change=None, extra=None):
    lock = load_lock(vendor)
    assets = "assets/" if path.suffix == ".apk" else "base/assets/"
    with zipfile.ZipFile(path, "w") as archive:
        for name, data in packaged_files(lock, vendor).items():
            if name == drop:
                continue
            archive.writestr(f"{assets}{ASSET_ROOT}/{name}", data + b"!" if name == change else data)
        if extra:
            archive.writestr(f"{assets}{ASSET_ROOT}/{extra}", b"unexpected")
        if version is not None:
            archive.writestr(f"{assets}ventoy_payload/ventoy/version", f"{version}\n")


class Response(io.BytesIO):
    def __init__(self, body=b"", *, status=200, headers=None):
        super().__init__(body)
        self.status = status
        self.headers = headers or {}


class Opener:
    def __init__(self, responses):
        self.responses = list(responses)
        self.urls = []

    def open(self, request, timeout):
        self.urls.append(request.full_url)
        return self.responses.pop(0)


class RepositoryPins(unittest.TestCase):
    def test_vendored_license_folder_matches_the_lock_and_names_its_source(self):
        lock = load_lock()
        files = packaged_files(lock)
        self.assertEqual(len(files), len(lock["license_files"]) + 2)
        self.assertGreaterEqual(len(lock["license_files"]), 40)
        self.assertIn(b"GNU GENERAL PUBLIC LICENSE", files["License/gpl-3.0.txt"])
        self.assertIn(b"BusyBox", files["License/license-busybox.txt"])

    def test_gradle_and_both_workflows_bundle_the_locked_version(self):
        check_pins(load_lock())

    def test_lock_keeps_the_layout_that_gradle_reads(self):
        # app/build.gradle.kts reads the top-level version and commit by their two-space indentation.
        text = (VENDOR_DIRECTORY / "source-lock.json").read_text(encoding="utf-8")
        lock = load_lock()
        self.assertEqual(text, json.dumps(lock, indent=2) + "\n")
        self.assertEqual(text.count(f'\n  "version": "{lock["version"]}",\n'), 1)
        self.assertEqual(text.count(f'\n  "commit": "{lock["commit"]}",\n'), 1)

    def test_public_texts_give_the_exact_tag_and_commit(self):
        check_notices(load_lock())

    def test_vendored_bytes_are_exempt_from_line_ending_conversion(self):
        attributes = (ROOT / ".gitattributes").read_text(encoding="utf-8").splitlines()
        self.assertIn("third_party/ventoy/** -text", attributes)


class LockFixtures(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.vendor, self.lock, self.responses = fixture(self.directory.name)

    def test_fixture_lock_and_folder_are_accepted(self):
        self.assertEqual(set(packaged_files(load_lock(self.vendor), self.vendor)),
                         {f"License/{name}" for name in LICENSES} | {"NOTICE.txt", "source-lock.json"})

    def test_lock_cannot_point_at_other_tags_hosts_or_unpinned_busybox(self):
        def changed(edit):
            lock = copy.deepcopy(self.lock)
            edit(lock)
            return lock

        binary = next(iter(BINARIES))
        cases = {
            "tag": lambda lock: lock.update(tag="v9.9.8"),
            "commit": lambda lock: lock.update(commit="main"),
            "fork": lambda lock: lock["source_archive"].update(url="https://github.com/fork/Ventoy/archive/x.tar.gz"),
            "digest": lambda lock: lock["source_archive"].update(sha256="A" * 64),
            "size": lambda lock: lock["source_archive"].update(size=True),
            "license": lambda lock: lock["license_files"].pop("gpl-2.0.txt"),
            "traversal": lambda lock: lock["license_files"].update({"../LICENSE.txt": "a" * 64}),
            "busybox-host": lambda lock: lock["busybox"]["sources"]["1.31.0"].update(url="https://example.com/b.tar.bz2"),
            "unpinned": lambda lock: lock["busybox"]["binaries"][binary].update(version="1.36.1"),
            "evidence": lambda lock: lock["busybox"]["binaries"][binary].update(evidence="guess"),
            "unused-source": lambda lock: lock["busybox"]["binaries"].pop("INSTALL/tool/i386/ash"),
            "extra-field": lambda lock: lock.update(note="unreviewed"),
        }
        for name, edit in cases.items():
            with self.subTest(name=name), self.assertRaises(LicenseCheckError):
                validate_lock(changed(edit))

    def test_changed_missing_or_added_vendored_files_are_rejected(self):
        edits = {
            # The same text with converted line endings is no longer the upstream file.
            "changed": lambda vendor: (vendor / "License/gpl-3.0.txt").write_bytes(b"terms in gpl-3.0.txt\n"),
            "missing": lambda vendor: (vendor / "License/license-busybox.txt").unlink(),
            "added": lambda vendor: (vendor / "License/license-new.txt").write_bytes(b"new"),
            "stray": lambda vendor: (vendor / "README.md").write_bytes(b"stray"),
        }
        for name, edit in edits.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                vendor = Path(directory) / "ventoy"
                shutil.copytree(self.vendor, vendor)
                edit(vendor)
                with self.assertRaises(LicenseCheckError):
                    packaged_files(load_lock(vendor), vendor)

    def test_notice_must_name_the_locked_commit(self):
        text = (self.vendor / "NOTICE.txt").read_text(encoding="utf-8")
        (self.vendor / "NOTICE.txt").write_text(text.replace(COMMIT, "d" * 40), encoding="utf-8")
        with self.assertRaisesRegex(LicenseCheckError, "NOTICE.txt"):
            packaged_files(load_lock(self.vendor), self.vendor)

    def test_a_version_bump_without_new_licenses_or_notices_is_rejected(self):
        root = Path(self.directory.name) / "repository"
        for relative, text in {
            "app/build.gradle.kts": f'val pinnedVentoyVersion = "{VERSION}"\n        versionName = "0.2.0"\n',
            ".github/workflows/build-test-debug.yml": f"env:\n  VENTOY_VERSION: '{VERSION}'\n",
            ".github/workflows/publish-google-play.yml": f"env:\n  VENTOY_VERSION: '{VERSION}'\n",
        }.items():
            (root / relative).parent.mkdir(parents=True, exist_ok=True)
            (root / relative).write_text(text, encoding="utf-8")
        notice = f"Source: {tag_url(self.lock)} at commit {COMMIT}\n"
        documents = ("README.md", "docs/licensing.md", "docs/releases/README.md", "docs/releases/0.2.0.md",
                     "fastlane/metadata/android/en-US/full_description.txt")
        for relative in documents:
            (root / relative).parent.mkdir(parents=True, exist_ok=True)
            (root / relative).write_text(notice, encoding="utf-8")
        check_pins(self.lock, root)
        check_notices(self.lock, root)
        for relative in documents:
            with self.subTest(relative=relative):
                (root / relative).write_text(notice.replace(COMMIT, "d" * 40), encoding="utf-8")
                with self.assertRaisesRegex(LicenseCheckError, relative):
                    check_notices(self.lock, root)
                (root / relative).write_text(notice, encoding="utf-8")
        workflow = root / ".github/workflows/publish-google-play.yml"
        workflow.write_text("env:\n  VENTOY_VERSION: '9.9.10'\n", encoding="utf-8")
        with self.assertRaisesRegex(LicenseCheckError, "publish-google-play.yml pins Ventoy 9.9.10"):
            check_pins(self.lock, root)


class ArtifactFixtures(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.vendor, self.lock, _ = fixture(self.directory.name)

    def path(self, name):
        return Path(self.directory.name) / name

    def test_apk_and_bundle_with_the_complete_license_set_pass(self):
        for name in ("app.apk", "app.aab"):
            artifact(self.path(name), self.vendor)
            report = inspect_artifact(self.path(name), self.vendor)
            self.assertTrue(report["ventoy_licenses_passed"])
            self.assertEqual(report["ventoy_version"], VERSION)
            self.assertEqual(report["ventoy_source_commit"], commit_url(self.lock))
            self.assertEqual(report["license_files"], len(LICENSES))

    def test_missing_changed_or_unreviewed_license_entries_fail(self):
        for name, options in {"missing.apk": {"drop": "License/gpl-2.0.txt"},
                              "notice.apk": {"drop": "NOTICE.txt"},
                              "changed.aab": {"change": "License/license-busybox.txt"},
                              "extra.apk": {"extra": "License/license-unreviewed.txt"}}.items():
            artifact(self.path(name), self.vendor, **options)
            with self.subTest(name=name), self.assertRaises(LicenseCheckError):
                inspect_artifact(self.path(name), self.vendor)

    def test_payload_of_another_version_cannot_ship_with_these_licenses(self):
        artifact(self.path("newer.apk"), self.vendor, version="9.9.10")
        with self.assertRaisesRegex(LicenseCheckError, "9.9.10"):
            inspect_artifact(self.path("newer.apk"), self.vendor)
        artifact(self.path("none.apk"), self.vendor, version=None)
        with self.assertRaisesRegex(LicenseCheckError, "ventoy_payload/ventoy/version"):
            inspect_artifact(self.path("none.apk"), self.vendor)

    def test_cli_failure_writes_a_failed_report_and_returns_nonzero(self):
        apk, report = self.path("empty.apk"), self.path("report.json")
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr("classes.dex", b"")
        result = subprocess.run([sys.executable, str(Path(__file__).with_name("verify_apk.py")),
                                 str(apk), "--output", str(report)], capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('"ventoy_licenses_passed": false', report.read_text())


class SourceFixtures(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)

    def prepared(self, source=None, responses=None):
        root = Path(tempfile.mkdtemp(dir=self.directory.name))
        vendor, lock, served = fixture(root, source)
        served.update(responses or {})
        return prepare(root / "output", vendor, serve(served), lambda seconds: None), root / "output", lock

    def test_bundle_contains_verified_sources_licenses_and_checksums(self):
        report, output, lock = self.prepared()
        names = {f"ventoy-{VERSION}-source.tar.gz", "busybox-1.27.1.tar.bz2", "busybox-1.31.0.tar.bz2",
                 f"ventoy-{VERSION}-licenses.zip", GUIDE_NAME}
        self.assertEqual({path.name for path in output.iterdir()}, names | {SUMS_NAME})
        self.assertTrue(report["source_commit_verified"])
        sums = dict(reversed(line.split("  ")) for line in (output / SUMS_NAME).read_text().splitlines())
        self.assertEqual(set(sums), names)
        for name, expected in sums.items():
            self.assertEqual(digest((output / name).read_bytes()), expected)
        self.assertEqual(report["files"], sums)
        with zipfile.ZipFile(output / f"ventoy-{VERSION}-licenses.zip") as archive:
            root = f"ventoy-{VERSION}-licenses"
            self.assertEqual(set(archive.namelist()),
                             {f"{root}/License/{name}" for name in LICENSES} |
                             {f"{root}/NOTICE.txt", f"{root}/source-lock.json"})
            self.assertEqual(archive.read(f"{root}/License/gpl-3.0.txt"), LICENSES["gpl-3.0.txt"])
        text = (output / GUIDE_NAME).read_text(encoding="utf-8")
        for needle in (tag_url(lock), COMMIT, "busybox-1.27.1.tar.bz2", "`INSTALL/tool/i386/ash`"):
            self.assertIn(needle, text)

    def test_license_archive_is_reproducible(self):
        first = self.prepared()[1] / f"ventoy-{VERSION}-licenses.zip"
        second = self.prepared()[1] / f"ventoy-{VERSION}-licenses.zip"
        self.assertEqual(first.read_bytes(), second.read_bytes())

    def test_archive_bytes_other_than_the_pinned_ones_are_refused(self):
        _, lock, served = fixture(tempfile.mkdtemp(dir=self.directory.name))
        url = lock["source_archive"]["url"]
        for name, body in {"truncated": served[url][:-1], "longer": served[url] + b"x",
                           "replaced": bytes(len(served[url]))}.items():
            with self.subTest(name=name), self.assertRaisesRegex(LicenseCheckError, "pinned"):
                self.prepared(responses={url: body})
        busybox = lock["busybox"]["sources"]["1.31.0"]["url"]
        with self.assertRaisesRegex(LicenseCheckError, "busybox-1.31.0.tar.bz2"):
            self.prepared(responses={busybox: b"another busybox release"})

    def test_archive_from_another_commit_or_with_other_licenses_is_refused(self):
        cases = {
            "commit": (ventoy_archive(commit="d" * 40), "pinned commit"),
            "root": (ventoy_archive(root="Ventoy-master"), "unexpected root"),
            "license-changed": (ventoy_archive(licenses={**LICENSES, "gpl-2.0.txt": b"other terms"}), "License folder"),
            "license-added": (ventoy_archive(licenses={**LICENSES, "license-new.txt": b"new"}), "License folder"),
            "license-nested": (ventoy_archive(extra={"License/more/terms.txt": b"nested"}), "Unexpected License entry"),
        }
        for name, (source, message) in cases.items():
            with self.subTest(name=name), self.assertRaisesRegex(LicenseCheckError, message):
                self.prepared(source)

    def test_changed_or_unidentified_busybox_binaries_block_the_bundle(self):
        path = "IMG/cpio_x86/ventoy/busybox/busybox64.xz"
        rebuilt = ventoy_archive(binaries={**BINARIES, path: (b"busybox from a newer release", "1.31.0")})
        root = Path(tempfile.mkdtemp(dir=self.directory.name))
        vendor, lock, served = fixture(root)
        lock["source_archive"].update(size=len(rebuilt), sha256=digest(rebuilt))
        write_lock(vendor, lock)
        served[lock["source_archive"]["url"]] = rebuilt
        with self.assertRaisesRegex(LicenseCheckError, "identify its BusyBox version again"):
            prepare(root / "output", vendor, serve(served))
        added = ventoy_archive(extra={"IMG/cpio_arm64/ventoy/busybox/busyboxaa64.xz": b"unidentified"})
        (root / "added.tar.gz").write_bytes(added)
        with self.assertRaisesRegex(LicenseCheckError, "Unidentified files beside BusyBox"):
            verify_ventoy_tree(root / "added.tar.gz", lock)
        removed = ventoy_archive(other={})
        (root / "removed.tar.gz").write_bytes(removed)
        with self.assertRaisesRegex(LicenseCheckError, "no longer in the Ventoy tree"):
            verify_ventoy_tree(root / "removed.tar.gz", lock)

    def test_output_directory_must_start_empty(self):
        root = Path(tempfile.mkdtemp(dir=self.directory.name))
        vendor, _, served = fixture(root)
        (root / "output").mkdir()
        (root / "output/stale.tar.gz").write_bytes(b"stale")
        with self.assertRaisesRegex(LicenseCheckError, "empty"):
            prepare(root / "output", vendor, serve(served))

    def test_downloads_follow_only_expected_https_hosts(self):
        url = "https://github.com/ventoy/Ventoy/archive/refs/tags/v9.9.9.tar.gz"
        delivery = "https://codeload.github.com/ventoy/Ventoy/tar.gz/refs/tags/v9.9.9"
        opener = Opener([Response(status=302, headers={"Location": delivery}), Response(b"archive")])
        with open_pinned(url, opener) as response:
            self.assertEqual(response.read(), b"archive")
        self.assertEqual(opener.urls, [url, delivery])
        for location in ("http://codeload.github.com/ventoy/Ventoy/tar.gz/x", "https://example.com/archive",
                         "https://github.com.example.com/archive", "https://github.com:444/archive"):
            opener = Opener([Response(status=302, headers={"Location": location})])
            with self.subTest(location=location), self.assertRaisesRegex(LicenseCheckError, "expected hosts"):
                open_pinned(url, opener)
            self.assertEqual(opener.urls, [url])
        for responses in ([Response(status=404)], [Response(status=302)],
                          [Response(status=302, headers={"Location": url}) for _ in range(6)]):
            with self.subTest(responses=len(responses)), self.assertRaises(LicenseCheckError):
                open_pinned(url, Opener(responses))
        with self.assertRaisesRegex(LicenseCheckError, "expected hosts"):
            open_pinned("https://example.com/busybox-1.31.0.tar.bz2", Opener([]))

    def test_transient_network_errors_are_retried_and_then_reported(self):
        data = b"busybox source"
        archive = pinned("busybox-1.31.0.tar.bz2", "https://busybox.net/downloads/busybox-1.31.0.tar.bz2", data)
        destination = Path(self.directory.name) / "busybox.tar.bz2"
        attempts, pauses = [], []

        def flaky(url):
            attempts.append(url)
            if len(attempts) < 3:
                raise URLError("temporary failure")
            return io.BytesIO(data)

        download_with_retries(archive, destination, flaky, pauses.append)
        self.assertEqual((len(attempts), pauses, destination.read_bytes()), (3, [5, 10], data))

        def down(url):
            raise URLError("unreachable")

        with self.assertRaisesRegex(LicenseCheckError, "could not be downloaded"):
            download_with_retries(archive, destination, down, pauses.append)


if __name__ == "__main__":
    unittest.main()
