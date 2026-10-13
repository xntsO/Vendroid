import os

import appium.webdriver

from vendroid.fixtures import appium_service, driver
from vendroid.utils import used, wait_for_element
from vendroid.validation_profiles import configured_profile

used(appium_service)


def test_home_identity_and_privacy_policy_action(driver: appium.webdriver.Remote):
    profile = configured_profile()
    version = profile.version(os.environ.get("VENDROID_VERSION", "0.2.0"))
    wait_for_element(driver, '//*[@resource-id="installVentoyCTA"]', timeout=15)
    wait_for_element(driver, f'//*[@text="{profile.app_name} v{version}"]', timeout=15)
    assert driver.current_package == profile.package
    wait_for_element(driver, '//*[@content-desc="Menu"]', timeout=15).click()
    wait_for_element(
        driver, '//*[@text="About"]/ancestor-or-self::*[@clickable="true"][1]', timeout=15,
    ).click()
    privacy_button = wait_for_element(
        driver, '//*[@text="Privacy policy"]/ancestor-or-self::*[@clickable="true"][1]', timeout=15,
    )
    assert privacy_button.get_attribute("enabled") == "true"
    privacy_button.click()
    policy = wait_for_element(driver, '//*[@resource-id="localPrivacyPolicy"]', timeout=15)
    assert "# Vendroid privacy policy" in policy.get_attribute("text")
    assert "IP address" in policy.get_attribute("text")
    assert driver.current_package == profile.package
    wait_for_element(
        driver, '//*[@text="Close"]/ancestor-or-self::*[@clickable="true"][1]', timeout=15,
    ).click()

    website = wait_for_element(driver, '//*[@resource-id="projectWebsiteButton"]', timeout=15)
    assert website.get_attribute("enabled") == "true"
    assert not driver.find_elements(
        "xpath", '//*[@resource-id="onlineBirthDay" or @resource-id="onlineBirthMonth" '
        'or @resource-id="onlineBirthYear" or @resource-id="onlineAccessRestricted"]',
    ), "About and local privacy must not require an age entry"
    driver.back()
    # Returning from About restores the home activity's still-open settings menu.
    wait_for_element(driver, '//*[@text="Style"]', timeout=15)
    driver.back()
    wait_for_element(driver, '//*[@resource-id="installVentoyCTA"]', timeout=15)
