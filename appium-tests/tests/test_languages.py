import appium.webdriver

from vendroid.fixtures import appium_service, driver
from vendroid.utils import used, wait_for_element
from vendroid.validation_profiles import configured_profile

used(appium_service)


def _open_language_picker(driver: appium.webdriver.Remote):
    wait_for_element(driver, '//*[@resource-id="settingsMenuButton"]', timeout=15).click()
    wait_for_element(driver, '//*[@resource-id="languageMenuItem"]', timeout=15).click()
    for option in ("AUTOMATIC", "ENGLISH", "ARABIC"):
        wait_for_element(driver, f'//*[@resource-id="language{option}"]', timeout=15)


def _selected(driver: appium.webdriver.Remote, option: str):
    element = wait_for_element(driver, f'//*[@resource-id="language{option}"]', timeout=15)
    assert any(element.get_attribute(attribute) == "true" for attribute in ("checked", "selected")), (
        f"Saved language {option} is not exposed as selected"
    )


def _choose(driver: appium.webdriver.Remote, option: str):
    _open_language_picker(driver)
    wait_for_element(driver, f'//*[@resource-id="language{option}"]', timeout=15).click()


def _assert_home_language(driver: appium.webdriver.Remote, *, arabic: bool):
    label = "تثبيت Ventoy" if arabic else "Install Ventoy"
    wait_for_element(driver, f'//*[@text="{label}" or @content-desc="{label}"]', timeout=15)
    menu = wait_for_element(driver, '//*[@resource-id="settingsMenuButton"]', timeout=15)
    midpoint = driver.get_window_size()["width"] / 2
    assert (menu.rect["x"] < midpoint) if arabic else (menu.rect["x"] > midpoint), (
        "The end-aligned menu did not follow the selected RTL/LTR language"
    )


def test_language_choice_persists_and_arabic_mirrors_the_actual_home_ui(driver: appium.webdriver.Remote):
    package = configured_profile().package
    try:
        # CI's system language is English. The default remains Automatic.
        _assert_home_language(driver, arabic=False)
        _open_language_picker(driver)
        _selected(driver, "AUTOMATIC")
        wait_for_element(driver, '//*[@resource-id="languageARABIC"]', timeout=15).click()
        _assert_home_language(driver, arabic=True)

        # Relaunch the process without clearApp: the explicit override must survive.
        driver.terminate_app(package)
        driver.activate_app(package)
        _assert_home_language(driver, arabic=True)
        _open_language_picker(driver)
        _selected(driver, "ARABIC")
        wait_for_element(driver, '//*[@resource-id="languageENGLISH"]', timeout=15).click()
        _assert_home_language(driver, arabic=False)
        _choose(driver, "AUTOMATIC")
        _assert_home_language(driver, arabic=False)
        _open_language_picker(driver)
        _selected(driver, "AUTOMATIC")
        driver.back()
    finally:
        # Never leave a locale override for subsequent USB lifecycle sessions.
        # Fresh launch also dismisses any menu/dialog left open by a failed assertion.
        driver.terminate_app(package)
        driver.activate_app(package)
        _choose(driver, "AUTOMATIC")
        _assert_home_language(driver, arabic=False)
