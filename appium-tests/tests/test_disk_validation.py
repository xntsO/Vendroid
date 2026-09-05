"""Stdlib-only synthetic image tests; no Appium, QEMU, mounts, or sudo.

CI: python -m unittest discover -s tests -p test_disk_validation.py
or: python -m pytest tests/test_disk_validation.py
Run from appium-tests. Fixtures encode disk structures directly, independently
of the Android formatter and partition writer.
"""

import hashlib
import importlib.util
import io
import json
import lzma
import os
from pathlib import Path
import struct
import subprocess
import tarfile
import tempfile
import unittest
from unittest import mock
import uuid
import zlib


# Load just the helper so this suite never imports application/device fixtures.
_spec = importlib.util.spec_from_file_location(
    "vendroid_disk_validation",
    Path(__file__).resolve().parents[1] / "vendroid" / "disk_validation.py",
)
dv = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(dv)

SECTOR = 512
TOTAL = 81920
DATA_START = 2048
DATA_LENGTH = 8192
EFI_START = DATA_START + DATA_LENGTH
EFI_LENGTH = 65536
ROOT_CLUSTER = 5
ROOT_OFFSET = (DATA_START + 32 + (ROOT_CLUSTER - 2) * 8) * SECTOR
LABEL = "TOOLS-\u6d4b"
BASIC_GUID = uuid.UUID("ebd0a0a2-b9e5-4433-87c0-68b6b72699c7").bytes_le
DISK_GUID = uuid.UUID("12345678-1234-4abc-8def-123456789abc").bytes_le


def put(path, offset, data):
    with path.open("r+b") as disk:
        disk.seek(offset)
        disk.write(data)


def take(path, offset, length):
    with path.open("rb") as disk:
        disk.seek(offset)
        return disk.read(length)


def header_crc(header):
    header = bytearray(header)
    length = struct.unpack_from("<I", header, 12)[0]
    struct.pack_into("<I", header, 16, 0)
    struct.pack_into("<I", header, 16, zlib.crc32(header[:length]))
    return header


def gpt_header(lba, other, table_lba, table):
    header = bytearray(SECTOR)
    header[:8] = b"EFI PART"
    struct.pack_into("<IIIIQQQQ", header, 8, 0x10000, 92, 0, 0,
                     lba, other, 34, TOTAL - 34)
    header[56:72] = DISK_GUID
    struct.pack_into("<QIII", header, 72, table_lba, 128, 128, zlib.crc32(table))
    return header_crc(header)


def gpt_table():
    table = bytearray(16384)
    for index, (start, length, name, attrs) in enumerate((
        (DATA_START, DATA_LENGTH, "Ventoy", 0),
        (EFI_START, EFI_LENGTH, "VTOYEFI", 1 << 63),
    )):
        offset = index * 128
        table[offset:offset + 16] = BASIC_GUID
        table[offset + 16:offset + 32] = uuid.UUID(int=index + 10).bytes_le
        struct.pack_into("<QQQ", table, offset + 32, start, start + length - 1, attrs)
        encoded = name.encode("utf-16le")
        table[offset + 56:offset + 56 + len(encoded)] = encoded
    return table


def write_gpt(path, primary=None, backup=None):
    primary = gpt_table() if primary is None else primary
    backup = primary if backup is None else backup
    put(path, 2 * SECTOR, primary)
    put(path, (TOTAL - 33) * SECTOR, backup)
    put(path, SECTOR, gpt_header(1, TOTAL - 1, 2, primary))
    put(path, (TOTAL - 1) * SECTOR, gpt_header(TOTAL - 1, 1, TOTAL - 33, backup))


def checksum_region(region):
    """Fixture implementation of the exFAT rotate-right checksum."""
    checksum = 0
    for index in range(11 * SECTOR):
        if index in (106, 107, 112):
            continue
        checksum = ((checksum // 2) + (0x80000000 if checksum % 2 else 0) + region[index]) % (1 << 32)
    region[11 * SECTOR:] = struct.pack("<I", checksum) * 128
    return region


def exfat_region():
    region = bytearray(12 * SECTOR)
    region[:11] = b"\xeb\x76\x90EXFAT   "
    struct.pack_into("<QQIIIIIIHH", region, 64, DATA_START, DATA_LENGTH,
                     24, 8, 32, 1020, ROOT_CLUSTER, 0xABCD1234, 0x100, 0)
    region[108:113] = bytes([9, 3, 1, 0x80, 0])
    for sector in range(9):
        region[sector * SECTOR + 510:sector * SECTOR + 512] = b"\x55\xaa"
    return checksum_region(region)


def label_entry(label=LABEL):
    entry = bytearray(32)
    encoded = label.encode("utf-16le")
    entry[0:2] = bytes([0x83, len(encoded) // 2])
    entry[2:2 + len(encoded)] = encoded
    return entry


def make_image(path, style="gpt"):
    with path.open("wb") as disk:
        disk.truncate(TOTAL * SECTOR)
    mbr = bytearray(SECTOR)
    mbr[384:400] = bytes(range(16))
    mbr[440:444] = b"\x12\x34\x56\x78"
    mbr[510:512] = b"\x55\xaa"
    if style == "gpt":
        struct.pack_into("<B3xB3xII", mbr, 446, 0, 0xEE, 1, TOTAL - 1)
    else:
        struct.pack_into("<B3xB3xII", mbr, 446, 0x80, 0x07, DATA_START, DATA_LENGTH)
        struct.pack_into("<B3xB3xII", mbr, 462, 0, 0xEF, EFI_START, EFI_LENGTH)
    put(path, 0, mbr)
    if style == "gpt":
        write_gpt(path)
    region = exfat_region()
    put(path, DATA_START * SECTOR, region)
    put(path, (DATA_START + 12) * SECTOR, region)
    fat = bytearray(8 * SECTOR)
    struct.pack_into("<II", fat, 0, 0xFFFFFFF8, 0xFFFFFFFF)
    struct.pack_into("<I", fat, ROOT_CLUSTER * 4, 0xFFFFFFFF)
    put(path, (DATA_START + 24) * SECTOR, fat)
    # Label is not the first root entry. Deleted labels must be ignored.
    root = bytearray(8 * SECTOR)
    root[0], root[32], root[64] = 0x81, 0x82, 0x03
    root[96:128] = label_entry()
    put(path, ROOT_OFFSET, root)
    return path


def make_archive(path, *, core_length=2047 * SECTOR, efi_length=EFI_LENGTH * SECTOR,
                 duplicate=False, missing=False, symlink=False, bad_xz=False,
                 prefix="./"):
    boot = bytearray(b"B" * SECTOR)
    boot[510:512] = b"\x55\xaa"
    members = [("boot/boot.img", bytes(boot)),
               ("boot/core.img.xz", b"not xz" if bad_xz else lzma.compress(b"C" * core_length))]
    if not missing:
        members.append(("ventoy/ventoy.disk.img.xz", lzma.compress(b"E" * efi_length)))
    if duplicate:
        members.append(members[0])
    with tarfile.open(path, "w:gz") as bundle:
        for suffix, data in members:
            member = tarfile.TarInfo(prefix + "ventoy-1.1.15/" + suffix)
            member.size = len(data)
            if symlink and suffix == "boot/boot.img":
                member.type = tarfile.SYMTYPE
                member.linkname = "/etc/passwd"
                member.size = 0
                bundle.addfile(member)
            else:
                bundle.addfile(member, io.BytesIO(data))
    return path


class ImageInspectionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.image = self.root / "disk.img"

    def assert_invalid(self, message, style="gpt"):
        with self.assertRaisesRegex(ValueError, message):
            dv.inspect_image(self.image, style)

    def test_both_styles_return_stable_json_metadata(self):
        for style in ("mbr", "gpt"):
            with self.subTest(style=style):
                make_image(self.image, style)
                result = dv.inspect_image(self.image, style)
                self.assertEqual(json.loads(json.dumps(result)), result)
                self.assertEqual(result, dv.image_snapshot(self.image, style))
                self.assertEqual(result["partitions"][0]["start_lba"], DATA_START)
                self.assertEqual(result["partitions"][1]["end_lba"], EFI_START + EFI_LENGTH - 1)
                self.assertEqual(result["identity"]["ventoy_uuid"], bytes(range(16)).hex())
                self.assertEqual(result["identity"]["mbr_disk_signature"], "12345678")
                self.assertEqual(result["exfat"]["label"], LABEL)
                self.assertEqual(result["exfat"]["cluster_size"], 4096)
                self.assertEqual(result["exfat"]["volume_serial"], 0xABCD1234)
                self.assertEqual(result["reserved_bytes"],
                                 (TOTAL - EFI_START - EFI_LENGTH - (33 if style == "gpt" else 0)) * SECTOR)
                if style == "gpt":
                    for copy in ("primary", "backup"):
                        metadata = result["gpt"][copy]
                        self.assertEqual(metadata["header_crc32"], metadata["computed_header_crc32"])
                        self.assertEqual(metadata["table_crc32"], metadata["computed_table_crc32"])

    def test_rejects_directories_devices_symlinks_and_misalignment(self):
        with self.assertRaisesRegex(ValueError, "regular file"):
            dv.inspect_image(self.root, "gpt")
        make_image(self.image)
        with self.image.open("ab") as disk:
            disk.write(b"!")
        self.assert_invalid("aligned")
        self.image.write_bytes(b"\0" * 512)
        self.assert_invalid("geometry")
        if os.name == "posix":
            with self.assertRaisesRegex(ValueError, "regular file"):
                dv.inspect_image("/dev/null", "gpt")
            link = self.root / "link.img"
            link.symlink_to(self.image)
            with self.assertRaisesRegex(ValueError, "regular file"):
                dv.inspect_image(link, "gpt")

    def test_style_and_boot_signature(self):
        make_image(self.image)
        self.assert_invalid("expected_style", "other")
        self.assert_invalid("style", "mbr")
        put(self.image, 510, b"\0\0")
        self.assert_invalid("signature")

    def test_protective_mbr_coverage_and_hybrid_entries(self):
        make_image(self.image)
        put(self.image, 458, struct.pack("<I", TOTAL - 2))
        self.assert_invalid("protective MBR")
        make_image(self.image)
        put(self.image, 462, struct.pack("<B3xB3xII", 0, 7, 100, 20))
        self.assert_invalid("protective MBR")

    def test_each_gpt_header_and_table_crc_is_checked(self):
        for name, lba, table_lba in (("primary", 1, 2), ("backup", TOTAL - 1, TOTAL - 33)):
            for field, offset in (("header", lba * SECTOR + 16), ("table", table_lba * SECTOR + 60)):
                with self.subTest(copy=name, field=field):
                    make_image(self.image)
                    byte = take(self.image, offset, 1)[0]
                    put(self.image, offset, bytes([byte ^ 1]))
                    self.assert_invalid(f"{name} GPT {field} CRC")

    def test_checks_header_geometry_even_with_valid_crc(self):
        for name, lba in (("primary", 1), ("backup", TOTAL - 1)):
            for field, value, fmt in ((24, 10, "<Q"), (32, 10, "<Q"), (40, 35, "<Q"),
                                      (48, TOTAL - 10, "<Q"), (72, 100, "<Q"),
                                      (80, 0xFFFFFFFF, "<I"), (84, 256, "<I")):
                with self.subTest(copy=name, field=field):
                    make_image(self.image)
                    header = bytearray(take(self.image, lba * SECTOR, SECTOR))
                    struct.pack_into(fmt, header, field, value)
                    put(self.image, lba * SECTOR, header_crc(header))
                    self.assert_invalid("geometry")

    def test_individually_valid_gpt_copies_must_agree(self):
        make_image(self.image)
        table = gpt_table()
        table[56:58] = "X".encode("utf-16le")
        write_gpt(self.image, backup=table)
        self.assert_invalid("tables disagree")
        make_image(self.image)
        header = bytearray(take(self.image, (TOTAL - 1) * SECTOR, SECTOR))
        header[56] ^= 1
        put(self.image, (TOTAL - 1) * SECTOR, header_crc(header))
        self.assert_invalid("GUIDs disagree")

    def test_gpt_partition_bounds_overlap_and_identity(self):
        cases = ((32, "<Q", 33, "outside"), (40, "<Q", EFI_START, "overlap"),
                 (128 + 40, "<Q", TOTAL - 1, "outside"),
                 (128 + 48, "<Q", 0, "metadata"))
        for offset, fmt, value, error in cases:
            with self.subTest(offset=offset):
                make_image(self.image)
                table = gpt_table()
                struct.pack_into(fmt, table, offset, value)
                write_gpt(self.image, primary=table)
                self.assert_invalid(error)
        make_image(self.image)
        table = gpt_table()
        table[144:160] = table[16:32]
        write_gpt(self.image, primary=table)
        self.assert_invalid("duplicate")

    def test_mbr_partition_bounds_overlap_and_types(self):
        for offset, value, error in ((470, DATA_START + 1, "overlap"),
                                      (474, TOTAL, "outside"), (458, 0, "Invalid MBR")):
            with self.subTest(offset=offset):
                make_image(self.image, "mbr")
                put(self.image, offset, struct.pack("<I", value))
                self.assert_invalid(error, "mbr")
        make_image(self.image, "mbr")
        put(self.image, 450, b"\x0f")
        self.assert_invalid("Extended", "mbr")

    def test_exfat_checks_both_boot_checksums(self):
        for name, base in (("main", DATA_START), ("backup", DATA_START + 12)):
            with self.subTest(copy=name):
                make_image(self.image)
                put(self.image, base * SECTOR + 200, b"x")
                self.assert_invalid(f"{name} exFAT boot checksum")

    def test_exfat_rejects_valid_checksum_with_invalid_geometry(self):
        for offset, value, fmt in ((64, DATA_START + 1, "<Q"), (72, DATA_LENGTH + 1, "<Q"),
                                   (80, 1, "<I"), (84, 0, "<I"), (88, DATA_LENGTH, "<I"),
                                   (92, 0, "<I"), (96, 2000, "<I"), (108, 12, "<B"),
                                   (109, 30, "<B"), (110, 2, "<B")):
            with self.subTest(offset=offset):
                make_image(self.image)
                region = exfat_region()
                struct.pack_into(fmt, region, offset, value)
                checksum_region(region)
                for base in (DATA_START, DATA_START + 12):
                    put(self.image, base * SECTOR, region)
                self.assert_invalid("exFAT")

    def test_mutable_exfat_flags_and_usage_do_not_change_snapshot(self):
        make_image(self.image)
        baseline = dv.inspect_image(self.image, "gpt")
        put(self.image, DATA_START * SECTOR + 106, b"\x02")
        put(self.image, DATA_START * SECTOR + 112, b"\x25")
        self.assertEqual(baseline, dv.inspect_image(self.image, "gpt"))

    def test_label_can_live_in_second_root_cluster(self):
        make_image(self.image)
        put(self.image, ROOT_OFFSET, b"\x03" + b"\0" * 31)
        put(self.image, ROOT_OFFSET, (b"\x03" + b"\0" * 31) * 128)
        put(self.image, (DATA_START + 24) * SECTOR + ROOT_CLUSTER * 4, struct.pack("<I", 7))
        put(self.image, (DATA_START + 24) * SECTOR + 7 * 4, struct.pack("<I", 0xFFFFFFFF))
        put(self.image, (DATA_START + 32 + (7 - 2) * 8) * SECTOR, label_entry("SECOND"))
        self.assertEqual(dv.inspect_image(self.image, "gpt")["exfat"]["label"], "SECOND")

    def test_root_chain_cycles_and_bad_label_fail(self):
        make_image(self.image)
        put(self.image, ROOT_OFFSET, (b"\x03" + b"\0" * 31) * 128)
        put(self.image, (DATA_START + 24) * SECTOR + ROOT_CLUSTER * 4, struct.pack("<I", ROOT_CLUSTER))
        self.assert_invalid("cyclic")
        make_image(self.image)
        put(self.image, ROOT_OFFSET + 97, b"\x0c")
        self.assert_invalid("label")

    def test_inspection_reads_only_metadata(self):
        make_image(self.image)
        original = dv._read
        spans = []

        def measured(disk, offset, length):
            spans.append((offset, length))
            return original(disk, offset, length)

        with mock.patch.object(dv, "_read", side_effect=measured):
            dv.inspect_image(self.image, "gpt")
        self.assertLess(sum(length for _, length in spans), 64 * 1024)
        self.assertLessEqual(max(length for _, length in spans), 16384)
        self.assertFalse(any(EFI_START * SECTOR <= offset < (EFI_START + EFI_LENGTH) * SECTOR
                             for offset, _ in spans))

    def test_damage_changes_exactly_one_crc_byte_and_refuses_second_damage(self):
        for which, lba in (("primary", 1), ("backup", TOTAL - 1)):
            with self.subTest(which=which):
                make_image(self.image)
                before = dv.inspect_image(self.image, "gpt")
                header = take(self.image, lba * SECTOR, SECTOR)
                with mock.patch.object(dv.os, "fsync", wraps=os.fsync) as sync:
                    self.assertEqual(dv.damage_gpt_header(self.image, which), before)
                    sync.assert_called_once()
                changed = take(self.image, lba * SECTOR, SECTOR)
                self.assertEqual([i for i in range(SECTOR) if header[i] != changed[i]], [16])
                self.assertEqual(changed[16], header[16] ^ 1)
                self.assert_invalid(f"{which} GPT header CRC")
                with self.assertRaisesRegex(ValueError, "CRC"):
                    dv.damage_gpt_header(self.image, "backup" if which == "primary" else "primary")

    def test_damage_rejects_invalid_target_without_writing(self):
        make_image(self.image)
        baseline = dv.inspect_image(self.image, "gpt")
        with self.assertRaisesRegex(ValueError, "which"):
            dv.damage_gpt_header(self.image, "both")
        self.assertEqual(baseline, dv.inspect_image(self.image, "gpt"))


class PreservationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def test_roundtrip_unicode_nested_binary_empty_and_changed_file(self):
        manifest = dv.create_preservation_files(self.root)
        self.assertEqual(len(manifest), 4)
        self.assertTrue(any(not name.isascii() for name in manifest))
        self.assertTrue(any((self.root / name).stat().st_size > 1024 * 1024 for name in manifest))
        self.assertIn(hashlib.sha256(b"").hexdigest(), manifest.values())
        for name, digest in manifest.items():
            self.assertFalse(Path(name).is_absolute())
            self.assertEqual(hashlib.sha256((self.root / name).read_bytes()).hexdigest(), digest)
        dv.verify_preservation_files(self.root, manifest)
        victim = self.root / next(iter(manifest))
        victim.write_bytes(b"corrupted")
        with self.assertRaisesRegex(ValueError, "SHA256 mismatch"):
            dv.verify_preservation_files(self.root, manifest)
        victim.unlink()
        with self.assertRaises(FileNotFoundError):
            dv.verify_preservation_files(self.root, manifest)

    def test_creation_refuses_overwrite(self):
        manifest = dv.create_preservation_files(self.root)
        with self.assertRaisesRegex(ValueError, "already exists"):
            dv.create_preservation_files(self.root)
        dv.verify_preservation_files(self.root, manifest)

    def test_manifest_rejects_escape_and_empty_manifest(self):
        for name in ("../outside", "/absolute", "C:/absolute", "a\\..\\outside", ".", ""):
            with self.subTest(name=name), self.assertRaises(ValueError):
                dv.verify_preservation_files(self.root, {name: "0" * 64})
        with self.assertRaises(ValueError):
            dv.verify_preservation_files(self.root, {})

    @unittest.skipUnless(os.name == "posix", "Symlink fixtures require POSIX")
    def test_manifest_and_creation_reject_symlinks(self):
        real = self.root / "real"
        real.mkdir()
        (self.root / "vendroid-preservation").symlink_to(real, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, "symlinks"):
            dv.create_preservation_files(self.root)
        (real / "file").write_bytes(b"x")
        with self.assertRaisesRegex(ValueError, "symlinks"):
            dv.verify_preservation_files(self.root, {"vendroid-preservation/file": "0" * 64})


class MountTests(unittest.TestCase):
    def setUp(self):
        self.enterContext(mock.patch.object(dv.os, "getuid", return_value=1001, create=True))
        self.enterContext(mock.patch.object(dv.os, "getgid", return_value=1002, create=True))
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.image = make_image(Path(self.temp.name) / "disk.img")
        self.calls = []
        self.failed_command = None

    def run_command(self, command, **kwargs):
        self.calls.append(command)
        if self.failed_command and self.failed_command in command:
            raise subprocess.CalledProcessError(1, command)
        return subprocess.CompletedProcess(command, 0, stdout="/dev/loop7\n")

    def context(self, writable=False):
        return dv.mounted_data_partition(self.image, writable=writable)

    def test_readonly_mount_uses_exact_partition_bounds_and_cleans_up(self):
        with mock.patch.object(dv.sys, "platform", "linux"), \
                mock.patch.object(dv.subprocess, "run", side_effect=self.run_command):
            with self.context() as root:
                self.assertIsInstance(root, Path)
                self.assertTrue(root.is_dir())
        setup, mount, unmount, detach = self.calls
        self.assertEqual(setup[:5], ["sudo", "-n", "losetup", "--find", "--show"])
        self.assertEqual(setup[setup.index("--offset") + 1], str(DATA_START * SECTOR))
        self.assertEqual(setup[setup.index("--sizelimit") + 1], str(DATA_LENGTH * SECTOR))
        self.assertIn("--read-only", setup)
        self.assertIn("/proc/", setup[-1])
        self.assertEqual(mount[:4], ["sudo", "-n", "mount.exfat-fuse", "-o"])
        self.assertEqual(mount[4], "ro,uid=1001,gid=1002,umask=077,allow_other,default_permissions")
        self.assertEqual(mount[5], "/dev/loop7")
        self.assertEqual(unmount, ["sudo", "-n", "umount", str(root)])
        self.assertEqual(detach, ["sudo", "-n", "losetup", "--detach", "/dev/loop7"])
        self.assertFalse(root.exists())

    def test_writable_mount_cleans_up_on_body_exception(self):
        with mock.patch.object(dv.sys, "platform", "linux"), \
                mock.patch.object(dv.os, "getuid", return_value=1001, create=True), \
                mock.patch.object(dv.os, "getgid", return_value=1002, create=True), \
                mock.patch.object(dv.subprocess, "run", side_effect=self.run_command):
            with self.assertRaisesRegex(RuntimeError, "body failed"):
                with self.context(writable=True):
                    raise RuntimeError("body failed")
        self.assertNotIn("--read-only", self.calls[0])
        self.assertIn("rw,uid=1001,gid=1002,umask=077,allow_other,default_permissions", self.calls[1])
        self.assertEqual(self.calls[-2][2], "umount")
        self.assertIn("--detach", self.calls[-1])

    def test_mount_failure_still_detaches_loop(self):
        self.failed_command = "mount.exfat-fuse"
        with mock.patch.object(dv.sys, "platform", "linux"), \
                mock.patch.object(dv.subprocess, "run", side_effect=self.run_command):
            with self.assertRaises(subprocess.CalledProcessError):
                with self.context():
                    self.fail("must not enter body")
        self.assertIn("--detach", self.calls[-1])
        self.assertFalse(any("umount" in command for command in self.calls))

    def test_unmount_failure_still_attempts_detach(self):
        self.failed_command = "umount"
        with mock.patch.object(dv.sys, "platform", "linux"), \
                mock.patch.object(dv.subprocess, "run", side_effect=self.run_command):
            with self.assertRaises(subprocess.CalledProcessError):
                with self.context():
                    pass
        self.assertIn("--detach", self.calls[-1])

    def test_invalid_image_never_runs_sudo(self):
        put(self.image, SECTOR + 16, b"\0" * 4)
        with mock.patch.object(dv.sys, "platform", "linux"), mock.patch.object(dv.subprocess, "run") as run:
            with self.assertRaises(ValueError):
                with self.context():
                    self.fail("must not mount invalid image")
            run.assert_not_called()


class PayloadSeedTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.archives = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.archives.cleanup)
        cls.archive = make_archive(Path(cls.archives.name) / "old.tar.gz")

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.image = self.root / "disk.img"

    def test_seed_replaces_payload_but_preserves_metadata_and_data(self):
        for style in ("mbr", "gpt"):
            with self.subTest(style=style):
                make_image(self.image, style)
                before = dv.inspect_image(self.image, style)
                tail = b"T" * (8 * SECTOR)
                put(self.image, 2040 * SECTOR, tail)
                put(self.image, (EFI_START - 1) * SECTOR, b"preserve data" * 20)
                data_before = take(self.image, DATA_START * SECTOR, DATA_LENGTH * SECTOR)
                tables_before = (take(self.image, SECTOR, 33 * SECTOR),
                                 take(self.image, (TOTAL - 33) * SECTOR, 33 * SECTOR))
                result = dv.seed_payload(self.image, self.archive, style)
                self.assertEqual(result["version"], "1.1.15")
                self.assertEqual(result["snapshot"], before)
                self.assertEqual(data_before, take(self.image, DATA_START * SECTOR, DATA_LENGTH * SECTOR))
                self.assertEqual(take(self.image, 0, 32), b"B" * 32)
                self.assertEqual(take(self.image, EFI_START * SECTOR, 128), b"E" * 128)
                self.assertEqual(take(self.image, (EFI_START + EFI_LENGTH) * SECTOR - 128, 128), b"E" * 128)
                self.assertEqual(result["payload_sha256"]["vtoyefi"], hashlib.sha256(b"E" * (EFI_LENGTH * SECTOR)).hexdigest())
                if style == "gpt":
                    self.assertEqual(take(self.image, 92, 1), b"\x22")
                    self.assertEqual(take(self.image, 17908, 1), b"\x23")
                    self.assertEqual(take(self.image, 34 * SECTOR, 32), b"C" * 32)
                    self.assertEqual(take(self.image, 2040 * SECTOR, len(tail)), tail)
                    self.assertEqual(tables_before, (take(self.image, SECTOR, 33 * SECTOR),
                                                     take(self.image, (TOTAL - 33) * SECTOR, 33 * SECTOR)))
                else:
                    self.assertEqual(take(self.image, SECTOR, 32), b"C" * 32)
                    self.assertEqual(take(self.image, 2040 * SECTOR, len(tail)), b"C" * len(tail))

    def test_seed_rejects_malformed_archives_before_any_disk_write(self):
        cases = ({"missing": True}, {"duplicate": True}, {"symlink": True},
                 {"core_length": 2047 * SECTOR - 1}, {"core_length": 2047 * SECTOR + 1},
                 {"efi_length": EFI_LENGTH * SECTOR - 1}, {"efi_length": EFI_LENGTH * SECTOR + 1},
                 {"bad_xz": True}, {"prefix": "../"}, {"prefix": "/"})
        for options in cases:
            with self.subTest(options=options):
                make_image(self.image)
                invalid = make_archive(self.root / "invalid.tar.gz", **options)
                original = dv._open_image

                # Assert write absence in a finally block, including exceptions.
                writes = []

                @dv.contextmanager
                def no_writes(*args, **kwargs):
                    with original(*args, **kwargs) as (disk, size):
                        wrapper = mock.Mock(wraps=disk)
                        try:
                            yield wrapper, size
                        finally:
                            writes.extend(wrapper.write.call_args_list)

                with mock.patch.object(dv, "_open_image", no_writes):
                    with self.assertRaises((ValueError, lzma.LZMAError, EOFError)):
                        dv.seed_payload(self.image, invalid, "gpt")
                self.assertEqual(writes, [])
                self.assertEqual(dv.inspect_image(self.image, "gpt")["identity"]["ventoy_uuid"], bytes(range(16)).hex())

    def test_seed_accepts_archive_without_dot_prefix(self):
        make_image(self.image)
        archive = make_archive(self.root / "plain.tar.gz", prefix="")
        self.assertEqual(dv.seed_payload(self.image, archive, "gpt")["version"], "1.1.15")

    def test_seed_refuses_damaged_baseline(self):
        make_image(self.image)
        dv.damage_gpt_header(self.image, "backup")
        with mock.patch.object(dv.tarfile, "open") as archive_open:
            with self.assertRaisesRegex(ValueError, "backup GPT header CRC"):
                dv.seed_payload(self.image, self.archive, "gpt")
            archive_open.assert_not_called()


if __name__ == "__main__":
    unittest.main()
