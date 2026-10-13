#!/usr/bin/env python3
"""Verify an AAB's package, version, upload signer, and native validation proof."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

from release_plan import MAX_VERSION_CODE, PACKAGE_NAME


ANDROID = "{http://schemas.android.com/apk/res/android}"
UPLOAD_CERTIFICATE_SHA256 = "b388ad9d2290967480630cbd4a48f4f8c6784305c05da3541ad780821d9fbeb1"


class BundleVerificationError(ValueError):
    pass


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def manifest_metadata(xml: str, *, version_code: int, version_name: str) -> dict:
    try:
        manifest = ET.fromstring(xml)
    except ET.ParseError as error:
        raise BundleVerificationError("bundletool did not return a valid manifest") from error
    if manifest.tag != "manifest" or manifest.get("package") != PACKAGE_NAME:
        raise BundleVerificationError("Bundle package must be the shared Vendroid application")
    if not 1 <= version_code <= MAX_VERSION_CODE:
        raise BundleVerificationError("Expected versionCode is outside the supported range")
    if manifest.get(ANDROID + "versionCode") != str(version_code):
        raise BundleVerificationError("Bundle versionCode differs from the release plan")
    if manifest.get(ANDROID + "versionName") != version_name:
        raise BundleVerificationError("Bundle versionName differs from the release plan")
    application = manifest.find("application")
    if application is None or application.get(ANDROID + "debuggable", "false") != "false":
        raise BundleVerificationError("Play bundles must be non-debuggable")
    sdk = manifest.find("uses-sdk")
    if sdk is None:
        raise BundleVerificationError("Bundle SDK metadata is missing")
    try:
        minimum_sdk = int(sdk.get(ANDROID + "minSdkVersion", ""))
        target_sdk = int(sdk.get(ANDROID + "targetSdkVersion", ""))
    except ValueError as error:
        raise BundleVerificationError("Bundle SDK metadata must contain integer API levels") from error
    if minimum_sdk != 23 or target_sdk < 36:
        raise BundleVerificationError("Play bundle must preserve minSdk 23 and target at least API 36")
    return {
        "packageName": PACKAGE_NAME,
        "versionCode": version_code,
        "versionName": version_name,
        "minSdkVersion": str(minimum_sdk),
        "targetSdkVersion": sdk.get(ANDROID + "targetSdkVersion"),
    }


def upload_certificate(jarsigner_output: str, keytool_output: str) -> str:
    if "jar verified." not in jarsigner_output.lower() or any(
        warning in jarsigner_output.lower()
        for warning in ("jar is unsigned", "unsigned entries", "disabled algorithm")
    ):
        raise BundleVerificationError("AAB signature verification failed or contains unsigned entries")
    signers = re.findall(r"^Signer #[0-9]+:", keytool_output, re.MULTILINE)
    digests = re.findall(r"^\s*SHA256:\s*([0-9A-Fa-f:]+)\s*$", keytool_output, re.MULTILINE)
    if len(signers) != 1 or not digests:
        raise BundleVerificationError("AAB must have exactly one upload signer")
    certificate = digests[0].replace(":", "").lower()
    if certificate != UPLOAD_CERTIFICATE_SHA256:
        raise BundleVerificationError("AAB upload certificate differs from the existing Vendroid key")
    return certificate


def native_proof(report: dict, bundle_hash: str) -> None:
    if not isinstance(report, dict) or report.get("artifact_type") != "aab":
        raise BundleVerificationError("AAB native validation report is missing")
    if report.get("sha256") != bundle_hash or report.get("native_16kb_passed") is not True:
        raise BundleVerificationError("Native 16 KB evidence must pass for the exact bundle")


def run_tool(command: list[str], *, include_stderr: bool = True) -> str:
    result = subprocess.run(command, capture_output=True, text=True, check=False, timeout=120)
    output = result.stdout + result.stderr
    if result.returncode != 0:
        raise BundleVerificationError(f"{Path(command[0]).name} verification command failed")
    return output if include_stderr else result.stdout


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", type=Path, required=True)
    parser.add_argument("--bundletool", type=Path, required=True)
    parser.add_argument("--native-report", type=Path, required=True)
    parser.add_argument("--version-code", type=int, required=True)
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        if not re.fullmatch(r"[0-9a-f]{40}", args.commit):
            raise BundleVerificationError("An exact tested commit is required")
        metadata = manifest_metadata(
            run_tool(["java", "-jar", str(args.bundletool), "dump", "manifest",
                      f"--bundle={args.bundle}", "--module=base"], include_stderr=False),
            version_code=args.version_code,
            version_name=args.version_name,
        )
        jarsigner_output = run_tool([
            "jarsigner", "-J-Duser.language=en", "-J-Duser.country=US",
            "-verify", "-verbose", "-certs", str(args.bundle),
        ])
        keytool_output = run_tool([
            "keytool", "-J-Duser.language=en", "-J-Duser.country=US",
            "-printcert", "-jarfile", str(args.bundle),
        ])
        certificate = upload_certificate(jarsigner_output, keytool_output)
        bundle_hash = file_sha256(args.bundle)
        native_proof(json.loads(args.native_report.read_text(encoding="utf-8")), bundle_hash)
        metadata.update({
            "bundleSha256": bundle_hash,
            "commit": args.commit,
            "uploadCertificateSha256": certificate,
            "native16KbPassed": True,
        })
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
        args.output.with_name("aab-signature.txt").write_text(jarsigner_output + "\n" + keytool_output,
                                                            encoding="utf-8")
        print(f"Verified {metadata['packageName']} {args.version_name} ({args.version_code}) at {args.commit}")
        return 0
    except (BundleVerificationError, OSError, json.JSONDecodeError, subprocess.TimeoutExpired) as error:
        print(f"Bundle verification failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
