"""Optional real UI captures, run separately after required storage validation."""

import hashlib
import json
import os
from pathlib import Path
import re
import struct
import traceback

import pytest

from vendroid import actions, package_name
from vendroid.fixtures import appium_service, driver
from vendroid.utils import run_adb_command, used, wait_for_element
from vendroid.validation_profiles import configured_profile

used(appium_service)


def shell_output(driver, command, *arguments):
    return run_adb_command(driver, command, *arguments)["stdout"].strip()


def capture(driver, directory, name):
    data = driver.get_screenshot_as_png()
    if len(data) < 33 or data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR":
        raise ValueError("Appium did not return a PNG screenshot")
    width, height = struct.unpack(">II", data[16:24])
    bit_depth, color_type = data[24:26]
    path = directory / f"{name}.png"
    path.write_bytes(data)
    return {"file": path.name, "sha256": hashlib.sha256(data).hexdigest(),
            "width": width, "height": height,
            "bit_depth": bit_depth, "color_type": color_type,
            "store_format": bit_depth == 8 and color_type == 2,
            "store_geometry": min(width, height) >= 320 and max(width, height) <= 3840 and
            max(width, height) <= 2 * min(width, height),
            "recommended_aspect_ratio": width * 9 == height * 16 or width * 16 == height * 9}


def capture_failure_diagnostics(driver, directory, report):
    for name, read in (("failure.xml", lambda: driver.page_source.encode("utf-8")),
                       ("failure.png", driver.get_screenshot_as_png)):
        try:
            (directory / name).write_bytes(read())
            report.setdefault("diagnostic_files", []).append(name)
        except Exception as error:
            report["diagnostic_errors"].append(f"{name}: {type(error).__name__}")


@pytest.mark.qemu
def test_capture_real_store_screenshots(driver):
    profile = configured_profile()
    version = profile.version(os.environ["VENDROID_VERSION"])
    directory = Path(os.environ["VENDROID_EVIDENCE_DIR"]) / "store-screenshots"
    directory.mkdir(parents=True, exist_ok=True)
    report = {"channel": profile.channel, "package": profile.package, "version": version,
              "commit": os.environ["GITHUB_SHA"], "store_ready": False, "screenshots": [],
              "display_policy": "unchanged", "capture_stage": "read-display",
              "restore_errors": [], "diagnostic_errors": []}
    original_settings = {}
    try:
        original_display = shell_output(driver, "wm", "size")
        report["original_display"] = original_display
        if not re.search(r"Physical size: [0-9]+x[0-9]+", original_display):
            raise ValueError("Cannot safely identify the original display size")
        # The original 1280x800 viewport is already exercised by required USB tests.
        # Resizing to 720px clips the permission button in the scrollable confirmation.
        report["capture_stage"] = "hide-input-overlays"
        for name in ("pointer_location", "show_touches"):
            original_settings[name] = shell_output(driver, "settings", "get", "system", name)
            shell_output(driver, "settings", "put", "system", name, "0")
        report["capture_stage"] = "launch-home"
        driver.terminate_app(package_name)
        driver.activate_app(package_name)
        wait_for_element(driver, f'//*[@text="{profile.app_name} v{version}"]', timeout=30)
        wait_for_element(driver, '//*[@resource-id="installVentoyCTA"]', timeout=30)
        if driver.current_package != profile.package:
            raise ValueError("Foreground package differs from the captured app identity")
        report["capture_stage"] = "capture-home"
        report["screenshots"].append(capture(driver, directory, "home"))

        report["capture_stage"] = "open-install-confirmation"
        actions.tap_install_ventoy(driver)
        report["capture_stage"] = "select-usb-device"
        actions.select_first_usb_device_if_multiple(driver)
        report["capture_stage"] = "grant-usb-permission"
        actions.grant_usb_permission(driver)
        report["capture_stage"] = "open-install-options"
        actions.open_ventoy_advanced_options(driver)
        wait_for_element(driver, '//*[@resource-id="ventoyVolumeLabelField"]', timeout=30)
        # The options sheet is only inspected. No install, update, or repair is started.
        report["capture_stage"] = "capture-install-options"
        report["screenshots"].append(capture(driver, directory, "install-options"))
        report["store_ready"] = len(report["screenshots"]) == 2 and all(
            item["store_geometry"] and item["store_format"] for item in report["screenshots"]
        )
        report["capture_stage"] = "complete"
        if not report["store_ready"]:
            report["capture_error"] = "Two RGB PNG captures with Google Play's required dimensions were not obtained"
    except Exception as error:
        report["failure_stage"] = report["capture_stage"]
        report["capture_error"] = f"{type(error).__name__}: {error}"
        (directory / "capture-traceback.txt").write_text(traceback.format_exc(), encoding="utf-8")
        report["diagnostic_files"] = ["capture-traceback.txt"]
        capture_failure_diagnostics(driver, directory, report)
    finally:
        for name, value in original_settings.items():
            try:
                if value == "null":
                    shell_output(driver, "settings", "delete", "system", name)
                else:
                    shell_output(driver, "settings", "put", "system", name, value)
            except Exception as error:
                report["restore_errors"].append(f"{name} restore failed: {type(error).__name__}")
        if report["restore_errors"]:
            report["store_ready"] = False
        (directory / "report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    if not report["store_ready"]:
        pytest.skip("Optional store screenshot capture incomplete; see store-screenshots/report.json")
