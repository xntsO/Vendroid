"""Reject incomplete evidence and produce a compact GitHub Actions summary."""

import argparse
import json
import os
from pathlib import Path


def summarize(root: Path) -> tuple[str, bool]:
    rows = ["# Vendroid automated validation", "",
            "| App | USB controller | Lifecycle and BIOS/UEFI boot | 3 TiB policy |",
            "|---|---|---|---|"]
    passed = True
    required = {"install-and-file-copy", "boot-after-install", "idle-usb-reconnect-and-version-detection",
                "healthy-repair", "boot-after-healthy-repair", "old-to-bundled-update", "updated-version-detected",
                "boot-after-update", "newer-version-blocked-and-preserved"}
    for channel in ("stable", "preview"):
        for controller in ("uhci", "xhci"):
            directory = root / f"qemu-diagnostics-{channel}-{controller}"
            # upload-artifact preserves validation/ under its common vm-outputs root.
            lifecycle = directory / "validation" / "lifecycle.json"
            large = directory / "validation" / "large-drive.json"
            lifecycle_ok = False
            large_result = "Not scheduled"
            try:
                report = json.loads(lifecycle.read_text(encoding="utf-8"))
                expected = required | ({"primary-gpt-repair", "backup-gpt-repair",
                                        "boot-after-primary-gpt-repair", "boot-after-backup-gpt-repair"}
                                       if channel == "preview" else set())
                checkpoints = {item["name"] for item in report["checkpoints"] if item.get("passed") is True}
                lifecycle_ok = (report.get("passed") is True and expected <= checkpoints and
                                report["layout"] == ("mbr" if channel == "stable" else "gpt") and
                                report["usb_bus"] == f"{controller}.0")
                for checkpoint in report["checkpoints"]:
                    if checkpoint["name"].startswith("boot-"):
                        boot = checkpoint["details"]
                        lifecycle_ok = lifecycle_ok and boot.get("passed") is True and all(
                            boot["modes"][mode].get("passed") is True for mode in ("bios", "uefi")
                        )
                if controller == "xhci":
                    large_ok = json.loads(large.read_text(encoding="utf-8")).get("passed") is True
                    large_result = "Pass" if large_ok else "FAIL"
                    passed = passed and large_ok
            except (OSError, ValueError, KeyError, TypeError):
                lifecycle_ok = False
                if controller == "xhci":
                    large_result = "Missing or incomplete"
            passed = passed and lifecycle_ok
            rows.append(f"| {channel} | {controller} | {'Pass' if lifecycle_ok else 'FAIL / incomplete'} | {large_result} |")
    rows.extend(["", f"Overall: {'PASS' if passed else 'FAIL / incomplete evidence'}", "",
                 "All results above use virtual disks and emulated firmware. They do not establish physical USB, "
                 "phone/tablet, Secure Boot, or arbitrary OS compatibility. See docs/validation.md for the one-USB release check."])
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
