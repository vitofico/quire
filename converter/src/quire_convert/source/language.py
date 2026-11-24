"""A small stopword-frequency language guess for the languages the converter qualifies."""

from __future__ import annotations

import re
from collections import Counter
from collections.abc import Iterable

_STOPWORDS = {
    "en": "the and of to in is that it was for with as his he on be at by this had not are "
    "but from have they which you were her she an their would there been has its we",
    "it": "il di che non per una sono gli della del nel anche più questo come ma ed alla "
    "delle dei lo si da ha è era nella degli ci le un con al cui suo sua loro quando",
    "fr": "le les des du et est une dans que qui pas pour sur au il elle ne se ce avec plus "
    "par nous vous sont mais ou été cette aux leur son sa était fait comme",
    "de": "der die das und ist nicht ein eine zu den mit sich auf für dem von des im es auch "
    "als wie bei nach aus wird sind oder aber noch war ich er sie wir",
    "es": "el los las que del por una con para es se su como pero más está y al lo sus muy "
    "sin sobre entre también fue era cuando hay ha porque esta todo",
    "pt": "o os as que não do da dos das em um uma para com é por mais se como mas foi ao seu "
    "sua ele está também são já nos pela pelo quando muito isso",
}
LISTS = {lang: frozenset(words.split()) for lang, words in _STOPWORDS.items()}
_TOKEN = re.compile(r"[^\W\d_]+(?:['’][^\W\d_]+)?")
MIN_TOKENS = 40  # below this the text is too short to trust
MIN_SCORE = 0.08  # share of tokens that are stopwords of the winning language
MIN_MARGIN = 1.25  # winner must beat the runner-up by this factor


def tokens(texts: Iterable[str], limit: int = 200_000) -> list[str]:
    out: list[str] = []
    for text in texts:
        out.extend(t.lower() for t in _TOKEN.findall(text))
        if len(out) >= limit:
            break
    return out[:limit]


def detect(words: list[str]) -> tuple[str | None, dict[str, float]]:
    """Best language and the per-language stopword shares; None when not confident."""
    if not words:
        return None, {}
    counts = Counter(words)
    total = len(words)
    scores = {lang: round(sum(counts[w] for w in stop) / total, 4) for lang, stop in LISTS.items()}
    ranked = sorted(scores.items(), key=lambda kv: kv[1], reverse=True)
    (best, top), (_, second) = ranked[0], ranked[1]
    confident = total >= MIN_TOKENS and top >= MIN_SCORE and top >= MIN_MARGIN * second
    return (best if confident else None), scores


def primary(tag: str | None) -> str | None:
    """Primary subtag of a BCP 47 tag, lowercased: ``it-IT`` -> ``it``."""
    if not tag:
        return None
    head = re.split(r"[-_]", tag.strip())[0].lower()
    return head if head.isalpha() and 2 <= len(head) <= 3 else None


def choose(
    detected: str | None, scores: dict[str, float], n_tokens: int, declared: list[str]
) -> str | None:
    """Prefer text evidence; use a declared tag when it agrees or the text is too short."""
    declared = [d for d in declared if primary(d)]
    if detected:
        agreeing = [d for d in declared if primary(d) == detected]
        return agreeing[0] if agreeing else detected
    if declared and n_tokens < MIN_TOKENS:
        return declared[0]
    if declared:
        # Text was long enough but unconfident: keep a declared tag the text does not contradict.
        best = max(scores, key=scores.get) if scores else None
        for d in declared:
            if primary(d) == best or primary(d) not in LISTS:
                return d
    return None
