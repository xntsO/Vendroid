import test from "node:test";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { handleMirrorRequest, ORIGIN, parseManifest } from "./handler.mjs";

const token = "fixture-publisher-token-00000000000000000000";
const bytes = new TextEncoder().encode("verified archive fixture");
const sha256 = createHash("sha256").update(bytes).digest("hex");
const manifest = (version = "1.1.17") => ({ schemaVersion: 1, version,
  assetName: `ventoy-${version}-linux.tar.gz`, sizeBytes: bytes.length, sha256,
  downloadUrl: `${ORIGIN}/v1/assets/${sha256}/ventoy-${version}-linux.tar.gz`,
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
      httpEtag: `"revision-${++this.revision}"` };
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

test("verified upload, atomic publication and anonymous byte-for-byte readback", async () => {
  const bucket = new Bucket();
  assert.equal((await upload(bucket)).status, 201);
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
  await upload(bucket);
  for (const entry of [
    { ...manifest(), schemaVersion: 2 }, { ...manifest(), sha256: "A".repeat(64) },
    { ...manifest(), downloadUrl: manifest().downloadUrl.replace(ORIGIN, "https://github.com") },
    { ...manifest(), downloadUrl: manifest().downloadUrl.replace("https://", "https://user@") },
    { ...manifest(), assetName: "../payload.tar.gz" }, { ...manifest(), version: "1.1.17/other" },
    { ...manifest(), sizeBytes: 256 * 1024 * 1024 + 1 },
  ]) assert.equal((await publish(bucket, entry)).status, 400);
  assert.equal(bucket.objects.has("ventoy/latest.json"), false);
});

test("a newer release replaces latest but downgrade and same-version changes do not", async () => {
  const bucket = new Bucket();
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
  await upload(bucket); await publish(bucket); await upload(bucket, manifest("1.1.18"));
  bucket.publicationConflict = true;
  assert.equal((await publish(bucket, manifest("1.1.18"))).status, 409);
  bucket.publicationConflict = false; bucket.failWrites = true;
  assert.equal((await publish(bucket, manifest("1.1.18"))).status, 503);
  assert.equal((await (await bucket.get("ventoy/latest.json")).json()).version, "1.1.17");
});

test("publication is idempotent and strips unrelated input fields", async () => {
  const bucket = new Bucket();
  await upload(bucket); await publish(bucket);
  const revision = bucket.revision;
  assert.equal((await upload(bucket)).status, 200);
  assert.deepEqual(await (await publish(bucket, { ...manifest(), visitor: "discard-me" })).json(), manifest());
  assert.equal(bucket.revision, revision);
  assert.equal("visitor" in parseManifest({ ...manifest(), visitor: "discard-me" }), false);
});

test("oversized metadata, unknown paths, query strings and unverified objects are rejected", async () => {
  const bucket = new Bucket();
  assert.equal((await invoke(bucket, request("/v1/admin/ventoy/latest.json", {
    method: "PUT", body: " ".repeat(4097),
  }))).status, 413);
  assert.equal((await invoke(bucket, request("/v1/ventoy/latest.json?user=123"))).status, 400);
  assert.equal((await invoke(bucket, request("/v1/assets/anything"))).status, 404);
  assert.equal((await invoke(bucket, request("/v1/ventoy/latest.json"))).status, 404);
  bucket.objects.set(`assets/${sha256}/${manifest().assetName}`, { bytes, size: bytes.length, customMetadata: {} });
  assert.equal((await invoke(bucket, new Request(manifest().downloadUrl))).status, 404);
  assert.equal((await publish(bucket)).status, 409);
});
