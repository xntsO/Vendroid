"""Reject incomplete evidence and produce a compact GitHub Actions summary."""

import argparse
import json
import os
from pathlib import Path
import re
import sys

# The CLI runs from the repository root; the shared profiles live in appium-tests.
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from vendroid.firmware_validation import BOOT_MARKER
from vendroid.validation_profiles import LARGE_DRIVE_BYTES, PROFILES


COMMON_CHECKPOINTS = {
    "partition-style-policy", "install-and-file-copy", "boot-after-install",
    "idle-usb-reconnect-and-version-detection", "healthy-repair", "boot-after-healthy-repair",
    "old-to-bundled-update", "updated-version-detected", "boot-after-update",
    "newer-version-blocked-and-preserved",
}
GPT_CHECKPOINTS = {
    "primary-gpt-repair", "backup-gpt-repair",
    "boot-after-primary-gpt-repair", "boot-after-backup-gpt-repair",
}
BOOT_DIRECTORIES = {
    "boot-after-install": "boot-install",
    "boot-after-healthy-repair": "boot-healthy-repair",
    "boot-after-primary-gpt-repair": "boot-primary-repair",
    "boot-after-backup-gpt-repair": "boot-backup-repair",
    "boot-after-update": "boot-update",
}


def read_report(path):
    report = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(report, dict):
        raise ValueError(f"Expected a JSON object in {path}")
    return report


def identity_matches(report, profile, controller, commit, base_version):
    return (report.get("channel") == profile.channel and report.get("package") == profile.package and
            report.get("version") == profile.version(base_version) and report.get("commit") == commit and
            report.get("usb_bus") == f"{controller}.0" and report.get("passed") is True)


def boot_passed(boot, evidence, directory):
    reports = list((evidence / directory).glob("firmware-*/report.json"))
    if len(reports) != 1 or read_report(reports[0]) != boot or boot.get("passed") is not True:
        return False
    for mode in ("bios", "uefi"):
        result = boot["modes"][mode]
        if not all(result.get(key) is True for key in ("passed", "marker_seen", "firmware_seen")):
            return False
        lines = (reports[0].parent / mode / "serial.log").read_text(encoding="utf-8", errors="replace").splitlines()
        firmware = "pc" if mode == "bios" else "efi"
        if BOOT_MARKER not in lines or f"VENDROID_CI_BOOT_FIRMWARE={firmware}" not in lines:
            return False
    return True


def lifecycle_passed(report, profile, controller, commit, base_version, evidence):
    if not identity_matches(report, profile, controller, commit, base_version) or report.get("layout") != profile.layout:
        return False
    checkpoints = report["checkpoints"]
    if not isinstance(checkpoints, list) or any(not isinstance(item, dict) for item in checkpoints):
        return False
    by_name = {item["name"]: item for item in checkpoints if item.get("passed") is True}
    expected = COMMON_CHECKPOINTS | (GPT_CHECKPOINTS if profile.layout == "gpt" else set())
    if len(by_name) != len(checkpoints) or not expected <= by_name.keys():
        return False
    policy = by_name["partition-style-policy"]["details"]
    if policy.get("default") != "mbr" or policy.get("gpt_enabled") is not (profile.layout == "gpt"):
        return False
    return all(boot_passed(by_name[name]["details"], evidence, directory)
               for name, directory in BOOT_DIRECTORIES.items() if name in expected)


def large_drive_passed(report, profile, controller, commit, base_version):
    if not identity_matches(report, profile, controller, commit, base_version) or report.get("bytes") != LARGE_DRIVE_BYTES:
        return False
    if profile.supports_large_drives:
        disk = report["simulated_large_drive"]
        return disk.get("style") == "gpt" and disk.get("size_bytes") == LARGE_DRIVE_BYTES
    return report.get("blocked_without_writes") is True and "simulated_large_drive" not in report


def summarize(root: Path, *, expected_commit=None, base_version=None) -> tuple[str, bool]:
    expected_commit = expected_commit or os.environ.get("GITHUB_SHA")
    base_version = base_version or os.environ.get("VENDROID_VERSION", "0.2.0")
    rows = ["# Vendroid automated validation", "",
            "| App | Layout | USB controller | Lifecycle and BIOS/UEFI boot | 3 TiB policy |",
            "|---|---|---|---|---|"]
    passed = True
    commits = set()
    for profile in PROFILES.values():
        for controller in ("uhci", "xhci"):
            directory = root / f"qemu-diagnostics-{profile.channel}-{controller}"
            evidence = directory / "validation"
            lifecycle_ok = False
            large_result = "Not scheduled"
            commit = None
            try:
                commit = (directory / "commit.txt").read_text(encoding="utf-8").strip()
                if not re.fullmatch(r"[0-9a-f]{40}", commit) or (expected_commit and commit != expected_commit):
                    raise ValueError("Evidence belongs to another revision")
                commits.add(commit)
                report = read_report(evidence / "lifecycle.json")
                lifecycle_ok = lifecycle_passed(report, profile, controller, commit, base_version, evidence)
            except (OSError, ValueError, KeyError, TypeError, AttributeError):
                lifecycle_ok = False
            if controller == "xhci":
                try:
                    large_ok = commit is not None and large_drive_passed(
                        read_report(evidence / "large-drive.json"), profile, controller, commit, base_version,
                    )
                    policy = "Preview GPT" if profile.supports_large_drives else "blocked without writes"
                    large_result = f"Pass: {policy}" if large_ok else "FAIL / incomplete"
                except (OSError, ValueError, KeyError, TypeError, AttributeError):
                    large_ok = False
                    large_result = "Missing or incomplete"
                passed = passed and large_ok
            passed = passed and lifecycle_ok
            rows.append(f"| {profile.channel} | {profile.layout.upper()} | {controller} | "
                        f"{'Pass' if lifecycle_ok else 'FAIL / incomplete'} | {large_result} |")
    passed = passed and len(commits) == 1
    rows.extend(["", f"Overall: {'PASS' if passed else 'FAIL / incomplete evidence'}", "",
                 "All results above use virtual disks and emulated firmware. They do not establish physical USB, "
                 "phone/tablet, Secure Boot, or arbitrary OS compatibility. The optimized GPT candidate uses the stable "
                 "package and still blocks drives above 2 TiB. Stable GPT remains disabled until the physical promotion "
                 "check in docs/validation.md is recorded."])
    return "\n".join(rows) + "\n", passed


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    args = parser.parse_args()
    summary, passed = summarize(args.root)
    print(summary)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as output:
            output.write(summary)
    raise SystemExit(0 if passed else 1)
