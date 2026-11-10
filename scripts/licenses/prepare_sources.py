#!/usr/bin/env python3
"""Download and verify the third-party source that accompanies every Vendroid release."""

from __future__ import annotations

import argparse
import hashlib
from http.client import HTTPException
import json
from pathlib import Path
import sys
import tarfile
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urljoin, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener
import zipfile

from ventoy_lock import (
    VENDOR_DIRECTORY, LicenseCheckError, commit_url, inspect_ventoy_tree, load_lock, packaged_files, require,
    tag_url,
)


DOWNLOAD_HOSTS = frozenset({"github.com", "codeload.github.com", "busybox.net"})
USER_AGENT = "Vendroid-Source-Offer/1.0"
CHUNK_BYTES = 1024 * 1024
MAX_REDIRECTS = 5
TIMEOUT_SECONDS = 120
ATTEMPTS = 3
SUMS_NAME = "third-party-sources.sha256"
GUIDE_NAME = "THIRD-PARTY-SOURCES.md"


class NoRedirects(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        return None


def open_pinned(url, opener=None):
    """Follow redirects by hand so every hop stays on an expected HTTPS host."""
    opener = opener or build_opener(NoRedirects())
    target = url
    for _ in range(MAX_REDIRECTS + 1):
        parsed = urlsplit(target)
        require(parsed.scheme == "https" and parsed.netloc in DOWNLOAD_HOSTS and not parsed.fragment,
                f"Source download left its expected hosts: {parsed.netloc or target}")
        request = Request(target, headers={"User-Agent": USER_AGENT, "Accept-Encoding": "identity"})
        try:
            response = opener.open(request, timeout=TIMEOUT_SECONDS)
        except HTTPError as error:
            response = error
        if response.status == 200:
            return response
        try:
            require(response.status in (301, 302, 303, 307, 308), f"Source download returned HTTP {response.status}")
            location = response.headers.get("Location")
            require(location, "Source download redirect has no destination")
            target = urljoin(target, location)
        finally:
            response.close()
    raise LicenseCheckError("Source download exceeded its redirect limit")


def download(archive, destination, opener=open_pinned):
    """Stream one pinned archive and refuse any other size or SHA-256."""
    digest = hashlib.sha256()
    received = 0
    with opener(archive["url"]) as response, destination.open("wb") as output:
        while True:
            chunk = response.read(CHUNK_BYTES)
            if not chunk:
                break
            received += len(chunk)
            require(received <= archive["size"], f"{archive['name']} exceeds its pinned size")
            digest.update(chunk)
            output.write(chunk)
    require(received == archive["size"] and digest.hexdigest() == archive["sha256"],
            f"{archive['name']} differs from its pinned size and SHA-256")


def download_with_retries(archive, destination, opener=open_pinned, pause=time.sleep):
    for attempt in range(ATTEMPTS):
        try:
            return download(archive, destination, opener)
        except (URLError, HTTPException, TimeoutError, ConnectionError) as error:
            if attempt == ATTEMPTS - 1:
                raise LicenseCheckError(
                    f"{archive['name']} could not be downloaded ({type(error).__name__})") from error
            pause(5 * (attempt + 1))


def verify_ventoy_tree(path, lock):
    """Tie the archive to the pinned commit, the vendored licenses and the identified BusyBox binaries."""
    licenses = inspect_ventoy_tree(path, lock["version"], lock["commit"], lock["busybox"])
    require(licenses == lock["license_files"],
            "Ventoy's License folder at the pinned tag differs from the vendored license set")


def write_license_archive(lock, files, destination):
    """Zip the vendored License folder reproducibly."""
    root = f"ventoy-{lock['version']}-licenses"
    with zipfile.ZipFile(destination, "w") as archive:
        for name, data in sorted(files.items()):
            entry = zipfile.ZipInfo(f"{root}/{name}", date_time=(1980, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.external_attr = 0o644 << 16
            archive.writestr(entry, data)


def guide(lock):
    version, busybox = lock["version"], lock["busybox"]
    lines = [
        f"# Third-party source for Vendroid's bundled Ventoy {version}",
        "",
        f"Vendroid installs unmodified boot files from the official Ventoy {version} Linux release.",
        "These files are the source and license texts for that payload.",
        "",
        f"- `{lock['source_archive']['name']}`: the complete Ventoy source tree at tag `{lock['tag']}`,",
        f"  commit `{lock['commit']}`.",
        f"  Tag: {tag_url(lock)}",
        f"  Commit: {commit_url(lock)}",
        f"- `ventoy-{version}-licenses.zip`: Ventoy's `License` folder at that commit, with Vendroid's notice.",
        "- BusyBox upstream source. Ventoy ships BusyBox binaries unmodified and does not keep their",
        "  source in its repository:",
    ]
    for release, archive in sorted(busybox["sources"].items()):
        paths = sorted(path for path, binary in busybox["binaries"].items() if binary["version"] == release)
        lines.append(f"  - `{archive['name']}` ({archive['url']}) for " + ", ".join(f"`{path}`" for path in paths))
    lines += [
        "",
        f"Check the files with `sha256sum -c {SUMS_NAME}`.",
        "",
        "Vendroid's own source is the tagged commit of https://github.com/xntsO/Vendroid.",
        "The libaums and libusb sources are inside the APK under `assets/native-source/`.",
        "See `docs/licensing.md` in the Vendroid repository for what is and is not covered.",
        "",
    ]
    return "\n".join(lines)


def prepare(output, vendor_directory=VENDOR_DIRECTORY, opener=open_pinned, pause=time.sleep):
    lock = load_lock(vendor_directory)
    files = packaged_files(lock, vendor_directory)
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    require(not any(output.iterdir()), "Source output directory must be empty")
    archives = [lock["source_archive"]] + [archive for _, archive in sorted(lock["busybox"]["sources"].items())]
    for archive in archives:
        download_with_retries(archive, output / archive["name"], opener, pause)
    verify_ventoy_tree(output / lock["source_archive"]["name"], lock)
    licenses = output / f"ventoy-{lock['version']}-licenses.zip"
    write_license_archive(lock, files, licenses)
    (output / GUIDE_NAME).write_text(guide(lock), encoding="utf-8", newline="\n")
    names = [archive["name"] for archive in archives] + [licenses.name, GUIDE_NAME]
    sums = {}
    for name in names:
        with (output / name).open("rb") as stream:
            sums[name] = hashlib.file_digest(stream, "sha256").hexdigest()
    (output / SUMS_NAME).write_text("".join(f"{digest}  {name}\n" for name, digest in sums.items()),
                                    encoding="utf-8", newline="\n")
    return {"schema": 1, "ventoy_version": lock["version"], "ventoy_commit": lock["commit"],
            "source_commit_verified": True, "license_folder_matches_vendored_copy": True,
            "busybox_binaries_match_lock": True, "files": sums}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--evidence", type=Path)
    args = parser.parse_args()
    try:
        report = prepare(args.output)
    except (LicenseCheckError, OSError, tarfile.TarError, zipfile.BadZipFile, EOFError) as error:
        print(f"Source preparation failed: {error}", file=sys.stderr)
        return 1
    rendered = json.dumps(report, indent=2) + "\n"
    if args.evidence:
        args.evidence.parent.mkdir(parents=True, exist_ok=True)
        args.evidence.write_text(rendered, encoding="utf-8")
    print(rendered, end="")
    return 0


if __name__ == "__main__":
    sys.exit(main())
