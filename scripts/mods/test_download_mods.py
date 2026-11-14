"""Offline regression tests for profile isolation and reproducible downloads."""
import copy
import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import download_mods as mods


class ProfileTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        patcher = patch.object(mods, "PROFILES", self.root / "profiles")
        patcher.start()
        self.addCleanup(patcher.stop)

    def profile(self, profile_id, **fields):
        path = mods.profile_path(profile_id)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(fields))

    def base(self):
        self.profile("26.3/base", name="Base", mc_version="26.3", fabric_loader="0.20.1", mods=["one", "two"])
        return mods.load_profile("26.3/base")

    def test_inheritance_removal_override_and_isolation(self):
        self.base()
        self.profile("26.3/child", extends="26.3/base", remove=["two"], mods=[{"slug": "one", "version_id": "pinned"}, "three"])
        child = mods.load_profile("26.3/child")
        self.assertEqual(set(child["mods"]), {"one", "three"})
        self.assertEqual(child["mods"]["one"]["version_id"], "pinned")
        self.assertEqual(set(mods.load_profile("26.3/base")["mods"]), {"one", "two"})

    def test_multiple_game_versions(self):
        self.base()
        self.profile("26.1.2/base", mc_version="26.1.2", fabric_loader="0.19.3", mods=["one"])
        self.assertEqual(mods.load_profile("26.1.2/base")["mc_version"], "26.1.2")
        self.assertNotEqual(mods.profile_path("26.1.2/base"), mods.profile_path("26.3/base"))

    def test_cross_version_inheritance_and_cycles_rejected(self):
        self.base()
        self.profile("26.4/child", extends="26.3/base", mc_version="26.4")
        with self.assertRaisesRegex(ValueError, "same Minecraft"):
            mods.load_profile("26.4/child")
        self.profile("26.3/a", extends="26.3/b")
        self.profile("26.3/b", extends="26.3/a")
        with self.assertRaisesRegex(ValueError, "cycle"):
            mods.load_profile("26.3/a")

    def test_profile_path_traversal_rejected(self):
        for name in ["../default", "26.3/../../secret", "/tmp/default"]:
            with self.assertRaises(ValueError):
                mods.profile_path(name)

    def file(self, name="one.jar", data=b"jar"):
        return {"slug": "one", "version": "1", "filename": name,
                "url": "https://example.com/" + name, "sha512": hashlib.sha512(data).hexdigest()}

    def lock(self):
        p = self.base()
        return {"schema_version": 1, "profile": p["profile"], "mc_version": p["mc_version"],
                "fabric_loader": p["fabric_loader"], "selection_sha256": mods.fingerprint(p), "files": [self.file()]}

    def test_stale_lock_rejected_after_parent_change(self):
        lock = self.lock()
        mods.write_json(mods.lock_path("26.3/base"), lock)
        self.assertEqual(mods.read_lock(mods.load_profile("26.3/base")), lock)
        self.profile("26.3/base", mc_version="26.3", fabric_loader="0.20.1", mods=["changed"])
        with self.assertRaisesRegex(ValueError, "Profile changed"):
            mods.read_lock(mods.load_profile("26.3/base"))

    def test_unsafe_links_and_filenames_rejected(self):
        for field, value in [("filename", "../bad.jar"), ("url", "http://example.com/one.jar"), ("sha512", "bad")]:
            file = self.file()
            file[field] = value
            with self.assertRaises(ValueError):
                mods.validate_file(file)

    def test_checksum_failure(self):
        from io import BytesIO
        with patch.object(mods.urllib.request, "urlopen", return_value=BytesIO(b"corrupted")):
            with self.assertRaisesRegex(ValueError, "Checksum mismatch"):
                mods.download_file(self.file(), self.root / "bad.jar")

    def test_unmanaged_directory_preserved(self):
        output = self.root / "mods"
        output.mkdir()
        (output / "personal.jar").write_bytes(b"original")
        with self.assertRaisesRegex(ValueError, "unmanaged"):
            mods.download_profile(self.lock(), output)
        self.assertEqual((output / "personal.jar").read_bytes(), b"original")

    def test_failed_download_preserves_previous_profile(self):
        lock = self.lock()
        output = self.root / "mods"
        output.mkdir()
        mods.write_json(output / ".xn-mc-profile.json", lock)
        (output / "one.jar").write_bytes(b"old")
        with patch.object(mods, "download_file", side_effect=ValueError("Checksum mismatch")):
            with self.assertRaises(ValueError):
                mods.download_profile(lock, output)
        self.assertEqual((output / "one.jar").read_bytes(), b"old")
        self.assertFalse(list(self.root.glob(".xn-mc-stage-*")))

    def test_profile_replacement_removes_stale_jars_only_in_managed_output(self):
        lock = self.lock()
        output = self.root / "mods"
        def download(file, dest):
            dest.write_bytes(b"jar")
        with patch.object(mods, "download_file", side_effect=download):
            mods.download_profile(lock, output)
            changed = copy.deepcopy(lock)
            changed["files"] = [self.file("two.jar")]
            mods.download_profile(changed, output)
        self.assertFalse((output / "one.jar").exists())
        self.assertTrue((output / "two.jar").exists())
        (output / "personal.jar").touch()
        with self.assertRaisesRegex(ValueError, "Unmanaged files"):
            mods.download_profile(lock, output)
        self.assertTrue((output / "personal.jar").exists())

    def test_required_dependency_and_exact_version_are_locked(self):
        profile = self.base()
        profile["mods"] = {"one": {"slug": "one"}}
        def version(pid, vid, deps=()):
            return {"project_id": pid, "id": vid, "version_number": vid,
                    "version_type": "release", "date_published": "2026-11-14", "loaders": ["fabric"],
                    "game_versions": ["26.3"], "dependencies": list(deps),
                    "files": [{**self.file(pid + ".jar"), "hashes": {"sha512": self.file()["sha512"]}, "primary": True}]}
        versions = {
            "one": version("one", "v1", [{"project_id": "two", "version_id": "v2", "dependency_type": "required"}]),
            "two": version("two", "v2")}
        def api(path):
            if path.startswith("project/") and "/version" not in path:
                pid = path.split("/")[1]
                return {"id": pid, "slug": pid}
            if path.startswith("version/"):
                return versions["two"]
            return [versions[path.split("/")[1]]]
        with patch.object(mods, "fetch", side_effect=api):
            lock = mods.resolve(profile)
        self.assertEqual({f["version_id"] for f in lock["files"]}, {"v1", "v2"})
        profile["mods"]["two"] = {"slug": "two", "version_id": "different"}
        with patch.object(mods, "fetch", side_effect=api):
            with self.assertRaisesRegex(ValueError, "Conflicting version pins"):
                mods.resolve(profile)


if __name__ == "__main__":
    unittest.main()
