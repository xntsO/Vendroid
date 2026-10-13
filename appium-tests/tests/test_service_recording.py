"""Boundary fixtures for optional recording; real video comes only from Appium CI."""

import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch

from vendroid.mjpeg_recording import MjpegRecorder
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
        self.driver.execute_script.return_value = SERVICE_DUMP
        self.driver.save_screenshot.return_value = True
        self.driver.page_source = '<hierarchy><node resource-id="success_write_title"/></hierarchy>'
        self.video = VIDEO
        self.recorder = patch("vendroid.service_recording.MjpegRecorder", autospec=True).start().return_value
        self.recorder.start.side_effect = lambda: (self.evidence / "foreground-service-demo" / "usb-install.mp4").unlink(missing_ok=True)
        self.recorder.stop.side_effect = self.save_video
        self.probe = patch("vendroid.service_recording.subprocess.run", return_value=Mock(stdout=json.dumps({
            "streams": [{"width": 720, "height": 1280}], "format": {"duration": "120.0"},
        }))).start()
        self.addCleanup(patch.stopall)

    def save_video(self):
        if self.video is not None:
            (self.evidence / "foreground-service-demo" / "usb-install.mp4").write_bytes(self.video)

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
        self.recorder.start.assert_called_once_with()
        self.recorder.stop.assert_called_once_with()
        self.driver.start_recording_screen.assert_not_called()
        self.driver.execute_script.assert_called_once_with("mobile: shell", {
            "command": "dumpsys", "args": ["activity", "services", IDENTITY["package"]],
            "includeStderr": False, "timeout": 5000,
        })
        self.assertEqual([event["name"] for event in report["events"]],
                         ["before-install-action", "write-confirmation", "installed-success-observed"])

    def test_start_failure_still_attempts_cleanup_and_reports_gap(self):
        self.recorder.start.side_effect = RuntimeError("MJPEG stream unavailable")
        self.video = None
        self.install()
        report = self.metadata()
        self.assertEqual(report["status"], "unavailable")
        self.assertTrue(report["success_ui_observed"])
        self.assertTrue(any("MJPEG stream unavailable" in gap["reason"] for gap in report["gaps"]))
        self.recorder.stop.assert_called_once_with()

    def test_usb_failure_is_preserved_even_when_recorder_cleanup_fails(self):
        failure = AssertionError("USB write failed")
        self.recorder.stop.side_effect = RuntimeError("encoder failed")
        with self.assertRaises(AssertionError) as raised:
            with ServiceRecording(self.driver, self.evidence, IDENTITY):
                raise failure
        self.assertIs(raised.exception, failure)
        report = self.metadata()
        self.assertFalse(report["success_ui_observed"])
        self.assertTrue(any(gap["stage"] == "stop-recording" for gap in report["gaps"]))

    def test_missing_empty_and_non_mp4_outputs_are_explicit_gaps(self):
        for payload in (None, b"", b"not a video"):
            with self.subTest(payload=payload):
                self.video = payload
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
        self.recorder.stop.assert_called_once_with()


class MjpegRecorderTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.driver = Mock(capabilities={"deviceUDID": "localhost:5556"})
        self.driver.get_settings.return_value = {
            "mjpegServerPort": 7810, "mjpegServerFramerate": 10,
            "mjpegScalingFactor": 50, "mjpegServerScreenshotQuality": 50,
        }
        self.adb = patch("vendroid.mjpeg_recording.subprocess.run", return_value=Mock(stdout="12345\n")).start()
        self.process = Mock(returncode=0)
        self.process.poll.return_value = None
        self.process.wait.return_value = 0
        self.popen = patch("vendroid.mjpeg_recording.subprocess.Popen", side_effect=self.launch).start()
        patch.dict("os.environ", {"ANDROID_HOME": "/android/sdk"}).start()
        self.addCleanup(patch.stopall)
        self.recorder = MjpegRecorder(self.driver, self.directory, 600)
        self.addCleanup(self.recorder.stop)

    def launch(self, *arguments, **options):
        self.recorder.output.write_bytes(VIDEO)
        return self.process

    def test_live_stream_uses_only_its_allocated_port_and_restores_settings(self):
        self.recorder.start()
        self.recorder.stop()
        command = self.popen.call_args.args[0]
        self.assertIn("http://127.0.0.1:12345", command)
        self.assertIn("mpjpeg", command)
        self.assertIn("libx264", command)
        self.assertIn("-use_wallclock_as_timestamps", command)
        self.assertEqual(self.adb.call_args_list[0].args[0],
                         ["/android/sdk/platform-tools/adb", "-s", "localhost:5556", "forward", "tcp:0", "tcp:7810"])
        self.assertEqual(self.adb.call_args_list[1].args[0],
                         ["/android/sdk/platform-tools/adb", "-s", "localhost:5556", "forward", "--remove", "tcp:12345"])
        self.assertEqual(self.driver.update_settings.call_args.args[0],
                         {"mjpegServerFramerate": 10, "mjpegScalingFactor": 50, "mjpegServerScreenshotQuality": 50})
        self.recorder.stop()
        self.assertEqual(self.adb.call_count, 2)

    def test_ffmpeg_unavailable_releases_port_and_restores_settings(self):
        self.popen.side_effect = FileNotFoundError("ffmpeg missing")
        with self.assertRaises(FileNotFoundError):
            self.recorder.start()
        self.recorder.stop()
        self.assertEqual(self.adb.call_args_list[-1].args[0][-3:], ["forward", "--remove", "tcp:12345"])

    def test_dead_encoder_cannot_confirm_startup(self):
        self.process.poll.return_value = 9
        self.process.returncode = 9
        with self.assertRaises(RuntimeError):
            self.recorder.start()
        with self.assertRaises(RuntimeError):
            self.recorder.stop()
        self.assertEqual(self.adb.call_args_list[-1].args[0][-3:], ["forward", "--remove", "tcp:12345"])

    def test_start_timeout_and_hung_stop_are_bounded(self):
        self.popen.side_effect = lambda *args, **options: self.process
        with patch("vendroid.mjpeg_recording.monotonic", side_effect=[0, 11]):
            with self.assertRaises(TimeoutError):
                self.recorder.start()
        self.process.wait.side_effect = [subprocess.TimeoutExpired("ffmpeg", 15), 0]
        with self.assertRaisesRegex(RuntimeError, "forced termination"):
            self.recorder.stop()
        self.process.kill.assert_called_once()
        self.process.wait.side_effect = None

    def test_invalid_forward_result_never_launches_an_encoder(self):
        self.adb.return_value.stdout = "invalid-port"
        with self.assertRaises(ValueError):
            self.recorder.start()
        self.popen.assert_not_called()

    def test_old_video_is_removed_before_failed_start(self):
        self.recorder.output.write_bytes(VIDEO)
        self.popen.side_effect = FileNotFoundError("ffmpeg missing")
        with self.assertRaises(FileNotFoundError):
            self.recorder.start()
        self.assertFalse(self.recorder.output.exists())


if __name__ == "__main__":
    unittest.main()
