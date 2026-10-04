"""Expected identities and policies for the required CI APKs."""

from dataclasses import dataclass
import os


@dataclass(frozen=True)
class ValidationProfile:
    channel: str
    package: str
    app_name: str
    version_suffix: str
    partition_style: str
    layout: str
    supports_large_drives: bool

    def version(self, base_version: str) -> str:
        return base_version + self.version_suffix


PROFILES = {
    "stable": ValidationProfile(
        "stable", "io.github.xntso.vendroid", "Vendroid", "", "MBR (Recommended)", "mbr", False,
    ),
    "preview": ValidationProfile(
        "preview", "io.github.xntso.vendroid.preview", "V-Preview", "-preview", "GPT (Preview)", "gpt", True,
    ),
    "gpt-candidate": ValidationProfile(
        "gpt-candidate", "io.github.xntso.vendroid", "Vendroid", "-gpt-candidate", "GPT", "gpt", False,
    ),
}

LARGE_DRIVE_BYTES = 3 * 1024**4


def configured_profile() -> ValidationProfile:
    channel = os.environ.get("VENDROID_TEST_CHANNEL", "stable")
    try:
        profile = PROFILES[channel]
    except KeyError:
        raise ValueError(f"Unknown VENDROID_TEST_CHANNEL: {channel}") from None
    expected = {
        "VENDROID_PACKAGE_NAME": profile.package,
        "VENDROID_APP_NAME": profile.app_name,
        "VENDROID_PARTITION_STYLE": profile.partition_style,
        "VENDROID_EXPECTED_VERSION": profile.version(os.environ.get("VENDROID_VERSION", "0.2.0")),
    }
    for name, value in expected.items():
        configured = os.environ.get(name, value)
        if configured != value:
            raise ValueError(f"{name} must be {value!r} for {channel}, got {configured!r}")
    return profile
