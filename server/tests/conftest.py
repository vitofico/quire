import base64
from collections.abc import AsyncIterator, Iterator

import httpx
import pytest
from alembic import command
from alembic.config import Config as AlembicConfig
from sqlalchemy.ext.asyncio import (
    AsyncEngine,
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)
from testcontainers.postgres import PostgresContainer


def pytest_collection_modifyitems(config, items):
    """Ordering + mode-marker skipping.

    Ordering: ensure test_schema runs before test_progress to avoid
    committed-row cross-pollution.

    Mode markers: tests marked `requires_progress` or `requires_ai` are
    skipped when the corresponding env flag is false (so the CI mode matrix
    can run the same suite under each mode without spurious failures from
    routers that aren't mounted).
    """
    import os

    def _flag(name: str) -> bool:
        return os.environ.get(name, "true").strip().lower() in {"1", "true", "yes", "on"}

    progress_on = _flag("OPDS_SYNC_PROGRESS_ENABLED")
    ai_on = _flag("OPDS_SYNC_AI_ENABLED")

    skip_progress = pytest.mark.skip(reason="OPDS_SYNC_PROGRESS_ENABLED=false")
    skip_ai = pytest.mark.skip(reason="OPDS_SYNC_AI_ENABLED=false")

    for item in items:
        if "requires_progress" in item.keywords and not progress_on:
            item.add_marker(skip_progress)
        if "requires_ai" in item.keywords and not ai_on:
            item.add_marker(skip_ai)

    def _key(item):
        path = item.nodeid
        if "test_schema" in path:
            return 0
        if "test_progress" in path:
            return 1
        return 2

    items.sort(key=_key)


@pytest.fixture(scope="session")
def postgres_url() -> Iterator[str]:
    with PostgresContainer("postgres:16-alpine") as pg:
        sync_url = pg.get_connection_url()
        async_url = sync_url.replace("postgresql+psycopg2://", "postgresql+asyncpg://")
        yield async_url


@pytest.fixture(scope="session")
def alembic_upgrade(postgres_url: str) -> None:
    cfg = AlembicConfig("alembic.ini")
    cfg.set_main_option("sqlalchemy.url", postgres_url)
    command.upgrade(cfg, "head")


@pytest.fixture
async def engine(postgres_url: str, alembic_upgrade: None) -> AsyncIterator[AsyncEngine]:
    eng = create_async_engine(postgres_url, future=True)
    yield eng
    await eng.dispose()


@pytest.fixture
async def session(engine: AsyncEngine) -> AsyncIterator[AsyncSession]:
    factory = async_sessionmaker(engine, expire_on_commit=False, class_=AsyncSession)
    async with factory() as s:
        yield s
        await s.rollback()


# ---- CWA mock --------------------------------------------------------------


@pytest.fixture
def cwa_users() -> dict[str, str]:
    """Mutable per-test dict of valid CWA username → password."""
    return {"alice": "alicepass", "bob": "bobpass"}


@pytest.fixture
def cwa_transport(cwa_users: dict[str, str]) -> httpx.MockTransport:
    def handler(request: httpx.Request) -> httpx.Response:
        if not request.url.path.endswith("/opds"):
            return httpx.Response(404)
        auth = request.headers.get("authorization", "")
        if not auth.lower().startswith("basic "):
            return httpx.Response(401)
        try:
            decoded = base64.b64decode(auth[6:].strip()).decode("utf-8")
            user, pw = decoded.split(":", 1)
        except Exception:
            return httpx.Response(401)
        if cwa_users.get(user) == pw:
            return httpx.Response(200, text="<feed/>")
        return httpx.Response(401)

    return httpx.MockTransport(handler)


@pytest.fixture
def basic_header():
    def _make(user: str, pw: str) -> str:
        token = base64.b64encode(f"{user}:{pw}".encode()).decode("ascii")
        return f"Basic {token}"

    return _make


@pytest.fixture
def app_under_test(postgres_url, alembic_upgrade, monkeypatch, cwa_transport):
    """A FastAPI app wired to the test Postgres + a mock CWA transport."""
    monkeypatch.setenv("OPDS_SYNC_DATABASE_URL", postgres_url)
    monkeypatch.setenv("OPDS_SYNC_CWA_BASE_URL", "http://test-cwa")

    from opds_sync.config import get_settings

    get_settings.cache_clear()

    from opds_sync.core.auth import CalibreAuthValidator
    from opds_sync.main import create_app

    app = create_app()
    test_client = httpx.AsyncClient(
        transport=cwa_transport, base_url="http://test-cwa", timeout=3.0
    )
    app.state.httpx_client = test_client
    app.state.auth_validator = CalibreAuthValidator(
        client=test_client,
        cwa_base_url="http://test-cwa",
    )
    return app
