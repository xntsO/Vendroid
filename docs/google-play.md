# Google Play publishing

Vendroid uses the existing `foss` flavor and package `io.github.xntso.vendroid` on
Google Play. Production and beta are tracks of the same app. The internal Gradle
build type `preview` produces the optimized beta with version suffix `-beta`.
Experimental GPT and drives above 2 TiB remain beta features until their physical
validation gates in [validation.md](validation.md) pass.

## First listing

1. Create a free app named Vendroid, with English (United States) as its default
   language and the existing package name. The owner must review Google's policy
   and export declarations. Creating a draft does not publish an app or resolve
   an inactivity warning by itself.
2. Configure Play App Signing using the **existing Vendroid app signing key**.
   The first listing created on 13 October 2026 was automatically enrolled with a
   Google-generated key whose fingerprint started with `2E:3F:28:D0`. The existing
   Vendroid key was imported successfully on the same day using the encrypted
   export from Actions run `38355954349`. Play Console then displayed the matching
   certificate in its app-link configuration and upload-key section. For a future
   fresh listing, before the first upload, use **Protected with Play →
   Play Store protection → Manage Play app signing → Change key** and complete the
   supported existing-key export/import flow with the owner. Do not export an
   unencrypted private key as a CI artifact. A Google-generated replacement
   key would prevent updates over existing GitHub APK installations. Compare the
   classical app signing certificate SHA-256 with
   `b388ad9d2290967480630cbd4a48f4f8c6784305c05da3541ad780821d9fbeb1`.
   Keep the existing keystore and credentials private. Do not rotate keys during
   first-time setup. The CI bundle uses that key as its upload signer initially.
   If the keystore is available only in GitHub Actions secrets, use the dedicated
   `export_play_signing_key=true` manual input on `Build and test`. It exports the
   existing key using Google's pinned PEPK tool and the listing's public encryption
   key. Download `vendroid-google-play-key-import` within three days and upload
   `vendroid-play-signing-key.zip` through the same Console import dialog. The
   artifact contains only Google's encrypted key, the public certificate, and
   provenance. This enrollment job does not build or publish the app and is not
   evidence that the release passed validation.
3. Run the Google Play workflow in `prepare` mode for the beta track. Download the
   signed AAB and its metadata, signature, checksum, native-library, and commit
   evidence. Upload the first bundle through Play Console and complete any required
   internal or closed testing before the account can use open beta or production.
   The Console determines which testing requirements apply to this account.
4. Use the reviewed English listing under `fastlane/metadata/android/en-US`.
   Other inherited translations have not been reviewed for Vendroid; do not
   bulk-upload them. The obsolete EtchDroid screenshots and Italian store icon
   have been removed. Capture current Vendroid screenshots using the store-screenshot
   checks in GitHub Actions, and use the reviewed English listing's Vendroid icon and
   feature graphic. Complete content rating, audience, app access, ads, Data safety,
   countries, contact details, and foreground-service declarations in the Console.
   Use `contact@fhas.sa` for public developer and support contact. The owner has
   enabled a Vendroid website at `https://vendroid.fhas.sa`. The website was made
   public on 13 October 2026 after verifying its DNS records and HTTPS certificate;
   its website and privacy-policy URLs were saved in the Play listing draft.
   App access needs no login. USB operations require a USB host-capable Android
   device, a USB drive, and permission to access it. Declare the connected-device
   foreground service with a demonstration of a user-started USB operation.
5. Link the published [privacy policy](https://vendroid.fhas.sa/privacy/)
   and verify that Google accepts and can access it. Review Data safety against the
   current code: no accounts, ads, analytics, or remote crash reporting; optional
   downloads from Vendroid's mirror disclose ordinary network information to its
   hosting provider. The updater does not contact GitHub from the user's device
   and does not ask for a birth date. Local file
   access and network-provider handling must not be conflated.
6. Review Play's automatic installer protection before publication. It can prompt
   people who install protected APKs from another source to install from Play.
   Vendroid also distributes APKs on GitHub, so choose the protection settings
   deliberately and test cross-store updates.

## Automatic uploads

The `Prepare or publish Google Play` workflow validates the selected commit, builds and
checks the signed bundle, then uploads it through the Android Publisher API.
Publishing a GitHub prerelease selects the Vendroid beta channel. It uploads to
Play's closed `alpha` track by default. Publishing a stable GitHub release selects
`production`. Draft releases and ordinary commits do not upload.
Google review, testing eligibility, and managed publishing can still delay availability.

Before enabling uploads, create a dedicated service account with Android Publisher
API access and give it release access to **Vendroid only** in Play Console. Use
the minimum permissions needed for the beta and production tracks. The owner
approved testing and production release access on 13 October 2026, including the
device-exclusion and Play App Signing controls bundled with Google's production
permission. The workflow only publishes releases. The dedicated Cloud project is
`vendroid-publishing`; Android Publisher API is enabled and its service account is
`vendroid-play-publisher@vendroid-publishing.iam.gserviceaccount.com`, with no Cloud
project roles. The Console confirms the publisher is active for Vendroid only;
the owner also approved Google's mandatory policy-declaration and deep-link
permissions. The publishing JSON was installed as the repository secret on
13 October 2026. Store the service-account JSON as the GitHub
Actions secret `VENDROID_PLAY_SERVICE_ACCOUNT_JSON`; never commit it or paste it
into chat. Enable the repository variable `VENDROID_PLAY_PUBLISHING_ENABLED=true`
only after first-listing setup, signing, and API access have been checked.

The current Console account requires at least 12 closed testers opted in for 14
continuous days before requesting production access. Open testing is also
unavailable during this initial stage. Leave `VENDROID_PLAY_BETA_TRACK` unset or
set it to `alpha` for closed testing. Set it to `beta` only after Play makes open
testing available and the owner chooses that track. Other values are rejected.
This setting affects only the beta channel; production always uses `production`.

The closed Alpha track uses the self-join Google Group
[`vendroid-beta-testers@googlegroups.com`](https://groups.google.com/g/vendroid-beta-testers).
It was created and saved in Play Console on 13 October 2026, with all 178 available
country/region entries selected and `contact@fhas.sa` as the feedback contact.
Only group owners can view members and their email addresses or post to the group.
No testers were invited or added manually. Joining the group grants eligibility;
testers must then opt in on Google Play using the same Google account.
The Console shows `https://play.google.com/apps/testing/io.github.xntso.vendroid`
as the future opt-in URL, but it remains inactive until the first beta is published.
The website's [beta section](https://vendroid.fhas.sa/#beta) links to the group and
keeps the second step disabled until publication. Enable that button only after
checking the live Play opt-in page. Group membership alone does not count toward
the 12-testers-for-14-days requirement.

The workflow's manual `prepare` mode needs only the existing four release-signing
secrets and creates an artifact without contacting the publishing API. Manual
`publish` uses the selected track and the same validation and upload checks. Select
the source branch or tag using GitHub's normal workflow ref selector.

Before this new workflow is registered on the default branch, the existing
`Build and test` workflow can prepare the beta bundle on a pushed branch:

```sh
gh workflow run build-test-debug.yml --ref codex/google-play-publishing -f signed_play_bundle=true
```

It runs the full validation first, then uploads a signed beta APK and AAB with
evidence. This is an artifact-only preparation path and never calls Google Play.

Production retains the existing physical release gate. Validate the candidate,
record the affected physical USB checks and the owner's promotion decision, then
publish the GitHub release explicitly. CI success alone is not physical evidence.

## Ventoy payload updates

Ventoy payload checks are independent of Google Play app updates. The app reads
the version manifest and verified archive from `https://updates.vendroid.fhas.sa`.
The scheduled [mirror sync](../scripts/mirror/README.md) copies official upstream
releases and verifies their SHA-256 before making them available. Users still
choose when to download a payload and apply it to a USB drive. No age prompt is
used. The bundled payload remains available offline.

The owner chose to keep the Sites/R2 host after being informed that Sites terms
restrict services designed for children under 13, and plans to contact the host
if necessary. This choice does not establish compliance with those terms or
authorize the separate Play all-ages legal certification. Initial Python requests
using the generic urllib user agent received a hosting-layer HTTP 403; an honest
`Vendroid-Mirror/1.0` user agent reached the service. Initial publication of
Ventoy 1.1.18 succeeded on 13 October 2026, including anonymous full-archive
SHA-256 verification and manifest readback. The archive is 20,320,548 bytes and
matches upstream SHA-256
`d86ff9de63d94c8b8f1f6b14d23c55af4b88e8207bf2893890892a6a97b1ad54`.
The scheduled workflow still needs registration on the default branch and
activation after CI validation. The owner explicitly authorized the separate
all-ages certification on 13 October 2026. It was saved for all six age groups,
including children under 13; the optional Teacher Approved program was not joined.
This records the owner's declaration, not an independent compliance finding.
The Data safety answers declare optional, non-ephemeral approximate location and
app interactions for functionality, operational analytics, and security. HTTPS is
used; the app has no accounts. The observed hosting-log transfers use Google's
service-provider sharing exception, supported by the
[Sites DPA](https://openai.com/policies/chatgpt-sites-data-processing-addendum/).
This does not claim that providers retain no connection data. There is no verified
server-log deletion mechanism or 90-day deletion guarantee.

## Version codes and upgrades

All tracks and signed GitHub distributions share one increasing version-code
sequence. Code 30 was consumed by the first GPT candidate; this preparation starts
at 31. Increment `defaultVersionCode` before distributing the next distinct version. CI reads
the code and version name directly from Gradle source. An explicit `VENDROID_VERSION_CODE`
override must be at least the checked-in default and at most 2100000000.

Do not reuse one code for different beta and production binaries. The publisher
checks existing bundles, APKs, and tracks before upload and rejects a code that is
not greater than the highest one returned by Play. Keep a record of distributed
codes, including artifacts used for physical checks. A failed or repeated upload
does not justify overwriting a previously distributed version.

Beta users receive the highest eligible version code. After leaving beta, an
installed higher-code beta may remain until production catches up. Joining beta
does not install a separate V-Preview app. Existing `io.github.xntso.vendroid.preview`
installations remain separate and require a deliberate transition to Vendroid.

## References

- [Google Play tracks](https://developers.google.com/android-publisher/tracks)
- [Play App Signing](https://support.google.com/googleplay/android-developer/answer/9842756)
- [Testing tracks](https://support.google.com/googleplay/android-developer/answer/9845334)
- [16 KB page sizes](https://developer.android.com/guide/practices/page-sizes)
- [Connected-device foreground services](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device)
