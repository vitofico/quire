#!/usr/bin/env python3
"""Keep the native and server marbled ink masks identical to the approved artwork."""

import argparse
from pathlib import Path

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--check", action="store_true")
args = parser.parse_args()
exports = {
    root / "app/src/main/res/drawable-nodpi/marbled_ink.webp": "marbled-ink.webp",
    root / "server/quire_server/assets/marbled-ink.webp": "marbled-ink.webp",
    root / "app/src/main/res/drawable-nodpi/background_ink.webp": "background-ink.webp",
    root / "server/quire_server/assets/background-ink.webp": "background-ink.webp",
}
stale = [
    path
    for path, name in exports.items()
    if not path.exists() or path.read_bytes() != (root / "branding" / name).read_bytes()
]
if args.check and stale:
    parser.exit(1, "Stale artwork; run python3 scripts/sync-brand-artwork.py\n")
for path in stale:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes((root / "branding" / exports[path]).read_bytes())
print(f"Verified {len(exports)} ink artwork exports")
