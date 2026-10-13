#!/usr/bin/env python3
"""Populate Vendroid's first-party Ventoy mirror from verified public releases."""

from __future__ import annotations

import argparse
from dataclasses import asdict, dataclass
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import tempfile
from urllib.error import HTTPError
from urllib.parse import urljoin, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener


MIRROR_ORIGIN = "https://updates.vendroid.fhas.sa"
LATEST_PATH = "/v1/ventoy/latest.json"
UPSTREAM_API = "https://api.github.com/repos/ventoy/Ventoy/releases/latest"
UPSTREAM_DOWNLOAD_HOSTS = frozenset({
    "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com",
})
MAX_ARCHIVE_BYTES = 256 * 1024 * 1024
MAX_METADATA_BYTES = 1024 * 1024
MAX_MIRROR_JSON_BYTES = 16 * 1024
MAX_REDIRECTS = 5
CHUNK_BYTES = 64 * 1024
TIMEOUT_SECONDS = 60
USER_AGENT = "Vendroid-Mirror/1.0"
VERSION_PATTERN = re.compile(r"(?:0|[1-9][0-9]{0,5})(?:\.(?:0|[1-9][0-9]{0,5})){1,3}\Z", re.ASCII)
DIGEST_PATTERN = re.compile(r"[0-9a-f]{64}\Z", re.ASCII)


class MirrorSyncError(ValueError):
    pass


@dataclass(frozen=True)
class Manifest:
    schemaVersion: int
    version: str
    assetName: str
    sizeBytes: int
    sha256: str
    downloadUrl: str


@dataclass(frozen=True)
class UpstreamRelease:
    manifest: Manifest
    source_url: str
    release_id: int
    asset_id: int


def version_parts(value: object) -> tuple[int, ...]:
    if not isinstance(value, str) or not VERSION_PATTERN.fullmatch(value):
        raise MirrorSyncError("Ventoy version must contain two to four canonical ASCII numeric components")
    return tuple(int(part) for part in value.split("."))


def compare_versions(left: str, right: str) -> int:
    a, b = version_parts(left), version_parts(right)
    length = max(len(a), len(b))
    a += (0,) * (length - len(a))
    b += (0,) * (length - len(b))
    return (a > b) - (a < b)


def positive_integer(value: object, label: str, maximum: int | None = None) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 1:
        raise MirrorSyncError(f"{label} must be a positive integer")
    if maximum is not None and value > maximum:
        raise MirrorSyncError(f"{label} exceeds its supported limit")
    return value


def asset_path(digest: str, asset_name: str, *, admin: bool = False) -> str:
    return f"/v1/{'admin/' if admin else ''}assets/{digest}/{asset_name}"


def parse_manifest(value: object) -> Manifest:
    fields = set(Manifest.__dataclass_fields__)
    if not isinstance(value, dict) or set(value) != fields:
        raise MirrorSyncError("Mirror manifest must contain only the schema version and release fields")
    if type(value["schemaVersion"]) is not int or value["schemaVersion"] != 1:
        raise MirrorSyncError("Unsupported mirror manifest schema")
    version_parts(value["version"])
    expected_name = f"ventoy-{value['version']}-linux.tar.gz"
    if value["assetName"] != expected_name:
        raise MirrorSyncError("Mirror asset name differs from its version")
    positive_integer(value["sizeBytes"], "Archive size", MAX_ARCHIVE_BYTES)
    digest = value["sha256"]
    if not isinstance(digest, str) or not DIGEST_PATTERN.fullmatch(digest):
        raise MirrorSyncError("Mirror checksum must be a lowercase SHA-256")
    if value["downloadUrl"] != MIRROR_ORIGIN + asset_path(digest, expected_name):
        raise MirrorSyncError("Mirror download URL must match the first-party immutable asset path")
    return Manifest(**value)


def published_checksum(asset: dict, release_body: object, asset_name: str) -> str:
    digest = asset.get("digest")
    if digest is not None and not isinstance(digest, str):
        raise MirrorSyncError("Upstream asset digest is malformed")
    digest_value = None
    if digest:
        match = re.fullmatch(r"sha256:([0-9a-fA-F]{64})", digest, re.IGNORECASE | re.ASCII)
        if not match:
            raise MirrorSyncError("Upstream asset digest is not a published SHA-256")
        digest_value = match.group(1).lower()
    if release_body is not None and not isinstance(release_body, str):
        raise MirrorSyncError("Upstream release notes are malformed")
    notes_pattern = re.compile(
        rf"(?<![0-9a-fA-F])([0-9a-fA-F]{{64}})\s+\*?{re.escape(asset_name)}(?=\s|$)",
        re.ASCII,
    )
    notes = {match.group(1).lower() for match in notes_pattern.finditer(release_body or "")}
    if len(notes) > 1 or (digest_value and notes and notes != {digest_value}):
        raise MirrorSyncError("Upstream publishes conflicting archive checksums")
    checksum = digest_value or next(iter(notes), None)
    if checksum is None:
        raise MirrorSyncError("Upstream does not publish an archive SHA-256")
    return checksum


def parse_upstream_release(value: object) -> UpstreamRelease:
    if not isinstance(value, dict) or value.get("draft") is not False or value.get("prerelease") is not False:
        raise MirrorSyncError("Only a published stable upstream release can be mirrored")
    tag = value.get("tag_name")
    if not isinstance(tag, str) or not tag.startswith("v"):
        raise MirrorSyncError("Upstream Ventoy release tag is invalid")
    version = tag[1:]
    version_parts(version)
    name = f"ventoy-{version}-linux.tar.gz"
    assets = value.get("assets")
    if not isinstance(assets, list):
        raise MirrorSyncError("Upstream release asset list is malformed")
    matches = [asset for asset in assets if isinstance(asset, dict) and asset.get("name") == name]
    if len(matches) != 1 or matches[0].get("state") != "uploaded":
        raise MirrorSyncError("Upstream must contain exactly one uploaded Linux archive")
    asset = matches[0]
    size = positive_integer(asset.get("size"), "Archive size", MAX_ARCHIVE_BYTES)
    digest = published_checksum(asset, value.get("body"), name)
    source_url = f"https://github.com/ventoy/Ventoy/releases/download/v{version}/{name}"
    if asset.get("browser_download_url") != source_url:
        raise MirrorSyncError("Upstream asset URL is not the exact official Ventoy release URL")
    return UpstreamRelease(
        Manifest(1, version, name, size, digest, MIRROR_ORIGIN + asset_path(digest, name)),
        source_url, positive_integer(value.get("id"), "Release ID"),
        positive_integer(asset.get("id"), "Asset ID"),
    )


def decode_json(data: bytes) -> object:
    def unique_fields(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise MirrorSyncError("JSON contains duplicate fields")
            result[key] = value
        return result

    def invalid_constant(value):
        raise MirrorSyncError("JSON contains a non-finite number")

    try:
        return json.loads(data.decode("utf-8"), object_pairs_hook=unique_fields, parse_constant=invalid_constant)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise MirrorSyncError("Response is not valid UTF-8 JSON") from error


def read_bounded(response, maximum: int) -> bytes:
    result = bytearray()
    while True:
        chunk = response.read(min(CHUNK_BYTES, maximum + 1 - len(result)))
        if not chunk:
            return bytes(result)
        result.extend(chunk)
        if len(result) > maximum:
            raise MirrorSyncError("Response exceeds its size limit")


class NoRedirects(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        return None


class HttpTransport:
    def __init__(self):
        self.opener = build_opener(NoRedirects())

    def request(self, method: str, url: str, *, headers: dict[str, str], data=None):
        request_headers = {"User-Agent": USER_AGENT, **headers}
        request = Request(url, data=data, headers=request_headers, method=method)
        try:
            return self.opener.open(request, timeout=TIMEOUT_SECONDS)
        except HTTPError as response:
            # Inspect only the status and safe headers; never print raw error bodies.
            return response


def source_headers(*, json_response: bool, github_token: str = "") -> dict[str, str]:
    headers = {
        "User-Agent": USER_AGENT, "Accept-Encoding": "identity",
        "Accept": "application/vnd.github+json" if json_response else "application/octet-stream",
    }
    if json_response:
        headers["X-GitHub-Api-Version"] = "2026-03-10"
        if github_token:
            headers["Authorization"] = f"Bearer {github_token}"
    return headers


def require_download_target(url: str) -> None:
    try:
        parsed = urlsplit(url)
        safe = (
            parsed.scheme == "https" and parsed.netloc in UPSTREAM_DOWNLOAD_HOSTS and
            not parsed.fragment and not parsed.username and not parsed.password and
            not any(char in url for char in "\r\n\t\\")
        )
    except ValueError:
        safe = False
    if not safe:
        raise MirrorSyncError("Upstream download redirect must stay on an allowed HTTPS delivery host")


def open_source_archive(transport, url: str):
    target = url
    for redirect_count in range(MAX_REDIRECTS + 1):
        require_download_target(target)
        response = transport.request("GET", target, headers=source_headers(json_response=False))
        if response.status == 200:
            return response
        try:
            if response.status not in {301, 302, 303, 307, 308}:
                raise MirrorSyncError(f"Upstream archive request returned HTTP {response.status}")
            if redirect_count == MAX_REDIRECTS:
                raise MirrorSyncError("Upstream archive exceeded its redirect limit")
            location = response.headers.get("Location")
            if not location:
                raise MirrorSyncError("Upstream download redirect has no destination")
            target = urljoin(target, location)
        finally:
            response.close()
    raise MirrorSyncError("Upstream archive exceeded its redirect limit")


def read_source_release(transport, github_token: str = "") -> UpstreamRelease:
    with transport.request("GET", UPSTREAM_API, headers=source_headers(json_response=True, github_token=github_token)) as response:
        if response.status != 200:
            raise MirrorSyncError(f"Upstream metadata request returned HTTP {response.status}")
        return parse_upstream_release(decode_json(read_bounded(response, MAX_METADATA_BYTES)))


def verify_archive(response, manifest: Manifest, destination=None) -> None:
    encoding = response.headers.get("Content-Encoding", "identity")
    if encoding != "identity":
        raise MirrorSyncError("Archive transfer encoding must preserve the original bytes")
    length = response.headers.get("Content-Length")
    if length is not None and length != str(manifest.sizeBytes):
        raise MirrorSyncError("Archive Content-Length differs from published metadata")
    digest = hashlib.sha256()
    received = 0
    while True:
        chunk = response.read(CHUNK_BYTES)
        if not chunk:
            break
        received += len(chunk)
        if received > manifest.sizeBytes:
            raise MirrorSyncError("Archive exceeds its published size")
        digest.update(chunk)
        if destination is not None:
            destination.write(chunk)
    if received != manifest.sizeBytes or digest.hexdigest() != manifest.sha256:
        raise MirrorSyncError("Archive bytes do not match the upstream size and SHA-256")


def read_current_manifest(transport) -> Manifest | None:
    with transport.request("GET", MIRROR_ORIGIN + LATEST_PATH, headers={"Accept": "application/json", "Cache-Control": "no-cache"}) as response:
        if response.status == 404:
            return None
        if response.status != 200:
            raise MirrorSyncError(f"Mirror metadata request returned HTTP {response.status}; redirects are not allowed")
        return parse_manifest(decode_json(read_bounded(response, MAX_MIRROR_JSON_BYTES)))


def check_new_release(current: Manifest | None, proposed: Manifest) -> None:
    if current is None:
        return
    relation = compare_versions(proposed.version, current.version)
    if relation < 0:
        raise MirrorSyncError("Upstream latest is older than the mirrored release")
    if relation == 0 and current != proposed:
        raise MirrorSyncError("A published Ventoy version cannot be replaced with different mirror metadata")


def upload_archive(transport, manifest: Manifest, path: Path, token: str) -> None:
    headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/octet-stream", "Content-Length": str(manifest.sizeBytes)}
    with path.open("rb") as data:
        with transport.request("PUT", MIRROR_ORIGIN + asset_path(manifest.sha256, manifest.assetName, admin=True), headers=headers, data=data) as response:
            if response.status not in {200, 201}:
                raise MirrorSyncError(f"Mirror archive upload returned HTTP {response.status}; redirects are not allowed")
            proof = decode_json(read_bounded(response, MAX_MIRROR_JSON_BYTES))
    if not isinstance(proof, dict) or proof.get("sha256") != manifest.sha256 or type(proof.get("sizeBytes")) is not int or proof["sizeBytes"] != manifest.sizeBytes:
        raise MirrorSyncError("Mirror archive acknowledgement differs from the verified bytes")


def verify_public_archive(transport, manifest: Manifest) -> None:
    with transport.request("GET", manifest.downloadUrl, headers={"Accept": "application/octet-stream", "Accept-Encoding": "identity"}) as response:
        if response.status != 200:
            raise MirrorSyncError(f"Public mirror archive returned HTTP {response.status}; redirects are not allowed")
        verify_archive(response, manifest)


def publish_manifest(transport, manifest: Manifest, token: str) -> None:
    body = (json.dumps(asdict(manifest), separators=(",", ":")) + "\n").encode("utf-8")
    headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json", "Content-Length": str(len(body))}
    with transport.request("PUT", MIRROR_ORIGIN + "/v1/admin/ventoy/latest.json", headers=headers, data=body) as response:
        if response.status not in {200, 201}:
            raise MirrorSyncError(f"Mirror manifest publication returned HTTP {response.status}; redirects are not allowed")
        acknowledgement = parse_manifest(decode_json(read_bounded(response, MAX_MIRROR_JSON_BYTES)))
        if acknowledgement != manifest:
            raise MirrorSyncError("Published mirror manifest differs from the verified release")


def sync_mirror(transport, token: str, *, github_token: str = "") -> dict:
    release = read_source_release(transport, github_token)
    manifest = release.manifest
    current = read_current_manifest(transport)
    check_new_release(current, manifest)
    changed = current != manifest
    if changed:
        with tempfile.TemporaryDirectory(prefix="vendroid-mirror-") as directory:
            path = Path(directory) / manifest.assetName
            with open_source_archive(transport, release.source_url) as response, path.open("wb") as destination:
                verify_archive(response, manifest, destination)
                destination.flush()
                os.fsync(destination.fileno())
            upload_archive(transport, manifest, path, token)
    # Verify the public stream before advertising it, including unchanged releases.
    verify_public_archive(transport, manifest)
    if changed:
        publish_manifest(transport, manifest, token)
    if read_current_manifest(transport) != manifest:
        raise MirrorSyncError("Public mirror metadata readback differs from the proposed release")
    return {
        "schemaVersion": 1, "status": "published" if changed else "unchanged",
        "manifest": asdict(manifest), "sourceReleaseId": release.release_id,
        "sourceAssetId": release.asset_id, "sourceDownloaded": changed,
        "upstreamPublishedSha256": True, "publicArchiveSha256Verified": True,
        "publicManifestReadbackVerified": True,
    }


def validate_token(value: str, label: str, *, optional: bool = False) -> str:
    if optional and not value:
        return value
    if not 32 <= len(value) <= 4096 or any(ord(char) < 33 or ord(char) > 126 for char in value):
        raise MirrorSyncError(f"{label} must be a nonempty private ASCII token without whitespace")
    return value


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        if not re.fullmatch(r"[0-9a-f]{40}", args.commit, re.ASCII):
            raise MirrorSyncError("Sync proof must identify the exact repository commit")
        token = validate_token(os.environ.get("VENDROID_MIRROR_PUBLISH_TOKEN", ""), "VENDROID_MIRROR_PUBLISH_TOKEN")
        github_token = validate_token(os.environ.get("GH_TOKEN", ""), "GH_TOKEN", optional=True)
        proof = sync_mirror(HttpTransport(), token, github_token=github_token)
        proof["commit"] = args.commit
        args.output.write_text(json.dumps(proof, indent=2) + "\n", encoding="utf-8")
        print(f"Ventoy {proof['manifest']['version']} mirror {proof['status']}; public archive SHA-256 verified")
        return 0
    except MirrorSyncError as error:
        print(f"Mirror sync failed: {error}", file=sys.stderr)
        return 1
    except Exception as error:
        # URLs may contain delivery tokens and raw errors may contain credentials.
        print(f"Mirror sync failed during an operation ({type(error).__name__}); inspect public readback before retrying", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
