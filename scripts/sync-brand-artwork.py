#!/usr/bin/env python3
"""Keep the native and server marbled ink masks identical to the approved artwork."""

import argparse
from pathlib import Path

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--check", action="store_true")
args = parser.parse_args()
artwork = (root / "branding/marbled-ink.webp").read_bytes()
targets = [
    root / "app/src/main/res/drawable-nodpi/marbled_ink.webp",
    root / "server/quire_server/assets/marbled-ink.webp",
]
stale = [path for path in targets if not path.exists() or path.read_bytes() != artwork]
if args.check and stale:
    parser.exit(1, "Stale artwork; run python3 scripts/sync-brand-artwork.py\n")
for path in stale:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(artwork)
print(f"Verified {len(targets)} marbled ink exports")
