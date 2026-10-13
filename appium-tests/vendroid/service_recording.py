"""Optional real-screen evidence for Play's connected-device service declaration.

Appium API: https://github.com/appium/python-client/blob/v5.0.0/appium/webdriver/extensions/screen_record.py
Android recorder: https://github.com/appium/appium-android-driver/blob/v13.1.1/lib/commands/recordscreen.ts
"""

import base64
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path
import re
import subprocess
from time import monotonic


RECORDING_OPTIONS = {"timeLimit": 600, "bitRate": 1_000_000, "forceRestart": True}
MAX_VIDEO_BYTES = 128 * 1024 * 1024


class ServiceRecording:
    """Capture one existing install without changing its result or USB actions."""

    def __init__(self, driver, evidence: Path, identity: dict):
        self.driver = driver
        self.enabled = identity["channel"] == "preview" and identity["usb_bus"] == "xhci.0"
        self.directory = evidence / "foreground-service-demo"
        self.report = {
            **identity,
            "environment": "Bliss OS / QEMU virtual USB",
            "physical_device_tested": False,
            "started_at": datetime.now(timezone.utc).isoformat(),
            "recording_options": RECORDING_OPTIONS,
            "status": "unavailable",
            "success_ui_observed": False,
            "manual_video_review_required": True,
            "events": [],
            "gaps": [],
        }
        self._start_attempted = False
        self._started = False
        self._begin = monotonic()

    def _gap(self, stage, reason):
        detail = {"stage": stage, "reason": str(reason)[:500]}
        self.report["gaps"].append(detail)
        print(f"Foreground-service demo gap: {stage}: {detail['reason']}")

    def _optional(self, stage, action):
        if self.enabled:
            try:
                action()
            except Exception as error:
                self._gap(stage, f"{type(error).__name__}: {error}")

    def __enter__(self):
        def start():
            self.directory.mkdir(parents=True, exist_ok=True)
            self._start_attempted = True
            self.driver.start_recording_screen(**RECORDING_OPTIONS)
            self._started = True
            self._begin = monotonic()
            self.mark("before-install-action")
        self._optional("start-recording", start)
        return self

    def mark(self, name):
        if self.enabled:
            self.report["events"].append({"name": name, "elapsed_seconds": monotonic() - self._begin})

    def capture_active_service(self):
        def capture():
            output = self.driver.execute_script("mobile: shell", {
                "command": "dumpsys",
                "args": ["activity", "services", self.report["package"]],
                "includeStderr": False,
                "timeout": 5000,
            })
            if not isinstance(output, str) or not output.strip():
                raise ValueError("No service dump was returned")
            (self.directory / "active-service.txt").write_text(output, encoding="utf-8")
            foreground = "WorkerService" in output and bool(re.search(r"\bisForeground=true\b", output))
            self.report["foreground_service_observed"] = foreground
            self.report["foreground_notification_observed"] = "foregroundNoti=Notification(" in output
            if not foreground:
                self._gap("active-service", "WorkerService foreground state was not observed")
            if not self.report["foreground_notification_observed"]:
                self._gap("active-service", "Foreground notification was not observed in the service dump")
        self._optional("active-service", capture)

    def installed(self):
        if self.enabled:
            self.report["success_ui_observed"] = True
            self.mark("installed-success-observed")
        def capture():
            if not self.driver.save_screenshot(str(self.directory / "installed-success.png")):
                raise ValueError("Success screenshot was not saved")
            (self.directory / "installed-success.xml").write_text(self.driver.page_source, encoding="utf-8")
        self._optional("success-screen", capture)

    def _inspect_video(self):
        video = self.directory / "usb-install.mp4"
        result = subprocess.run(
            ["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
             "stream=width,height:format=duration", "-of", "json", str(video)],
            check=True, capture_output=True, text=True, timeout=15,
        )
        info = json.loads(result.stdout)
        duration = float(info["format"]["duration"])
        stream = info["streams"][0]
        if not math.isfinite(duration) or duration <= 0 or stream["width"] <= 0 or stream["height"] <= 0:
            raise ValueError("Recorded video has no usable video stream or duration")
        self.report["video"].update({"duration_seconds": duration, "width": stream["width"], "height": stream["height"]})
        # The Android driver returns only the last 180s chunk when merging fails.
        if duration + 5 < self.report["stop_requested_elapsed_seconds"]:
            self._gap("video-duration", "Video is shorter than the observed install interval; check for missing chunks or encoder stalls")

    def _stop(self):
        payload = self.driver.stop_recording_screen()
        if not isinstance(payload, (str, bytes)) or not payload:
            raise ValueError("Appium returned no recorded video")
        if len(payload) > 4 * ((MAX_VIDEO_BYTES + 2) // 3):
            raise ValueError("Recorded video exceeds the evidence size limit")
        data = base64.b64decode(payload, validate=True)
        if len(data) < 12 or data[4:8] != b"ftyp":
            raise ValueError("Recorded data is not an MP4 file")
        (self.directory / "usb-install.mp4").write_bytes(data)
        self.report["video"] = {"file": "usb-install.mp4", "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
        self.report["status"] = "captured"
        self._optional("inspect-video", self._inspect_video)

    def __exit__(self, exception_type, exception, traceback):
        if self.enabled:
            self.report["stop_requested_elapsed_seconds"] = monotonic() - self._begin
            if exception_type is not None:
                self._gap("install-lifecycle", f"Existing USB test failed with {exception_type.__name__}")
            if not self.report["success_ui_observed"]:
                self._gap("install-lifecycle", "Installed-success UI was not observed")
            if not self._started:
                self._gap("start-recording", "Recorder did not confirm startup before the user action")
            if self.report["stop_requested_elapsed_seconds"] >= RECORDING_OPTIONS["timeLimit"]:
                self._gap("video-duration", "Recording time limit was reached before the end of the install")
            if self._start_attempted:
                self._optional("stop-recording", self._stop)
            if self.report["gaps"] and "video" in self.report:
                self.report["status"] = "captured-with-gaps"
            self.report["finished_at"] = datetime.now(timezone.utc).isoformat()
            self._optional("save-metadata", lambda: (self.directory / "recording.json").write_text(
                json.dumps(self.report, indent=2), encoding="utf-8",
            ))
        return False
