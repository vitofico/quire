"""Request and response shapes of `/details/v1/*`, and the shape of the tidy call."""

from __future__ import annotations

import unicodedata
from typing import Annotated, Literal, get_args

from pydantic import BaseModel, ConfigDict, Field, field_validator

from quire_server.api.ai_schemas import DocumentIdentity

# "ai": the model's rewording of a source text; "ai_written": a description
# the model wrote where no source had one.
Origin = Literal["file", "catalog", "openlibrary", "ai", "ai_written"]
Motif = Literal[
    "sun",
    "moon",
    "stars",
    "waves",
    "mountains",
    "rings",
    "grid",
    "dots",
    "stripes",
    "diagonals",
    "arch",
    "diamonds",
    "chevrons",
    "triangles",
    "rain",
    "spiral",
    "orbit",
    "burst",
    "squares",
    "horizon",
]
Layout = Literal["classic", "band", "frame", "stack"]
Typeface = Literal["serif", "elegant", "sans"]
MOTIFS: tuple[str, ...] = get_args(Motif)


def _collapse(value: str | None) -> str | None:
    # NFC, so "Café" typed with a combining accent keys the same record.
    return " ".join(unicodedata.normalize("NFC", value).split()) or None if value else None


class BookFields(BaseModel):
    """A book's details: what the client sends, and what the server answers.

    Text is NFC-normalised, whitespace runs collapse to one space and blank
    values count as missing, so two readers whose files differ only in
    spacing or accent encoding share one record. Clients render every field
    as plain text.
    """

    model_config = ConfigDict(extra="forbid")

    title: str = Field(min_length=1, max_length=512)
    authors: list[Annotated[str, Field(max_length=256)]] = Field(
        default_factory=list, max_length=16
    )
    series_name: str | None = Field(default=None, max_length=256)
    series_index: float | None = Field(default=None, ge=0, le=10_000)
    description: str | None = Field(default=None, max_length=8192)
    subjects: list[Annotated[str, Field(max_length=80)]] = Field(
        default_factory=list, max_length=64
    )
    language: str | None = Field(default=None, max_length=32)
    isbn: str | None = Field(default=None, max_length=64)
    year: int | None = Field(default=None, ge=0, le=9999)
    publisher: str | None = Field(default=None, max_length=256)

    @field_validator("title", "series_name", "description", "language", "isbn", "publisher")
    @classmethod
    def _text(cls, value: str | None, info) -> str | None:
        value = _collapse(value)
        if value is None and info.field_name == "title":
            raise ValueError("title must not be blank")
        return value

    @field_validator("authors", "subjects")
    @classmethod
    def _items(cls, value: list[str]) -> list[str]:
        return [item for item in map(_collapse, value) if item]


class DetailsLookupBody(BaseModel):
    """Body of `POST /details/v1/lookup` and `POST /details/v1/tidy`."""

    model_config = ConfigDict(extra="forbid")

    identity: DocumentIdentity
    book: BookFields
    # Where the client's fields come from: the book file or its catalog entry.
    source: Literal["file", "catalog"]
    # The real cover the client already has, if any. A generated cover never
    # replaces one.
    cover: Literal["file", "catalog"] | None = None


class DetailsCover(BaseModel):
    """The cover to show: the client's own, Open Library's or a generated one.

    ``url`` is absolute for Open Library and a server path for a generated
    cover; it is null for the client's own cover and when there is none.
    A generated cover may be drawn on painted artwork (``painted``), or have
    artwork on its way (``painting``): a later lookup then answers with a
    new ``url``.
    """

    source: Literal["file", "catalog", "openlibrary", "generated"] | None
    url: str | None = None
    generated: bool = False
    painted: bool = False
    painting: bool = False


class DetailsResponse(BaseModel):
    key: str
    book: BookFields
    # Where each field that has a value came from.
    origins: dict[str, Origin]
    cover: DetailsCover
    # "done": the record includes a tidy; "not_needed": nothing called for
    # one; "quota_exceeded" and "failed": one was due and could not run.
    tidy: Literal["done", "not_needed", "quota_exceeded", "failed"]
    # True when the tidy was served from the shared record, at no cost.
    cached: bool
    # The Open Library work the details were matched to, for attribution.
    openlibrary_url: str | None = None


class CoverDesign(BaseModel):
    model_config = ConfigDict(extra="forbid")

    background: str = Field(description="#RRGGBB")
    accent: str = Field(description="#RRGGBB")
    ink: str = Field(description="#RRGGBB, the colour of the title and author")
    motif: Motif
    layout: Layout
    typeface: Typeface


class TidyOutput(BaseModel):
    """What the tidy call returns. There is no field for a year, an ISBN, a
    publisher or a language: those come only from the file, the catalog or
    Open Library."""

    model_config = ConfigDict(extra="forbid")

    title: str
    authors: list[str] = Field(default_factory=list)
    series_name: str | None = None
    series_index: float | None = None
    description: str | None = None
    design: CoverDesign
