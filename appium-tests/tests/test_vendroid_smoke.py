import json
import os
from pathlib import Path

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

    # The bundled Ventoy is GPL software: About names its version and links its source from a word.
    # AboutCreditsTest checks that the link opens the exact locked commit.
    lock = json.loads((Path(__file__).resolve().parents[2] / "third_party/ventoy/source-lock.json").read_text())
    credits = wait_for_element(driver, '//*[@resource-id="aboutCredits"]', timeout=15).get_attribute("text")
    assert f"Ventoy {lock['version']} is free software under the GNU GPLv3. Its source code: GitHub." in credits
    assert "github.com" not in credits, "About must link addresses from words, not show them"
    # Vendroid is a modified EtchDroid: About credits its author and states the GPL warranty terms.
    assert "modified version of EtchDroid" in credits
    assert "Davide Depau" in credits
    assert "no warranty" in credits

    # About opens the packaged notices and license texts offline.
    try:
        driver.find_element(
            "-android uiautomator",
            'new UiScrollable(new UiSelector().scrollable(true))'
            '.scrollIntoView(new UiSelector().resourceId("licensesButton"))',
        )
    except Exception:
        pass  # The compact layout may have nothing to scroll; the button is then already visible.
    wait_for_element(driver, '//*[@resource-id="licensesButton"]', timeout=15).click()
    wait_for_element(driver, '//*[@resource-id="licenseDocument0"]', timeout=15).click()
    notice = wait_for_element(driver, '//*[@resource-id="licenseText"]', timeout=15).get_attribute("text")
    for phrase in ("modified version of EtchDroid", "Davide Depau", "libusb", "WITHOUT ANY"):
        assert phrase in notice, phrase
    wait_for_element(driver, '//*[@resource-id="licensesBack"]', timeout=15).click()
    wait_for_element(driver, '//*[@resource-id="licenseDocument1"]', timeout=15).click()
    gpl = wait_for_element(driver, '//*[@resource-id="licenseText"]', timeout=15).get_attribute("text")
    assert "GNU GENERAL PUBLIC LICENSE" in gpl
    wait_for_element(driver, '//*[@resource-id="closeLicenses"]', timeout=15).click()
    wait_for_element(driver, '//*[@resource-id="aboutCredits"]', timeout=15)
    assert driver.current_package == profile.package

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
