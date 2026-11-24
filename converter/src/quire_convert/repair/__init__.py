"""Deterministic repair families, applied in order by the rules variant."""

from . import chapters, figures, index, metadata, notes, pages

# Order matters: text structure first, then anchors that depend on it, then navigation,
# figures, notes and index links that point at those anchors, and metadata last.
REPAIRS = [
    pages.apply,
    chapters.apply,
    figures.apply,
    notes.apply,
    index.apply,
    metadata.apply,
]
