"""Deterministic repair families, applied in order by the rules variant."""

from . import chapters, characters, figures, index, metadata, notes, pages, paragraphs

# Order matters: text structure first, then anchors that depend on it, then navigation,
# figures, notes and index links that point at those anchors, and metadata last.
REPAIRS = [
    paragraphs.apply,
    characters.apply,
    pages.apply,
    chapters.apply,
    figures.apply,
    notes.apply,
    index.apply,
    metadata.apply,
]
