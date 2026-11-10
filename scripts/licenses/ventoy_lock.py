"""Shared checks for the vendored Ventoy license set and its corresponding-source lock."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
import re
import tarfile


ROOT = Path(__file__).resolve().parents[2]
VENDOR_DIRECTORY = ROOT / "third_party/ventoy"
ASSET_ROOT = "ventoy_licenses"
REPOSITORY = "https://github.com/ventoy/Ventoy"
BUSYBOX_DOWNLOADS = "https://busybox.net/downloads"
REQUIRED_LICENSES = frozenset({
    "gpl-2.0.txt", "gpl-3.0.txt", "license-busybox.txt", "license-grub2.txt", "license-ventoy.txt",
})
EVIDENCE = frozenset({"version-string", "ventoy-binary-list", "ventoy-build-notes"})
VERSION = re.compile(r"(?:0|[1-9][0-9]{0,5})(?:\.(?:0|[1-9][0-9]{0,5})){1,3}\Z", re.ASCII)
SHA256 = re.compile(r"[0-9a-f]{64}\Z", re.ASCII)
COMMIT = re.compile(r"[0-9a-f]{40}\Z", re.ASCII)
LICENSE_NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,80}\.txt\Z", re.ASCII)
TREE_PATH = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]*(?:/[A-Za-z0-9][A-Za-z0-9._-]*)+\Z", re.ASCII)
MAX_SOURCE_BYTES = 256 * 1024 * 1024
MAX_INSPECTED_BYTES = 16 * 1024 * 1024
BUSYBOX_DIRECTORY = re.compile(r"IMG/cpio_[^/]+/ventoy/busybox/[^/]+\Z")


class LicenseCheckError(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise LicenseCheckError(message)


def sha256_bytes(data):
    return hashlib.sha256(data).hexdigest()


def tag_url(lock):
    return f"{REPOSITORY}/tree/{lock['tag']}"


def commit_url(lock):
    return f"{REPOSITORY}/commit/{lock['commit']}"


def _archive(value, name, url, label):
    require(isinstance(value, dict) and set(value) == {"name", "url", "size", "sha256"}, f"{label} is malformed")
    require(value["name"] == name and value["url"] == url, f"{label} must use its official name and URL")
    require(type(value["size"]) is int and 0 < value["size"] <= MAX_SOURCE_BYTES, f"{label} size is invalid")
    require(isinstance(value["sha256"], str) and SHA256.fullmatch(value["sha256"]), f"{label} SHA-256 is invalid")


def validate_lock(lock):
    """Reject a lock that could point a source offer at the wrong upstream bytes."""
    fields = {"schema", "version", "tag", "commit", "source_archive", "license_files", "busybox"}
    require(isinstance(lock, dict) and set(lock) == fields and lock["schema"] == 1, "Unsupported Ventoy source lock")
    version = lock["version"]
    require(isinstance(version, str) and VERSION.fullmatch(version), "Ventoy version is invalid")
    require(lock["tag"] == f"v{version}", "Ventoy tag differs from its version")
    require(isinstance(lock["commit"], str) and COMMIT.fullmatch(lock["commit"]), "Ventoy commit is invalid")
    _archive(lock["source_archive"], f"ventoy-{version}-source.tar.gz",
             f"{REPOSITORY}/archive/refs/tags/v{version}.tar.gz", "Ventoy source archive")
    licenses = lock["license_files"]
    require(isinstance(licenses, dict) and REQUIRED_LICENSES <= licenses.keys(), "Ventoy license set is incomplete")
    for name, digest in licenses.items():
        require(LICENSE_NAME.fullmatch(name) and isinstance(digest, str) and SHA256.fullmatch(digest),
                f"License entry is invalid: {name}")
    busybox = lock["busybox"]
    require(isinstance(busybox, dict) and set(busybox) == {"sources", "binaries", "other_tools"},
            "BusyBox section is malformed")
    sources, binaries, other = busybox["sources"], busybox["binaries"], busybox["other_tools"]
    require(isinstance(sources, dict) and sources, "BusyBox sources are missing")
    for release, archive in sources.items():
        require(VERSION.fullmatch(release), f"BusyBox version is invalid: {release}")
        _archive(archive, f"busybox-{release}.tar.bz2", f"{BUSYBOX_DOWNLOADS}/busybox-{release}.tar.bz2",
                 f"BusyBox {release} source")
    require(isinstance(binaries, dict) and binaries, "BusyBox binaries are missing")
    for path, binary in binaries.items():
        require(TREE_PATH.fullmatch(path) and isinstance(binary, dict) and
                set(binary) == {"sha256", "version", "evidence"}, f"BusyBox binary entry is invalid: {path}")
        require(isinstance(binary["sha256"], str) and SHA256.fullmatch(binary["sha256"]),
                f"BusyBox binary SHA-256 is invalid: {path}")
        require(binary["version"] in sources, f"BusyBox binary has no pinned source: {path}")
        require(binary["evidence"] in EVIDENCE, f"BusyBox binary evidence is unknown: {path}")
    require({binary["version"] for binary in binaries.values()} == set(sources), "A pinned BusyBox source is unused")
    require(isinstance(other, list) and all(isinstance(path, str) and TREE_PATH.fullmatch(path) for path in other) and
            len(set(other)) == len(other) and not set(other) & binaries.keys(), "Other tool list is invalid")
    return lock


def load_lock(vendor_directory=VENDOR_DIRECTORY):
    path = Path(vendor_directory) / "source-lock.json"
    try:
        return validate_lock(json.loads(path.read_text(encoding="utf-8")))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise LicenseCheckError(f"Cannot read {path.name}: {type(error).__name__}") from error


def packaged_files(lock, vendor_directory=VENDOR_DIRECTORY):
    """Return every file the app packages, after proving the vendored copy matches the lock."""
    vendor_directory = Path(vendor_directory)
    license_directory = vendor_directory / "License"
    present = {path.name for path in license_directory.iterdir()} if license_directory.is_dir() else set()
    require(present == lock["license_files"].keys(),
            "Vendored License folder differs from the lock: " +
            ", ".join(sorted(present ^ lock["license_files"].keys())[:5]))
    files = {}
    for name, digest in sorted(lock["license_files"].items()):
        path = license_directory / name
        require(path.is_file() and not path.is_symlink(), f"Vendored license is not a regular file: {name}")
        data = path.read_bytes()
        require(sha256_bytes(data) == digest, f"Vendored license differs from upstream: {name}")
        files[f"License/{name}"] = data
    others = {path.name for path in vendor_directory.iterdir() if path.name != "License"}
    require(others == {"NOTICE.txt", "source-lock.json"}, "Vendored Ventoy folder has unexpected entries")
    notice = (vendor_directory / "NOTICE.txt").read_bytes()
    text = notice.decode("utf-8")
    wanted = [f"Ventoy {lock['version']}", tag_url(lock), commit_url(lock), lock["source_archive"]["url"],
              lock["source_archive"]["name"]] + [archive["url"] for archive in lock["busybox"]["sources"].values()]
    for needle in wanted:
        require(needle in text, f"NOTICE.txt does not mention {needle}")
    files["NOTICE.txt"] = notice
    files["source-lock.json"] = (vendor_directory / "source-lock.json").read_bytes()
    return files


def inspect_ventoy_tree(path, version, commit, busybox):
    """Return the License folder digests of a Ventoy source archive made from one exact commit.

    The BusyBox binaries inside must be the ones whose upstream versions were identified, so the
    BusyBox source published with this tree is the source of what the tree ships.
    """
    root = f"Ventoy-{version}"
    binaries, other = busybox["binaries"], set(busybox["other_tools"])
    licenses, found = {}, {}
    with tarfile.open(path, "r:gz") as archive:
        member = archive.next()
        require(member is not None, "Ventoy source archive is empty")
        require(archive.pax_headers.get("comment") == commit,
                "Ventoy source archive was not generated from the pinned commit")
        while member is not None:
            require(member.name == root or member.name.startswith(root + "/"),
                    f"Ventoy source archive has an unexpected root: {member.name[:80]}")
            relative = member.name[len(root) + 1:]
            parts = relative.split("/")
            is_license = len(parts) == 2 and parts[0] == "License"
            if relative.startswith("License/"):
                require(is_license and member.isfile(), f"Unexpected License entry: {relative}")
            if member.isfile() and (is_license or relative in binaries or relative in other or
                                    BUSYBOX_DIRECTORY.fullmatch(relative)):
                require(member.size <= MAX_INSPECTED_BYTES, f"{relative} exceeds its inspection limit")
                digest = sha256_bytes(archive.extractfile(member).read())
                if is_license:
                    licenses[parts[1]] = digest
                else:
                    found[relative] = digest
            member = archive.next()
    for relative, binary in binaries.items():
        require(found.get(relative) == binary["sha256"],
                f"{relative} changed; identify its BusyBox version again before updating the lock")
    unknown = sorted(relative for relative in found if relative not in binaries and relative not in other)
    require(not unknown, f"Unidentified files beside BusyBox: {', '.join(unknown[:5])}")
    missing = sorted(other - found.keys())
    require(not missing, f"Listed tools are no longer in the Ventoy tree: {', '.join(missing[:5])}")
    return licenses


def pinned_versions(root=ROOT):
    """Read each place that selects the bundled Ventoy release."""
    root = Path(root)
    patterns = {
        "app/build.gradle.kts": r'^val pinnedVentoyVersion = "([^"]+)"$',
        ".github/workflows/build-test-debug.yml": r"^  VENTOY_VERSION: '([^']+)'$",
        ".github/workflows/publish-google-play.yml": r"^  VENTOY_VERSION: '([^']+)'$",
    }
    versions = {}
    for relative, pattern in patterns.items():
        matches = re.findall(pattern, (root / relative).read_text(encoding="utf-8"), re.MULTILINE)
        require(len(matches) == 1, f"{relative} must pin exactly one Ventoy version")
        versions[relative] = matches[0]
    return versions


def check_pins(lock, root=ROOT):
    for relative, version in pinned_versions(root).items():
        require(version == lock["version"],
                f"{relative} pins Ventoy {version}, but the license set and source lock are for {lock['version']}")


def app_version(root=ROOT):
    matches = re.findall(r'^\s+versionName = "([^"]+)"$',
                         (Path(root) / "app/build.gradle.kts").read_text(encoding="utf-8"), re.MULTILINE)
    require(len(matches) == 1, "app/build.gradle.kts must declare one versionName")
    return matches[0]


def check_notices(lock, root=ROOT):
    """Public texts must name the exact tag and commit of the bundled release."""
    root = Path(root)
    documents = ["README.md", "docs/licensing.md", "docs/releases/README.md",
                 "fastlane/metadata/android/en-US/full_description.txt"]
    notes = f"docs/releases/{app_version(root)}.md"
    if (root / notes).is_file():
        documents.append(notes)
    for relative in documents:
        path = root / relative
        require(path.is_file(), f"{relative} is missing")
        text = path.read_text(encoding="utf-8")
        for needle in (tag_url(lock), lock["commit"]):
            require(needle in text, f"{relative} does not give the Ventoy source as {needle}")
