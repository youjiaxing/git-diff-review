"""Build evidence and immutable GitHub releases. Uses the runner's scoped GITHUB_TOKEN."""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from zipfile import ZipFile

OWNER = "git-diff-review/actions-v1"
WORKFLOW = ".github/workflows/build.yml"
MANIFEST = "release-manifest.json"
MARKER = "<!-- git-diff-review-release-v1\n"
VERSION = re.compile(r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)")
SHA = re.compile(r"[0-9a-f]{40}")


def version(value):
    if not VERSION.fullmatch(value):
        raise ValueError(f"Expected a stable major.minor.patch version: {value!r}")
    return tuple(map(int, value.split(".")))


def properties(text):
    values = re.findall(r"^pluginVersion=(.+)$", text, re.M)
    if len(values) != 1:
        raise ValueError("Expected exactly one pluginVersion")
    value = values[0].strip()
    version(value)
    return value


def git(*args):
    return subprocess.check_output(["git", *args], text=True, encoding="utf-8").strip()


def ancestor(before, after):
    if not SHA.fullmatch(before) or not SHA.fullmatch(after):
        raise ValueError("Invalid commit identity")
    return subprocess.run(["git", "merge-base", "--is-ancestor", before, after], capture_output=True).returncode == 0


def affects_package(path):
    # Documentation has an explicit exemption. New, unclassified files are conservative.
    if path.startswith("src/main/"):
        return True
    return not (path.startswith("docs/") or path.endswith(".md") or path in {"LICENSE", ".gitignore", ".gitattributes"})


def changed_paths(before, after):
    raw = subprocess.check_output(["git", "diff", "--name-only", "-z", before, after])
    return [p.decode("utf-8") for p in raw.split(b"\0") if p]


def gate_only(before, after, paths):
    if [p for p in paths if affects_package(p)] != [".github/release-policy.json"]:
        return False
    old = json.loads(git("show", f"{before}:.github/release-policy.json"))
    new = json.loads(git("show", f"{after}:.github/release-policy.json"))
    return old == {"automatic": False} and new == {"automatic": True}


class Api:
    def __init__(self, repo):
        if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo):
            raise ValueError("Invalid repository")
        self.repo = repo

    def call(self, path, method="GET", data=None, binary=None, content_type=None):
        command = ["gh", "api", path, "--method", method]
        payload = None
        if data is not None:
            payload = json.dumps(data).encode()
        if isinstance(binary, bytes):
            payload = binary
        if payload is not None:
            command += ["--input", "-"]
        if content_type:
            command += ["-H", "Content-Type: " + content_type]
        if method == "GET" and binary is False:
            command += ["-H", "Accept: application/octet-stream"]
        result = subprocess.run(command, input=payload, capture_output=True)
        if result.returncode:
            error = result.stderr.decode("utf-8", errors="replace")
            if "HTTP 404" in error:
                raise FileNotFoundError(path)
            raise RuntimeError(f"GitHub {method} {path}: {error.strip()}")
        return result.stdout if binary is False else json.loads(result.stdout or b"null")

    def releases(self):
        result = []
        page = 1
        while True:
            batch = self.call(f"repos/{self.repo}/releases?per_page=100&page={page}")
            result.extend(batch)
            if len(batch) < 100:
                return result
            page += 1

    def asset(self, asset):
        return self.call(f"repos/{self.repo}/releases/assets/{asset['id']}", binary=False)

    def upload(self, release, name, data):
        # The upload API requires a known Content-Length; gh's streaming `api --input -`
        # can use chunked encoding. `release upload` sends the size of the actual file.
        if Path(name).name != name:
            raise ValueError("Invalid asset name")
        with tempfile.TemporaryDirectory(prefix="diff-review-upload-") as directory:
            path = Path(directory) / name
            path.write_bytes(data)
            result = subprocess.run(["gh", "release", "upload", release["tag_name"], str(path), "--repo", self.repo], capture_output=True)
            if result.returncode:
                raise RuntimeError("Asset upload failed: " + result.stderr.decode("utf-8", errors="replace").strip())


def ownership(release):
    body = release.get("body") or ""
    if MARKER not in body:
        return None
    try:
        raw = body.split(MARKER, 1)[1].split("\n-->", 1)[0]
        evidence = json.loads(raw)
        validate_manifest(evidence)
        if release["tag_name"] != "v" + evidence["version"]:
            return None
        return evidence
    except (ValueError, KeyError, TypeError):
        return None


def validate_manifest(manifest):
    version(manifest["version"])
    if manifest["owner"] != OWNER or not SHA.fullmatch(manifest["commit"]):
        raise ValueError("Invalid release ownership or commit")
    if not isinstance(manifest["run_id"], int) or manifest["run_id"] <= 0:
        raise ValueError("Invalid build run identity")
    if manifest["schema"] != 1 or manifest["workflow"] != WORKFLOW or not isinstance(manifest["run_attempt"], int) or manifest["run_attempt"] <= 0:
        raise ValueError("Invalid build provenance")
    if manifest["file"] != f"git-diff-review-{manifest['version']}.zip" or manifest["size"] <= 0:
        raise ValueError("Invalid install archive identity")
    if not re.fullmatch("[0-9a-f]{64}", manifest["sha256"]):
        raise ValueError("Invalid archive digest")


def inspect_zip(path, expected_version):
    version(expected_version)
    with ZipFile(path) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)) or any("\\" in n or PurePosixPath(n).is_absolute() or ".." in PurePosixPath(n).parts for n in names):
            raise ValueError("Duplicate or unsafe archive entry")
        files = [n for n in names if not n.endswith("/")]
        jars = [n for n in files if n.endswith(".jar")]
        if not jars or any(not n.startswith("git-diff-review/") for n in files):
            raise ValueError("Expected the plugin directory, not an Actions artifact wrapper")
        descriptors = []
        for name in jars:
            if not name.startswith("git-diff-review/lib/"):
                raise ValueError("JAR outside plugin lib directory")
            with ZipFile(io.BytesIO(archive.read(name))) as jar:
                if any("/smoke/" in n or n.endswith("Test.class") for n in jar.namelist()):
                    raise ValueError("Test code leaked into the production plugin")
                if "META-INF/plugin.xml" in jar.namelist():
                    descriptors.append(ET.fromstring(jar.read("META-INF/plugin.xml")))
        if len(descriptors) != 1:
            raise ValueError("Expected exactly one plugin descriptor")
        descriptor = descriptors[0]
        if descriptor.findtext("id") != "io.github.youjiaxing.git-diff-review" or descriptor.findtext("version") != expected_version:
            raise ValueError("Plugin identity/version does not match the release")
        compatibility = descriptor.find("idea-version")
        if compatibility is None or compatibility.get("since-build") != "262" or compatibility.get("until-build") != "262.*":
            raise ValueError("Unexpected IDE compatibility range")
    return hashlib.sha256(path.read_bytes()).hexdigest()


def make_manifest(directory, commit, run_id):
    expected = properties(Path("gradle.properties").read_text(encoding="utf-8"))
    archives = list(directory.glob("*.zip"))
    if len(archives) != 1 or archives[0].name != f"git-diff-review-{expected}.zip":
        raise ValueError("Expected exactly one correctly named plugin ZIP")
    archive = archives[0]
    result = dict(owner=OWNER, schema=1, workflow=WORKFLOW, repository=os.environ.get("GITHUB_REPOSITORY", "youjiaxing/git-diff-review"),
                  version=expected, commit=commit, run_id=int(run_id), run_attempt=int(os.environ.get("GITHUB_RUN_ATTEMPT", "1")),
                  file=archive.name, size=archive.stat().st_size, sha256=inspect_zip(archive, expected))
    validate_manifest(result)
    (directory / MANIFEST).write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    (directory / (archive.name + ".sha256")).write_text(f"{result['sha256']}  {archive.name}\n", encoding="utf-8")
    return result


def verify_files(directory, expected_sha=None, expected_digest=None):
    manifest = json.loads((directory / MANIFEST).read_text(encoding="utf-8"))
    validate_manifest(manifest)
    path = directory / manifest["file"]
    digest = inspect_zip(path, manifest["version"])
    if path.stat().st_size != manifest["size"] or digest != manifest["sha256"]:
        raise ValueError("Archive no longer matches its original build evidence")
    if expected_sha and manifest["commit"] != expected_sha or expected_digest and digest != expected_digest:
        raise ValueError("Archive is not the exact build that was validated")
    if (directory / (path.name + ".sha256")).read_text(encoding="utf-8").strip() != f"{digest}  {path.name}":
        raise ValueError("Checksum file mismatch")
    return manifest


def source_artifact(api, run_id, commit, attempt=None):
    run = verify_run(api, run_id, commit, attempt)
    artifacts = api.call(f"repos/{api.repo}/actions/runs/{run_id}/artifacts?per_page=100")["artifacts"]
    name = f"plugin-{commit}-{run['run_attempt']}"
    matching = [a for a in artifacts if a["name"] == name and not a["expired"] and a["workflow_run"]["id"] == int(run_id)
                and a["workflow_run"]["head_sha"] == commit and a["workflow_run"]["repository_id"] == run["repository"]["id"]]
    if len(matching) != 1:
        raise ValueError("Expected one original, unexpired artifact from this successful run attempt")
    return matching[0]


def tag_commit(api, tag):
    try:
        obj = api.call(f"repos/{api.repo}/git/ref/tags/{tag}")["object"]
    except FileNotFoundError:
        return None
    while obj["type"] == "tag":
        obj = api.call(f"repos/{api.repo}/git/tags/{obj['sha']}")["object"]
    if obj["type"] != "commit":
        raise ValueError("Tag does not resolve to a commit")
    return obj["sha"]


def decide(api, target):
    current = properties(git("show", f"{target}:gradle.properties"))
    history = git("rev-list", "--first-parent", target).splitlines()
    for newer, older in zip(history, history[1:]):
        try:
            old_text = git("show", f"{older}:gradle.properties")
        except subprocess.CalledProcessError:
            break
        if version(properties(git("show", f"{newer}:gradle.properties"))) < version(properties(old_text)):
            raise ValueError("Version moved backwards in main history")
    releases = [(r, ownership(r)) for r in api.releases() if not r["draft"] and not r["prerelease"]]
    owned = [(r, m) for r, m in releases if m is not None]
    candidates = [(r, m) for r, m in owned if ancestor(m["commit"], target)]
    candidates.sort(key=lambda pair: version(pair[1]["version"]), reverse=True)
    if not candidates:
        return current, True, "bootstrap: no released ancestor"
    release, baseline = candidates[0]
    if tag_commit(api, release["tag_name"]) != baseline["commit"]:
        raise ValueError("Published baseline tag changed")
    paths = changed_paths(baseline["commit"], target)
    relevant = any(affects_package(p) for p in paths) and not gate_only(baseline["commit"], target, paths)
    if version(current) < version(baseline["version"]):
        raise ValueError("Version moved backwards")
    if current == baseline["version"]:
        if relevant:
            raise ValueError("Unreleased package/validation changes require a version bump")
        if target == baseline["commit"]:
            verify_remote(api, release, baseline)
        return current, False, "already released; no package changes"
    return current, True, "new version"


def verify_run(api, run_id, commit, attempt=None):
    endpoint = f"repos/{api.repo}/actions/runs/{int(run_id)}"
    if attempt is not None:
        endpoint += f"/attempts/{int(attempt)}"
    run = api.call(endpoint)
    if run["head_sha"] != commit or run["head_branch"] != "main" or run["status"] != "completed" \
            or run["head_repository"]["full_name"] != api.repo or run["repository"]["full_name"] != api.repo \
            or run["head_repository"]["id"] != run["repository"]["id"] or run["path"].split("@", 1)[0] != WORKFLOW \
            or run["event"] not in {"push", "workflow_dispatch"}:
        raise ValueError("Source artifact must come from a successful trusted main build of this workflow")
    workflow = api.call(f"repos/{api.repo}/actions/workflows/build.yml")
    if run["workflow_id"] != workflow["id"]:
        raise ValueError("Unexpected workflow identity")
    if run["conclusion"] != "success":
        jobs = api.call(f"repos/{api.repo}/actions/runs/{int(run_id)}/attempts/{run['run_attempt']}/jobs?per_page=100")["jobs"]
        builds = [j for j in jobs if j["name"] == "build"]
        if len(builds) != 1 or builds[0]["conclusion"] != "success":
            raise ValueError("The original build job did not complete successfully")
    return run


def verify_remote(api, release, manifest):
    expected = {manifest["file"], manifest["file"] + ".sha256", MANIFEST}
    assets = release["assets"]
    if len(assets) != 3 or {a["name"] for a in assets} != expected or any(a["state"] != "uploaded" for a in assets):
        raise ValueError("Incomplete or unexpected release assets")
    by_name = {a["name"]: a for a in assets}
    if json.loads(api.asset(by_name[MANIFEST])) != manifest:
        raise ValueError("Published manifest mismatch")
    data = api.asset(by_name[manifest["file"]])
    if len(data) != manifest["size"] or hashlib.sha256(data).hexdigest() != manifest["sha256"]:
        raise ValueError("Remote installation archive digest mismatch")
    checksum = api.asset(by_name[manifest["file"] + ".sha256"]).decode().strip()
    if checksum != f"{manifest['sha256']}  {manifest['file']}":
        raise ValueError("Remote checksum mismatch")


def publish(api, directory):
    manifest = verify_files(directory)
    if manifest["repository"] != api.repo:
        raise ValueError("Build belongs to a different repository")
    tag = "v" + manifest["version"]
    existing_tag = tag_commit(api, tag)
    if existing_tag is not None and existing_tag != manifest["commit"]:
        raise ValueError("Tag collision: refusing to move a version tag")
    existing = next((r for r in api.releases() if r["tag_name"] == tag), None)
    if existing:
        original = ownership(existing)
        if original is None or original["commit"] != manifest["commit"]:
            raise ValueError("Release collision: unrelated or different commit")
        if not existing["draft"]:
            verify_remote(api, existing, original)
            print("Verified existing immutable release: " + existing["html_url"])
            return
        if original != manifest:
            raise ValueError("Draft recovery requires the original build evidence and bytes")
    else:
        if existing_tag is None:
            api.call(f"repos/{api.repo}/git/refs", "POST", {"ref": "refs/tags/" + tag, "sha": manifest["commit"]})
        notes = api.call(f"repos/{api.repo}/releases/generate-notes", "POST", {"tag_name": tag, "target_commitish": manifest["commit"]})["body"]
        body = notes + "\n\n验证范围：GoLand 262。ZIP 可通过 IDE「从磁盘安装插件」导入；其他 JetBrains IDE 尚未验证。\n\n" + MARKER + json.dumps(manifest, sort_keys=True) + "\n-->"
        existing = api.call(f"repos/{api.repo}/releases", "POST", {"tag_name": tag, "target_commitish": manifest["commit"],
                            "name": "Git Diff Review " + manifest["version"], "body": body, "draft": True, "prerelease": False, "make_latest": "false"})
    assets = {a["name"]: a for a in existing["assets"]}
    expected = {manifest["file"], manifest["file"] + ".sha256", MANIFEST}
    if len(assets) != len(existing["assets"]) or set(assets) - expected:
        raise ValueError("Unexpected draft assets; refusing to overwrite")
    for name in sorted(expected):
        data = (directory / name).read_bytes()
        if name in assets:
            if assets[name]["state"] == "starter" and assets[name].get("size", 0) == 0:
                api.call(f"repos/{api.repo}/releases/assets/{assets[name]['id']}", "DELETE")
                api.upload(existing, name, data)
            elif api.asset(assets[name]) != data:
                raise ValueError("Draft asset differs from original; refusing to overwrite")
        else:
            api.upload(existing, name, data)
    ready = api.call(f"repos/{api.repo}/releases/{existing['id']}")
    verify_remote(api, ready, manifest)
    if tag_commit(api, tag) != manifest["commit"]:
        raise ValueError("Tag changed during upload")
    done = api.call(f"repos/{api.repo}/releases/{existing['id']}", "PATCH", {"draft": False, "make_latest": "false"})
    verify_remote(api, done, manifest)
    print("Published: " + done["html_url"])


def latest(api):
    releases = [(r, ownership(r)) for r in api.releases() if not r["draft"] and not r["prerelease"]]
    owned = [(r, m) for r, m in releases if m]
    if not owned:
        return
    release, manifest = max(owned, key=lambda pair: version(pair[1]["version"]))
    if tag_commit(api, release["tag_name"]) != manifest["commit"]:
        raise ValueError("Latest candidate has a mismatched tag")
    verify_remote(api, release, manifest)
    api.call(f"repos/{api.repo}/releases/{release['id']}", "PATCH", {"make_latest": "true"})
    if api.call(f"repos/{api.repo}/releases/latest")["id"] != release["id"]:
        raise ValueError("Latest reconciliation was not applied")
    print("Latest: " + release["html_url"])


def recover_draft(api, directory, current_sha):
    """Repair a previously owned draft using its original successful build and bytes."""
    receipt_path = Path("docs/release-recovery.json")
    if not receipt_path.exists():
        print("No draft recovery requested")
        return
    receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
    version(receipt["version"])
    tag = "v" + receipt["version"]
    existing = next((r for r in api.releases() if r["tag_name"] == tag), None)
    manifest = ownership(existing) if existing else None
    if manifest is None or manifest["repository"] != api.repo or manifest["commit"] != receipt["commit"] \
            or manifest["sha256"] != receipt["sha256"] or manifest["run_id"] != receipt["run_id"] \
            or tag_commit(api, tag) != manifest["commit"] or not ancestor(manifest["commit"], current_sha):
        raise ValueError("Recovery is limited to an existing owned draft and its original build")
    if not existing["draft"]:
        verify_remote(api, existing, manifest)
        print("Recovery already completed: " + existing["html_url"])
        return
    artifact = source_artifact(api, manifest["run_id"], manifest["commit"], manifest["run_attempt"])
    if artifact["id"] != receipt["artifact_id"]:
        raise ValueError("Recovery artifact does not match the recorded original")
    data = api.call(f"repos/{api.repo}/actions/artifacts/{artifact['id']}/zip", binary=False)
    directory.mkdir(parents=True, exist_ok=True)
    expected = {manifest["file"], manifest["file"] + ".sha256", MANIFEST}
    with ZipFile(io.BytesIO(data)) as archive:
        files = [n for n in archive.namelist() if not n.endswith("/")]
        if len(files) != 3 or set(files) != expected:
            raise ValueError("Unexpected recovery artifact contents")
        for name in files:
            (directory / name).write_bytes(archive.read(name))
    if verify_files(directory, manifest["commit"], manifest["sha256"]) != manifest:
        raise ValueError("Recovery must use the original draft manifest")
    publish(api, directory)


def output(**values):
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as stream:
            for key, value in values.items():
                stream.write(f"{key}={value}\n")
    print(json.dumps(values, ensure_ascii=False))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["plan", "manifest", "verify", "publish", "latest", "recover"])
    parser.add_argument("--directory", type=Path, default=Path("build/distributions"))
    parser.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY", "youjiaxing/git-diff-review"))
    parser.add_argument("--sha", default=os.environ.get("GITHUB_SHA"))
    parser.add_argument("--run-id", default=os.environ.get("GITHUB_RUN_ID"))
    args = parser.parse_args()
    if args.command == "manifest":
        print(json.dumps(make_manifest(args.directory, args.sha, args.run_id)))
    elif args.command == "verify":
        manifest = verify_files(args.directory, args.sha, os.environ.get("EXPECTED_SHA256") or None)
        print(json.dumps(manifest))
    else:
        api = Api(args.repo)
        if args.command == "plan":
            if os.environ.get("GITHUB_REF") != "refs/heads/main":
                output(publish="false", version=properties(Path("gradle.properties").read_text()), sha=args.sha, source_run="")
                return
            source_run = os.environ.get("SOURCE_RUN_ID", "")
            publish_requested = os.environ.get("PUBLISH_REQUESTED") == "true"
            policy = json.loads(Path(".github/release-policy.json").read_text())
            target = args.sha
            artifact_id = ""
            digest = os.environ.get("EXPECTED_SHA256", "")
            receipt = None
            receipt_path = Path("docs/release-bootstrap.json")
            if policy["automatic"] and not source_run and receipt_path.exists():
                recorded = json.loads(receipt_path.read_text(encoding="utf-8"))
                current = properties(git("show", f"{target}:gradle.properties"))
                published = [ownership(r) for r in api.releases() if not r["draft"] and not r["prerelease"]]
                if recorded["version"] == current and not any(m and m["version"] == current for m in published):
                    if recorded["repository"] != api.repo or recorded["validation"] != "isolated-goland-262-pass" or recorded["schema"] != 1:
                        raise ValueError("Invalid first-release validation receipt")
                    receipt = recorded
                    source_run = str(recorded["run_id"])
                    digest = recorded["sha256"]
                    publish_requested = True
            recovery_attempt = None
            # Re-running all jobs must reuse the original successful build, not replace a draft's bytes.
            if not source_run and int(os.environ.get("GITHUB_RUN_ATTEMPT", "1")) > 1:
                artifacts = api.call(f"repos/{api.repo}/actions/runs/{args.run_id}/artifacts?per_page=100")["artifacts"]
                prefix = f"plugin-{target}-"
                attempts = sorted(int(a["name"][len(prefix):]) for a in artifacts if a["name"].startswith(prefix)
                                  and a["name"][len(prefix):].isdigit() and not a["expired"])
                if attempts:
                    source_run = args.run_id
                    recovery_attempt = attempts[0]
            if source_run:
                if recovery_attempt is None and (not publish_requested or receipt is None and os.environ.get("GITHUB_EVENT_NAME") != "workflow_dispatch"
                        or not re.fullmatch(r"[0-9]+", source_run) or not re.fullmatch(r"[0-9a-f]{64}", digest)):
                    raise ValueError("Bootstrap requires explicit main dispatch, source run and validated SHA-256")
                run = api.call(f"repos/{api.repo}/actions/runs/{source_run}")
                target = run["head_sha"]
                artifact_id = str(source_artifact(api, source_run, target, recovery_attempt)["id"])
                if receipt is not None and (receipt["commit"] != target or str(receipt["artifact_id"]) != artifact_id):
                    raise ValueError("Validation receipt does not identify the original build artifact")
                if not ancestor(target, args.sha):
                    raise ValueError("Source build is not an ancestor of current main")
                paths = changed_paths(target, args.sha)
                if any(affects_package(p) for p in paths) and not gate_only(target, args.sha, paths):
                    raise ValueError("Source build predates package or validation changes")
            elif publish_requested:
                raise ValueError("Manual publication requires the exact previously validated source artifact")
            current, needed, reason = decide(api, target)
            output(publish=str(needed and (policy["automatic"] or publish_requested)).lower(), version=current, sha=target,
                   source_run=source_run, artifact_id=artifact_id, digest=digest, reason=reason)
        elif args.command == "publish":
            manifest = verify_files(args.directory)
            # Current-run builds are still running; prior-run bootstrap artifacts must be successful.
            if str(manifest["run_id"]) != args.run_id:
                verify_run(api, manifest["run_id"], manifest["commit"], manifest["run_attempt"])
            if manifest["commit"] != args.sha:
                raise ValueError("Release does not match the selected build commit")
            publish(api, args.directory)
        elif args.command == "recover":
            if os.environ.get("GITHUB_REF") != "refs/heads/main":
                raise ValueError("Draft recovery requires trusted main")
            recover_draft(api, args.directory, args.sha)
        else:
            latest(api)


if __name__ == "__main__":
    try:
        main()
    except Exception as failure:
        print(f"ERROR: {failure}", file=sys.stderr)
        sys.exit(1)
