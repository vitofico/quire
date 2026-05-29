"""Deterministic reader-affinity scorer (no LLM).

Pure functions over in-memory library rows. See
docs/superpowers/specs/2026-05-29-book-scan-design.md for the rubric.
"""
from __future__ import annotations

import re
import unicodedata

AFFINITY_VERSION = 1

_GENERIC_SUBJECTS = {
    "fiction", "general", "literature", "novels",
    "protected daisy", "accessible book", "large type books",
}
_SUBJECT_ALIASES = {
    "detective and mystery stories": "mystery",
    "sci-fi": "science fiction",
    "science fiction": "science fiction",
    "fantasy fiction": "fantasy",
    "thrillers": "thriller",
}


def _strip_diacritics(s: str) -> str:
    return "".join(c for c in unicodedata.normalize("NFKD", s) if not unicodedata.combining(c))


def _base_normalize(s: str) -> str:
    s = _strip_diacritics(s).lower()
    s = re.sub(r"\([^)]*\)", " ", s)
    s = re.sub(r"[^\w\s.&'-]", " ", s)
    s = re.sub(r"\s+", " ", s).strip()
    return s


def normalize_subject(raw: str | None) -> str | None:
    if not raw:
        return None
    s = _base_normalize(raw)
    s = _SUBJECT_ALIASES.get(s, s)
    if not s or s in _GENERIC_SUBJECTS:
        return None
    return s


def normalize_author(raw: str | None) -> str | None:
    if not raw:
        return None
    s = _base_normalize(raw)
    if "," in raw:
        last, _, first = (p.strip() for p in raw.partition(","))   # raw, not _base_normalize(raw)
        if first:
            s = f"{_base_normalize(first)} {_base_normalize(last)}".strip()
    return s or None


def normalize_series(raw: str | None) -> str | None:
    if not raw:
        return None
    return _base_normalize(raw) or None
