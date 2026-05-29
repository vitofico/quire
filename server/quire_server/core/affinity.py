"""Deterministic reader-affinity scorer (no LLM).

Pure functions over in-memory library rows. See
docs/superpowers/specs/2026-05-29-book-scan-design.md for the rubric.
"""
from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass, field

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


@dataclass(frozen=True)
class Reason:
    kind: str
    polarity: str
    message: str
    points: float = 0.0


@dataclass(frozen=True)
class AffinityResult:
    version: int
    score: int | None
    band: str
    reasons: list[Reason] = field(default_factory=list)


def _status_counts(library):
    finished = sum(1 for b in library if b["status"] == "finished")
    abandoned = sum(1 for b in library if b["status"] == "abandoned")
    return finished, abandoned


def _author_signal(scanned_authors, library):
    finished_titles = [b for b in library if b["status"] == "finished"
                       and scanned_authors & {normalize_author(a) for a in b["authors"]}]
    abandoned_titles = [b for b in library if b["status"] == "abandoned"
                        and scanned_authors & {normalize_author(a) for a in b["authors"]}]
    fm, am = len(finished_titles), len(abandoned_titles)
    if fm > 0:
        pts = min(25, 8 * fm)
        return Reason("author", "positive", f"You've finished {fm} book(s) by this author.", pts)
    if am >= 2:
        return Reason("author", "negative", f"You've abandoned {am} books by this author.", -10)
    return None


def _theme_signal(scanned_subjects, library):
    best = []
    for theme in scanned_subjects:
        terminal = [b for b in library if b["status"] in ("finished", "abandoned")
                    and theme in {normalize_subject(s) for s in b["subjects"]}]
        if not terminal:
            continue
        fin = sum(1 for b in terminal if b["status"] == "finished")
        support = min(1.0, len(terminal) / 3.0)
        rate = fin / len(terminal)
        affinity = (rate - 0.5) * 2 * support
        best.append((affinity, theme, fin, len(terminal)))
    if not best:
        return None
    best.sort(key=lambda t: abs(t[0]), reverse=True)
    top = best[:3]
    avg = sum(t[0] for t in top) / len(top)
    pts = round(avg * 30) if avg >= 0 else round(avg * 25)
    if pts == 0:
        return None   # a net-neutral theme is not a reason worth surfacing
    theme, fin, tot = top[0][1], top[0][2], top[0][3]
    if avg >= 0:
        return Reason(
            "theme", "positive",
            f"Matches your high-finish theme '{theme}' ({fin} finished, {tot - fin} abandoned).",
            pts)
    return Reason("theme", "negative",
                  f"You've abandoned {tot - fin} of {tot} books tagged '{theme}'.", pts)


def _series_signal(scanned_series, library):
    if not scanned_series:
        return None
    same = [b for b in library if normalize_series(b["series_name"]) == scanned_series]
    if not same:
        return None
    if any(b["status"] in ("finished", "in_progress") for b in same):
        return Reason("series", "positive", "Continues a series you're reading.", 20)
    if all(b["status"] == "abandoned" for b in same):
        return Reason("series", "negative", "You abandoned this series.", -10)
    return Reason("series", "positive", "Matches a series in your library.", 12)


def _language_signal(scanned_lang, library):
    finished = [b for b in library if b["status"] == "finished" and b["language"]]
    if not finished or not scanned_lang:
        return None
    counts: dict[str, int] = {}
    for b in finished:
        counts[b["language"]] = counts.get(b["language"], 0) + 1
    top = sorted(counts.items(), key=lambda kv: kv[1], reverse=True)
    if len(top) >= 2 and top[0][1] == top[1][1]:
        return None
    dominant, dom_n = top[0]
    if scanned_lang == dominant and dom_n >= 2:
        return Reason(
            "language", "positive", f"Language matches most of your reading: {dominant}.", 5)
    if scanned_lang != dominant and dom_n >= 3 and scanned_lang not in counts:
        return Reason("language", "negative", f"You mostly read in {dominant}.", -5)
    return None


def _band(score: int) -> str:
    if score >= 75:
        return "strong"
    if score >= 60:
        return "moderate"
    return "weak"


def score_affinity(*, scanned: dict, library: list[dict]) -> AffinityResult:
    scanned_authors = {
        normalize_author(a) for a in scanned.get("authors", []) if normalize_author(a)}
    scanned_subjects = {
        normalize_subject(s) for s in scanned.get("subjects", []) if normalize_subject(s)}
    scanned_series = normalize_series(scanned.get("series_name"))
    scanned_lang = (scanned.get("language") or "").lower()[:2] or None

    finished, abandoned = _status_counts(library)
    no_meta = not (scanned_authors or scanned_subjects or scanned_series)
    if (finished < 3 and (finished + abandoned) < 5) or no_meta:
        msg = (f"Not enough reading history yet (you've finished {finished})."
               if not no_meta else "Not enough metadata on this book to compare.")
        return AffinityResult(
            AFFINITY_VERSION, None, "unknown", [Reason("coldstart", "neutral", msg)])

    signals = [s for s in (
        _author_signal(scanned_authors, library),
        _theme_signal(scanned_subjects, library),
        _series_signal(scanned_series, library),
        _language_signal(scanned_lang, library),
    ) if s is not None]

    raw = 50 + sum(s.points for s in signals)
    score = max(0, min(100, round(raw)))
    families = len({s.kind for s in signals})
    has_series_pos = any(s.kind == "series" and s.polarity == "positive" for s in signals)
    # Confidence guard: cap the SCORE itself (not just the band) so the number
    # and band never disagree.
    if families == 1 and not has_series_pos and score >= 75:
        score = 74
    band = _band(score)

    ranked = sorted(signals, key=lambda s: abs(s.points), reverse=True)
    chosen, lang_used = [], False
    for s in ranked:
        if s.polarity == "negative" and s.points > -8:
            continue
        if s.kind == "language":
            if lang_used:
                continue
            lang_used = True
        chosen.append(s)
        if len(chosen) == 4:
            break
    return AffinityResult(AFFINITY_VERSION, score, band, chosen)
