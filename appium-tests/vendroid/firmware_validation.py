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


LINUX_IMAGE = "/vendroid-ci-alpine.iso"
LINUX_BOOT_CONFIG = "/ventoy/vendroid-ci-alpine-grub.cfg"
LINUX_SYSLINUX_CONFIG = "/ventoy/vendroid-ci-alpine-syslinux.cfg"
# Emulated USB is slow without KVM; Ventoy's hook must find the disk and mount the ISO.
LINUX_BOOT_TIMEOUT_SECONDS = 600
LINUX_BANNER = "Welcome to Alpine Linux"
LINUX_LOGIN_PROMPT = "login:"
# Alpine standard's own entries with the serial console in place of "quiet". Ventoy
# boots the ISO's GRUB config under UEFI and its ISOLINUX config under BIOS;
# conf_replace substitutes both without changing the ISO. Alpine's minimal virt
# kernel cannot be used: Ventoy's hook then fails to mount the boot media.
# Each replacement stays within the ISO sector of the file it replaces.
LINUX_GRUB_CONFIG = """set timeout=1

menuentry "Linux lts" {
linux /boot/vmlinuz-lts modules=loop,squashfs,sd-mod,usb-storage console=ttyS0,115200
initrd /boot/initramfs-lts
}
"""
LINUX_SYSLINUX_CONFIG_TEXT = """TIMEOUT 10
PROMPT 0
DEFAULT lts

LABEL lts
KERNEL /boot/vmlinuz-lts
INITRD /boot/initramfs-lts
APPEND modules=loop,squashfs,sd-mod,usb-storage console=ttyS0,115200
"""
# One Linux boot per channel and layout is enough; the uhci jobs keep their duration.
LINUX_BOOT_CONTROLLER = "xhci"
LINUX_LIMITATIONS = [
    "Boots one pinned Alpine Linux ISO through Ventoy's Linux hook under emulated x86 BIOS and/or x64 UEFI.",
    "Its GRUB and ISOLINUX entries are replaced through Ventoy's conf_replace plugin only to add a serial console.",
    "Does not validate Secure Boot, vendor firmware, physical USB hardware, or other distributions.",
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


def _linux_fixture_paths(mount_root: str | Path) -> tuple[Path, Path, Path, Path, Path]:
    root = Path(mount_root).resolve(strict=True)
    if not root.is_dir() or not root.is_mount():
        raise ValueError(f"Expected a mounted data partition: {root}")
    paths = (
        root / LINUX_IMAGE.lstrip("/"), root / LINUX_BOOT_CONFIG.lstrip("/"),
        root / LINUX_SYSLINUX_CONFIG.lstrip("/"), root / "ventoy" / "ventoy.json",
    )
    for path in (*paths, root / "ventoy"):
        if path.is_symlink() or not path.resolve().is_relative_to(root):
            raise ValueError(f"Refusing a boot-file path outside the mounted fixture: {path}")
    return root, *paths


def prepare_linux_boot_files(mount_root: str | Path, linux_iso: str | Path) -> dict:
    """Copy the pinned Alpine ISO and make Ventoy boot it with a serial console.

    Replaces /ventoy/ventoy.json. Call prepare_boot_files afterwards to restore the
    GRUB probe for later checkpoints. The caller verifies the ISO's pinned SHA-256.
    https://www.ventoy.net/en/plugin_bootconf_replace.html
    """
    root, destination, boot_config, syslinux_config, config_path = _linux_fixture_paths(mount_root)
    source = Path(linux_iso).resolve(strict=True)
    if not source.is_file():
        raise ValueError(f"Expected a regular Linux ISO: {source}")
    with source.open("rb") as image:
        image.seek(16 * 2048 + 1)
        if image.read(5) != b"CD001":
            raise ValueError(f"Linux image is not an ISO9660 image: {source}")
    config_path.parent.mkdir(exist_ok=True)
    shutil.copyfile(source, destination)
    boot_config.write_text(LINUX_GRUB_CONFIG, encoding="utf-8")
    syslinux_config.write_text(LINUX_SYSLINUX_CONFIG_TEXT, encoding="utf-8")
    config = {
        "control": [
            {"VTOY_DEFAULT_IMAGE": LINUX_IMAGE},
            {"VTOY_MENU_TIMEOUT": "2"},
            {"VTOY_SECONDARY_BOOT_MENU": "1"},
            {"VTOY_SECONDARY_TIMEOUT": "2"},
        ],
        "conf_replace": [
            {"iso": LINUX_IMAGE, "org": "/boot/grub/grub.cfg", "new": LINUX_BOOT_CONFIG},
            {"iso": LINUX_IMAGE, "org": "/boot/syslinux/syslinux.cfg", "new": LINUX_SYSLINUX_CONFIG},
        ],
    }
    config_path.write_text(json.dumps(config, indent=2) + "\n", encoding="utf-8")
    return {"linux_iso": str(destination), "boot_config": str(boot_config),
            "syslinux_config": str(syslinux_config), "ventoy_json": str(config_path),
            "control": config["control"], "conf_replace": config["conf_replace"]}


def remove_linux_boot_files(mount_root: str | Path) -> None:
    """Delete the Alpine ISO and its boot entries. The caller restores ventoy.json."""
    _, destination, boot_config, syslinux_config, _ = _linux_fixture_paths(mount_root)
    for path in (destination, boot_config, syslinux_config):
        path.unlink(missing_ok=True)


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


def _probe_state(mode: str):
    expected_firmware = f"VENDROID_CI_BOOT_FIRMWARE={'pc' if mode == 'bios' else 'efi'}"

    def state(lines: list[str]) -> dict:
        marker = BOOT_MARKER in lines
        firmware = expected_firmware in lines
        return {"marker_seen": marker, "firmware_seen": firmware, "passed": marker and firmware}

    return state


def linux_boot_state(lines: list[str]) -> dict:
    """Alpine reached userspace: its issue banner and a getty login prompt on the serial console."""
    banner = any(line.lstrip().startswith(LINUX_BANNER) for line in lines)
    login = any(line.rstrip().endswith(LINUX_LOGIN_PROMPT) for line in lines)
    return {"banner_seen": banner, "login_seen": login, "passed": banner and login}


def _boot_mode(
    image: Path, directory: Path, mode: str, qemu: str, *,
    state=None, timeout: int = BOOT_TIMEOUT_SECONDS, accelerator: str = "tcg",
    failure_message: str = "Probe marker or matching firmware line missing from guest serial output",
) -> dict:
    state = state or _probe_state(mode)
    directory.mkdir()
    serial = directory / "serial.log"
    stderr = directory / "qemu.log"
    serial.touch()
    # AF_UNIX paths are limited to roughly 108 bytes; evidence paths can be longer.
    qmp_directory = tempfile.TemporaryDirectory(prefix="vendroid-qmp-")
    qmp_path = Path(qmp_directory.name) / "monitor.sock"
    firmware = "SeaBIOS (QEMU default)" if mode == "bios" else "OVMF_CODE_4M.fd (Secure Boot disabled)"
    result = {
        "mode": mode, "firmware": firmware, "passed": False, **state([]),
        "timed_out": False, "timeout_seconds": timeout, "accelerator": accelerator,
        "serial_log": str(serial), "qemu_log": str(stderr), "returncode": None,
    }
    command = [
        qemu, "-no-user-config", "-nodefaults", "-machine", "pc", "-accel", accelerator,
        *(["-cpu", "max"] if accelerator == "kvm" else []),
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
                deadline = time.monotonic() + timeout
                while True:
                    # Only guest serial output counts. Never search QEMU's own log.
                    result.update(state(serial.read_text(encoding="utf-8", errors="replace").splitlines()))
                    if result["passed"]:
                        break
                    if process.poll() is not None:
                        # Read once more after exit to catch the final serial write.
                        result.update(state(serial.read_text(encoding="utf-8", errors="replace").splitlines()))
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
            result["error"] = failure_message
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


def linux_boot_required(usb_bus: str) -> bool:
    """Lifecycle jobs on this controller must record linux-boot-after-install."""
    return usb_bus == f"{LINUX_BOOT_CONTROLLER}.0"


def linux_boot_accelerator() -> str:
    """KVM when the runner exposes it; the Linux boot is impractically slow under TCG."""
    return "kvm" if os.access("/dev/kvm", os.R_OK | os.W_OK) else "tcg"


def verify_linux_boot(
    image_path: str | Path, output_dir: str | Path, modes: tuple[str, ...] = ("bios", "uefi"),
) -> dict:
    """Boot the Alpine ISO from prepare_linux_boot_files to its login prompt.

    Same contract as verify_firmware_boot, with a fresh linux-* subdirectory and
    report.json. Passing requires Alpine's issue banner and getty prompt on the
    guest serial console, which Ventoy's Linux hook reaches only after it finds
    this USB disk and mounts the ISO. Budget 600s per mode.
    """
    modes = tuple(modes)
    if not modes or len(set(modes)) != len(modes) or any(mode not in ("bios", "uefi") for mode in modes):
        raise ValueError("modes must contain unique entries from ('bios', 'uefi') and cannot be empty")
    if os.name != "posix" or not Path("/proc").is_dir():
        raise ValueError("Linux boot validation requires the Linux CI runner")
    image = Path(image_path).resolve(strict=True)
    _check_detached_disk(image)
    qemu = shutil.which("qemu-system-x86_64")
    if qemu is None:
        raise FileNotFoundError("qemu-system-x86_64 is required")
    accelerator = linux_boot_accelerator()
    output = Path(output_dir).resolve()
    output.mkdir(parents=True, exist_ok=True)
    run = Path(tempfile.mkdtemp(prefix="linux-", dir=output))
    report = {
        "image_path": str(image), "image_format": "raw", "accelerator": accelerator,
        "snapshot": True, "network": False, "secure_boot": False,
        "linux_image": LINUX_IMAGE, "banner": LINUX_BANNER, "login_prompt": LINUX_LOGIN_PROMPT,
        "limitations": list(LINUX_LIMITATIONS), "report_path": str(run / "report.json"),
        "passed": False, "modes": {},
    }
    for mode in modes:
        _check_detached_disk(image)
        report["modes"][mode] = _boot_mode(
            image, run / mode, mode, qemu, state=linux_boot_state, timeout=LINUX_BOOT_TIMEOUT_SECONDS,
            accelerator=accelerator, failure_message="Alpine banner or login prompt missing from guest serial output",
        )
    report["passed"] = all(result["passed"] for result in report["modes"].values())
    Path(report["report_path"]).write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    if not report["passed"]:
        raise FirmwareBootError(report)
    return report
