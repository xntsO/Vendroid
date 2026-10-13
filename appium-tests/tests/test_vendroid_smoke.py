import os
from datetime import date

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

    # External actions prompt locally; blank entry can never silently select an eligible age.
    wait_for_element(
        driver, '//*[@text="Project on GitHub"]/ancestor-or-self::*[@clickable="true"][1]', timeout=15,
    ).click()
    birth_fields = [
        wait_for_element(driver, f'//*[@resource-id="{tag}"]', timeout=15)
        for tag in ("onlineBirthDay", "onlineBirthMonth", "onlineBirthYear")
    ]
    assert all(field.get_attribute("text") == "" for field in birth_fields)
    assert wait_for_element(driver, '//*[@resource-id="onlineAgeContinue"]').get_attribute("enabled") == "false"
    wait_for_element(
        driver, '//*[@text="Continue offline"]/ancestor-or-self::*[@clickable="true"][1]', timeout=15,
    ).click()
    assert driver.current_package == profile.package

    wait_for_element(
        driver, '//*[@text="Project on GitHub"]/ancestor-or-self::*[@clickable="true"][1]', timeout=15,
    ).click()
    for tag, value in (
        ("onlineBirthDay", "1"), ("onlineBirthMonth", "1"), ("onlineBirthYear", str(date.today().year - 8)),
    ):
        field = wait_for_element(driver, f'//*[@resource-id="{tag}"]', timeout=15)
        driver.execute_script("mobile: replaceElementValue", {"elementId": field.id, "text": value})
    if driver.is_keyboard_shown():
        driver.hide_keyboard()
    wait_for_element(driver, '//*[@resource-id="onlineAgeContinue"]', timeout=15).click()
    wait_for_element(driver, '//*[@resource-id="onlineAccessRestricted"]', timeout=15)
    assert driver.current_package == profile.package
    wait_for_element(
        driver, '//*[@text="Continue offline"]/ancestor-or-self::*[@clickable="true"][1]', timeout=15,
    ).click()
    wait_for_element(
        driver, '//*[@text="Star on GitHub"]/ancestor-or-self::*[@clickable="true"][1]', timeout=15,
    ).click()
    wait_for_element(driver, '//*[@resource-id="onlineAccessRestricted"]', timeout=15)
    assert driver.current_package == profile.package
    wait_for_element(
        driver, '//*[@text="Continue offline"]/ancestor-or-self::*[@clickable="true"][1]', timeout=15,
    ).click()
    wait_for_element(
        driver, '//*[@text="Privacy policy"]/ancestor-or-self::*[@clickable="true"][1]', timeout=15,
    ).click()
    wait_for_element(driver, '//*[@resource-id="localPrivacyPolicy"]', timeout=15)
    assert driver.current_package == profile.package
    wait_for_element(
        driver, '//*[@text="Close"]/ancestor-or-self::*[@clickable="true"][1]', timeout=15,
    ).click()
    driver.back()
    wait_for_element(driver, '//*[@resource-id="installVentoyCTA"]', timeout=15)
