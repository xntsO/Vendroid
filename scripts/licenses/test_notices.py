"""Pins Vendroid's own notices: the EtchDroid attribution and the changed-file notices."""

import hashlib
import json
from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]
ETCHDROID_COMMIT = "e2eda57136d46fd3c465dee2e5ab31cdcb019a6e"
MASS_STORAGE = ROOT / "app/src/main/java/io/github/xntso/vendroid/massstorage"
# Hashes of the repository bytes; .gitattributes keeps these files out of line-ending conversion.
TEXT_HASHES = {
    "LICENSE": "3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986",
    "third_party/licenses/Apache-2.0.txt": "cb5e8e7e5f4a3988e1063c142c60dc2df75605f4c46515e776e3aca6df976e14",
    "third_party/licenses/LGPL-2.1.txt": "5df07007198989c622f5d41de8d703e7bef3d0e79d62e24332ee739a452af62a",
}


def read(relative):
    return (ROOT / relative).read_text(encoding="utf-8")


class NoticeTest(unittest.TestCase):
    def test_license_texts_are_unchanged(self):
        for relative, expected in TEXT_HASHES.items():
            with self.subTest(file=relative):
                self.assertEqual(hashlib.sha256((ROOT / relative).read_bytes()).hexdigest(), expected)

    def test_notice_credits_etchdroid_and_states_the_terms(self):
        notice = read("NOTICE")
        for phrase in ("modified version of EtchDroid", "Davide Depau", ETCHDROID_COMMIT, "2026-07-04",
                       "WITHOUT ANY", "GNU Lesser General Public License",
                       "Creative Commons 3.0 Attribution", "exfatprogs", "Okio", "XZ for Java"):
            with self.subTest(phrase=phrase):
                self.assertIn(phrase, notice)

    def test_notice_follows_the_native_source_pins(self):
        notice = read("NOTICE")
        sources = json.loads(read("scripts/native/source-lock.json"))["sources"]
        self.assertIn(sources["libaums"]["commit"], notice)
        self.assertIn(sources["libusb"]["commit"], notice)
        self.assertIn(f"libusb {sources['libusb']['version']}", notice)

    def test_every_attribution_names_the_same_etchdroid_commit(self):
        for relative in ("NOTICE", "README.md", "app/src/main/java/io/github/xntso/vendroid/utils/ProjectLinks.kt"):
            with self.subTest(file=relative):
                self.assertIn(ETCHDROID_COMMIT, read(relative))

    def test_adapted_libaums_files_say_they_were_changed(self):
        for name in ("VendroidScsiBlockDevice.kt", "VendroidUsbMassStorageDevice.kt"):
            with self.subTest(file=name):
                header = (MASS_STORAGE / name).read_text(encoding="utf-8").split("Licensed under the Apache License")[0]
                self.assertIn("Modified by Vendroid contributors, 2026:", header)

    def test_about_strings_carry_the_attribution(self):
        for relative in ("app/src/main/res/values/strings.xml", "app/src/main/res/values-ar/strings.xml"):
            with self.subTest(file=relative):
                strings = read(relative)
                match = re.search(r'<string name="etchdroid_attribution"[^>]*>(.*?)</string>', strings, re.S)
                self.assertIsNotNone(match)
                self.assertIn("%1$s", match.group(1))
                self.assertIn("%2$s", match.group(1))
                self.assertIn("Davide Depau", match.group(1))
                self.assertNotIn('name="upstream_projects"', strings)


if __name__ == "__main__":
    unittest.main()
