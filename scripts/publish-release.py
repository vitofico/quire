#!/usr/bin/env python3
"""Publish missing GitHub Release assets without replacing existing bytes."""

import argparse
import json
import re
import subprocess
import sys
import tempfile
from pathlib import Path
from urllib.parse import quote


def gh(*args):
    return subprocess.run(["gh", *args], check=True, capture_output=True).stdout


def find_release(repo, tag):
    try:
        return json.loads(gh("api", f"repos/{repo}/releases/tags/{quote(tag, safe='')}"))
    except subprocess.CalledProcessError as error:
        # Only a definitive not-found permits creation. Auth, rate limiting and
        # transport failures must not be treated as an absent release.
        if b"HTTP 404" in (error.stderr or b""):
            return None
        raise


def asset_map(release):
    assets = {}
    for asset in release["assets"]:
        if asset["name"] in assets:
            raise ValueError(f"Duplicate remote asset name: {asset['name']}.")
        assets[asset["name"]] = asset
    return assets


def download(repo, asset, destination):
    payload = gh("api", "-H", "Accept: application/octet-stream", f"repos/{repo}/releases/assets/{asset['id']}")
    if len(payload) != asset["size"]:
        raise ValueError(f"Incomplete asset download: {asset['name']}.")
    destination.write_bytes(payload)


def fetch_existing(repo, tag, name, destination):
    release = find_release(repo, tag)
    if release is None or name not in asset_map(release):
        return False
    destination.parent.mkdir(parents=True, exist_ok=True)
    download(repo, asset_map(release)[name], destination)
    return True


def publish(repo, tag, paths, latest):
    if len({path.name for path in paths}) != len(paths):
        raise ValueError("Local asset names must be unique.")
    for path in paths:
        if not path.is_file() or path.stat().st_size == 0:
            raise ValueError(f"Missing or empty release asset: {path}.")
    release = find_release(repo, tag)
    if release is None:
        gh("release", "create", tag, "--repo", repo, "--verify-tag", "--title", tag,
           "--generate-notes", f"--latest={latest}", *map(str, paths))
        print(f"Created {tag} with {len(paths)} assets.")
        return
    if release["tag_name"] != tag or release.get("draft") or release.get("prerelease"):
        raise ValueError("Existing release must be a published stable release of the requested tag.")
    assets = asset_map(release)
    missing = []
    # Compare every existing asset before the first upload: a sidecar mismatch
    # must not leave a partial repair that otherwise looked successful.
    with tempfile.TemporaryDirectory(prefix="quire-release-compare-") as temporary:
        for path in paths:
            if path.name not in assets:
                missing.append(path)
                continue
            existing = Path(temporary) / path.name
            download(repo, assets[path.name], existing)
            if existing.read_bytes() != path.read_bytes():
                raise ValueError(f"Immutable release asset differs: {path.name}; refusing to replace it.")
            print(f"Unchanged: {path.name}")
    for path in missing:
        # No --clobber: a concurrent publisher winning this name makes the
        # upload fail safely instead of deleting their artifact.
        gh("release", "upload", tag, "--repo", repo, str(path))
        print(f"Uploaded missing asset: {path.name}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--asset", type=Path, action="append", default=[])
    parser.add_argument("--latest", choices=("true", "false"), default="false")
    parser.add_argument("--fetch-existing", metavar="ASSET_NAME")
    parser.add_argument("--destination", type=Path)
    args = parser.parse_args()
    if re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", args.repo) is None:
        parser.error("Use an explicit owner/repository.")
    if args.tag.startswith("-") or not args.tag:
        parser.error("Use an explicit tag name.")
    if args.fetch_existing:
        if not args.destination or args.asset:
            parser.error("--fetch-existing requires --destination and no --asset.")
    elif not args.asset:
        parser.error("Provide at least one --asset.")
    try:
        if args.fetch_existing:
            found = fetch_existing(args.repo, args.tag, args.fetch_existing, args.destination)
            print(f"existing={'true' if found else 'false'}")
        else:
            publish(args.repo, args.tag, args.asset, args.latest)
    except (OSError, ValueError, KeyError, subprocess.CalledProcessError) as error:
        print(str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
