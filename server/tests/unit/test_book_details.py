"""The tidy's guards and the file-name check, without a database."""

import time
import unicodedata
from types import SimpleNamespace

import pytest

from quire_server.api.details_schemas import (
    BookFields,
    CoverDesign,
    DetailsLookupBody,
    TidyOutput,
)
from quire_server.core.ai.details import (
    Found,
    RecentFailures,
    assemble,
    details_key,
    guard,
    looks_like_file_name,
    needs_tidy,
)
from quire_server.core.ai.retrieval import OpenLibraryBook

DESIGN = CoverDesign(
    background="#F3EBDD",
    accent="#B4532A",
    ink="#2B2118",
    motif="sun",
    layout="classic",
    typeface="serif",
)
DUNE = OpenLibraryBook(
    url="https://openlibrary.org/works/OL893415W",
    title="Dune",
    authors=["Frank Herbert"],
    first_publish_year=1965,
    cover_url="https://covers.openlibrary.org/b/id/1-L.jpg",
    description="A desert planet and the spice that everyone wants.",
    subjects=["Science fiction"],
)


def _out(**kw) -> TidyOutput:
    return TidyOutput(**{"title": "Dune", "authors": [], "design": DESIGN, **kw})


@pytest.mark.parametrize(
    ("title", "authors"),
    [
        ("dune_frank_herbert_(retail)", []),
        ("Dune.epub", []),
        ("Dune [epub]", []),
        ("Dune (retail)", []),
        ("Frank.Herbert-Dune", []),
        ("dune messiah", []),
        ("Frank Herbert - Dune", ["Frank Herbert"]),
        ("Children of Dune (z-lib.org)", []),
    ],
)
def test_file_name_titles_are_recognised(title, authors):
    assert looks_like_file_name(title, authors)


@pytest.mark.parametrize(
    ("title", "authors"),
    [
        ("Dune", ["Frank Herbert"]),
        ("Mrs. Dalloway", ["Virginia Woolf"]),
        ("Catch-22", ["Joseph Heller"]),
        ("The Hitchhiker's Guide to the Galaxy", ["Douglas Adams"]),
        ("三体", ["刘慈欣"]),
        ("الحرافيش", ["نجيب محفوظ"]),
        ("Herbert", ["Frank Herbert"]),  # one word: may well be the real title
    ],
)
def test_real_titles_are_left_alone(title, authors):
    assert not looks_like_file_name(title, authors)


def test_the_tidy_cleans_a_file_name_title_and_splits_authors():
    fields = BookFields(title="dune_frank_herbert_(retail)", authors=["Herbert, Frank"])
    record = guard(fields, None, _out(title="Dune", authors=["Frank Herbert"]))
    assert record["title"] == "Dune"
    assert record["authors"] == ["Frank Herbert"]
    assert record["origins"] == {"title": "ai", "authors": "ai"}


def test_the_tidy_cannot_invent_a_title_word_an_author_or_a_series_number():
    fields = BookFields(title="dune_frank_herbert", authors=["Frank Herbert"])
    record = guard(
        fields,
        None,
        _out(
            title="Dune (1965)",
            authors=["Frank Herbert", "Kevin J. Anderson"],
            series_name="Dune Chronicles",
            series_index=1,
        ),
    )
    assert record["title"] == "dune_frank_herbert"
    assert record["authors"] == ["Frank Herbert"]
    assert record["series_name"] is None
    assert record["series_index"] is None
    assert record["origins"] == {"title": "input", "authors": "input"}


def test_a_series_stated_in_the_title_is_kept():
    fields = BookFields(title="The Way of Kings (Stormlight Archive 1)", authors=["B. Sanderson"])
    record = guard(
        fields,
        None,
        _out(title="The Way of Kings", series_name="Stormlight Archive", series_index=1),
    )
    assert record["series_name"] == "Stormlight Archive"
    assert record["series_index"] == 1
    assert record["origins"]["series_name"] == "ai"


def test_the_description_loses_every_sentence_with_an_edition_fact():
    fields = BookFields(title="Dune", authors=["Frank Herbert"], description="Old blurb.")
    record = guard(
        fields,
        None,
        _out(
            description=(
                "A boy inherits a desert planet. "
                "First published by Chilton Books in 1965. "
                "ISBN 9780441013593 is the Ace edition. "
                "It runs to 412 pages. "
                "Spice is everything."
            )
        ),
    )
    text = record["description"]
    assert text == "A boy inherits a desert planet. Spice is everything."
    for fact in ("1965", "9780441013593", "Chilton", "412", "Ace"):
        assert fact not in text
    # Not the reader's text any more, so not labelled as theirs.
    assert record["origins"]["description"] == "ai"


def test_numbers_the_sources_hold_may_stay():
    fields = BookFields(title="1984", authors=["George Orwell"], description="Big Brother.")
    record = guard(fields, None, _out(title="1984", description="In 1984, Big Brother watches."))
    assert record["description"] == "In 1984, Big Brother watches."


def test_a_description_from_nothing_needs_an_open_library_match():
    fields = BookFields(title="Dune", authors=["Frank Herbert"])
    invented = _out(description="A thrilling saga of betrayal.")
    assert guard(fields, None, invented)["description"] is None
    with_match = guard(fields, DUNE, invented)
    assert with_match["description"] == "A thrilling saga of betrayal."
    # Open Library had a description, so this one is a rewording of it.
    assert with_match["origins"]["description"] == "ai"
    ol_less = OpenLibraryBook(**{**DUNE.__dict__, "description": None})
    # No source had one: the model wrote it.
    assert guard(fields, ol_less, invented)["origins"]["description"] == "ai_written"


def test_the_record_has_no_place_for_year_isbn_publisher_or_language():
    fields = BookFields(title="dune_frank_herbert", authors=["Frank Herbert"])
    record = guard(fields, DUNE, _out(title="Dune", authors=["Frank Herbert"]))
    assert set(record) == {
        "title",
        "authors",
        "series_name",
        "series_index",
        "description",
        "origins",
    }
    assert set(TidyOutput.model_fields) == {
        "title",
        "authors",
        "series_name",
        "series_index",
        "description",
        "design",
    }


def test_the_key_covers_identity_and_every_field_but_not_spacing():
    base = BookFields(title="Dune", authors=["Frank Herbert"])
    key = details_key("metadata_id:a", base)
    assert key == details_key(
        "metadata_id:a", BookFields(title=" Dune ", authors=["Frank  Herbert"])
    )
    assert key != details_key("metadata_id:b", base)
    assert key != details_key("metadata_id:a", base.model_copy(update={"year": 1965}))
    assert key != details_key("metadata_id:a", base.model_copy(update={"publisher": "Ace"}))
    assert len(key) == 64


def test_when_a_lookup_tidies_on_its_own():
    clean = BookFields(title="Dune", authors=["Frank Herbert"], description="Spice.")
    # Everything present: no tidy.
    assert not needs_tidy(clean, Found(book=None, language=None), cover="file")
    # Open Library brings the cover: no tidy.
    assert not needs_tidy(clean, Found(book=DUNE, language=None), cover=None)
    # No cover anywhere: the tidy designs one.
    assert needs_tidy(clean, Found(book=None, language=None), cover=None)
    # A file-name title.
    messy = clean.model_copy(update={"title": "dune_(retail)"})
    assert needs_tidy(messy, Found(book=None, language=None), cover="file")
    # Matched, but no authors, or no description anywhere.
    no_authors = clean.model_copy(update={"authors": []})
    assert needs_tidy(no_authors, Found(book=DUNE, language=None), cover="file")
    no_description = BookFields(title="Dune", authors=["Frank Herbert"])
    bare = OpenLibraryBook(**{**DUNE.__dict__, "description": None})
    assert needs_tidy(no_description, Found(book=bare, language=None), cover="file")
    # Nothing matched and nothing to write a description from: no tidy.
    assert not needs_tidy(no_description, Found(book=None, language=None), cover="file")


def test_a_real_title_keeps_its_words_and_their_order():
    """Dropping a word may turn a title into its opposite."""
    fields = BookFields(title="How Not to Die", authors=["Michael Greger"])
    for wrong in ("How to Die", "Die Not to How", "Not to Die"):
        assert guard(fields, None, _out(title=wrong))["title"] == "How Not to Die"


def test_a_real_title_may_shed_its_series_and_its_author():
    fields = BookFields(title="Dune Messiah (Dune Chronicles Book 2)", authors=["Frank Herbert"])
    record = guard(
        fields,
        None,
        _out(title="Dune Messiah", series_name="Dune Chronicles", series_index=2),
    )
    assert record["title"] == "Dune Messiah"
    assert record["series_index"] == 2
    named = BookFields(title="Frank Herbert - Dune", authors=["Frank Herbert"])
    assert guard(named, None, _out(title="Dune"))["title"] == "Dune"


def test_names_from_the_title_only_when_it_is_a_file_name():
    real = BookFields(title="Harry Potter and the Philosopher's Stone", authors=["J.K. Rowling"])
    record = guard(real, None, _out(title=real.title, authors=["Harry Potter"]))
    assert record["authors"] == ["J.K. Rowling"]
    file_name = BookFields(title="rowling_joanne_harry_potter", authors=[])
    record = guard(file_name, None, _out(title="Harry Potter", authors=["Joanne Rowling"]))
    assert record["authors"] == ["Joanne Rowling"]


def test_names_are_whole_words_of_the_input():
    fields = BookFields(title="The Hobbit", authors=["J.R.R. Tolkien"])
    # "Hob" and "Tolk" are pieces of words, not words.
    record = guard(fields, None, _out(title="The Hobbit", authors=["Tolk"], series_name="Hob"))
    assert record["authors"] == ["J.R.R. Tolkien"]
    assert record["series_name"] is None


@pytest.mark.parametrize(
    ("fields", "answer", "name", "kept"),
    [
        (
            {"title": "Report 99999 Book", "authors": ["A B"]},
            {"title": "Report Book", "series_name": "Report", "series_index": 99999},
            "series_index",
            None,
        ),
        (
            {"title": "War and Peace", "authors": ["Leo Tolstoy"]},
            {"title": " ".join(["War and Peace"] * 60)},
            "title",
            "War and Peace",
        ),
        (
            {"title": "War and Peace", "authors": ["Leo Tolstoy"]},
            {"title": "War and Peace", "authors": [" ".join(["Leo Tolstoy"] * 30)]},
            "authors",
            ["Leo Tolstoy"],
        ),
    ],
)
def test_the_stored_record_always_fits_the_field_caps(fields, answer, name, kept):
    book = BookFields(**fields)
    record = guard(book, None, _out(**{"authors": book.authors, **answer}))
    assert record[name] == kept
    BookFields.model_validate({k: v for k, v in record.items() if k in BookFields.model_fields})


def test_a_stored_record_over_the_caps_is_served_without_it():
    """Rows written before the caps were checked must not fail every lookup."""
    fields = BookFields(title="War and Peace", authors=["Leo Tolstoy"])
    record = {
        "title": "x" * 600,
        "authors": ["y" * 300],
        "series_name": "War",
        "series_index": 99999,
        "description": "A long book.",
        "origins": {
            "title": "ai",
            "authors": "ai",
            "series_name": "ai",
            "series_index": "ai",
            "description": "ai",
        },
    }
    row = SimpleNamespace(record=record, design=DESIGN.model_dump())
    body = DetailsLookupBody(
        identity={"content_hash": "x"}, book=fields, source="file", cover="file"
    )
    out = assemble(body, "k" * 64, row, Found(None, None), tidy="done", cached=True)
    assert out.book.title == "War and Peace" and out.book.authors == ["Leo Tolstoy"]
    assert out.book.series_index is None
    assert out.book.series_name == "War" and out.book.description == "A long book."
    assert out.origins["title"] == "file"


def test_the_description_is_plain_text():
    fields = BookFields(title="Dune", authors=["Frank Herbert"], description="A desert planet.")
    record = guard(
        fields,
        None,
        _out(
            description=(
                "<p>A <b>desert</b> planet.</p> **Spice** is _everything_ to "
                "[the Fremen](https://example.org/fremen). "
                "Read it free at www.example.org/dune today."
            )
        ),
    )
    assert record["description"] == "A desert planet. Spice is everything to the Fremen."
    assert record["origins"]["description"] == "ai"


def test_the_description_origin_names_who_wrote_it():
    fields = BookFields(title="Dune", authors=["Frank Herbert"], description="A desert planet.")
    assert guard(fields, None, _out(description="A desert planet."))["origins"]["description"] == (
        "input"
    )
    bare = BookFields(title="Dune", authors=["Frank Herbert"])
    copied = guard(bare, DUNE, _out(description=DUNE.description))
    assert copied["origins"]["description"] == "openlibrary"


def test_the_key_is_the_same_for_composed_and_decomposed_text():
    composed = unicodedata.normalize("NFC", "Café")
    decomposed = unicodedata.normalize("NFD", "Café")
    assert composed != decomposed
    assert details_key("metadata_id:a", BookFields(title=composed)) == details_key(
        "metadata_id:a", BookFields(title=decomposed)
    )


def test_recent_failures_expire_and_stay_bounded():
    now = [0.0]
    failures = RecentFailures(ttl_s=900, size=2, clock=lambda: now[0])
    failures.add("a")
    assert failures.recent("a") and not failures.recent("b")
    now[0] = 901
    assert not failures.recent("a")
    for key in ("a", "b", "c"):
        failures.add(key)
    assert not failures.recent("a")  # the oldest made room
    assert failures.recent("b") and failures.recent("c")
    failures.discard("b")
    assert not failures.recent("b")
    assert time.monotonic()  # the default clock
