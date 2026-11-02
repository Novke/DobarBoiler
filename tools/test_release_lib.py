"""python3 -m unittest discover -s tools -p 'test_*.py'"""
import json
import tempfile
import unittest
from pathlib import Path

import release_lib as rl


def pr(number, body, merge="m", head="h", title="Naslov", merged_at="2026-11-02T10:00:00Z"):
    return {"number": number, "title": title, "body": body, "mergedAt": merged_at,
            "mergeCommit": {"oid": f"{merge}{number}"}, "headRefOid": f"{head}{number}"}


class ParseTest(unittest.TestCase):

    def test_block_with_label_and_bullets(self):
        b = rl.parse_release_block("## Release\nRelease: minor\nChangelog:\n- Prvo\n- Drugo\n\n## Provera\n- nije deo")
        self.assertEqual(b.bump, "minor")
        self.assertEqual(b.changelog, ["Prvo", "Drugo"])

    def test_bullets_capped_at_three(self):
        b = rl.parse_release_block("Release: patch\n- a\n- b\n- c\n- d")
        self.assertEqual(b.changelog, ["a", "b", "c"])

    def test_missing_or_invalid_block(self):
        self.assertIsNone(rl.parse_release_block("nema bloka"))
        self.assertIsNone(rl.parse_release_block("Release: huge\n- x"))
        self.assertIsNone(rl.parse_release_block(None))

    def test_none_needs_no_bullets(self):
        self.assertEqual(rl.parse_release_block("Release: none").bump, "none")


class PlanTest(unittest.TestCase):

    def test_biggest_bump_wins_and_changelog_in_merge_order(self):
        prs = [
            pr(12, "Release: minor\n- Ažuriranje iz aplikacije", merged_at="2026-11-02T12:00:00Z"),
            pr(10, "Release: patch\n- Ispravljeno: bojler se nije palio", merged_at="2026-11-02T09:00:00Z"),
            pr(9, "Release: none", merged_at="2026-11-02T10:00:00Z"),
        ]
        p = rl.plan("0.1.0", prs, {"m12", "m10", "m9"})
        self.assertEqual((p["bump"], p["version"]), ("minor", "0.2.0"))
        self.assertEqual([c["pr"] for c in p["changelog"]], [10, 12])

    def test_pr_counted_by_head_commit_when_merged_through_another_pr(self):
        p = rl.plan("0.1.0", [pr(10, "Release: patch\n- x")], {"h10"})
        self.assertEqual(p["version"], "0.1.1")

    def test_prs_outside_the_range_are_ignored(self):
        p = rl.plan("0.1.0", [pr(8, "Release: major\n- staro")], {"m10"})
        self.assertIsNone(p["version"])
        self.assertEqual(p["prs"], [])

    def test_missing_block_counts_as_patch_with_title(self):
        p = rl.plan("0.0.0", [pr(11, "bez bloka", title="feat: istorija")], {"m11"})
        self.assertEqual(p["version"], "0.0.1")
        self.assertEqual(p["changelog"], [{"pr": 11, "text": "feat: istorija"}])
        self.assertTrue(p["warnings"])

    def test_only_none_ships_nothing(self):
        p = rl.plan("0.3.0", [pr(9, "Release: none")], {"m9"})
        self.assertIsNone(p["version"])

    def test_bumps(self):
        self.assertEqual(rl.bump_version("v1.2.3", "major"), "2.0.0")
        self.assertEqual(rl.bump_version("1.2.3", "minor"), "1.3.0")
        self.assertEqual(rl.bump_version("1.2.3", "patch"), "1.2.4")


class PublishTest(unittest.TestCase):

    def test_history_newest_first_and_republish_replaces(self):
        with tempfile.TemporaryDirectory() as d:
            out = Path(d)
            (out / "dobarboiler-0.2.0.apk").write_bytes(b"x")
            rl.publish(out, {"version": "0.1.0", "versionCode": 1, "file": "dobarboiler-0.1.0.apk", "changelog": []})
            rl.publish(out, {"version": "0.2.0", "versionCode": 2, "file": "dobarboiler-0.2.0.apk",
                             "changelog": [{"pr": 12, "text": "Ažuriranje <iz> aplikacije"}]})
            rl.publish(out, {"version": "0.2.0", "versionCode": 3, "file": "dobarboiler-0.2.0.apk", "changelog": []})
            history = json.loads((out / "versions.json").read_text())
            self.assertEqual([(h["version"], h["versionCode"]) for h in history], [("0.2.0", 3), ("0.1.0", 1)])
            self.assertEqual(json.loads((out / "latest.json").read_text())["versionCode"], 3)
            self.assertEqual([h["file_kept"] for h in history], [True, False])
            self.assertIn("Instaliraj 0.2.0", (out / "index.html").read_text())


if __name__ == "__main__":
    unittest.main()
