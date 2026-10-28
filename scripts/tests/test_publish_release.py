import importlib.util
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / "publish-release.py"
SPEC = importlib.util.spec_from_file_location("publish_release", SCRIPT)
PUBLISH = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PUBLISH)
REPO, TAG = "owner/repository", "v2026.09.29.274"


def release(names):
    return {"tag_name": TAG, "draft": False, "prerelease": False, "assets": [
        {"name": name, "id": index, "size": len(data)} for index, (name, data) in enumerate(names.items())
    ]}


class PublishReleaseTest(unittest.TestCase):
    def test_only_definitive_not_found_allows_release_creation(self):
        for status in (401, 403, 429, 500):
            error = subprocess.CalledProcessError(1, "gh", stderr=f"HTTP {status}".encode())
            with self.subTest(status=status), patch.object(PUBLISH, "gh", side_effect=error), self.assertRaises(subprocess.CalledProcessError):
                PUBLISH.find_release(REPO, TAG)
        missing = subprocess.CalledProcessError(1, "gh", stderr=b"gh: Not Found (HTTP 404)")
        with patch.object(PUBLISH, "gh", side_effect=missing):
            self.assertIsNone(PUBLISH.find_release(REPO, TAG))

    def test_release_creation_checks_existing_tag_and_never_clobbers(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "app-release.apk"
            apk.write_bytes(b"apk")
            with patch.object(PUBLISH, "find_release", return_value=None), patch.object(PUBLISH, "gh") as gh:
                PUBLISH.publish(REPO, TAG, [apk], "false")
                args = gh.call_args.args
                self.assertIn("--verify-tag", args)
                self.assertIn("--latest=false", args)
                self.assertNotIn("--clobber", args)

    def test_identical_assets_skip_and_only_missing_sidecar_is_uploaded(self):
        with tempfile.TemporaryDirectory() as directory:
            apk, checksum = Path(directory) / "app-release.apk", Path(directory) / "app-release.apk.sha256"
            apk.write_bytes(b"original apk")
            checksum.write_bytes(b"checksum")
            def download(repo, asset, destination):
                destination.write_bytes(b"original apk")
            with patch.object(PUBLISH, "find_release", return_value=release({apk.name: apk.read_bytes()})), patch.object(PUBLISH, "download", side_effect=download), patch.object(PUBLISH, "gh") as gh:
                PUBLISH.publish(REPO, TAG, [apk, checksum], "true")
                gh.assert_called_once_with("release", "upload", TAG, "--repo", REPO, str(checksum))

    def test_mismatch_fails_before_any_mutation_even_when_other_asset_is_missing(self):
        with tempfile.TemporaryDirectory() as directory:
            missing, apk = Path(directory) / "missing.json", Path(directory) / "app-release.apk"
            missing.write_bytes(b"new sidecar")
            apk.write_bytes(b"new APK")
            def download(repo, asset, destination):
                destination.write_bytes(b"published APK")
            with patch.object(PUBLISH, "find_release", return_value=release({apk.name: b"published APK"})), patch.object(PUBLISH, "download", side_effect=download), patch.object(PUBLISH, "gh") as gh, self.assertRaises(ValueError):
                PUBLISH.publish(REPO, TAG, [missing, apk], "false")
            gh.assert_not_called()

    def test_recovery_fetches_original_bytes_and_reports_missing_without_build(self):
        data = b"original signed APK"
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "nested/app-release.apk"
            with patch.object(PUBLISH, "find_release", return_value=release({destination.name: data})), patch.object(PUBLISH, "gh", return_value=data):
                self.assertTrue(PUBLISH.fetch_existing(REPO, TAG, destination.name, destination))
                self.assertEqual(destination.read_bytes(), data)
            with patch.object(PUBLISH, "find_release", return_value=None):
                self.assertFalse(PUBLISH.fetch_existing(REPO, TAG, destination.name, destination))

    def test_short_download_duplicate_names_and_empty_local_assets_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "app-release.apk"
            with patch.object(PUBLISH, "gh", return_value=b"short"), self.assertRaises(ValueError):
                PUBLISH.download(REPO, {"id": 1, "size": 99, "name": path.name}, path)
            with self.assertRaises(ValueError):
                PUBLISH.asset_map({"assets": [{"name": "same"}, {"name": "same"}]})
            with patch.object(PUBLISH, "gh") as gh, self.assertRaises(ValueError):
                PUBLISH.publish(REPO, TAG, [path], "false")
            gh.assert_not_called()


if __name__ == "__main__":
    unittest.main()
