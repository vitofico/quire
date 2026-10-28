#!/usr/bin/env python3
"""Validate Quire's source/APK identity and write deterministic release records."""

import argparse
import datetime
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path

PACKAGE = "io.theficos.quire"
VERSION_PATTERN = re.compile(r"([0-9]{4})\.([0-9]{2})\.([0-9]{2})\.([1-9][0-9]*)")
MAX_CODE = 2_100_000_000


def calendar_version(name):
    match = VERSION_PATTERN.fullmatch(name)
    if match is None:
        raise ValueError("VERSION_NAME must use CalVer YYYY.MM.DD.<run>, starting at run 1.")
    year, month, day, run = map(int, match.groups())
    return datetime.date(year, month, day), run


def read_version(text):
    values = {}
    for line in text.splitlines():
        key, separator, value = line.strip().partition("=")
        key = key.strip()
        if separator and key in {"VERSION_NAME", "VERSION_CODE"}:
            if key in values:
                raise ValueError(f"Duplicate {key} in gradle.properties.")
            values[key] = value.strip()
    if len(values) != 2:
        raise ValueError("Set VERSION_NAME and VERSION_CODE in gradle.properties.")
    name = values["VERSION_NAME"]
    calendar_version(name)
    raw_code = values["VERSION_CODE"]
    if re.fullmatch(r"[1-9][0-9]*", raw_code) is None or int(raw_code) > MAX_CODE:
        raise ValueError("VERSION_CODE must be an integer from 1 to 2100000000.")
    return {"packageName": PACKAGE, "versionName": name, "versionCode": int(raw_code), "tag": f"v{name}"}


def git(root, *args):
    return subprocess.run(
        ["git", *args], cwd=root, check=True, capture_output=True, text=True
    ).stdout.strip()


def check_source(root, current, tag):
    if tag != current["tag"]:
        raise ValueError("Release tag differs from VERSION_NAME in gradle.properties.")
    # Full refs and a peeled commit reject branches, arbitrary SHAs and tag names
    # that resolve somewhere other than the source actually built.
    commit = git(root, "rev-parse", "--verify", f"refs/tags/{tag}^{{commit}}")
    if git(root, "rev-parse", "HEAD") != commit:
        raise ValueError("HEAD differs from the release tag's source commit.")
    if git(root, "diff", "--name-only", "HEAD", "--"):
        raise ValueError("Tracked source differs from the release tag.")
    # Gradle also compiles untracked source files. The trusted historical-tag
    # repair checkout is the sole nonignored exception, outside the app source.
    if git(root, "ls-files", "--others", "--exclude-standard", "--", ".", ":(exclude).release-tools/**"):
        raise ValueError("Untracked files differ from the release tag.")
    committed = read_version(git(root, "show", f"{commit}:gradle.properties"))
    if committed != current:
        raise ValueError("Working version differs from the committed release version.")
    return commit


def newest_tag(tags):
    releases = []
    for tag in tags:
        if not tag.startswith("v"):
            continue
        try:
            releases.append((calendar_version(tag[1:]), tag))
        except ValueError:
            continue
    if not releases:
        raise ValueError("No valid CalVer release tags found.")
    return max(releases)[1]


def check_badging(current, text):
    packages = [line for line in text.splitlines() if line.startswith("package: ")]
    if len(packages) != 1:
        raise ValueError("APK must contain one package identity.")
    values = dict(re.findall(r"(?:^|[ \t])(name|versionCode|versionName)='([^']*)'", packages[0]))
    expected = {
        "name": PACKAGE,
        "versionCode": str(current["versionCode"]),
        "versionName": current["versionName"],
    }
    if values != expected:
        raise ValueError("Built APK package/version differs from gradle.properties.")
    if any(line.startswith("application-debuggable") for line in text.splitlines()):
        raise ValueError("A release APK must not be debuggable.")


def normalize_fingerprint(value):
    value = value.strip().replace(":", "").lower()
    if re.fullmatch(r"[0-9a-f]{64}", value) is None:
        raise ValueError("Expected one SHA-256 certificate fingerprint (64 hex digits).")
    return value


def check_signature(text, expected):
    # apksigner's exit status is checked by the caller; parse the verified
    # certificate as an additional guard against a stale APK or debug key.
    digests = re.findall(r"^Signer #[0-9]+ certificate SHA-256 digest: (\S+)$", text, re.MULTILINE)
    names = re.findall(r"^Signer #[0-9]+ certificate DN: (.+)$", text, re.MULTILINE)
    if len(digests) != 1 or len(names) != 1:
        raise ValueError("Release APK must have exactly one verified signer.")
    if re.search(r"(?:^|,)\s*CN\s*=\s*Android Debug\s*(?:,|$)", names[0], re.IGNORECASE):
        raise ValueError("Refusing an APK signed with an Android debug certificate.")
    digest = normalize_fingerprint(digests[0])
    if digest != normalize_fingerprint(expected):
        raise ValueError("APK signing certificate differs from the expected release certificate.")
    return digest


def write_records(current, commit, apk, fingerprint):
    if apk.name != "app-release.apk":
        raise ValueError("The F-Droid upstream asset must remain app-release.apk.")
    with apk.open("rb") as payload:
        digest = hashlib.file_digest(payload, "sha256").hexdigest()
    record = {
        "schemaVersion": 1,
        **current,
        "sourceCommit": commit,
        "apk": {"file": apk.name, "sizeBytes": apk.stat().st_size, "sha256": digest},
        "signingCertificateSha256": fingerprint,
    }
    apk.with_suffix(".apk.sha256").write_text(f"{digest}  {apk.name}\n")
    (apk.parent / "release-identity.json").write_text(json.dumps(record, indent=2, sort_keys=True) + "\n")
    return record


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--tag", help="verify this existing CalVer tag and its checked-out source")
    parser.add_argument("--newest-tag", action="store_true", help="print the numerically newest valid CalVer tag")
    parser.add_argument("--apk", type=Path, help="verify a signed APK and write checksum/source identity beside it")
    parser.add_argument("--build-tools", type=Path, help="Android build-tools directory, e.g. $ANDROID_HOME/build-tools/34.0.0")
    parser.add_argument("--certificate-sha256", type=Path, help="file containing the expected certificate fingerprint")
    args = parser.parse_args()
    if args.apk and not (args.tag and args.build_tools and args.certificate_sha256):
        parser.error("--apk requires --tag, --build-tools and --certificate-sha256.")
    try:
        current = read_version((args.root / "gradle.properties").read_text())
        if args.newest_tag:
            print(newest_tag(git(args.root, "tag", "--list").splitlines()))
            return 0
        commit = check_source(args.root, current, args.tag) if args.tag else None
        if args.apk:
            badging = subprocess.run(
                [str(args.build_tools / "aapt"), "dump", "badging", str(args.apk)],
                check=True, capture_output=True, text=True,
            )
            check_badging(current, badging.stdout)
            signature = subprocess.run(
                [str(args.build_tools / "apksigner"), "verify", "--verbose", "--print-certs", str(args.apk)],
                check=True, capture_output=True, text=True,
            )
            fingerprint = check_signature(signature.stdout, args.certificate_sha256.read_text())
            current = write_records(current, commit, args.apk, fingerprint)
        print(json.dumps(current, sort_keys=True))
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
