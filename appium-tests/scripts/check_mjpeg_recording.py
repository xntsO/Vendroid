"""Exercise the real FFmpeg recorder with UiAutomator2's multipart wire format.

Synthetic frames are temporary test inputs, never a service demonstration.
Run in CI after installing ffmpeg: python3 -m scripts.check_mjpeg_recording
"""

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import subprocess
import tempfile
from threading import Event, Thread
from time import monotonic, sleep
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

from vendroid.mjpeg_recording import MjpegRecorder


class MjpegStreamContractTest(unittest.TestCase):
    def test_appium_multipart_frames_produce_a_decodable_mp4(self):
        jpeg = subprocess.run([
            "ffmpeg", "-v", "error", "-f", "lavfi", "-i", "color=c=red:s=96x64:r=3",
            "-frames:v", "1", "-c:v", "mjpeg", "-threads", "1", "-f", "image2pipe", "pipe:1",
        ], check=True, capture_output=True, timeout=15).stdout
        self.assertTrue(jpeg.startswith(b"\xff\xd8"))
        self.assertTrue(jpeg.endswith(b"\xff\xd9"))
        for content_type in ("image/jpg", "image/jpeg"):
            with self.subTest(content_type=content_type), tempfile.TemporaryDirectory() as temporary:
                self.record_stream(jpeg, content_type, Path(temporary))

    def record_stream(self, jpeg, content_type, directory):
        finished = Event()
        frames_sent = []

        class StreamHandler(BaseHTTPRequestHandler):
            def do_GET(self):
                self.send_response(200)
                self.send_header("Content-Type", "multipart/x-mixed-replace; boundary=--BoundaryString")
                self.end_headers()
                # Exact pinned UiAutomator2 MjpegScreenshotStream framing.
                # https://github.com/appium/appium-uiautomator2-server/blob/v9.11.1/app/src/main/java/io/appium/uiautomator2/server/mjpeg/MjpegScreenshotStream.java
                part = (f"--BoundaryString\r\nContent-type: {content_type}\r\n"
                        f"Content-Length: {len(jpeg)}\r\n\r\n").encode("ascii") + jpeg + b"\r\n\r\n"
                deadline = monotonic() + 15
                try:
                    while not finished.is_set() and monotonic() < deadline:
                        self.wfile.write(part)
                        self.wfile.flush()
                        frames_sent.append(1)
                        finished.wait(1 / 3)
                except (BrokenPipeError, ConnectionResetError):
                    pass  # The recorder closes its stream after SIGINT.

            def log_message(self, *args):
                pass

        server = ThreadingHTTPServer(("127.0.0.1", 0), StreamHandler)
        server.daemon_threads = True
        worker = Thread(target=server.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True)
        worker.start()
        driver = Mock(capabilities={"deviceUDID": "wire-format-fixture"})
        driver.get_settings.return_value = {
            "mjpegServerPort": 7810, "mjpegServerFramerate": 10,
            "mjpegScalingFactor": 50, "mjpegServerScreenshotQuality": 50,
        }
        recorder = MjpegRecorder(driver, directory, 10)
        try:
            # Only the absent Android transport is replaced. FFmpeg, HTTP, frame
            # parsing, encoding, MP4 finalization, and probing are all real.
            with patch.dict("os.environ", {"ANDROID_HOME": "/wire-format-fixture"}), \
                    patch.object(recorder, "_adb", return_value=SimpleNamespace(stdout=str(server.server_port))):
                try:
                    recorder.start()
                    sleep(2)
                finally:
                    recorder.stop()
            probe = subprocess.run([
                "ffprobe", "-v", "error", "-count_frames", "-select_streams", "v:0",
                "-show_entries", "stream=codec_name,width,height,nb_read_frames:format=duration",
                "-of", "json", str(recorder.output),
            ], check=True, capture_output=True, text=True, timeout=15)
            result = json.loads(probe.stdout)
            stream = result["streams"][0]
            self.assertEqual(stream["codec_name"], "h264")
            self.assertEqual((stream["width"], stream["height"]), (96, 64))
            self.assertGreaterEqual(int(stream["nb_read_frames"]), 3)
            self.assertGreaterEqual(float(result["format"]["duration"]), 2 / 3)
            self.assertGreaterEqual(len(frames_sent), 3)
        except BaseException:
            log = directory / "mjpeg-recorder.log"
            if log.exists():
                print(log.read_text(encoding="utf-8", errors="replace"))
            raise
        finally:
            finished.set()
            server.shutdown()
            server.server_close()
            worker.join(timeout=2)


if __name__ == "__main__":
    unittest.main(verbosity=2)
