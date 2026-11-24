"""Per-page layout signals: columns, folios, script and symbol statistics."""

from __future__ import annotations

import re
import unicodedata
from collections import Counter
from dataclasses import dataclass

from ..models import Box, Line

MARGIN = 0.08  # top/bottom share of the page where folios and running heads live
_ROMAN = re.compile(
    r"^(?=[ivxlcdm]+$)m{0,4}(cm|cd|d?c{0,3})(xc|xl|l?x{0,3})(ix|iv|v?i{0,3})$", re.I
)
_DIGITS = re.compile(r"^\d{1,4}$")
_DECOR = "-–—·•[]()|.,"


def folio_text(text: str) -> str | None:
    """The page number in a margin word, e.g. ``"- 12 -"`` -> ``"12"``; None otherwise."""
    core = text.strip(_DECOR + " ")
    if _DIGITS.match(core) or (core and _ROMAN.match(core)):
        return core
    return None


def folio_candidates(lines: list[Line]) -> list[str]:
    """Short numeric or roman words standing alone in the top or bottom margin."""
    found: list[str] = []
    for line in lines:
        if not in_margin(line.box):
            continue
        words = [w for w in line.words if w.text.strip(_DECOR)]
        for k, word in enumerate(words):
            value = folio_text(word.text)
            if value is None:
                continue
            alone = len(words) == 1
            if not alone and k in (0, len(words) - 1):
                other = words[1] if k == 0 else words[-2]
                gap = other.box.x0 - word.box.x1 if k == 0 else word.box.x0 - other.box.x1
                alone = gap >= 3 * word.box.height
            if alone and value not in found:
                found.append(value)
    return found


def in_margin(box: Box) -> bool:
    return box.y1 <= MARGIN + 0.01 or box.y0 >= 1 - MARGIN - 0.01


# --------------------------------------------------------------------------- columns


def _split(lines: list[Box], min_side: int) -> float | None:
    """Gutter x where lines fall cleanly to either side, side by side vertically."""
    best, best_score = None, 0
    n = len(lines)
    for step in range(25, 76):
        g = step / 100
        left = [b for b in lines if b.x1 <= g]
        right = [b for b in lines if b.x0 >= g]
        if len(left) < min_side or len(right) < min_side or n - len(left) - len(right) > 0.2 * n:
            continue
        if _mean_width(left) < 0.15 or _mean_width(right) < 0.15:
            continue
        top = max(min(b.y0 for b in left), min(b.y0 for b in right))
        bottom = min(max(b.y1 for b in left), max(b.y1 for b in right))
        shorter = min(
            max(b.y1 for b in left) - min(b.y0 for b in left),
            max(b.y1 for b in right) - min(b.y0 for b in right),
        )
        if shorter <= 0 or (bottom - top) < 0.5 * shorter:
            continue
        score = min(len(left), len(right))
        if score > best_score:
            best, best_score = g, score
    return best


def _mean_width(boxes: list[Box]) -> float:
    return sum(b.width for b in boxes) / len(boxes) if boxes else 0.0


def column_count(lines: list[Box]) -> int:
    """1 for single-column text, 2 or 3 when consistent vertical gutters split the lines."""
    body = [b for b in lines if not in_margin(b)]
    if not body:
        return 0
    if len(body) < 8:
        return 1
    min_side = max(4, round(0.2 * len(body)))
    g = _split(body, min_side)
    if g is None:
        return 1
    count = 2
    for side in ([b for b in body if b.x1 <= g], [b for b in body if b.x0 >= g]):
        span = max(b.x1 for b in side) - min(b.x0 for b in side)
        if len(side) >= 2 * min_side and span > 0.4 and _split(side, min_side):
            count += 1
    return min(count, 3)


# --------------------------------------------------------------------------- characters


@dataclass
class CharStats:
    chars: int = 0
    letters: int = 0
    rtl: int = 0
    cjk: int = 0
    math: int = 0
    bad: int = 0  # U+FFFD, private use, control characters

    def add(self, other: CharStats) -> None:
        for name in ("chars", "letters", "rtl", "cjk", "math", "bad"):
            setattr(self, name, getattr(self, name) + getattr(other, name))


def _rtl(cp: int) -> bool:
    return 0x0590 <= cp <= 0x08FF or 0xFB1D <= cp <= 0xFDFF or 0xFE70 <= cp <= 0xFEFF


def _cjk(cp: int) -> bool:
    return (
        0x3040 <= cp <= 0x30FF
        or 0x3400 <= cp <= 0x4DBF
        or 0x4E00 <= cp <= 0x9FFF
        or 0xAC00 <= cp <= 0xD7AF
        or 0xF900 <= cp <= 0xFAFF
        or 0x20000 <= cp <= 0x2FA1F
    )


def char_stats(text: str) -> CharStats:
    stats = CharStats()
    counts = Counter(text)
    for ch, n in counts.items():
        if ch.isspace():
            continue
        cp = ord(ch)
        cat = unicodedata.category(ch)
        stats.chars += n
        if cat.startswith("L"):
            stats.letters += n
        if _rtl(cp):
            stats.rtl += n
        elif _cjk(cp):
            stats.cjk += n
        if cat == "Sm" or 0x1D400 <= cp <= 0x1D7FF:
            stats.math += n
        if cp == 0xFFFD or cat in ("Co", "Cc") or 0xF0000 <= cp:
            stats.bad += n
    return stats
