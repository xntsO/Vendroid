import appium.webdriver

from vendroid.fixtures import appium_service, driver
from vendroid.utils import used, wait_for_element

used(appium_service)


def test_home_shows_install_ventoy_action(driver: appium.webdriver.Remote):
    wait_for_element(driver, '//*[@resource-id="installVentoyCTA"]', timeout=15)
