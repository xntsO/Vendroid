import os

import appium.webdriver

from vendroid.fixtures import appium_service, driver
from vendroid.utils import used, wait_for_element

used(appium_service)


def test_home_shows_install_ventoy_action(driver: appium.webdriver.Remote):
    app_name = os.environ.get("VENDROID_APP_NAME", "Vendroid")
    version = os.environ.get("VENDROID_EXPECTED_VERSION", "0.2.0")
    wait_for_element(driver, '//*[@resource-id="installVentoyCTA"]', timeout=15)
    wait_for_element(driver, f'//*[@text="{app_name} v{version}"]', timeout=15)
