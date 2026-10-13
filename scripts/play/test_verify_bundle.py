import unittest

from verify_bundle import (BundleVerificationError, UPLOAD_CERTIFICATE_SHA256,
                           manifest_metadata, native_proof, upload_certificate)


MANIFEST = '''<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="io.github.xntso.vendroid" android:versionCode="31" android:versionName="0.2.0-beta">
    <uses-sdk android:minSdkVersion="23" android:targetSdkVersion="36"/>
    <application android:debuggable="false"/>
</manifest>'''
CERTIFICATE = "Signer #1:\nCertificate #1:\nSHA256: " + ":".join(
    UPLOAD_CERTIFICATE_SHA256[index:index + 2].upper()
    for index in range(0, 64, 2)
) + "\n"


class BundleVerificationTest(unittest.TestCase):
    def test_shared_package_optimized_beta_metadata(self):
        result = manifest_metadata(MANIFEST, version_code=31, version_name="0.2.0-beta")
        self.assertEqual("io.github.xntso.vendroid", result["packageName"])
        self.assertEqual(31, result["versionCode"])
        self.assertEqual("36", result["targetSdkVersion"])

    def test_wrong_package_version_and_debuggable_bundle_fail(self):
        for xml in (
            MANIFEST.replace('vendroid"', 'vendroid.preview"'),
            MANIFEST.replace('versionCode="31"', 'versionCode="30"'),
            MANIFEST.replace('versionName="0.2.0-beta"', 'versionName="0.2.0"'),
            MANIFEST.replace('debuggable="false"', 'debuggable="true"'),
            MANIFEST.replace('<application android:debuggable="false"/>', ''),
            "not XML",
        ):
            with self.subTest(xml=xml), self.assertRaises(BundleVerificationError):
                manifest_metadata(xml, version_code=31, version_name="0.2.0-beta")

    def test_valid_signature_requires_existing_single_upload_key(self):
        self.assertEqual(UPLOAD_CERTIFICATE_SHA256,
                         upload_certificate("jar verified.\n", CERTIFICATE))
        for jarsigner, keytool in (
            ("jar is unsigned.\n", CERTIFICATE),
            ("jar verified.\nThis jar contains unsigned entries", CERTIFICATE),
            ("jar verified.\n", CERTIFICATE + "Signer #2:\n"),
            ("jar verified.\n", CERTIFICATE.replace("B3:", "00:", 1)),
            ("jar verified.\n", ""),
        ):
            with self.assertRaises(BundleVerificationError):
                upload_certificate(jarsigner, keytool)

    def test_minimum_android_support_and_current_play_target_are_preserved(self):
        for xml in (MANIFEST.replace('minSdkVersion="23"', 'minSdkVersion="24"'),
                    MANIFEST.replace('targetSdkVersion="36"', 'targetSdkVersion="35"'),
                    MANIFEST.replace('targetSdkVersion="36"', 'targetSdkVersion="preview"')):
            with self.assertRaises(BundleVerificationError):
                manifest_metadata(xml, version_code=31, version_name="0.2.0-beta")

    def test_native_proof_must_match_exact_aab_and_pass(self):
        report = {"artifact_type": "aab", "sha256": "a" * 64, "native_16kb_passed": True}
        native_proof(report, "a" * 64)
        for changed in ({"artifact_type": "apk"}, {"sha256": "b" * 64},
                        {"native_16kb_passed": False}, {"native_16kb_passed": "true"}):
            with self.assertRaises(BundleVerificationError):
                native_proof({**report, **changed}, "a" * 64)


if __name__ == "__main__":
    unittest.main()
