"""ISBN normalization: validate ISBN-10/13 and canonicalize to ISBN-13.

A single source of truth so scanned/typed ISBNs are compared and keyed
consistently (owned-detection, identity.metadata_id="isbn:<isbn13>").
"""

from __future__ import annotations


def _clean(raw: str) -> str:
    return raw.strip().replace("-", "").replace(" ", "").upper()


def _isbn10_valid(s: str) -> bool:
    if len(s) != 10:
        return False
    total = 0
    for i, ch in enumerate(s):
        if ch == "X" and i == 9:
            val = 10
        elif ch.isdigit():
            val = int(ch)
        else:
            return False
        total += (10 - i) * val
    return total % 11 == 0


def _isbn13_valid(s: str) -> bool:
    if len(s) != 13 or not s.isdigit():
        return False
    total = sum((1 if i % 2 == 0 else 3) * int(c) for i, c in enumerate(s))
    return total % 10 == 0


def _isbn10_to_13(s: str) -> str:
    core = "978" + s[:9]
    check = (10 - sum((1 if i % 2 == 0 else 3) * int(c) for i, c in enumerate(core)) % 10) % 10
    return core + str(check)


def to_isbn13(raw: str | None) -> str | None:
    """Return the canonical ISBN-13 for a valid ISBN-10/13, else None."""
    if not raw:
        return None
    s = _clean(raw)
    if _isbn13_valid(s):
        return s
    if _isbn10_valid(s):
        return _isbn10_to_13(s)
    return None
