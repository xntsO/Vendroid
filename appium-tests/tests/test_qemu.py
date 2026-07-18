import base64
import os
import tempfile
import struct
from pathlib import Path
from time import sleep
from typing import Generator

import appium.webdriver
import pytest

from vendroid import actions as app
from vendroid import package_name
from vendroid.config import Config
from vendroid.fixtures import appium_service, driver, qemu
from vendroid.qemu import QEMUController
from vendroid.utils import (
    used,
    device_temp_sparse_file,
    wait_for_element,
    execute_script,
    run_adb_command,
    grant_permissions,
)

pytestmark = pytest.mark.legacy_etchdroid

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


def verify_ventoy_options(raw_blockdev: Path, label: str, reserved_mib: int, cluster_size: int):
    with open(raw_blockdev, "rb") as disk:
        mbr = disk.read(512)
        partition1_start, = struct.unpack_from("<I", mbr, 446 + 8)
        partition2_start, partition2_size = struct.unpack_from("<II", mbr, 462 + 8)
        actual_reserved = raw_blockdev.stat().st_size - (partition2_start + partition2_size) * 512
        assert actual_reserved >= reserved_mib * 1024 * 1024

        disk.seek(partition1_start * 512)
        boot_sector = disk.read(512)
        cluster_heap_offset, = struct.unpack_from("<I", boot_sector, 88)
        root_cluster, = struct.unpack_from("<I", boot_sector, 96)
        actual_cluster_size = 1 << (boot_sector[108] + boot_sector[109])
        assert actual_cluster_size == cluster_size

        root_offset = (
            partition1_start * 512
            + cluster_heap_offset * 512
            + (root_cluster - 2) * actual_cluster_size
        )
        disk.seek(root_offset)
        root = disk.read(actual_cluster_size)
        label_length = root[1]
        actual_label = root[2:2 + label_length * 2].decode("utf-16le")
        assert actual_label == label


@pytest.mark.qemu
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
def test_ventoy_install_options_and_repair(
    driver: appium.webdriver.Remote,
    raw_usb_drive: tuple[str, Path],
):
    _, raw_disk_image_path = raw_usb_drive

    app.tap_install_ventoy(driver)
    app.select_first_usb_device_if_multiple(driver)
    app.grant_usb_permission(driver)
    app.open_ventoy_advanced_options(driver)
    app.configure_ventoy_options(driver, "TOOLS", 1, "64 KiB")
    app.confirm_write_image(driver)
    app.skip_lay_flat_sheet(driver)
    app.wait_for_success(driver, timeout=180)

    sleep(1)
    verify_ventoy_options(raw_disk_image_path, "TOOLS", 1, 64 * 1024)

    driver.terminate_app(package_name)
    driver.activate_app(package_name)
    app.tap_install_ventoy(driver)
    app.select_first_usb_device_if_multiple(driver)
    app.grant_usb_permission(driver)
    repair_button = wait_for_element(driver, '//*[@resource-id="writeImageButton"]', timeout=30)
    assert repair_button.text == "Repair"
    repair_button.click()
    app.skip_lay_flat_sheet(driver)
    app.wait_for_success(driver, timeout=180)

    sleep(1)
    verify_ventoy_options(raw_disk_image_path, "TOOLS", 1, 64 * 1024)


@pytest.mark.qemu
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
            f'//android.widget.TextView[@resource-id="android:id/title" and @text="Action required"]',
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
