#!/usr/bin/env python3
"""Resolve a Play release from GitHub's event and checked-in Android version."""

from __future__ import annotations

import argparse
from dataclasses import asdict, dataclass
import json
from pathlib import Path
import re
import sys


MAX_VERSION_CODE = 2_100_000_000
PACKAGE_NAME = "io.github.xntso.vendroid"


class ReleasePlanError(ValueError):
    pass


@dataclass(frozen=True)
class ReleasePlan:
    commit: str
    track: str
    variant: str
    version_code: int
    version_name: str
    release_name: str
    publish: bool
    execute: bool

    @property
    def bundle_task(self) -> str:
        return "bundleFossPreview" if self.track == "beta" else "bundleFossRelease"

    @property
    def apk_task(self) -> str:
        return "assembleFossPreview" if self.track == "beta" else "assembleFossRelease"

    def outputs(self) -> dict[str, str]:
        return {
            **{key: str(value).lower() if isinstance(value, bool) else str(value)
               for key, value in asdict(self).items()},
            "bundle_task": self.bundle_task,
            "apk_task": self.apk_task,
            "package_name": PACKAGE_NAME,
        }


def checked_in_version(gradle_source: str) -> tuple[int, str]:
    codes = re.findall(r"^\s*val defaultVersionCode = ([0-9]+)\s*$", gradle_source, re.MULTILINE)
    names = re.findall(r'^\s*versionName = "([0-9]+\.[0-9]+\.[0-9]+)"\s*$', gradle_source, re.MULTILINE)
    if len(codes) != 1 or len(names) != 1:
        raise ReleasePlanError("Expected one checked-in defaultVersionCode and versionName")
    code = int(codes[0])
    if not 1 <= code <= MAX_VERSION_CODE:
        raise ReleasePlanError("Checked-in versionCode is outside Google Play's supported range")
    return code, names[0]


def resolve_plan(
    event_name: str,
    event: dict,
    *,
    gradle_source: str,
    commit: str,
    expected_commit: str,
    publishing_enabled: bool,
    production_enabled: bool = False,
    published_stable_release: dict | None = None,
    published_stable_commit: str | None = None,
) -> ReleasePlan:
    if not isinstance(event, dict):
        raise ReleasePlanError("GitHub must provide an object event payload")
    if not re.fullmatch(r"[0-9a-f]{40}", commit) or commit != expected_commit:
        raise ReleasePlanError("Checkout must match the exact GitHub event commit")
    default_code, base_version = checked_in_version(gradle_source)
    code = default_code
    if event_name == "release":
        release = event.get("release")
        if not isinstance(release, dict) or event.get("action") != "published":
            raise ReleasePlanError("Automatic distribution requires a published GitHub release")
        if release.get("draft") is not False or not isinstance(release.get("prerelease"), bool):
            raise ReleasePlanError("Release draft and prerelease state must be explicit")
        track = "beta" if release["prerelease"] else "production"
        release_name = release.get("tag_name")
        if not isinstance(release_name, str) or not re.fullmatch(
            rf"v{re.escape(base_version)}(?:-[0-9A-Za-z][0-9A-Za-z.-]*)?", release_name
        ):
            raise ReleasePlanError("Release tag must match the checked-in versionName")
        if track == "production" and release_name != f"v{base_version}":
            raise ReleasePlanError("Stable releases require the exact stable version tag")
        # A stable GitHub release must not depend on Play granting production access.
        publish = publishing_enabled and (track != "production" or production_enabled)
        execute = publish
    elif event_name == "workflow_dispatch":
        inputs = event.get("inputs", {})
        if not isinstance(inputs, dict):
            raise ReleasePlanError("Dispatch inputs must be an object")
        mode = inputs.get("mode", "prepare")
        track = inputs.get("track", "beta")
        if mode not in {"prepare", "publish"} or track not in {"beta", "production"}:
            raise ReleasePlanError("Select prepare/publish and beta/production")
        override = inputs.get("version_code", "")
        if not isinstance(override, str):
            raise ReleasePlanError("version_code must be a positive decimal integer")
        if override:
            if not re.fullmatch(r"[1-9][0-9]*", override):
                raise ReleasePlanError("version_code must be a positive decimal integer")
            code = int(override)
            if not default_code <= code <= MAX_VERSION_CODE:
                raise ReleasePlanError("version_code must be at least the checked-in code and at most 2100000000")
        if mode == "publish" and not publishing_enabled:
            raise ReleasePlanError("Set VENDROID_PLAY_PUBLISHING_ENABLED=true after completing Play setup")
        if mode == "publish" and track == "production" and not production_enabled:
            raise ReleasePlanError("Set VENDROID_PLAY_PRODUCTION_ENABLED=true after Play grants production access")
        publish = mode == "publish"
        execute = True
        release_name = base_version + ("-beta" if track == "beta" else "")
    else:
        raise ReleasePlanError("Only published releases and manual dispatch can distribute bundles")

    if publish and track == "production":
        stable_gpt = re.findall(r"^val stableGptEnabled = (true|false)$", gradle_source, re.MULTILINE)
        if stable_gpt != ["true"]:
            raise ReleasePlanError("Production requires the completed stable GPT promotion gate")
        if code != default_code:
            raise ReleasePlanError("Production uses the checked-in code of the published final release")
        if event_name == "workflow_dispatch":
            if (
                not isinstance(published_stable_release, dict)
                or published_stable_release.get("draft") is not False
                or published_stable_release.get("prerelease") is not False
                or published_stable_release.get("tag_name") != f"v{base_version}"
                or published_stable_commit != commit
            ):
                raise ReleasePlanError("Production dispatch requires a published stable GitHub release for this exact commit")

    return ReleasePlan(
        commit=commit,
        track=track,
        variant="preview" if track == "beta" else "release",
        version_code=code,
        version_name=base_version + ("-beta" if track == "beta" else ""),
        release_name=release_name,
        publish=publish,
        execute=execute,
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--event-name", required=True)
    parser.add_argument("--event-file", type=Path, required=True)
    parser.add_argument("--gradle-file", type=Path, required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--expected-commit", required=True)
    parser.add_argument("--publishing-enabled", default="false")
    parser.add_argument("--production-enabled", default="false")
    parser.add_argument("--plan-output", type=Path, required=True)
    parser.add_argument("--github-output", type=Path, required=True)
    parser.add_argument("--published-stable-release", type=Path)
    parser.add_argument("--published-stable-commit")
    args = parser.parse_args()
    try:
        plan = resolve_plan(
            args.event_name,
            json.loads(args.event_file.read_text(encoding="utf-8")),
            gradle_source=args.gradle_file.read_text(encoding="utf-8"),
            commit=args.commit,
            expected_commit=args.expected_commit,
            publishing_enabled=args.publishing_enabled == "true",
            production_enabled=args.production_enabled == "true",
            published_stable_release=(json.loads(args.published_stable_release.read_text(encoding="utf-8"))
                                      if args.published_stable_release else None),
            published_stable_commit=args.published_stable_commit,
        )
        args.plan_output.write_text(json.dumps(asdict(plan), indent=2) + "\n", encoding="utf-8")
        with args.github_output.open("a", encoding="utf-8") as output:
            for key, value in plan.outputs().items():
                output.write(f"{key}={value}\n")
        if not plan.execute:
            print("Skipping Play: enable VENDROID_PLAY_PUBLISHING_ENABLED, and VENDROID_PLAY_PRODUCTION_ENABLED for production.")
        else:
            print(f"{'Publish' if plan.publish else 'Prepare'} {plan.track} version {plan.version_name} ({plan.version_code}) at {plan.commit}")
        return 0
    except (ReleasePlanError, OSError, json.JSONDecodeError) as error:
        print(f"Release planning failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
