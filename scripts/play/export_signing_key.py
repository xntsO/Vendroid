#!/usr/bin/env python3
"""Export the existing Vendroid key using Google's pinned PEPK tool in CI only."""

from __future__ import annotations

import argparse
import base64
import binascii
from dataclasses import dataclass
import hashlib
import io
import json
import os
from pathlib import Path
import re
import signal
import stat
import subprocess
import sys
import tempfile
from typing import Mapping
import urllib.request
import zipfile

from verify_bundle import UPLOAD_CERTIFICATE_SHA256


PEPK_URL = "https://www.gstatic.com/play-apps-publisher-rapid/signing-tool/prod/pepk.jar"
PEPK_SHA256 = "aaccc0774b240aa5304bdad2a49865e92f229ca73209ecb6eaafe75dc858e24e"
PEPK_SOURCE_URL = "https://www.gstatic.com/play-apps-publisher-rapid/signing-tool/prod/pepk-src.jar"
PEPK_SOURCE_SHA256 = "dc29a1d192eef9371eec0d2c134879fefc0a9dcf0c7046d6c8f3d302d0009b09"
PUBLIC_KEY_PATH = Path(__file__).with_name("encryption_public_key.pem")
PUBLIC_KEY_SHA256 = "37831b46ba92bc7e806d907efbe55a4b1e78145fe142b0bc6a390f426153f0e3"
ENCRYPTION_RSA_BYTES = 384  # The pinned Console public key is RSA-3072.
EXPECTED_CERTIFICATE_SHA256 = UPLOAD_CERTIFICATE_SHA256
SECRET_NAMES = (
    "VENDROID_KEYSTORE_BASE64", "VENDROID_KEYSTORE_PASSWORD",
    "VENDROID_KEY_ALIAS", "VENDROID_KEY_PASSWORD",
)
PUBLIC_OUTPUT_NAMES = (
    "vendroid-play-signing-key.zip", "certificate.pem", "export-evidence.json", "SHA256SUMS",
)
SAFE_TOOL_ENVIRONMENT = (
    "PATH", "JAVA_HOME", "HOME", "LANG", "LC_ALL", "TMPDIR", "TEMP", "TMP",
    "SYSTEMROOT", "WINDIR",
)
MAX_KEYSTORE_BYTES = 16 * 1024 * 1024
MAX_JAR_BYTES = 32 * 1024 * 1024
MAX_EXPORT_BYTES = 256 * 1024
MAX_CERTIFICATE_BYTES = 16 * 1024


class SigningKeyExportError(ValueError):
    """Only controlled, nonsensitive messages may cross the CLI boundary."""


@dataclass(frozen=True, repr=False)
class SigningInputs:
    keystore: bytes
    store_password: str
    alias: str
    key_password: str

    @classmethod
    def from_environment(cls, environment: Mapping[str, str]) -> SigningInputs:
        values = [environment.get(name, "") for name in SECRET_NAMES]
        if any(not value for value in values):
            raise SigningKeyExportError("The four existing signing secrets are required")
        encoded, store_password, alias, key_password = values
        if any("\0" in value for value in values):
            raise SigningKeyExportError("Signing inputs contain an unsupported character")
        if any("\r" in value or "\n" in value for value in (store_password, alias, key_password)):
            raise SigningKeyExportError("Signing passwords and alias must each be one line")
        if max(len(store_password), len(alias), len(key_password)) > 4096:
            raise SigningKeyExportError("Signing input length exceeds the supported limit")
        if len(encoded) > MAX_KEYSTORE_BYTES * 2:
            raise SigningKeyExportError("The encoded keystore exceeds the supported limit")
        try:
            compact = encoded.translate(str.maketrans("", "", " \t\r\n"))
            keystore = base64.b64decode(compact, validate=True)
        except (ValueError, binascii.Error):
            raise SigningKeyExportError("The keystore secret is not valid base64") from None
        if not 1 <= len(keystore) <= MAX_KEYSTORE_BYTES:
            raise SigningKeyExportError("The decoded keystore size is outside the supported limit")
        return cls(keystore, store_password, alias, key_password)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def tool_environment(environment: Mapping[str, str]) -> dict[str, str]:
    # No credentials, JVM injection options, or tracing switches reach child tools.
    return {name: environment[name] for name in SAFE_TOOL_ENVIRONMENT if name in environment}


def run_private_command(command: list[str], environment: Mapping[str, str], stage: str) -> bytes:
    try:
        result = subprocess.run(
            command, stdin=subprocess.DEVNULL, capture_output=True, check=False,
            env=dict(environment), timeout=120,
        )
    except (OSError, subprocess.SubprocessError):
        # TimeoutExpired and CalledProcessError can expose command-line passwords.
        raise SigningKeyExportError(f"The {stage} tool did not complete") from None
    if result.returncode != 0:
        # Google's flag-parser error prints all arguments, including passwords.
        raise SigningKeyExportError(f"The {stage} tool failed; its private output was discarded")
    return result.stdout


def write_private_file(path: Path, data: bytes) -> None:
    descriptor = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    with os.fdopen(descriptor, "wb") as output:
        output.write(data)


def download_pepk(destination: Path) -> None:
    try:
        with urllib.request.urlopen(PEPK_URL, timeout=60) as response:
            if response.geturl() != PEPK_URL:
                raise SigningKeyExportError("Google's PEPK download redirected unexpectedly")
            chunks = []
            length = 0
            while chunk := response.read(1024 * 1024):
                length += len(chunk)
                if length > MAX_JAR_BYTES:
                    raise SigningKeyExportError("Google's PEPK download exceeds its size limit")
                chunks.append(chunk)
    except OSError:
        raise SigningKeyExportError("Google's PEPK download failed") from None
    jar = b"".join(chunks)
    if sha256(jar) != PEPK_SHA256:
        raise SigningKeyExportError("Google's PEPK binary does not match the reviewed SHA-256")
    write_private_file(destination, jar)


def certificate_der(pem: bytes) -> bytes:
    if len(pem) > MAX_CERTIFICATE_BYTES:
        raise SigningKeyExportError("The public certificate exceeds its size limit")
    match = re.fullmatch(
        rb"-----BEGIN CERTIFICATE-----\r?\n([A-Za-z0-9+/=\r\n]+)"
        rb"-----END CERTIFICATE-----\r?\n?", pem,
    )
    if match is None:
        raise SigningKeyExportError("PEPK must include exactly one public PEM certificate")
    try:
        der = base64.b64decode(match[1].replace(b"\r", b"").replace(b"\n", b""), validate=True)
    except binascii.Error:
        raise SigningKeyExportError("PEPK's public certificate encoding is invalid") from None
    if not der:
        raise SigningKeyExportError("PEPK's public certificate is empty")
    return der


def looks_like_unencrypted_der(data: bytes) -> bool:
    """Recognize complete PKCS8/PKCS1/SEC1/PFX versioned DER sequences, not ciphertext."""
    if len(data) < 5 or data[0] != 0x30:
        return False
    length = data[1]
    position = 2
    if length & 0x80:
        count = length & 0x7F
        if not 1 <= count <= 4 or len(data) < position + count:
            return False
        length = int.from_bytes(data[position:position + count], "big")
        position += count
    return len(data) == position + length and data[position:position + 3] in (
        b"\x02\x01\x00", b"\x02\x01\x01", b"\x02\x01\x03",
    )


def validate_export_zip(data: bytes, verified_certificate: bytes) -> tuple[bytes, int]:
    if not 1 <= len(data) <= MAX_EXPORT_BYTES:
        raise SigningKeyExportError("PEPK's encrypted ZIP size is outside its supported limit")
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            entries = archive.infolist()
            if len(entries) != 2 or {entry.filename for entry in entries} != {
                "encryptedPrivateKey", "certificate.pem",
            }:
                raise SigningKeyExportError("PEPK ZIP must contain only the encrypted key and public certificate")
            for entry in entries:
                mode = entry.external_attr >> 16
                if (entry.is_dir() or stat.S_IFMT(mode) not in (0, stat.S_IFREG) or entry.flag_bits & 1
                        or entry.compress_type not in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED)
                        or not 1 <= entry.file_size <= MAX_EXPORT_BYTES):
                    raise SigningKeyExportError("PEPK ZIP contains an unsupported entry")
            pem = archive.read("certificate.pem")
            if certificate_der(pem) != verified_certificate:
                raise SigningKeyExportError("PEPK's certificate differs from the verified existing signing key")
            encrypted = archive.read("encryptedPrivateKey")
    except (zipfile.BadZipFile, RuntimeError, NotImplementedError, EOFError):
        raise SigningKeyExportError("PEPK's encrypted ZIP is malformed") from None
    wrapped_length = len(encrypted) - ENCRYPTION_RSA_BYTES
    if wrapped_length < 16 or wrapped_length % 8:
        raise SigningKeyExportError("PEPK's key payload does not match the RSA-AES wrapping format")
    if (b"-----BEGIN " in encrypted or encrypted.startswith(b"\xfe\xed\xfe\xed")
            or looks_like_unencrypted_der(encrypted)):
        raise SigningKeyExportError("PEPK's key payload contains recognizable unencrypted key material")
    return pem, len(encrypted)


def export_signing_key(output_dir: Path, commit: str, environment: Mapping[str, str]) -> None:
    if re.fullmatch(r"[0-9a-f]{40}", commit) is None:
        raise SigningKeyExportError("A full commit SHA is required")
    if environment.get("GITHUB_SHA", commit) != commit:
        raise SigningKeyExportError("The export commit differs from the workflow commit")
    if output_dir.exists() or output_dir.is_symlink() or not output_dir.parent.is_dir():
        raise SigningKeyExportError("The export output must be a new directory with an existing parent")
    public_key = PUBLIC_KEY_PATH.read_bytes()
    if sha256(public_key) != PUBLIC_KEY_SHA256:
        raise SigningKeyExportError("Google's encryption public key differs from the reviewed Console key")
    child_environment = tool_environment(environment)
    current_commit = run_private_command(["git", "rev-parse", "HEAD"], child_environment, "commit check")
    if current_commit.strip() != commit.encode("ascii"):
        raise SigningKeyExportError("The checkout differs from the export commit")
    inputs = SigningInputs.from_environment(environment)
    temporary_root = environment.get("RUNNER_TEMP")
    if not temporary_root or not Path(temporary_root).is_dir():
        raise SigningKeyExportError("A runner temporary directory is required")

    with tempfile.TemporaryDirectory(prefix="vendroid-pepk-", dir=temporary_root) as directory:
        private = Path(directory)
        os.chmod(private, 0o700)
        keystore = private / "release.jks"
        password_file = private / "store.pass"
        certificate_file = private / "certificate.der"
        encrypted_zip = private / "export.zip"
        jar = private / "pepk.jar"
        encryption_key = private / "encryption-public-key.pem"
        write_private_file(keystore, inputs.keystore)
        write_private_file(password_file, (inputs.store_password + "\n").encode("utf-8"))
        write_private_file(encryption_key, public_key)
        run_private_command([
            "keytool", "-J-Duser.language=en", "-J-Duser.country=US", "-exportcert",
            "-keystore", str(keystore), "-alias", inputs.alias,
            "-storepass:file", str(password_file), "-file", str(certificate_file),
        ], child_environment, "certificate export")
        verified_certificate = certificate_file.read_bytes()
        if sha256(verified_certificate) != EXPECTED_CERTIFICATE_SHA256:
            raise SigningKeyExportError("The existing signing certificate does not match Vendroid releases")
        download_pepk(jar)
        # PEPK lacks a password-file option and requires flags in noninteractive CI.
        # Never echo this argv or relay the tool's output, even on parse/timeout errors.
        run_private_command([
            "java", "-jar", str(jar), f"--keystore={keystore}", f"--alias={inputs.alias}",
            f"--output={encrypted_zip}", "--include-cert", "--rsa-aes-encryption",
            f"--encryption-key-path={encryption_key}",
            f"--keystore-pass={inputs.store_password}", f"--key-pass={inputs.key_password}",
        ], child_environment, "PEPK key export")
        exported = encrypted_zip.read_bytes()
        public_certificate, encrypted_length = validate_export_zip(exported, verified_certificate)
        proof = {
            "schema": 1, "commit": commit, "signingCertificateSha256": sha256(verified_certificate),
            "googleEncryptionPublicKeySha256": PUBLIC_KEY_SHA256,
            "googleEncryptionRsaBits": ENCRYPTION_RSA_BYTES * 8,
            "pepkTool": {"url": PEPK_URL, "sha256": PEPK_SHA256},
            "auditedSource": {"url": PEPK_SOURCE_URL, "sha256": PEPK_SOURCE_SHA256},
            "encryption": "CKM_RSA_AES_KEY_WRAP", "encryptedPrivateKeyBytes": encrypted_length,
            "zipEntries": ["encryptedPrivateKey", "certificate.pem"],
            "encryptedZipSha256": sha256(exported), "certificatePemSha256": sha256(public_certificate),
            "formatVerified": True,
        }
        outputs = {
            "vendroid-play-signing-key.zip": exported,
            "certificate.pem": public_certificate,
            "export-evidence.json": (json.dumps(proof, indent=2) + "\n").encode("utf-8"),
        }
        outputs["SHA256SUMS"] = "".join(
            f"{sha256(value)}  {name}\n" for name, value in outputs.items()
        ).encode("ascii")
        # Nothing enters the output directory until the complete export is verified.
        output_dir.mkdir(mode=0o700, exist_ok=False)
        try:
            for name, data in outputs.items():
                write_private_file(output_dir / name, data)
        except BaseException:
            for name in PUBLIC_OUTPUT_NAMES:
                (output_dir / name).unlink(missing_ok=True)
            output_dir.rmdir()
            raise


def _interrupted(_number: int, _frame: object) -> None:
    raise SigningKeyExportError("PEPK export was interrupted")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--commit", required=True)
    args = parser.parse_args()
    previous_handler = signal.signal(signal.SIGTERM, _interrupted)
    try:
        export_signing_key(args.output_dir, args.commit, os.environ)
        print("Created the Google-encrypted signing-key ZIP and public verification evidence.")
        return 0
    except SigningKeyExportError as error:
        print(f"PEPK export failed: {error}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("PEPK export was interrupted.", file=sys.stderr)
        return 130
    except Exception:
        # Do not print unexpected exception text, command lines, or traceback contexts.
        print("PEPK export failed; private tool output was discarded.", file=sys.stderr)
        return 1
    finally:
        signal.signal(signal.SIGTERM, previous_handler)


if __name__ == "__main__":
    raise SystemExit(main())
