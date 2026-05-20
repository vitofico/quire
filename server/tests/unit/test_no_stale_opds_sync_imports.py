"""Belt-and-braces: prove every Python file in the server package and tests
imports from `quire_server`, not the legacy package name. The rename-guard
CI step also covers this textually; this test surfaces breakage in local
`pytest` runs without waiting for CI.

Plan §6.4.
"""

from __future__ import annotations

import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[2]  # server/


def test_no_legacy_imports() -> None:
    offenders: list[str] = []
    for sub in ("quire_server", "tests", "scripts"):
        for path in (ROOT / sub).rglob("*.py"):
            text = path.read_text()
            # Use string literals that the rename-guard regex won't itself
            # match in this file (the regex requires a non-identifier
            # left-boundary before OPDS_SYNC_). Concatenation breaks the
            # token.
            legacy_pkg = "opds" + "_sync"
            if f"from {legacy_pkg}" in text or f"import {legacy_pkg}" in text:
                offenders.append(str(path))
    assert not offenders, f"Legacy imports still present in: {offenders}"
