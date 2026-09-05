"""CI-only boot checks for a detached, raw whole-disk Ventoy image.

The caller mounts the data partition, calls prepare_boot_files, unmounts it,
detaches loop devices AND removes Bliss's QEMU block backend before verification.
Keep the image detached until this call returns. No physical device is opened.
Ubuntu runtime dependencies: qemu-system-x86, seabios, ovmf.
"""

import json
import os
import shutil
import signal
import socket
import stat
import subprocess
import tempfile
import time
from pathlib import Path


BOOT_MARKER = "VENDROID_CI_BOOT_OK"
PROBE_IMAGE = "/vendroid-ci-boot-probe.iso"
BOOT_TIMEOUT_SECONDS = 180
LIMITATIONS = [
    "Validates only this GRUB probe through Ventoy under emulated x86 BIOS and/or x64 UEFI.",
    "Does not validate Secure Boot, vendor firmware, physical USB hardware, or OS compatibility.",
    "The caller must confirm QMP device and block-backend removal and keep the image detached. "
    "Loop-device checks and QEMU's normal image locks are not a system-wide ownership guarantee.",
]


class FirmwareBootError(AssertionError):
    """A requested mode failed; report and report.json retain all diagnostics."""

    def __init__(self, report: dict):
        self.report = report
        failed = ", ".join(mode for mode, result in report["modes"].items() if not result["passed"])
        super().__init__(f"Firmware boot validation failed ({failed}); see {report['report_path']}")


def prepare_boot_files(mount_root: str | Path, probe_iso: str | Path) -> dict:
    """Copy the probe and replace the fixture's /ventoy/ventoy.json.

    mount_root must already be the mounted data partition. This helper neither
    mounts nor unmounts it. The caller must unmount successfully to flush writes.
    Control values are strings as required by the official plugin schema:
    https://www.ventoy.net/en/plugin_control.html
    """
    root = Path(mount_root).resolve(strict=True)
    probe = Path(probe_iso).resolve(strict=True)
    if not root.is_dir() or not root.is_mount():
        raise ValueError(f"Expected a mounted data partition: {root}")
    if not probe.is_file():
        raise ValueError(f"Expected a regular probe ISO: {probe}")
    with probe.open("rb") as source:
        source.seek(16 * 2048 + 1)
        if source.read(5) != b"CD001":
            raise ValueError(f"Probe is not an ISO9660 image: {probe}")

    destination = root / PROBE_IMAGE.lstrip("/")
    config_dir = root / "ventoy"
    config_path = config_dir / "ventoy.json"
    for path in (destination, config_dir, config_path):
        if path.is_symlink() or not path.resolve().is_relative_to(root):
            raise ValueError(f"Refusing a boot-file path outside the mounted fixture: {path}")
    config_dir.mkdir(exist_ok=True)
    if destination.resolve() != probe:
        shutil.copyfile(probe, destination)
    config = {
        "control": [
            {"VTOY_DEFAULT_IMAGE": PROBE_IMAGE},
            {"VTOY_MENU_TIMEOUT": "2"},
            {"VTOY_SECONDARY_BOOT_MENU": "1"},
            {"VTOY_SECONDARY_TIMEOUT": "2"},
        ]
    }
    config_path.write_text(json.dumps(config, indent=2) + "\n", encoding="utf-8")
    return {"probe_iso": str(destination), "ventoy_json": str(config_path), "control": config["control"]}


def _check_detached_disk(image: Path) -> None:
    if not stat.S_ISREG(image.stat().st_mode):
        raise ValueError(f"Expected a detached regular raw disk image, not a device: {image}")
    with image.open("rb") as disk:
        mbr = disk.read(512)
        if mbr[:4] == b"QFI\xfb":
            raise ValueError("Convert the detached QCOW2 candidate to a raw whole-disk image first")
        if len(mbr) != 512 or mbr[510:512] != b"\x55\xaa" or not any(mbr[446:510]):
            raise ValueError("Candidate must contain a whole-disk partition table")
        # grub-mkrescue ISOs can be hybrid disks. Reject them even if renamed .img.
        disk.seek(16 * 2048 + 1)
        if disk.read(5) == b"CD001":
            raise ValueError("An ISO cannot be the boot disk; supply the Ventoy disk containing the probe")

    for backing in Path("/sys/block").glob("loop*/loop/backing_file"):
        try:
            name = backing.read_text().strip()
            if name and Path(name).resolve() == image:
                raise ValueError(f"Detach the loop device before validation: {backing}")
        except FileNotFoundError:
            continue  # A loop device disappeared during enumeration.

    # The caller confirms device_del completion and drive_del/query-block absence.
    # Do not inspect other QEMU processes' /proc/<pid>/fd: Bliss can run as root
    # even after releasing this image. Retain QEMU's normal image locking so
    # conflicting access fails at launch and is captured in the per-mode log.


def _qemu_path(path: Path) -> str:
    # QEMU's key=value option parser escapes commas by doubling them.
    return str(path).replace(",", ",,")


def _stop_process(process: subprocess.Popen) -> None:
    if process.poll() is None:
        try:
            os.killpg(process.pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.wait(timeout=5)
    else:
        process.wait()


def _capture_screen(qmp_path: Path, output: Path) -> None:
    """Capture firmware errors visible only on VGA, before stopping a failed boot."""
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as connection:
        connection.settimeout(3)
        connection.connect(str(qmp_path))
        with connection.makefile("rwb") as stream:
            json.loads(stream.readline())
            for command in ({"execute": "qmp_capabilities"},
                            {"execute": "screendump", "arguments": {"filename": str(output)}}):
                stream.write(json.dumps(command).encode() + b"\n")
                stream.flush()
                while True:
                    reply = json.loads(stream.readline())
                    if "error" in reply:
                        raise RuntimeError(str(reply["error"]))
                    if "return" in reply:
                        break


def _boot_mode(image: Path, directory: Path, mode: str, qemu: str) -> dict:
    directory.mkdir()
    serial = directory / "serial.log"
    stderr = directory / "qemu.log"
    serial.touch()
    # AF_UNIX paths are limited to roughly 108 bytes; evidence paths can be longer.
    qmp_directory = tempfile.TemporaryDirectory(prefix="vendroid-qmp-")
    qmp_path = Path(qmp_directory.name) / "monitor.sock"
    firmware = "SeaBIOS (QEMU default)" if mode == "bios" else "OVMF_CODE_4M.fd (Secure Boot disabled)"
    result = {
        "mode": mode, "firmware": firmware, "passed": False, "marker_seen": False,
        "firmware_seen": False, "timed_out": False, "timeout_seconds": BOOT_TIMEOUT_SECONDS,
        "serial_log": str(serial), "qemu_log": str(stderr), "returncode": None,
    }
    command = [
        qemu, "-no-user-config", "-nodefaults", "-machine", "pc", "-accel", "tcg",
        "-m", "1024", "-smp", "1", "-vga", "std", "-display", "none",
        "-monitor", "none", "-serial", f"file:{serial}", "-nic", "none",
        "-qmp", f"unix:{qmp_path},server=on,wait=off",
        "-no-reboot", "-snapshot", "-boot", "strict=on",
        "-drive", f"file={_qemu_path(image)},format=raw,if=none,id=candidate,media=disk",
        "-device", "usb-ehci,id=ehci",
        "-device", "usb-storage,bus=ehci.0,drive=candidate,bootindex=1,removable=on",
    ]
    started = time.monotonic()
    try:
        if mode == "uefi":
            code = Path("/usr/share/OVMF/OVMF_CODE_4M.fd")
            template = Path("/usr/share/OVMF/OVMF_VARS_4M.fd")
            if not code.is_file() or not template.is_file():
                raise FileNotFoundError("Install ovmf with OVMF_CODE_4M.fd and OVMF_VARS_4M.fd")
            variables = directory / "OVMF_VARS_4M.fd"
            shutil.copyfile(template, variables)
            command.extend([
                "-drive", f"if=pflash,format=raw,unit=0,readonly=on,snapshot=off,file={_qemu_path(code)}",
                "-drive", f"if=pflash,format=raw,unit=1,snapshot=off,file={_qemu_path(variables)}",
            ])
        result["command"] = command
        with stderr.open("wb") as log:
            process = subprocess.Popen(
                command, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
                start_new_session=True,
                # QEMU owns/unlinks its disposable overlays; keep TMPDIR private.
                env={**os.environ, "TMPDIR": str(directory)},
            )
            try:
                deadline = time.monotonic() + BOOT_TIMEOUT_SECONDS
                expected_firmware = f"VENDROID_CI_BOOT_FIRMWARE={'pc' if mode == 'bios' else 'efi'}"
                while True:
                    # Only guest serial output counts. Never search QEMU's own log.
                    lines = serial.read_text(encoding="utf-8", errors="replace").splitlines()
                    result["marker_seen"] = BOOT_MARKER in lines
                    result["firmware_seen"] = expected_firmware in lines
                    if result["marker_seen"] and result["firmware_seen"]:
                        result["passed"] = True
                        break
                    if process.poll() is not None:
                        # Read once more after exit to catch the final serial write.
                        lines = serial.read_text(encoding="utf-8", errors="replace").splitlines()
                        result["marker_seen"] = BOOT_MARKER in lines
                        result["firmware_seen"] = expected_firmware in lines
                        result["passed"] = result["marker_seen"] and result["firmware_seen"]
                        break
                    if time.monotonic() >= deadline:
                        result["timed_out"] = True
                        break
                    time.sleep(0.2)
            finally:
                if not result["passed"] and process.poll() is None:
                    try:
                        screenshot = directory / "screen.ppm"
                        _capture_screen(qmp_path, screenshot)
                        result["screenshot"] = str(screenshot)
                    except (OSError, ValueError, RuntimeError) as error:
                        result["screenshot_error"] = str(error)
                _stop_process(process)
                result["returncode"] = process.returncode
        if not result["passed"]:
            result["error"] = "Probe marker or matching firmware line missing from guest serial output"
    except (OSError, subprocess.SubprocessError) as error:
        result["passed"] = False
        result["error"] = str(error)
    finally:
        result["elapsed_seconds"] = round(time.monotonic() - started, 3)
        qmp_directory.cleanup()
    return result


def verify_firmware_boot(
    image_path: str | Path, output_dir: str | Path, modes: tuple[str, ...] = ("bios", "uefi"),
) -> dict:
    """Boot only the detached raw candidate disk, once per requested firmware.

    Returns a JSON-compatible report on success. On boot/setup failure, raises
    FirmwareBootError with the same report in .report after trying every mode.
    Invalid arguments or failed detachment checks raise ValueError/OSError.
    Each call creates a fresh subdirectory with report.json and per-mode logs,
    commands, timings and private OVMF variables. TCG needs no KVM access and
    uses no Bliss monitor, network, display, or ports. Budget 180s per mode plus
    at most 10s of termination grace. QEMU -snapshot protects the candidate.
    No ISO/CD drive or direct kernel/firmware payload bypass is attached.
    """
    modes = tuple(modes)
    if not modes or len(set(modes)) != len(modes) or any(mode not in ("bios", "uefi") for mode in modes):
        raise ValueError("modes must contain unique entries from ('bios', 'uefi') and cannot be empty")
    if os.name != "posix" or not Path("/proc").is_dir():
        raise ValueError("Firmware validation requires the Linux CI runner")
    image = Path(image_path).resolve(strict=True)
    _check_detached_disk(image)
    qemu = shutil.which("qemu-system-x86_64")
    if qemu is None:
        raise FileNotFoundError("qemu-system-x86_64 is required")
    output = Path(output_dir).resolve()
    output.mkdir(parents=True, exist_ok=True)
    run = Path(tempfile.mkdtemp(prefix="firmware-", dir=output))
    report = {
        "image_path": str(image), "image_format": "raw", "accelerator": "tcg",
        "snapshot": True, "network": False, "secure_boot": False, "marker": BOOT_MARKER,
        "limitations": list(LIMITATIONS), "report_path": str(run / "report.json"),
        "passed": False, "modes": {},
    }
    for mode in modes:
        _check_detached_disk(image)
        report["modes"][mode] = _boot_mode(image, run / mode, mode, qemu)
    report["passed"] = all(result["passed"] for result in report["modes"].values())
    Path(report["report_path"]).write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    if not report["passed"]:
        raise FirmwareBootError(report)
    return report
