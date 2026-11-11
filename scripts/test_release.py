"""Release boundaries, using real temporary Git histories and an isolated API fake."""
import contextlib
import io
import json
import os
from pathlib import Path
import subprocess
import stat
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZipFile

import release


def package(path, number="0.3.7", extra=None):
    jar = io.BytesIO()
    with ZipFile(jar, "w") as archive:
        archive.writestr("META-INF/plugin.xml", f'<idea-plugin><id>io.github.youjiaxing.git-diff-review</id><version>{number}</version><idea-version since-build="262" until-build="262.*"/></idea-plugin>')
        archive.writestr("io/github/youjiaxing/diffnav/NavigationStartup.class", b"production")
        if extra:
            archive.writestr(extra, b"test")
    with ZipFile(path, "w") as archive:
        archive.writestr(f"git-diff-review/lib/git-diff-review-{number}.jar", jar.getvalue())


class FakeApi:
    repo = "youjiaxing/git-diff-review"

    def __init__(self):
        self.items, self.tags, self.blobs, self.mutations = [], {}, {}, []
        self.latest_id = None

    def releases(self):
        return self.items

    def asset(self, asset):
        return self.blobs[asset["id"]]

    def upload(self, item, name, data):
        key = len(self.blobs) + 1
        self.blobs[key] = data
        item["assets"].append(dict(id=key, name=name, state="uploaded"))
        self.mutations.append(("upload", name))

    def call(self, path, method="GET", data=None, **kwargs):
        if "/git/ref/tags/" in path:
            tag = path.rsplit("/", 1)[1]
            if tag not in self.tags:
                raise FileNotFoundError(path)
            return {"object": {"type": "commit", "sha": self.tags[tag]}}
        if path.endswith("/git/refs"):
            self.tags[data["ref"].removeprefix("refs/tags/")] = data["sha"]
            self.mutations.append(("tag", data["sha"]))
            return {}
        if path.endswith("/generate-notes"):
            return {"body": "Release notes"}
        if path.endswith("/releases") and method == "POST":
            item = dict(data, id=len(self.items) + 1, assets=[], html_url="https://github.com/test/release")
            self.items.append(item)
            self.mutations.append(("draft", item["id"]))
            return item
        if path.endswith("/releases/latest"):
            return next(r for r in self.items if r["id"] == self.latest_id)
        if "/releases/" in path:
            item = next(r for r in self.items if r["id"] == int(path.rsplit("/", 1)[1]))
            if method == "PATCH":
                item.update(data)
                self.mutations.append(("patch", dict(data)))
                if data.get("make_latest") == "true":
                    self.latest_id = item["id"]
            return item
        raise AssertionError((path, method, data))


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.cwd = Path.cwd()
        os.chdir(self.root)
        def cleanup():
            os.chdir(self.cwd)
            for path in self.root.rglob('*'):
                if path.is_file():
                    path.chmod(stat.S_IWRITE | stat.S_IREAD)
            self.temp.cleanup()
        self.addCleanup(cleanup)
        self.stdout = contextlib.redirect_stdout(io.StringIO())
        self.stdout.__enter__()
        self.addCleanup(self.stdout.__exit__, None, None, None)
        Path("gradle.properties").write_text("pluginVersion=0.3.7\n")
        self.out = Path("dist")
        self.out.mkdir()
        self.zip = self.out / "git-diff-review-0.3.7.zip"
        package(self.zip)
        self.manifest = release.make_manifest(self.out, "a" * 40, 101)
        self.api = FakeApi()

    def test_exact_package_and_manifest(self):
        self.assertEqual(release.verify_files(self.out), self.manifest)
        self.assertEqual(self.manifest["version"], "0.3.7")

    def test_wrong_version_rejected(self):
        with self.assertRaises(ValueError):
            release.inspect_zip(self.zip, "0.3.8")

    def test_smoke_code_rejected(self):
        package(self.zip, extra="io/github/youjiaxing/diffnav/smoke/SmokeActivity.class")
        with self.assertRaises(ValueError):
            release.inspect_zip(self.zip, "0.3.7")

    def test_changed_archive_rejected(self):
        self.zip.write_bytes(self.zip.read_bytes() + b"changed")
        with self.assertRaises(ValueError):
            release.verify_files(self.out)

    def test_draft_becomes_public_only_after_asset_verification(self):
        release.publish(self.api, self.out)
        self.assertFalse(self.api.items[0]["draft"])
        self.assertEqual(len(self.api.items[0]["assets"]), 3)
        self.assertEqual(self.api.mutations[-1], ("patch", {"draft": False, "make_latest": "false"}))

    def test_same_commit_rerun_verifies_existing_original_assets(self):
        release.publish(self.api, self.out)
        mutations = list(self.api.mutations)
        # A new build need not be byte-identical. Existing published assets remain authoritative.
        self.manifest["run_id"] = 102
        (self.out / release.MANIFEST).write_text(json.dumps(self.manifest))
        release.publish(self.api, self.out)
        self.assertEqual(self.api.mutations, mutations)

    def test_tag_collision_does_not_write(self):
        self.api.tags["v0.3.7"] = "b" * 40
        with self.assertRaises(ValueError):
            release.publish(self.api, self.out)
        self.assertEqual(self.api.mutations, [])

    def test_corrupt_public_asset_is_never_overwritten(self):
        release.publish(self.api, self.out)
        mutations = list(self.api.mutations)
        asset = next(a for a in self.api.items[0]["assets"] if a["name"].endswith(".zip"))
        self.api.blobs[asset["id"]] = b"corrupted"
        with self.assertRaises(ValueError):
            release.publish(self.api, self.out)
        self.assertEqual(self.api.mutations, mutations)

    def test_partial_draft_resumes_matching_assets(self):
        original = self.api.upload
        def fail_second(item, name, data):
            if len(item["assets"]) == 1:
                raise OSError("upload interrupted")
            original(item, name, data)
        self.api.upload = fail_second
        with self.assertRaises(OSError):
            release.publish(self.api, self.out)
        self.assertTrue(self.api.items[0]["draft"])
        self.api.upload = original
        release.publish(self.api, self.out)
        self.assertFalse(self.api.items[0]["draft"])

    def test_unrelated_draft_is_not_claimed(self):
        self.api.items.append(dict(tag_name="v0.3.7", body="Unrelated", draft=True))
        with self.assertRaises(ValueError):
            release.publish(self.api, self.out)
        self.assertEqual(self.api.mutations, [])

    def test_latest_uses_highest_version_when_old_build_finishes_last(self):
        release.publish(self.api, self.out)
        self.manifest["version"] = "0.3.8"
        self.manifest["commit"] = "b" * 40
        self.manifest["file"] = "git-diff-review-0.3.8.zip"
        Path("gradle.properties").write_text("pluginVersion=0.3.8\n")
        self.zip.unlink()
        package(self.out / self.manifest["file"], "0.3.8")
        release.make_manifest(self.out, "b" * 40, 102)
        release.publish(self.api, self.out)
        release.latest(self.api)
        self.assertEqual(self.api.latest_id, 2)
        self.api.items.reverse()
        release.latest(self.api)
        self.assertEqual(self.api.latest_id, 2)

    def commit(self, changes):
        for name, content in changes.items():
            path = Path(name)
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
        release.git("add", ".")
        release.git("-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-qm", "fixture")
        return release.git("rev-parse", "HEAD")

    def baseline(self):
        release.git("init", "-q", "--initial-branch=main")
        first = self.commit({".gitignore": "dist/\n", "src/main/example.java": "old", ".github/release-policy.json": '{"automatic":false}'})
        self.manifest["commit"] = first
        (self.out / release.MANIFEST).write_text(json.dumps(self.manifest))
        release.publish(self.api, self.out)
        return first

    def test_docs_do_not_require_tag_to_match_new_commit(self):
        self.baseline()
        target = self.commit({"README.md": "new docs"})
        self.assertFalse(release.decide(self.api, target)[1])

    def test_failed_plugin_change_then_docs_does_not_hide_missing_bump(self):
        self.baseline()
        self.commit({"src/main/example.java": "new"})
        target = self.commit({"README.md": "docs after a failed build"})
        with self.assertRaises(ValueError):
            release.decide(self.api, target)

    def test_new_version_includes_accumulated_changes(self):
        self.baseline()
        self.commit({"src/main/example.java": "new"})
        target = self.commit({"gradle.properties": "pluginVersion=0.3.8\n"})
        self.assertTrue(release.decide(self.api, target)[1])

    def test_only_enabling_verified_gate_is_exempt(self):
        self.baseline()
        target = self.commit({".github/release-policy.json": '{"automatic":true}'})
        self.assertFalse(release.decide(self.api, target)[1])

    def test_changed_validation_requires_bump(self):
        self.baseline()
        target = self.commit({"scripts/test_validation.py": "changed verification"})
        with self.assertRaises(ValueError):
            release.decide(self.api, target)

    def test_document_packaged_as_resource_is_not_exempt(self):
        self.baseline()
        target = self.commit({"src/main/resources/help.md": "included in the plugin"})
        with self.assertRaises(ValueError):
            release.decide(self.api, target)

    def test_docs_after_gate_enable_are_still_not_a_new_release(self):
        self.baseline()
        self.commit({".github/release-policy.json": '{"automatic":true}'})
        target = self.commit({"README.md": "document verified release"})
        self.assertFalse(release.decide(self.api, target)[1])

    def test_version_rollback_before_failed_release_is_rejected(self):
        self.baseline()
        self.commit({"gradle.properties": "pluginVersion=0.3.9\n"})
        target = self.commit({"gradle.properties": "pluginVersion=0.3.8\n"})
        with self.assertRaises(ValueError):
            release.decide(self.api, target)

    def trusted_source(self):
        repository = dict(id=123, full_name=self.api.repo)
        run = dict(head_sha="a" * 40, head_branch="main", status="completed", conclusion="success", repository=repository,
                   head_repository=repository, workflow_id=42, path=release.WORKFLOW, event="push", run_attempt=1)
        artifact = dict(id=11, name=f"plugin-{'a' * 40}-1", expired=False,
                        workflow_run=dict(id=101, head_sha="a" * 40, repository_id=123))
        def call(path):
            if path.endswith("artifacts?per_page=100"):
                return {"artifacts": [artifact]}
            if path.endswith("workflows/build.yml"):
                return {"id": 42}
            if path.endswith("jobs?per_page=100"):
                return {"jobs": [{"name": "build", "conclusion": run["conclusion"]}]}
            return run
        self.api.call = call
        return run, artifact

    def test_bootstrap_pins_artifact_id_from_successful_main_attempt(self):
        self.trusted_source()
        self.assertEqual(release.source_artifact(self.api, "101", "a" * 40)["id"], 11)

    def test_bootstrap_rejects_failed_or_foreign_run(self):
        run, _ = self.trusted_source()
        run["conclusion"] = "failure"
        with self.assertRaises(ValueError):
            release.source_artifact(self.api, "101", "a" * 40)
        run["conclusion"] = "success"
        run["head_repository"] = dict(id=456, full_name="fork/project")
        with self.assertRaises(ValueError):
            release.source_artifact(self.api, "101", "a" * 40)

    def test_bootstrap_rejects_expired_or_wrong_attempt_artifact(self):
        _, artifact = self.trusted_source()
        artifact["expired"] = True
        with self.assertRaises(ValueError):
            release.source_artifact(self.api, "101", "a" * 40)
        artifact["expired"] = False
        artifact["name"] = f"plugin-{'a' * 40}-2"
        with self.assertRaises(ValueError):
            release.source_artifact(self.api, "101", "a" * 40)

    def test_successful_build_is_recoverable_after_publish_failure(self):
        run, _ = self.trusted_source()
        original = self.api.call
        run["conclusion"] = "failure"
        def call(path):
            if path.endswith("jobs?per_page=100"):
                return {"jobs": [{"name": "build", "conclusion": "success"}, {"name": "publish", "conclusion": "failure"}]}
            return original(path)
        self.api.call = call
        self.assertEqual(release.source_artifact(self.api, "101", "a" * 40, 1)["id"], 11)


if __name__ == "__main__":
    unittest.main()
