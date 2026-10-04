"""Stdlib-only evidence gate regressions. Run in GitHub Actions, without Appium."""

import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock


_spec = importlib.util.spec_from_file_location(
    "vendroid_validation_summary",
    Path(__file__).resolve().parents[1] / "scripts" / "summarize-validation.py",
)
summary = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(summary)
from vendroid.validation_profiles import configured_profile

COMMIT = "a" * 40
VERSION = "0.2.0"


class ValidationSummaryTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        for profile in summary.PROFILES.values():
            for controller in ("uhci", "xhci"):
                directory = self.root / f"qemu-diagnostics-{profile.channel}-{controller}"
                evidence = directory / "validation"
                evidence.mkdir(parents=True)
                (directory / "commit.txt").write_text(COMMIT + "\n", encoding="utf-8")
                identity = {"channel": profile.channel, "package": profile.package,
                            "version": profile.version(VERSION), "commit": COMMIT,
                            "usb_bus": f"{controller}.0", "passed": True}
                required = summary.COMMON_CHECKPOINTS | (summary.GPT_CHECKPOINTS if profile.layout == "gpt" else set())
                checkpoints = []
                for name in sorted(required):
                    details = None
                    if name == "partition-style-policy":
                        details = {"default": "mbr", "gpt_enabled": profile.layout == "gpt"}
                    elif name in summary.BOOT_DIRECTORIES:
                        run = evidence / summary.BOOT_DIRECTORIES[name] / "firmware-fixture"
                        details = {"passed": True, "modes": {}}
                        for mode in ("bios", "uefi"):
                            mode_directory = run / mode
                            mode_directory.mkdir(parents=True)
                            firmware = "pc" if mode == "bios" else "efi"
                            (mode_directory / "serial.log").write_text(
                                f"{summary.BOOT_MARKER}\nVENDROID_CI_BOOT_FIRMWARE={firmware}\n", encoding="utf-8",
                            )
                            details["modes"][mode] = {
                                "passed": True, "marker_seen": True, "firmware_seen": True,
                                # Artifact download relocates the original runner paths.
                                "serial_log": f"/original/workspace/{name}/{mode}/serial.log",
                            }
                        self.write(run / "report.json", details)
                    checkpoints.append({"name": name, "passed": True, "details": details})
                self.write(evidence / "lifecycle.json", {**identity, "layout": profile.layout,
                                                        "checkpoints": checkpoints})
                if controller == "xhci":
                    large = {**identity, "bytes": summary.LARGE_DRIVE_BYTES}
                    if profile.supports_large_drives:
                        large["simulated_large_drive"] = {"style": "gpt", "size_bytes": summary.LARGE_DRIVE_BYTES}
                    else:
                        large["blocked_without_writes"] = True
                    self.write(evidence / "large-drive.json", large)

    @staticmethod
    def write(path, value):
        path.write_text(json.dumps(value), encoding="utf-8")

    def path(self, name, channel="gpt-candidate", controller="xhci"):
        return self.root / f"qemu-diagnostics-{channel}-{controller}" / "validation" / name

    def result(self):
        return summary.summarize(self.root, expected_commit=COMMIT, base_version=VERSION)

    def test_complete_matrix_passes_with_relocated_boot_logs(self):
        text, passed = self.result()
        self.assertTrue(passed)
        self.assertIn("| gpt-candidate | GPT | xhci | Pass | Pass: blocked without writes |", text)

    def test_missing_candidate_lifecycle_blocks_validation(self):
        self.path("lifecycle.json").unlink()
        self.assertFalse(self.result()[1])

    def test_identity_layout_and_completion_are_required(self):
        path = self.path("lifecycle.json")
        original = json.loads(path.read_text())
        for field, value in (("package", "io.github.xntso.vendroid.preview"), ("channel", "preview"),
                             ("version", VERSION), ("commit", "b" * 40), ("layout", "mbr"),
                             ("usb_bus", "uhci.0"), ("passed", False), ("passed", 1)):
            with self.subTest(field=field, value=value):
                self.write(path, {**original, field: value})
                self.assertFalse(self.result()[1])

    def test_every_candidate_gpt_repair_and_boot_checkpoint_is_required(self):
        path = self.path("lifecycle.json")
        original = json.loads(path.read_text())
        for name in summary.GPT_CHECKPOINTS:
            with self.subTest(checkpoint=name):
                report = {**original, "checkpoints": [item for item in original["checkpoints"] if item["name"] != name]}
                self.write(path, report)
                self.assertFalse(self.result()[1])

    def test_duplicate_checkpoint_cannot_hide_incomplete_evidence(self):
        path = self.path("lifecycle.json")
        report = json.loads(path.read_text())
        report["checkpoints"].append(report["checkpoints"][0])
        self.write(path, report)
        self.assertFalse(self.result()[1])

    def test_gpt_selection_policy_must_match_each_channel(self):
        for channel in summary.PROFILES:
            with self.subTest(channel=channel):
                path = self.path("lifecycle.json", channel=channel)
                report = json.loads(path.read_text())
                original = path.read_bytes()
                policy = next(item["details"] for item in report["checkpoints"] if item["name"] == "partition-style-policy")
                policy["gpt_enabled"] = not policy["gpt_enabled"]
                self.write(path, report)
                self.assertFalse(self.result()[1])
                path.write_bytes(original)

    def test_missing_boot_report_or_serial_marker_blocks_validation(self):
        for file in ("report.json", "bios/serial.log", "uefi/serial.log"):
            with self.subTest(file=file):
                path = self.path(f"boot-install/firmware-fixture/{file}")
                original = path.read_bytes()
                path.unlink()
                self.assertFalse(self.result()[1])
                path.write_bytes(original)
        self.path("boot-install/firmware-fixture/uefi/serial.log").write_text(summary.BOOT_MARKER + "\n")
        self.assertFalse(self.result()[1])

    def test_wrong_commit_file_blocks_validation(self):
        directory = self.path("lifecycle.json").parent.parent
        (directory / "commit.txt").write_text("b" * 40 + "\n")
        self.assertFalse(self.result()[1])

    def test_missing_large_drive_result_blocks_validation(self):
        self.path("large-drive.json").unlink()
        self.assertFalse(self.result()[1])

    def test_candidate_cannot_pass_by_installing_the_preview_large_drive(self):
        path = self.path("large-drive.json")
        report = json.loads(path.read_text())
        report["simulated_large_drive"] = {"style": "gpt", "size_bytes": summary.LARGE_DRIVE_BYTES}
        self.write(path, report)
        self.assertFalse(self.result()[1])

    def test_large_drive_capacity_and_channel_policy_are_required(self):
        path = self.path("large-drive.json", channel="preview")
        report = json.loads(path.read_text())
        report["simulated_large_drive"]["size_bytes"] = 256 * 1024**2
        self.write(path, report)
        self.assertFalse(self.result()[1])

    def test_malformed_reports_fail_without_crashing(self):
        path = self.path("lifecycle.json")
        for value in ([], {"checkpoints": "invalid"}, None):
            with self.subTest(value=value):
                self.write(path, value)
                self.assertFalse(self.result()[1])

    def test_candidate_profile_rejects_preview_package_configuration(self):
        with mock.patch.dict("os.environ", {"VENDROID_TEST_CHANNEL": "gpt-candidate",
                                           "VENDROID_PACKAGE_NAME": "io.github.xntso.vendroid.preview"}, clear=True):
            with self.assertRaises(ValueError):
                configured_profile()


if __name__ == "__main__":
    unittest.main()
