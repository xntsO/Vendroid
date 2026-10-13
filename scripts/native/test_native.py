"""Independent malformed binary/archive fixtures for CI's native publishing gate."""

import hashlib
import io
import json
from pathlib import Path
import struct
import subprocess
import sys
import tarfile
import tempfile
import unittest
import zipfile

from prepare import apply_jni_release_mode_patch, extract_source, replace_native_entries
from verify import ABI_MACHINES, USB_LIBRARIES, VerificationError, bundle_alignment, inspect_artifact, inspect_elf


def elf(abi="arm64-v8a", alignment=16384):
    bits, machine = ABI_MACHINES[abi]
    ident = b"\x7fELF" + bytes((2 if bits == 64 else 1, 1, 1)) + bytes(9)
    if bits == 64:
        header = struct.pack("<HHIQQQIHHHHHH", 3, machine, 1, 0, 64, 0, 0, 64, 56, 1, 0, 0, 0)
        segment = struct.pack("<IIQQQQQQ", 1, 5, 0, 0, 0, 120, 120, alignment)
    else:
        header = struct.pack("<HHIIIIIHHHHHH", 3, machine, 1, 0, 52, 0, 0, 52, 32, 1, 0, 0, 0)
        segment = struct.pack("<IIIIIIII", 1, 0, 0, 0, 84, 84, 5, alignment)
    return ident + header + segment


def bundle_config(alignment=2):
    # Independent encoding of the three documented protobuf messages.
    native = bytes((8, 1, 16, alignment))
    optimizations = bytes((18, len(native))) + native
    return bytes((18, len(optimizations))) + optimizations


def artifact(path, *, bad_abi=None, zip_alignment=True, duplicate=False, missing=None, legacy_32bit=False):
    with zipfile.ZipFile(path, "w") as archive:
        if path.suffix == ".aab":
            archive.writestr("BundleConfig.pb", bundle_config())
        for abi, (bits, _) in ABI_MACHINES.items():
            for library in sorted(USB_LIBRARIES):
                if missing == (abi, library):
                    continue
                name = f"lib/{abi}/{library}"
                if path.suffix == ".aab":
                    name = f"base/{name}"
                info = zipfile.ZipInfo(name)
                if path.suffix == ".apk" and zip_alignment:
                    boundary = 4096 if legacy_32bit and bits == 32 else 16384
                    padding = (-archive.fp.tell() - 30 - len(name.encode()) - 4) % boundary
                    info.extra = struct.pack("<HH", 0xD935, padding) + bytes(padding)
                alignment = 4096 if abi == bad_abi or (legacy_32bit and bits == 32) else 16384
                archive.writestr(info, elf(abi, alignment))
                if duplicate and abi == "arm64-v8a" and library == "liblibusb.so":
                    archive.writestr(info, elf(abi))


class ElfFixtures(unittest.TestCase):
    def test_valid_native_load_segments(self):
        for abi in ABI_MACHINES:
            with self.subTest(abi=abi):
                self.assertEqual(inspect_elf(elf(abi), abi)[0]["alignment"], 16384)

    def test_64bit_4kb_prebuilt_is_rejected(self):
        for abi in ("arm64-v8a", "x86_64"):
            with self.subTest(abi=abi), self.assertRaisesRegex(VerificationError, "16384"):
                inspect_elf(elf(abi, 4096), abi)

    def test_32bit_4kb_library_is_compatible(self):
        for abi in ("armeabi-v7a", "x86"):
            self.assertEqual(inspect_elf(elf(abi, 4096), abi)[0]["alignment"], 4096)

    def test_truncated_or_wrong_architecture_library_is_rejected(self):
        for data in (b"not an ELF", elf()[:100], elf("x86_64")):
            with self.subTest(data=data[:16]), self.assertRaises(VerificationError):
                inspect_elf(data, "arm64-v8a")

    def test_load_segment_outside_file_is_rejected(self):
        data = bytearray(elf())
        struct.pack_into("<Q", data, 64 + 32, len(data) + 1)
        with self.assertRaisesRegex(VerificationError, "exceeds"):
            inspect_elf(data, "arm64-v8a")

    def test_mismatched_virtual_address_is_rejected(self):
        data = bytearray(elf())
        struct.pack_into("<Q", data, 64 + 16, 1)
        with self.assertRaisesRegex(VerificationError, "incompatible alignment"):
            inspect_elf(data, "arm64-v8a")


class ArtifactFixtures(unittest.TestCase):
    def test_apk_and_bundle_native_gate_reads_real_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            for suffix in ("apk", "aab"):
                path = Path(directory) / f"fixture.{suffix}"
                artifact(path)
                report = inspect_artifact(path)
                self.assertTrue(report["native_16kb_passed"])
                self.assertEqual(report["sha256"], hashlib.sha256(path.read_bytes()).hexdigest())
                self.assertEqual(len(report["libraries"]), 16)
                if suffix == "aab":
                    self.assertEqual(report["bundle_page_alignment"], "PAGE_ALIGNMENT_16K")

    def test_32bit_4kb_alignment_does_not_block_a_compatible_apk(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "legacy32.apk"
            artifact(path, legacy_32bit=True)
            self.assertTrue(inspect_artifact(path)["native_16kb_passed"])

    def test_bad_64bit_library_in_otherwise_complete_artifact_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            for abi in ("arm64-v8a", "x86_64"):
                path = Path(directory) / f"bad-{abi}.aab"
                artifact(path, bad_abi=abi)
                with self.assertRaisesRegex(VerificationError, abi):
                    inspect_artifact(path)

    def test_uncompressed_64bit_zip_entry_must_be_16kb_aligned(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "unaligned.apk"
            artifact(path, zip_alignment=False)
            with self.assertRaisesRegex(VerificationError, "ZIP entry"):
                inspect_artifact(path)

    def test_stripping_all_or_one_required_library_is_not_a_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "missing.apk"
            artifact(path, missing=("x86_64", "liblibusbcom.so"))
            with self.assertRaisesRegex(VerificationError, "Missing rebuilt USB libraries for x86_64"):
                inspect_artifact(path)

    def test_duplicate_native_entries_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "duplicate.apk"
            artifact(path, duplicate=True)
            with self.assertRaisesRegex(VerificationError, "duplicate"):
                inspect_artifact(path)

    def test_bundle_4kb_or_truncated_config_is_rejected(self):
        for data in (bundle_config(1), bundle_config()[:-1], b"\x12\x80", b"\x00"):
            with self.subTest(data=data), self.assertRaises(VerificationError):
                bundle_alignment(data)

    def test_cli_failure_writes_a_failed_report_and_returns_nonzero(self):
        with tempfile.TemporaryDirectory() as directory:
            path, report = Path(directory) / "bad.apk", Path(directory) / "report.json"
            artifact(path, bad_abi="arm64-v8a")
            result = subprocess.run([sys.executable, str(Path(__file__).with_name("verify.py")),
                                     str(path), "--output", str(report)], capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('"native_16kb_passed": false', report.read_text())


class JniPatchFixtures(unittest.TestCase):
    original = b"""void fixture() {
    (*env)->ReleaseLongArrayElements(env, handle, body, NULL);
    keepPointer(NULL);
    (*env)->ReleaseByteArrayElements(env, data, c_data, NULL);
    keepPointer(NULL);
    (*env)->ReleaseByteArrayElements(env, buffer, c_data, NULL);
}
"""
    corrected = b"""void fixture() {
    (*env)->ReleaseLongArrayElements(env, handle, body, 0);
    keepPointer(NULL);
    (*env)->ReleaseByteArrayElements(env, data, c_data, 0);
    keepPointer(NULL);
    (*env)->ReleaseByteArrayElements(env, buffer, c_data, 0);
}
"""
    patch = b"""--- a/libusbcommunication/src/c/usb.c
+++ b/libusbcommunication/src/c/usb.c
@@ -2 +2 @@
-    (*env)->ReleaseLongArrayElements(env, handle, body, NULL);
+    (*env)->ReleaseLongArrayElements(env, handle, body, 0);
@@ -4 +4 @@
-    (*env)->ReleaseByteArrayElements(env, data, c_data, NULL);
+    (*env)->ReleaseByteArrayElements(env, data, c_data, 0);
@@ -6 +6 @@
-    (*env)->ReleaseByteArrayElements(env, buffer, c_data, NULL);
+    (*env)->ReleaseByteArrayElements(env, buffer, c_data, 0);
"""

    def fixture(self, root):
        path = root / "libusbcommunication/src/c/usb.c"
        path.parent.mkdir(parents=True)
        path.write_bytes(self.original)
        spec = {"file": "jni-release-modes.patch", "source_file": "libusbcommunication/src/c/usb.c",
                "sha256": hashlib.sha256(self.patch).hexdigest(),
                "source_sha256": hashlib.sha256(self.original).hexdigest(),
                "patched_source_sha256": hashlib.sha256(self.corrected).hexdigest()}
        return path, spec

    def test_exact_patch_changes_only_three_integer_modes_and_records_proof(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path, spec = self.fixture(root)
            proof = apply_jni_release_mode_patch(root, spec, self.patch)
            self.assertEqual(path.read_bytes(), self.corrected)
            self.assertEqual(proof["integer_mode_corrections"], 3)
            self.assertEqual(proof["original_source_sha256"], spec["source_sha256"])
            self.assertEqual(proof["patched_source_sha256"], spec["patched_source_sha256"])

    def test_mismatched_source_patch_and_result_are_rejected_without_writes(self):
        for failure in ("source", "checksum", "wrong-mode", "result", "duplicate-call"):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                path, spec = self.fixture(root)
                patch = self.patch
                if failure == "source":
                    path.write_bytes(self.original + b"// unexpected source revision\n")
                elif failure == "checksum":
                    patch += b"// unexpected patch change\n"
                elif failure == "wrong-mode":
                    patch = patch.replace(b", c_data, 0);", b", c_data, 1);", 1)
                    spec["sha256"] = hashlib.sha256(patch).hexdigest()
                elif failure == "result":
                    spec["patched_source_sha256"] = "0" * 64
                else:
                    path.write_bytes(self.original + self.original.splitlines(keepends=True)[1])
                    spec["source_sha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
                before = path.read_bytes()
                with self.assertRaises(ValueError):
                    apply_jni_release_mode_patch(root, spec, patch)
                self.assertEqual(path.read_bytes(), before)

    def test_distributed_patch_matches_checked_in_source_lock(self):
        directory = Path(__file__).parent
        spec = json.loads((directory / "source-lock.json").read_text())["jni_release_mode_patch"]
        self.assertEqual(hashlib.sha256((directory / spec["file"]).read_bytes()).hexdigest(), spec["sha256"])


class SourcePackagingFixtures(unittest.TestCase):
    def test_aar_replacement_preserves_bytecode_resources_and_consumer_rules(self):
        with tempfile.TemporaryDirectory() as directory:
            source, output = Path(directory) / "original.aar", Path(directory) / "rebuilt.aar"
            native = "jni/arm64-v8a/liblibusbcom.so"
            unchanged = {"classes.jar": b"original bytecode", "proguard.txt": b"keep JNI signatures",
                         "res/values/strings.xml": b"original resources"}
            with zipfile.ZipFile(source, "w") as archive:
                for name, data in {native: elf(alignment=4096), **unchanged}.items():
                    archive.writestr(name, data)
            classes = replace_native_entries(source, output, {native: elf()}, {"assets/source/NOTICE": b"license"})
            self.assertEqual(classes, hashlib.sha256(unchanged["classes.jar"]).hexdigest())
            with zipfile.ZipFile(output) as archive:
                for name, data in unchanged.items():
                    self.assertEqual(archive.read(name), data)
                self.assertEqual(archive.read(native), elf())
                self.assertEqual(archive.read("assets/source/NOTICE"), b"license")

    def test_incomplete_or_incompatible_replacement_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            source, output = Path(directory) / "original.aar", Path(directory) / "rebuilt.aar"
            native = "jni/arm64-v8a/liblibusbcom.so"
            with zipfile.ZipFile(source, "w") as archive:
                archive.writestr(native, elf(alignment=4096))
            with self.assertRaisesRegex(ValueError, "replacement set"):
                replace_native_entries(source, output, {}, {})
            with self.assertRaises(VerificationError):
                replace_native_entries(source, output, {native: elf(alignment=4096)}, {})

    def test_source_archive_materializes_an_internal_symlink_and_chain(self):
        with tempfile.TemporaryDirectory() as directory:
            cache = Path(directory)
            archive = cache / "source.tar.gz"
            content = b"the same pinned source bytes"
            with tarfile.open(archive, "w:gz") as source:
                regular = tarfile.TarInfo("root/README")
                regular.size = len(content)
                regular.mode = 0o644
                source.addfile(regular, io.BytesIO(content))
                for name, link in (("root/README.md", "README"), ("root/docs/README", "../README.md")):
                    member = tarfile.TarInfo(name)
                    member.type = tarfile.SYMTYPE
                    member.linkname = link
                    source.addfile(member)
            destination = extract_source(archive, cache / "sources", cache)
            for name in ("README", "README.md", "docs/README"):
                self.assertEqual((destination / name).read_bytes(), content)
                self.assertFalse((destination / name).is_symlink())

    def test_source_archive_cannot_escape_or_create_unsafe_links(self):
        with tempfile.TemporaryDirectory() as directory:
            cache = Path(directory)
            for name, kind, link in (("root/../../escaped", tarfile.REGTYPE, ""),
                                     ("root/link", tarfile.SYMTYPE, "../../escaped"),
                                     ("root/link", tarfile.SYMTYPE, "/etc/passwd"),
                                     ("root/link", tarfile.SYMTYPE, "missing"),
                                     ("root/link", tarfile.LNKTYPE, "../../escaped")):
                archive = cache / "source.tar.gz"
                with tarfile.open(archive, "w:gz") as source:
                    member = tarfile.TarInfo(name)
                    member.type = kind
                    member.linkname = link
                    source.addfile(member, io.BytesIO())
                with self.assertRaises(ValueError):
                    extract_source(archive, cache / "sources", cache)
            self.assertFalse((cache.parent / "escaped").exists())

    def test_source_archive_rejects_symlink_cycles_and_directory_targets(self):
        with tempfile.TemporaryDirectory() as directory:
            cache = Path(directory)
            for directory_target in (False, True):
                archive = cache / "source.tar.gz"
                with tarfile.open(archive, "w:gz") as source:
                    first = tarfile.TarInfo("root/first")
                    first.type = tarfile.SYMTYPE
                    first.linkname = "second"
                    source.addfile(first)
                    second = tarfile.TarInfo("root/second")
                    second.type = tarfile.DIRTYPE if directory_target else tarfile.SYMTYPE
                    second.linkname = "first" if not directory_target else ""
                    source.addfile(second)
                with self.assertRaisesRegex(ValueError, "regular file|cycle"):
                    extract_source(archive, cache / "sources", cache)


if __name__ == "__main__":
    unittest.main()
