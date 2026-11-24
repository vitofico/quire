#!/usr/bin/env bash
# Download the pinned EPUBCheck release into the user cache and verify its checksum.
set -euo pipefail
VERSION=5.4.0
SHA256=33350c61038e71dfb3d45a76aed04bf5481e6d5500cb780f6e98db8bbd15a28c
DEST="${XDG_CACHE_HOME:-$HOME/.cache}/quire-convert"
mkdir -p "$DEST"
cd "$DEST"
if [ ! -f "epubcheck-$VERSION/epubcheck.jar" ]; then
  curl -fsSL -o "epubcheck-$VERSION.zip" \
    "https://github.com/w3c/epubcheck/releases/download/v$VERSION/epubcheck-$VERSION.zip"
  echo "$SHA256  epubcheck-$VERSION.zip" | shasum -a 256 -c -
  unzip -q -o "epubcheck-$VERSION.zip"
fi
echo "$DEST/epubcheck-$VERSION/epubcheck.jar"
