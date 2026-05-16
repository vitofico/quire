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
