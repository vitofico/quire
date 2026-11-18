import base64
from collections.abc import AsyncIterator, Iterator

import httpx
import pytest
from alembic.config import Config as AlembicConfig
from sqlalchemy.ext.asyncio import (
    AsyncEngine,
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)
from testcontainers.postgres import PostgresContainer

from quire_server.config import Settings

_CI_MODES = {
    "full": (True, True),
    "sync_only": (True, False),
    "ai_only": (False, True),
}


def pytest_addoption(parser):
    parser.addoption(
        "--ci-mode",
        choices=tuple(_CI_MODES),
        help="Select one server CI lane and migrate only its enabled branches.",
    )


def pytest_configure(config):
    config.addinivalue_line("markers", "ci_lane(mode): run this case in the named CI lane")


@pytest.fixture(autouse=True)
def _no_dotenv(monkeypatch):
    """Ignore a developer's server/.env: tests build their own environment."""
    monkeypatch.setitem(Settings.model_config, "env_file", None)


def pytest_collection_modifyitems(config, items):
    """Keep the ordinary local suite, or select one explicit CI lane."""
    settings = Settings(_env_file=None)
    progress_on, ai_on = settings.progress_enabled, settings.ai_enabled
    ci_mode = config.getoption("ci_mode")
    if ci_mode and (progress_on, ai_on) != _CI_MODES[ci_mode]:
        raise pytest.UsageError(
            f"--ci-mode={ci_mode} disagrees with QUIRE_SERVER_PROGRESS_ENABLED "
            "or QUIRE_SERVER_AI_ENABLED"
        )

    skip_progress = pytest.mark.skip(reason="QUIRE_SERVER_PROGRESS_ENABLED=false")
    skip_ai = pytest.mark.skip(reason="QUIRE_SERVER_AI_ENABLED=false")
    selected = []
    deselected = []
    for item in items:
        markers = list(item.iter_markers("ci_lane"))
        if markers and (
            len(markers) != 1
            or len(markers[0].args) != 1
            or markers[0].kwargs
            or not isinstance(markers[0].args[0], str)
            or markers[0].args[0] not in _CI_MODES
        ):
            raise pytest.UsageError(f"{item.nodeid}: expected one valid ci_lane marker")
        lane = markers[0].args[0] if markers else "full"
        requires_progress = "requires_progress" in item.keywords
        requires_ai = "requires_ai" in item.keywords
        if requires_progress and requires_ai and lane != "full":
            raise pytest.UsageError(f"{item.nodeid}: cross-branch tests need the full lane")
        if ci_mode and lane != ci_mode:
            deselected.append(item)
            continue
        needs_progress = requires_progress and not progress_on
        needs_ai = requires_ai and not ai_on
        if ci_mode and (needs_progress or needs_ai):
            raise pytest.UsageError(f"{item.nodeid}: requires a router disabled in {ci_mode}")
        if needs_progress:
            item.add_marker(skip_progress)
        if needs_ai:
            item.add_marker(skip_ai)
        selected.append(item)
    if deselected:
        config.hook.pytest_deselected(items=deselected)
        items[:] = selected

    # Run the schema checks first while the migrated database is pristine.
    def _key(item):
        path = item.nodeid
        if "test_schema" in path:
            return 0
        return 1

    items.sort(key=_key)


@pytest.fixture(scope="session")
def postgres_container() -> Iterator[PostgresContainer]:
    with PostgresContainer("postgres:16-alpine") as pg:
        yield pg


@pytest.fixture(scope="session")
def postgres_url(postgres_container: PostgresContainer) -> str:
    sync_url = postgres_container.get_connection_url()
    return sync_url.replace("postgresql+psycopg2://", "postgresql+asyncpg://")


def _migrate_test_database(url: str, *, progress_enabled: bool, ai_enabled: bool) -> None:
    cfg = AlembicConfig("alembic.ini")
    cfg.set_main_option("sqlalchemy.url", url)
    from scripts.migrate import run_migrations

    run_migrations(cfg, progress_enabled=progress_enabled, ai_enabled=ai_enabled)


@pytest.fixture(scope="session")
def alembic_upgrade(postgres_url: str, pytestconfig) -> None:
    # Ordinary local tests retain the full shared database. Each explicit CI
    # lane builds the same schema the production deploy migrator would build.
    progress_enabled, ai_enabled = _CI_MODES[pytestconfig.getoption("ci_mode") or "full"]
    _migrate_test_database(postgres_url, progress_enabled=progress_enabled, ai_enabled=ai_enabled)


@pytest.fixture(scope="module")
def partial_postgres_url(request) -> Iterator[str]:
    lane = request.node.get_closest_marker("ci_lane").args[0]
    progress_enabled, ai_enabled = _CI_MODES[lane]
    if request.config.getoption("ci_mode") == lane:
        request.getfixturevalue("alembic_upgrade")
        yield request.getfixturevalue("postgres_url")
        return

    # An ordinary local run collects all lanes. The partial smoke modules
    # still need real branch-limited schemas independent of the full fixture.
    with PostgresContainer("postgres:16-alpine") as pg:
        url = pg.get_connection_url().replace("postgresql+psycopg2://", "postgresql+asyncpg://")
        _migrate_test_database(url, progress_enabled=progress_enabled, ai_enabled=ai_enabled)
        yield url


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
    monkeypatch.setenv("QUIRE_SERVER_DATABASE_URL", postgres_url)
    monkeypatch.setenv("QUIRE_SERVER_CWA_BASE_URL", "http://test-cwa")

    from quire_server.config import get_settings

    get_settings.cache_clear()

    from quire_server.core.auth import CalibreAuthValidator
    from quire_server.core.auth_backend import CalibreWebBasicAuth
    from quire_server.main import create_app

    app = create_app()
    test_client = httpx.AsyncClient(
        transport=cwa_transport, base_url="http://test-cwa", timeout=3.0
    )
    app.state.httpx_client = test_client
    app.state.auth_validator = CalibreAuthValidator(
        client=test_client,
        cwa_base_url="http://test-cwa",
    )
    # Phase 0, task S-1: the primary auth dependency now resolves through
    # ``app.state.auth_backend``, which captured a reference to the
    # ORIGINAL validator at create_app() time. Rebuild the backend so it
    # wraps the test's CWA-mock validator instead.
    app.state.auth_backend = CalibreWebBasicAuth(app.state.auth_validator)
    return app
