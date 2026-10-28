import importlib.util
import io
import json
import subprocess
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / "android-release.py"
SPEC = importlib.util.spec_from_file_location("android_release", SCRIPT)
RELEASE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RELEASE)
FINGERPRINT = "a" * 64


def properties(name="2026.09.29.274", code="260929000"):
    return f"VERSION_NAME={name}\nVERSION_CODE={code}\n"


def signature(name="CN=Quire Release", digest=FINGERPRINT):
    return f"Verifies\nSigner #1 certificate DN: {name}\nSigner #1 certificate SHA-256 digest: {digest}\n"


class AndroidReleaseTest(unittest.TestCase):
    def test_source_identity_and_real_leap_day(self):
        current = RELEASE.read_version(properties())
        self.assertEqual(current["packageName"], "io.theficos.quire")
        self.assertEqual(current["versionCode"], 260929000)
        self.assertEqual(current["tag"], "v2026.09.29.274")
        RELEASE.calendar_version("2024.02.29.1")

    def test_invalid_calendar_and_noncanonical_names(self):
        for name in ("2026.02.29.1", "2026.04.31.1", "0000.01.01.1", "2026.1.01.1", "2026.01.01.0", "2026.01.01.01", "2026.01.01.1-debug"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                RELEASE.read_version(properties(name=name))

    def test_bounds_duplicates_and_missing_properties(self):
        for code in ("0", "-1", "01", "1.1", "2100000001", "one"):
            with self.subTest(code=code), self.assertRaises(ValueError):
                RELEASE.read_version(properties(code=code))
        for text in ("", "VERSION_NAME=2026.01.01.1\n", properties() + "VERSION_CODE=2\n"):
            with self.subTest(text=text), self.assertRaises(ValueError):
                RELEASE.read_version(text)
        self.assertEqual(RELEASE.read_version(properties(code="2100000000"))["versionCode"], 2100000000)

    def test_latest_uses_numeric_calendar_and_run_not_tag_creation(self):
        self.assertEqual(RELEASE.newest_tag([
            "v2026.10.14.1000", "v2026.10.15.9", "v2026.10.15.10",
            "v2026.02.30.10000", "server-999", "v9999.99.99.9999",
        ]), "v2026.10.15.10")
        with self.assertRaises(ValueError):
            RELEASE.newest_tag(["main"])

    def test_apk_must_match_source_package_code_name_and_release_mode(self):
        correct = "package: name='io.theficos.quire' versionCode='260929000' versionName='2026.09.29.274' platformBuildVersionName='15'\n"
        current = RELEASE.read_version(properties())
        RELEASE.check_badging(current, correct)
        for text in ("", correct * 2, correct.replace("quire'", "quire.debug'"), correct.replace("260929000", "260929001"), correct.replace("2026.09.29.274", "2026.09.29.275"), correct + "application-debuggable\n"):
            with self.subTest(text=text), self.assertRaises(ValueError):
                RELEASE.check_badging(current, text)

    def test_actual_verified_certificate_is_pinned_and_debug_key_rejected(self):
        self.assertEqual(RELEASE.check_signature(signature(), ":".join(["AA"] * 32)), FINGERPRINT)
        for text, expected in (
            (signature(), "b" * 64), (signature("C=US, CN=Android Debug, O=Android"), FINGERPRINT),
            (signature("CN=android debug"), FINGERPRINT), ("", FINGERPRINT),
            (signature() + signature().replace("#1", "#2"), FINGERPRINT), (signature(), "bad fingerprint"),
        ):
            with self.subTest(text=text), self.assertRaises(ValueError):
                RELEASE.check_signature(text, expected)

    def test_tag_source_is_peeled_exact_and_clean(self):
        current = RELEASE.read_version(properties())
        with patch.object(RELEASE, "git", side_effect=["abc", "abc", "", "", properties()]) as git:
            self.assertEqual(RELEASE.check_source(Path("/repo"), current, current["tag"]), "abc")
            self.assertEqual(git.call_args_list[0].args[1:], ("rev-parse", "--verify", "refs/tags/v2026.09.29.274^{commit}"))
        with self.assertRaises(ValueError):
            RELEASE.check_source(Path("/repo"), current, "main")
        for results in (["abc", "def"], ["abc", "abc", "app/file.kt"], ["abc", "abc", "", "", properties(code="1")]):
            with self.subTest(results=results), patch.object(RELEASE, "git", side_effect=results), self.assertRaises(ValueError):
                RELEASE.check_source(Path("/repo"), current, current["tag"])
        with patch.object(RELEASE, "git", side_effect=subprocess.CalledProcessError(128, "git")), self.assertRaises(subprocess.CalledProcessError):
            RELEASE.check_source(Path("/repo"), current, current["tag"])

    def test_tag_cleanliness_checks_real_untracked_build_inputs(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def git(*args):
                return subprocess.run(["git", "-c", "user.name=Quire test", "-c", "user.email=test@example.test", "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null", *args], cwd=root, check=True, capture_output=True)
            git("init", "-q")
            (root / "gradle.properties").write_text(properties())
            (root / ".gitignore").write_text("/build/\n")
            git("add", ".")
            git("commit", "-qm", "fixture")
            current = RELEASE.read_version(properties())
            git("tag", current["tag"])
            RELEASE.check_source(root, current, current["tag"])
            extra = root / "app/src/main/java/Extra.kt"
            extra.parent.mkdir(parents=True)
            extra.write_text("class Extra\n")
            with self.assertRaisesRegex(ValueError, "Untracked"):
                RELEASE.check_source(root, current, current["tag"])
            extra.unlink()
            for name in ("build/output.apk", ".release-tools/scripts/helper.py"):
                output = root / name
                output.parent.mkdir(parents=True, exist_ok=True)
                output.write_text("fixture")
            RELEASE.check_source(root, current, current["tag"])
            sibling = root / ".release-tools-evil/Extra.kt"
            sibling.parent.mkdir()
            sibling.write_text("class Extra\n")
            with self.assertRaisesRegex(ValueError, "Untracked"):
                RELEASE.check_source(root, current, current["tag"])

    def test_records_derive_from_exact_apk_bytes_and_are_deterministic(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "app-release.apk"
            apk.write_bytes(b"synthetic apk payload")
            current = RELEASE.read_version(properties())
            first = RELEASE.write_records(current, "abcdef", apk, FINGERPRINT)
            before = (apk.parent / "release-identity.json").read_bytes()
            self.assertEqual(RELEASE.write_records(current, "abcdef", apk, FINGERPRINT), first)
            self.assertEqual((apk.parent / "release-identity.json").read_bytes(), before)
            self.assertEqual(json.loads(before)["sourceCommit"], "abcdef")
            self.assertEqual(apk.with_suffix(".apk.sha256").read_text(), first["apk"]["sha256"] + "  app-release.apk\n")
            renamed = apk.with_name("other.apk")
            renamed.write_bytes(apk.read_bytes())
            with self.assertRaises(ValueError):
                RELEASE.write_records(current, "abcdef", renamed, FINGERPRINT)

    def test_failed_signature_verification_never_writes_publication_sidecars(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "gradle.properties").write_text(properties())
            apk = root / "app-release.apk"
            apk.write_bytes(b"not a valid signed APK")
            certificate = root / "certificate.txt"
            certificate.write_text(FINGERPRINT)
            correct_badging = "package: name='io.theficos.quire' versionCode='260929000' versionName='2026.09.29.274'\n"
            command = [str(SCRIPT), "--root", str(root), "--tag", "v2026.09.29.274", "--apk", str(apk), "--build-tools", str(root), "--certificate-sha256", str(certificate)]
            with patch.object(RELEASE, "check_source", return_value="abcdef"), patch.object(RELEASE.sys, "argv", command), patch.object(RELEASE.subprocess, "run", side_effect=[subprocess.CompletedProcess([], 0, correct_badging), subprocess.CalledProcessError(1, "apksigner")]), redirect_stderr(io.StringIO()), redirect_stdout(io.StringIO()):
                self.assertEqual(RELEASE.main(), 1)
            self.assertFalse((root / "release-identity.json").exists())
            self.assertFalse(apk.with_suffix(".apk.sha256").exists())


if __name__ == "__main__":
    unittest.main()
