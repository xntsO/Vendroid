"""Check that an APK or bundle carries the Ventoy license set for its bundled payload."""

import argparse
import json
from pathlib import Path
import sys
import zipfile

from ventoy_lock import (
    ASSET_ROOT, ROOT, VENDOR_DIRECTORY, LicenseCheckError, commit_url, load_lock, packaged_files, require,
    sha256_bytes, tag_url,
)


MAX_ENTRY_BYTES = 4 * 1024 * 1024
NOTICE_ROOT = "licenses"
# Packaged name under assets/licenses/ and the repository file it must equal byte for byte.
NOTICE_FILES = {"NOTICE.txt": "NOTICE", "GPL-3.0.txt": "LICENSE",
                "LGPL-2.1.txt": "third_party/licenses/LGPL-2.1.txt",
                "Apache-2.0.txt": "third_party/licenses/Apache-2.0.txt"}


def notice_files(root=ROOT):
    return {name: (Path(root) / source).read_bytes() for name, source in NOTICE_FILES.items()}


def inspect_artifact(path, vendor_directory=VENDOR_DIRECTORY, notice_root=ROOT):
    path = Path(path)
    kind = path.suffix.lower().lstrip(".")
    require(kind in ("apk", "aab"), "Expected an .apk or .aab artifact")
    lock = load_lock(vendor_directory)
    expected = packaged_files(lock, vendor_directory)
    notices = notice_files(notice_root)
    assets = "assets/" if kind == "apk" else "base/assets/"
    prefix = f"{assets}{ASSET_ROOT}/"
    with zipfile.ZipFile(path) as archive:
        entries = {info.filename: info for info in archive.infolist()}
        require(len(entries) == len(archive.infolist()), "Artifact contains duplicate ZIP entry names")

        def read(name):
            require(name in entries, f"Artifact is missing {name}")
            require(entries[name].file_size <= MAX_ENTRY_BYTES, f"{name} exceeds its size limit")
            return archive.read(entries[name])

        for relative, data in expected.items():
            require(read(prefix + relative) == data, f"{prefix}{relative} differs from the vendored file")
        unexpected = sorted(name[len(prefix):] for name in entries
                            if name.startswith(prefix) and name[len(prefix):] not in expected)
        require(not unexpected, f"Unexpected packaged license entries: {', '.join(unexpected[:5])}")
        notice_prefix = f"{assets}{NOTICE_ROOT}/"
        for name, data in notices.items():
            require(read(notice_prefix + name) == data, f"{notice_prefix}{name} differs from the repository file")
        stray = sorted(name[len(notice_prefix):] for name in entries
                       if name.startswith(notice_prefix) and name[len(notice_prefix):] not in notices)
        require(not stray, f"Unexpected packaged notice entries: {', '.join(stray[:5])}")
        payload_version = read(f"{assets}ventoy_payload/ventoy/version").decode("utf-8").strip()
        require(payload_version == lock["version"],
                f"Bundled payload is Ventoy {payload_version}, but its licenses are for {lock['version']}")
    return {"schema": 1, "artifact_type": kind, "sha256": sha256_bytes(path.read_bytes()),
            "ventoy_licenses_passed": True, "ventoy_version": lock["version"],
            "ventoy_source_tag": tag_url(lock), "ventoy_source_commit": commit_url(lock),
            "license_files": len(lock["license_files"]),
            "packaged": {name: sha256_bytes(data) for name, data in expected.items()},
            "notices_passed": True,
            "notices": {name: sha256_bytes(data) for name, data in notices.items()}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifact", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        report = inspect_artifact(args.artifact)
    except (LicenseCheckError, OSError, UnicodeDecodeError, zipfile.BadZipFile) as error:
        report = {"schema": 1, "ventoy_licenses_passed": False, "error": str(error)}
    rendered = json.dumps(report, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered, encoding="utf-8")
    print(rendered, end="")
    return 0 if report["ventoy_licenses_passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
