import base64
import os
import tempfile
import struct
import hashlib
import json
from contextlib import contextmanager
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
from vendroid.disk_validation import (
    inspect_image, mounted_data_partition, create_preservation_files,
    verify_preservation_files, damage_gpt_header, seed_payload,
)
from vendroid.firmware_validation import prepare_boot_files, verify_firmware_boot
from vendroid.utils import (
    used,
    device_temp_sparse_file,
    wait_for_element,
    execute_script,
    run_adb_command,
    grant_permissions,
    get_wait,
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
        if mbr[446 + 4] == 0xEE:
            disk.seek(2 * 512)
            entries = disk.read(2 * 128)
            partition1_start, = struct.unpack_from("<Q", entries, 32)
            partition2_start, partition2_end = struct.unpack_from("<QQ", entries, 128 + 32)
            actual_reserved = raw_blockdev.stat().st_size - (partition2_end + 34) * 512
        else:
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


class ValidationDrive:
    def __init__(self, qemu, path, device_id, bus):
        self.qemu, self.path, self.device_id, self.bus = qemu, path, device_id, bus
        self.attached = False

    def attach(self):
        self.qemu.add_usb_drive(self.device_id, bus=self.bus, file=self.path, format="raw")
        self.attached = True
        sleep(3)

    def detach(self):
        if self.attached:
            self.qemu.detach_usb_drive(self.device_id)
            self.attached = False


@pytest.fixture
def validation_drive(qemu, request):
    original = qemu.get_block_device(Config.QEMU_USB_DEV_ID)["inserted"]["image"]
    qemu.detach_usb_drive(Config.QEMU_USB_DEV_ID)
    try:
        with tempfile.TemporaryDirectory(prefix="vendroid-validation-") as directory:
            path = Path(directory) / "usb.img"
            with path.open("wb") as image:
                image.truncate(getattr(request, "param", 256 * 1024 * 1024))
            drive = ValidationDrive(qemu, path, "validation-usb", os.environ.get("VENDROID_TEST_USB_BUS", "uhci.0"))
            try:
                drive.attach()
                yield drive
            finally:
                drive.detach()
    finally:
        qemu.add_usb_drive(Config.QEMU_USB_DEV_ID, bus=Config.QEMU_USB_BUS,
                           file=original["filename"], format=original["format"])
        sleep(3)


def restart_and_scan(driver):
    driver.terminate_app(package_name)
    driver.activate_app(package_name)
    app.tap_install_ventoy(driver)
    app.select_first_usb_device_if_multiple(driver)
    app.grant_usb_permission(driver)


def assert_detected_version(driver, version):
    wait_for_element(driver, f'//*[contains(@text,"Ventoy {version} is installed") or '
                     f'contains(@text,"Ventoy {version} installed") or '
                     f'contains(@text,"has Ventoy {version},")]', timeout=30)


def complete_operation(driver, action):
    wait_for_element(driver, f'//*[@text="{action}"]', timeout=30)
    wait_for_element(driver, '//*[@resource-id="writeImageButton"]', timeout=30).click()
    app.skip_lay_flat_sheet(driver)
    app.wait_for_success(driver, timeout=240)


def file_digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


@contextmanager
def paused_vm_automounter(driver):
    """Isolate app writes from vold/fsck writes on the disposable Android VM."""
    def state():
        return run_adb_command(driver, "getprop", "init.svc.vold")["stdout"].strip()

    assert state() == "running", "Expected the VM auto-mounter to start normally"
    run_adb_command(driver, "stop", "vold")
    try:
        get_wait(driver, 10).until(lambda _: state() == "stopped")
        yield
        assert state() == "stopped", "Auto-mounter restarted during the zero-write check"
    finally:
        run_adb_command(driver, "start", "vold")
        get_wait(driver, 10).until(lambda _: state() == "running")


@pytest.mark.qemu
def test_ventoy_lifecycle_and_firmware(
    driver: appium.webdriver.Remote,
    validation_drive,
):
    drive = validation_drive
    style = "gpt" if os.environ.get("VENDROID_PARTITION_STYLE", "MBR").startswith("GPT") else "mbr"
    evidence = Path(os.environ["VENDROID_EVIDENCE_DIR"])
    evidence.mkdir(parents=True, exist_ok=True)
    report = {"layout": style, "usb_bus": drive.bus, "checkpoints": []}
    report_path = evidence / "lifecycle.json"

    def record(name, details=None):
        report["checkpoints"].append({"name": name, "passed": True, "details": details})
        report_path.write_text(json.dumps(report, indent=2), encoding="utf-8")

    def verify_preserved(name):
        snapshot = inspect_image(drive.path, style)
        assert snapshot == baseline, f"Disk identity/layout changed during {name}"
        with mounted_data_partition(drive.path) as root:
            verify_preservation_files(root, manifest)
        record(name, snapshot)

    app.tap_install_ventoy(driver)
    app.select_first_usb_device_if_multiple(driver)
    app.grant_usb_permission(driver)
    app.open_ventoy_advanced_options(driver)
    wait_for_element(driver, '//*[@text="MBR (Recommended)"]', timeout=15)
    app.configure_ventoy_options(
        driver, "TOOLS", 1, "64 KiB",
        partition_style=os.environ.get("VENDROID_PARTITION_STYLE"),
    )
    app.confirm_write_image(driver)
    app.skip_lay_flat_sheet(driver)
    app.wait_for_success(driver, timeout=240)
    drive.detach()
    baseline = inspect_image(drive.path, style)
    verify_ventoy_options(drive.path, "TOOLS", 1, 64 * 1024)
    with mounted_data_partition(drive.path, writable=True) as root:
        manifest = create_preservation_files(root)
        prepare_boot_files(root, Path(os.environ["VENDROID_BOOT_PROBE_ISO"]))
    (evidence / "preservation-sha256.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    record("install-and-file-copy", baseline)
    record("boot-after-install", verify_firmware_boot(drive.path, evidence / "boot-install"))

    drive.attach()
    restart_and_scan(driver)
    assert_detected_version(driver, os.environ["VENTOY_VERSION"])
    record("idle-usb-reconnect-and-version-detection")
    complete_operation(driver, "Repair")
    drive.detach()
    verify_preserved("healthy-repair")
    record("boot-after-healthy-repair", verify_firmware_boot(drive.path, evidence / "boot-healthy-repair"))

    if style == "gpt":
        for damaged_copy in ("primary", "backup"):
            damage_gpt_header(drive.path, damaged_copy)
            drive.attach()
            restart_and_scan(driver)
            assert_detected_version(driver, os.environ["VENTOY_VERSION"])
            complete_operation(driver, "Repair")
            drive.detach()
            verify_preserved(f"{damaged_copy}-gpt-repair")
            record(f"boot-after-{damaged_copy}-gpt-repair",
                   verify_firmware_boot(drive.path, evidence / f"boot-{damaged_copy}-repair"))

    fixtures = Path(os.environ["VENDROID_PAYLOAD_FIXTURES"])
    seed_payload(drive.path, fixtures / "ventoy-1.1.15-linux.tar.gz", style)
    drive.attach()
    restart_and_scan(driver)
    assert_detected_version(driver, "1.1.15")
    complete_operation(driver, "Update")
    drive.detach()
    verify_preserved("old-to-bundled-update")
    drive.attach()
    restart_and_scan(driver)
    assert_detected_version(driver, os.environ["VENTOY_VERSION"])
    drive.detach()
    record("updated-version-detected")
    record("boot-after-update", verify_firmware_boot(drive.path, evidence / "boot-update"))

    seed_payload(drive.path, fixtures / "ventoy-1.1.17-linux.tar.gz", style)
    # Bliss OS mounts GPT's VTOYEFI and runs fsck before Vendroid claims USB.
    # Disable that independent writer only for this exact-byte assertion.
    with paused_vm_automounter(driver):
        before_downgrade = file_digest(drive.path)
        try:
            drive.attach()
            restart_and_scan(driver)
            assert_detected_version(driver, "1.1.17")
            wait_for_element(driver, '//*[contains(@text,"Downgrade is blocked")]', timeout=30)
            button = wait_for_element(driver, '//*[@resource-id="writeImageButton"]', timeout=15)
            assert button.get_attribute("enabled") == "false"
            driver.terminate_app(package_name)
        finally:
            drive.detach()
        after_downgrade = file_digest(drive.path)
        (evidence / "downgrade-write-check.json").write_text(json.dumps({
            "automounter": "stopped", "before_sha256": before_downgrade,
            "after_sha256": after_downgrade, "passed": before_downgrade == after_downgrade,
        }, indent=2), encoding="utf-8")
        assert after_downgrade == before_downgrade, "Disk changed while downgrade was blocked"
        verify_preserved("newer-version-blocked-with-zero-writes")
    report["passed"] = True
    report_path.write_text(json.dumps(report, indent=2), encoding="utf-8")


@pytest.mark.qemu
@pytest.mark.parametrize("validation_drive", [3 * 1024**4], indirect=True)
def test_large_sparse_drive_policy(driver, validation_drive):
    if os.environ.get("VENDROID_TEST_USB_BUS") != "xhci.0":
        pytest.skip("Large-capacity case runs once per channel on xHCI")
    drive = validation_drive
    app.tap_install_ventoy(driver)
    app.select_first_usb_device_if_multiple(driver)
    app.grant_usb_permission(driver)
    evidence = Path(os.environ["VENDROID_EVIDENCE_DIR"])
    if os.environ.get("VENDROID_APP_NAME") == "Vendroid":
        wait_for_element(driver, '//*[contains(@text,"requires the separately installable GitHub V-Preview")]', timeout=30)
        button = wait_for_element(driver, '//*[@resource-id="writeImageButton"]', timeout=15)
        assert button.get_attribute("enabled") == "false"
        driver.terminate_app(package_name)
        drive.detach()
        assert drive.path.stat().st_blocks == 0, "Stable wrote to a blocked sparse drive"
        result = {"passed": True, "bytes": drive.path.stat().st_size, "stable_blocked_without_writes": True}
    else:
        app.open_ventoy_advanced_options(driver)
        app.configure_ventoy_options(driver, "LARGE", 1, "64 KiB", partition_style="GPT (Preview)")
        app.confirm_write_image(driver)
        app.skip_lay_flat_sheet(driver)
        app.wait_for_success(driver, timeout=300)
        drive.detach()
        result = inspect_image(drive.path, "gpt")
        with mounted_data_partition(drive.path, writable=True) as root:
            manifest = create_preservation_files(root)
        drive.attach()
        restart_and_scan(driver)
        assert_detected_version(driver, os.environ["VENTOY_VERSION"])
        driver.terminate_app(package_name)
        drive.detach()
        assert inspect_image(drive.path, "gpt") == result
        with mounted_data_partition(drive.path) as root:
            verify_preservation_files(root, manifest)
        result = {"passed": True, "simulated_large_drive": result}
    evidence.mkdir(parents=True, exist_ok=True)
    (evidence / "large-drive.json").write_text(json.dumps(result, indent=2), encoding="utf-8")


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
