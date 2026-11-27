"""Assertions for integration tests running on a branch-limited deployment."""

from alembic.config import Config
from alembic.script import ScriptDirectory
from sqlalchemy import text
from sqlalchemy.ext.asyncio import create_async_engine

_BRANCH_TABLES = {
    "ai": {"ai_generation_log", "book_themes"},
    "progress": {
        "library_items",
        "highlights",
        "library_sync_receipts",
        "reading_activity",
        "reading_activity_resets",
    },
}


async def assert_partial_schema(url: str, *, enabled: str, disabled: str) -> list[str]:
    engine = create_async_engine(url)
    try:
        async with engine.connect() as connection:
            tables = set(
                await connection.scalars(
                    text("SELECT tablename FROM pg_tables WHERE schemaname = current_schema()")
                )
            )
            applied = sorted(
                await connection.scalars(text("SELECT version_num FROM alembic_version"))
            )
    finally:
        await engine.dispose()

    unexpected = _BRANCH_TABLES[disabled] & tables
    assert not unexpected, f"disabled {disabled} branch has tables: {sorted(unexpected)}"
    assert _BRANCH_TABLES[enabled] <= tables

    script = ScriptDirectory.from_config(Config("alembic.ini"))
    expected = sorted(
        script.get_revision(f"{label}@head").revision
        for label in ("auth", "catalogs", "convert", enabled)
    )
    assert applied == expected
    return expected
