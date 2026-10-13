# Vendroid update service

This service serves persisted, verified Ventoy archives at
`https://updates.vendroid.fhas.sa`. It is separate from the Vendroid website.
Public requests never fetch from GitHub; the
[GitHub Actions sync](../../scripts/mirror/README.md) obtains upstream metadata
and bytes using its own job identity, verifies them, and populates this service.

## Source and deployment mapping

`handler.mjs` is the canonical handler tracked in this repository.
`handler.test.mjs` exercises its HTTP and R2 boundaries in GitHub Actions with
Node 22. Deployments must copy the reviewed handler into the separate Sites
checkout as `app/lib/mirror.mjs`. Do not patch that copy independently.

The Sites route adapter at `app/v1/[...path]/route.ts` forwards GET, HEAD, and PUT
requests to `handleMirrorRequest(request, { bucket, publishToken })`, mapping:

| Handler input | Sites runtime value |
| --- | --- |
| `bucket` | `env.BUCKET`, the provisioned R2 binding |
| `publishToken` | `env.MIRROR_PUBLISH_TOKEN`, a private runtime secret |

The source hosting manifest declares `"r2": "BUCKET"` and preserves the registered
Sites project ID. Leave D1 unused. The current registered project is
`appgprj_6acdd5aa9e6481919e00654691968c32`. The deployment checkout is
`C:/Users/acer/Documents/vendroid/.vendroid-cache/sites/vendroid-updates`;
registration and publication belong to the service owner.

The runtime secret must have the same private value as the GitHub Actions secret
`VENDROID_MIRROR_PUBLISH_TOKEN`. Use a cryptographically generated ASCII token of
at least 32 characters, without whitespace. Keep it out of source, hosting
manifests, client bundles, logs, and artifacts. The app receives no publisher
credential. GitHub's Android signing and Play credentials are not used here.

## HTTP and storage contract

Public GET and HEAD paths:

- `/v1/ventoy/latest.json` returns the six-field manifest with
  `Cache-Control: no-store`. Before the first successful seed it returns 404.
- `/v1/assets/{sha256}/ventoy-{version}-linux.tar.gz` serves the original archive
  as an R2 stream, with exact `Content-Length`, ETag, and a one-year immutable
  cache lifetime. Unknown or unverified objects return 404.

Authenticated PUT paths:

- `/v1/admin/assets/{sha256}/{assetName}` requires a Bearer token and
  `Content-Length`. R2 checks the SHA-256 during the streaming write. The stored
  digest, asset name, verified marker, and declared size must match the R2 object
  before it can be read or referenced by a manifest.
- `/v1/admin/ventoy/latest.json` accepts at most 4096 bytes of JSON. It requires a
  matching verified archive, rejects downgrades and altered metadata for an
  already published version, and returns the accepted manifest. `Content-Length`
  is optional for this small JSON request.

Assets use `If-None-Match: *` to prevent replacement. Latest-manifest writes use
the previous R2 ETag, or `If-None-Match: *` for the initial manifest, so concurrent
publications cannot overwrite each other silently. A rejected size mismatch is
never eligible for public reads or publication, including while its uploader is
still waiting to inspect the write result.

There are no external redirects, public upload routes, cookies, analytics, raw
request logging, or outbound fetches in the canonical handler. Hosting providers
still process network information; deployment and privacy review must account
for that handling. This implementation is not a worldwide compliance finding.

## Provider limits

Sites server code runs in Cloudflare Workers with 128 MB of memory per isolate.
Keep archive uploads and downloads streamed; do not buffer a whole archive in an
`arrayBuffer`. R2's SHA-256 write option verifies the stream without requiring
handler-level whole-file buffering. [R2 API](https://developers.cloudflare.com/r2/api/workers/workers-api-reference/)

The app and handler limit archives to 256 MiB. Cloudflare's inbound body cap
depends on its account plan: Free and Pro permit 100 MB, while Business permits
200 MB. The current 20.3 MB upstream Linux archive fits those caps. A larger
future archive can be rejected before reaching the handler; the current API has
no multipart upload path. Such a failure must leave the existing latest manifest
available. Workers do not impose a response-body size cap, but CDN cache limits
still apply. [Worker limits](https://developers.cloudflare.com/workers/platform/limits/),
[cache limits](https://developers.cloudflare.com/cache/concepts/default-cache-behavior/)

## Verification still required

Local source syntax checks have passed. The CI fixtures and real hosted checks
remain pending; source inspection does not establish successful deployment,
provider checksum enforcement, or mirror availability.

Before enabling the scheduled sync and releasing the Android mirror client:

1. Require the current revision's Python sync and Node handler fixtures to pass
   in Actions, including the interleaved upload/publication regression.
2. Confirm the deployed `app/lib/mirror.mjs` matches this canonical handler and
   that the public custom domain, R2 binding, and private token mapping work.
3. Exercise real R2 upload rejection for wrong hashes and sizes, denied anonymous
   writes, and monotonic manifest publication. Read the public archive back and
   verify its complete SHA-256 and size.
4. Retain the sync run's commit and readback evidence. Verify the Android client
   downloads only from the Vendroid origin and still checks payload compatibility,
   exact size, digest, extraction, and cache activation in its existing CI tests.

The registered schedule uses the repository's default branch. GitHub can delay
runs and disables public-repository schedules after 60 inactive days; preserve
the last verified release and arrange reactivation or an independent approved
scheduler when needed. [GitHub schedule rules](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#schedule)
