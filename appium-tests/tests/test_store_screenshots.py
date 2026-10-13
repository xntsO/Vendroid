"""Optional real UI captures, run separately after required storage validation."""

import hashlib
import json
import os
from pathlib import Path
import re
import struct

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
    if data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR":
        raise ValueError("Appium did not return a PNG screenshot")
    width, height = struct.unpack(">II", data[16:24])
    path = directory / f"{name}.png"
    path.write_bytes(data)
    return {"file": path.name, "sha256": hashlib.sha256(data).hexdigest(),
            "width": width, "height": height,
            "store_geometry": min(width, height) >= 320 and
            (width * 9 == height * 16 or width * 16 == height * 9)}


@pytest.mark.qemu
def test_capture_real_store_screenshots(driver):
    profile = configured_profile()
    version = profile.version(os.environ["VENDROID_VERSION"])
    directory = Path(os.environ["VENDROID_EVIDENCE_DIR"]) / "store-screenshots"
    directory.mkdir(parents=True, exist_ok=True)
    report = {"channel": profile.channel, "package": profile.package, "version": version,
              "commit": os.environ["GITHUB_SHA"], "store_ready": False, "screenshots": [],
              "requested_display": "1280x720", "restore_errors": []}
    original_override = None
    display_read = False
    original_settings = {}
    try:
        original_display = shell_output(driver, "wm", "size")
        report["original_display"] = original_display
        if not re.search(r"Physical size: [0-9]+x[0-9]+", original_display):
            raise ValueError("Cannot safely identify the original display size")
        override = re.search(r"Override size: ([0-9]+x[0-9]+)", original_display)
        original_override = override.group(1) if override else "reset"
        display_read = True
        for name in ("pointer_location", "show_touches"):
            original_settings[name] = shell_output(driver, "settings", "get", "system", name)
            shell_output(driver, "settings", "put", "system", name, "0")
        shell_output(driver, "wm", "size", "1280x720")
        driver.terminate_app(package_name)
        driver.activate_app(package_name)
        wait_for_element(driver, f'//*[@text="{profile.app_name} v{version}"]', timeout=30)
        wait_for_element(driver, '//*[@resource-id="installVentoyCTA"]', timeout=30)
        if driver.current_package != profile.package:
            raise ValueError("Foreground package differs from the captured app identity")
        report["screenshots"].append(capture(driver, directory, "home"))

        actions.tap_install_ventoy(driver)
        actions.select_first_usb_device_if_multiple(driver)
        actions.grant_usb_permission(driver)
        actions.open_ventoy_advanced_options(driver)
        wait_for_element(driver, '//*[@resource-id="ventoyVolumeLabelField"]', timeout=30)
        # The options sheet is only inspected. No install, update, or repair is started.
        report["screenshots"].append(capture(driver, directory, "install-options"))
        report["store_ready"] = all(item["store_geometry"] for item in report["screenshots"])
        if not report["store_ready"]:
            report["capture_error"] = "The real display capture did not provide 16:9 or 9:16 geometry"
    except Exception as error:
        report["capture_error"] = f"{type(error).__name__}: {error}"
    finally:
        if display_read:
            try:
                shell_output(driver, "wm", "size", original_override)
            except Exception as error:
                report["restore_errors"].append(f"Display restore failed: {type(error).__name__}")
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
