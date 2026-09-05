"""Independent checks for detached Ventoy CI images, using only the stdlib.

All LBAs use 512-byte sectors and partition ends are inclusive. Inspection is
strict: a damaged GPT copy raises ValueError even when the other copy is valid.
Snapshots contain layout and identities, not file timestamps or mutable exFAT
usage fields. Reads cover metadata only; the disk is never hashed in full.

The caller must detach QEMU's block backend before inspecting, mounting, or
modifying its image. These helpers cannot establish QEMU ownership themselves.
"""

from contextlib import ExitStack, contextmanager
import hashlib
import lzma
import os
from pathlib import Path, PurePosixPath
import re
import stat
import struct
import subprocess
import sys
import tarfile
import tempfile
import uuid
import zlib


SECTOR_SIZE = 512
_BASIC_DATA_GUID = "ebd0a0a2-b9e5-4433-87c0-68b6b72699c7"
_ROOT_SCAN_LIMIT = 64 * 1024 * 1024


def _require(condition, message):
    if not condition:
        raise ValueError(message)


@contextmanager
def _open_image(path, writable=False):
    path = Path(path).absolute()
    _require(stat.S_ISREG(path.lstat().st_mode), "Image must be a regular file, not a symlink or device")
    flags = os.O_RDWR if writable else os.O_RDONLY
    flags |= getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_NONBLOCK", 0) | getattr(os, "O_BINARY", 0)
    fd = os.open(path, flags)
    try:
        info = os.fstat(fd)
        _require(stat.S_ISREG(info.st_mode), "Image must be a regular file")
        _require(info.st_size >= 3 * SECTOR_SIZE and info.st_size % SECTOR_SIZE == 0,
                 "Image size must have valid 512-byte aligned geometry")
        with os.fdopen(fd, "r+b" if writable else "rb", closefd=False) as disk:
            yield disk, info.st_size
    finally:
        os.close(fd)


def _read(disk, offset, length):
    disk.seek(offset)
    data = disk.read(length)
    _require(len(data) == length, f"Truncated image metadata at byte {offset}")
    return data


def _mbr_entries(mbr):
    entries = []
    for index in range(4):
        entry = mbr[446 + 16 * index:462 + 16 * index]
        start, count = struct.unpack_from("<II", entry, 8)
        if not any(entry):
            continue
        _require(entry[0] in (0, 0x80) and entry[4] != 0 and start > 0 and count > 0,
                 f"Invalid MBR partition {index + 1}")
        entries.append({"index": index + 1, "type": entry[4], "active": entry[0],
                        "start_lba": start, "end_lba": start + count - 1,
                        "sector_count": count, "entry_hex": entry.hex()})
    return entries


def _gpt_copy(disk, sectors, which):
    lba = 1 if which == "primary" else sectors - 1
    other = sectors - 1 if which == "primary" else 1
    table_lba = 2 if which == "primary" else sectors - 33
    header = _read(disk, lba * SECTOR_SIZE, SECTOR_SIZE)
    _require(header[:8] == b"EFI PART", f"{which} GPT signature missing")
    revision, header_size, header_crc, reserved = struct.unpack_from("<IIII", header, 8)
    _require(revision == 0x10000 and 92 <= header_size <= SECTOR_SIZE and reserved == 0,
             f"{which} GPT header format invalid")
    checked = bytearray(header[:header_size])
    checked[16:20] = b"\0" * 4
    computed_header_crc = zlib.crc32(checked)
    _require(header_crc == computed_header_crc, f"{which} GPT header CRC mismatch")
    current, backup, first, last = struct.unpack_from("<QQQQ", header, 24)
    entries_lba, count, entry_size, table_crc = struct.unpack_from("<QIII", header, 72)
    _require((current, backup, first, last, entries_lba, count, entry_size) ==
             (lba, other, 34, sectors - 34, table_lba, 128, 128),
             f"{which} GPT geometry invalid for a Ventoy image")
    _require(first <= last, f"{which} GPT usable range is empty")
    disk_guid = str(uuid.UUID(bytes_le=header[56:72]))
    _require(any(header[56:72]), f"{which} GPT disk GUID is zero")
    table = _read(disk, table_lba * SECTOR_SIZE, count * entry_size)
    computed_table_crc = zlib.crc32(table)
    _require(table_crc == computed_table_crc, f"{which} GPT table CRC mismatch")
    return ({"header_lba": current, "backup_lba": backup,
             "first_usable_lba": first, "last_usable_lba": last,
             "disk_guid": disk_guid, "header_size": header_size,
             "table_lba": entries_lba, "entry_count": count, "entry_size": entry_size,
             "header_crc32": header_crc, "computed_header_crc32": computed_header_crc,
             "table_crc32": table_crc, "computed_table_crc32": computed_table_crc,
             "header_crc_valid": True, "table_crc_valid": True}, table)


def _gpt_partitions(table):
    partitions = []
    guids = set()
    for index in range(128):
        entry = table[index * 128:(index + 1) * 128]
        if not any(entry[:16]):
            _require(not any(entry), f"Unused GPT entry {index + 1} contains metadata")
            continue
        identity = str(uuid.UUID(bytes_le=entry[16:32]))
        _require(any(entry[16:32]) and identity not in guids, "Missing or duplicate GPT partition GUID")
        guids.add(identity)
        start, end, attributes = struct.unpack_from("<QQQ", entry, 32)
        partitions.append({"index": index + 1,
                           "type_guid": str(uuid.UUID(bytes_le=entry[:16])),
                           "unique_guid": identity, "start_lba": start, "end_lba": end,
                           "sector_count": end - start + 1, "attributes": attributes,
                           "name": entry[56:128].decode("utf-16le").rstrip("\0")})
    return partitions


def _boot_checksum(region):
    checksum = 0
    for index, value in enumerate(region[:11 * SECTOR_SIZE]):
        if index not in (106, 107, 112):
            checksum = (((checksum >> 1) | ((checksum & 1) << 31)) + value) & 0xFFFFFFFF
    return checksum


def _exfat(disk, partition):
    base = partition["start_lba"] * SECTOR_SIZE
    _require(partition["sector_count"] >= 24, "exFAT partition is too small")
    main = _read(disk, base, 12 * SECTOR_SIZE)
    backup = _read(disk, base + 12 * SECTOR_SIZE, 12 * SECTOR_SIZE)
    for name, region in (("main", main), ("backup", backup)):
        _require(region[3:11] == b"EXFAT   " and region[510:512] == b"\x55\xaa",
                 f"{name} exFAT boot signature invalid")
        checksum = _boot_checksum(region)
        _require(region[11 * SECTOR_SIZE:] == struct.pack("<I", checksum) * 128,
                 f"{name} exFAT boot checksum mismatch")
    # VolumeFlags and PercentInUse may differ after a normal writable mount.
    normalized = []
    for region in (main, backup):
        clean = bytearray(region[:11 * SECTOR_SIZE])
        for index in (106, 107, 112):
            clean[index] = 0
        normalized.append(clean)
    _require(normalized[0] == normalized[1], "exFAT boot copies disagree")
    offset, length = struct.unpack_from("<QQ", main, 64)
    fat_offset, fat_length, heap, count, root, serial = struct.unpack_from("<IIIIII", main, 80)
    revision, flags = struct.unpack_from("<HH", main, 104)
    sector_shift, cluster_shift, fats = main[108:111]
    _require(sector_shift == 9 and cluster_shift <= 16 and fats == 1 and revision == 0x100,
             "Unsupported exFAT sector/cluster/FAT geometry")
    _require(flags & 1 == 0, "exFAT active FAT is invalid for one FAT")
    sectors_per_cluster = 1 << cluster_shift
    cluster_bytes = sectors_per_cluster * SECTOR_SIZE
    _require(offset == partition["start_lba"] and length == partition["sector_count"],
             "exFAT volume geometry differs from its partition")
    _require(0 < count <= 0xFFFFFFF5 and 2 <= root <= count + 1,
             "exFAT cluster count or root cluster invalid")
    _require(fat_offset >= 24 and fat_length * SECTOR_SIZE >= (count + 2) * 4
             and fat_offset + fat_length <= heap
             and heap + count * sectors_per_cluster <= length,
             "exFAT FAT/cluster heap lies outside its volume or overlaps")
    label = None
    visited = set()
    cluster = root
    done = False
    while not done:
        _require(2 <= cluster <= count + 1 and cluster not in visited,
                 "exFAT root directory has an invalid or cyclic FAT chain")
        visited.add(cluster)
        _require(len(visited) * cluster_bytes <= _ROOT_SCAN_LIMIT,
                 "exFAT root directory exceeds the bounded metadata scan")
        # Scan in sectors so even a 32 MiB cluster never becomes a large read.
        cluster_offset = base + heap * SECTOR_SIZE + (cluster - 2) * cluster_bytes
        for sector in range(sectors_per_cluster):
            data = _read(disk, cluster_offset + sector * SECTOR_SIZE, SECTOR_SIZE)
            for index in range(0, SECTOR_SIZE, 32):
                entry = data[index:index + 32]
                if entry[0] == 0:
                    done = True
                    break
                if entry[0] == 0x83:
                    _require(label is None and entry[1] <= 11, "Invalid or duplicate exFAT label entry")
                    label = entry[2:2 + entry[1] * 2].decode("utf-16le")
            if done:
                break
        next_cluster, = struct.unpack("<I", _read(disk, base + fat_offset * SECTOR_SIZE + cluster * 4, 4))
        _require(next_cluster >= 0xFFFFFFF8 or 2 <= next_cluster <= count + 1,
                 "exFAT root directory references a free, bad, or invalid cluster")
        if done or next_cluster >= 0xFFFFFFF8:
            break
        cluster = next_cluster
    return {"label": label if label is not None else "", "volume_serial": serial,
            "partition_offset_lba": offset, "volume_length_sectors": length,
            "sector_size": SECTOR_SIZE, "cluster_size": cluster_bytes,
            "fat_offset_sectors": fat_offset, "fat_length_sectors": fat_length,
            "cluster_heap_offset_sectors": heap, "cluster_count": count,
            "root_directory_cluster": root, "boot_checksum": _boot_checksum(main),
            "main_boot_checksum_valid": True, "backup_boot_checksum_valid": True}


def _inspect(disk, size, expected_style):
    _require(expected_style in ("mbr", "gpt"), "expected_style must be 'mbr' or 'gpt'")
    sectors = size // SECTOR_SIZE
    mbr = _read(disk, 0, SECTOR_SIZE)
    _require(mbr[510:512] == b"\x55\xaa", "MBR boot signature missing")
    entries = _mbr_entries(mbr)
    protective = bool(entries and entries[0]["type"] == 0xEE)
    _require(protective == (expected_style == "gpt"), "Partition style differs from expected_style")
    gpt = None
    if expected_style == "gpt":
        _require(len(entries) == 1 and entries[0]["index"] == 1 and entries[0]["active"] == 0
                 and entries[0]["start_lba"] == 1
                 and entries[0]["sector_count"] == min(sectors - 1, 0xFFFFFFFF),
                 "Invalid protective MBR")
        primary, primary_table = _gpt_copy(disk, sectors, "primary")
        backup, backup_table = _gpt_copy(disk, sectors, "backup")
        _require(primary["disk_guid"] == backup["disk_guid"], "GPT disk GUIDs disagree")
        _require(primary_table == backup_table, "GPT tables disagree")
        partitions = _gpt_partitions(primary_table)
        gpt = {"primary": primary, "backup": backup}
        first_usable, last_usable = 34, sectors - 34
    else:
        _require(all(p["type"] not in (0x05, 0x0F, 0x85, 0xEE) for p in entries),
                 "Extended or protective partitions are unsupported in a Ventoy MBR")
        partitions = entries
        first_usable, last_usable = 1, sectors - 1
    _require(len(partitions) >= 2 and [p["index"] for p in partitions[:2]] == [1, 2],
             "Missing Ventoy data or VTOYEFI partition")
    for partition in partitions:
        _require(first_usable <= partition["start_lba"] <= partition["end_lba"] <= last_usable,
                 f"Partition {partition['index']} lies outside the usable disk")
    ordered = sorted(partitions, key=lambda p: p["start_lba"])
    _require(all(a["end_lba"] < b["start_lba"] for a, b in zip(ordered, ordered[1:])),
             "Partitions overlap")
    data, efi = partitions[:2]
    _require(data["start_lba"] == 2048 and efi["start_lba"] == data["end_lba"] + 1
             and efi["sector_count"] == 65536, "Invalid Ventoy partition layout")
    if gpt:
        _require(data["type_guid"] == efi["type_guid"] == _BASIC_DATA_GUID
                 and efi["name"] == "VTOYEFI" and efi["attributes"] == 1 << 63,
                 "Invalid Ventoy GPT partition metadata")
    else:
        _require(data["type"] == 0x07 and data["active"] == 0x80
                 and efi["type"] == 0xEF and efi["active"] == 0,
                 "Invalid Ventoy MBR partition metadata")
    return {"style": expected_style, "size_bytes": size, "sector_size": SECTOR_SIZE,
            "total_sectors": sectors, "partitions": partitions,
            "reserved_bytes": (last_usable - efi["end_lba"]) * SECTOR_SIZE,
            "identity": {"ventoy_uuid": mbr[384:400].hex(),
                         "mbr_disk_signature": mbr[440:444].hex(),
                         "gpt_disk_guid": gpt["primary"]["disk_guid"] if gpt else None},
            "mbr": {"boot_signature_valid": True, "protective": protective, "entries": entries},
            "gpt": gpt, "exfat": _exfat(disk, data)}


def inspect_image(path, expected_style: str) -> dict:
    """Validate a detached regular-file Ventoy image; return JSON-safe metadata.

    Supports the Ventoy 512-byte sector layout, single-FAT exFAT, and 128x128
    GPT tables. Raises ValueError for corrupt or unsupported image structures.
    The VTOYEFI filesystem and bootloader payload bytes are not inspected.
    """
    with _open_image(path) as (disk, size):
        return _inspect(disk, size, expected_style)


def image_snapshot(path, expected_style: str) -> dict:
    """Alias for strict inspection, suitable for equality before/after repair."""
    return inspect_image(path, expected_style)


@contextmanager
def mounted_data_partition(path, writable=False):
    """Yield a Path for partition 1, only after the caller detached QEMU.

    Linux requires sudo -n access to losetup, mount.exfat-fuse and umount.
    The loop device is read-only unless writable=True. Cleanup always
    attempts unmount and detach, including when the context body raises.
    """
    _require(sys.platform.startswith("linux"), "Image mounting requires Linux")
    with _open_image(path, writable) as (disk, size):
        mbr = _read(disk, 0, SECTOR_SIZE)
        style = "gpt" if mbr[450] == 0xEE else "mbr"
        snapshot = _inspect(disk, size, style)
        data = snapshot["partitions"][0]
        with ExitStack() as cleanup:
            root = Path(tempfile.mkdtemp(prefix="vendroid-data-"))
            # Never recursively delete a directory that may still be mounted.
            cleanup.callback(os.rmdir, root)
            command = ["sudo", "-n", "losetup", "--find", "--show",
                       "--offset", str(data["start_lba"] * SECTOR_SIZE),
                       "--sizelimit", str(data["sector_count"] * SECTOR_SIZE)]
            if not writable:
                command.append("--read-only")
            # Hold this exact regular file open throughout the mount lifetime.
            command += ["--", f"/proc/{os.getpid()}/fd/{disk.fileno()}"]
            loop = subprocess.run(command, check=True, capture_output=True, text=True).stdout.strip()
            _require(re.fullmatch(r"/dev/loop[0-9]+", loop) is not None, "Unexpected losetup device output")
            cleanup.callback(subprocess.run, ["sudo", "-n", "losetup", "--detach", loop], check=True)
            options = "rw" if writable else "ro"
            options += f",uid={os.getuid()},gid={os.getgid()},umask=077,allow_other,default_permissions"
            subprocess.run(["sudo", "-n", "mount.exfat-fuse", "-o", options,
                            loop, str(root)], check=True)
            cleanup.callback(subprocess.run, ["sudo", "-n", "umount", str(root)], check=True)
            yield root


def _preservation_path(root, relative):
    relative_path = PurePosixPath(relative)
    _require(bool(relative) and not relative_path.is_absolute()
             and ".." not in relative_path.parts and "\\" not in relative and ":" not in relative,
             "Preservation paths must be relative and remain inside mount_root")
    candidate = root.joinpath(*relative_path.parts)
    _require(candidate.resolve().is_relative_to(root) and candidate != root,
             "Preservation path escapes mount_root")
    current = root
    for part in relative_path.parts:
        current = current / part
        _require(not current.is_symlink(), "Preservation paths cannot contain symlinks")
    return candidate


def _file_sha256(path):
    _require(stat.S_ISREG(path.lstat().st_mode), f"Preservation file is not regular: {path}")
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def create_preservation_files(mount_root) -> dict:
    """Create deterministic test files without overwriting; return relative SHA256s."""
    root = Path(mount_root).resolve(strict=True)
    _require(root.is_dir(), "mount_root must be a directory")
    payloads = {
        "vendroid-preservation/readme.txt": b"Vendroid preservation fixture\nKeep this across repair and update.\n",
        "vendroid-preservation/pattern.bin": bytes(range(256)) * 4097,
        "vendroid-preservation/nested/deeper/empty.dat": b"",
        "vendroid-preservation/nested/Unicode-\u00e9-\u4e2d\u6587/\u0645\u0644\u0641.txt": "Ventoy \u2014 \u4fdd\u7559 \u0627\u0644\u0628\u064a\u0627\u0646\u0627\u062a\n".encode("utf-8"),
    }
    paths = {name: _preservation_path(root, name) for name in payloads}
    _require(all(not path.exists() for path in paths.values()), "Preservation fixture already exists")
    for name, path in paths.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("xb") as output:
            output.write(payloads[name])
            output.flush()
            os.fsync(output.fileno())
    return {name: _file_sha256(path) for name, path in paths.items()}


def verify_preservation_files(mount_root, expected_hashes) -> None:
    """Raise on missing files, unsafe paths or SHA256 mismatches."""
    root = Path(mount_root).resolve(strict=True)
    _require(root.is_dir() and bool(expected_hashes), "A directory and nonempty manifest are required")
    for relative, expected in expected_hashes.items():
        path = _preservation_path(root, relative)
        actual = _file_sha256(path)
        _require(actual == expected, f"Preservation SHA256 mismatch: {relative}")


def damage_gpt_header(path, which) -> dict:
    """Flip one bit in the selected header's CRC field, fsync, return baseline.

    which is 'primary' or 'backup'. A fully valid baseline is mandatory; this
    intentionally prevents damaging the remaining copy of an unhealthy image.
    """
    _require(which in ("primary", "backup"), "which must be 'primary' or 'backup'")
    with _open_image(path, writable=True) as (disk, size):
        baseline = _inspect(disk, size, "gpt")
        offset = baseline["gpt"][which]["header_lba"] * SECTOR_SIZE + 16
        original = _read(disk, offset, 1)
        disk.seek(offset)
        disk.write(bytes([original[0] ^ 1]))
        disk.flush()
        os.fsync(disk.fileno())
        return baseline


def _stage_payload(bundle, member, target, expected_size):
    """Decode before touching the disk; reject short and oversized payloads."""
    with bundle.extractfile(member) as compressed:
        with lzma.LZMAFile(compressed) as source:
            copied = 0
            while True:
                block = source.read(min(1024 * 1024, expected_size + 1 - copied))
                if not block:
                    break
                copied += len(block)
                _require(copied <= expected_size, f"Oversized payload: {member.name}")
                target.write(block)
    _require(copied == expected_size, f"Truncated payload: {member.name}")
    target.seek(0)


def _hash_span(source, offset, length):
    digest = hashlib.sha256()
    source.seek(offset)
    while length:
        block = source.read(min(length, 1024 * 1024))
        _require(bool(block), "Truncated payload during readback")
        digest.update(block)
        length -= len(block)
    return digest.hexdigest()


def _copy_span(source, disk, offset, length):
    source.seek(0)
    disk.seek(offset)
    remaining = length
    while remaining:
        block = source.read(min(remaining, 1024 * 1024))
        _require(bool(block), "Truncated staged payload")
        disk.write(block)
        remaining -= len(block)


def seed_payload(path, archive, expected_style: str) -> dict:
    """Seed a detached installed image from a caller-verified official archive.

    The caller pins and SHA256-verifies the Linux tar.gz; this helper performs
    no network access. All three members must share one ventoy-VERSION root.
    Decode and size-check both XZ payloads before writing. Replace boot code,
    core and VTOYEFI, preserving identities, partition tables, the GPT core tail
    at sectors 2040..2047, and all data-partition bytes. Apply the installer's
    GPT patches at MBR byte 92 and disk byte 17908.

    Return {version, snapshot, payload_sha256}. Payload SHA256s are verified by
    reading back only the written regions. snapshot must equal the baseline.
    A write failure can leave a partial payload; use disposable CI images.
    """
    with _open_image(path, writable=True) as (disk, size), ExitStack() as cleanup:
        before = _inspect(disk, size, expected_style)
        _require(Path(archive).is_file(), "Payload archive must be a regular file")
        bundle = cleanup.enter_context(tarfile.open(archive, "r:gz"))
        required = ("boot/boot.img", "boot/core.img.xz", "ventoy/ventoy.disk.img.xz")
        found = {}
        roots = set()
        for member in bundle:
            for suffix in required:
                if member.name.endswith("/" + suffix):
                    root = member.name[:-(len(suffix) + 1)]
                    _require(re.fullmatch(r"ventoy-[0-9]+\.[0-9]+\.[0-9]+", root) is not None,
                             "Unexpected Ventoy archive root")
                    _require(member.isfile() and suffix not in found,
                             f"Duplicate or non-regular payload: {suffix}")
                    _require(0 < member.size <= 64 * 1024 * 1024, "Invalid archived payload size")
                    roots.add(root)
                    found[suffix] = member
        _require(set(found) == set(required) and len(roots) == 1, "Missing or mixed Ventoy payload members")
        _require(found["boot/boot.img"].size == SECTOR_SIZE, "boot.img must be exactly 512 bytes")
        with bundle.extractfile(found["boot/boot.img"]) as source:
            boot = bytearray(source.read(SECTOR_SIZE))
        _require(len(boot) == SECTOR_SIZE and boot[510:512] == b"\x55\xaa", "Invalid boot.img signature")
        core = cleanup.enter_context(tempfile.TemporaryFile())
        efi = cleanup.enter_context(tempfile.TemporaryFile())
        _stage_payload(bundle, found["boot/core.img.xz"], core, 2047 * SECTOR_SIZE)
        efi_length = before["partitions"][1]["sector_count"] * SECTOR_SIZE
        _stage_payload(bundle, found["ventoy/ventoy.disk.img.xz"], efi, efi_length)
        mbr = _read(disk, 0, SECTOR_SIZE)
        boot[384:400] = mbr[384:400]
        boot[440:512] = mbr[440:512]
        if expected_style == "gpt":
            core_lba, core_sectors = 34, 2014
            boot[92] = 0x22
            core.seek(17908 - core_lba * SECTOR_SIZE)
            core.write(b"\x23")
            core.seek((2040 - core_lba) * SECTOR_SIZE)
            core.write(_read(disk, 2040 * SECTOR_SIZE, 8 * SECTOR_SIZE))
        else:
            core_lba, core_sectors = 1, 2047
        core_length = core_sectors * SECTOR_SIZE
        efi_offset = before["partitions"][1]["start_lba"] * SECTOR_SIZE
        expected_hashes = {"boot": hashlib.sha256(boot).hexdigest(),
                           "core": _hash_span(core, 0, core_length),
                           "vtoyefi": _hash_span(efi, 0, efi_length)}
        disk.seek(0)
        disk.write(boot)
        _copy_span(core, disk, core_lba * SECTOR_SIZE, core_length)
        _copy_span(efi, disk, efi_offset, efi_length)
        disk.flush()
        os.fsync(disk.fileno())
        actual_hashes = {"boot": _hash_span(disk, 0, SECTOR_SIZE),
                         "core": _hash_span(disk, core_lba * SECTOR_SIZE, core_length),
                         "vtoyefi": _hash_span(disk, efi_offset, efi_length)}
        _require(actual_hashes == expected_hashes, "Seeded payload readback mismatch")
        after = _inspect(disk, size, expected_style)
        _require(after == before, "Seeding changed disk identity or filesystem/partition metadata")
        return {"version": next(iter(roots))[len("ventoy-"):], "snapshot": after,
                "payload_sha256": actual_hashes}
