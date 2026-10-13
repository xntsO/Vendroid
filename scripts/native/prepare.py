"""Rebuild the pinned upstream USB JNI libraries and replace their prebuilt AAR entries."""

import argparse
import copy
import difflib
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import posixpath
import shutil
import subprocess
import sys
import tarfile
import time
from urllib.error import URLError
from urllib.request import urlopen
import zipfile

from verify import inspect_elf, sha256_file


SCRIPT_DIRECTORY = Path(__file__).resolve().parent
ROOT = SCRIPT_DIRECTORY.parents[1]
MAVEN_BASE = "https://repo.maven.apache.org/maven2/me/jahnen/libaums"
MAX_DOWNLOAD_BYTES = 20 * 1024 * 1024


def pinned_download(url, expected_sha256, destination):
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.is_file() and sha256_file(destination) == expected_sha256:
        return destination
    for attempt in range(3):
        try:
            with urlopen(url, timeout=90) as response:
                data = response.read(MAX_DOWNLOAD_BYTES + 1)
            if len(data) > MAX_DOWNLOAD_BYTES:
                raise ValueError(f"Pinned download exceeds the size limit: {url}")
            actual_sha256 = hashlib.sha256(data).hexdigest()
            if actual_sha256 != expected_sha256:
                raise ValueError(f"Pinned download checksum mismatch: {url}")
            destination.write_bytes(data)
            return destination
        except (URLError, TimeoutError, OSError):
            if attempt == 2:
                raise
            time.sleep(attempt + 1)
    raise RuntimeError("Download did not complete")


def extract_source(archive, destination, cache_boundary):
    """Materialize regular files and bounded internal file links inside one source root."""
    destination = destination.resolve()
    boundary = cache_boundary.resolve()
    if destination == boundary or not destination.is_relative_to(boundary):
        raise ValueError("Source extraction must stay inside the native preparation cache")
    with tarfile.open(archive, "r:gz") as source:
        members = source.getmembers()
        roots = set()
        total_bytes = 0
        paths = {}
        for member in members:
            name = PurePosixPath(member.name)
            if name.is_absolute() or not name.parts or ".." in name.parts or "\\" in member.name:
                raise ValueError(f"Unsafe source archive path: {member.name}")
            roots.add(name.parts[0])
            if not (member.isdir() or member.isfile() or member.issym()):
                raise ValueError(f"Unsupported hard link or special file in source archive: {member.name}")
            if len(name.parts) == 1 and not member.isdir():
                raise ValueError("Source archive root must be a directory")
            canonical = name.as_posix()
            if canonical in paths:
                raise ValueError(f"Duplicate source archive path: {member.name}")
            paths[canonical] = member
            total_bytes += member.size
        if len(roots) != 1 or total_bytes > 50 * 1024 * 1024:
            raise ValueError("Source archive must have one root and remain within its size limit")
        root = next(iter(roots))
        payloads = {}
        for name, member in paths.items():
            if member.isdir():
                continue
            visited = set()
            current = name
            for _ in range(64):
                if current in visited:
                    raise ValueError(f"Source archive symlink cycle: {name}")
                visited.add(current)
                target = paths.get(current)
                if target is None:
                    raise ValueError(f"Source archive symlink target is missing: {name}")
                if target.isfile():
                    payloads[name] = target
                    break
                if not target.issym():
                    raise ValueError(f"Source archive symlink must resolve to a regular file: {name}")
                link = PurePosixPath(target.linkname)
                if link.is_absolute() or "\\" in target.linkname:
                    raise ValueError(f"Unsafe source archive symlink target: {name}")
                current = posixpath.normpath((PurePosixPath(current).parent / link).as_posix())
                normalized = PurePosixPath(current)
                if not normalized.parts or normalized.parts[0] != root or ".." in normalized.parts:
                    raise ValueError(f"Source archive symlink escapes its root: {name}")
            else:
                raise ValueError(f"Source archive symlink chain exceeds its limit: {name}")
        if sum(member.size for member in payloads.values()) > 50 * 1024 * 1024:
            raise ValueError("Materialized source files exceed the size limit")
        if destination.exists():
            shutil.rmtree(destination)
        destination.mkdir(parents=True)
        for member in members:
            relative = PurePosixPath(member.name).parts[1:]
            if not relative:
                continue
            target = destination.joinpath(*relative)
            if not target.resolve().is_relative_to(destination):
                raise ValueError("Source archive escapes its extraction directory")
            if member.isdir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                payload = payloads[PurePosixPath(member.name).as_posix()]
                with source.extractfile(payload) as stream:
                    target.write_bytes(stream.read())
                target.chmod(0o755 if payload.mode & 0o111 else 0o644)
    return destination


def apply_jni_release_mode_patch(source_root, spec, patch_bytes):
    """Apply only the three pinned JNI integer-mode corrections, without fuzzy matching."""
    relative = "libusbcommunication/src/c/usb.c"
    if spec["source_file"] != relative:
        raise ValueError("JNI patch must target the pinned libusbcommunication adapter")
    if hashlib.sha256(patch_bytes).hexdigest() != spec["sha256"]:
        raise ValueError("JNI patch checksum differs from source-lock.json")
    source = source_root / relative
    original = source.read_bytes()
    if hashlib.sha256(original).hexdigest() != spec["source_sha256"]:
        raise ValueError("JNI patch preimage differs from the pinned upstream source")
    corrected = original
    calls = (
        b"    (*env)->ReleaseLongArrayElements(env, handle, body, NULL);",
        b"    (*env)->ReleaseByteArrayElements(env, data, c_data, NULL);",
        b"    (*env)->ReleaseByteArrayElements(env, buffer, c_data, NULL);",
    )
    for old in calls:
        if original.splitlines().count(old) != 1:
            raise ValueError("JNI patch requires exactly one occurrence of each pinned release call")
        corrected = corrected.replace(old, old.replace(b", NULL);", b", 0);"), 1)
    expected_patch = "".join(difflib.unified_diff(
        original.decode("utf-8").splitlines(keepends=True),
        corrected.decode("utf-8").splitlines(keepends=True),
        fromfile=f"a/{relative}", tofile=f"b/{relative}", n=0,
    )).encode("utf-8")
    if expected_patch != patch_bytes:
        raise ValueError("JNI patch must contain only the three NULL-to-zero release-mode corrections")
    corrected_hash = hashlib.sha256(corrected).hexdigest()
    if corrected_hash != spec["patched_source_sha256"]:
        raise ValueError("JNI patch result differs from source-lock.json")
    source.write_bytes(corrected)
    return {"file": spec["file"], "sha256": spec["sha256"], "source_file": relative,
            "original_source_sha256": spec["source_sha256"],
            "patched_source_sha256": corrected_hash, "integer_mode_corrections": 3}


def replace_native_entries(original, output, replacements, assets):
    """Keep upstream bytecode, manifest, resources, and consumer rules byte-for-byte."""
    with zipfile.ZipFile(original) as source:
        entries = source.infolist()
        names = [entry.filename for entry in entries]
        if len(names) != len(set(names)):
            raise ValueError("Upstream AAR has duplicate entry names")
        native = {name for name in names if name.startswith("jni/") and name.endswith(".so")}
        if native != replacements.keys():
            raise ValueError("Upstream AAR native entries differ from the pinned replacement set")
        for name, data in replacements.items():
            inspect_elf(data, name.split("/")[1])
        if set(assets) & set(names):
            raise ValueError("Source/license assets would overwrite upstream AAR entries")
        output.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(output, "w") as rebuilt:
            for entry in entries:
                data = replacements[entry.filename] if entry.filename in replacements else source.read(entry)
                rebuilt.writestr(copy.copy(entry), data)
            for name, data in assets.items():
                entry = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
                entry.compress_type = zipfile.ZIP_DEFLATED
                rebuilt.writestr(entry, data)
        with zipfile.ZipFile(output) as rebuilt:
            for entry in entries:
                if entry.filename not in replacements and source.read(entry) != rebuilt.read(entry.filename):
                    raise ValueError(f"Non-native upstream AAR entry changed: {entry.filename}")
            return hashlib.sha256(rebuilt.read("classes.jar")).hexdigest()


def prepare(sdk_root, cache_root, parallel):
    lock_bytes = (SCRIPT_DIRECTORY / "source-lock.json").read_bytes()
    lock = json.loads(lock_bytes)
    sdk_root, cache_root = sdk_root.resolve(), cache_root.resolve()
    cache_root.mkdir(parents=True, exist_ok=True)
    ndk = sdk_root / "ndk" / lock["ndk_version"]
    cmake = sdk_root / "cmake" / lock["cmake_version"] / "bin" / "cmake"
    if not cmake.is_file() or not (ndk / "build/cmake/android.toolchain.cmake").is_file():
        raise ValueError(f"Install SDK packages ndk;{lock['ndk_version']} and cmake;{lock['cmake_version']} first")
    revision = (ndk / "source.properties").read_text(encoding="utf-8")
    if f"Pkg.Revision = {lock['ndk_version']}" not in revision:
        raise ValueError("The installed NDK revision does not match source-lock.json")
    downloaded = {}
    sources = {}
    for name, spec in lock["sources"].items():
        archive = pinned_download(spec["url"], spec["sha256"], cache_root / "downloads" / f"{name}.tar.gz")
        downloaded[name] = archive
        sources[name] = extract_source(archive, cache_root / "sources" / name, cache_root)
    patch_spec = lock["jni_release_mode_patch"]
    patch_bytes = (SCRIPT_DIRECTORY / patch_spec["file"]).read_bytes()
    source_patch = apply_jni_release_mode_patch(sources["libaums"], patch_spec, patch_bytes)
    compiled = {}
    for abi in lock["abis"]:
        build = cache_root / "build" / abi
        subprocess.run([str(cmake), "-S", str(SCRIPT_DIRECTORY), "-B", str(build),
                        f"-DCMAKE_TOOLCHAIN_FILE={ndk}/build/cmake/android.toolchain.cmake",
                        f"-DANDROID_ABI={abi}", f"-DANDROID_PLATFORM=android-{lock['android_api']}",
                        "-DCMAKE_BUILD_TYPE=Release", f"-DLIBAUMS_SOURCE={sources['libaums']}",
                        f"-DLIBUSB_SOURCE={sources['libusb']}"], check=True)
        subprocess.run([str(cmake), "--build", str(build), "--parallel", str(parallel)], check=True)
        for spec in lock["artifacts"].values():
            for name in spec["libraries"]:
                data = (build / "lib" / name).read_bytes()
                inspect_elf(data, abi)
                compiled[f"jni/{abi}/{name}"] = data
    provenance = {"schema": 1, "source_lock": lock,
                  "source_lock_sha256": hashlib.sha256(lock_bytes).hexdigest(),
                  "elf_max_page_size": 16384, "elf_common_page_size": 16384,
                  "source_patches": [source_patch],
                  "artifacts": []}
    for name, spec in lock["artifacts"].items():
        version = spec["version"]
        filename = f"{name}-{version}"
        original = pinned_download(f"{MAVEN_BASE}/{name}/{version}/{filename}.aar", spec["aar_sha256"],
                                   cache_root / "downloads" / f"{filename}.aar")
        pom = pinned_download(f"{MAVEN_BASE}/{name}/{version}/{filename}.pom", spec["pom_sha256"],
                              cache_root / "downloads" / f"{filename}.pom")
        module_root = cache_root / "maven/me/jahnen/libaums" / name / version
        output = module_root / f"{filename}.aar"
        asset_root = f"assets/native-source/{name}"
        notice = (f"Vendroid rebuilds {name} {version} with 16 KB ELF alignment.\n"
                  "Upstream Java/Kotlin bytecode is unchanged.\n"
                  "The libusb JNI adapter has only three NULL-to-integer-0 release-mode corrections.\n"
                  "Original source archives and the checked patch are included beside this notice.\n"
                  "libaums by Magnus Jahnen and contributors, Apache License 2.0.\n"
                  f"https://github.com/magnusja/libaums/tree/{lock['sources']['libaums']['commit']}\n"
                  "Rebuild recipe: scripts/native in https://github.com/xntsO/Vendroid .\n")
        assets = {f"{asset_root}/NOTICE.txt": notice.encode(),
                  f"{asset_root}/source-lock.json": lock_bytes,
                  f"{asset_root}/{patch_spec['file']}": patch_bytes,
                  f"{asset_root}/LICENSE-libaums.txt": (sources["libaums"] / "LICENSE").read_bytes(),
                  f"{asset_root}/libaums-source.tar.gz": downloaded["libaums"].read_bytes()}
        if name == "libusbcommunication":
            assets[f"{asset_root}/LICENSE-libusb.txt"] = (sources["libusb"] / "COPYING").read_bytes()
            assets[f"{asset_root}/libusb-source.tar.gz"] = downloaded["libusb"].read_bytes()
            assets[f"{asset_root}/NOTICE.txt"] += (
                "libusb 1.0.26 by its authors and contributors, LGPL 2.1 or later.\n"
                f"https://github.com/libusb/libusb/tree/{lock['sources']['libusb']['commit']}\n"
                "Corresponding source archives and licenses are included beside this notice.\n"
                "Fork Vendroid, change the libusb source pin and checksum, and run its CI with your own signing key to relink.\n"
            ).encode()
        replacements = {path: data for path, data in compiled.items() if path.rsplit("/", 1)[1] in spec["libraries"]}
        classes_sha256 = replace_native_entries(original, output, replacements, assets)
        shutil.copyfile(pom, module_root / pom.name)
        provenance["artifacts"].append({"coordinates": f"me.jahnen.libaums:{name}:{version}",
                                        "original_aar_sha256": spec["aar_sha256"], "aar_sha256": sha256_file(output),
                                        "classes_sha256": classes_sha256, "pom_sha256": sha256_file(pom),
                                        "native_libraries": {path: hashlib.sha256(data).hexdigest()
                                                             for path, data in replacements.items()}})
    evidence = cache_root / "provenance.json"
    evidence.write_text(json.dumps(provenance, indent=2) + "\n", encoding="utf-8")
    print(f"Prepared exclusive local Maven repository: {cache_root / 'maven'}")
    print(f"Pinned source, unchanged bytecode, and native library evidence: {evidence}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk-root", type=Path, default=os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT"))
    parser.add_argument("--cache-dir", type=Path, default=ROOT / ".vendroid-cache/native-16kb")
    parser.add_argument("--parallel", type=int, default=2)
    args = parser.parse_args()
    if args.sdk_root is None or not 1 <= args.parallel <= 32:
        parser.error("Set --sdk-root and use a parallel count from 1 through 32")
    try:
        prepare(args.sdk_root, args.cache_dir, args.parallel)
    except (ValueError, OSError, tarfile.TarError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        print(f"Native preparation failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
