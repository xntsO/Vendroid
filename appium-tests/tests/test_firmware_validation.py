"""Stdlib-only regressions for the CI boot harness. Run in GitHub Actions, without QEMU."""

import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

from vendroid import firmware_validation as firmware


def iso_fixture(path: Path) -> Path:
    data = bytearray(17 * 2048)
    data[16 * 2048 + 1:16 * 2048 + 6] = b"CD001"
    path.write_bytes(bytes(data))
    return path


class LinuxBootStateTest(unittest.TestCase):
    def test_banner_and_login_prompt_are_both_required(self):
        console = ["Mounting boot media, please wait ......done", "",
                   "Welcome to Alpine Linux 3.24", "Kernel 6.18.60-0-lts on x86_64 (/dev/ttyS0)", "",
                   "localhost login: "]
        self.assertTrue(firmware.linux_boot_state(console)["passed"])
        for missing in ("Welcome to Alpine Linux 3.24", "localhost login: "):
            with self.subTest(missing=missing):
                state = firmware.linux_boot_state([line for line in console if line != missing])
                self.assertFalse(state["passed"])

    def test_the_ventoy_hook_alone_does_not_pass(self):
        # The stall seen on real hardware: Ventoy's hook never finds the disk again.
        self.assertEqual(
            firmware.linux_boot_state(["Mounting boot media, please wait ......"]),
            {"banner_seen": False, "login_seen": False, "passed": False},
        )

    def test_probe_state_keeps_its_marker_and_firmware_requirements(self):
        state = firmware._probe_state("uefi")
        self.assertTrue(state([firmware.BOOT_MARKER, "VENDROID_CI_BOOT_FIRMWARE=efi"])["passed"])
        self.assertFalse(state([firmware.BOOT_MARKER, "VENDROID_CI_BOOT_FIRMWARE=pc"])["passed"])

    def test_only_the_xhci_jobs_require_the_linux_boot(self):
        self.assertTrue(firmware.linux_boot_required("xhci.0"))
        self.assertFalse(firmware.linux_boot_required("uhci.0"))


class LinuxBootFilesTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name) / "data"
        self.root.mkdir()
        self.iso = iso_fixture(Path(temporary.name) / "alpine.iso")
        patcher = mock.patch.object(Path, "is_mount", lambda path: path == self.root.resolve())
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_boots_the_unchanged_iso_with_a_replaced_serial_console_entry(self):
        result = firmware.prepare_linux_boot_files(self.root, self.iso)

        copied = self.root / firmware.LINUX_IMAGE.lstrip("/")
        self.assertEqual(copied.read_bytes(), self.iso.read_bytes())
        grub = (self.root / firmware.LINUX_BOOT_CONFIG.lstrip("/")).read_text(encoding="utf-8")
        syslinux = (self.root / firmware.LINUX_SYSLINUX_CONFIG.lstrip("/")).read_text(encoding="utf-8")
        for entry in (grub, syslinux):
            self.assertIn("console=ttyS0,115200", entry)
            self.assertIn("/boot/vmlinuz-lts", entry)
            self.assertIn("/boot/initramfs-lts", entry)
            # Ventoy overrides the file within the ISO; stay inside one 2048-byte sector.
            self.assertLessEqual(len(entry.encode("utf-8")), 2048)
        # Without a timeout prompt that waits for input, ISOLINUX boots on its own.
        self.assertIn("PROMPT 0", syslinux)
        self.assertNotIn("SERIAL", syslinux)
        config = json.loads((self.root / "ventoy" / "ventoy.json").read_text(encoding="utf-8"))
        self.assertIn({"VTOY_DEFAULT_IMAGE": firmware.LINUX_IMAGE}, config["control"])
        # UEFI boots the ISO's GRUB config and BIOS its ISOLINUX config; replace both.
        self.assertEqual(config["conf_replace"], [
            {"iso": firmware.LINUX_IMAGE, "org": "/boot/grub/grub.cfg", "new": firmware.LINUX_BOOT_CONFIG},
            {"iso": firmware.LINUX_IMAGE, "org": "/boot/syslinux/syslinux.cfg",
             "new": firmware.LINUX_SYSLINUX_CONFIG},
        ])
        # Ventoy's control plugin accepts only string values.
        self.assertTrue(all(isinstance(value, str) for item in config["control"] for value in item.values()))
        self.assertEqual(result["conf_replace"], config["conf_replace"])

    def test_removal_deletes_only_the_linux_files(self):
        firmware.prepare_linux_boot_files(self.root, self.iso)
        keep = self.root / "keep.txt"
        keep.write_text("ordinary file", encoding="utf-8")

        firmware.remove_linux_boot_files(self.root)
        firmware.remove_linux_boot_files(self.root)

        self.assertFalse((self.root / firmware.LINUX_IMAGE.lstrip("/")).exists())
        self.assertFalse((self.root / firmware.LINUX_BOOT_CONFIG.lstrip("/")).exists())
        self.assertFalse((self.root / firmware.LINUX_SYSLINUX_CONFIG.lstrip("/")).exists())
        self.assertTrue(keep.exists())

    def test_rejects_a_non_iso_and_an_unmounted_root(self):
        not_iso = self.iso.with_name("not-an.iso")
        not_iso.write_bytes(b"\0" * 40000)
        with self.assertRaises(ValueError):
            firmware.prepare_linux_boot_files(self.root, not_iso)
        self.assertFalse((self.root / "ventoy" / "ventoy.json").exists())
        with self.assertRaises(ValueError):
            firmware.prepare_linux_boot_files(self.iso.parent, self.iso)

    def test_refuses_a_ventoy_directory_that_escapes_the_partition(self):
        outside = self.iso.parent / "outside"
        outside.mkdir()
        try:
            (self.root / "ventoy").symlink_to(outside, target_is_directory=True)
        except OSError:
            self.skipTest("Symlinks are unavailable")
        with self.assertRaises(ValueError):
            firmware.prepare_linux_boot_files(self.root, self.iso)
        self.assertEqual(list(outside.iterdir()), [])


class UsbControllerTest(unittest.TestCase):
    def test_linux_boot_uses_xhci_and_unknown_controllers_are_rejected(self):
        # Over emulated EHCI, Ventoy's Alpine hook took about 450 s per boot.
        self.assertEqual(firmware.USB_CONTROLLER_DEVICES[firmware.LINUX_BOOT_CONTROLLER], "qemu-xhci")
        self.assertEqual(firmware.USB_CONTROLLER_DEVICES["ehci"], "usb-ehci")
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError):
                firmware._boot_mode(Path(directory) / "disk.img", Path(directory) / "run", "bios",
                                    "qemu-system-x86_64", usb_controller="ohci")
            self.assertFalse((Path(directory) / "run").exists())


class LinuxBootArgumentsTest(unittest.TestCase):
    def test_invalid_modes_are_rejected_before_any_boot(self):
        for modes in ((), ("bios", "bios"), ("arm",)):
            with self.subTest(modes=modes):
                with self.assertRaises(ValueError):
                    firmware.verify_linux_boot("/nonexistent.img", "/nonexistent", modes)


if __name__ == "__main__":
    unittest.main()
