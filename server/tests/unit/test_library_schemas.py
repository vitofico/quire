"""Unit tests for `LibraryStatsResponse` and friends (PR9)."""

from __future__ import annotations

from quire_server.api.library_schemas import (
    LIBRARY_STATS_THEMES_CAVEAT,
    LibraryStatsResponse,
    TopAuthor,
    TopTheme,
)


def test_library_stats_response_shape_and_defaults() -> None:
    resp = LibraryStatsResponse(
        total_books=3,
        finished_count=1,
        in_progress_count=1,
        abandoned_count=1,
        top_authors=[TopAuthor(name="Asimov", count=2)],
        top_themes=[TopTheme(theme="noir", count=1, note="v3+ insights only")],
        themes_caveat=LIBRARY_STATS_THEMES_CAVEAT,
    )
    dumped = resp.model_dump()
    assert dumped["total_books"] == 3
    assert dumped["finished_count"] == 1
    assert dumped["in_progress_count"] == 1
    assert dumped["abandoned_count"] == 1
    assert dumped["top_authors"] == [{"name": "Asimov", "count": 2}]
    assert dumped["top_themes"] == [{"theme": "noir", "count": 1, "note": "v3+ insights only"}]
    assert "may be missing" in dumped["themes_caveat"]


def test_library_stats_response_empty_lists_allowed() -> None:
    resp = LibraryStatsResponse(
        total_books=0,
        finished_count=0,
        in_progress_count=0,
        abandoned_count=0,
        top_authors=[],
        top_themes=[],
        themes_caveat="x",
    )
    assert resp.top_authors == []
    assert resp.top_themes == []


def test_library_stats_response_round_trip_preserves_abandoned_count() -> None:
    """PR-9: explicit round-trip on `abandoned_count` so a regression where
    it disappears from the wire breaks loudly.
    """
    resp = LibraryStatsResponse(
        total_books=10,
        finished_count=3,
        in_progress_count=2,
        abandoned_count=7,
        top_authors=[],
        top_themes=[],
        themes_caveat=LIBRARY_STATS_THEMES_CAVEAT,
    )
    raw = resp.model_dump_json()
    rebuilt = LibraryStatsResponse.model_validate_json(raw)
    assert rebuilt.abandoned_count == 7
