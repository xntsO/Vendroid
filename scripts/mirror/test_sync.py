import contextlib
from dataclasses import asdict
import gzip
import hashlib
import io
import json
import os
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch

from sync import (
    BUSYBOX_DOWNLOAD_HOSTS, MAX_ARCHIVE_BYTES, MAX_SOURCE_ARCHIVES, MIRROR_ORIGIN, SOURCE_DOWNLOAD_HOSTS,
    UPSTREAM_API, UPSTREAM_COMMIT_API, UPSTREAM_SOURCE, HttpTransport, MirrorSyncError, SourceManifest,
    asset_path, check_new_release, decode_json, main, mirrored, open_source_archive, parse_manifest,
    parse_source_manifest, parse_upstream_release, read_bounded, source_path, sync_mirror,
)


def digest(data):
    return hashlib.sha256(data).hexdigest()


ARCHIVE = b"upstream release archive fixture\x00\xff"
SHA256 = digest(ARCHIVE)
TOKEN = "publish-token-" + "a" * 48
GITHUB_TOKEN = "github-token-" + "b" * 48
COMMIT = "c" * 40
BUSYBOX_BINARY = b"busybox binary shipped by Ventoy"
BUSYBOX_SOURCE = b"busybox upstream source fixture"
BUSYBOX_URL = "https://busybox.net/downloads/busybox-1.31.0.tar.bz2"
BINARY_PATH = "IMG/cpio_x86/ventoy/busybox/busybox64.xz"
LOCK = {"busybox": {
    "sources": {"1.31.0": {"name": "busybox-1.31.0.tar.bz2", "url": BUSYBOX_URL,
                           "size": len(BUSYBOX_SOURCE), "sha256": digest(BUSYBOX_SOURCE)}},
    "binaries": {BINARY_PATH: {"sha256": digest(BUSYBOX_BINARY), "version": "1.31.0", "evidence": "version-string"}},
    "other_tools": [],
}}


def source_tree(*, version="1.1.17", commit=COMMIT, binary=BUSYBOX_BINARY, extra=None):
    """A generated source archive: GitHub records the commit in a pax header."""
    files = {"README.md": b"Ventoy", BINARY_PATH: binary, **(extra or {})}
    buffer = io.BytesIO()
    with gzip.GzipFile(fileobj=buffer, mode="wb", mtime=0) as compressed, \
            tarfile.open(fileobj=compressed, mode="w", format=tarfile.PAX_FORMAT,
                         pax_headers={"comment": commit}) as archive:
        for path, data in sorted(files.items()):
            info = tarfile.TarInfo(f"Ventoy-{version}/{path}")
            info.size = len(data)
            archive.addfile(info, io.BytesIO(data))
    return buffer.getvalue()


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


def source_manifest(*, version="1.1.17", tree=None, commit=COMMIT):
    tree = source_tree(version=version) if tree is None else tree
    return SourceManifest(1, version, f"v{version}", commit, (
        mirrored(f"ventoy-{version}-source.tar.gz", len(tree), digest(tree)),
        mirrored("busybox-1.31.0.tar.bz2", len(BUSYBOX_SOURCE), digest(BUSYBOX_SOURCE)),
    ))


def plain(value):
    return json.loads(json.dumps(asdict(value)))


class Response(io.BytesIO):
    def __init__(self, body=b"", *, status=200, headers=None):
        super().__init__(body)
        self.status = status
        self.headers = headers or {}


def json_response(value, *, status=200):
    return Response(json.dumps(value).encode(), status=status)


def acknowledgement(data):
    return json_response({"sha256": digest(data), "sizeBytes": len(data)})


def head(data):
    return Response(status=200, headers={"Content-Length": str(len(data))})


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


def flow(*, version="1.1.17", has_release=False, has_source=False, has_busybox=False, tree=None):
    """The named responses of one sync, in request order."""
    tree = source_tree(version=version) if tree is None else tree
    proposed, source = manifest(version=version), source_manifest(version=version, tree=tree)
    steps = [("release", json_response(upstream(version=version))),
             ("latest", json_response(asdict(proposed)) if has_release else Response(status=404)),
             ("source-list", json_response(asdict(source)) if has_source else Response(status=404))]
    if not has_source:
        steps += [("tag-commit", Response(COMMIT.encode() + b"\n")), ("source-download", Response(tree)),
                  ("source-upload", acknowledgement(tree)), ("source-public", Response(tree)),
                  ("busybox-stored", head(BUSYBOX_SOURCE) if has_busybox else Response(status=404))]
        if not has_busybox:
            steps += [("busybox-download", Response(BUSYBOX_SOURCE)),
                      ("busybox-upload", acknowledgement(BUSYBOX_SOURCE)),
                      ("busybox-public", Response(BUSYBOX_SOURCE))]
        steps += [("source-list-publish", json_response(asdict(source))),
                  ("source-list-readback", json_response(asdict(source)))]
    steps += [("source-available", head(tree)), ("busybox-available", head(BUSYBOX_SOURCE))]
    if not has_release:
        steps += [("download", Response(ARCHIVE)), ("upload", acknowledgement(ARCHIVE))]
    steps.append(("public", Response(ARCHIVE)))
    if not has_release:
        steps.append(("publish", json_response(asdict(proposed))))
    steps.append(("readback", json_response(asdict(proposed))))
    names = [name for name, _ in steps]
    assert len(names) == len(set(names))
    return dict(steps)


def run(steps, **options):
    """Run one sync and return its proof with each request under the name of its step."""
    transport = Transport(steps.values())
    try:
        proof = sync_mirror(transport, TOKEN, lock=LOCK, **options)
    finally:
        transport.named = dict(zip(steps, transport.calls))
    return proof, transport


def failed(test, steps, message=None):
    transport = Transport(steps.values())
    with test.assertRaises(MirrorSyncError) as raised:
        sync_mirror(transport, TOKEN, lock=LOCK)
    if message:
        test.assertIn(message, str(raised.exception))
    transport.named = dict(zip(steps, transport.calls))
    return transport


class MirrorSyncTest(unittest.TestCase):
    def test_http_requests_identify_the_sync_job_without_persisting_credentials(self):
        delivery_url = "https://release-assets.githubusercontent.com/payload?temporary=delivery"
        steps = flow()
        responses = []
        for name, response in steps.items():
            if name == "download":
                responses.append(Response(status=302, headers={"Location": delivery_url}))
            responses.append(response)
        with patch("sync.build_opener") as build_opener:
            opener = build_opener.return_value
            opener.open.side_effect = responses
            sync_mirror(HttpTransport(), TOKEN, github_token=GITHUB_TOKEN, lock=LOCK)
        self.assertEqual(len(steps) + 1, opener.open.call_count)
        for call in opener.open.call_args_list:
            request = call.args[0]
            self.assertEqual("Vendroid-Mirror/1.0", request.get_header("User-agent"))
            if request.full_url == UPSTREAM_API or request.full_url.startswith(UPSTREAM_COMMIT_API):
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
        steps = flow()
        proof, transport = run(steps, github_token=GITHUB_TOKEN)
        proposed = manifest()
        self.assertEqual("published", proof["status"])
        self.assertTrue(proof["publicArchiveSha256Verified"])
        self.assertTrue(proof["publicManifestReadbackVerified"])
        self.assertEqual(asdict(proposed), proof["manifest"])
        self.assertEqual(len(steps), len(transport.calls))
        self.assertEqual(list(steps), list(transport.named))
        self.assertEqual(proposed.downloadUrl, transport.named["public"]["url"])
        publication = transport.named["publish"]
        self.assertEqual("PUT", publication["method"])
        self.assertEqual(MIRROR_ORIGIN + "/v1/admin/ventoy/latest.json", publication["url"])
        self.assertEqual(asdict(proposed), decode_json(publication["body"]))
        self.assertEqual(str(len(publication["body"])), publication["headers"]["Content-Length"])
        asset = transport.named["upload"]
        self.assertEqual(ARCHIVE, asset["body"])
        self.assertEqual(str(len(ARCHIVE)), asset["headers"]["Content-Length"])
        self.assertEqual(MIRROR_ORIGIN + asset_path(SHA256, proposed.assetName, admin=True), asset["url"])
        self.assertTrue(asset["file"].closed)
        self.assertFalse(Path(asset["file"].name).exists())
        self.assertNotIn("body", proof["manifest"])
        self.assertNotIn("assets", proof["manifest"])

    def test_source_is_mirrored_and_listed_before_the_release_is_advertised(self):
        steps = flow()
        proof, transport = run(steps, github_token=GITHUB_TOKEN)
        tree, source, calls = source_tree(), source_manifest(), transport.named
        self.assertEqual(plain(source), proof["source"])
        self.assertEqual("published", proof["sourceStatus"])
        self.assertTrue(proof["sourceTreeVerifiedAgainstTag"])
        self.assertTrue(proof["sourceArchivesAvailable"])
        self.assertEqual(UPSTREAM_COMMIT_API + "v1.1.17", calls["tag-commit"]["url"])
        self.assertEqual("application/vnd.github.sha", calls["tag-commit"]["headers"]["Accept"])
        self.assertEqual(UPSTREAM_SOURCE + "v1.1.17.tar.gz", calls["source-download"]["url"])
        self.assertEqual(BUSYBOX_URL, calls["busybox-download"]["url"])
        for name, data, archive in (("source-upload", tree, source.archives[0]),
                                    ("busybox-upload", BUSYBOX_SOURCE, source.archives[1])):
            upload = calls[name]
            self.assertEqual(("PUT", data), (upload["method"], upload["body"]))
            self.assertEqual(MIRROR_ORIGIN + asset_path(archive.sha256, archive.assetName, admin=True), upload["url"])
            self.assertEqual(str(len(data)), upload["headers"]["Content-Length"])
            self.assertTrue(upload["file"].closed)
            self.assertFalse(Path(upload["file"].name).exists())
        self.assertEqual(source.archives[0].downloadUrl, calls["source-public"]["url"])
        listing = calls["source-list-publish"]
        self.assertEqual(("PUT", MIRROR_ORIGIN + source_path("1.1.17", admin=True)), (listing["method"], listing["url"]))
        self.assertEqual(plain(source), decode_json(listing["body"]))
        self.assertEqual(MIRROR_ORIGIN + "/v1/ventoy/source/1.1.17.json", calls["source-list-readback"]["url"])
        self.assertEqual(["HEAD", "HEAD"], [calls[name]["method"] for name in ("source-available", "busybox-available")])
        order = list(calls)
        self.assertLess(order.index("source-list-readback"), order.index("download"))
        self.assertLess(order.index("busybox-available"), order.index("publish"))

    def test_unchanged_release_checks_public_bytes_without_upstream_download_or_put(self):
        proposed = manifest()
        steps = flow(has_release=True, has_source=True)
        proof, transport = run(steps)
        self.assertEqual("unchanged", proof["status"])
        self.assertEqual("unchanged", proof["sourceStatus"])
        self.assertFalse(proof["sourceDownloaded"])
        self.assertFalse(proof["sourceTreeVerifiedAgainstTag"])
        self.assertTrue(proof["publicArchiveSha256Verified"])
        self.assertEqual(["release", "latest", "source-list", "source-available", "busybox-available",
                          "public", "readback"], list(transport.named))
        self.assertTrue(all(call["method"] in ("GET", "HEAD") for call in transport.calls))
        self.assertEqual(proposed.downloadUrl, transport.named["public"]["url"])

    def test_an_already_advertised_release_gains_its_source_on_the_next_run(self):
        steps = flow(has_release=True)
        proof, transport = run(steps)
        self.assertEqual(("unchanged", "published"), (proof["status"], proof["sourceStatus"]))
        self.assertEqual(list(steps), list(transport.named))
        writes = [call["url"] for call in transport.calls if call["method"] == "PUT"]
        self.assertEqual(3, len(writes))
        self.assertFalse(any(url.endswith("/v1/admin/ventoy/latest.json") or "-linux.tar.gz" in url for url in writes))

    def test_stored_busybox_source_is_reused_without_contacting_busybox_net(self):
        steps = flow(has_busybox=True)
        proof, transport = run(steps)
        self.assertEqual(plain(source_manifest()), proof["source"])
        self.assertEqual(list(steps), list(transport.named))
        self.assertFalse(any("busybox.net" in call["url"] for call in transport.calls))
        self.assertEqual("HEAD", transport.named["busybox-stored"]["method"])

    def test_source_that_does_not_match_the_tag_or_the_pinned_busybox_is_never_uploaded(self):
        cases = {
            "another commit": ({"source-download": Response(source_tree(commit="d" * 40))}, "pinned commit"),
            "another version": ({"source-download": Response(source_tree(version="1.1.16"))}, "unexpected root"),
            "rebuilt busybox": ({"source-download": Response(source_tree(binary=b"another BusyBox release"))},
                                "identify its BusyBox version again"),
            "unidentified busybox": ({"source-download": Response(source_tree(
                extra={"IMG/cpio_arm64/ventoy/busybox/busyboxaa64.xz": b"new"}))}, "Unidentified files"),
            "not an archive": ({"source-download": Response(b"<html>rate limited</html>")}, "unreadable"),
            "compressed transfer": ({"source-download": Response(source_tree(), headers={"Content-Encoding": "gzip"})},
                                    "transfer encoding"),
            "unknown tag": ({"tag-commit": Response(status=404)}, "HTTP 404"),
            "branch name": ({"tag-commit": Response(b"main")}, "malformed"),
            "oversized commit": ({"tag-commit": Response(b"c" * 65)}, "size limit"),
        }
        for name, (changed, message) in cases.items():
            with self.subTest(name=name):
                transport = failed(self, {**flow(), **changed}, message)
                self.assertTrue(all(call["method"] == "GET" for call in transport.calls))
                self.assertFalse(any("/v1/admin/" in call["url"] for call in transport.calls))
                self.assertNotIn("download", transport.named)

    def test_wrong_busybox_bytes_or_source_acknowledgements_never_advertise_the_release(self):
        other = source_manifest(commit="d" * 40)
        cases = {
            "busybox bytes": {"busybox-download": Response(b"x" * len(BUSYBOX_SOURCE))},
            "busybox acknowledgement": {"busybox-upload": json_response({"sha256": "c" * 64, "sizeBytes": 1})},
            "source acknowledgement": {"source-upload": json_response({"sha256": "c" * 64, "sizeBytes": 1})},
            "source public bytes": {"source-public": Response(b"x" * len(source_tree()))},
            "source public redirect": {"source-public": Response(status=302, headers={"Location": "https://github.com"})},
            "busybox stored size": {"busybox-stored": Response(status=200, headers={"Content-Length": "1"})},
            "list acknowledgement": {"source-list-publish": json_response(asdict(other))},
            "list refused": {"source-list-publish": json_response({"error": "conflict"}, status=409)},
            "list readback": {"source-list-readback": json_response(asdict(other))},
            "list not public": {"source-list-readback": Response(status=404)},
            "source missing": {"source-available": Response(status=404)},
            "busybox truncated": {"busybox-available": Response(status=200, headers={"Content-Length": "1"})},
        }
        for name, changed in cases.items():
            with self.subTest(name=name):
                transport = failed(self, {**flow(), **changed})
                urls = [call["url"] for call in transport.calls]
                self.assertFalse(any(url.endswith("/v1/admin/ventoy/latest.json") for url in urls))
                self.assertFalse(any("-linux.tar.gz" in url for url in urls))
                for call in transport.calls:
                    if call["file"] is not None:
                        self.assertTrue(call["file"].closed)
                        self.assertFalse(Path(call["file"].name).exists())

    def test_listed_source_that_is_missing_or_resized_blocks_an_existing_release_without_writes(self):
        for changed in ({"source-available": Response(status=404)},
                        {"busybox-available": Response(status=200, headers={"Content-Length": "1"})},
                        {"source-list": json_response(asdict(source_manifest(version="1.1.16")))},
                        {"source-list": Response(status=302, headers={"Location": "https://github.com"})}):
            with self.subTest(changed=list(changed)):
                transport = failed(self, {**flow(has_release=True, has_source=True), **changed})
                self.assertTrue(all(call["method"] in ("GET", "HEAD") for call in transport.calls))
                self.assertNotIn("public", transport.named)

    def test_source_list_is_first_party_and_belongs_to_one_release(self):
        fields = plain(source_manifest())
        self.assertEqual(source_manifest(), parse_source_manifest(fields, "1.1.17"))
        ventoy, busybox = fields["archives"]
        linux = asdict(mirrored("ventoy-1.1.17-linux.tar.gz", len(ARCHIVE), SHA256))
        many = [asdict(mirrored(f"busybox-1.{index}.0.tar.bz2", 1, "a" * 64)) for index in range(MAX_SOURCE_ARCHIVES)]
        for changed in ({"schemaVersion": True}, {"schemaVersion": 2}, {"version": "1.1.18"}, {"tag": "main"},
                        {"commit": "main"}, {"commit": COMMIT.upper()}, {"visitorId": "unexpected"},
                        {"archives": []}, {"archives": None}, {"archives": [None]}, {"archives": [busybox, ventoy]},
                        {"archives": [linux]}, {"archives": [ventoy, linux]}, {"archives": [ventoy, busybox, busybox]},
                        {"archives": [ventoy] + many},
                        {"archives": [{**ventoy, "downloadUrl": UPSTREAM_SOURCE + "v1.1.17.tar.gz"}]},
                        {"archives": [{**ventoy, "downloadUrl": ventoy["downloadUrl"] + "?token=visitor"}]},
                        {"archives": [{**ventoy, "sha256": ventoy["sha256"].upper()}]},
                        {"archives": [{**ventoy, "sizeBytes": True}]},
                        {"archives": [{**ventoy, "sizeBytes": MAX_ARCHIVE_BYTES + 1}]},
                        {"archives": [{**ventoy, "visitorId": "unexpected"}]},
                        {"archives": [ventoy, {**busybox, "assetName": "busybox-latest.tar.bz2"}]}):
            with self.subTest(changed=changed), self.assertRaises(MirrorSyncError):
                parse_source_manifest({**fields, **changed}, "1.1.17")
        with self.assertRaises(MirrorSyncError):
            parse_source_manifest(fields, "1.1.18")

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
            with self.subTest(response=response):
                transport = failed(self, {**flow(has_source=True), "download": response})
                self.assertEqual("download", list(transport.named)[-1])
                self.assertTrue(all(call["method"] in ("GET", "HEAD") for call in transport.calls))

    def test_wrong_upload_acknowledgement_or_corrupt_public_bytes_never_advertise_release(self):
        for problem in ("acknowledgement", "public-digest", "public-redirect"):
            steps = flow(has_source=True)
            if problem == "acknowledgement":
                steps["upload"] = json_response({"sha256": "c" * 64, "sizeBytes": len(ARCHIVE)})
            elif problem == "public-digest":
                steps["public"] = Response(b"x" * len(ARCHIVE))
            else:
                steps["public"] = Response(status=302, headers={"Location": "https://github.com"})
            with self.subTest(problem=problem):
                transport = failed(self, steps)
                self.assertFalse(any(call["url"].endswith("/v1/admin/ventoy/latest.json") for call in transport.calls))
                file = transport.named["upload"]["file"]
                self.assertTrue(file.closed)
                self.assertFalse(Path(file.name).exists())

    def test_credentials_are_scoped_to_exact_api_or_admin_requests(self):
        _, transport = run(flow(), github_token=GITHUB_TOKEN)
        api, admin = 0, 0
        for call in transport.calls:
            if call["url"] == UPSTREAM_API or call["url"].startswith(UPSTREAM_COMMIT_API):
                api += 1
                self.assertEqual(f"Bearer {GITHUB_TOKEN}", call["headers"]["Authorization"])
            elif "/v1/admin/" in call["url"]:
                admin += 1
                self.assertTrue(call["url"].startswith(MIRROR_ORIGIN + "/v1/admin/"))
                self.assertEqual(f"Bearer {TOKEN}", call["headers"]["Authorization"])
            else:
                self.assertNotIn("Authorization", call["headers"])
            for header in ("Cookie", "Referer", "Forwarded", "X-Forwarded-For", "CF-Connecting-IP", "X-Real-IP"):
                self.assertNotIn(header, call["headers"])
        self.assertEqual((2, 5), (api, admin))

    def test_admin_redirect_is_not_followed_with_private_token(self):
        for step in ("source-upload", "busybox-upload", "source-list-publish", "upload", "publish"):
            steps = flow()
            steps[step] = Response(status=307, headers={"Location": "https://evil.example/steal"})
            with self.subTest(step=step):
                transport = failed(self, steps)
                self.assertEqual(step, list(transport.named)[-1])
                self.assertFalse(any("evil.example" in call["url"] for call in transport.calls))

    def test_source_redirects_use_allowlisted_hosts_and_never_forward_credentials(self):
        for location in ("http://release-assets.githubusercontent.com/payload",
                         "https://github.com.evil.example/payload", "https://github.com@evil.example/payload",
                         "https://release-assets.githubusercontent.com:443/payload", "https://127.0.0.1/payload",
                         "https://codeload.github.com/payload", "https://busybox.net/payload",
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

    def test_generated_source_and_busybox_downloads_stay_on_their_own_hosts(self):
        generated = "https://codeload.github.com/ventoy/Ventoy/tar.gz/refs/tags/v1.1.17"
        transport = Transport([Response(status=302, headers={"Location": generated}), Response(b"tree")])
        with open_source_archive(transport, UPSTREAM_SOURCE + "v1.1.17.tar.gz", SOURCE_DOWNLOAD_HOSTS) as response:
            self.assertEqual(b"tree", response.read())
        self.assertEqual(generated, transport.calls[1]["url"])
        for url, hosts, location in (
            (UPSTREAM_SOURCE + "v1.1.17.tar.gz", SOURCE_DOWNLOAD_HOSTS, "https://busybox.net/tree.tar.gz"),
            (UPSTREAM_SOURCE + "v1.1.17.tar.gz", SOURCE_DOWNLOAD_HOSTS, "https://release-assets.githubusercontent.com/x"),
            (BUSYBOX_URL, BUSYBOX_DOWNLOAD_HOSTS, "https://github.com/mirror/busybox"),
            (BUSYBOX_URL, BUSYBOX_DOWNLOAD_HOSTS, "http://busybox.net/downloads/busybox-1.31.0.tar.bz2"),
        ):
            transport = Transport([Response(status=302, headers={"Location": location})])
            with self.subTest(location=location), self.assertRaises(MirrorSyncError):
                open_source_archive(transport, url, hosts)
            self.assertEqual(1, len(transport.calls))
        with self.assertRaises(MirrorSyncError):
            open_source_archive(Transport([]), BUSYBOX_URL, SOURCE_DOWNLOAD_HOSTS)

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

    def test_checked_in_source_lock_is_usable_by_the_sync(self):
        from ventoy_lock import load_lock
        busybox = load_lock()["busybox"]
        self.assertTrue(busybox["sources"] and busybox["binaries"])
        for pinned in busybox["sources"].values():
            self.assertTrue(pinned["url"].startswith("https://busybox.net/downloads/"))
            self.assertEqual(mirrored(pinned["name"], pinned["size"], pinned["sha256"]).downloadUrl,
                             MIRROR_ORIGIN + asset_path(pinned["sha256"], pinned["name"]))
        self.assertLessEqual(1 + len(busybox["sources"]), MAX_SOURCE_ARCHIVES)


if __name__ == "__main__":
    unittest.main()
