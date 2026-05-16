import pytest
from sqlalchemy import inspect, select

from opds_sync.db.models import Document


async def test_schema_round_trip(session) -> None:
    doc = Document(user_id="alice", metadata_id="abc123", content_hash="hash1")
    session.add(doc)
    await session.commit()
    rows = (await session.execute(select(Document))).scalars().all()
    assert len(rows) == 1
    assert rows[0].metadata_id == "abc123"


@pytest.mark.requires_ai
async def test_ai_generation_log_table_exists(engine) -> None:
    """ai_001 migration creates the table with the right columns + indexes + FK."""

    def _introspect(sync_conn) -> dict:
        insp = inspect(sync_conn)
        cols = {c["name"]: c for c in insp.get_columns("ai_generation_log")}
        idx_names = {i["name"] for i in insp.get_indexes("ai_generation_log")}
        fks = insp.get_foreign_keys("ai_generation_log")
        return {"cols": cols, "idx": idx_names, "fks": fks}

    async with engine.connect() as conn:
        info = await conn.run_sync(_introspect)

    expected_cols = {
        "id",
        "book_insight_id",
        "tenant_id",
        "subject",
        "request_id",
        "model_id",
        "prompt_version",
        "latency_ms",
        "status",
        "error_class",
        "created_at",
    }
    assert expected_cols.issubset(info["cols"].keys())
    assert "ix_ai_generation_log_tenant_created" in info["idx"]
    assert "ix_ai_generation_log_book_insight" in info["idx"]
    fk = next((f for f in info["fks"] if f["referred_table"] == "book_insights"), None)
    assert fk is not None, "expected FK to book_insights"
    assert fk["constrained_columns"] == ["book_insight_id"]
    assert fk["options"].get("ondelete", "").upper() == "CASCADE"


@pytest.mark.requires_ai
async def test_book_insights_has_language_column(engine) -> None:
    """ai_002 migration adds `language` to book_insights, NOT NULL, default 'auto'."""

    def _introspect(sync_conn) -> dict:
        insp = inspect(sync_conn)
        cols = {c["name"]: c for c in insp.get_columns("book_insights")}
        return {"cols": cols}

    async with engine.connect() as conn:
        info = await conn.run_sync(_introspect)

    assert "language" in info["cols"]
    lang = info["cols"]["language"]
    assert lang["nullable"] is False
    # Postgres reports server defaults as strings like "'auto'::character varying"
    assert "auto" in str(lang.get("default", ""))


@pytest.mark.requires_ai
async def test_book_insights_unique_indexes_include_language(engine) -> None:
    """ai_002 drops the tone-keyed unique indexes and recreates them with `language` appended."""

    def _introspect(sync_conn) -> dict:
        insp = inspect(sync_conn)
        idx = {i["name"]: i for i in insp.get_indexes("book_insights")}
        return {"idx": idx}

    async with engine.connect() as conn:
        info = await conn.run_sync(_introspect)

    expected_names = {
        "uq_book_insights_content_hash_model_prompt_tone_language",
        "uq_book_insights_metadata_id_model_prompt_tone_language",
    }
    assert expected_names.issubset(info["idx"].keys()), info["idx"].keys()
    # Confirm `language` is in the column list of both new indexes.
    for name in expected_names:
        cols = info["idx"][name]["column_names"]
        assert "language" in cols, (name, cols)
        assert "tone" in cols, (name, cols)
    # And the old tone-only indexes are gone.
    assert "uq_book_insights_content_hash_model_prompt_tone" not in info["idx"]
    assert "uq_book_insights_metadata_id_model_prompt_tone" not in info["idx"]


@pytest.mark.requires_progress
async def test_library_items_table_exists(engine) -> None:
    """progress_001 creates library_items with the right columns + partial indexes."""

    def _introspect(sync_conn) -> dict:
        insp = inspect(sync_conn)
        cols = {c["name"]: c for c in insp.get_columns("library_items")}
        idx = {i["name"]: i for i in insp.get_indexes("library_items")}
        return {"cols": cols, "idx": idx}

    async with engine.connect() as conn:
        info = await conn.run_sync(_introspect)

    expected_cols = {
        "pk",
        "user_id",
        "metadata_id",
        "content_hash",
        "title",
        "authors",
        "series_name",
        "series_index",
        "isbn",
        "language",
        "subjects",
        "opds_href",
        "created_at",
        "updated_at",
        "deleted_at",
    }
    assert expected_cols.issubset(info["cols"].keys()), info["cols"].keys()

    expected_indexes = {
        "uq_library_items_user_content",
        "uq_library_items_user_metadata",
        "ix_library_items_user_series_alive",
        "ix_library_items_user_updated",
    }
    assert expected_indexes.issubset(info["idx"].keys()), info["idx"].keys()

    # Hard unique on (user_id, content_hash).
    assert info["idx"]["uq_library_items_user_content"]["unique"] is True
    assert info["idx"]["uq_library_items_user_content"]["column_names"] == [
        "user_id",
        "content_hash",
    ]

    # Partial unique on (user_id, metadata_id) WHERE metadata_id IS NOT NULL.
    md = info["idx"]["uq_library_items_user_metadata"]
    assert md["unique"] is True
    where_md = (md.get("dialect_options") or {}).get("postgresql_where")
    assert where_md is not None and "metadata_id" in str(where_md).lower()

    # Partial alive series index.
    sa_idx = info["idx"]["ix_library_items_user_series_alive"]
    where_alive = (sa_idx.get("dialect_options") or {}).get("postgresql_where")
    assert where_alive is not None and "deleted_at" in str(where_alive).lower()


@pytest.mark.requires_ai
async def test_ai_generation_log_round_trip(session) -> None:
    """ORM model writes and reads ai_generation_log rows."""
    from opds_sync.db.models import AIGenerationLog, BookInsight

    insight = BookInsight(
        metadata_id=None,
        content_hash="ch-rt-log",
        model_id="m1",
        prompt_version="p1",
        tone="neutral",
        sources_used=[],
        payload={"schema_version": 2, "intro": "x", "confidence": "low"},
        sources=[],
        generated_by="legacy-write",
    )
    session.add(insight)
    await session.flush()

    log = AIGenerationLog(
        book_insight_id=insight.id,
        subject="alice",
        model_id="m1",
        prompt_version="p1",
        status="miss",
        latency_ms=123,
    )
    session.add(log)
    await session.commit()
    await session.refresh(log)

    assert log.id is not None
    assert log.tenant_id == "local"  # server default
    assert log.created_at is not None
    assert log.request_id is None
