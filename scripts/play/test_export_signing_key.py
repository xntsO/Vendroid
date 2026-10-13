import base64
from contextlib import redirect_stderr, redirect_stdout
import io
import json
import os
from pathlib import Path
import stat
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import warnings
import zipfile

import export_signing_key as export


COMMIT = "a" * 40
# Format fixtures contain no actual private key or signing credentials.
CERTIFICATE = b"public-certificate-format-fixture"
CERTIFICATE_PEM = (b"-----BEGIN CERTIFICATE-----\n" + base64.b64encode(CERTIFICATE)
                   + b"\n-----END CERTIFICATE-----\n")
CIPHERTEXT = b"\xa5" * (export.ENCRYPTION_RSA_BYTES + 16)


def make_zip(entries=None):
    output = io.BytesIO()
    with warnings.catch_warnings(), zipfile.ZipFile(output, "w") as archive:
        warnings.simplefilter("ignore", UserWarning)
        for name, data in entries if entries is not None else [("encryptedPrivateKey", CIPHERTEXT), ("certificate.pem", CERTIFICATE_PEM)]:
            archive.writestr(name, data)
    return output.getvalue()


class ExportFormatTest(unittest.TestCase):
    def test_reviewed_console_public_key_has_the_exact_pin(self):
        self.assertEqual(export.PUBLIC_KEY_SHA256, export.sha256(export.PUBLIC_KEY_PATH.read_bytes()))

    def test_expected_two_entry_zip_matches_the_preverified_certificate(self):
        pem, length = export.validate_export_zip(make_zip(), CERTIFICATE)
        self.assertEqual(CERTIFICATE_PEM, pem)
        self.assertEqual(len(CIPHERTEXT), length)

    def test_extra_duplicate_missing_and_traversal_entries_are_rejected(self):
        entries = [("encryptedPrivateKey", CIPHERTEXT), ("certificate.pem", CERTIFICATE_PEM)]
        cases = [entries + [("release.jks", b"must-not-be-published")],
                 entries + [("encryptedPrivateKey", CIPHERTEXT)], entries[:1],
                 [("encryptedPrivateKey", CIPHERTEXT), ("../certificate.pem", CERTIFICATE_PEM)]]
        for case in cases:
            with self.subTest(entries=[name for name, _ in case]):
                with self.assertRaises(export.SigningKeyExportError):
                    export.validate_export_zip(make_zip(case), CERTIFICATE)

    def test_wrong_certificate_and_private_pem_disguised_as_certificate_are_rejected(self):
        for pem in (CERTIFICATE_PEM.replace(base64.b64encode(CERTIFICATE), base64.b64encode(b"wrong")),
                    b"-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----\n",
                    CERTIFICATE_PEM + CERTIFICATE_PEM):
            with self.subTest(pem=pem), self.assertRaises(export.SigningKeyExportError):
                export.validate_export_zip(make_zip([("encryptedPrivateKey", CIPHERTEXT), ("certificate.pem", pem)]),
                                           CERTIFICATE)

    def test_plaintext_private_keys_keystores_and_wrong_wrapping_lengths_are_rejected(self):
        marker = b"-----BEGIN PRIVATE KEY-----"
        # A complete versioned DER sequence deliberately has a valid wrapping-size residue.
        der_body = b"\x02\x01\x00" + b"\0" * (len(CIPHERTEXT) - 7)
        raw_der = b"\x30\x82" + len(der_body).to_bytes(2, "big") + der_body
        cases = (marker + b"A" * (len(CIPHERTEXT) - len(marker)),
                 b"\xfe\xed\xfe\xed" + b"A" * (len(CIPHERTEXT) - 4), raw_der,
                 CIPHERTEXT[:-1], b"\xa5" * export.ENCRYPTION_RSA_BYTES)
        for key in cases:
            with self.subTest(length=len(key)), self.assertRaises(export.SigningKeyExportError):
                export.validate_export_zip(make_zip([("encryptedPrivateKey", key), ("certificate.pem", CERTIFICATE_PEM)]),
                                           CERTIFICATE)

    def test_symlink_and_oversized_expanded_entries_are_rejected_before_reading(self):
        link = zipfile.ZipInfo("certificate.pem")
        link.create_system = 3
        link.external_attr = (stat.S_IFLNK | 0o777) << 16
        with self.assertRaises(export.SigningKeyExportError):
            export.validate_export_zip(make_zip([("encryptedPrivateKey", CIPHERTEXT), (link, CERTIFICATE_PEM)]),
                                       CERTIFICATE)
        output = io.BytesIO()
        with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("encryptedPrivateKey", b"\0" * (export.MAX_EXPORT_BYTES + 1))
            archive.writestr("certificate.pem", CERTIFICATE_PEM)
        with self.assertRaises(export.SigningKeyExportError):
            export.validate_export_zip(output.getvalue(), CERTIFICATE)

    def test_corrupt_zip_and_crc_fail_closed(self):
        damaged = bytearray(make_zip())
        with zipfile.ZipFile(io.BytesIO(damaged)) as archive:
            entry = archive.getinfo("encryptedPrivateKey")
            start = entry.header_offset + 30 + len(entry.filename.encode()) + len(entry.extra)
        damaged[start] ^= 1
        for data in (b"not a ZIP", bytes(damaged)):
            with self.assertRaises(export.SigningKeyExportError):
                export.validate_export_zip(data, CERTIFICATE)


class ExportBoundaryTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.runner_temp = self.root / "runner"
        self.runner_temp.mkdir()
        self.output = self.root / "pepk-export"
        self.environment = {
            "VENDROID_KEYSTORE_BASE64": base64.b64encode(b"keystore-fixture").decode(),
            "VENDROID_KEYSTORE_PASSWORD": "fixture-store-password",
            "VENDROID_KEY_ALIAS": "fixture-alias",
            "VENDROID_KEY_PASSWORD": "--fixture-key-password=with-equals",
            "RUNNER_TEMP": str(self.runner_temp), "GITHUB_SHA": COMMIT, "PATH": "/bin",
            "OTHER_CREDENTIAL": "unrelated-secret", "JAVA_TOOL_OPTIONS": "-Dprivate=secret",
        }
        self.commands = []
        self.private_paths = []

    def run_fixture(self, command, **kwargs):
        self.commands.append(command)
        self.assertTrue(kwargs["capture_output"])
        self.assertFalse(kwargs["check"])
        self.assertEqual(subprocess.DEVNULL, kwargs["stdin"])
        self.assertEqual({"PATH": "/bin"}, kwargs["env"])
        if command[0] == "git":
            return subprocess.CompletedProcess(command, 0, COMMIT.encode() + b"\n", b"")
        if command[0] == "keytool":
            keystore = Path(command[command.index("-keystore") + 1])
            password = Path(command[command.index("-storepass:file") + 1])
            self.private_paths.append(keystore.parent)
            self.assertEqual(b"keystore-fixture", keystore.read_bytes())
            self.assertEqual(b"fixture-store-password\n", password.read_bytes())
            if os.name == "posix":
                self.assertEqual(0o700, stat.S_IMODE(keystore.parent.stat().st_mode))
                self.assertEqual(0o600, stat.S_IMODE(keystore.stat().st_mode))
                self.assertEqual(0o600, stat.S_IMODE(password.stat().st_mode))
            Path(command[command.index("-file") + 1]).write_bytes(CERTIFICATE)
        elif command[0] == "java":
            self.assertIn("--include-cert", command)
            self.assertIn("--rsa-aes-encryption", command)
            self.assertIn("--key-pass=--fixture-key-password=with-equals", command)
            output = next(value.removeprefix("--output=") for value in command if value.startswith("--output="))
            Path(output).write_bytes(make_zip())
        return subprocess.CompletedProcess(command, 0, b"fixture-store-password", b"private tool output")

    def export_fixture(self, runner=None):
        with patch.object(export, "EXPECTED_CERTIFICATE_SHA256", export.sha256(CERTIFICATE)), \
                patch.object(export, "download_pepk", side_effect=lambda path: path.write_bytes(b"reviewed-tool-fixture")), \
                patch.object(export.subprocess, "run", side_effect=runner or self.run_fixture):
            export.export_signing_key(self.output, COMMIT, self.environment)

    def assert_private_cleanup(self):
        self.assertEqual([], list(self.runner_temp.iterdir()))
        self.assertTrue(all(not path.exists() for path in self.private_paths))

    def test_success_publishes_only_encrypted_zip_public_certificates_and_evidence(self):
        self.export_fixture()
        self.assertEqual(set(export.PUBLIC_OUTPUT_NAMES), {path.name for path in self.output.iterdir()})
        proof = json.loads((self.output / "export-evidence.json").read_text())
        self.assertEqual(COMMIT, proof["commit"])
        self.assertEqual(export.sha256(CERTIFICATE), proof["signingCertificateSha256"])
        self.assertEqual(export.PEPK_SHA256, proof["pepkTool"]["sha256"])
        self.assertTrue(proof["formatVerified"])
        for line in (self.output / "SHA256SUMS").read_text().splitlines():
            digest, name = line.split("  ")
            self.assertEqual(digest, export.sha256((self.output / name).read_bytes()))
        for path in self.output.iterdir():
            self.assertNotIn(b"fixture-store-password", path.read_bytes())
            self.assertNotIn(b"keystore-fixture", path.read_bytes())
        self.assert_private_cleanup()

    def test_wrong_existing_certificate_prevents_pepk_and_creates_no_output(self):
        with patch.object(export.subprocess, "run", side_effect=self.run_fixture), \
                patch.object(export, "download_pepk") as download:
            with self.assertRaisesRegex(export.SigningKeyExportError, "does not match"):
                export.export_signing_key(self.output, COMMIT, self.environment)
            download.assert_not_called()
        self.assertFalse(any(command[0] == "java" for command in self.commands))
        self.assertFalse(self.output.exists())
        self.assert_private_cleanup()

    def test_pepk_error_and_timeout_hide_outputs_and_cleanup_all_secrets(self):
        for timeout in (False, True):
            with self.subTest(timeout=timeout):
                def failing(command, **kwargs):
                    if command[0] != "java":
                        return self.run_fixture(command, **kwargs)
                    if timeout:
                        raise subprocess.TimeoutExpired(command, 120, b"fixture-store-password")
                    return subprocess.CompletedProcess(command, 1, b"fixture-store-password", str(command).encode())
                with self.assertRaises(export.SigningKeyExportError) as failure:
                    self.export_fixture(failing)
                self.assertNotIn("fixture-store-password", str(failure.exception))
                self.assertNotIn("fixture-key-password", str(failure.exception))
                self.assertFalse(self.output.exists())
                self.assert_private_cleanup()

    def test_bad_pepk_zip_and_output_write_failure_cleanup_without_partial_artifacts(self):
        def wrong_zip(command, **kwargs):
            result = self.run_fixture(command, **kwargs)
            if command[0] == "java":
                target = next(value.removeprefix("--output=") for value in command if value.startswith("--output="))
                Path(target).write_bytes(make_zip([("release.jks", b"keystore-fixture")]))
            return result
        with self.assertRaises(export.SigningKeyExportError):
            self.export_fixture(wrong_zip)
        self.assertFalse(self.output.exists())
        self.assert_private_cleanup()
        actual_write = export.write_private_file
        def failed_public_write(path, data):
            if path.parent == self.output and path.name == "certificate.pem":
                raise OSError("fixture-store-password")
            actual_write(path, data)
        with patch.object(export, "write_private_file", side_effect=failed_public_write):
            with self.assertRaises(OSError):
                self.export_fixture()
        self.assertFalse(self.output.exists())
        self.assert_private_cleanup()

    def test_existing_output_and_wrong_commit_are_rejected_without_modifying_anything(self):
        self.output.mkdir()
        keep = self.output / "owner-file"
        keep.write_text("keep")
        with patch.object(export.subprocess, "run") as runner:
            with self.assertRaises(export.SigningKeyExportError):
                export.export_signing_key(self.output, COMMIT, self.environment)
            runner.assert_not_called()
        self.assertEqual("keep", keep.read_text())
        with self.assertRaises(export.SigningKeyExportError):
            export.export_signing_key(self.root / "other", "b" * 40, self.environment)

    def test_malformed_secrets_fail_closed_without_echoing_their_values(self):
        for field, value in (("VENDROID_KEYSTORE_BASE64", "bad-$-base64-secret"),
                             ("VENDROID_KEY_PASSWORD", "secret\nsecond-line"),
                             ("VENDROID_KEY_ALIAS", "secret\0alias"),
                             ("VENDROID_KEYSTORE_PASSWORD", "")):
            environment = {**self.environment, field: value}
            with self.subTest(field=field), self.assertRaises(export.SigningKeyExportError) as failure:
                export.SigningInputs.from_environment(environment)
            if value:
                self.assertNotIn(value, str(failure.exception))
        inputs = export.SigningInputs.from_environment(self.environment)
        self.assertNotIn("fixture", repr(inputs))

    def test_cli_never_reports_unexpected_exception_text_or_traceback(self):
        stdout, stderr = io.StringIO(), io.StringIO()
        with patch.object(export.sys, "argv", ["export_signing_key.py", "--output-dir", str(self.output), "--commit", COMMIT]), \
                patch.object(export, "export_signing_key", side_effect=OSError("fixture-store-password")), \
                redirect_stdout(stdout), redirect_stderr(stderr):
            self.assertEqual(1, export.main())
        self.assertNotIn("fixture-store-password", stdout.getvalue() + stderr.getvalue())
        self.assertNotIn("Traceback", stderr.getvalue())


class DownloadPinTest(unittest.TestCase):
    def test_wrong_binary_and_unexpected_redirect_are_rejected_without_execution(self):
        class Response(io.BytesIO):
            def geturl(self):
                return self.url
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "pepk.jar"
            for url in (export.PEPK_URL, "https://example.invalid/tool.jar"):
                response = Response(b"unreviewed tool")
                response.url = url
                with patch.object(export.urllib.request, "urlopen", return_value=response):
                    with self.assertRaises(export.SigningKeyExportError):
                        export.download_pepk(destination)
                self.assertFalse(destination.exists())


if __name__ == "__main__":
    unittest.main()
