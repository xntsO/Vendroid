# Native USB libraries and 16 KB pages

Google Play requires 16 KB compatibility for new apps and updates targeting Android 15 or later. Vendroid targets Android 16. Its original core 0.10.0 and libusbcommunication 0.3.0 AARs contain 64-bit USB libraries with 4 KB ELF load alignment. ZIP alignment alone does not fix them. See [Android's 16 KB guide](https://developer.android.com/guide/practices/page-sizes).

## CI preparation

Before any Gradle build, GitHub Actions runs:

```sh
sdkmanager 'ndk;28.2.13676358' 'cmake;3.22.1'
python3 scripts/native/prepare.py --sdk-root "$ANDROID_HOME"
```

The [source lock](../scripts/native/source-lock.json) pins libaums, libusb, the original AARs and POMs, NDK, CMake, and the [JNI release-mode patch](../scripts/native/jni-release-modes.patch). CI checks SHA-256 before using downloaded sources and artifacts. The original APK's exported libusb version structure identifies libusb 1.0.26 with nano version 11724; that release's source is retained. Java/Kotlin bytecode stays unchanged.

The pinned libusb JNI adapter passes `NULL` to three `ReleaseLongArrayElements` or `ReleaseByteArrayElements` mode arguments. Those arguments are integers, and Clang 19 rejects the pointer type. The patch changes only these three arguments to integer `0`, preserving the existing copy-back and release behavior. Preparation requires the exact upstream file hash, patch hash, expected calls and diff, and patched file hash before writing any source changes. Other JNI code remains unchanged.

Preparation builds all four existing ABIs with explicit maximum and common ELF page sizes of 16384. It replaces only JNI `.so` entries in the upstream AARs and checks that every other entry, including bytecode, resources, and consumer rules, remains byte-for-byte identical. The original POMs retain their transitive dependencies. Gradle resolves these two coordinates exclusively from `.vendroid-cache/native-16kb/maven`, so missing preparation cannot fall back to the incompatible prebuilts.

`.vendroid-cache/native-16kb/provenance.json` records source pins, the checked source patch and before/after hashes, original and rebuilt artifact hashes, unchanged bytecode hashes, and each rebuilt native library hash. Retain it with the APK/AAB validation evidence.

## Artifact checks

CI's fixture tests cover valid artifacts, incompatible 64-bit ELF segments, wrong architectures, truncated files, invalid load boundaries, APK ZIP misalignment, AAB alignment configuration, missing USB libraries, duplicate entries, source archive traversal, and incomplete AAR replacements:

```sh
python3 -m unittest discover -s scripts/native -p 'test_*.py' -v
```

The actual APK and AAB gates run after the build:

```sh
python3 scripts/native/verify.py path/to/app.apk --output native-apk.json
python3 scripts/native/verify.py path/to/app.aab --output native-aab.json
```

They require all four USB libraries for every shipped ABI. All 64-bit ELF load segments must support at least 16 KB alignment; 32-bit libraries may retain 4 KB alignment. Uncompressed APK entries must have the matching ZIP alignment. AABs must request `PAGE_ALIGNMENT_16K` or greater in `BundleConfig.pb`, using [bundletool's documented protobuf fields](https://github.com/google/bundletool/blob/master/src/main/proto/config.proto). The report identifies the artifact SHA-256 and every inspected library. These checks prove binary and packaging alignment. The ordinary Bliss OS USB tests use a 4 KB environment and do not prove execution on a 16 KB Android device. Record that runtime coverage gap until separate evidence is available.

Inspection of the pinned native sources found no hardcoded operating-system page size in the Android JNI adapters or libusb's compiled Linux backend. `MAX_CTRL_BUFFER_LENGTH=4096` limits USB control-transfer length; it does not define a memory page. The Linux backend's device-memory allocator calls `mmap` with a null address and zero file offset, so the kernel chooses the mapping alignment. This source check does not replace a runtime test on 16 KB Android.

## Source and licensing

The rebuilt AARs add per-module `assets/native-source/` notices, licenses, original pinned source archives, and the exact JNI release-mode patch. libaums and its JNI adapters retain their Apache 2.0 license. libusb retains LGPL 2.1 or later and its original source headers. The application links libusb as a shared library.

To relink with modified libusb, fork Vendroid, publish the modified libusb source revision, update its commit and archive checksum in `source-lock.json`, and run the fork's GitHub Actions with your own signing credentials. The native rebuild uses that source without changing the JNI interfaces. Credentials belong in Actions secrets. Source, build flags, and the relinking recipe are available in the repository and distributed source assets.
