#!/usr/bin/env python3
"""Publish an already-verified AAB through Google's official Android Publisher client."""

from __future__ import annotations

import argparse
from dataclasses import asdict, dataclass
import json
from pathlib import Path
import re
import sys

from release_plan import MAX_VERSION_CODE, PACKAGE_NAME
from verify_bundle import UPLOAD_CERTIFICATE_SHA256, file_sha256


API_RETRIES = 2


class PlayPublishError(ValueError):
    pass


def resolve_play_track(channel: str, beta_track: str = "") -> str:
    if channel not in {"beta", "production"}:
        raise PlayPublishError("Only beta and production Vendroid channels are supported")
    configured = beta_track or "alpha"
    if configured not in {"alpha", "beta"}:
        raise PlayPublishError("VENDROID_PLAY_BETA_TRACK must be alpha for closed testing or beta for open testing")
    return configured if channel == "beta" else "production"


@dataclass(frozen=True)
class VerifiedBundle:
    path: Path
    version_code: int
    version_name: str
    sha256: str
    commit: str


def parse_version_code(value) -> int:
    if isinstance(value, bool) or not isinstance(value, (str, int)):
        raise PlayPublishError("Google Play returned an invalid versionCode")
    if isinstance(value, str) and not re.fullmatch(r"[1-9][0-9]*", value):
        raise PlayPublishError("Google Play returned an invalid versionCode")
    code = int(value)
    if not 1 <= code <= MAX_VERSION_CODE:
        raise PlayPublishError("versionCode is outside Google Play's supported range")
    return code


def verified_bundle(
    path: Path, metadata: dict, *, version_code: int, version_name: str, commit: str,
) -> VerifiedBundle:
    if not isinstance(metadata, dict) or metadata.get("packageName") != PACKAGE_NAME:
        raise PlayPublishError("Bundle proof must identify the shared Vendroid package")
    if parse_version_code(metadata.get("versionCode")) != parse_version_code(version_code):
        raise PlayPublishError("Bundle versionCode differs from the release plan")
    if metadata.get("versionName") != version_name:
        raise PlayPublishError("Bundle versionName differs from the release plan")
    if not re.fullmatch(r"[0-9a-f]{40}", commit) or metadata.get("commit") != commit:
        raise PlayPublishError("Bundle proof must match the exact validated commit")
    if metadata.get("native16KbPassed") is not True:
        raise PlayPublishError("Native 16 KB bundle validation must pass before publishing")
    if metadata.get("uploadCertificateSha256") != UPLOAD_CERTIFICATE_SHA256:
        raise PlayPublishError("Bundle proof must match Vendroid's existing upload certificate")
    if not path.is_file() or path.is_symlink():
        raise PlayPublishError("A regular verified AAB file is required")
    digest = file_sha256(path)
    if metadata.get("bundleSha256") != digest:
        raise PlayPublishError("AAB checksum differs from the verified bundle proof")
    return VerifiedBundle(path, version_code, version_name, digest, commit)


def response_list(response: dict, field: str) -> list:
    if not isinstance(response, dict) or not isinstance(response.get(field, []), list):
        raise PlayPublishError("Google Play returned a malformed release inventory")
    return response.get(field, [])


def highest_version_code(tracks: dict, bundles: dict, apks: dict) -> int:
    codes = []
    for track in response_list(tracks, "tracks"):
        if not isinstance(track, dict) or not isinstance(track.get("releases", []), list):
            raise PlayPublishError("Google Play returned a malformed track")
        for release in track.get("releases", []):
            if not isinstance(release, dict) or not isinstance(release.get("versionCodes", []), list):
                raise PlayPublishError("Google Play returned a malformed track release")
            codes.extend(parse_version_code(value) for value in release.get("versionCodes", []))
    for response, field in ((bundles, "bundles"), (apks, "apks")):
        for artifact in response_list(response, field):
            if not isinstance(artifact, dict):
                raise PlayPublishError("Google Play returned a malformed binary inventory")
            codes.append(parse_version_code(artifact.get("versionCode")))
    return max(codes, default=0)


def publish_bundle(client, bundle: VerifiedBundle, *, track: str, release_name: str, media_body) -> dict:
    if track not in {"alpha", "beta", "production"}:
        raise PlayPublishError("Only closed testing, open testing, and production publishing are supported")
    if not release_name or len(release_name) > 50 or "\n" in release_name:
        raise PlayPublishError("Play release names must contain 1 to 50 characters")
    edits = client.edits()
    edit = edits.insert(packageName=PACKAGE_NAME, body={}).execute(num_retries=API_RETRIES)
    if not isinstance(edit, dict) or not isinstance(edit.get("id"), str) or not edit["id"]:
        raise PlayPublishError("Google Play did not create a valid edit")
    args = {"packageName": PACKAGE_NAME, "editId": edit["id"]}
    committed = False
    try:
        tracks = edits.tracks().list(**args).execute(num_retries=API_RETRIES)
        bundles = edits.bundles().list(**args).execute(num_retries=API_RETRIES)
        apks = edits.apks().list(**args).execute(num_retries=API_RETRIES)
        maximum = highest_version_code(tracks, bundles, apks)
        if bundle.version_code <= maximum:
            raise PlayPublishError(
                f"versionCode {bundle.version_code} must be greater than the existing Play maximum {maximum}; "
                "increment the checked-in versionCode before distributing another release"
            )
        uploaded = edits.bundles().upload(**args, media_body=media_body).execute(num_retries=API_RETRIES)
        if not isinstance(uploaded, dict) or parse_version_code(uploaded.get("versionCode")) != bundle.version_code:
            raise PlayPublishError("Uploaded bundle versionCode differs from the verified artifact")
        if uploaded.get("sha256") != bundle.sha256:
            raise PlayPublishError("Google Play's uploaded checksum differs from the verified artifact")
        edits.tracks().update(**args, track=track, body={
            "track": track,
            "releases": [{
                "name": release_name,
                "versionCodes": [str(bundle.version_code)],
                "status": "completed",
            }],
        }).execute(num_retries=API_RETRIES)
        edits.validate(**args).execute(num_retries=API_RETRIES)
        edits.commit(
            **args, changesNotSentForReview=False,
            changesInReviewBehavior="ERROR_IF_IN_REVIEW",
        ).execute(num_retries=API_RETRIES)
        committed = True
        return {
            "packageName": PACKAGE_NAME,
            "track": track,
            "versionCode": bundle.version_code,
            "versionName": bundle.version_name,
            "bundleSha256": bundle.sha256,
            "commit": bundle.commit,
            "status": "committed",
        }
    finally:
        if not committed:
            try:
                edits.delete(**args).execute(num_retries=API_RETRIES)
            except Exception:
                # Preserve the original failure. Uncommitted edits also expire.
                pass


def google_client(credentials_path: Path):
    # Keep optional Google dependencies out of the stdlib regression-test imports.
    from google.oauth2 import service_account
    from google_auth_httplib2 import AuthorizedHttp
    from googleapiclient.discovery import build
    import httplib2

    credentials = service_account.Credentials.from_service_account_file(
        str(credentials_path), scopes=["https://www.googleapis.com/auth/androidpublisher"],
    )
    return build(
        "androidpublisher", "v3", cache_discovery=False, static_discovery=True,
        http=AuthorizedHttp(credentials, http=httplib2.Http(timeout=60)),
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--credentials", type=Path, required=True)
    parser.add_argument("--bundle", type=Path, required=True)
    parser.add_argument("--metadata", type=Path, required=True)
    parser.add_argument("--version-code", type=int, required=True)
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--track", choices=("beta", "production"), required=True)
    parser.add_argument("--beta-track", default="", help="Play testing track: alpha (default closed) or beta (open)")
    parser.add_argument("--release-name", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        track = resolve_play_track(args.track, args.beta_track)
        bundle = verified_bundle(
            args.bundle, json.loads(args.metadata.read_text(encoding="utf-8")),
            version_code=args.version_code, version_name=args.version_name, commit=args.commit,
        )
        from googleapiclient.http import MediaFileUpload

        client = google_client(args.credentials)
        try:
            proof = publish_bundle(
                client, bundle, track=track, release_name=args.release_name,
                media_body=MediaFileUpload(str(bundle.path), mimetype="application/octet-stream", resumable=True),
            )
        finally:
            client.close()
        proof["channel"] = args.track
        args.output.write_text(json.dumps(proof, indent=2) + "\n", encoding="utf-8")
        print(f"Submitted versionCode {bundle.version_code} to Google Play {track} for the {args.track} channel. Availability depends on Play review.")
        return 0
    except (PlayPublishError, OSError, json.JSONDecodeError) as error:
        print(f"Play publishing failed: {error}", file=sys.stderr)
        return 1
    except Exception as error:
        # Credential values and Google's raw error bodies must never reach logs.
        print(f"Play publishing failed during a Google API operation ({type(error).__name__}). "
              "Check Console setup, service-account access, and the current release before retrying.",
              file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
