import pytest
from sqlalchemy import delete, inspect, select, text
from sqlalchemy.exc import IntegrityError

from quire_server.db.models import AIGenerationLog, BookInsight, Document, LibraryItem


async def rejects(session, statement, state):
    with pytest.raises(IntegrityError) as error:
        async with session.begin_nested():
            await session.execute(statement)
    assert error.value.orig.sqlstate == state


async def check_identity_version(session, table):
    # Read a server-assigned default and enforce the bound at the database.
    assert await session.scalar(text(f"SELECT identity_hash_version FROM {table} LIMIT 1")) == 1
    for value, state in (("0", "23514"), ("NULL", "23502")):
        await rejects(session, text(f"UPDATE {table} SET identity_hash_version={value}"), state)


async def test_schema_round_trip(session):
    doc = await session.scalar(
        text(
            "INSERT INTO documents (user_id, metadata_id, content_hash) "
            "VALUES ('alice', 'abc123', 'hash1') RETURNING pk"
        )
    )
    assert (await session.get(Document, doc)).metadata_id == "abc123"
    await check_identity_version(session, "documents")


@pytest.mark.requires_ai
async def test_insight_language_unique_keys_and_audit_cascade(session):
    insert = BookInsight.__table__.insert()
    values = dict(
        metadata_id="m",
        content_hash="h",
        model_id="model",
        prompt_version="p",
        tone="neutral",
        identity_hash_version=text("DEFAULT"),
        language=text("DEFAULT"),
        sources_used=[],
        payload={},
        sources=[],
        generated_by="alice",
    )
    row = (
        await session.execute(
            insert.values(**values).returning(BookInsight.id, BookInsight.language)
        )
    ).one()
    assert row.language == "auto"
    await session.execute(insert.values(values | {"language": "it"}))
    await session.execute(insert.values(values | {"tone": "warm"}))
    for changed in ({"content_hash": "other"}, {"metadata_id": None}):
        await rejects(session, insert.values(values | changed), "23505")
    await rejects(
        session, insert.values(values | {"content_hash": "other", "language": None}), "23502"
    )
    await check_identity_version(session, "book_insights")

    log = AIGenerationLog(
        kind="insight",
        book_insight_id=row.id,
        subject="alice",
        model_id="model",
        prompt_version="p",
        status="miss",
        latency_ms=123,
    )
    session.add(log)
    await session.flush()
    await session.refresh(log)
    assert log.tenant_id == "local" and log.created_at is not None and log.request_id is None
    log_id = log.id
    await session.execute(delete(BookInsight).where(BookInsight.id == row.id))
    assert (
        await session.scalar(select(AIGenerationLog.id).where(AIGenerationLog.id == log_id)) is None
    )


@pytest.mark.requires_progress
async def test_library_item_identity_and_live_series_index(session):
    insert = LibraryItem.__table__.insert()
    values = dict(
        user_id="alice",
        content_hash="library-hash",
        metadata_id="metadata",
        title="Book",
        identity_hash_version=text("DEFAULT"),
    )
    await session.execute(insert.values(**values))
    await session.execute(insert.values(values | {"user_id": "bob"}))
    for changed in ({"metadata_id": None}, {"content_hash": "other"}):
        await rejects(session, insert.values(values | changed), "23505")
    for hash_value in ("without-metadata-one", "without-metadata-two"):
        await session.execute(
            insert.values(values | {"content_hash": hash_value, "metadata_id": None})
        )
    await check_identity_version(session, "library_items")
    conn = await session.connection()
    indexes = await conn.run_sync(lambda c: inspect(c).get_indexes("library_items"))
    index = next(item for item in indexes if item["name"] == "ix_library_items_user_series_alive")
    assert "deleted_at is null" in str(index["dialect_options"]["postgresql_where"]).lower()
