"""Validate real pinned discovery request shapes without credentials or network."""

import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import MagicMock
from urllib.parse import parse_qs, urlparse


@unittest.skipUnless(importlib.util.find_spec("googleapiclient"), "Install scripts/play/requirements.txt")
class GoogleClientContractTest(unittest.TestCase):
    def test_official_android_publisher_accepts_all_publishing_request_shapes(self):
        import httplib2
        from googleapiclient.discovery import build
        from googleapiclient.http import MediaFileUpload

        http = httplib2.Http(timeout=60)
        http.request = MagicMock(side_effect=AssertionError("Contract tests must not access the network"))
        client = build("androidpublisher", "v3", http=http, static_discovery=True, cache_discovery=False)
        edits = client.edits()
        package = "io.github.xntso.vendroid"
        args = {"packageName": package, "editId": "test-edit"}
        try:
            edits.insert(packageName=package, body={})
            for resource in (edits.tracks(), edits.bundles(), edits.apks()):
                self.assertEqual("GET", resource.list(**args).method)
            with tempfile.TemporaryDirectory() as directory:
                bundle = Path(directory) / "test.aab"
                bundle.write_bytes(b"fake AAB, no upload")
                upload = edits.bundles().upload(**args, media_body=MediaFileUpload(
                    str(bundle), mimetype="application/octet-stream", resumable=True,
                ))
                self.assertIsNotNone(upload.resumable)
            update = edits.tracks().update(**args, track="beta", body={"track": "beta", "releases": [{
                "name": "v0.2.0-beta.1", "versionCodes": ["31"], "status": "completed",
            }]})
            self.assertEqual("PUT", update.method)
            self.assertTrue(urlparse(update.uri).path.endswith("/tracks/beta"))
            self.assertEqual(["31"], json.loads(update.body)["releases"][0]["versionCodes"])
            self.assertEqual("POST", edits.validate(**args).method)
            commit = edits.commit(**args, changesNotSentForReview=False)
            self.assertEqual(["false"], parse_qs(urlparse(commit.uri).query)["changesNotSentForReview"])
            self.assertEqual("DELETE", edits.delete(**args).method)
            http.request.assert_not_called()
        finally:
            client.close()
