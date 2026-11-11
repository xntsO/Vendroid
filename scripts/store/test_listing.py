"""Checks the store listing and guards branding that was removed from the app."""

from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]
METADATA = ROOT / "fastlane" / "metadata" / "android"
ENGLISH = METADATA / "en-US"
MAIN = ROOT / "app" / "src" / "main"

# Google Play limits, counted in characters.
LIMITS = {"title.txt": 30, "short_description.txt": 80, "full_description.txt": 4000}


def read(path):
    return path.read_text(encoding="utf-8")


class StoreListingTest(unittest.TestCase):
    def test_english_listing_fits_play_limits(self):
        for name, limit in LIMITS.items():
            with self.subTest(file=name):
                text = read(ENGLISH / name).strip()
                self.assertTrue(text, f"{name} is empty")
                self.assertLessEqual(len(text), limit, f"{name} has {len(text)} characters")

    def test_title_names_ventoy(self):
        self.assertIn("Ventoy", read(ENGLISH / "title.txt"))

    def test_every_listing_describes_ventoy(self):
        locales = sorted(path for path in METADATA.iterdir() if path.is_dir())
        self.assertIn(ENGLISH, locales)
        for locale in locales:
            with self.subTest(locale=locale.name):
                self.assertIn("Ventoy", read(locale / "full_description.txt"))

    def test_only_the_english_description_names_etchdroid(self):
        allowed = ENGLISH / "full_description.txt"
        for path in sorted(METADATA.rglob("*.txt")):
            if path == allowed:
                continue
            with self.subTest(file=str(path.relative_to(ROOT))):
                self.assertNotIn("etchdroid", read(path).lower())


class RemovedBrandingTest(unittest.TestCase):
    def test_windows_artwork_stays_removed(self):
        names = [path.name for path in (MAIN / "res").rglob("*")]
        self.assertEqual([name for name in names if re.search(r"win_xp|ic_windows", name)], [])

    def test_coil_stays_removed(self):
        for path in sorted((MAIN / "java").rglob("*.kt")):
            with self.subTest(file=str(path.relative_to(ROOT))):
                self.assertNotRegex(read(path), r"\bimport coil\.")
        self.assertNotIn("coil", read(ROOT / "gradle" / "libs.versions.toml").lower())
        self.assertNotIn("libs.coil", read(ROOT / "app" / "build.gradle.kts"))


if __name__ == "__main__":
    unittest.main()
