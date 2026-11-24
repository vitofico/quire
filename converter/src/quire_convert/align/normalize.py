"""Matching strings for alignment, kept apart from the text they were made from.

``normalize`` folds a string into a comparison form (compatibility composition, known
ligatures, typographic quotes, dashes and spaces, case) and records, for every character
of the result, the span of original characters it came from. ``tokenize`` cuts the
matching string into words and reports each word's span in the original string, so an
alignment found on matching strings can be applied back to the real text.
"""

from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass

# NFKC already expands the Latin presentation ligatures; this table keeps the expansion
# explicit and covers the long s, which NFKC folds only on its own.
_LIGATURES = {
    "ﬀ": "ff",
    "ﬁ": "fi",
    "ﬂ": "fl",
    "ﬃ": "ffi",
    "ﬄ": "ffl",
    "ﬅ": "st",
    "ﬆ": "st",
    "ſ": "s",
}
_QUOTES = dict.fromkeys("‘’‚‛′ʼ`´", "'")
_QUOTES |= dict.fromkeys("“”„‟″«»‹›", '"')
_DASHES = dict.fromkeys("‐‑‒–—―−⁃﹘﹣－", "-")
_FOLD = _QUOTES | _DASHES
# Invisible characters that never separate words: soft hyphen, zero-width marks, BOM.
_DROP = frozenset("­​‌‍⁠﻿")
_WORD = re.compile(r"\w+")


@dataclass(frozen=True)
class Normalized:
    """A matching string and, per character, the original span ``[starts[k], ends[k])``."""

    text: str
    starts: tuple[int, ...]
    ends: tuple[int, ...]


@dataclass(frozen=True)
class Token:
    norm: str  # the word in matching form
    start: int  # span in the original string
    end: int


def _clusters(text: str):
    """Yield (start, end) of each base character with its combining marks."""
    n = len(text)
    i = 0
    while i < n:
        j = i + 1
        while j < n and unicodedata.combining(text[j]):
            j += 1
        yield i, j
        i = j


def _fold(cluster: str) -> str:
    if cluster in _DROP:
        return ""
    if cluster.isspace():
        return " "
    out = _LIGATURES.get(cluster[0], cluster[0]) + cluster[1:]
    out = unicodedata.normalize("NFKC", out)
    return "".join(_FOLD.get(ch, ch) for ch in out).casefold()


def normalize(text: str) -> Normalized:
    """Fold ``text`` into its matching form; whitespace runs become one space, trimmed."""
    chars: list[str] = []
    starts: list[int] = []
    ends: list[int] = []
    for start, end in _clusters(text):
        for ch in _fold(text[start:end]):
            if ch.isspace():
                if not chars or chars[-1] == " ":
                    continue
                ch = " "
            chars.append(ch)
            starts.append(start)
            ends.append(end)
    if chars and chars[-1] == " ":
        chars.pop()
        starts.pop()
        ends.pop()
    return Normalized("".join(chars), tuple(starts), tuple(ends))


def tokenize(text: str) -> list[Token]:
    """Words of ``text`` (runs of letters and digits) in matching form, with original spans."""
    norm = normalize(text)
    return [
        Token(m.group(), norm.starts[m.start()], norm.ends[m.end() - 1])
        for m in _WORD.finditer(norm.text)
    ]


def similar(a: str, b: str) -> bool:
    """Whether two different matching words are close enough to be the same word.

    Allows about one edit per four characters, never on words shorter than three
    characters, so extraction noise matches while short function words do not.
    """
    la, lb = len(a), len(b)
    if min(la, lb) < 3:
        return False
    limit = max(1, max(la, lb) // 4)
    if abs(la - lb) > limit:
        return False
    return _distance(a, b, limit) <= limit


def _distance(a: str, b: str, limit: int) -> int:
    """Levenshtein distance, stopping early once every path exceeds ``limit``."""
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        if min(cur) > limit:
            return limit + 1
        prev = cur
    return prev[-1]
