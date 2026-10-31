"""GitHub release/dispatch routing tests; runs only in GitHub Actions."""

import unittest

from release_plan import ReleasePlanError, checked_in_version, resolve_plan


COMMIT = "a" * 40
GRADLE = 'val defaultVersionCode = 31\nval stableGptEnabled = true\n        versionName = "0.2.0"\n'


class ReleasePlanTest(unittest.TestCase):
    def plan(self, event, event_name="release", *, enabled=True, production=True, source=GRADLE,
             commit=COMMIT, published=None, published_commit=None):
        return resolve_plan(
            event_name, event, gradle_source=source, commit=commit,
            expected_commit=COMMIT, publishing_enabled=enabled, production_enabled=production,
            published_stable_release=published, published_stable_commit=published_commit,
        )

    def release(self, *, prerelease=False, draft=False, tag="v0.2.0"):
        return {"action": "published", "release": {
            "prerelease": prerelease, "draft": draft, "tag_name": tag,
        }}

    def test_published_stable_uses_production_and_checked_in_version(self):
        plan = self.plan(self.release())
        self.assertEqual(("production", "release", 31, "0.2.0"),
                         (plan.track, plan.variant, plan.version_code, plan.version_name))
        self.assertEqual("bundleFossRelease", plan.bundle_task)
        self.assertTrue(plan.publish)
        self.assertTrue(plan.execute)

    def test_prerelease_uses_beta_capabilities_and_same_version_code_sequence(self):
        plan = self.plan(self.release(prerelease=True, tag="v0.2.0-beta.1"))
        self.assertEqual(("beta", "preview", 31, "0.2.0-beta.1"),
                         (plan.track, plan.variant, plan.version_code, plan.version_name))
        self.assertEqual("bundleFossPreview", plan.bundle_task)
        self.assertEqual("-beta.1", plan.outputs()["beta_suffix"])
        self.assertEqual("io.github.xntso.vendroid", plan.outputs()["package_name"])

    def test_unconfigured_automatic_release_does_not_build_or_publish(self):
        plan = self.plan(self.release(), enabled=False)
        self.assertFalse(plan.execute)
        self.assertFalse(plan.publish)

    def test_prerelease_tag_must_be_numbered_for_the_checked_in_version(self):
        for tag in ("v0.2.0-beta", "v0.2.0-beta.0", "v0.2.0-rc.1", "v0.2.0-beta.1.1", "v0.3.0-beta.1"):
            with self.subTest(tag=tag), self.assertRaises(ReleasePlanError):
                self.plan(self.release(prerelease=True, tag=tag))

    def test_stable_and_manual_plans_have_no_numbered_suffix(self):
        self.assertEqual("", self.plan(self.release()).outputs()["beta_suffix"])
        manual = self.plan({"inputs": {"mode": "prepare", "track": "beta"}}, "workflow_dispatch")
        self.assertEqual("-beta", manual.outputs()["beta_suffix"])

    def test_stable_release_skips_play_until_production_is_enabled(self):
        plan = self.plan(self.release(), production=False)
        self.assertFalse(plan.execute)
        self.assertFalse(plan.publish)

    def test_prerelease_does_not_need_production_access(self):
        plan = self.plan(self.release(prerelease=True, tag="v0.2.0-beta.2"), production=False)
        self.assertTrue(plan.execute)
        self.assertTrue(plan.publish)

    def test_manual_production_publish_requires_production_access(self):
        event = {"inputs": {"mode": "publish", "track": "production"}}
        with self.assertRaisesRegex(ReleasePlanError, "PRODUCTION_ENABLED"):
            self.plan(event, "workflow_dispatch", production=False,
                      published=self.release()["release"], published_commit=COMMIT)

    def test_first_setup_prepare_works_without_play_publishing_enabled(self):
        for track in ("beta", "production"):
            with self.subTest(track=track):
                plan = self.plan({"inputs": {"mode": "prepare", "track": track}},
                                 "workflow_dispatch", enabled=False)
                self.assertTrue(plan.execute)
                self.assertFalse(plan.publish)
                self.assertEqual(track, plan.track)

    def test_manual_publish_requires_setup_and_allows_both_tracks(self):
        for track in ("beta", "production"):
            event = {"inputs": {"mode": "publish", "track": track}}
            with self.assertRaises(ReleasePlanError):
                self.plan(event, "workflow_dispatch", enabled=False)
            self.assertTrue(self.plan(event, "workflow_dispatch", published=self.release()["release"],
                                     published_commit=COMMIT).publish)

    def test_production_requires_promotion_and_published_exact_commit(self):
        source = GRADLE.replace("stableGptEnabled = true", "stableGptEnabled = false")
        with self.assertRaisesRegex(ReleasePlanError, "promotion"):
            self.plan(self.release(), source=source)
        self.assertFalse(self.plan({"inputs": {"mode": "prepare", "track": "production"}},
                                  "workflow_dispatch", source=source).publish)
        self.assertTrue(self.plan(self.release(prerelease=True, tag="v0.2.0-beta.1"), source=source).publish)
        event = {"inputs": {"mode": "publish", "track": "production"}}
        for published, sha in ((None, None), (self.release(draft=True)["release"], COMMIT),
                               (self.release(prerelease=True)["release"], COMMIT),
                               (self.release()["release"], "b" * 40)):
            with self.assertRaisesRegex(ReleasePlanError, "published stable"):
                self.plan(event, "workflow_dispatch", published=published, published_commit=sha)
        with self.assertRaisesRegex(ReleasePlanError, "checked-in code"):
            self.plan({"inputs": {**event["inputs"], "version_code": "32"}}, "workflow_dispatch",
                      published=self.release()["release"], published_commit=COMMIT)

    def test_manual_version_override_is_explicit_and_bounded(self):
        for value in ("32", "2100000000"):
            plan = self.plan({"inputs": {"mode": "prepare", "version_code": value}},
                             "workflow_dispatch")
            self.assertEqual(int(value), plan.version_code)
        for value in ("30", "2100000001", "0", "-1", "31\n", "3e1", " 31", "00031"):
            with self.subTest(value=value), self.assertRaises(ReleasePlanError):
                self.plan({"inputs": {"version_code": value}}, "workflow_dispatch")

    def test_release_rejects_draft_unpublished_malformed_and_wrong_version(self):
        events = [self.release(draft=True), self.release(tag="v0.3.0"),
                  self.release(tag="v0.2.0-beta.1"),
                  {"action": "created", "release": self.release()["release"]},
                  {"action": "published", "release": {"tag_name": "v0.2.0"}}]
        for event in events:
            with self.subTest(event=event), self.assertRaises(ReleasePlanError):
                self.plan(event)

    def test_dispatch_rejects_unknown_mode_track_and_wrong_commit(self):
        for inputs in ({"mode": "promote"}, {"track": "internal"}, {"track": "beta\noutput=true"}):
            with self.assertRaises(ReleasePlanError):
                self.plan({"inputs": inputs}, "workflow_dispatch")
        with self.assertRaises(ReleasePlanError):
            self.plan(self.release(), commit="b" * 40)
        with self.assertRaises(ReleasePlanError):
            self.plan(self.release(), event_name="pull_request")

    def test_source_metadata_must_be_unambiguous(self):
        self.assertEqual((31, "0.2.0"), checked_in_version(GRADLE))
        for source in ("", GRADLE + GRADLE, GRADLE.replace("31", "2100000001")):
            with self.assertRaises(ReleasePlanError):
                checked_in_version(source)


if __name__ == "__main__":
    unittest.main()
