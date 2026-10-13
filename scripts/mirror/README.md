# Vendroid Ventoy mirror sync

`sync.py` runs in GitHub Actions. It fetches the public Ventoy release metadata,
accepts only a stable release and its exact Linux archive, verifies the
upstream-published SHA-256 and size, and uploads the original bytes to Vendroid's
first-party mirror. It verifies the public byte stream before publishing the
stripped latest manifest, then reads that manifest back. The app retains its own
size, checksum, payload compatibility, extraction, and cache checks.

The update service serves persisted R2 objects. Public requests never contact
GitHub, and the sync does not accept visitor headers or arbitrary upstream URLs.
GitHub's authentication token is sent only to its fixed metadata API. The mirror's
publisher token is sent only to its fixed HTTPS admin endpoints. Neither token,
raw error bodies, nor temporary delivery URLs appear in evidence or artifacts.

## Setup

1. Deploy the separate public service at `https://updates.vendroid.fhas.sa` with
   its R2 binding and private publisher token. Its public reads need no account.
2. Store that publisher token as the GitHub Actions secret
   `VENDROID_MIRROR_PUBLISH_TOKEN`. Do not commit it or send it in chat.
3. After reviewing and testing the deployed service, set repository variable
   `VENDROID_MIRROR_SYNC_ENABLED=true` and dispatch `Sync verified Ventoy mirror`.
   Download `vendroid-mirror-sync-<run-id>` and check its public byte/manifest
   verification evidence before releasing the mirror client.
4. The registered workflow runs every 30 minutes at minutes 17 and 47 UTC.
   Scheduled runs use the default branch and require the workflow to be present
   there. GitHub may delay or drop runs during heavy load and automatically
   disables public-repository schedules after 60 days without repository activity.
   The owner must re-enable a disabled schedule; uninterrupted refresh across
   inactive periods requires a separately approved scheduler. A sync failure
   retains existing objects and does not intentionally remove the last working
   release. [GitHub schedule rules](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#schedule)

Invocation in CI:

```sh
python3 scripts/mirror/sync.py --commit "$GITHUB_SHA" --output mirror-evidence.json
```

The four Android signing secrets and Play service account are unrelated to this
job. `GH_TOKEN` uses the job's temporary read-only GitHub token for public API
requests. No Python packages outside the standard library are required.

## Service contract

Public endpoints accept GET and HEAD with no redirects:

- `/v1/ventoy/latest.json` returns exactly `schemaVersion`, `version`, `assetName`,
  `sizeBytes`, `sha256`, and `downloadUrl`. Schema version is integer `1`.
  It uses `Cache-Control: no-store` so latest-version readback cannot return a
  cached manifest. An empty mirror returns HTTP 404; storage failures return 503.
- `/v1/assets/{sha256}/ventoy-{version}-linux.tar.gz` streams the original archive.
  The download URL is exactly the HTTPS origin above plus that immutable path.

Authenticated endpoints accept PUT with `Authorization: Bearer <private-token>`
and no redirects:

- `/v1/admin/assets/{sha256}/{assetName}` accepts the archive bytes and returns
  `{ "sha256": "<digest>", "sizeBytes": <size> }` with HTTP 200 or 201.
  Archive uploads require `Content-Length`.
- `/v1/admin/ventoy/latest.json` accepts and returns the public manifest with
  HTTP 200 or 201, after confirming its verified object exists with the exact size
  and digest. It rejects downgrades and changes to a published version's metadata.
  Its parser limits the body to 4096 bytes; `Content-Length` is optional here,
  although the sync sends it.

The server verifies uploads with R2's SHA-256 `put` option, uses conditional
writes for immutable objects, and publishes a manifest only after that verified
write completes. Existing identical uploads may succeed idempotently. Public
metadata is not cached, while immutable assets can be cached for a year.
Declared size is persisted with the verified digest and must equal R2's actual
size before the object is eligible for reads or publication. No client cookies,
identifiers, raw request logging, or external
redirects belong in the service. Hosting-provider network processing still needs
accurate privacy disclosure; this design is not a worldwide compliance finding.

Archives remain bounded by the Android client's 256 MiB limit. A hosting plan's
inbound upload cap can be lower; an oversized upload must fail without publishing
the manifest. Larger future archives need a separately verified multipart path.
The complete upstream archive remains unchanged, including its licensing files;
retain the version's upstream source attribution and corresponding-source access.

See [service deployment](../../services/ventoy-mirror/README.md) for the canonical
handler, Sites adapter, storage binding, secret mapping, and pending hosted checks.

## CI fixtures

```sh
python3 -m unittest discover -s scripts/mirror -p 'test_*.py' -v
```

Fixtures cover published checksums, conflicting metadata, duplicate JSON fields,
size limits, corrupt and truncated byte streams, redirect allowlists, credential
isolation, acknowledgement failures, readback ordering, monotonic manifests,
temporary-file cleanup, and redacted error handling. Run these in Actions;
workstation app builds and tests remain prohibited by project instructions.

Sources: [GitHub release assets](https://docs.github.com/en/rest/releases/assets),
[Cloudflare R2 streaming and integrity checks](https://developers.cloudflare.com/r2/api/workers/workers-api-reference/),
[Cloudflare Worker HTTP headers](https://developers.cloudflare.com/fundamentals/reference/http-headers/),
[Cloudflare cache size limits](https://developers.cloudflare.com/cache/concepts/default-cache-behavior/).
