"""Replay delayed USB dialogs without allowing an unconfirmed permission grant."""

import unittest
from unittest.mock import Mock, patch

from selenium.common import TimeoutException

from vendroid import actions


class PermissionTrace:
    def __init__(self, frames):
        self.frames = frames
        self.frame = (False, False)
        self.granted = Mock()
        self.granted.is_displayed.return_value = True
        self.ok = Mock()
        self.ok.is_displayed.return_value = True
        self.ok.get_attribute.return_value = "true"

    def find_elements(self, by, value):
        if value == '//*[@content-desc="Permission granted"]':
            return [self.granted] if self.frame[0] else []
        return [self.ok] if self.frame[1] else []

    def until(self, predicate):
        for frame in self.frames:
            self.frame = frame
            if predicate(self):
                return True
        raise TimeoutException("Permission was never confirmed")


class UsbPermissionTest(unittest.TestCase):
    def grant(self, trace):
        button = Mock()
        with patch.object(actions, "wait_for_element", return_value=button), \
                patch.object(actions, "get_wait", return_value=trace) as wait:
            actions.grant_usb_permission(trace)
        wait.assert_called_once_with(trace, 15)
        button.click.assert_called_once()

    def test_delayed_dialog_is_accepted_before_the_grant_returns(self):
        trace = PermissionTrace([(False, False), (False, False), (False, True), (True, False)])
        self.grant(trace)
        trace.ok.click.assert_called_once()
        self.assertTrue(trace.frame[0])

    def test_existing_permission_needs_no_system_dialog(self):
        trace = PermissionTrace([(True, False)])
        self.grant(trace)
        trace.ok.click.assert_not_called()

    def test_missing_dialog_and_permission_is_a_failure(self):
        trace = PermissionTrace([(False, False), (False, False)])
        with self.assertRaises(TimeoutException):
            self.grant(trace)

    def test_accepting_a_dialog_without_a_grant_is_a_failure(self):
        trace = PermissionTrace([(False, True), (False, False)])
        with self.assertRaises(TimeoutException):
            self.grant(trace)
        trace.ok.click.assert_called_once()


if __name__ == "__main__":
    unittest.main()
