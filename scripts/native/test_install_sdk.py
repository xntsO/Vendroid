"""Bounded retries must preserve pinned packages and fail closed on exhausted installs."""

import subprocess
import unittest
from unittest.mock import call, patch

import install_sdk


PACKAGES = ["ndk;28.2.13676358", "cmake;3.22.1", "build-tools;36.0.0"]
COMMAND = ["sdkmanager", *PACKAGES]


class SdkInstallationTests(unittest.TestCase):
    def setUp(self):
        self.run_patch = patch("install_sdk.subprocess.run")
        self.sleep_patch = patch("install_sdk.time.sleep")
        self.run = self.run_patch.start()
        self.sleep = self.sleep_patch.start()
        self.addCleanup(self.run_patch.stop)
        self.addCleanup(self.sleep_patch.stop)

    def test_success_does_not_repeat_installation(self):
        self.assertEqual(install_sdk.main(PACKAGES), 0)
        self.run.assert_called_once_with(COMMAND, check=True, timeout=300)
        self.sleep.assert_not_called()

    def test_failed_download_retries_identical_pinned_packages_then_stops(self):
        self.run.side_effect = [subprocess.CalledProcessError(1, COMMAND), None]
        self.assertEqual(install_sdk.main(PACKAGES), 0)
        self.assertEqual(self.run.call_args_list, [call(COMMAND, check=True, timeout=300)] * 2)
        self.sleep.assert_called_once_with(5)

    def test_three_failed_installs_keep_the_final_failure_status(self):
        self.run.side_effect = [subprocess.CalledProcessError(code, COMMAND) for code in (1, 1, 42)]
        self.assertEqual(install_sdk.main(PACKAGES), 42)
        self.assertEqual(self.run.call_args_list, [call(COMMAND, check=True, timeout=300)] * 3)
        self.assertEqual(self.sleep.call_args_list, [call(5), call(10)])

    def test_timeout_retries_and_can_recover(self):
        self.run.side_effect = [subprocess.TimeoutExpired(COMMAND, 300), None]
        self.assertEqual(install_sdk.main(PACKAGES), 0)
        self.assertEqual(self.run.call_count, 2)
        self.sleep.assert_called_once_with(5)

    def test_three_timeouts_remain_a_nonzero_validation_failure(self):
        self.run.side_effect = subprocess.TimeoutExpired(COMMAND, 300)
        self.assertEqual(install_sdk.main(PACKAGES), 124)
        self.assertEqual(self.run.call_count, 3)
        self.assertEqual(self.sleep.call_args_list, [call(5), call(10)])

    def test_missing_sdkmanager_is_not_treated_as_a_download_failure(self):
        self.run.side_effect = FileNotFoundError("sdkmanager")
        with self.assertRaises(FileNotFoundError):
            install_sdk.main(PACKAGES)
        self.run.assert_called_once()
        self.sleep.assert_not_called()


if __name__ == "__main__":
    unittest.main()
