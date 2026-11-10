import test from "node:test";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { handleMirrorRequest, MAX_SOURCE_ARCHIVES, ORIGIN, parseManifest, parseSourceManifest } from "./handler.mjs";

const token = "fixture-publisher-token-00000000000000000000";
const digest = data => createHash("sha256").update(data).digest("hex");
const bytes = new TextEncoder().encode("verified archive fixture");
const sha256 = digest(bytes);
const sourceBytes = new TextEncoder().encode("verified Ventoy source fixture");
const busyboxBytes = new TextEncoder().encode("verified BusyBox source fixture");
const commit = "c".repeat(40);
const manifest = (version = "1.1.17") => ({ schemaVersion: 1, version,
  assetName: `ventoy-${version}-linux.tar.gz`, sizeBytes: bytes.length, sha256,
  downloadUrl: `${ORIGIN}/v1/assets/${sha256}/ventoy-${version}-linux.tar.gz`,
});
const archive = (assetName, data) => ({ assetName, sizeBytes: data.length, sha256: digest(data),
  downloadUrl: `${ORIGIN}/v1/assets/${digest(data)}/${assetName}`,
});
const sourceArchive = (version = "1.1.17") => archive(`ventoy-${version}-source.tar.gz`, sourceBytes);
const busybox = (release = "1.31.0") => archive(`busybox-${release}.tar.bz2`, busyboxBytes);
const sourceManifest = (version = "1.1.17") => ({ schemaVersion: 1, version, tag: `v${version}`, commit,
  archives: [sourceArchive(version), busybox()],
});

// The fixture models the R2 conditional-write/checksum contract. Hosted readback
// separately verifies that the real provider enforces the streaming checksum.
class Bucket {
  objects = new Map();
  revision = 0;
  publicationConflict = false;
  failWrites = false;
  afterPut = null;
  async head(key) { return this.copy(key); }
  async get(key) { return this.copy(key); }
  copy(key) {
    const value = this.objects.get(key);
    if (!value) return null;
    return { ...value, body: new Response(value.bytes).body,
      json: async () => JSON.parse(new TextDecoder().decode(value.bytes)) };
  }
  async put(key, body, options) {
    if (this.failWrites) throw new Error("storage unavailable");
    const previous = this.objects.get(key);
    if (this.publicationConflict && key === "ventoy/latest.json") return null;
    if (options.onlyIf?.get("If-None-Match") === "*" && previous) return null;
    if (options.onlyIf?.has("If-Match") && options.onlyIf.get("If-Match") !== previous?.httpEtag) return null;
    const data = new Uint8Array(await new Response(body).arrayBuffer());
    if (options.sha256 && createHash("sha256").update(data).digest("hex") !== options.sha256) throw new Error("checksum mismatch");
    const object = { bytes: data, size: data.length, customMetadata: options.customMetadata,
      httpMetadata: options.httpMetadata, httpEtag: `"revision-${++this.revision}"` };
    this.objects.set(key, object);
    // Other requests can see a committed R2 object before its uploader resumes.
    if (this.afterPut) await this.afterPut(key, object);
    return this.copy(key);
  }
  async delete(key) { this.objects.delete(key); }
}

function request(path, { method = "GET", body, auth = token, headers = {} } = {}) {
  return new Request(`${ORIGIN}${path}`, { method, body, headers: {
    ...(auth ? { Authorization: `Bearer ${auth}` } : {}), ...headers,
  } });
}
const invoke = (bucket, req) => handleMirrorRequest(req, { bucket, publishToken: token });
const upload = (bucket, entry = manifest(), body = bytes, auth = token) => invoke(bucket,
  request(`/v1/admin/assets/${entry.sha256}/${entry.assetName}`, {
    method: "PUT", body, auth, headers: { "Content-Length": String(entry.sizeBytes) },
  }));
const publish = (bucket, entry = manifest(), auth = token) => invoke(bucket,
  request("/v1/admin/ventoy/latest.json", { method: "PUT", body: JSON.stringify(entry), auth }));
const publishSource = (bucket, entry = sourceManifest(), auth = token, version = entry.version) => invoke(bucket,
  request(`/v1/admin/ventoy/source/${version}.json`, { method: "PUT", body: JSON.stringify(entry), auth }));
const readSource = (bucket, version = "1.1.17", method = "GET") => invoke(bucket,
  request(`/v1/ventoy/source/${version}.json`, { method, auth: null }));
// Publishes the source that every advertised release needs.
async function seedSource(bucket, version = "1.1.17") {
  await upload(bucket, sourceArchive(version), sourceBytes);
  await upload(bucket, busybox(), busyboxBytes);
  assert.equal((await publishSource(bucket, sourceManifest(version))).status, 200);
}

test("verified upload, atomic publication and anonymous byte-for-byte readback", async () => {
  const bucket = new Bucket();
  assert.equal((await upload(bucket)).status, 201);
  await seedSource(bucket);
  assert.deepEqual(await (await publish(bucket)).json(), manifest());
  const latest = await invoke(bucket, request("/v1/ventoy/latest.json", { auth: null }));
  assert.equal(latest.status, 200);
  assert.equal(latest.headers.get("Set-Cookie"), null);
  assert.deepEqual(await latest.json(), manifest());
  const asset = await invoke(bucket, new Request(manifest().downloadUrl));
  assert.equal(asset.status, 200);
  assert.deepEqual(new Uint8Array(await asset.arrayBuffer()), bytes);
  const head = await invoke(bucket, new Request(manifest().downloadUrl, { method: "HEAD" }));
  assert.equal(head.headers.get("Content-Length"), String(bytes.length));
  assert.equal(await head.text(), "");
});

test("anonymous or incorrect publisher credentials cannot write", async () => {
  for (const auth of [null, "wrong-token"]) {
    const bucket = new Bucket();
    assert.equal((await upload(bucket, manifest(), bytes, auth)).status, 401);
    assert.equal((await publish(bucket, manifest(), auth)).status, 401);
    assert.equal((await upload(bucket, sourceArchive(), sourceBytes, auth)).status, 401);
    assert.equal((await publishSource(bucket, sourceManifest(), auth)).status, 401);
    assert.equal(bucket.objects.size, 0);
  }
});

test("bad checksum and truncated uploads cannot become public releases", async () => {
  for (const body of [new Uint8Array(bytes.length), bytes.slice(0, -1)]) {
    const bucket = new Bucket();
    assert.equal((await upload(bucket, manifest(), body)).status, 422);
    assert.equal((await publish(bucket)).status, 409);
    assert.equal(bucket.objects.size, 0);
  }
});

test("declared size mismatch is removed and missing Content-Length is rejected", async () => {
  const bucket = new Bucket();
  assert.equal((await upload(bucket, { ...manifest(), sizeBytes: bytes.length + 1 })).status, 422);
  assert.equal(bucket.objects.size, 0);
  assert.equal((await invoke(bucket, request(`/v1/admin/assets/${sha256}/${manifest().assetName}`,
    { method: "PUT", body: bytes }))).status, 411);
});

test("interleaved reads and publication cannot expose a size-mismatched committed upload", async () => {
  const bucket = new Bucket();
  await seedSource(bucket); await seedSource(bucket, "1.1.18");
  await upload(bucket); await publish(bucket);
  const proposed = manifest("1.1.18");
  const mismatched = { ...proposed, sizeBytes: bytes.length + 1 };
  let observed = false;
  bucket.afterPut = async (key) => {
    if (key !== `assets/${proposed.sha256}/${proposed.assetName}`) return;
    observed = true;
    bucket.afterPut = null;
    assert.equal((await invoke(bucket, new Request(proposed.downloadUrl))).status, 404);
    assert.equal((await invoke(bucket, new Request(proposed.downloadUrl, { method: "HEAD" }))).status, 404);
    assert.equal((await publish(bucket, proposed)).status, 409);
    assert.equal((await publish(bucket, mismatched)).status, 409);
    const latest = await invoke(bucket, request("/v1/ventoy/latest.json", { auth: null }));
    assert.deepEqual(await latest.json(), manifest());
  };
  assert.equal((await upload(bucket, mismatched)).status, 422);
  assert.equal(observed, true, "The fixture must interleave before the upload size check");
  assert.equal(bucket.objects.has(`assets/${proposed.sha256}/${proposed.assetName}`), false);
  assert.deepEqual(await (await invoke(bucket, request("/v1/ventoy/latest.json", { auth: null }))).json(), manifest());
  // A subsequent correct upload can publish after the rejected object is removed.
  assert.equal((await upload(bucket, proposed)).status, 201);
  assert.equal((await publish(bucket, proposed)).status, 200);
});

test("schema, external URLs, credential URLs and disguised asset names fail at publication", async () => {
  const bucket = new Bucket();
  await upload(bucket); await seedSource(bucket);
  for (const entry of [
    { ...manifest(), schemaVersion: 2 }, { ...manifest(), sha256: "A".repeat(64) },
    { ...manifest(), downloadUrl: manifest().downloadUrl.replace(ORIGIN, "https://github.com") },
    { ...manifest(), downloadUrl: manifest().downloadUrl.replace("https://", "https://user@") },
    { ...manifest(), assetName: "../payload.tar.gz" }, { ...manifest(), version: "1.1.17/other" },
    { ...manifest(), sizeBytes: 256 * 1024 * 1024 + 1 },
    // The source archive of a release is not its installable payload.
    { ...manifest(), ...sourceArchive() },
  ]) assert.equal((await publish(bucket, entry)).status, 400);
  assert.equal(bucket.objects.has("ventoy/latest.json"), false);
});

test("a newer release replaces latest but downgrade and same-version changes do not", async () => {
  const bucket = new Bucket();
  await seedSource(bucket); await seedSource(bucket, "1.1.18");
  await upload(bucket); await publish(bucket);
  await upload(bucket, manifest("1.1.18"));
  assert.equal((await publish(bucket, manifest("1.1.18"))).status, 200);
  assert.equal((await publish(bucket)).status, 409);
  const changed = { ...manifest("1.1.18"), sha256: "0".repeat(64) };
  changed.downloadUrl = `${ORIGIN}/v1/assets/${changed.sha256}/${changed.assetName}`;
  bucket.objects.set(`assets/${changed.sha256}/${changed.assetName}`, {
    bytes, size: bytes.length, customMetadata: {
      sha256: changed.sha256, assetName: changed.assetName, sizeBytes: String(bytes.length), verified: "sha256",
    }, httpEtag: '"fixture"',
  });
  assert.equal((await publish(bucket, changed)).status, 409);
  assert.equal((await (await bucket.get("ventoy/latest.json")).json()).version, "1.1.18");
});

test("concurrent or failed publication leaves the previous release available", async () => {
  const bucket = new Bucket();
  await seedSource(bucket); await seedSource(bucket, "1.1.18");
  await upload(bucket); await publish(bucket); await upload(bucket, manifest("1.1.18"));
  bucket.publicationConflict = true;
  assert.equal((await publish(bucket, manifest("1.1.18"))).status, 409);
  bucket.publicationConflict = false; bucket.failWrites = true;
  assert.equal((await publish(bucket, manifest("1.1.18"))).status, 503);
  assert.equal((await (await bucket.get("ventoy/latest.json")).json()).version, "1.1.17");
});

test("publication is idempotent and strips unrelated input fields", async () => {
  const bucket = new Bucket();
  await seedSource(bucket);
  await upload(bucket); await publish(bucket);
  const revision = bucket.revision;
  assert.equal((await upload(bucket)).status, 200);
  assert.deepEqual(await (await publish(bucket, { ...manifest(), visitor: "discard-me" })).json(), manifest());
  assert.deepEqual(await (await publishSource(bucket, { ...sourceManifest(), visitor: "discard-me" })).json(), sourceManifest());
  assert.equal(bucket.revision, revision);
  assert.equal("visitor" in parseManifest({ ...manifest(), visitor: "discard-me" }), false);
  assert.equal("visitor" in parseSourceManifest({ ...sourceManifest(), visitor: "discard-me" }), false);
});

test("oversized metadata, unknown paths, query strings and unverified objects are rejected", async () => {
  const bucket = new Bucket();
  assert.equal((await invoke(bucket, request("/v1/admin/ventoy/latest.json", {
    method: "PUT", body: " ".repeat(4097),
  }))).status, 413);
  assert.equal((await invoke(bucket, request("/v1/admin/ventoy/source/1.1.17.json", {
    method: "PUT", body: " ".repeat(8193),
  }))).status, 413);
  assert.equal((await invoke(bucket, request("/v1/ventoy/latest.json?user=123"))).status, 400);
  assert.equal((await invoke(bucket, request("/v1/assets/anything"))).status, 404);
  assert.equal((await invoke(bucket, request(`/v1/assets/${sha256}/ventoy-1.1.17-windows.zip`))).status, 404);
  assert.equal((await invoke(bucket, request(`/v1/admin/assets/${sha256}/ventoy-1.1.17-windows.zip`, {
    method: "PUT", body: bytes, headers: { "Content-Length": String(bytes.length) },
  }))).status, 404);
  assert.equal((await invoke(bucket, request("/v1/ventoy/latest.json"))).status, 404);
  bucket.objects.set(`assets/${sha256}/${manifest().assetName}`, { bytes, size: bytes.length, customMetadata: {} });
  assert.equal((await invoke(bucket, new Request(manifest().downloadUrl))).status, 404);
  assert.equal((await publish(bucket)).status, 409);
});

test("a release cannot be advertised before its source is published", async () => {
  const bucket = new Bucket();
  assert.equal((await upload(bucket)).status, 201);
  const refused = await publish(bucket);
  assert.equal(refused.status, 409);
  assert.match((await refused.json()).error, /Source for this release/);
  assert.equal(bucket.objects.has("ventoy/latest.json"), false);
  // Source published for another version does not cover this one.
  await seedSource(bucket, "1.1.18");
  assert.equal((await publish(bucket)).status, 409);
  await seedSource(bucket);
  assert.equal((await publish(bucket)).status, 200);
});

test("source archives are verified, typed and listed for anonymous readers", async () => {
  const bucket = new Bucket();
  assert.equal((await readSource(bucket)).status, 404);
  assert.equal((await upload(bucket, sourceArchive(), sourceBytes)).status, 201);
  // The list is refused while any of its archives is missing or unverified.
  assert.equal((await publishSource(bucket)).status, 409);
  assert.equal((await upload(bucket, busybox(), new Uint8Array(busyboxBytes.length))).status, 422);
  assert.equal((await publishSource(bucket)).status, 409);
  assert.equal((await upload(bucket, busybox(), busyboxBytes)).status, 201);
  assert.deepEqual(await (await publishSource(bucket)).json(), sourceManifest());
  const listed = await readSource(bucket);
  assert.equal(listed.status, 200);
  assert.equal(listed.headers.get("Cache-Control"), "no-store");
  assert.deepEqual(await listed.json(), sourceManifest());
  const head = await readSource(bucket, "1.1.17", "HEAD");
  assert.equal(head.status, 200);
  assert.equal(await head.text(), "");
  for (const [entry, data, type] of [[sourceArchive(), sourceBytes, "application/gzip"],
    [busybox(), busyboxBytes, "application/x-bzip2"]]) {
    const response = await invoke(bucket, new Request(entry.downloadUrl));
    assert.equal(response.status, 200);
    assert.equal(response.headers.get("Content-Type"), type);
    assert.equal(response.headers.get("Content-Length"), String(data.length));
    assert.deepEqual(new Uint8Array(await response.arrayBuffer()), data);
    assert.equal(bucket.objects.get(`assets/${entry.sha256}/${entry.assetName}`).httpMetadata.contentType, type);
  }
  assert.equal((await invoke(bucket, request("/v1/ventoy/source/1.1.17.json", { method: "PUT", auth: null,
    body: JSON.stringify(sourceManifest()) }))).status, 405);
});

test("a published source list cannot change and older versions stay available", async () => {
  const bucket = new Bucket();
  await seedSource(bucket);
  await upload(bucket); await publish(bucket);
  const revision = bucket.revision;
  assert.equal((await publishSource(bucket)).status, 200);
  assert.equal(bucket.revision, revision);
  for (const changed of [{ ...sourceManifest(), commit: "d".repeat(40) },
    { ...sourceManifest(), archives: [sourceArchive()] }]) {
    assert.equal((await publishSource(bucket, changed)).status, 409);
  }
  assert.deepEqual(await (await readSource(bucket)).json(), sourceManifest());
  // A newer release replaces latest. The older payload, its source and its list remain.
  await seedSource(bucket, "1.1.18");
  await upload(bucket, manifest("1.1.18"));
  assert.equal((await publish(bucket, manifest("1.1.18"))).status, 200);
  assert.deepEqual(await (await readSource(bucket)).json(), sourceManifest());
  assert.deepEqual(await (await readSource(bucket, "1.1.18")).json(), sourceManifest("1.1.18"));
  for (const entry of [manifest(), sourceArchive(), sourceArchive("1.1.18"), busybox()]) {
    assert.equal((await invoke(bucket, new Request(entry.downloadUrl))).status, 200);
  }
  // A list that another publisher stored first is kept, not overwritten.
  const racing = new Bucket();
  await upload(racing, sourceArchive(), sourceBytes); await upload(racing, busybox(), busyboxBytes);
  racing.objects.set("ventoy/source/1.1.17.json", { bytes: new TextEncoder().encode(
    JSON.stringify({ ...sourceManifest(), commit: "e".repeat(40) })), size: 1, httpEtag: '"other"' });
  assert.equal((await publishSource(racing)).status, 409);
});

test("source lists reject other versions, hosts, archive kinds and malformed fields", async () => {
  const bucket = new Bucket();
  await upload(bucket, sourceArchive(), sourceBytes); await upload(bucket, busybox(), busyboxBytes);
  const base = sourceManifest();
  const linux = archive("ventoy-1.1.17-linux.tar.gz", sourceBytes);
  const many = Array.from({ length: MAX_SOURCE_ARCHIVES }, (_, index) =>
    archive(`busybox-1.${index}.0.tar.bz2`, busyboxBytes));
  for (const entry of [
    { ...base, schemaVersion: 2 }, { ...base, tag: "v1.1.18" }, { ...base, tag: "main" },
    { ...base, commit: "main" }, { ...base, commit: "C".repeat(40) },
    { ...base, archives: [] }, { ...base, archives: null }, { ...base, archives: [null] },
    { ...base, archives: [busybox(), sourceArchive()] },
    { ...base, archives: [linux] }, { ...base, archives: [sourceArchive(), linux] },
    { ...base, archives: [sourceArchive("1.1.18")] },
    { ...base, archives: [sourceArchive(), busybox(), busybox()] },
    { ...base, archives: [sourceArchive(), ...many] },
    { ...base, archives: [{ ...sourceArchive(), downloadUrl: "https://github.com/ventoy/Ventoy/archive/x.tar.gz" }] },
    { ...base, archives: [{ ...sourceArchive(), sizeBytes: 0 }] },
    { ...base, archives: [{ ...sourceArchive(), sha256: "A".repeat(64) }] },
  ]) assert.equal((await publishSource(bucket, entry, token, "1.1.17")).status, 400, JSON.stringify(entry).slice(0, 160));
  // The body must describe the version named in its path.
  assert.equal((await publishSource(bucket, base, token, "1.1.18")).status, 400);
  for (const path of ["/v1/ventoy/source/latest.json", "/v1/ventoy/source/1.json", "/v1/ventoy/source/01.1.json",
    "/v1/ventoy/source/1.1.17", "/v1/ventoy/source/1.1.17.json.gz"]) {
    assert.equal((await invoke(bucket, request(path, { auth: null }))).status, 404, path);
  }
  assert.equal(bucket.objects.has("ventoy/source/1.1.17.json"), false);
  assert.equal(bucket.objects.has("ventoy/source/1.1.18.json"), false);
});
