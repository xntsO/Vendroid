import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import MagicMock

from publish import (PlayPublishError, VerifiedBundle, highest_version_code,
                     publish_bundle, resolve_play_track, verified_bundle)
from verify_bundle import UPLOAD_CERTIFICATE_SHA256


COMMIT = "a" * 40
HASH = "b" * 64


class PlayPublishingTest(unittest.TestCase):
    def test_beta_defaults_to_closed_testing_until_open_beta_is_available(self):
        self.assertEqual("alpha", resolve_play_track("beta"))
        self.assertEqual("alpha", resolve_play_track("beta", "alpha"))
        self.assertEqual("beta", resolve_play_track("beta", "beta"))

    def test_testing_configuration_cannot_reroute_production(self):
        for configured in ("", "alpha", "beta"):
            self.assertEqual("production", resolve_play_track("production", configured))
        for configured in ("production", "internal", " beta", "beta\n"):
            with self.assertRaises(PlayPublishError):
                resolve_play_track("beta", configured)
        with self.assertRaises(PlayPublishError):
            resolve_play_track("alpha")

    def client(self, *, tracks=None, bundles=None, apks=None):
        client = MagicMock()
        edits = client.edits.return_value
        edits.insert.return_value.execute.return_value = {"id": "test-edit"}
        edits.tracks.return_value.list.return_value.execute.return_value = tracks or {"tracks": []}
        edits.bundles.return_value.list.return_value.execute.return_value = bundles or {"bundles": []}
        edits.apks.return_value.list.return_value.execute.return_value = apks or {"apks": []}
        edits.bundles.return_value.upload.return_value.execute.return_value = {"versionCode": 31, "sha256": HASH}
        return client

    def publish(self, client, track="beta"):
        bundle = VerifiedBundle(Path("verified.aab"), 31, "0.2.0-beta", HASH, COMMIT)
        return publish_bundle(client, bundle, track=track, release_name="v0.2.0-beta.1", media_body=object())

    def test_success_validates_commits_and_targets_only_selected_track(self):
        for track in ("alpha", "beta", "production"):
            with self.subTest(track=track):
                client = self.client(tracks={"tracks": [{"track": "internal", "releases": [{"versionCodes": ["30"]}]}]})
                result = self.publish(client, track)
                edits = client.edits.return_value
                self.assertEqual("committed", result["status"])
                self.assertEqual(track, result["track"])
                update = edits.tracks.return_value.update.call_args.kwargs
                self.assertEqual(track, update["track"])
                self.assertEqual([{"name": "v0.2.0-beta.1", "versionCodes": ["31"], "status": "completed"}],
                                 update["body"]["releases"])
                names = [call[0] for call in client.mock_calls]
                self.assertLess(names.index("edits().tracks().list"), names.index("edits().bundles().upload"))
                self.assertLess(names.index("edits().validate"), names.index("edits().commit"))
                edits.commit.assert_called_once_with(packageName="io.github.xntso.vendroid",
                                                     editId="test-edit", changesNotSentForReview=False,
                                                     changesInReviewBehavior="ERROR_IF_IN_REVIEW")
                edits.delete.assert_not_called()

    def test_pending_review_error_abandons_current_edit_without_cancelling_review(self):
        client = self.client()
        edits = client.edits.return_value
        edits.commit.return_value.execute.side_effect = RuntimeError("CHANGES_ALREADY_IN_REVIEW")
        with self.assertRaisesRegex(RuntimeError, "CHANGES_ALREADY_IN_REVIEW"):
            self.publish(client, "alpha")
        edits.commit.assert_called_once_with(
            packageName="io.github.xntso.vendroid", editId="test-edit",
            changesNotSentForReview=False, changesInReviewBehavior="ERROR_IF_IN_REVIEW",
        )
        edits.commit.return_value.execute.assert_called_once_with(num_retries=2)
        edits.delete.assert_called_once_with(packageName="io.github.xntso.vendroid", editId="test-edit")

    def test_equal_or_lower_code_on_any_track_blocks_before_upload(self):
        for track in ("beta", "production", "internal", "custom-testing"):
            for code in ("31", "100"):
                with self.subTest(track=track, code=code):
                    client = self.client(tracks={"tracks": [{"track": track,
                        "releases": [{"status": "draft", "versionCodes": [code]}]}]})
                    with self.assertRaisesRegex(PlayPublishError, "must be greater"):
                        self.publish(client)
                    edits = client.edits.return_value
                    edits.bundles.return_value.upload.assert_not_called()
                    edits.tracks.return_value.update.assert_not_called()
                    edits.commit.assert_not_called()
                    edits.delete.assert_called_once()

    def test_unassigned_bundle_or_apk_also_reserves_its_version_code(self):
        for inventory in ({"bundles": {"bundles": [{"versionCode": 32}]}},
                          {"apks": {"apks": [{"versionCode": 31}]}}):
            client = self.client(**inventory)
            with self.assertRaises(PlayPublishError):
                self.publish(client)
            client.edits.return_value.bundles.return_value.upload.assert_not_called()

    def test_inventory_parsing_rejects_malformed_external_codes(self):
        for code in (True, None, "31\n", -1, 2_100_000_001, 31.0):
            with self.subTest(code=code), self.assertRaises(PlayPublishError):
                highest_version_code({"tracks": []}, {"bundles": [{"versionCode": code}]}, {"apks": []})
        with self.assertRaises(PlayPublishError):
            highest_version_code({"tracks": "unknown"}, {}, {})

    def test_upload_mismatch_or_validation_failure_never_commits(self):
        for uploaded in ({"versionCode": 30, "sha256": HASH}, {"versionCode": 31, "sha256": "c" * 64}):
            client = self.client()
            edits = client.edits.return_value
            edits.bundles.return_value.upload.return_value.execute.return_value = uploaded
            with self.assertRaises(PlayPublishError):
                self.publish(client)
            edits.tracks.return_value.update.assert_not_called()
            edits.commit.assert_not_called()
            edits.delete.assert_called_once()
        client = self.client()
        edits = client.edits.return_value
        edits.validate.return_value.execute.side_effect = RuntimeError("fixture failure")
        with self.assertRaises(RuntimeError):
            self.publish(client)
        edits.commit.assert_not_called()
        edits.delete.assert_called_once()

    def test_cleanup_failure_preserves_original_error(self):
        client = self.client(bundles={"bundles": [{"versionCode": 31}]})
        client.edits.return_value.delete.return_value.execute.side_effect = RuntimeError("cleanup failed")
        with self.assertRaisesRegex(PlayPublishError, "must be greater"):
            self.publish(client)

    def test_bundle_proof_hash_commit_package_signer_and_native_gate_are_required(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "vendroid.aab"
            path.write_bytes(b"bundle fixture")
            metadata = {
                "packageName": "io.github.xntso.vendroid", "versionCode": 31,
                "versionName": "0.2.0-beta", "commit": COMMIT,
                "bundleSha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                "uploadCertificateSha256": UPLOAD_CERTIFICATE_SHA256,
                "native16KbPassed": True,
            }
            bundle = verified_bundle(path, metadata, version_code=31, version_name="0.2.0-beta", commit=COMMIT)
            self.assertEqual(metadata["bundleSha256"], bundle.sha256)
            for changed in ({"packageName": "io.github.xntso.vendroid.preview"}, {"versionCode": 30},
                            {"versionName": "0.2.0"}, {"commit": "b" * 40}, {"bundleSha256": HASH},
                            {"uploadCertificateSha256": "c" * 64}, {"native16KbPassed": False}):
                with self.subTest(changed=changed), self.assertRaises(PlayPublishError):
                    verified_bundle(path, {**metadata, **changed}, version_code=31,
                                    version_name="0.2.0-beta", commit=COMMIT)


if __name__ == "__main__":
    unittest.main()
