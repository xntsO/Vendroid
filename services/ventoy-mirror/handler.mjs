export const ORIGIN = "https://updates.vendroid.fhas.sa";
export const MAX_ARCHIVE_BYTES = 256 * 1024 * 1024;
const LATEST = "ventoy/latest.json";
const VERSION = /^(?:0|[1-9]\d{0,5})(?:\.(?:0|[1-9]\d{0,5})){1,3}$/;
const SHA256 = /^[a-f0-9]{64}$/;
const ASSET_PATH = /^\/v1\/(admin\/)?assets\/([a-f0-9]{64})\/(ventoy-([0-9.]+)-linux\.tar\.gz)$/;

class RequestError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}

function requireValue(condition, message, status = 400) {
  if (!condition) throw new RequestError(status, message);
}

function json(value, status = 200, cache = "no-store") {
  return new Response(JSON.stringify(value), { status, headers: {
    "Content-Type": "application/json; charset=utf-8", "Cache-Control": cache,
    "X-Content-Type-Options": "nosniff", "Referrer-Policy": "no-referrer",
  } });
}

export function parseManifest(value) {
  requireValue(value !== null && typeof value === "object" && !Array.isArray(value), "Invalid manifest");
  const { schemaVersion, version, assetName, sizeBytes, sha256, downloadUrl } = value;
  requireValue(schemaVersion === 1, "Unsupported manifest schema");
  requireValue(typeof version === "string" && VERSION.test(version), "Invalid version");
  requireValue(assetName === `ventoy-${version}-linux.tar.gz`, "Invalid asset name");
  requireValue(Number.isSafeInteger(sizeBytes) && sizeBytes > 0 && sizeBytes <= MAX_ARCHIVE_BYTES, "Invalid archive size");
  requireValue(typeof sha256 === "string" && SHA256.test(sha256), "Invalid SHA-256");
  requireValue(downloadUrl === `${ORIGIN}/v1/assets/${sha256}/${assetName}`, "Invalid download URL");
  // Only release fields are persisted or returned, even if a caller adds fields.
  return { schemaVersion, version, assetName, sizeBytes, sha256, downloadUrl };
}

export function compareVersions(left, right) {
  const a = left.split(".").map(Number), b = right.split(".").map(Number);
  for (let i = 0; i < Math.max(a.length, b.length); i++) {
    const difference = (a[i] ?? 0) - (b[i] ?? 0);
    if (difference) return Math.sign(difference);
  }
  return 0;
}

async function readJson(request) {
  const reader = request.body?.getReader();
  requireValue(reader, "Missing manifest");
  const chunks = [];
  let length = 0;
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      length += value.byteLength;
      requireValue(length <= 4096, "Manifest too large", 413);
      chunks.push(value);
    }
  } catch (error) { await reader.cancel(); throw error; }
  const bytes = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
  try { return parseManifest(JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes))); }
  catch (error) {
    if (error instanceof RequestError) throw error;
    throw new RequestError(400, "Invalid JSON manifest");
  }
}

function objectMatches(object, sha256, assetName, sizeBytes = object?.size) {
  return object && object.size === sizeBytes && object.customMetadata?.sha256 === sha256 &&
    object.customMetadata?.assetName === assetName && object.customMetadata?.verified === "sha256" &&
    object.customMetadata?.sizeBytes === String(sizeBytes);
}

async function authorize(request, secret) {
  requireValue(typeof secret === "string" && secret.length >= 32, "Publisher is not configured", 503);
  const header = request.headers.get("Authorization") ?? "";
  requireValue(header.length <= 512, "Unauthorized", 401);
  const encoder = new TextEncoder();
  const [actual, expected] = await Promise.all([header, `Bearer ${secret}`].map(value =>
    crypto.subtle.digest("SHA-256", encoder.encode(value))));
  const a = new Uint8Array(actual), b = new Uint8Array(expected);
  let mismatch = 0;
  for (let i = 0; i < a.length; i++) mismatch |= a[i] ^ b[i];
  requireValue(mismatch === 0, "Unauthorized", 401);
}

async function uploadAsset(request, bucket, sha256, assetName) {
  const lengthHeader = request.headers.get("Content-Length");
  requireValue(lengthHeader !== null && /^[1-9]\d*$/.test(lengthHeader), "Content-Length required", 411);
  const sizeBytes = Number(lengthHeader);
  requireValue(Number.isSafeInteger(sizeBytes) && sizeBytes <= MAX_ARCHIVE_BYTES, "Archive too large", 413);
  const key = `assets/${sha256}/${assetName}`;
  const existing = await bucket.head(key);
  if (objectMatches(existing, sha256, assetName, sizeBytes)) {
    await request.body?.cancel();
    return json({ sha256, sizeBytes });
  }
  requireValue(!existing, "Stored asset conflicts with upload", 409);
  requireValue(request.body !== null, "Missing archive");
  let object;
  try {
    // R2 checks SHA-256 while consuming the stream. Readers also compare the
    // persisted declared size with R2's actual size before exposing this key.
    object = await bucket.put(key, request.body, { sha256,
      onlyIf: new Headers({ "If-None-Match": "*" }),
      customMetadata: { sha256, assetName, sizeBytes: String(sizeBytes), verified: "sha256" },
      httpMetadata: { contentType: "application/gzip" },
    });
  } catch { throw new RequestError(422, "Archive upload or checksum validation failed"); }
  requireValue(object, "Concurrent asset upload; retry", 409);
  if (object.size !== sizeBytes) {
    await bucket.delete(key);
    throw new RequestError(422, "Archive size mismatch");
  }
  return json({ sha256, sizeBytes }, 201);
}

async function publishManifest(request, bucket) {
  const manifest = await readJson(request);
  const asset = await bucket.head(`assets/${manifest.sha256}/${manifest.assetName}`);
  requireValue(objectMatches(asset, manifest.sha256, manifest.assetName, manifest.sizeBytes), "Verified archive is not available", 409);
  const current = await bucket.get(LATEST);
  if (current) {
    const previous = parseManifest(await current.json());
    const relation = compareVersions(manifest.version, previous.version);
    requireValue(relation >= 0, "Cannot downgrade the mirror", 409);
    if (relation === 0) {
      requireValue(JSON.stringify(manifest) === JSON.stringify(previous), "Cannot replace a published release", 409);
      return json(previous);
    }
  }
  const saved = await bucket.put(LATEST, JSON.stringify(manifest), {
    onlyIf: new Headers(current ? { "If-Match": current.httpEtag } : { "If-None-Match": "*" }),
    httpMetadata: { contentType: "application/json" },
  });
  requireValue(saved, "Concurrent publication; retry", 409);
  return json(manifest);
}

export async function handleMirrorRequest(request, { bucket, publishToken }) {
  try {
    const url = new URL(request.url);
    requireValue(!url.search, "Query parameters are not supported");
    requireValue(bucket, "Mirror storage is unavailable", 503);
    const admin = url.pathname.startsWith("/v1/admin/");
    if (admin) {
      await authorize(request, publishToken);
      requireValue(request.method === "PUT", "Method not allowed", 405);
      if (url.pathname === "/v1/admin/ventoy/latest.json") return await publishManifest(request, bucket);
    } else requireValue(["GET", "HEAD"].includes(request.method), "Method not allowed", 405);

    if (url.pathname === "/v1/ventoy/latest.json") {
      const object = await bucket.get(LATEST);
      requireValue(object, "No verified release is available yet", 404);
      const response = json(parseManifest(await object.json()));
      return request.method === "HEAD" ? new Response(null, response) : response;
    }
    const match = ASSET_PATH.exec(url.pathname);
    requireValue(match && VERSION.test(match[4]), "Not found", 404);
    const [, , sha256, assetName] = match;
    if (admin) return await uploadAsset(request, bucket, sha256, assetName);
    const key = `assets/${sha256}/${assetName}`;
    const object = request.method === "HEAD" ? await bucket.head(key) : await bucket.get(key);
    requireValue(objectMatches(object, sha256, assetName), "Not found", 404);
    return new Response(request.method === "HEAD" ? null : object.body, { headers: {
      "Content-Type": "application/gzip", "Content-Length": String(object.size),
      "Cache-Control": "public, max-age=31536000, immutable", "ETag": object.httpEtag,
      "X-Content-Type-Options": "nosniff", "Referrer-Policy": "no-referrer",
    } });
  } catch (error) {
    return error instanceof RequestError ? json({ error: error.message }, error.status) :
      json({ error: "Mirror storage operation failed" }, 503);
  }
}
