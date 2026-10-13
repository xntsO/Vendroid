# Vendroid privacy policy

Effective date: 13 October 2026

Vendroid is an open-source Android app maintained by Xnts0. This policy applies to
the Vendroid FOSS app, including its Google Play beta and production releases.
Privacy questions can be sent to contact@fhas.sa.

## Files and USB drives

Vendroid accesses the USB drive you grant permission to and files or folders you
select through Android's file picker. It uses this access to install, inspect,
update, or repair Ventoy and manage boot image files. These operations run on your
device. Vendroid does not upload USB contents, selected files, or their names to
the developer. Installing Ventoy erases the selected USB drive after confirmation.

## Optional update downloads

The bundled Ventoy payload works offline. If you choose to check for or download
a Ventoy update, Vendroid connects over HTTPS to updates.vendroid.fhas.sa, the
Vendroid update mirror. The mirror serves copies of official Ventoy release files
that are checked against their upstream-published SHA-256. The app verifies the
download again. This does not require a new Vendroid app release.

The mirror is hosted through ChatGPT Sites and its Cloudflare infrastructure.
Hosting providers receive and retain ordinary request information, including your
IP address, approximate country, requested resource, and request headers, to
deliver, operate, and protect the service. Security cookies may be included in
responses. The service's operational logs can be accessed by its administrator.
See [OpenAI's privacy policy](https://openai.com/policies/privacy-policy/) and
[Cloudflare's privacy policy](https://www.cloudflare.com/privacypolicy/).
This information is not the contents of your USB
drive. The updater does not send personal files, filenames, a birth date, account
information, or a persistent app identifier. It does not forward your requests to
GitHub. A separate scheduled server process retrieves upstream releases from
GitHub without using visitors' connection information.

Vendroid does not ask for, store, or transmit your age or birth date. The privacy
policy is included in the app and can be read offline. Website, source-code,
issue-report, and store links open your chosen external browser or app. Those
services receive connection information and apply their own terms and privacy
policies. Google Play applies its own account and parental controls.

## Analytics, storage, and deletion

Vendroid has no user accounts, advertisements, analytics service, or remote crash
reporting. Diagnostic messages are written to Android's local logs. The developer
does not automatically receive those logs. If you choose to share a diagnostic
report, review it first and remove information you do not wish to disclose.

App preferences and downloaded Ventoy payloads are stored locally. Clear the app's
storage or uninstall it to remove its private data. Android backup and restore may
retain interface preferences according to your device settings and backup
provider's policies. Downloaded Ventoy payloads are excluded from Android backup.
Uninstalling Vendroid does not remove connection records retained by a hosting
provider. Files on external USB drives remain until you delete or overwrite them.
Revoking file-picker or USB permission stops the corresponding access.

Vendroid does not sell personal data. Its update mirror stores public release
files and metadata, not your app files or USB contents. Hosting providers may keep
operational records as described above. Information you send voluntarily in a
support request is handled through the email or issue service you choose.

## Changes

Future changes to Vendroid's data handling will be reflected here with a revised
effective date. This policy is available from the app's About screen and its store listing.
