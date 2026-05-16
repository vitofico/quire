"""Unit-test fixtures.

The `session` fixture (in tests/conftest.py) rolls back uncommitted changes after
each test, but `_write_cache` calls `session.commit()`, so committed rows survive
across tests. The autouse fixture below truncates the cache table before each
test that requests a session, preventing cross-test contamination.
"""

import pytest
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncEngine


@pytest.fixture(autouse=True)
async def _truncate_external_source_cache(request, engine: AsyncEngine):
    """Wipe committed tables before every unit test that uses a DB session.

    The engine dependency means all unit tests share a single Postgres container
    (session-scoped), but the actual TRUNCATE is skipped unless the test requests
    the `session` fixture, keeping pure-unit tests fast.
    """
    if "session" not in request.fixturenames:
        return
    async with engine.begin() as conn:
        # CASCADE because ai_generation_log has an FK on book_insights.id;
        # truncating book_insights without CASCADE raises in PostgreSQL.
        await conn.execute(
            text(
                "TRUNCATE TABLE ai_generation_log, external_source_cache, "
                "book_insights, ai_usage_daily CASCADE"
            )
        )
