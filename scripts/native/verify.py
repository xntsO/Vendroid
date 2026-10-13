"""Inspect actual APK/AAB native ELF segments and their packaging, without SDK tools."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import sys
import zipfile


PAGE_SIZE = 16_384
ABI_MACHINES = {"arm64-v8a": (64, 183), "armeabi-v7a": (32, 40), "x86": (32, 3), "x86_64": (64, 62)}
USB_LIBRARIES = {"liberrno-lib.so", "libusb-lib.so", "liblibusb.so", "liblibusbcom.so"}
MAX_LIBRARY_BYTES = 64 * 1024 * 1024


class VerificationError(ValueError):
    pass


def sha256_file(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def inspect_elf(data, abi):
    """Parse the ELF boundary once and reject malformed or incompatible load segments."""
    if abi not in ABI_MACHINES:
        raise VerificationError(f"Unsupported Android ABI {abi}")
    bits, machine = ABI_MACHINES[abi]
    header_size = 64 if bits == 64 else 52
    if len(data) < header_size or data[:4] != b"\x7fELF":
        raise VerificationError("Missing or truncated ELF header")
    if data[4:7] != bytes((2 if bits == 64 else 1, 1, 1)):
        raise VerificationError(f"ELF class, byte order, or version does not match {abi}")
    file_type, actual_machine, version = struct.unpack_from("<HHI", data, 16)
    if file_type != 3 or actual_machine != machine or version != 1:
        raise VerificationError(f"ELF is not an Android shared library for {abi}")
    phoff = struct.unpack_from("<Q" if bits == 64 else "<I", data, 32 if bits == 64 else 28)[0]
    ehsize, phsize, phnum = struct.unpack_from("<HHH", data, 52 if bits == 64 else 40)
    expected_phsize = 56 if bits == 64 else 32
    if ehsize != header_size or phsize != expected_phsize or not 0 < phnum <= 256:
        raise VerificationError("Invalid ELF header or program-header table dimensions")
    if phoff < header_size or phoff + phsize * phnum > len(data):
        raise VerificationError("Truncated ELF program-header table")
    loads = []
    for index in range(phnum):
        values = struct.unpack_from("<IIQQQQQQ" if bits == 64 else "<IIIIIIII", data, phoff + index * phsize)
        if values[0] != 1:
            continue
        if bits == 64:
            _, _, offset, address, _, file_size, memory_size, alignment = values
        else:
            _, offset, address, _, file_size, memory_size, _, alignment = values
        if offset + file_size > len(data) or memory_size < file_size:
            raise VerificationError("ELF load segment exceeds the library or its memory size")
        required_alignment = PAGE_SIZE if bits == 64 else 4096
        if alignment < required_alignment or alignment & (alignment - 1):
            raise VerificationError(f"ELF load alignment {alignment} requires at least {required_alignment} bytes for {abi}")
        if address % alignment != offset % alignment:
            raise VerificationError("ELF load address and file offset have incompatible alignment")
        loads.append({"offset": offset, "virtual_address": address, "alignment": alignment})
    if not loads:
        raise VerificationError("ELF has no loadable segments")
    return loads


def protobuf_fields(data):
    """Read bounded protobuf fields, including unknown fields, without a generated runtime."""
    cursor = 0

    def varint():
        nonlocal cursor
        value = 0
        for shift in range(0, 70, 7):
            if cursor >= len(data):
                raise VerificationError("Truncated BundleConfig protobuf varint")
            byte = data[cursor]
            cursor += 1
            if shift == 63 and byte > 1:
                raise VerificationError("BundleConfig protobuf varint exceeds 64 bits")
            value |= (byte & 127) << shift
            if byte < 128:
                return value
        raise VerificationError("BundleConfig protobuf varint is too long")

    fields = []
    while cursor < len(data):
        tag = varint()
        field, wire = tag >> 3, tag & 7
        if field == 0:
            raise VerificationError("BundleConfig protobuf has field zero")
        if wire == 0:
            value = varint()
        elif wire in (1, 2, 5):
            length = varint() if wire == 2 else (8 if wire == 1 else 4)
            if cursor + length > len(data):
                raise VerificationError("Truncated BundleConfig protobuf field")
            value = data[cursor:cursor + length]
            cursor += length
        else:
            raise VerificationError(f"Unsupported BundleConfig protobuf wire type {wire}")
        fields.append((field, wire, value))
    return fields


def one_field(data, number, wire):
    values = [value for field, actual_wire, value in protobuf_fields(data) if field == number and actual_wire == wire]
    if len(values) != 1:
        raise VerificationError(f"BundleConfig requires exactly one field {number} of wire type {wire}")
    return values[0]


def bundle_alignment(config):
    # google/bundletool src/main/proto/config.proto:
    # BundleConfig.optimizations=2 -> uncompress_native_libraries=2 -> alignment=2.
    native = one_field(one_field(config, 2, 2), 2, 2)
    if one_field(native, 1, 0) != 1:
        raise VerificationError("BundleConfig does not enable uncompressed native library optimization")
    alignment = one_field(native, 2, 0)
    if alignment not in (2, 3):
        raise VerificationError("BundleConfig does not request PAGE_ALIGNMENT_16K or PAGE_ALIGNMENT_64K")
    return "PAGE_ALIGNMENT_16K" if alignment == 2 else "PAGE_ALIGNMENT_64K"


def zip_data_offset(archive, info):
    archive.fp.seek(info.header_offset)
    header = archive.fp.read(30)
    if len(header) != 30 or header[:4] != b"PK\x03\x04":
        raise VerificationError("Invalid ZIP local file header")
    name_bytes, extra_bytes = struct.unpack_from("<HH", header, 26)
    return info.header_offset + 30 + name_bytes + extra_bytes


def inspect_artifact(path):
    path = Path(path)
    kind = path.suffix.lower().lstrip(".")
    if kind not in ("apk", "aab"):
        raise VerificationError("Expected an .apk or .aab artifact")
    native_pattern = re.compile(r"^lib/([^/]+)/([^/]+\.so)$" if kind == "apk" else r"^[^/]+/lib/([^/]+)/([^/]+\.so)$")
    libraries = []
    found = {abi: set() for abi in ABI_MACHINES}
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise VerificationError("Artifact contains duplicate ZIP entry names")
        alignment = None
        if kind == "aab":
            config = archive.getinfo("BundleConfig.pb")
            if config.file_size > 1024 * 1024:
                raise VerificationError("BundleConfig exceeds its size limit")
            alignment = bundle_alignment(archive.read(config))
        for info in archive.infolist():
            match = native_pattern.fullmatch(info.filename)
            if not match:
                continue
            abi, name = match.groups()
            if info.file_size > MAX_LIBRARY_BYTES:
                raise VerificationError(f"Native library exceeds its size limit: {info.filename}")
            try:
                data = archive.read(info)
                loads = inspect_elf(data, abi)
                if info.compress_type not in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED):
                    raise VerificationError("Unsupported native library ZIP compression")
                zip_aligned = None
                if kind == "apk" and info.compress_type == zipfile.ZIP_STORED:
                    zip_alignment = PAGE_SIZE if ABI_MACHINES[abi][0] == 64 else 4096
                    zip_aligned = zip_data_offset(archive, info) % zip_alignment == 0
                    if not zip_aligned:
                        raise VerificationError(f"Uncompressed native library ZIP entry is not {zip_alignment} byte aligned")
            except VerificationError as error:
                raise VerificationError(f"{info.filename}: {error}") from error
            found[abi].add(name)
            libraries.append({"name": info.filename, "abi": abi,
                              "sha256": hashlib.sha256(data).hexdigest(),
                              "load_alignments": [load["alignment"] for load in loads],
                              "required_alignment_bytes": PAGE_SIZE if ABI_MACHINES[abi][0] == 64 else 4096,
                              "zip_aligned": zip_aligned, "compression": info.compress_type})
        for abi, names in found.items():
            missing = USB_LIBRARIES - names
            if missing:
                raise VerificationError(f"Missing rebuilt USB libraries for {abi}: {', '.join(sorted(missing))}")
    return {"schema": 1, "artifact_type": kind, "sha256": sha256_file(path),
            "native_16kb_passed": True, "bundle_page_alignment": alignment, "libraries": libraries}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifact", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        report = inspect_artifact(args.artifact)
    except (VerificationError, OSError, zipfile.BadZipFile, KeyError, struct.error) as error:
        report = {"schema": 1, "native_16kb_passed": False, "error": str(error)}
    rendered = json.dumps(report, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered, encoding="utf-8")
    print(rendered, end="")
    return 0 if report["native_16kb_passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
