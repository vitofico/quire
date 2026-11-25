"""Book details: fill what a book is missing, tidy what is messy.

Two steps, in the order a lookup runs them:

1. **Find.** When the book lacks a cover, a description, subjects, a
   language, an ISBN or a year, look it up on Open Library with the
   retriever the insights use, through the same check that the result is
   this book (title forms, volume, format, author surname). No match means
   no result, never a guess. No model call, nothing counted.
2. **Tidy.** One model call gets the book's own fields and what the lookup
   found, and returns a cleaned title, authors, series and description plus
   a cover design. It runs on its own only when the title looks like a file
   name or something stays missing after the lookup, and whenever a reader
   asks. It counts as one generation; a stored tidy is served free.

What the tidy may not do is enforced here, after the call: it supplies no
fact. Year, ISBN, publisher and language have no field in its answer and
come only from the client's fields or Open Library. A title, author or
series name whose words are not in the input is refused. A series number
must appear in the input. Sentences of the description that state a
publication detail or a number the sources do not hold are dropped, and a
description is written from nothing only when Open Library matched.

The tidied record is shared like ``book_insights``, keyed by
``details_key``: a hash of the book's canonical identity together with the
fields the client sent. The record is a function of that input and of what
Open Library holds, so a reader who sends false fields only writes a record
that no reader with the true fields will ever be served.
"""

from __future__ import annotations

import hashlib
import json
import logging
import re
import time
from collections import OrderedDict
from collections.abc import Callable
from dataclasses import asdict, dataclass
from typing import Literal

from fastapi import HTTPException
from pydantic import ValidationError
from sqlalchemy import func
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.ext.asyncio import AsyncSession

from quire_server.api.ai_schemas import DocumentIdentity
from quire_server.api.details_schemas import (
    BookFields,
    DetailsCover,
    DetailsLookupBody,
    DetailsResponse,
    TidyOutput,
)
from quire_server.core.ai.client import ProviderError
from quire_server.core.ai.identity import IDENTITY_HIERARCHY, resolve_identity
from quire_server.core.ai.prompts import _normalize_book_language
from quire_server.core.ai.retrieval import (
    OpenLibraryBook,
    _match_form,
    _surname,
    _volume_number,
    plain_text,
)
from quire_server.core.ai.service import InsightOrchestrator, QuotaExceeded
from quire_server.core.covers import RENDERER_VERSION
from quire_server.db.models import BookDetails, BookDetailsReader

logger = logging.getLogger(__name__)

# Part of every record key: bump it when the prompt or the guards change what
# a tidy produces, and every book is tidied afresh on its next lookup.
DETAILS_PROMPT_VERSION = "details-1"
_DESCRIPTION_CAP = 2000
_OPENLIBRARY_DESCRIPTION_CAP = 4000

SYSTEM_PROMPT = """You tidy the catalogue record of one book for a reading app.

The user message is JSON: "book" is the book's own record, "open_library" is
the Open Library record of the same work, or null when none was found.

Return:
- title: the title as it would be printed on the cover, in the normal case for
  its language. Remove file-name leftovers (underscores, file extensions, dots
  or dashes standing for spaces), release and format tags such as "(retail)",
  "[epub]", "v5" or "z-lib", the author's name, and a series name or number
  that was only appended. Keep a real subtitle after a colon.
- authors: one entry per person, each written "First Last". Split joint
  credits such as "A & B" or "Last, First; Last2, First2". Never add a person
  the input does not name.
- series_name and series_index: the series and the book's number in it, only
  when the input states them, for example in the title. Otherwise null.
- description: when the book or Open Library has a description, that text
  cleaned: no HTML or Markdown, no praise quotes, no sales copy, no
  publication details. When neither has one and open_library is given, two or
  three plain sentences on what the book is, using only that record. Never
  invent plot, characters or claims. Otherwise null. Write it in the book's
  language when the record gives one.
- design: a cover that suits the book. background, accent and ink are colours
  written #RRGGBB, and the ink must read clearly on the background. Pick the
  motif closest to the book's subject or mood, a layout, and a typeface:
  serif for most fiction, elegant for romance, poetry, history and classics,
  sans for non-fiction, technical books and science fiction.

Never write a year, an ISBN, a publisher, an edition or a language name:
those come from the records, not from you. Use only facts in the input.
Return only the JSON object."""

# Words that put a sentence about the edition rather than the book.
_PUBLICATION = re.compile(
    r"\b(?:publish\w*|publisher\w*|imprint|isbn\w*|edition\w*|reprint\w*|copyright)\b",
    re.IGNORECASE,
)
_ADDRESS = re.compile(r"https?://|www\.", re.IGNORECASE)
_NUMBER = re.compile(r"\d+(?:[-\s]\d+)*")
_SENTENCE_END = re.compile(r"(?<=[.!?])\s+")
_EBOOK_EXTENSION = re.compile(r"\.(?:epub|pdf|mobi|azw3?|fb2|cbz|cbr|djvu|txt|docx?)$", re.I)
_RELEASE_TAG = re.compile(
    r"[(\[{]\s?(?:retail|epub|mobi|pdf|azw3?|v\d+(?:\.\d+)*|z-?lib[^()\[\]{}]*|scan\w*|ocr|ebook)\s?[)\]}]",
    re.IGNORECASE,
)
_GLUED_WORDS = re.compile(r"[A-Za-z][._-][A-Za-z]")

TidyStatus = Literal["done", "not_needed", "quota_exceeded", "failed"]


@dataclass(frozen=True)
class Found:
    """What the find step added, and from where."""

    book: OpenLibraryBook | None
    language: str | None


def details_key(identity: str, fields: BookFields) -> str:
    """The shared record's key: the canonical identity and the exact fields.

    Every field the client sends is in it, not only the ones the tidy reads,
    so the record can never be served for input it was not made from. The
    client's cover flag and cover choice, the enabled sources and the model
    are not: they
    change what is looked up or who wrote it, not what the record may say.
    """
    canonical = json.dumps(
        {"v": DETAILS_PROMPT_VERSION, "identity": identity, "book": fields.model_dump()},
        sort_keys=True,
        ensure_ascii=False,
        separators=(",", ":"),
    )
    return hashlib.sha256(canonical.encode()).hexdigest()


def cover_etag(row: BookDetails, art_digest: str | None = None) -> str:
    """Changes whenever anything drawn on the cover does, the painted
    artwork under it included."""
    drawn = {
        "v": RENDERER_VERSION,
        "design": row.design,
        "title": row.record.get("title"),
        "authors": row.record.get("authors"),
    }
    if art_digest:
        drawn["art"] = art_digest
    raw = json.dumps(drawn, sort_keys=True, ensure_ascii=False).encode()
    return hashlib.sha256(raw).hexdigest()[:20]


def looks_like_file_name(title: str, authors: list[str]) -> bool:
    """Whether a title still carries the marks of a file name.

    "dune_frank_herbert_(retail)", "Dune.epub", "Dune [epub]",
    "Frank.Herbert-Dune", a title in lower case only, or one that names its
    own author ("Frank Herbert - Dune").
    """
    if "_" in title or _EBOOK_EXTENSION.search(title) or _RELEASE_TAG.search(title):
        return True
    if len(title) > 8 and not any(c.isspace() for c in title) and _GLUED_WORDS.search(title):
        return True
    # Scripts without case, such as Chinese or Arabic, are neither.
    cased = [c for c in title if c.isupper() or c.islower()]
    if cased and not any(c.isupper() for c in cased):
        return True
    words = _match_form(title).split()
    for author in authors:
        surname = _surname(author)
        if surname and len(words) > 1 and surname in words:
            return True
    return False


async def canonical_identity(
    session: AsyncSession, ident: DocumentIdentity, *, user_id: str
) -> str | None:
    """ "metadata_id:..." or "content_hash:...", resolving alias hints as the
    insights do; None when nothing resolves."""
    for scheme in IDENTITY_HIERARCHY:
        value = getattr(ident, scheme, None)
        if not value:
            continue
        found = await resolve_identity(
            session, alias_scheme=scheme, alias_value=value, user_id=user_id
        )
        if found is not None:
            return str(found)
    return None


async def find(
    orch: InsightOrchestrator, session: AsyncSession, fields: BookFields, *, cover: str | None
) -> Found:
    """Look up what the book is missing. Never raises: a failure finds nothing."""
    missing = (
        cover is None
        or not fields.description
        or not fields.subjects
        or not fields.language
        or not fields.isbn
        or fields.year is None
    )
    if not missing or "openlibrary" not in orch.sources_enabled:
        return Found(book=None, language=None)
    retriever = orch.retriever_factory(session)
    book = None
    language = None
    # A title alone matches too many books: the author or the ISBN must back it.
    if fields.authors or fields.isbn:
        try:
            book = await retriever.lookup_openlibrary_book(
                author=fields.authors[0] if fields.authors else None,
                title=fields.title,
                isbn=fields.isbn,
            )
        except Exception as e:
            logger.info("details.find.failed err=%s", type(e).__name__)
    if not fields.language and fields.isbn:
        try:
            raw = await retriever.lookup_book_language(fields.isbn)
        except Exception as e:
            logger.info("details.language.failed err=%s", type(e).__name__)
            raw = None
        language = _normalize_book_language(raw) if raw else None
    return Found(book=book, language=language)


def kept_cover(body: DetailsLookupBody) -> str | None:
    """The client's own cover, unless the reader asked for another one."""
    return body.cover if body.cover_choice == "keep" else None


def needs_tidy(fields: BookFields, found: Found, *, cover: str | None) -> bool:
    """Whether a lookup tidies on its own.

    It does when the title looks like a file name, when no cover exists
    (the tidy designs one), and when the authors or the description are
    missing but Open Library matched, so there are facts to take them from.
    A missing description with nothing to write it from is no reason to spend
    a generation."""
    book = found.book
    return (
        looks_like_file_name(fields.title, fields.authors)
        or not (cover or (book and book.cover_url))
        or (book is not None and not fields.authors)
        or (book is not None and not (fields.description or book.description))
    )


def _words(*texts: str | None) -> list[str]:
    """Words in match form, apostrophes dropped: "Ender's Game" and
    "enders_game" both give ["enders", "game"]."""
    joined = " ".join(t for t in texts if t)
    return _match_form(re.sub(r"['’]", "", joined)).split()


def _compact(*texts: str | None) -> str:
    """The words run together: "TheHobbit.epub" reads "thehobbitepub"."""
    return "".join(_words(*texts))


def _drawn(words: list[str], allowed: set[str], glued: str | None = None) -> bool:
    """Whether every word is one of ``allowed``, or, for a file-name title,
    a piece of ``glued`` ("the" and "hobbit" out of "TheHobbit.epub")."""
    return bool(words) and all(w in allowed or (glued is not None and w in glued) for w in words)


def _in_order(words: list[str], source: list[str]) -> tuple[bool, list[str]]:
    """Whether ``words`` appear in ``source`` in the same order, and the
    words of ``source`` left out."""
    dropped: list[str] = []
    rest = iter(source)
    for word in words:
        for candidate in rest:
            if candidate == word:
                break
            dropped.append(candidate)
        else:
            return False, []
    dropped.extend(rest)
    return True, dropped


# Words a title may lose without changing which book it names.
_DROPPABLE = frozenset({"a", "book", "vol", "volume", "part", "no", "nr", "series", "novel"})


def _numbers(*texts: str | None) -> set[str]:
    return {re.sub(r"\D", "", n) for t in texts if t for n in _NUMBER.findall(t)}


def _scrub(description: str, sources: set[str]) -> str | None:
    """Plain text without the sentences that state an edition detail, carry
    an address, or a number the sources do not hold: a year, an ISBN, a page
    count from nowhere."""
    kept = []
    # Markup goes first, so a tag does not hide where a sentence ends; an
    # address stays until its sentence is judged.
    for sentence in _SENTENCE_END.split(plain_text(description, drop_addresses=False)):
        if _ADDRESS.search(sentence) or _PUBLICATION.search(sentence):
            continue
        if any(len(n) >= 3 and n not in sources for n in _numbers(sentence)):
            continue
        kept.append(sentence)
    return " ".join(kept)[:_DESCRIPTION_CAP] or None


def _series_numbers(fields: BookFields) -> set[float]:
    found = {float(n) for n in re.findall(r"\d+(?:\.\d+)?", fields.title)}
    if (volume := _volume_number(fields.title)) is not None:
        found.add(volume)
    if fields.series_index is not None:
        found.add(fields.series_index)
    return found


def _fits(fields: BookFields, name: str, value) -> bool:
    """Whether ``value`` passes the caps of ``BookFields.<name>``."""
    try:
        BookFields.model_validate({**fields.model_dump(), name: value})
    except ValidationError:
        return False
    return True


_TIDIED = ("title", "authors", "series_name", "series_index", "description")


def guard(fields: BookFields, book: OpenLibraryBook | None, out: TidyOutput) -> dict:
    """The stored record: the tidy's answer with everything it may not say
    taken out. Each field records "input", "openlibrary" or "ai" as its
    origin; "input" becomes the client's source when served.

    Names must be whole words of the input. A title may drop words only when
    it was a file name, or when they moved to the series or named the
    author; otherwise it keeps the input's words in their order. Authors may
    come from the title only when it was a file name."""
    file_name = looks_like_file_name(fields.title, fields.authors)
    title_words = _words(fields.title)
    glued = _compact(fields.title) if file_name else None
    ol_title = book.title if book else None
    ol_authors = book.authors if book else []

    series_name = fields.series_name
    series_index = fields.series_index
    proposed = " ".join((out.series_name or "").split())
    if proposed and _drawn(_words(proposed), set(title_words) | set(_words(fields.series_name))):
        series_name = proposed
        if out.series_index is not None and out.series_index in _series_numbers(fields):
            series_index = out.series_index

    title = " ".join(out.title.split())
    words = _words(title)
    if not words:
        allowed = False
    elif ol_title and words == _words(ol_title):
        allowed = True
    elif file_name:
        allowed = _drawn(words, set(title_words) | set(_words(fields.series_name)), glued)
    else:
        ordered, dropped = _in_order(words, title_words)
        movable = set(_words(series_name, *fields.authors)) | _DROPPABLE
        allowed = ordered and all(w in movable or w.isdigit() for w in dropped)
    if not allowed:
        title = fields.title

    names = set(_words(*fields.authors, *ol_authors))
    if file_name:
        names |= set(title_words)
    authors = [" ".join(a.split()) for a in out.authors if a.strip()][:8]
    if not authors or not all(_drawn(_words(a), names, glued) for a in authors):
        authors = fields.authors

    source_description = fields.description or (book.description if book else None)
    description = None
    if out.description and (source_description or book is not None):
        description = _scrub(
            out.description,
            _numbers(
                fields.title,
                source_description,
                fields.isbn,
                fields.publisher,
                str(fields.year) if fields.year is not None else None,
                str(book.first_publish_year) if book and book.first_publish_year else None,
            ),
        )

    record = {
        "title": title,
        "authors": authors,
        "series_name": series_name,
        "series_index": series_index,
        "description": description,
    }
    # A value the API could not answer with falls back to the reader's own.
    for name in _TIDIED:
        if not _fits(fields, name, record[name]):
            record[name] = getattr(fields, name)

    origins = {
        "title": _origin(record["title"], fields.title, ol_title),
        "authors": _origin(record["authors"], fields.authors, ol_authors),
    }
    for name in ("series_name", "series_index"):
        if record[name] is not None:
            origins[name] = "input" if record[name] == getattr(fields, name) else "ai"
    if record["description"]:
        if record["description"] == fields.description:
            origins["description"] = "input"
        elif book and book.description and record["description"] == book.description:
            # Open Library's text arrives already plain.
            origins["description"] = "openlibrary"
        elif source_description:
            # Rewritten, even if only cleaned: the reader sees it is the model's.
            origins["description"] = "ai"
        else:
            # No source had one: written from the Open Library record alone.
            origins["description"] = "ai_written"
    return {**record, "origins": origins}


def _origin(value, original, looked_up) -> str:
    if value == original:
        return "input"
    if looked_up and value == looked_up:
        return "openlibrary"
    return "ai"


def _prompt(fields: BookFields, book: OpenLibraryBook | None) -> str:
    own = fields.model_dump(exclude_none=True)
    looked_up = None
    if book is not None:
        looked_up = {
            "title": book.title,
            "authors": book.authors,
            "description": (book.description or "")[:_OPENLIBRARY_DESCRIPTION_CAP] or None,
            "subjects": book.subjects,
        }
    return json.dumps({"book": own, "open_library": looked_up}, ensure_ascii=False)


def _stored_found(row: BookDetails) -> Found | None:
    """The find result kept with a record, so serving it needs no lookup."""
    stored = row.record.get("found")
    if not isinstance(stored, dict):
        return None
    try:
        book = OpenLibraryBook(**stored["book"]) if stored.get("book") else None
    except TypeError:
        return None
    return Found(book=book, language=stored.get("language"))


async def _point(session: AsyncSession, *, user_id: str, identity: str, key: str) -> None:
    """Remember ``key`` as the record this reader was given for the book.
    Not committed here: it goes with the caller's next commit."""
    values = {"user_id": user_id, "identity_hash": _identity_hash(identity), "key": key}
    stmt = pg_insert(BookDetailsReader).values(**values)
    await session.execute(
        stmt.on_conflict_do_update(
            index_elements=["user_id", "identity_hash"],
            set_={"key": stmt.excluded.key, "updated_at": func.now()},
        )
    )


async def _pointed(session: AsyncSession, *, user_id: str, identity: str) -> BookDetails | None:
    """The record this reader was last given for the book, if it still
    exists and was made by the current prompt."""
    pointer = await session.get(BookDetailsReader, (user_id, _identity_hash(identity)))
    if pointer is None:
        return None
    row = await session.get(BookDetails, pointer.key)
    if row is None or row.prompt_version != DETAILS_PROMPT_VERSION:
        return None
    return row


def _identity_hash(identity: str) -> str:
    return hashlib.sha256(identity.encode()).hexdigest()


async def tidy(
    orch: InsightOrchestrator,
    session: AsyncSession,
    *,
    key: str,
    identity: str,
    fields: BookFields,
    found: Found,
    cover: str | None,
    user_id: str,
    tenant_id: str,
    is_regen: bool,
) -> BookDetails:
    """Run the tidy call and store its guarded record under ``key``,
    replacing any earlier one. Quota and provider errors propagate.

    A reader's ``/tidy`` replaces the record every reader with the same
    input is served. That is wanted: the record depends only on that input
    and Open Library, so a new tidy can differ only in the model's wording
    and design, and it counts against that reader's regenerations."""
    out = await orch.generate_structured(
        session,
        kind="details",
        prompt_version=DETAILS_PROMPT_VERSION,
        system=SYSTEM_PROMPT,
        user=_prompt(fields, found.book),
        schema=TidyOutput,
        user_id=user_id,
        tenant_id=tenant_id,
        is_regen=is_regen,
    )
    record = guard(fields, found.book, out)
    renamed = record["title"] != fields.title or record["authors"] != fields.authors
    if found.book is None and renamed:
        # A file-name title hid the book from the first look; the tidied one may not.
        retry = fields.model_copy(update={"title": record["title"], "authors": record["authors"]})
        found = await find(orch, session, retry, cover=cover)
    record["found"] = {
        "book": asdict(found.book) if found.book else None,
        "language": found.language,
    }
    values = {
        "identity": identity,
        "record": record,
        # Colours the model wrote badly are left for the renderer, which
        # falls back to a palette and fixes the contrast.
        "design": out.design.model_dump(),
        "model_id": orch.model_id,
        "prompt_version": DETAILS_PROMPT_VERSION,
    }
    stmt = pg_insert(BookDetails).values(key=key, **values)
    stmt = stmt.on_conflict_do_update(
        index_elements=["key"], set_={**values, "generated_at": stmt.excluded.generated_at}
    )
    await session.execute(stmt)
    await _point(session, user_id=user_id, identity=identity, key=key)
    await session.commit()
    row = await session.get(BookDetails, key, populate_existing=True)
    assert row is not None
    return row


async def _log_hit(
    orch: InsightOrchestrator, session: AsyncSession, *, user_id: str, tenant_id: str
) -> None:
    await orch._log_generation(
        session,
        kind="details",
        book_insight_id=None,
        subject=user_id,
        tenant_id=tenant_id,
        status="hit",
        latency_ms=0,
        prompt_version=DETAILS_PROMPT_VERSION,
    )
    await session.commit()


class RecentFailures:
    """Record keys whose tidy failed lately, kept in this process for
    ``ttl_s`` and at most ``size`` of them. A lookup of such a book answers
    "failed" without paying for the same failure again; an explicit tidy
    still tries."""

    def __init__(
        self, *, ttl_s: float = 900, size: int = 4096, clock: Callable[[], float] = time.monotonic
    ) -> None:
        self._until: OrderedDict[str, float] = OrderedDict()
        self._ttl_s = ttl_s
        self._size = size
        self._clock = clock

    def recent(self, key: str) -> bool:
        until = self._until.get(key)
        if until is None:
            return False
        if until <= self._clock():
            del self._until[key]
            return False
        return True

    def add(self, key: str) -> None:
        self._until.pop(key, None)
        self._until[key] = self._clock() + self._ttl_s
        while len(self._until) > self._size:
            self._until.popitem(last=False)

    def discard(self, key: str) -> None:
        self._until.pop(key, None)


async def lookup(
    orch: InsightOrchestrator,
    session: AsyncSession,
    body: DetailsLookupBody,
    *,
    identity: str,
    user_id: str,
    tenant_id: str,
    force: bool,
    failures: RecentFailures | None = None,
) -> DetailsResponse:
    """Serve the stored record, or find, tidy when due (always when
    ``force``) and assemble the answer.

    A stored record is served with the find result kept beside it, so it
    costs no Open Library request either. The record this reader was last
    given for the book comes first, even when the fields differ: another
    device of theirs may send other fields for the same book, and should not
    pay for a second tidy. The pointer is the reader's own, so it can only
    choose among records for their own lookups, never what anyone else is
    served. Without ``force`` a tidy that
    cannot run (allowance spent, provider down) degrades to the find result
    with ``tidy`` saying why. With it, those errors propagate so the reader
    sees them.
    """
    fields = body.book
    key = details_key(identity, fields)
    cover = kept_cover(body)
    if not force:
        row = await _pointed(session, user_id=user_id, identity=identity)
        if row is None:
            row = await session.get(BookDetails, key)
        if row is not None:
            return await _serve(orch, session, body, row, user_id, tenant_id, identity)

    found = await find(orch, session, fields, cover=cover)
    if not force:
        # A drawn cover the reader asked for needs the tidy's design.
        drawn = body.cover_choice in ("draw", "paint")
        if not drawn and not needs_tidy(fields, found, cover=cover):
            return assemble(body, key, None, found, tidy="not_needed", cached=False)
        if failures is not None and failures.recent(key):
            return assemble(body, key, None, found, tidy="failed", cached=False)

    async with await orch.lock_for(f"details:{key}"):
        existing = await session.get(BookDetails, key)
        if existing is not None and not force:
            # Another request tidied it while this one waited.
            return await _serve(orch, session, body, existing, user_id, tenant_id, identity, found)
        try:
            row = await tidy(
                orch,
                session,
                key=key,
                identity=identity,
                fields=fields,
                found=found,
                cover=cover,
                user_id=user_id,
                tenant_id=tenant_id,
                is_regen=existing is not None,
            )
        except Exception as e:
            # The reader did not ask for this tidy, so the lookup still
            # answers with what the find gave. The hosted monthly allowance
            # answers 429 like the daily one.
            if force:
                raise
            spent = isinstance(e, QuotaExceeded) or (
                isinstance(e, HTTPException) and e.status_code == 429
            )
            if not spent:
                if not isinstance(e, ProviderError):
                    logger.warning("details.tidy.failed err=%s", type(e).__name__)
                if failures is not None:
                    failures.add(key)
            await session.rollback()
            status: TidyStatus = "quota_exceeded" if spent else "failed"
            return assemble(body, key, None, found, tidy=status, cached=False)
    if failures is not None:
        failures.discard(key)
    return assemble(body, key, row, _stored_found(row) or found, tidy="done", cached=False)


async def _serve(
    orch: InsightOrchestrator,
    session: AsyncSession,
    body: DetailsLookupBody,
    row: BookDetails,
    user_id: str,
    tenant_id: str,
    identity: str,
    found: Found | None = None,
) -> DetailsResponse:
    """Answer with a stored record, free, and point the reader at it."""
    await _point(session, user_id=user_id, identity=identity, key=row.key)
    await _log_hit(orch, session, user_id=user_id, tenant_id=tenant_id)
    cover = kept_cover(body)
    found = _stored_found(row) or found or await find(orch, session, body.book, cover=cover)
    if body.cover_choice == "find" and found.book is None:
        # The record was made while the book's own cover stood, so Open
        # Library was not asked: ask now, still without a model call.
        again = await find(orch, session, body.book, cover=None)
        if again.book is not None:
            found = again
    return assemble(body, row.key, row, found, tidy="done", cached=True)


def assemble(
    body: DetailsLookupBody,
    key: str,
    row: BookDetails | None,
    found: Found,
    *,
    tidy: TidyStatus,
    cached: bool,
) -> DetailsResponse:
    """Merge the client's fields, the find and the tidy, in that order of
    trust for facts: the client's own values always win. A stored or found
    value that fails the field caps is left out rather than failing the
    answer. A record that leaves the title, authors, series and description
    as the client sent them answers ``tidy="not_needed"``: there is nothing
    for the reader to see."""
    book = found.book
    record = row.record if row is not None else {}
    values = body.book.model_dump()
    sources: dict[str, str] = {}
    for name, origin in (record.get("origins") or {}).items():
        if name in values and _fits(body.book, name, record.get(name)):
            values[name] = record.get(name)
            sources[name] = origin
    filled = {
        "description": book.description[:_OPENLIBRARY_DESCRIPTION_CAP]
        if book and book.description
        else None,
        "subjects": [s for s in book.subjects if len(s) <= 80][:64] if book else None,
        "year": book.first_publish_year if book else None,
        "language": found.language,
    }
    for name, value in filled.items():
        if values[name] in (None, []) and value and _fits(body.book, name, value):
            values[name] = value
            sources[name] = "openlibrary"
    origins = {
        name: (body.source if sources.get(name, "input") == "input" else sources[name])
        for name, value in values.items()
        if value not in (None, [])
    }

    if tidy == "done" and all(values[name] == getattr(body.book, name) for name in _TIDIED):
        tidy = "not_needed"

    # The reader's choice first; what it names may not exist (no tidy ran,
    # Open Library has no cover), and then the next one in line is given.
    choice = body.cover_choice
    if kept_cover(body) is not None:
        cover = DetailsCover(source=body.cover)
    elif choice in ("keep", "find") and book and book.cover_url:
        cover = DetailsCover(source="openlibrary", url=book.cover_url)
    elif row is not None:
        cover = DetailsCover(
            source="generated",
            url=f"/details/v1/covers/{key}?v={cover_etag(row)}",
            generated=True,
        )
    else:
        cover = DetailsCover(source=body.cover)
    if cover.source in ("openlibrary", "generated"):
        origins["cover"] = "openlibrary" if cover.source == "openlibrary" else "ai"
    elif cover.source is not None:
        origins["cover"] = cover.source

    return DetailsResponse(
        key=key,
        book=BookFields.model_validate(values),
        origins=origins,
        cover=cover,
        tidy=tidy,
        cached=cached,
        openlibrary_url=book.url if book else None,
    )
