"""Isolate committed cache writes for unit tests using a database session."""

import pytest
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncEngine


@pytest.fixture
async def session(session, engine: AsyncEngine):
    """Use the parent session fixture only for tests that request database access."""
    async with engine.begin() as conn:
        # CASCADE because ai_generation_log has an FK on book_insights.id;
        # truncating book_insights without CASCADE raises in PostgreSQL.
        await conn.execute(
            text(
                "TRUNCATE TABLE ai_generation_log, external_source_cache, "
                "book_insights, ai_usage_daily, insight_identity_aliases CASCADE"
            )
        )
    return session
