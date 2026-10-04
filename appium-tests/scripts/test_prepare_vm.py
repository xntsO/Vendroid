"""Exercise VM preparation with real curl/SHA-256 and a local HTTP fixture.

The extractor and qemu-img commands are replaced with small file-only fixtures.
No Android VM or external download is involved. GitHub Actions runs this suite.
"""

import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import os
from pathlib import Path
import subprocess
import tempfile
import threading
import time
import unittest


SCRIPT = Path(__file__).with_name("prepare-vm.sh")
ISO_NAME = "fixture.iso"
ISO_BYTES = b"Verified Bliss ISO fixture\n"
CHECKSUM = f"{hashlib.sha256(ISO_BYTES).hexdigest()}  {ISO_NAME}\n".encode()
BOOT_FILES = ("kernel", "initrd.img", "system.efs")


class DownloadFixture(BaseHTTPRequestHandler):
    def do_GET(self):
        self.server.requests.append(self.path)
        response = self.server.responses.get(self.path)
        if response is None:
            if self.path.endswith(f"/{ISO_NAME}.sha256"):
                response = CHECKSUM
            elif self.path.endswith(f"/{ISO_NAME}"):
                response = ISO_BYTES
            else:
                response = 404
        if isinstance(response, int):
            self.send_error(response)
            return
        stalled = response == "stall"
        body = ISO_BYTES if stalled else response
        self.send_response(200)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        try:
            if stalled:
                self.wfile.write(body[:1])
                self.wfile.flush()
                self.server.release.wait(10)
            else:
                self.wfile.write(body)
        except OSError:
            # Curl closes the connection when the bounded transfer expires.
            pass

    def log_message(self, format, *args):
        pass


class PrepareVmTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.vm = self.root / "vm cache"
        self.vm.mkdir()
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self._write_command("7z", """import os
from pathlib import Path
import sys

output = Path(next(value[2:] for value in sys.argv if value.startswith('-o')))
for name in ('kernel', 'initrd.img', 'system.efs'):
    (output / name).write_bytes(('extracted:' + name).encode())
    if os.environ.get('FAKE_EXTRACTION_FAILURE'):
        sys.exit(23)
""")
        self._write_command("qemu-img", """from pathlib import Path
import sys

assert sys.argv[1:4] == ['create', '-f', 'qcow2']
assert sys.argv[-1] == '2G'
Path(sys.argv[-2]).write_bytes(b'disposable USB fixture')
""")
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), DownloadFixture)
        self.server.responses = {}
        self.server.requests = []
        self.server.release = threading.Event()
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self._stop_server)
        base = f"http://127.0.0.1:{self.server.server_port}"
        self.environment = {
            **os.environ,
            "PATH": f"{self.bin}{os.pathsep}{os.environ['PATH']}",
            "VM_DIR": str(self.vm),
            "BLISSOS_FILE": ISO_NAME,
            "BLISSOS_URL": f"{base}/primary/",
            "BLISSOS_FALLBACK_URL": f"{base}/fallback",
            "BLISSOS_CONNECT_TIMEOUT": "1",
            "BLISSOS_CHECKSUM_TIMEOUT": "1",
            "BLISSOS_DOWNLOAD_TIMEOUT": "1",
            "BLISSOS_LOW_SPEED_TIME": "1",
            "GITHUB_OUTPUT": str(self.root / "github-output.txt"),
            "NO_PROXY": "127.0.0.1,localhost",
            "no_proxy": "127.0.0.1,localhost",
        }

    def _write_command(self, name, source):
        path = self.bin / name
        path.write_text("#!/usr/bin/env python3\n" + source, encoding="utf-8")
        path.chmod(0o755)

    def _stop_server(self):
        self.server.release.set()
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def _prepare(self):
        started = time.monotonic()
        result = subprocess.run(
            ["bash", str(SCRIPT)],
            env=self.environment,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            timeout=8,
        )
        self.assertLess(time.monotonic() - started, 7, result.stdout)
        return result

    def _assert_complete(self, result):
        self.assertEqual(result.returncode, 0, result.stdout)
        for name in BOOT_FILES:
            self.assertTrue((self.vm / name).stat().st_size, result.stdout)
        self.assertTrue((self.vm / "usb-storage.qcow2").stat().st_size)
        self.assertEqual(
            (self.root / "github-output.txt").read_text().strip(),
            f"vm_dir={self.vm}",
        )
        self.assertEqual(list(self.vm.glob("*.part")), [])
        self.assertEqual(list(self.vm.glob(".extract.*")), [])

    def _cache_iso(self, contents=ISO_BYTES, checksum=CHECKSUM):
        (self.vm / ISO_NAME).write_bytes(contents)
        (self.vm / f"{ISO_NAME}.sha256").write_bytes(checksum)

    def test_stalled_primary_transfer_uses_fallback_within_bound(self):
        self.server.responses[f"/primary/{ISO_NAME}"] = "stall"
        self._assert_complete(self._prepare())
        self.assertIn(f"/fallback/{ISO_NAME}", self.server.requests)
        self.assertEqual((self.vm / ISO_NAME).read_bytes(), ISO_BYTES)

    def test_all_stalled_transfers_fail_without_publishing_partial_iso(self):
        for mirror in ("primary", "fallback"):
            self.server.responses[f"/{mirror}/{ISO_NAME}"] = "stall"
        result = self._prepare()
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn("Unable to download a verified BlissOS ISO", result.stdout)
        self.assertFalse((self.vm / ISO_NAME).exists())
        self.assertFalse((self.vm / "kernel").exists())
        self.assertEqual(list(self.vm.glob("*.part")), [])

    def test_http_errors_fail_without_caching_checksum(self):
        for mirror in ("primary", "fallback"):
            self.server.responses[f"/{mirror}/{ISO_NAME}.sha256"] = 404
        result = self._prepare()
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertFalse((self.vm / f"{ISO_NAME}.sha256").exists())
        self.assertFalse((self.vm / ISO_NAME).exists())
        self.assertEqual(list(self.vm.glob("*.part")), [])
        self.assertTrue(all(request.endswith(".sha256") for request in self.server.requests))

    def test_invalid_checksum_response_uses_fallback(self):
        self.server.responses[f"/primary/{ISO_NAME}.sha256"] = b"<html>mirror failure</html>"
        self._assert_complete(self._prepare())
        self.assertIn(f"/fallback/{ISO_NAME}.sha256", self.server.requests)
        self.assertEqual((self.vm / f"{ISO_NAME}.sha256").read_bytes(), CHECKSUM)

    def test_checksum_mismatch_uses_fallback_before_publishing_iso(self):
        self.server.responses[f"/primary/{ISO_NAME}"] = b"wrong ISO contents"
        self._assert_complete(self._prepare())
        self.assertIn(f"/fallback/{ISO_NAME}", self.server.requests)
        self.assertEqual((self.vm / ISO_NAME).read_bytes(), ISO_BYTES)

    def test_all_checksum_mismatches_fail_without_publishing_iso(self):
        for mirror in ("primary", "fallback"):
            self.server.responses[f"/{mirror}/{ISO_NAME}"] = b"wrong ISO contents"
        result = self._prepare()
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertFalse((self.vm / ISO_NAME).exists())
        self.assertEqual(list(self.vm.glob("*.part")), [])

    def test_corrupt_cached_iso_is_downloaded_again(self):
        self._cache_iso(contents=b"interrupted download")
        self._assert_complete(self._prepare())
        self.assertIn(f"/primary/{ISO_NAME}", self.server.requests)
        self.assertEqual((self.vm / ISO_NAME).read_bytes(), ISO_BYTES)

    def test_invalid_cached_checksum_is_downloaded_again(self):
        self._cache_iso(checksum=b"cached error page")
        self._assert_complete(self._prepare())
        self.assertIn(f"/primary/{ISO_NAME}.sha256", self.server.requests)
        self.assertEqual((self.vm / f"{ISO_NAME}.sha256").read_bytes(), CHECKSUM)

    def test_verified_cached_iso_is_reused_without_network(self):
        self._cache_iso()
        self._assert_complete(self._prepare())
        self.assertEqual(self.server.requests, [])

    def test_extracted_cache_recreates_missing_usb_and_sets_output(self):
        for name in BOOT_FILES:
            (self.vm / name).write_bytes(b"cached boot artifact")
        self._assert_complete(self._prepare())
        self.assertEqual(self.server.requests, [])
        for name in BOOT_FILES:
            self.assertEqual((self.vm / name).read_bytes(), b"cached boot artifact")

    def test_empty_cached_boot_file_requires_preparation(self):
        for name in BOOT_FILES:
            (self.vm / name).write_bytes(b"cached boot artifact")
        (self.vm / "system.efs").write_bytes(b"")
        self._assert_complete(self._prepare())
        self.assertIn(f"/primary/{ISO_NAME}", self.server.requests)
        for name in BOOT_FILES:
            self.assertEqual((self.vm / name).read_bytes(), f"extracted:{name}".encode())

    def test_failed_extraction_does_not_publish_partial_boot_files(self):
        self.environment["FAKE_EXTRACTION_FAILURE"] = "1"
        result = self._prepare()
        self.assertNotEqual(result.returncode, 0, result.stdout)
        for name in BOOT_FILES:
            self.assertFalse((self.vm / name).exists())
        self.assertEqual(list(self.vm.glob(".extract.*")), [])


if __name__ == "__main__":
    unittest.main()
