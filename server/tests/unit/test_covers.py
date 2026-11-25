"""The designed cover: legible, deterministic, and never boxes for a script."""

import io
import time
import unicodedata

import pytest
from PIL import Image

from quire_server.api.details_schemas import MOTIFS
from quire_server.core.covers import (
    HEIGHT,
    WIDTH,
    _capped,
    _colours,
    _contrast,
    _Face,
    _fit,
    cover_png,
    draw_cover,
    has_glyph,
)

DESIGN = {
    "background": "#1F2A44",
    "accent": "#E0A458",
    "ink": "#F4EFE6",
    "motif": "waves",
    "layout": "classic",
    "typeface": "serif",
}

TITLES = {
    "cyrillic": ("Мастер и Маргарита", ["Михаил Булгаков"]),
    "greek": ("Ο Καπετάν Μιχάλης", ["Νίκος Καζαντζάκης"]),
    "chinese": ("三体：地球往事", ["刘慈欣"]),
    "japanese": ("ノルウェイの森", ["村上春樹"]),
    "korean": ("채식주의자", ["한강"]),
    "arabic": ("الحرافيش", ["نجيب محفوظ"]),
}


def _png(data: bytes) -> Image.Image:
    image = Image.open(io.BytesIO(data))
    image.load()
    return image


def test_the_missing_glyph_check_tells_a_box_from_a_letter():
    # Literata has no Chinese or Arabic; the fallbacks do.
    assert not has_glyph("Literata.ttf", "中")
    assert not has_glyph("Literata.ttf", "ب")
    assert has_glyph("DroidSansFallbackFull.ttf", "中")
    assert has_glyph("NotoNaskhArabic.ttf", "ب")
    assert has_glyph("Literata.ttf", "Ж")
    # Droid Sans Fallback has Korean letter parts but no whole syllables;
    # a shaping engine would build one from the parts, which reads wrong.
    assert not has_glyph("DroidSansFallbackFull.ttf", "강")
    assert has_glyph("NanumGothic.ttf", "강")


@pytest.mark.parametrize("typeface", ["serif", "elegant", "sans"])
@pytest.mark.parametrize("script", sorted(TITLES))
def test_every_letter_of_a_non_latin_title_has_a_font(script: str, typeface: str):
    title, authors = TITLES[script]
    face = _Face(
        {"serif": "Literata.ttf", "elegant": "Lora.ttf", "sans": "NotoSans.ttf"}[typeface], 40, 400
    )
    for text in (title, *authors):
        for name, run in face.runs(text):
            for char in run:
                if char.isspace() or unicodedata.combining(char):
                    continue
                assert has_glyph(name, char), f"{char!r} would be a box in {name}"


@pytest.mark.parametrize("script", sorted(TITLES))
def test_non_latin_covers_render(script: str):
    title, authors = TITLES[script]
    image = _png(cover_png(DESIGN, title, authors))
    assert image.size == (WIDTH, HEIGHT)
    assert image.format == "PNG"


def test_the_cover_is_the_same_every_time_and_differs_by_book():
    first = cover_png(DESIGN, "The Left Hand of Darkness", ["Ursula K. Le Guin"])
    again = cover_png(DESIGN, "The Left Hand of Darkness", ["Ursula K. Le Guin"])
    other = cover_png(DESIGN, "The Dispossessed", ["Ursula K. Le Guin"])
    assert first == again
    assert first != other


def test_a_very_long_title_shrinks_and_is_cut_with_an_ellipsis():
    title = " ".join(["The Extraordinarily Long and Winding History"] * 12)
    face, lines = _fit("Literata.ttf", 640, title, 900, 600, largest=72, smallest=26, max_lines=6)
    assert face.size == 26 * 2
    assert len(lines) <= 6
    assert lines[-1].endswith("…")
    assert all(face.width(line) <= 900 for line in lines)


def test_a_long_title_wraps_at_a_smaller_size_without_cutting():
    title = "The Strange Case of Dr Jekyll and Mr Hyde and Other Tales of Terror"
    face, lines = _fit("Literata.ttf", 640, title, 900, 600, largest=72, smallest=26, max_lines=6)
    assert face.size < 72 * 2
    assert not lines[-1].endswith("…")
    assert " ".join(lines) == title


def test_one_long_word_is_broken_to_fit():
    face, lines = _fit(
        "Literata.ttf",
        640,
        "Donaudampfschifffahrtsgesellschaftskapitän",
        300,
        900,
        largest=72,
        smallest=26,
        max_lines=6,
    )
    assert "".join(lines) == "Donaudampfschifffahrtsgesellschaftskapitän"
    assert all(face.width(line) <= 300 for line in lines)


@pytest.mark.parametrize("layout", ["classic", "band", "frame", "stack"])
@pytest.mark.parametrize("authors", [[], [""], ["A. Author"]])
def test_every_layout_draws_with_and_without_an_author(layout: str, authors: list[str]):
    with_author = draw_cover({**DESIGN, "layout": layout}, "Short", ["A. Author"])
    image = draw_cover({**DESIGN, "layout": layout}, "Short", authors)
    assert image.size == (WIDTH, HEIGHT)
    assert len(image.getcolors(1 << 20)) > 2  # not a flat field
    if not any(authors):
        assert image.tobytes() != with_author.tobytes()


@pytest.mark.parametrize("motif", MOTIFS)
def test_every_motif_draws(motif: str):
    image = draw_cover({**DESIGN, "motif": motif}, "Motifs", ["A. Author"])
    assert image.size == (WIDTH, HEIGHT)


def test_bad_colours_fall_back_and_the_ink_stays_legible():
    bg, accent, ink, soft = _colours(
        {"background": "navy", "accent": "#12", "ink": "#000000"}, seed=1
    )
    assert _contrast(ink, bg) >= 4.5
    # Ink as dark as the background is replaced.
    bg, accent, ink, soft = _colours(
        {"background": "#101010", "accent": "#141414", "ink": "#202020"}, seed=0
    )
    assert _contrast(ink, bg) >= 4.5
    assert _contrast(accent, bg) > 1.0
    assert draw_cover({"motif": "nonsense", "layout": "nonsense"}, "Fallbacks", []).size == (
        WIDTH,
        HEIGHT,
    )


@pytest.mark.parametrize("layout", ["classic", "band", "frame", "stack"])
@pytest.mark.parametrize(
    ("title", "authors"),
    [("W" * 512, ["Ann Author"]), ("Title", ["M" * 256] * 16)],
    ids=["unbroken-title", "long-authors"],
)
def test_the_longest_input_draws_quickly(layout: str, title: str, authors: list[str]):
    """An unbroken word once took time cubic in its length: 512 letters ran
    for over a minute, holding a worker thread."""
    start = time.perf_counter()
    cover_png({**DESIGN, "layout": layout}, title, authors)
    assert time.perf_counter() - start < 3


def test_only_what_fits_on_a_cover_is_drawn():
    assert _capped("Dune", 200) == "Dune"
    long = " ".join(["word"] * 100)
    cut = _capped(long, 200)
    assert len(cut) <= 200 and cut.endswith("word…")
    assert _capped("W" * 512, 200) == "W" * 199 + "…"
