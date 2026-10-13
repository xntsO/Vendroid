"""Boundary fixtures for optional recording; real video comes only from Appium CI."""

import base64
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

from vendroid.service_recording import ServiceRecording


VIDEO = b"\x00\x00\x00\x18ftypmp42\x00\x00\x00\x00mp42isom\x00\x00\x00\x08mdat"
IDENTITY = {"channel": "preview", "package": "io.github.xntso.vendroid", "version": "0.2.0-beta",
            "commit": "a" * 40, "usb_bus": "xhci.0", "layout": "gpt"}
SERVICE_DUMP = "ServiceRecord{example io.github.xntso.vendroid/.service.WorkerService}\n" \
               "  isForeground=true foregroundId=42 foregroundNoti=Notification(channel=job_progress)\n"


class ServiceRecordingTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.evidence = Path(self.temporary.name)
        self.driver = Mock()
        self.driver.stop_recording_screen.return_value = base64.b64encode(VIDEO).decode("ascii")
        self.driver.execute_script.return_value = SERVICE_DUMP
        self.driver.save_screenshot.return_value = True
        self.driver.page_source = '<hierarchy><node resource-id="success_write_title"/></hierarchy>'
        self.probe = patch("vendroid.service_recording.subprocess.run", return_value=Mock(stdout=json.dumps({
            "streams": [{"width": 720, "height": 1280}], "format": {"duration": "120.0"},
        }))).start()
        self.addCleanup(patch.stopall)

    def metadata(self):
        return json.loads((self.evidence / "foreground-service-demo" / "recording.json").read_text())

    def install(self):
        with ServiceRecording(self.driver, self.evidence, IDENTITY) as recording:
            recording.mark("write-confirmation")
            recording.capture_active_service()
            recording.installed()

    def test_only_preview_xhci_records_and_other_cases_make_no_driver_calls(self):
        for channel in ("stable", "preview", "gpt-candidate"):
            for bus in ("uhci.0", "xhci.0"):
                if channel == "preview" and bus == "xhci.0":
                    continue
                with self.subTest(channel=channel, bus=bus):
                    with ServiceRecording(self.driver, self.evidence, {**IDENTITY, "channel": channel, "usb_bus": bus}) as recording:
                        recording.mark("write-confirmation")
                        recording.capture_active_service()
                        recording.installed()
        self.assertEqual(self.driver.mock_calls, [])
        self.assertFalse((self.evidence / "foreground-service-demo").exists())

    def test_success_saves_video_identity_and_actual_service_evidence(self):
        self.install()
        report = self.metadata()
        self.assertEqual(report["status"], "captured")
        self.assertEqual(report["gaps"], [])
        self.assertEqual(report["commit"], IDENTITY["commit"])
        self.assertTrue(report["success_ui_observed"])
        self.assertTrue(report["foreground_service_observed"])
        self.assertTrue(report["foreground_notification_observed"])
        self.assertTrue(report["manual_video_review_required"])
        self.assertFalse(report["physical_device_tested"])
        self.assertEqual(report["video"]["sha256"], hashlib.sha256(VIDEO).hexdigest())
        directory = self.evidence / "foreground-service-demo"
        self.assertEqual((directory / "usb-install.mp4").read_bytes(), VIDEO)
        self.assertEqual((directory / "active-service.txt").read_text(), SERVICE_DUMP)
        self.driver.start_recording_screen.assert_called_once_with(timeLimit=600, bitRate=1_000_000, forceRestart=True)
        self.driver.stop_recording_screen.assert_called_once_with()
        self.driver.execute_script.assert_called_once_with("mobile: shell", {
            "command": "dumpsys", "args": ["activity", "services", IDENTITY["package"]],
            "includeStderr": False, "timeout": 5000,
        })
        self.assertEqual([event["name"] for event in report["events"]],
                         ["before-install-action", "write-confirmation", "installed-success-observed"])

    def test_start_failure_still_attempts_cleanup_and_reports_gap(self):
        self.driver.start_recording_screen.side_effect = RuntimeError("screenrecord unavailable")
        self.driver.stop_recording_screen.return_value = ""
        self.install()
        report = self.metadata()
        self.assertEqual(report["status"], "unavailable")
        self.assertTrue(report["success_ui_observed"])
        self.assertTrue(any("screenrecord unavailable" in gap["reason"] for gap in report["gaps"]))
        self.driver.stop_recording_screen.assert_called_once_with()

    def test_usb_failure_is_preserved_even_when_recorder_cleanup_fails(self):
        failure = AssertionError("USB write failed")
        self.driver.stop_recording_screen.side_effect = RuntimeError("pull failed")
        with self.assertRaises(AssertionError) as raised:
            with ServiceRecording(self.driver, self.evidence, IDENTITY):
                raise failure
        self.assertIs(raised.exception, failure)
        report = self.metadata()
        self.assertFalse(report["success_ui_observed"])
        self.assertTrue(any(gap["stage"] == "stop-recording" for gap in report["gaps"]))

    def test_empty_invalid_base64_and_non_mp4_outputs_are_explicit_gaps(self):
        for payload in ("", "invalid base64!", base64.b64encode(b"not a video").decode("ascii")):
            with self.subTest(payload=payload):
                self.driver.stop_recording_screen.return_value = payload
                self.install()
                self.assertEqual(self.metadata()["status"], "unavailable")
                self.assertTrue(any(gap["stage"] == "stop-recording" for gap in self.metadata()["gaps"]))

    def test_missing_service_or_notification_is_not_a_false_pass(self):
        self.driver.execute_script.return_value = "ACTIVITY MANAGER SERVICES\n(no services)\n"
        self.install()
        report = self.metadata()
        self.assertEqual(report["status"], "captured-with-gaps")
        self.assertFalse(report["foreground_service_observed"])
        self.assertFalse(report["foreground_notification_observed"])

    def test_probe_failure_retains_video_and_reports_unverified_duration(self):
        self.probe.side_effect = FileNotFoundError("ffprobe unavailable")
        self.install()
        report = self.metadata()
        self.assertEqual(report["status"], "captured-with-gaps")
        self.assertNotIn("duration_seconds", report["video"])
        self.assertTrue(any(gap["stage"] == "inspect-video" for gap in report["gaps"]))

    def test_last_chunk_only_is_reported_as_incomplete(self):
        with patch("vendroid.service_recording.monotonic", return_value=0) as clock:
            with ServiceRecording(self.driver, self.evidence, IDENTITY) as recording:
                recording.installed()
                clock.return_value = 250
        report = self.metadata()
        self.assertEqual(report["status"], "captured-with-gaps")
        self.assertTrue(any(gap["stage"] == "video-duration" for gap in report["gaps"]))

    def test_evidence_write_failure_does_not_mask_usb_result(self):
        with patch.object(Path, "write_text", side_effect=OSError("evidence unavailable")):
            self.install()
        self.driver.stop_recording_screen.assert_called_once_with()


if __name__ == "__main__":
    unittest.main()
