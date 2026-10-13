# Google PEPK export

This export-only helper transfers the existing Vendroid signing key to Google. It
does not build an app, upload a release, or change Play Console configuration.

Run only in the dedicated GitHub Actions job with Java 17, keytool, and the four
existing `VENDROID_*` signing secrets in its environment:

```sh
python3 scripts/play/export_signing_key.py --output-dir pepk-export --commit "$GITHUB_SHA"
```

The output directory must not already exist. Retain only these four files:

- `vendroid-play-signing-key.zip` — encrypted for the supplied Google public key.
- `certificate.pem` — public certificate for the original Vendroid signing key.
- `export-evidence.json` — tool, recipient-key, certificate, archive, and commit evidence.
- `SHA256SUMS` — checksums of the other three files.

The helper checks the existing certificate against `b388ad9d2290967480630cbd4a48f4f8c6784305c05da3541ad780821d9fbeb1`
before exporting a private key. Signing inputs, downloaded tools, and temporary
exports live in a private runner directory and are removed on success, failure,
or a handled interruption. Child tools receive no secret environment variables.
PEPK requires command-line password flags without an interactive console; its
stdout/stderr are captured and never relayed, including error traces. Do not enable
shell tracing or print process arguments in this job.

The [official PEPK binary](https://www.gstatic.com/play-apps-publisher-rapid/signing-tool/prod/pepk.jar)
downloaded on 13 October 2026 has SHA-256
`aaccc0774b240aa5304bdad2a49865e92f229ca73209ecb6eaafe75dc858e24e`.
The [official source archive](https://www.gstatic.com/play-apps-publisher-rapid/signing-tool/prod/pepk-src.jar)
has SHA-256 `dc29a1d192eef9371eec0d2c134879fefc0a9dcf0c7046d6c8f3d302d0009b09`.
`ExportEncryptedPrivateKeyTool.java` and `KeystoreHelper.java` confirm the flag
contract and Apache 2.0 licensing. RSA-OAEP wraps a random AES-256 key; AES Key Wrap
with Padding wraps the existing PKCS8 private key. With `--include-cert` and no
additional signing keystore, the ZIP contains exactly `encryptedPrivateKey` and
`certificate.pem`. The helper validates that structure and the included certificate;
it cannot decrypt Google's ciphertext or confirm Console acceptance.

The Console-supplied RSA-3072 encryption public key is checked in with SHA-256
`37831b46ba92bc7e806d907efbe55a4b1e78145fe142b0bc6a390f426153f0e3`.
Its local `.gitattributes` rule preserves these exact bytes across checkouts.
Changing either tool or public-key pin requires another source/download review.

CI runs the stdlib fixtures before loading credentials:

```sh
python3 -m unittest discover -s scripts/play -p 'test_export_signing_key.py' -v
```

After downloading the verified encrypted ZIP, follow the existing-key import flow
in [Play App Signing](https://support.google.com/googleplay/android-developer/answer/9842756).
Confirm the **classical app-signing certificate** still matches Vendroid. A local
upload certificate or successful export alone does not prove Google's signer.
