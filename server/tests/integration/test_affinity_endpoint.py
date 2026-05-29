"""End-to-end integration tests for `POST /library/v1/affinity` (task A6).

The endpoint derives a deterministic reader-affinity score from the user's
library: it joins `LibraryItem -> Document (user_id+content_hash) -> Progress`
(both outer joins, `deleted_at IS NULL`), maps terminal state to a reading
status, does owned-detection by ISBN-13 match, and calls the pure
`score_affinity` scorer.

These tests authenticate as user ``"alice"`` via the default stubbed Basic
auth installed by ``client_factory`` (the fake maps the Basic username to the
``user_id``), and seed library state directly through the ``session`` fixture.

Spec reference:
`docs/superpowers/specs/2026-05-29-book-scan-design.md`.
"""

from __future__ import annotations

import base64
from datetime import UTC, datetime

import pytest
from sqlalchemy.ext.asyncio import AsyncSession

from quire_server.db.models import Document, LibraryItem, Progress

pytestmark = pytest.mark.requires_progress

# A valid ISBN-13 for "The Hobbit" used across the cold-start / owned tests.
HOBBIT_ISBN13 = "9780261103573"


def _basic_header(user: str, password: str = "p") -> dict[str, str]:
    token = base64.b64encode(f"{user}:{password}".encode()).decode("ascii")
    return {"Authorization": f"Basic {token}"}


async def _seed_book(
    session: AsyncSession,
    *,
    user_id: str,
    content_hash: str,
    title: str,
    authors: list[str] | None = None,
    subjects: list[str] | None = None,
    series_name: str | None = None,
    language: str | None = None,
    isbn: str | None = None,
    state: str = "unread",
) -> None:
    """Insert one alive library book for ``user_id`` plus its progress.

    Creates a ``LibraryItem`` (alive), a matching ``Document`` (same
    ``user_id`` + ``content_hash`` — the bridge the endpoint joins on), and,
    unless ``state == "unread"``, a ``Progress`` row keyed by the document's
    pk. The endpoint maps progress to a reading status:

    * ``finished``   — ``finished_at`` set (``abandoned_at`` must be NULL per
      the ``ck_progress_abandoned_xor_finished`` constraint).
    * ``abandoned``  — ``abandoned_at`` set, ``finished_at`` NULL.
    * ``in_progress``— neither terminal timestamp, ``percent > 0``.
    * ``unread``     — no progress row at all.
    """
    now = datetime.now(UTC)
    li = LibraryItem(
        user_id=user_id,
        content_hash=content_hash,
        title=title,
        authors=list(authors or []),
        subjects=list(subjects or []),
        series_name=series_name,
        language=language,
        isbn=isbn,
        created_at=now,
        updated_at=now,
        deleted_at=None,
    )
    session.add(li)

    doc = Document(user_id=user_id, content_hash=content_hash)
    session.add(doc)
    await session.flush()  # assign doc.pk

    if state != "unread":
        prog = Progress(
            document_pk=doc.pk,
            locator="epubcfi(/6/2)",
            percent=1.0 if state == "finished" else 0.5,
            finished_at=now if state == "finished" else None,
            abandoned_at=now if state == "abandoned" else None,
            client_updated_at=now,
        )
        session.add(prog)

    await session.commit()


# ---------------------------------------------------------------------------
# Cold-start
# ---------------------------------------------------------------------------


async def test_affinity_unknown_when_cold(client_factory, session):
    """A fresh user (empty library) cannot be profiled → band 'unknown'."""
    async with client_factory(progress_enabled=True) as client:
        r = await client.post(
            "/library/v1/affinity",
            headers=_basic_header("alice"),
            json={
                "identity": {"isbn": HOBBIT_ISBN13},
                "bundle": {
                    "title": "The Hobbit",
                    "author": "J.R.R. Tolkien",
                    "isbn": HOBBIT_ISBN13,
                    "subjects": ["fantasy"],
                },
            },
        )
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["band"] == "unknown"
    assert body["score"] is None
    assert body["affinity_version"] >= 1
    kinds = {reason["kind"] for reason in body["reasons"]}
    assert "coldstart" in kinds, body["reasons"]


# ---------------------------------------------------------------------------
# Owned detection
# ---------------------------------------------------------------------------


async def test_affinity_owned_detection(client_factory, session):
    """A scanned ISBN already in the library is flagged owned, with status."""
    await _seed_book(
        session,
        user_id="alice",
        content_hash="ch-hobbit",
        title="The Hobbit",
        authors=["J.R.R. Tolkien"],
        subjects=["fantasy"],
        isbn=HOBBIT_ISBN13,
        state="finished",
    )

    async with client_factory(progress_enabled=True) as client:
        r = await client.post(
            "/library/v1/affinity",
            headers=_basic_header("alice"),
            json={
                "identity": {"isbn": HOBBIT_ISBN13},
                "bundle": {
                    "title": "The Hobbit",
                    "author": "J.R.R. Tolkien",
                    "isbn": HOBBIT_ISBN13,
                    "subjects": ["fantasy"],
                },
            },
        )
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["owned"] is not None, body
    assert body["owned"]["in_library"] is True
    assert body["owned"]["reading_status"] == "finished"


# ---------------------------------------------------------------------------
# Strong match
# ---------------------------------------------------------------------------


async def test_affinity_strong_match(client_factory, session):
    """4 finished books by one author/theme → high score + positive author."""
    for i in range(4):
        await _seed_book(
            session,
            user_id="alice",
            content_hash=f"ch-jane-{i}",
            title=f"Jane Mystery {i}",
            authors=["Jane Doe"],
            subjects=["mystery"],
            state="finished",
        )

    async with client_factory(progress_enabled=True) as client:
        r = await client.post(
            "/library/v1/affinity",
            headers=_basic_header("alice"),
            json={
                "identity": {"content_hash": "ch-jane-new"},
                "bundle": {
                    "title": "Jane's Newest Mystery",
                    "author": "Jane Doe",
                    "subjects": ["mystery"],
                },
            },
        )
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["score"] is not None, body
    assert body["score"] >= 60, body
    author_reasons = [
        reason
        for reason in body["reasons"]
        if reason["kind"] == "author" and reason["polarity"] == "positive"
    ]
    assert author_reasons, body["reasons"]


# ---------------------------------------------------------------------------
# Mode gating
# ---------------------------------------------------------------------------


async def test_affinity_absent_when_progress_disabled(client_factory, session):
    """With progress disabled the library router isn't mounted → 404."""
    async with client_factory(progress_enabled=False) as client:
        r = await client.post(
            "/library/v1/affinity",
            headers=_basic_header("alice"),
            json={
                "identity": {"isbn": HOBBIT_ISBN13},
                "bundle": {"title": "The Hobbit", "isbn": HOBBIT_ISBN13},
            },
        )
    assert r.status_code == 404, r.text
