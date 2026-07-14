# Contributing to Vendroid

Thanks for helping improve Vendroid. Because the app writes partition tables and boot data, changes should favor data safety, clear failure modes, and reproducible tests.

## Conduct

Be respectful, constructive, and patient. Critique the change, not the contributor. Harassment and discriminatory behavior are not accepted.

## Bugs and proposals

- Search existing issues before opening a new one.
- Include the Android version, device model, USB adapter, drive model/capacity, and a reproducible sequence.
- Remove secrets and personal file names from logs before attaching them.
- For large features or changes to disk layout, open an issue before implementation.
- Root-only features and phone-as-USB gadget mode are currently outside project scope.

## Translations

Until an official translation platform is configured, translation fixes may be submitted as focused pull requests. Keep placeholders and XML escaping intact. Translator-credit updates belong in `app/src/main/res/xml/about.xml`.

## Development workflow

- Create a focused feature branch and rebase it onto the latest `main`.
- Do not merge `main` into the feature branch.
- Prefer small, self-contained commits. Conventional Commit messages are encouraged.
- Separate unrelated formatting or cleanup from functional changes.
- Review the final diff for generated files, debug logs, and accidental payload archives.

## Build and verification

Prepare the pinned Ventoy payload as documented in the README, then run:

```sh
./gradlew assembleFossDebug
./gradlew testFossDebugUnitTest
./gradlew lint
```

The Appium suite can run against the Bliss OS/QEMU setup documented in `appium-tests/README.md`:

```sh
cd appium-tests
npm ci
uv run pytest -sv
```

QEMU tests install Ventoy onto a disposable virtual USB image and validate the disk layout. When testing on physical hardware, use a disposable USB drive and confirm the selected device—the test and install flows erase or repartition it.

## Pull requests

Describe the problem, the chosen approach, user-visible or compatibility impact, and the checks you ran. Respond professionally to review feedback; technical disagreement is welcome when supported by evidence.

Contributions are licensed under the project's [GPL-3.0 license](LICENSE).
