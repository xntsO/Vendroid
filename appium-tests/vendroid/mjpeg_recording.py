"""Record UiAutomator2's real screenshot stream without Android's video encoder.

https://github.com/appium/appium-uiautomator2-driver/blob/v7.1.2/docs/mjpeg.md
"""

import os
from pathlib import Path
import re
import signal
import subprocess
from time import monotonic, sleep


MJPEG_SETTINGS = {"mjpegServerFramerate": 3, "mjpegScalingFactor": 100, "mjpegServerScreenshotQuality": 80}


class MjpegRecorder:
    def __init__(self, driver, directory: Path, time_limit: int):
        self.driver = driver
        self.directory = directory
        self.time_limit = time_limit
        self.output = directory / "usb-install.mp4"
        self.process = None
        self.port = None
        self.log = None
        self.previous_settings = {}

    def _adb(self, *arguments):
        return subprocess.run(
            [self.adb, "-s", self.udid, *arguments],
            capture_output=True, text=True, check=True, timeout=10,
        )

    def start(self):
        self.output.unlink(missing_ok=True)
        sdk_root = os.environ.get("ANDROID_HOME")
        self.udid = self.driver.capabilities.get("deviceUDID")
        if not sdk_root or not isinstance(self.udid, str) or not self.udid:
            raise ValueError("MJPEG recording needs the SDK path and current Appium device ID")
        self.adb = str(Path(sdk_root) / "platform-tools" / "adb")
        settings = self.driver.get_settings()
        device_port = settings.get("mjpegServerPort", 7810)
        if not isinstance(device_port, int) or not 1024 <= device_port <= 65535:
            raise ValueError("Appium returned an invalid device MJPEG port")
        self.previous_settings = {key: settings[key] for key in MJPEG_SETTINGS if key in settings}
        self.driver.update_settings(MJPEG_SETTINGS)
        forwarded = self._adb("forward", "tcp:0", f"tcp:{device_port}").stdout.strip()
        if not re.fullmatch(r"[0-9]{1,5}", forwarded) or not 1 <= int(forwarded) <= 65535:
            raise ValueError("ADB returned an invalid allocated MJPEG port")
        self.port = int(forwarded)
        self.log = (self.directory / "mjpeg-recorder.log").open("wb")
        # UiAutomator2 emits image/jpg parts, rejected by FFmpeg's mpjpeg MIME
        # parser. The raw MJPEG parser reads the JPEG frames across those headers.
        self.process = subprocess.Popen([
            "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "warning", "-y",
            "-use_wallclock_as_timestamps", "1", "-analyzeduration", "1000000",
            "-f", "mjpeg", "-framerate", "3",
            "-i", f"http://127.0.0.1:{self.port}", "-an",
            "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
            "-b:v", "1000000", "-maxrate", "1000000", "-bufsize", "2000000",
            "-r", "3", "-t", str(self.time_limit), "-flush_packets", "1",
            "-movflags", "+faststart", str(self.output),
        ], stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=self.log)
        # FFmpeg creates its MP4 header only after reading the live MJPEG input.
        deadline = monotonic() + 10
        while monotonic() < deadline:
            if self.process.poll() is not None:
                raise RuntimeError("MJPEG recorder exited before startup; see mjpeg-recorder.log")
            if self.output.is_file() and self.output.stat().st_size > 0:
                return
            sleep(0.1)
        raise TimeoutError("MJPEG recording did not start within 10 seconds")

    def stop(self):
        errors = []
        process, self.process = self.process, None
        try:
            if process is not None:
                if process.poll() is None:
                    process.send_signal(signal.SIGINT)
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
                    errors.append("MJPEG recorder required forced termination")
                # FFmpeg uses exit255 after SIGINT even when it finalizes the MP4.
                if process.returncode not in (0, 255):
                    errors.append("MJPEG recorder failed; see mjpeg-recorder.log")
        except Exception as error:
            errors.append(f"MJPEG recorder cleanup failed: {type(error).__name__}")
        finally:
            log, self.log = self.log, None
            if log is not None:
                try:
                    log.close()
                except Exception as error:
                    errors.append(f"MJPEG log cleanup failed: {type(error).__name__}")
            port, self.port = self.port, None
            if port is not None:
                try:
                    self._adb("forward", "--remove", f"tcp:{port}")
                except Exception as error:
                    errors.append(f"MJPEG port cleanup failed: {type(error).__name__}")
            previous_settings, self.previous_settings = self.previous_settings, {}
            if previous_settings:
                try:
                    self.driver.update_settings(previous_settings)
                except Exception as error:
                    errors.append(f"MJPEG settings restore failed: {type(error).__name__}")
        if errors:
            raise RuntimeError("; ".join(errors))
