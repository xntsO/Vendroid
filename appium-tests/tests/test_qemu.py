# ruff: noqa: F811

import base64
import os
import tempfile
from pathlib import Path
from time import sleep
from typing import Generator

import appium.webdriver
import pytest

from vendroid import actions as app
from vendroid.config import Config
from vendroid.fixtures import appium_service, driver, qemu  # noqa: F401
from vendroid.qemu import QEMUController
from vendroid.utils import (
    used,
    device_temp_sparse_file,
    wait_for_element,
    execute_script,
    run_adb_command,
    grant_permissions,
)

used(appium_service)


def unplug_and_reconnect_usb(
    driver: appium.webdriver.Remote,
    qemu: QEMUController,
    device_id: str = Config.QEMU_USB_DEV_ID,
    bus: str = Config.QEMU_USB_BUS,
):
    print("Unplugging USB device...")
    device = qemu.get_block_device(device_id)
    qemu.device_del(device_id)

    print("Waiting for reconnect dialog...")
    wait_for_element(driver, '//android.widget.TextView[@resource-id="reconnect_usb_drive_title"]', 15)

    sleep(0.5)

    print("Plugging USB device back in...")
    qemu.add_usb_drive(
        device_id,
        bus=bus,
        file=device["inserted"]["image"]["filename"],
        format=device["inserted"]["image"]["format"],
    )

    # Wait 3 seconds to ensure the emulated device doesn't spit out Unit Attention sense codes on init.
    # A patch should be submitted to libaums to handle this.
    sleep(3)

    print("Accepting permission...")
    app.accept_usb_permission(driver)


@pytest.fixture(scope="function")
def random_image_file(driver: appium.webdriver.Remote, request) -> Generator[tuple[str, bytes], None, None]:
    size_bytes = 10 * 1024 * 1024  # 10 MB
    payload = os.urandom(size_bytes)

    remote_path = tempfile.mktemp(prefix=f"vendroid_{request.node.name}_", suffix=".iso", dir="/sdcard/Download/")
    execute_script(
        driver,
        "mobile: pushFile",
        {
            "remotePath": remote_path,
            "payload": base64.b64encode(payload).decode("utf-8"),
        },
    )

    yield remote_path, payload

    run_adb_command(driver, "rm", "-f", remote_path)


@pytest.fixture(scope="function")
def raw_disk_image(qemu: QEMUController, request):
    with tempfile.TemporaryDirectory("vendroid_qemu_test") as tmp_path:
        tmp_path = Path(tmp_path)

        size_bytes = 50 * 1024 * 1024  # 50 MB
        filename = tmp_path / f"vendroid_{request.node.name}.img"

        # Write random data to the image to catch more bugs
        with open(filename, "wb") as f:
            for i in range(size_bytes // 1024 // 1024):
                f.write(os.urandom(1024 * 1024))
            # Ensure the random fixture cannot accidentally look like an existing MBR.
            f.seek(510)
            f.write(b"\x00\x00")

        yield filename

        filename.unlink(missing_ok=True)


@pytest.fixture(scope="function")
def raw_usb_drive(qemu: QEMUController, raw_disk_image: Path) -> Generator[tuple[str, Path], None, None]:
    # Disconnect existing USB device first
    device = qemu.get_block_device(Config.QEMU_USB_DEV_ID)
    qemu.device_del(Config.QEMU_USB_DEV_ID)
    sleep(0.5)

    raw_dev_id = f"{Config.QEMU_USB_DEV_ID}-raw"
    qemu.add_usb_drive(
        raw_dev_id,
        bus=Config.QEMU_USB_SLOW_BUS,
        file=raw_disk_image,
        format="raw",
    )

    sleep(2)

    yield raw_dev_id, raw_disk_image

    # Restore the original USB device
    qemu.device_del(raw_dev_id)
    qemu.add_usb_drive(
        Config.QEMU_USB_DEV_ID,
        bus=Config.QEMU_USB_BUS,
        file=device["inserted"]["image"]["filename"],
        format=device["inserted"]["image"]["format"],
    )
    sleep(0.5)


def verify_written_image(payload: bytes, raw_blockdev: Path):
    with open(raw_blockdev, "rb") as f:
        written_data = f.read(len(payload))
        assert written_data == payload, "Written data does not match expected data"


def read_mbr_partition_entry(mbr: bytes, index: int) -> dict[str, int]:
    offset = 446 + (index * 16)
    return {
        "active": mbr[offset],
        "type": mbr[offset + 4],
        "start_sector": int.from_bytes(mbr[offset + 8:offset + 12], "little"),
        "sector_count": int.from_bytes(mbr[offset + 12:offset + 16], "little"),
    }


def verify_ventoy_install(raw_blockdev: Path):
    with open(raw_blockdev, "rb") as f:
        mbr = f.read(512)
        assert mbr[510:512] == b"\x55\xaa", "Ventoy MBR signature is missing"

        data_partition = read_mbr_partition_entry(mbr, 0)
        boot_partition = read_mbr_partition_entry(mbr, 1)
        assert data_partition["active"] == 0x80
        assert data_partition["type"] == 0x07
        assert data_partition["start_sector"] == 2048
        assert boot_partition["active"] == 0x00
        assert boot_partition["type"] == 0xEF
        assert boot_partition["sector_count"] == 65_536
        assert boot_partition["start_sector"] == (
            data_partition["start_sector"] + data_partition["sector_count"]
        )
        assert boot_partition["start_sector"] % 8 == 0

        f.seek(data_partition["start_sector"] * 512)
        exfat_boot_sector = f.read(512)
        assert exfat_boot_sector[3:11] == b"EXFAT   "
        assert exfat_boot_sector[510:512] == b"\x55\xaa"

        f.seek(boot_partition["start_sector"] * 512)
        assert f.read(512) != bytes(512), "Ventoy boot payload was not written"


@pytest.mark.qemu
def test_install_ventoy_to_virtual_usb(
    driver: appium.webdriver.Remote,
    raw_usb_drive: tuple[str, Path],
):
    _, raw_disk_image_path = raw_usb_drive

    app.ventoy_install_flow(driver)
    app.wait_for_success(driver, timeout=180)

    verify_ventoy_install(raw_disk_image_path)


@pytest.mark.qemu
@pytest.mark.legacy_etchdroid
def test_unplug_xhci(driver: appium.webdriver.Remote, qemu: QEMUController):
    with device_temp_sparse_file(driver, "vendroid_test_unplug_xhci_", ".iso", "1000M") as image:
        app.basic_flow(driver, image.filename)

        print("Waiting for write progress...")
        app.wait_for_write_progress(driver)

        unplug_and_reconnect_usb(driver, qemu)
        app.get_skip_verify_button(driver)
        unplug_and_reconnect_usb(driver, qemu)
        app.wait_for_success(driver)


@pytest.mark.qemu
@pytest.mark.legacy_etchdroid
def test_regular_flow_with_random_data_uhci(
    driver: appium.webdriver.Remote,
    random_image_file: tuple[str, bytes],
    raw_usb_drive: tuple[str, Path],
):
    remote_image_path, image_payload = random_image_file
    _, raw_disk_image_path = raw_usb_drive
    remote_fname = Path(remote_image_path).name

    app.basic_flow(driver, remote_fname)
    app.wait_for_success(driver)

    verify_written_image(image_payload, raw_disk_image_path)


@pytest.mark.qemu
@pytest.mark.legacy_etchdroid
def test_unplug_with_random_data_uhci(
    driver: appium.webdriver.Remote,
    random_image_file: tuple[str, bytes],
    raw_usb_drive: tuple[str, Path],
    qemu: QEMUController,
):
    remote_image_path, image_payload = random_image_file
    raw_device_id, raw_disk_image_path = raw_usb_drive
    remote_fname = Path(remote_image_path).name

    app.basic_flow(driver, remote_fname)

    print("Waiting for write progress...")
    app.wait_for_write_progress(driver)

    unplug_and_reconnect_usb(driver, qemu, raw_device_id, Config.QEMU_USB_SLOW_BUS)
    app.get_skip_verify_button(driver)
    unplug_and_reconnect_usb(driver, qemu, raw_device_id, Config.QEMU_USB_SLOW_BUS)
    app.wait_for_success(driver)

    verify_written_image(image_payload, raw_disk_image_path)


@pytest.mark.qemu
@pytest.mark.legacy_etchdroid
def test_unplug_resume_from_notification(driver: appium.webdriver.Remote, qemu: QEMUController):
    grant_permissions(driver, ["android.permission.POST_NOTIFICATIONS"])

    with device_temp_sparse_file(driver, "vendroid_test_unplug_resume_from_notification_", ".iso", "1000M") as image:
        app.basic_flow(driver, image.filename)
        app.wait_for_write_progress(driver)

        # Unplug USB device
        device = qemu.get_block_device(Config.QEMU_USB_DEV_ID)
        qemu.device_del(Config.QEMU_USB_DEV_ID)

        # Wait for reconnect dialog
        wait_for_element(driver, '//android.widget.TextView[@resource-id="reconnect_usb_drive_title"]', 15)

        # Close app from recents
        driver.keyevent(187)  # KEYCODE_APP_SWITCH
        sleep(0.5)
        driver.keyevent(67)  # KEYCODE_DEL
        sleep(0.5)
        driver.keyevent(3)  # KEYCODE_HOME

        driver.open_notifications()

        notification = wait_for_element(
            driver,
            '//android.widget.TextView[@resource-id="android:id/title" and @text="Action required"]',
            timeout=5,
        )
        notification.click()

        sleep(0.5)

        # Reconnect USB device
        qemu.add_usb_drive(
            Config.QEMU_USB_DEV_ID,
            bus=Config.QEMU_USB_BUS,
            file=device["inserted"]["image"]["filename"],
            format=device["inserted"]["image"]["format"],
        )

        # Wait 3 seconds to ensure the emulated device doesn't spit out Unit Attention sense codes on init.
        # A patch should be submitted to libaums to handle this.
        sleep(3)

        app.accept_usb_permission(driver)

        skip_btn = app.get_skip_verify_button(driver)
        skip_btn.click()

        app.wait_for_success(driver)
