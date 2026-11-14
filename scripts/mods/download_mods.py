#!/usr/bin/env python3
"""Resolve and download versioned Fabric profiles; no third-party Python packages."""

import argparse
import hashlib
import json
import re
import shutil
import sys
import tempfile
import urllib.request
from pathlib import Path
from urllib.parse import urlencode, urlparse

ROOT = Path(__file__).resolve().parent
PROFILES = ROOT / "profiles"
API = "https://api.modrinth.com/v2"
HEADERS = {"User-Agent": "xn-mc-mod-downloader/2.0"}


def fetch(path):
    request = urllib.request.Request(f"{API}/{path}", headers=HEADERS)
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def profile_path(profile):
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_-]+", profile):
        raise ValueError("Profile must be version/name, e.g. 26.3/default")
    if profile.split("/")[0] in {".", ".."}:
        raise ValueError("Invalid profile version")
    return PROFILES / f"{profile}.json"


def load_profile(profile, chain=()):
    if profile in chain:
        raise ValueError(f"Profile inheritance cycle: {' -> '.join((*chain, profile))}")
    raw = json.loads(profile_path(profile).read_text())
    parent = load_profile(raw["extends"], (*chain, profile)) if raw.get("extends") else {}
    result = {**parent, **raw}
    if parent and any(raw.get(k, parent[k]) != parent[k] for k in ("mc_version", "fabric_loader")):
        raise ValueError("Inheritance must use the same Minecraft and Fabric Loader versions")
    mods = dict(parent.get("mods", {}))
    for slug in raw.get("remove", []):
        mods.pop(slug, None)
    for entry in raw.get("mods", []):
        entry = {"slug": entry} if isinstance(entry, str) else entry
        mods[entry["slug"]] = entry
    if not result.get("mc_version") or not result.get("fabric_loader") or not mods:
        raise ValueError(f"Incomplete profile: {profile}")
    result["mods"] = mods
    result["profile"] = profile
    return result


def fingerprint(profile):
    # Display text can change without invalidating downloads.
    selection = {k: profile[k] for k in ("profile", "mc_version", "fabric_loader", "mods")}
    return hashlib.sha256(json.dumps(selection, sort_keys=True).encode()).hexdigest()


def resolve(profile):
    selected, projects = {}, {}

    def project_info(slug):
        if slug not in projects:
            info = fetch(f"project/{slug}")
            projects[slug] = projects[info["id"]] = projects[info["slug"]] = info
        return projects[slug]

    # Explicit pins are collected before dependencies so a dependency cannot silently
    # replace a user's selected version.
    pins = {}
    for entry in profile["mods"].values():
        if entry.get("version_id"):
            pid = project_info(entry["slug"])["id"]
            pins[pid] = entry["version_id"]

    def add(slug, version_id=None):
        info = project_info(slug)
        pid = info["id"]
        if version_id and pid in pins and pins[pid] != version_id:
            raise ValueError(f"Conflicting version pins for {info['slug']}")
        version_id = version_id or pins.get(pid)
        if pid in selected:
            if version_id and selected[pid]["id"] != version_id:
                raise ValueError(f"Conflicting dependency versions for {info['slug']}")
            return
        if version_id:
            version = fetch(f"version/{version_id}")
        else:
            query = urlencode({"game_versions": json.dumps([profile["mc_version"]]), "loaders": '["fabric"]'})
            versions = fetch(f"project/{pid}/version?{query}")
            releases = [v for v in versions if v["version_type"] == "release"]
            if not releases:
                raise ValueError(f"No stable Fabric release for {info['slug']} on {profile['mc_version']}; pin a compatible prerelease explicitly")
            version = max(releases, key=lambda v: v["date_published"])
        if (version["project_id"] != pid or profile["mc_version"] not in version["game_versions"]
                or "fabric" not in version["loaders"]):
            raise ValueError(f"Incompatible version for {info['slug']}")
        selected[pid] = {**version, "slug": info["slug"]}
        for dep in version["dependencies"]:
            if dep["dependency_type"] != "required":
                continue
            dependency_id = dep.get("project_id")
            if not dependency_id and dep.get("version_id"):
                dependency_id = fetch(f"version/{dep['version_id']}")["project_id"]
            if not dependency_id:
                raise ValueError(f"Unresolvable dependency in {info['slug']}: {dep}")
            add(dependency_id, dep.get("version_id"))

    for entry in profile["mods"].values():
        add(entry["slug"], entry.get("version_id"))
    files = []
    for version in selected.values():
        for dep in version["dependencies"]:
            if dep["dependency_type"] == "incompatible" and dep.get("project_id") in selected:
                if not dep.get("version_id") or selected[dep["project_id"]]["id"] == dep["version_id"]:
                    raise ValueError(f"Incompatible mods: {version['slug']} and {dep['project_id']}")
        candidates = [f for f in version["files"] if f.get("primary")] or version["files"]
        if not candidates:
            raise ValueError(f"No download for {version['slug']}")
        file = candidates[0]
        validate_file(file)
        files.append({"slug": version["slug"], "project_id": version["project_id"],
                      "version_id": version["id"], "version": version["version_number"],
                      "filename": file["filename"], "url": file["url"], "sha512": file["hashes"]["sha512"]})
    if len({f["filename"] for f in files}) != len(files):
        raise ValueError("Duplicate jar filenames")
    return {"schema_version": 1, "profile": profile["profile"],
            "mc_version": profile["mc_version"], "fabric_loader": profile["fabric_loader"],
            "selection_sha256": fingerprint(profile), "files": sorted(files, key=lambda f: f["slug"])}


def validate_file(file):
    name = file["filename"]
    if Path(name).name != name or not name.endswith(".jar") or "\\" in name:
        raise ValueError(f"Unsafe jar filename: {name}")
    if urlparse(file["url"]).scheme != "https":
        raise ValueError("Download links must use HTTPS")
    digest = file.get("sha512") or file.get("hashes", {}).get("sha512", "")
    if not re.fullmatch(r"[a-fA-F0-9]{128}", digest):
        raise ValueError(f"Missing SHA-512 for {name}")


def lock_path(profile):
    return profile_path(profile).with_suffix(".lock.json")


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(mode="w", dir=path.parent, delete=False) as stream:
        temp = Path(stream.name)
        json.dump(value, stream, indent=2)
        stream.write("\n")
    try:
        temp.replace(path)
    finally:
        temp.unlink(missing_ok=True)


def read_lock(profile):
    lock = json.loads(lock_path(profile["profile"]).read_text())
    if lock.get("schema_version") != 1 or lock.get("selection_sha256") != fingerprint(profile):
        raise ValueError("Profile changed; run resolve before download")
    for key in ("profile", "mc_version", "fabric_loader"):
        if lock[key] != profile[key]:
            raise ValueError(f"Lock metadata mismatch: {key}")
    if not lock.get("files") or len({f["filename"] for f in lock["files"]}) != len(lock["files"]):
        raise ValueError("Lock must contain unique jar files")
    for file in lock["files"]:
        validate_file(file)
    return lock


def download_file(file, destination):
    request = urllib.request.Request(file["url"], headers=HEADERS)
    digest = hashlib.sha512()
    with urllib.request.urlopen(request, timeout=60) as response, destination.open("wb") as stream:
        while chunk := response.read(1024 * 1024):
            digest.update(chunk)
            stream.write(chunk)
    if digest.hexdigest().lower() != file["sha512"].lower():
        raise ValueError(f"Checksum mismatch: {file['filename']}")


def download_profile(lock, output):
    output = output.resolve()
    receipt = ".xn-mc-profile.json"
    if output.exists() and any(output.iterdir()):
        previous = output / receipt
        if not previous.is_file():
            raise ValueError(f"Refusing to replace an unmanaged mods directory: {output}")
        old = json.loads(previous.read_text())
        if old.get("profile") != lock["profile"]:
            raise ValueError("Use a separate directory for each profile")
        expected = {f["filename"] for f in old["files"]} | {receipt}
        extras = {p.name for p in output.iterdir()} - expected
        if extras:
            raise ValueError(f"Unmanaged files in output; move them first: {sorted(extras)}")
    output.parent.mkdir(parents=True, exist_ok=True)
    stage = Path(tempfile.mkdtemp(prefix=".xn-mc-stage-", dir=output.parent))
    backup = None
    try:
        for file in lock["files"]:
            print(f"  {file['slug']} {file['version']}", flush=True)
            download_file(file, stage / file["filename"])
        write_json(stage / receipt, lock)
        if output.exists():
            backup = Path(tempfile.mkdtemp(prefix=".xn-mc-backup-", dir=output.parent))
            backup.rmdir()
            output.rename(backup)
        try:
            stage.rename(output)
        except BaseException:
            if backup:
                backup.rename(output)
                backup = None
            raise
        if backup:
            shutil.rmtree(backup)
    finally:
        if stage.exists():
            shutil.rmtree(stage)
    print(f"Ready: {output}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["list", "show", "resolve", "download"], nargs="?", default="download")
    parser.add_argument("--profile", default="26.3/default", help="version/name (default: 26.3/default)")
    parser.add_argument("--output", type=Path, help="Dedicated mods directory (download only)")
    args = parser.parse_args()
    try:
        if args.command == "list":
            for path in sorted(PROFILES.glob("*/*.json")):
                if not path.name.endswith(".lock.json"):
                    profile = path.relative_to(PROFILES).with_suffix("").as_posix()
                    print(f"{profile}: {json.loads(path.read_text())['name']}")
            return 0
        profile = load_profile(args.profile)
        if args.command == "show":
            print(json.dumps(profile, indent=2))
        elif args.command == "resolve":
            lock = resolve(profile)
            write_json(lock_path(args.profile), lock)
            print(f"Locked {len(lock['files'])} mods: {lock_path(args.profile)}")
        else:
            lock = read_lock(profile)
            output = args.output or ROOT / "instances" / args.profile / "mods"
            download_profile(lock, output)
        return 0
    except (OSError, ValueError, KeyError) as error:
        print(f"Error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
