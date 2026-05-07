from sqlalchemy import select

from opds_sync.db.models import Document


async def test_schema_round_trip(session) -> None:
    doc = Document(user_id="alice", metadata_id="abc123", content_hash="hash1")
    session.add(doc)
    await session.commit()
    rows = (await session.execute(select(Document))).scalars().all()
    assert len(rows) == 1
    assert rows[0].metadata_id == "abc123"
