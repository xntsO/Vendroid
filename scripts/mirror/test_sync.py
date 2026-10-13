import contextlib
from dataclasses import asdict
import hashlib
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from sync import (
    MAX_ARCHIVE_BYTES, MIRROR_ORIGIN, UPSTREAM_API, HttpTransport, MirrorSyncError,
    asset_path, check_new_release, decode_json, main, open_source_archive,
    parse_manifest, parse_upstream_release, read_bounded, sync_mirror,
)


ARCHIVE = b"upstream release archive fixture\x00\xff"
SHA256 = hashlib.sha256(ARCHIVE).hexdigest()
TOKEN = "publish-token-" + "a" * 48
GITHUB_TOKEN = "github-token-" + "b" * 48


def upstream(*, version="1.1.17"):
    name = f"ventoy-{version}-linux.tar.gz"
    return {
        "id": 10, "tag_name": f"v{version}", "draft": False, "prerelease": False,
        "body": "Upstream release notes", "assets": [{
            "id": 20, "state": "uploaded", "name": name, "size": len(ARCHIVE),
            "digest": f"sha256:{SHA256}",
            "browser_download_url": f"https://github.com/ventoy/Ventoy/releases/download/v{version}/{name}",
        }],
    }


def manifest(*, version="1.1.17"):
    return parse_upstream_release(upstream(version=version)).manifest


class Response(io.BytesIO):
    def __init__(self, body=b"", *, status=200, headers=None):
        super().__init__(body)
        self.status = status
        self.headers = headers or {}


def json_response(value, *, status=200):
    return Response(json.dumps(value).encode(), status=status)


class Transport:
    def __init__(self, responses):
        self.responses = list(responses)
        self.calls = []

    def request(self, method, url, *, headers, data=None):
        file = data if hasattr(data, "read") else None
        self.calls.append({
            "method": method, "url": url, "headers": headers,
            "body": file.read() if file else data, "file": file,
        })
        if not self.responses:
            raise AssertionError("An unexpected HTTP request was made")
        return self.responses.pop(0)


def initial_sync_responses():
    proposed = manifest()
    return [
        json_response(upstream()), Response(status=404), Response(ARCHIVE),
        json_response({"sha256": SHA256, "sizeBytes": len(ARCHIVE)}),
        Response(ARCHIVE), json_response(asdict(proposed)), json_response(asdict(proposed)),
    ]


class MirrorSyncTest(unittest.TestCase):
    def test_http_requests_identify_the_sync_job_without_persisting_credentials(self):
        delivery_url = "https://release-assets.githubusercontent.com/payload?temporary=delivery"
        responses = initial_sync_responses()
        responses.insert(2, Response(status=302, headers={"Location": delivery_url}))
        with patch("sync.build_opener") as build_opener:
            opener = build_opener.return_value
            opener.open.side_effect = responses
            sync_mirror(HttpTransport(), TOKEN, github_token=GITHUB_TOKEN)
        self.assertEqual(8, opener.open.call_count)
        for call in opener.open.call_args_list:
            request = call.args[0]
            self.assertEqual("Vendroid-Mirror/1.0", request.get_header("User-agent"))
            if request.full_url == UPSTREAM_API:
                self.assertEqual(f"Bearer {GITHUB_TOKEN}", request.get_header("Authorization"))
            elif request.full_url.startswith(MIRROR_ORIGIN + "/v1/admin/"):
                self.assertEqual(f"Bearer {TOKEN}", request.get_header("Authorization"))
            else:
                self.assertIsNone(request.get_header("Authorization"))
            for header in ("Cookie", "Referer", "Forwarded", "X-forwarded-for", "Cf-connecting-ip", "X-real-ip"):
                self.assertIsNone(request.get_header(header))
        mirror_reads = [call.args[0] for call in opener.open.call_args_list
                        if call.args[0].full_url == MIRROR_ORIGIN + "/v1/ventoy/latest.json"]
        self.assertTrue(all(request.get_header("Accept") == "application/json" for request in mirror_reads))

    def test_default_user_agent_does_not_mutate_caller_headers(self):
        headers = {"Accept": "application/json"}
        with patch("sync.build_opener") as build_opener:
            HttpTransport().request("GET", MIRROR_ORIGIN + "/v1/ventoy/latest.json", headers=headers)
            request = build_opener.return_value.open.call_args.args[0]
        self.assertEqual({"Accept": "application/json"}, headers)
        self.assertEqual("Vendroid-Mirror/1.0", request.get_header("User-agent"))

    def test_strips_metadata_and_publishes_only_after_both_archive_hash_checks(self):
        transport = Transport(initial_sync_responses())
        proof = sync_mirror(transport, TOKEN, github_token=GITHUB_TOKEN)
        proposed = manifest()
        self.assertEqual("published", proof["status"])
        self.assertTrue(proof["publicArchiveSha256Verified"])
        self.assertTrue(proof["publicManifestReadbackVerified"])
        self.assertEqual(asdict(proposed), proof["manifest"])
        self.assertEqual(7, len(transport.calls))
        self.assertEqual(proposed.downloadUrl, transport.calls[4]["url"])
        publication = transport.calls[5]
        self.assertEqual("PUT", publication["method"])
        self.assertEqual(MIRROR_ORIGIN + "/v1/admin/ventoy/latest.json", publication["url"])
        self.assertEqual(asdict(proposed), decode_json(publication["body"]))
        self.assertEqual(str(len(publication["body"])), publication["headers"]["Content-Length"])
        asset = transport.calls[3]
        self.assertEqual(ARCHIVE, asset["body"])
        self.assertEqual(str(len(ARCHIVE)), asset["headers"]["Content-Length"])
        self.assertEqual(MIRROR_ORIGIN + asset_path(SHA256, proposed.assetName, admin=True), asset["url"])
        self.assertTrue(asset["file"].closed)
        self.assertFalse(Path(asset["file"].name).exists())
        self.assertNotIn("body", proof["manifest"])
        self.assertNotIn("assets", proof["manifest"])

    def test_unchanged_release_checks_public_bytes_without_upstream_download_or_put(self):
        proposed = manifest()
        transport = Transport([
            json_response(upstream()), json_response(asdict(proposed)),
            Response(ARCHIVE), json_response(asdict(proposed)),
        ])
        proof = sync_mirror(transport, TOKEN)
        self.assertEqual("unchanged", proof["status"])
        self.assertFalse(proof["sourceDownloaded"])
        self.assertTrue(proof["publicArchiveSha256Verified"])
        self.assertTrue(all(call["method"] == "GET" for call in transport.calls))
        self.assertEqual(proposed.downloadUrl, transport.calls[2]["url"])

    def test_only_published_stable_release_and_one_exact_uploaded_asset_are_accepted(self):
        for changed in ({"draft": True}, {"draft": None}, {"prerelease": True},
                        {"tag_name": "v1.1.17\n"}, {"tag_name": "v١.١.١٧"},
                        {"tag_name": "v2147483648.1"}, {"id": True}, {"assets": None}):
            with self.subTest(changed=changed), self.assertRaises(MirrorSyncError):
                parse_upstream_release({**upstream(), **changed})
        for changed in ({"state": "new"}, {"name": "ventoy-1.1.17-windows.zip"},
                        {"size": True}, {"size": 0}, {"size": MAX_ARCHIVE_BYTES + 1},
                        {"browser_download_url": "https://github.com/another/repository/payload"},
                        {"browser_download_url": upstream()["assets"][0]["browser_download_url"] + "?mirror=evil"}):
            value = upstream()
            value["assets"][0].update(changed)
            with self.subTest(changed=changed), self.assertRaises(MirrorSyncError):
                parse_upstream_release(value)
        value = upstream()
        value["assets"].append(value["assets"][0].copy())
        with self.assertRaises(MirrorSyncError):
            parse_upstream_release(value)

    def test_checksum_must_be_upstream_published_and_unambiguous(self):
        value = upstream()
        value["assets"][0]["digest"] = None
        value["body"] = f"{SHA256}  ventoy-1.1.17-linux.tar.gz\n"
        self.assertEqual(SHA256, parse_upstream_release(value).manifest.sha256)
        for body in ("No checksum published", value["body"] + "c" * 64 + "  ventoy-1.1.17-linux.tar.gz\n"):
            with self.subTest(body=body), self.assertRaises(MirrorSyncError):
                parse_upstream_release({**value, "body": body})
        value = upstream()
        value["body"] = "c" * 64 + "  ventoy-1.1.17-linux.tar.gz\n"
        with self.assertRaises(MirrorSyncError):
            parse_upstream_release(value)
        value["assets"][0]["digest"] = "sha256:invalid"
        value["body"] = f"{SHA256}  ventoy-1.1.17-linux.tar.gz\n"
        with self.assertRaises(MirrorSyncError):
            parse_upstream_release(value)

    def test_metadata_rejects_duplicate_fields_nonfinite_values_and_oversized_bodies(self):
        for body in (b'{"size":1,"size":2}', b'{"size":NaN}', b'\xff'):
            with self.subTest(body=body), self.assertRaises(MirrorSyncError):
                decode_json(body)
        with self.assertRaises(MirrorSyncError):
            read_bounded(Response(b"a" * 17), 16)

    def test_bad_source_archive_never_reaches_an_authenticated_upload(self):
        for response in (Response(ARCHIVE[:-1]), Response(ARCHIVE + b"overflow"),
                         Response(b"x" * len(ARCHIVE)),
                         Response(ARCHIVE, headers={"Content-Length": str(len(ARCHIVE) + 1)}),
                         Response(ARCHIVE, headers={"Content-Encoding": "gzip"})):
            transport = Transport([json_response(upstream()), Response(status=404), response])
            with self.subTest(response=response), self.assertRaises(MirrorSyncError):
                sync_mirror(transport, TOKEN)
            self.assertEqual(3, len(transport.calls))
            self.assertTrue(all(call["method"] == "GET" for call in transport.calls))

    def test_wrong_upload_acknowledgement_or_corrupt_public_bytes_never_advertise_release(self):
        for problem in ("acknowledgement", "public-digest", "public-redirect"):
            responses = initial_sync_responses()
            if problem == "acknowledgement":
                responses[3] = json_response({"sha256": "c" * 64, "sizeBytes": len(ARCHIVE)})
            elif problem == "public-digest":
                responses[4] = Response(b"x" * len(ARCHIVE))
            else:
                responses[4] = Response(status=302, headers={"Location": "https://github.com"})
            transport = Transport(responses)
            with self.subTest(problem=problem), self.assertRaises(MirrorSyncError):
                sync_mirror(transport, TOKEN)
            self.assertFalse(any(call["url"].endswith("/v1/admin/ventoy/latest.json") for call in transport.calls))
            file = transport.calls[3]["file"]
            self.assertTrue(file.closed)
            self.assertFalse(Path(file.name).exists())

    def test_credentials_are_scoped_to_exact_api_or_admin_requests(self):
        transport = Transport(initial_sync_responses())
        sync_mirror(transport, TOKEN, github_token=GITHUB_TOKEN)
        for call in transport.calls:
            if call["url"] == UPSTREAM_API:
                self.assertEqual(f"Bearer {GITHUB_TOKEN}", call["headers"]["Authorization"])
            elif "/v1/admin/" in call["url"]:
                self.assertTrue(call["url"].startswith(MIRROR_ORIGIN + "/v1/admin/"))
                self.assertEqual(f"Bearer {TOKEN}", call["headers"]["Authorization"])
            else:
                self.assertNotIn("Authorization", call["headers"])
            for header in ("Cookie", "Referer", "Forwarded", "X-Forwarded-For", "CF-Connecting-IP", "X-Real-IP"):
                self.assertNotIn(header, call["headers"])

    def test_admin_redirect_is_not_followed_with_private_token(self):
        responses = initial_sync_responses()
        responses[3] = Response(status=307, headers={"Location": "https://evil.example/steal"})
        transport = Transport(responses)
        with self.assertRaises(MirrorSyncError):
            sync_mirror(transport, TOKEN)
        self.assertEqual(4, len(transport.calls))
        self.assertFalse(any("evil.example" in call["url"] for call in transport.calls))

    def test_source_redirects_use_allowlisted_hosts_and_never_forward_credentials(self):
        for location in ("http://release-assets.githubusercontent.com/payload",
                         "https://github.com.evil.example/payload", "https://github.com@evil.example/payload",
                         "https://release-assets.githubusercontent.com:443/payload", "https://127.0.0.1/payload",
                         "https://release-assets.githubusercontent.com/payload#fragment"):
            transport = Transport([Response(status=302, headers={"Location": location})])
            with self.subTest(location=location), self.assertRaises(MirrorSyncError):
                open_source_archive(transport, upstream()["assets"][0]["browser_download_url"])
            self.assertEqual(1, len(transport.calls))
        delivery_url = "https://release-assets.githubusercontent.com/payload?temporary=delivery"
        transport = Transport([Response(status=302, headers={"Location": delivery_url}), Response(ARCHIVE)])
        with open_source_archive(transport, upstream()["assets"][0]["browser_download_url"]) as response:
            self.assertEqual(ARCHIVE, response.read())
        self.assertEqual(delivery_url, transport.calls[1]["url"])
        self.assertTrue(all("Authorization" not in call["headers"] for call in transport.calls))
        self.assertEqual({"User-Agent", "Accept-Encoding", "Accept"}, set(transport.calls[1]["headers"]))

    def test_manifest_is_first_party_and_cannot_downgrade_or_replace_a_version(self):
        fields = asdict(manifest())
        for changed in ({"schemaVersion": True}, {"downloadUrl": "https://github.com/payload"},
                        {"downloadUrl": fields["downloadUrl"] + "?token=visitor"},
                        {"visitorId": "unexpected"}, {"sha256": SHA256.upper()}, {"sizeBytes": True}):
            with self.subTest(changed=changed), self.assertRaises(MirrorSyncError):
                parse_manifest({**fields, **changed})
        check_new_release(manifest(version="1.1.9"), manifest(version="1.1.17"))
        for current in (manifest(version="1.1.18"),
                        parse_manifest({**fields, "sizeBytes": fields["sizeBytes"] + 1})):
            with self.subTest(current=current), self.assertRaises(MirrorSyncError):
                check_new_release(current, manifest())

    def test_raw_network_errors_and_private_tokens_never_reach_output(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "proof.json"
            stderr = io.StringIO()
            with patch.dict(os.environ, {"VENDROID_MIRROR_PUBLISH_TOKEN": TOKEN, "GH_TOKEN": GITHUB_TOKEN}), \
                 patch("sys.argv", ["sync.py", "--commit", "a" * 40, "--output", str(output)]), \
                 patch("sync.sync_mirror", side_effect=RuntimeError(TOKEN + GITHUB_TOKEN)), \
                 contextlib.redirect_stderr(stderr):
                self.assertEqual(1, main())
            self.assertNotIn(TOKEN, stderr.getvalue())
            self.assertNotIn(GITHUB_TOKEN, stderr.getvalue())
            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
