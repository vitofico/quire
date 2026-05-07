# Phase 2 — Progress Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the `opds-sync` FastAPI server with Authentik OIDC+PKCE auth and a progress-only API; wire a WorkManager-based sync client into the Quire Android app; ship the Phase 2 gate (progress on device A appears on device B within one foreground sync).

**Architecture:** New Python service under `server/` (FastAPI + SQLAlchemy 2 async + Postgres 16) deployed via Kustomize manifests under `deploy/k8s/opds-sync/`. New Android module `:data:sync` driven by a `CoroutineWorker`, with auth flowing through additions to the existing `:auth` module (AppAuth + Keystore). Room schema bumps from v2 → v3 in a single migration that adds `localUpdatedAt`/`syncedAt` columns to `progress` and creates a `sync_state` high-water-mark table. Identity parity is enforced by reusing the existing Kotlin fixture file `core/identity/src/test/resources/identity/fixtures.json` — the Python test reads the same file via a symlink under `server/fixtures/`.

**Tech Stack:**
- **Server:** Python 3.12, FastAPI, SQLAlchemy 2.x async with `asyncpg`, Alembic, PyJWT, httpx, uvicorn, Postgres 16. Tests: pytest + pytest-asyncio + `testcontainers[postgres]`. Tooling: `uv` for deps; ruff for lint/format.
- **Deploy:** Kustomize, Traefik ingress, cert-manager, SOPS for the secret. Initial deploy via `kubectl apply -k`.
- **Android:** Kotlin 2.0 + Compose (existing). New: `net.openid:appauth:0.11.1`, `com.squareup.okhttp3:okhttp` (already present), `org.jetbrains.kotlinx:kotlinx-serialization-json` (already present), `androidx.work:work-runtime-ktx` (already in catalog, not yet wired).

**Spec:** `docs/superpowers/specs/2026-05-05-phase-2-progress-sync.md`

**Note on Room version:** The spec describes "v2: add columns; v3: add `sync_state`." The on-disk DB is already at version 2 (cover-path migration). This plan ships **a single migration v2 → v3** that does both column adds and the new table, to minimize migration churn.

---

## File structure

### New: `server/` (FastAPI service)

```
server/
├── pyproject.toml
├── uv.lock
├── ruff.toml
├── Dockerfile
├── alembic.ini
├── README.md
├── opds_sync/
│   ├── __init__.py
│   ├── main.py                       FastAPI app factory
│   ├── config.py                     Pydantic Settings (env-driven)
│   ├── api/
│   │   ├── __init__.py
│   │   ├── health.py                 /healthz, /readyz
│   │   └── progress.py               POST /progress, GET /progress
│   ├── core/
│   │   ├── __init__.py
│   │   ├── identity.py               normalize_metadata_id, content_hash
│   │   └── auth.py                   JWKS fetch + JWT validation, dep-injected user_id
│   └── db/
│       ├── __init__.py
│       ├── models.py                 SQLAlchemy models: Document, Progress
│       └── session.py                async engine + session factory
├── migrations/
│   ├── env.py
│   ├── script.py.mako
│   └── versions/
│       └── 0001_initial.py
├── fixtures/
│   └── identity/                     symlink → ../../core/identity/src/test/resources/identity
└── tests/
    ├── conftest.py                   shared fixtures (testcontainer postgres, JWT signer)
    ├── unit/
    │   ├── test_identity.py          parity test against shared JSON fixtures
    │   └── test_merge.py             LWW unit tests
    └── integration/
        ├── test_health.py
        └── test_progress.py
```

### New: `deploy/k8s/opds-sync/`

```
deploy/k8s/opds-sync/
├── kustomization.yaml
├── namespace.yaml
├── deployment.yaml
├── service.yaml
├── ingress.yaml
├── secret.example.yaml               unencrypted template; real secret SOPS-encrypted out-of-band
├── postgres-statefulset.yaml
├── postgres-pvc.yaml
├── postgres-service.yaml
├── network-policies.yaml
└── README.md                         apply instructions + Authentik prerequisite
```

### New: Android module `:data:sync`

```
data/sync/
├── build.gradle.kts
└── src/
    ├── main/
    │   └── java/io/theficos/ereader/data/sync/
    │       ├── ProgressDtos.kt              kotlinx.serialization wire types
    │       ├── SyncApi.kt                   request/response builders + endpoint paths
    │       ├── SyncClient.kt                OkHttp wrapper, raw HTTP
    │       ├── SyncOrchestrator.kt          push-then-pull pipeline
    │       ├── SyncResult.kt                outcome sealed class (Success/Unauthorized/NetworkFailure)
    │       └── SyncWorker.kt                CoroutineWorker, enqueue helpers
    └── test/
        └── java/io/theficos/ereader/data/sync/
            ├── ProgressDtosTest.kt          serialization round-trip
            ├── SyncClientTest.kt            MockWebServer
            └── SyncOrchestratorTest.kt      MockWebServer + in-memory Room
```

### Modified: `:auth` module (alongside existing `CalibreCredentialStore`)

```
auth/src/main/java/io/theficos/ereader/auth/
├── (existing) CalibreCredentials.kt, CalibreCredentialStore.kt
└── (new)
    ├── AuthentikConfig.kt
    ├── AuthState.kt
    ├── AuthTokenStore.kt              EncryptedSharedPreferences-backed
    ├── AuthentikAuthenticator.kt      AppAuth wrapper + PKCE flow
    └── SyncAuthInterceptor.kt         OkHttp Interceptor; attach Bearer; refresh on 401
```

### Modified: `:data:local`

```
data/local/src/main/java/io/theficos/ereader/data/local/
├── (modified) db/EReaderDatabase.kt          version 3, MIGRATION_2_3
├── (modified) db/ProgressEntity.kt           +localUpdatedAt, +syncedAt
├── (modified) db/ProgressDao.kt              +dirty(), +markSynced(...)
├── (modified) ProgressRepository.kt          bump localUpdatedAt on save
├── (new)      db/SyncStateEntity.kt
└── (new)      db/SyncStateDao.kt
```

### Modified: `:app`

```
app/src/main/java/io/theficos/ereader/
├── (modified) di/AppContainer.kt             wires AuthTokenStore, AuthentikAuthenticator, SyncClient, SyncOrchestrator
├── (modified) ui/settings/SettingsScreen.kt  adds "Sync" section
├── (modified) ui/settings/SettingsViewModel.kt
├── (modified) ui/library/LibraryScreen.kt    LaunchedEffect → enqueue sync (pull)
└── (modified) ui/reader/ReaderScreen.kt      onPause → enqueue sync (push)

app/src/main/AndroidManifest.xml              adds AppAuth RedirectUriReceiverActivity manifest entry
app/build.gradle.kts                          adds :data:sync; defines manifestPlaceholders["appAuthRedirectScheme"]
```

### Modified: top-level

```
settings.gradle.kts                            +":data:sync"
gradle/libs.versions.toml                      adds appauth, work, kotlinx-serialization-json (alias only)
.github/workflows/server-ci.yaml               new: lint + test + image push for server
```

---

## Task 1: version catalog + new `:data:sync` module declaration

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `settings.gradle.kts`
- Create: `data/sync/build.gradle.kts`
- Create: `data/sync/src/main/AndroidManifest.xml`

- [ ] **Step 1: Add appauth + work catalog entries**

Edit `gradle/libs.versions.toml`. In the `[versions]` block add (alongside the existing `work = "2.9.1"` line which is already present):

```
appauth = "0.11.1"
```

In the `[libraries]` block add:

```
appauth = { module = "net.openid:appauth", version.ref = "appauth" }
```

(`work-runtime-ktx`, `okhttp`, `okhttp-mockwebserver`, `kotlinx-serialization-json`, `room-*`, and `kotlinx-coroutines-test` are already in the catalog — no change.)

- [ ] **Step 2: Register `:data:sync` in `settings.gradle.kts`**

Append to the `include(...)` block so the file reads:

```kotlin
include(
    ":app",
    ":core:model",
    ":core:identity",
    ":data:local",
    ":data:opds",
    ":data:sync",
    ":reader",
    ":auth",
)
```

- [ ] **Step 3: Create `data/sync/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.theficos.ereader.data.sync"
    compileSdk = 34
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    api(project(":core:model"))
    api(project(":data:local"))
    implementation(project(":auth"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.work.runtime.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.room.testing)
}
```

- [ ] **Step 4: Create empty manifest**

`data/sync/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest />
```

- [ ] **Step 5: Compile-check the new module**

Run: `./scripts/dgradle :data:sync:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add gradle/libs.versions.toml settings.gradle.kts data/sync/build.gradle.kts data/sync/src/main/AndroidManifest.xml
git commit -m ":wrench: chore: scaffold :data:sync module + appauth catalog entry

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: Server scaffolding (`server/`)

**Files:**
- Create: `server/pyproject.toml`
- Create: `server/ruff.toml`
- Create: `server/Dockerfile`
- Create: `server/.dockerignore`
- Create: `server/README.md`
- Create: `server/opds_sync/__init__.py`
- Create: `server/opds_sync/config.py`
- Create: `server/opds_sync/main.py`
- Create: `server/tests/__init__.py`
- Create: `server/tests/conftest.py`

- [ ] **Step 1: Create `server/pyproject.toml`**

```toml
[project]
name = "opds-sync"
version = "0.1.0"
description = "Quire sync server: progress + (later) annotations API"
requires-python = ">=3.12"
dependencies = [
    "fastapi>=0.115",
    "uvicorn[standard]>=0.30",
    "sqlalchemy[asyncio]>=2.0",
    "asyncpg>=0.29",
    "alembic>=1.13",
    "pydantic>=2.7",
    "pydantic-settings>=2.4",
    "pyjwt[crypto]>=2.9",
    "httpx>=0.27",
    "python-json-logger>=2.0",
]

[project.optional-dependencies]
dev = [
    "pytest>=8.3",
    "pytest-asyncio>=0.24",
    "pytest-cov>=5.0",
    "testcontainers[postgres]>=4.7",
    "ruff>=0.6",
    "httpx>=0.27",
    "cryptography>=43",
]

[tool.pytest.ini_options]
asyncio_mode = "auto"
testpaths = ["tests"]
```

- [ ] **Step 2: Create `server/ruff.toml`**

```toml
target-version = "py312"
line-length = 100

[lint]
select = ["E", "F", "W", "I", "B", "UP", "ASYNC"]
```

- [ ] **Step 3: Create `server/Dockerfile`**

```dockerfile
FROM python:3.12-slim AS base

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
        build-essential libpq-dev \
 && rm -rf /var/lib/apt/lists/*

COPY pyproject.toml ./
RUN pip install --no-cache-dir uv && \
    uv pip install --system "."

COPY opds_sync ./opds_sync
COPY migrations ./migrations
COPY alembic.ini ./

EXPOSE 8000
CMD ["uvicorn", "opds_sync.main:app", "--host", "0.0.0.0", "--port", "8000"]
```

- [ ] **Step 4: Create `server/.dockerignore`**

```
__pycache__
*.pyc
.pytest_cache
.venv
tests
fixtures
.git
```

- [ ] **Step 5: Create `server/README.md`**

```markdown
# opds-sync

Quire sync server. FastAPI + Postgres. See
[`docs/superpowers/specs/2026-05-05-phase-2-progress-sync.md`](../docs/superpowers/specs/2026-05-05-phase-2-progress-sync.md).

## Local dev

```sh
cd server
uv venv && source .venv/bin/activate
uv pip install -e ".[dev]"
uv run pytest
uv run uvicorn opds_sync.main:app --reload
```

Tests require Docker (testcontainers spins up Postgres).
```

- [ ] **Step 6: Create `server/opds_sync/__init__.py`** (empty file).

- [ ] **Step 7: Create `server/opds_sync/config.py`**

```python
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="OPDS_SYNC_", env_file=".env", extra="ignore")

    database_url: str = "postgresql+asyncpg://postgres:postgres@localhost:5432/opds_sync"
    authentik_issuer: str = "https://auth.theficos.dedyn.io/application/o/quire/"
    authentik_audience: str = "quire"
    log_level: str = "INFO"


def get_settings() -> Settings:
    return Settings()
```

- [ ] **Step 8: Create `server/opds_sync/main.py`**

```python
import logging

from fastapi import FastAPI

from opds_sync.api import health, progress
from opds_sync.config import get_settings


def create_app() -> FastAPI:
    settings = get_settings()
    logging.basicConfig(level=settings.log_level)

    app = FastAPI(title="opds-sync", version="0.1.0")
    app.include_router(health.router, prefix="/sync/v1")
    app.include_router(progress.router, prefix="/sync/v1")
    return app


app = create_app()
```

- [ ] **Step 9: Create `server/tests/__init__.py`** (empty file).

- [ ] **Step 10: Create `server/tests/conftest.py`** (placeholder; real fixtures arrive in later tasks).

```python
import pytest


@pytest.fixture
def placeholder() -> bool:
    return True
```

- [ ] **Step 11: Initialize uv lockfile + verify install**

```sh
cd server
uv venv
source .venv/bin/activate
uv pip install -e ".[dev]"
```

Expected: install succeeds, `.venv` and `uv.lock` (or pip-resolved deps) created. Verify Python imports:

```sh
python -c "from opds_sync.main import app; print(app.title)"
```

Expected: prints `opds-sync`.

- [ ] **Step 12: Commit**

```bash
git add server/pyproject.toml server/ruff.toml server/Dockerfile server/.dockerignore server/README.md server/opds_sync/ server/tests/
git commit -m ":sparkles: feat(server): scaffold opds-sync FastAPI service

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: Identity parity test (Python ↔ Kotlin fixtures)

**Files:**
- Create symlink: `server/fixtures/identity` → `../../core/identity/src/test/resources/identity`
- Create: `server/opds_sync/core/__init__.py`
- Create: `server/opds_sync/core/identity.py`
- Create: `server/tests/unit/__init__.py`
- Create: `server/tests/unit/test_identity.py`

- [ ] **Step 1: Create the symlink**

```sh
mkdir -p server/fixtures
ln -s ../../core/identity/src/test/resources/identity server/fixtures/identity
```

Verify the file is reachable:

```sh
test -f server/fixtures/identity/fixtures.json && echo OK
```

Expected: `OK`.

- [ ] **Step 2: Create `server/opds_sync/core/__init__.py`** (empty file).

- [ ] **Step 3: Write the failing parity test at `server/tests/unit/__init__.py`** (empty) and `server/tests/unit/test_identity.py`**

```python
import json
from pathlib import Path

import pytest

from opds_sync.core.identity import normalize_metadata_id

FIXTURES = Path(__file__).resolve().parents[2] / "fixtures" / "identity" / "fixtures.json"


@pytest.fixture(scope="module")
def cases() -> list[dict]:
    with FIXTURES.open() as f:
        return json.load(f)["cases"]


def test_parity_with_kotlin_fixtures(cases: list[dict]) -> None:
    for case in cases:
        got = normalize_metadata_id(case["in"])
        expected = case["out"]
        assert got == expected, f"input={case['in']!r} expected={expected!r} got={got!r}"


def test_none_returns_none() -> None:
    assert normalize_metadata_id(None) is None
```

- [ ] **Step 4: Run the test to confirm it fails**

```sh
cd server && uv run pytest tests/unit/test_identity.py -v
```

Expected: failures — `ModuleNotFoundError: No module named 'opds_sync.core.identity'` (or similar).

- [ ] **Step 5: Implement `server/opds_sync/core/identity.py`** to match the Kotlin normalizer (`core/identity/src/main/java/io/theficos/ereader/core/identity/MetadataIdNormalizer.kt`):

```python
import re

_SCHEMES = ("isbn", "uuid", "calibre", "mobi-asin", "asin", "doi", "url")
# Order matters: longer/compound schemes (e.g. "mobi-asin") must precede their prefixes ("asin")
# since the regex breaks on first alternative match. Mirror the Kotlin ordering.
_SCHEME_PREFIX = re.compile(rf"^({'|'.join(_SCHEMES)})[:\s]+")
_WHITESPACE_AND_HYPHEN = re.compile(r"[\s-]")


def normalize_metadata_id(raw: str | None) -> str | None:
    if raw is None:
        return None
    s = raw.strip().lower()
    if not s:
        return None
    if s.startswith("urn:"):
        s = s[len("urn:"):]
    s = _SCHEME_PREFIX.sub("", s, count=1)
    s = _WHITESPACE_AND_HYPHEN.sub("", s)
    return s or None
```

- [ ] **Step 6: Run the test to confirm it passes**

```sh
cd server && uv run pytest tests/unit/test_identity.py -v
```

Expected: all assertions PASS.

- [ ] **Step 7: Sanity-run the existing Kotlin parity test**

```sh
./scripts/dgradle :core:identity:test
```

Expected: BUILD SUCCESSFUL — Kotlin reads the same fixtures and still passes.

- [ ] **Step 8: Commit**

```bash
git add server/fixtures/identity server/opds_sync/core/__init__.py server/opds_sync/core/identity.py server/tests/unit/__init__.py server/tests/unit/test_identity.py
git commit -m ":sparkles: feat(server): metadata-id normalizer with Kotlin parity tests

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: SQLAlchemy models + Alembic init + first migration

**Files:**
- Create: `server/alembic.ini`
- Create: `server/migrations/env.py`
- Create: `server/migrations/script.py.mako`
- Create: `server/migrations/versions/0001_initial.py`
- Create: `server/opds_sync/db/__init__.py`
- Create: `server/opds_sync/db/models.py`
- Create: `server/opds_sync/db/session.py`

- [ ] **Step 1: Write `server/opds_sync/db/__init__.py`** (empty).

- [ ] **Step 2: Write `server/opds_sync/db/models.py`**

```python
from datetime import datetime

from sqlalchemy import (
    BigInteger,
    CheckConstraint,
    DateTime,
    Float,
    ForeignKey,
    Index,
    String,
    UniqueConstraint,
    func,
)
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship


class Base(DeclarativeBase):
    pass


class Document(Base):
    __tablename__ = "documents"
    __table_args__ = (
        UniqueConstraint("user_id", "metadata_id", name="uq_documents_user_metadata"),
        UniqueConstraint("user_id", "content_hash", name="uq_documents_user_content_hash"),
        Index("ix_documents_user", "user_id"),
    )

    pk: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    user_id: Mapped[str] = mapped_column(String, nullable=False)
    metadata_id: Mapped[str | None] = mapped_column(String, nullable=True)
    content_hash: Mapped[str] = mapped_column(String, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now(), nullable=False)

    progress: Mapped["Progress | None"] = relationship(back_populates="document", uselist=False, cascade="all, delete-orphan")


class Progress(Base):
    __tablename__ = "progress"
    __table_args__ = (
        CheckConstraint("percent >= 0 AND percent <= 1", name="ck_progress_percent_range"),
        Index("ix_progress_document_client_updated_at", "document_pk", "client_updated_at"),
    )

    document_pk: Mapped[int] = mapped_column(BigInteger, ForeignKey("documents.pk", ondelete="CASCADE"), primary_key=True)
    locator: Mapped[str] = mapped_column(String, nullable=False)
    percent: Mapped[float] = mapped_column(Float, nullable=False)
    client_updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    received_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now(), nullable=False)

    document: Mapped[Document] = relationship(back_populates="progress")
```

- [ ] **Step 3: Write `server/opds_sync/db/session.py`**

```python
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from sqlalchemy.ext.asyncio import (
    AsyncEngine,
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)

from opds_sync.config import get_settings


def make_engine(database_url: str | None = None) -> AsyncEngine:
    url = database_url or get_settings().database_url
    return create_async_engine(url, pool_pre_ping=True, future=True)


def make_session_factory(engine: AsyncEngine) -> async_sessionmaker[AsyncSession]:
    return async_sessionmaker(engine, expire_on_commit=False, class_=AsyncSession)


_engine: AsyncEngine | None = None
_factory: async_sessionmaker[AsyncSession] | None = None


def _factories() -> async_sessionmaker[AsyncSession]:
    global _engine, _factory
    if _factory is None:
        _engine = make_engine()
        _factory = make_session_factory(_engine)
    return _factory


@asynccontextmanager
async def session_scope() -> AsyncIterator[AsyncSession]:
    factory = _factories()
    async with factory() as session:
        yield session


async def get_session() -> AsyncIterator[AsyncSession]:  # FastAPI dependency
    async with session_scope() as s:
        yield s
```

- [ ] **Step 4: Write `server/alembic.ini`**

```ini
[alembic]
script_location = migrations
sqlalchemy.url = postgresql+asyncpg://postgres:postgres@localhost:5432/opds_sync

[loggers]
keys = root,alembic

[handlers]
keys = console

[formatters]
keys = generic

[logger_root]
level = WARN
handlers = console

[logger_alembic]
level = INFO
handlers =
qualname = alembic

[handler_console]
class = StreamHandler
formatter = generic
args = (sys.stderr,)

[formatter_generic]
format = %(levelname)-5.5s [%(name)s] %(message)s
```

- [ ] **Step 5: Write `server/migrations/env.py`**

```python
import asyncio
from logging.config import fileConfig

from alembic import context
from sqlalchemy.ext.asyncio import async_engine_from_config

from opds_sync.config import get_settings
from opds_sync.db.models import Base

config = context.config
if config.config_file_name is not None:
    fileConfig(config.config_file_name)

target_metadata = Base.metadata


def _set_url() -> None:
    config.set_main_option("sqlalchemy.url", get_settings().database_url)


def run_migrations_offline() -> None:
    _set_url()
    context.configure(url=config.get_main_option("sqlalchemy.url"), target_metadata=target_metadata, literal_binds=True)
    with context.begin_transaction():
        context.run_migrations()


def do_run_migrations(connection):
    context.configure(connection=connection, target_metadata=target_metadata)
    with context.begin_transaction():
        context.run_migrations()


async def run_migrations_online() -> None:
    _set_url()
    connectable = async_engine_from_config(config.get_section(config.config_ini_section, {}), prefix="sqlalchemy.", future=True)
    async with connectable.connect() as connection:
        await connection.run_sync(do_run_migrations)
    await connectable.dispose()


if context.is_offline_mode():
    run_migrations_offline()
else:
    asyncio.run(run_migrations_online())
```

- [ ] **Step 6: Write `server/migrations/script.py.mako`** (standard Alembic template):

```mako
"""${message}

Revision ID: ${up_revision}
Revises: ${down_revision | comma,n}
Create Date: ${create_date}

"""
from alembic import op
import sqlalchemy as sa
${imports if imports else ""}

revision = ${repr(up_revision)}
down_revision = ${repr(down_revision)}
branch_labels = ${repr(branch_labels)}
depends_on = ${repr(depends_on)}


def upgrade() -> None:
    ${upgrades if upgrades else "pass"}


def downgrade() -> None:
    ${downgrades if downgrades else "pass"}
```

- [ ] **Step 7: Write the initial migration `server/migrations/versions/0001_initial.py`**

```python
"""initial schema: documents, progress

Revision ID: 0001
Revises:
Create Date: 2026-05-05 00:00:00.000000
"""
from alembic import op
import sqlalchemy as sa


revision = "0001"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "documents",
        sa.Column("pk", sa.BigInteger(), primary_key=True, autoincrement=True),
        sa.Column("user_id", sa.String(), nullable=False),
        sa.Column("metadata_id", sa.String(), nullable=True),
        sa.Column("content_hash", sa.String(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.UniqueConstraint("user_id", "metadata_id", name="uq_documents_user_metadata"),
        sa.UniqueConstraint("user_id", "content_hash", name="uq_documents_user_content_hash"),
    )
    op.create_index("ix_documents_user", "documents", ["user_id"])

    op.create_table(
        "progress",
        sa.Column("document_pk", sa.BigInteger(), sa.ForeignKey("documents.pk", ondelete="CASCADE"), primary_key=True),
        sa.Column("locator", sa.String(), nullable=False),
        sa.Column("percent", sa.Float(), nullable=False),
        sa.Column("client_updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("received_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.CheckConstraint("percent >= 0 AND percent <= 1", name="ck_progress_percent_range"),
    )
    op.create_index("ix_progress_document_client_updated_at", "progress", ["document_pk", "client_updated_at"])


def downgrade() -> None:
    op.drop_index("ix_progress_document_client_updated_at", table_name="progress")
    op.drop_table("progress")
    op.drop_index("ix_documents_user", table_name="documents")
    op.drop_table("documents")
```

- [ ] **Step 8: Update `server/tests/conftest.py` to spin up Postgres for integration tests**

Replace the placeholder with:

```python
import asyncio
from collections.abc import AsyncIterator, Iterator

import pytest
from alembic import command
from alembic.config import Config as AlembicConfig
from sqlalchemy.ext.asyncio import AsyncEngine, AsyncSession, async_sessionmaker, create_async_engine
from testcontainers.postgres import PostgresContainer


@pytest.fixture(scope="session")
def postgres_url() -> Iterator[str]:
    with PostgresContainer("postgres:16-alpine") as pg:
        sync_url = pg.get_connection_url()  # postgresql+psycopg2://...
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
```

- [ ] **Step 9: Create `server/tests/integration/__init__.py`** (empty) and a smoke test `server/tests/integration/test_schema.py`:

```python
from sqlalchemy import select

from opds_sync.db.models import Document


async def test_schema_round_trip(session) -> None:
    doc = Document(user_id="alice", metadata_id="abc123", content_hash="hash1")
    session.add(doc)
    await session.commit()
    rows = (await session.execute(select(Document))).scalars().all()
    assert len(rows) == 1
    assert rows[0].metadata_id == "abc123"
```

- [ ] **Step 10: Run the integration test**

```sh
cd server && uv run pytest tests/integration/test_schema.py -v
```

Expected: PASS (testcontainers spins up Postgres, alembic upgrade runs, schema round-trip works).

- [ ] **Step 11: Commit**

```bash
git add server/alembic.ini server/migrations server/opds_sync/db server/tests/conftest.py server/tests/integration
git commit -m ":sparkles: feat(server): SQLAlchemy models + initial Alembic migration

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: Auth — JWT validation against Authentik JWKS

**Files:**
- Create: `server/opds_sync/core/auth.py`
- Create: `server/tests/unit/test_auth.py`

- [ ] **Step 1: Write the failing tests at `server/tests/unit/test_auth.py`**

```python
import time
from datetime import datetime, timedelta, timezone

import jwt
import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa

from opds_sync.core.auth import JwtValidator, JwksFetcher


def _gen_keypair():
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    pem_priv = key.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption(),
    )
    pub = key.public_key()
    return key, pem_priv, pub


class _StaticJwks(JwksFetcher):
    def __init__(self, key_id: str, public_key) -> None:
        self.key_id = key_id
        self.public_key = public_key
        self.calls = 0

    async def get_signing_key(self, kid: str):  # type: ignore[override]
        self.calls += 1
        if kid != self.key_id:
            raise KeyError(kid)
        return self.public_key


@pytest.fixture
def keypair():
    return _gen_keypair()


@pytest.fixture
def jwks(keypair):
    _, _, pub = keypair
    return _StaticJwks("k1", pub)


def _sign(priv_pem, claims, kid="k1"):
    return jwt.encode(claims, priv_pem, algorithm="RS256", headers={"kid": kid})


async def test_valid_token_returns_subject(jwks, keypair):
    _, priv, _ = keypair
    now = int(time.time())
    token = _sign(priv, {
        "sub": "user-123", "iss": "iss", "aud": "aud",
        "iat": now, "nbf": now, "exp": now + 60,
    })
    validator = JwtValidator(jwks=jwks, issuer="iss", audience="aud")
    assert (await validator.subject_from_token(token)) == "user-123"


async def test_expired_token_rejected(jwks, keypair):
    _, priv, _ = keypair
    now = int(time.time())
    token = _sign(priv, {
        "sub": "user-123", "iss": "iss", "aud": "aud",
        "iat": now - 120, "nbf": now - 120, "exp": now - 60,
    })
    validator = JwtValidator(jwks=jwks, issuer="iss", audience="aud")
    with pytest.raises(jwt.ExpiredSignatureError):
        await validator.subject_from_token(token)


async def test_wrong_audience_rejected(jwks, keypair):
    _, priv, _ = keypair
    now = int(time.time())
    token = _sign(priv, {
        "sub": "user-123", "iss": "iss", "aud": "other",
        "iat": now, "nbf": now, "exp": now + 60,
    })
    validator = JwtValidator(jwks=jwks, issuer="iss", audience="aud")
    with pytest.raises(jwt.InvalidAudienceError):
        await validator.subject_from_token(token)
```

- [ ] **Step 2: Run the tests to verify they fail**

```sh
cd server && uv run pytest tests/unit/test_auth.py -v
```

Expected: ImportError for `JwtValidator`.

- [ ] **Step 3: Implement `server/opds_sync/core/auth.py`**

```python
from __future__ import annotations

from typing import Protocol

import httpx
import jwt
from fastapi import Depends, HTTPException, Request, status
from jwt import PyJWKClient


class JwksFetcher(Protocol):
    async def get_signing_key(self, kid: str): ...


class HttpxJwksFetcher:
    """Caches JWKS in-memory; refreshes on `kid` miss via PyJWKClient."""

    def __init__(self, jwks_url: str) -> None:
        self._client = PyJWKClient(jwks_url, cache_keys=True, lifespan=24 * 3600)

    async def get_signing_key(self, kid: str):
        # PyJWKClient is sync; in practice the JWKS is small and cached, so a thread offload is fine.
        # If this becomes hot, switch to httpx + manual JWKS parsing.
        import asyncio
        return await asyncio.to_thread(self._client.get_signing_key, kid)


class JwtValidator:
    def __init__(self, jwks: JwksFetcher, issuer: str, audience: str) -> None:
        self._jwks = jwks
        self._issuer = issuer
        self._audience = audience

    async def subject_from_token(self, token: str) -> str:
        unverified = jwt.get_unverified_header(token)
        kid = unverified.get("kid")
        if not kid:
            raise jwt.InvalidTokenError("missing kid")
        signing_key = await self._jwks.get_signing_key(kid)
        key_obj = signing_key.key if hasattr(signing_key, "key") else signing_key
        claims = jwt.decode(
            token,
            key=key_obj,
            algorithms=["RS256"],
            audience=self._audience,
            issuer=self._issuer,
            options={"require": ["exp", "iat", "sub"]},
        )
        sub = claims.get("sub")
        if not sub:
            raise jwt.InvalidTokenError("missing sub")
        return sub


async def get_validator(request: Request) -> JwtValidator:
    return request.app.state.jwt_validator


async def current_user_id(request: Request, validator: JwtValidator = Depends(get_validator)) -> str:
    auth = request.headers.get("authorization")
    if not auth or not auth.lower().startswith("bearer "):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="missing bearer")
    token = auth[7:]
    try:
        return await validator.subject_from_token(token)
    except jwt.PyJWTError as e:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail=str(e)) from e
```

- [ ] **Step 4: Wire the validator into the app factory**

Edit `server/opds_sync/main.py`:

```python
import logging

from fastapi import FastAPI

from opds_sync.api import health, progress
from opds_sync.config import get_settings
from opds_sync.core.auth import HttpxJwksFetcher, JwtValidator


def create_app() -> FastAPI:
    settings = get_settings()
    logging.basicConfig(level=settings.log_level)

    app = FastAPI(title="opds-sync", version="0.1.0")
    jwks = HttpxJwksFetcher(jwks_url=f"{settings.authentik_issuer.rstrip('/')}/jwks/")
    app.state.jwt_validator = JwtValidator(
        jwks=jwks,
        issuer=settings.authentik_issuer,
        audience=settings.authentik_audience,
    )
    app.include_router(health.router, prefix="/sync/v1")
    app.include_router(progress.router, prefix="/sync/v1")
    return app


app = create_app()
```

- [ ] **Step 5: Run the auth tests**

```sh
cd server && uv run pytest tests/unit/test_auth.py -v
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add server/opds_sync/core/auth.py server/opds_sync/main.py server/tests/unit/test_auth.py
git commit -m ":lock: feat(server): JWT validation against Authentik JWKS

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: Health endpoints

**Files:**
- Create: `server/opds_sync/api/__init__.py`
- Create: `server/opds_sync/api/health.py`
- Create: `server/tests/integration/test_health.py`

- [ ] **Step 1: Write the failing test**

`server/tests/integration/test_health.py`:

```python
from httpx import ASGITransport, AsyncClient


async def test_healthz_returns_200():
    from opds_sync.main import create_app
    app = create_app()
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        r = await client.get("/sync/v1/healthz")
    assert r.status_code == 200
    assert r.json() == {"status": "ok"}


async def test_readyz_returns_200_when_db_reachable(postgres_url, alembic_upgrade, monkeypatch):
    monkeypatch.setenv("OPDS_SYNC_DATABASE_URL", postgres_url)
    from opds_sync.main import create_app
    app = create_app()
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        r = await client.get("/sync/v1/readyz")
    assert r.status_code == 200
```

- [ ] **Step 2: Run the test — confirm it fails**

```sh
cd server && uv run pytest tests/integration/test_health.py -v
```

Expected: 404 (route not registered) or ImportError.

- [ ] **Step 3: Write `server/opds_sync/api/__init__.py`** (empty).

- [ ] **Step 4: Write `server/opds_sync/api/health.py`**

```python
from fastapi import APIRouter, HTTPException, status
from sqlalchemy import text

from opds_sync.db.session import session_scope

router = APIRouter(tags=["health"])


@router.get("/healthz")
async def healthz() -> dict:
    return {"status": "ok"}


@router.get("/readyz")
async def readyz() -> dict:
    try:
        async with session_scope() as s:
            await s.execute(text("select 1"))
    except Exception as e:  # noqa: BLE001 — readiness must not leak details
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="db unreachable") from e
    return {"status": "ready"}
```

- [ ] **Step 5: The `db.session` module currently caches the engine on first call. For tests that switch DB URL via env, force a fresh engine per `create_app`. Update `server/opds_sync/db/session.py`** to support a per-app override:

Replace the file with:

```python
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from sqlalchemy.ext.asyncio import (
    AsyncEngine,
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)

from opds_sync.config import get_settings


def make_engine(database_url: str | None = None) -> AsyncEngine:
    url = database_url or get_settings().database_url
    return create_async_engine(url, pool_pre_ping=True, future=True)


def make_session_factory(engine: AsyncEngine) -> async_sessionmaker[AsyncSession]:
    return async_sessionmaker(engine, expire_on_commit=False, class_=AsyncSession)


_engine: AsyncEngine | None = None
_factory: async_sessionmaker[AsyncSession] | None = None


def configure(engine: AsyncEngine) -> None:
    global _engine, _factory
    _engine = engine
    _factory = make_session_factory(engine)


def _factories() -> async_sessionmaker[AsyncSession]:
    global _engine, _factory
    if _factory is None:
        _engine = make_engine()
        _factory = make_session_factory(_engine)
    return _factory


@asynccontextmanager
async def session_scope() -> AsyncIterator[AsyncSession]:
    factory = _factories()
    async with factory() as session:
        yield session


async def get_session() -> AsyncIterator[AsyncSession]:
    async with session_scope() as s:
        yield s
```

And in `server/opds_sync/main.py`, configure the engine inside `create_app` so each app instance binds to its current settings:

```python
import logging

from fastapi import FastAPI

from opds_sync.api import health, progress
from opds_sync.config import get_settings
from opds_sync.core.auth import HttpxJwksFetcher, JwtValidator
from opds_sync.db.session import configure, make_engine


def create_app() -> FastAPI:
    settings = get_settings()
    logging.basicConfig(level=settings.log_level)

    configure(make_engine(settings.database_url))

    app = FastAPI(title="opds-sync", version="0.1.0")
    jwks = HttpxJwksFetcher(jwks_url=f"{settings.authentik_issuer.rstrip('/')}/jwks/")
    app.state.jwt_validator = JwtValidator(
        jwks=jwks,
        issuer=settings.authentik_issuer,
        audience=settings.authentik_audience,
    )
    app.include_router(health.router, prefix="/sync/v1")
    app.include_router(progress.router, prefix="/sync/v1")
    return app


app = create_app()
```

- [ ] **Step 6: Run the health tests — pass**

```sh
cd server && uv run pytest tests/integration/test_health.py -v
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add server/opds_sync/api/__init__.py server/opds_sync/api/health.py server/opds_sync/db/session.py server/opds_sync/main.py server/tests/integration/test_health.py
git commit -m ":sparkles: feat(server): /healthz + /readyz endpoints

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: POST /sync/v1/progress + GET /sync/v1/progress

**Files:**
- Create: `server/opds_sync/api/progress.py`
- Create: `server/tests/integration/test_progress.py`
- Modify: `server/tests/conftest.py` to add a JWT-signing fixture so tests can hit auth-protected routes.

- [ ] **Step 1: Extend `server/tests/conftest.py`**

Append to the existing `conftest.py`:

```python
import time

import jwt
import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa


@pytest.fixture(scope="session")
def signing_key():
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    priv = key.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption(),
    )
    pub = key.public_key()
    return priv, pub


@pytest.fixture
def issuer() -> str:
    return "https://test-iss.example/"


@pytest.fixture
def audience() -> str:
    return "test-aud"


@pytest.fixture
def make_token(signing_key, issuer, audience):
    priv, _ = signing_key
    def _make(sub: str, ttl: int = 60, **extra) -> str:
        now = int(time.time())
        claims = {"sub": sub, "iss": issuer, "aud": audience, "iat": now, "nbf": now, "exp": now + ttl, **extra}
        return jwt.encode(claims, priv, algorithm="RS256", headers={"kid": "test-key"})
    return _make


@pytest.fixture
def app_under_test(postgres_url, alembic_upgrade, monkeypatch, signing_key, issuer, audience):
    """A FastAPI app wired to the test Postgres + a static JWKS fetcher."""
    monkeypatch.setenv("OPDS_SYNC_DATABASE_URL", postgres_url)
    monkeypatch.setenv("OPDS_SYNC_AUTHENTIK_ISSUER", issuer)
    monkeypatch.setenv("OPDS_SYNC_AUTHENTIK_AUDIENCE", audience)

    from opds_sync.core.auth import JwksFetcher, JwtValidator
    from opds_sync.main import create_app

    _, pub = signing_key

    class StaticJwks(JwksFetcher):
        async def get_signing_key(self, kid):  # type: ignore[override]
            class _K: key = pub  # noqa: E701
            return _K()

    app = create_app()
    app.state.jwt_validator = JwtValidator(jwks=StaticJwks(), issuer=issuer, audience=audience)
    return app
```

- [ ] **Step 2: Write the failing tests at `server/tests/integration/test_progress.py`**

```python
from httpx import ASGITransport, AsyncClient


def _bearer(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


async def test_post_progress_creates_document_and_progress(app_under_test, make_token):
    transport = ASGITransport(app=app_under_test)
    headers = _bearer(make_token("alice"))
    body = {
        "items": [
            {
                "document": {"metadata_id": "abc", "content_hash": "hash1"},
                "locator": "epubcfi(/6/4!/4)",
                "percent": 0.42,
                "client_updated_at": "2026-05-05T12:00:00+00:00",
            }
        ]
    }
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        r = await c.post("/sync/v1/progress", json=body, headers=headers)
    assert r.status_code == 200, r.text
    data = r.json()
    assert len(data["results"]) == 1
    assert data["results"][0]["status"] == "accepted"
    assert data["results"][0]["server_client_updated_at"] == "2026-05-05T12:00:00+00:00"


async def test_post_progress_lww_keeps_newer(app_under_test, make_token):
    transport = ASGITransport(app=app_under_test)
    headers = _bearer(make_token("alice"))
    base = {"document": {"metadata_id": "abc", "content_hash": "hash1"}, "locator": "loc", "percent": 0.1}
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        # newer first
        r1 = await c.post("/sync/v1/progress", headers=headers, json={"items": [{**base, "percent": 0.5, "client_updated_at": "2026-05-05T13:00:00+00:00"}]})
        assert r1.status_code == 200
        # older comes after
        r2 = await c.post("/sync/v1/progress", headers=headers, json={"items": [{**base, "percent": 0.1, "client_updated_at": "2026-05-05T12:00:00+00:00"}]})
        assert r2.status_code == 200
        assert r2.json()["results"][0]["status"] == "stale"
        # GET — should reflect the 0.5 value
        r3 = await c.get("/sync/v1/progress?since=2026-01-01T00:00:00+00:00", headers=headers)
        assert r3.status_code == 200
        items = r3.json()["items"]
        assert len(items) == 1
        assert items[0]["percent"] == 0.5


async def test_get_progress_filters_by_user(app_under_test, make_token):
    transport = ASGITransport(app=app_under_test)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        await c.post(
            "/sync/v1/progress", headers=_bearer(make_token("alice")),
            json={"items": [{"document": {"metadata_id": "a", "content_hash": "h"}, "locator": "l", "percent": 0.1, "client_updated_at": "2026-05-05T12:00:00+00:00"}]},
        )
        r = await c.get("/sync/v1/progress?since=2026-01-01T00:00:00+00:00", headers=_bearer(make_token("bob")))
    assert r.status_code == 200
    assert r.json()["items"] == []


async def test_unauthenticated_request_rejected(app_under_test):
    transport = ASGITransport(app=app_under_test)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        r = await c.get("/sync/v1/progress?since=2026-01-01T00:00:00+00:00")
    assert r.status_code == 401
```

- [ ] **Step 3: Run the tests — confirm they fail**

```sh
cd server && uv run pytest tests/integration/test_progress.py -v
```

Expected: 404 / import error.

- [ ] **Step 4: Implement `server/opds_sync/api/progress.py`**

```python
from datetime import datetime
from typing import Annotated, Literal

from fastapi import APIRouter, Depends, Query
from pydantic import BaseModel
from sqlalchemy import select
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.ext.asyncio import AsyncSession

from opds_sync.core.auth import current_user_id
from opds_sync.db.models import Document, Progress
from opds_sync.db.session import get_session

router = APIRouter(tags=["progress"])


class DocumentIdentity(BaseModel):
    metadata_id: str | None = None
    content_hash: str


class ProgressItem(BaseModel):
    document: DocumentIdentity
    locator: str
    percent: float
    client_updated_at: datetime


class ProgressPushBody(BaseModel):
    items: list[ProgressItem]


class ProgressPushResult(BaseModel):
    document: DocumentIdentity
    status: Literal["accepted", "stale"]
    server_client_updated_at: datetime


class ProgressPushResponse(BaseModel):
    results: list[ProgressPushResult]


class ProgressPullItem(BaseModel):
    document: DocumentIdentity
    locator: str
    percent: float
    client_updated_at: datetime


class ProgressPullResponse(BaseModel):
    items: list[ProgressPullItem]
    server_time: datetime


async def _resolve_or_create_document(
    session: AsyncSession, user_id: str, ident: DocumentIdentity
) -> Document:
    """Per spec §5.4: metadata_id first, then content_hash, else create."""
    if ident.metadata_id:
        existing = (
            await session.execute(
                select(Document).where(
                    Document.user_id == user_id, Document.metadata_id == ident.metadata_id
                )
            )
        ).scalar_one_or_none()
        if existing:
            return existing
    existing = (
        await session.execute(
            select(Document).where(
                Document.user_id == user_id, Document.content_hash == ident.content_hash
            )
        )
    ).scalar_one_or_none()
    if existing:
        # Backfill metadata_id if we just learned it
        if ident.metadata_id and existing.metadata_id is None:
            existing.metadata_id = ident.metadata_id
        return existing
    doc = Document(user_id=user_id, metadata_id=ident.metadata_id, content_hash=ident.content_hash)
    session.add(doc)
    await session.flush()  # populate doc.pk
    return doc


@router.post("/progress", response_model=ProgressPushResponse)
async def push_progress(
    body: ProgressPushBody,
    user_id: Annotated[str, Depends(current_user_id)],
    session: Annotated[AsyncSession, Depends(get_session)],
) -> ProgressPushResponse:
    results: list[ProgressPushResult] = []
    for item in body.items:
        doc = await _resolve_or_create_document(session, user_id, item.document)
        existing = (
            await session.execute(select(Progress).where(Progress.document_pk == doc.pk))
        ).scalar_one_or_none()
        if existing is None:
            session.add(
                Progress(
                    document_pk=doc.pk,
                    locator=item.locator,
                    percent=item.percent,
                    client_updated_at=item.client_updated_at,
                )
            )
            results.append(
                ProgressPushResult(
                    document=item.document,
                    status="accepted",
                    server_client_updated_at=item.client_updated_at,
                )
            )
            continue
        if item.client_updated_at > existing.client_updated_at:
            existing.locator = item.locator
            existing.percent = item.percent
            existing.client_updated_at = item.client_updated_at
            results.append(
                ProgressPushResult(
                    document=item.document,
                    status="accepted",
                    server_client_updated_at=item.client_updated_at,
                )
            )
        else:
            results.append(
                ProgressPushResult(
                    document=item.document,
                    status="stale",
                    server_client_updated_at=existing.client_updated_at,
                )
            )
    await session.commit()
    return ProgressPushResponse(results=results)


@router.get("/progress", response_model=ProgressPullResponse)
async def pull_progress(
    since: Annotated[datetime, Query()],
    user_id: Annotated[str, Depends(current_user_id)],
    session: Annotated[AsyncSession, Depends(get_session)],
) -> ProgressPullResponse:
    rows = (
        await session.execute(
            select(Progress, Document)
            .join(Document, Document.pk == Progress.document_pk)
            .where(Document.user_id == user_id, Progress.client_updated_at > since)
            .order_by(Progress.client_updated_at)
        )
    ).all()
    items = [
        ProgressPullItem(
            document=DocumentIdentity(metadata_id=d.metadata_id, content_hash=d.content_hash),
            locator=p.locator,
            percent=p.percent,
            client_updated_at=p.client_updated_at,
        )
        for p, d in rows
    ]
    server_time = datetime.now().astimezone()
    return ProgressPullResponse(items=items, server_time=server_time)
```

- [ ] **Step 5: Run the tests**

```sh
cd server && uv run pytest tests/integration/test_progress.py -v
```

Expected: all PASS.

- [ ] **Step 6: Run the full test suite**

```sh
cd server && uv run pytest -v
```

Expected: all PASS.

- [ ] **Step 7: Commit**

```bash
git add server/opds_sync/api/progress.py server/tests/conftest.py server/tests/integration/test_progress.py
git commit -m ":sparkles: feat(server): POST/GET /progress with LWW + per-user scoping

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 8: Server CI workflow (lint + test + image push)

**Files:**
- Create: `.github/workflows/server-ci.yaml`

- [ ] **Step 1: Write the workflow**

```yaml
name: server-ci

on:
  push:
    paths:
      - "server/**"
      - ".github/workflows/server-ci.yaml"
      - "core/identity/src/test/resources/identity/**"
  pull_request:
    paths:
      - "server/**"
      - ".github/workflows/server-ci.yaml"
      - "core/identity/src/test/resources/identity/**"

jobs:
  test:
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: server
    steps:
      - uses: actions/checkout@v4
      - uses: astral-sh/setup-uv@v3
      - name: Set up Python
        run: uv python install 3.12
      - name: Install
        run: uv pip install --system -e ".[dev]"
      - name: Lint
        run: ruff check . && ruff format --check .
      - name: Test
        run: pytest -v

  image:
    needs: test
    runs-on: ubuntu-latest
    if: github.ref == 'refs/heads/main'
    permissions:
      contents: read
      packages: write
    steps:
      - uses: actions/checkout@v4
      - uses: docker/setup-buildx-action@v3
      - uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}
      - uses: docker/build-push-action@v6
        with:
          context: server
          push: true
          tags: |
            ghcr.io/${{ github.repository_owner }}/opds-sync:${{ github.sha }}
            ghcr.io/${{ github.repository_owner }}/opds-sync:latest
```

- [ ] **Step 2: Commit**

```bash
git add .github/workflows/server-ci.yaml
git commit -m ":wrench: chore(ci): server-ci workflow (lint + test + image push)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 9: Kustomize manifests for `opds-sync`

**Files:**
- Create: `deploy/k8s/opds-sync/kustomization.yaml`
- Create: `deploy/k8s/opds-sync/namespace.yaml`
- Create: `deploy/k8s/opds-sync/postgres-pvc.yaml`
- Create: `deploy/k8s/opds-sync/postgres-statefulset.yaml`
- Create: `deploy/k8s/opds-sync/postgres-service.yaml`
- Create: `deploy/k8s/opds-sync/deployment.yaml`
- Create: `deploy/k8s/opds-sync/service.yaml`
- Create: `deploy/k8s/opds-sync/ingress.yaml`
- Create: `deploy/k8s/opds-sync/network-policies.yaml`
- Create: `deploy/k8s/opds-sync/secret.example.yaml`
- Create: `deploy/k8s/opds-sync/README.md`

- [ ] **Step 1: `namespace.yaml`**

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: opds-sync
```

- [ ] **Step 2: `postgres-pvc.yaml`**

```yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: opds-sync-postgres
  namespace: opds-sync
spec:
  accessModes: ["ReadWriteOnce"]
  resources:
    requests:
      storage: 5Gi
```

- [ ] **Step 3: `postgres-statefulset.yaml`**

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: postgres
  namespace: opds-sync
spec:
  serviceName: postgres
  replicas: 1
  selector:
    matchLabels:
      app: postgres
  template:
    metadata:
      labels:
        app: postgres
    spec:
      containers:
        - name: postgres
          image: postgres:16-alpine
          ports:
            - containerPort: 5432
          env:
            - name: POSTGRES_DB
              value: opds_sync
            - name: POSTGRES_USER
              valueFrom: { secretKeyRef: { name: opds-sync-secrets, key: postgres-user } }
            - name: POSTGRES_PASSWORD
              valueFrom: { secretKeyRef: { name: opds-sync-secrets, key: postgres-password } }
            - name: PGDATA
              value: /var/lib/postgresql/data/pgdata
          volumeMounts:
            - name: data
              mountPath: /var/lib/postgresql/data
          readinessProbe:
            exec: { command: ["pg_isready", "-U", "$(POSTGRES_USER)", "-d", "opds_sync"] }
            initialDelaySeconds: 10
            periodSeconds: 5
      volumes:
        - name: data
          persistentVolumeClaim:
            claimName: opds-sync-postgres
```

- [ ] **Step 4: `postgres-service.yaml`**

```yaml
apiVersion: v1
kind: Service
metadata:
  name: postgres
  namespace: opds-sync
spec:
  type: ClusterIP
  selector:
    app: postgres
  ports:
    - port: 5432
      targetPort: 5432
```

- [ ] **Step 5: `deployment.yaml`**

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: opds-sync
  namespace: opds-sync
spec:
  replicas: 1
  selector:
    matchLabels:
      app: opds-sync
  template:
    metadata:
      labels:
        app: opds-sync
    spec:
      initContainers:
        - name: migrate
          image: ghcr.io/REPLACE_OWNER/opds-sync:latest  # replaced via Kustomize image override
          command: ["alembic", "upgrade", "head"]
          env:
            - name: OPDS_SYNC_DATABASE_URL
              valueFrom: { secretKeyRef: { name: opds-sync-secrets, key: database-url } }
      containers:
        - name: opds-sync
          image: ghcr.io/REPLACE_OWNER/opds-sync:latest
          ports:
            - containerPort: 8000
          env:
            - name: OPDS_SYNC_DATABASE_URL
              valueFrom: { secretKeyRef: { name: opds-sync-secrets, key: database-url } }
            - name: OPDS_SYNC_AUTHENTIK_ISSUER
              valueFrom: { secretKeyRef: { name: opds-sync-secrets, key: authentik-issuer } }
            - name: OPDS_SYNC_AUTHENTIK_AUDIENCE
              valueFrom: { secretKeyRef: { name: opds-sync-secrets, key: authentik-audience } }
            - name: OPDS_SYNC_LOG_LEVEL
              value: INFO
          readinessProbe:
            httpGet: { path: /sync/v1/readyz, port: 8000 }
            initialDelaySeconds: 5
            periodSeconds: 10
          livenessProbe:
            httpGet: { path: /sync/v1/healthz, port: 8000 }
            initialDelaySeconds: 5
            periodSeconds: 30
```

- [ ] **Step 6: `service.yaml`**

```yaml
apiVersion: v1
kind: Service
metadata:
  name: opds-sync
  namespace: opds-sync
spec:
  type: ClusterIP
  selector:
    app: opds-sync
  ports:
    - port: 80
      targetPort: 8000
```

- [ ] **Step 7: `ingress.yaml`**

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: opds-sync
  namespace: opds-sync
  annotations:
    cert-manager.io/cluster-issuer: letsencrypt-prod
    traefik.ingress.kubernetes.io/router.entrypoints: websecure
    traefik.ingress.kubernetes.io/router.tls: "true"
spec:
  ingressClassName: traefik
  tls:
    - hosts: ["sync.theficos.dedyn.io"]
      secretName: opds-sync-tls
  rules:
    - host: sync.theficos.dedyn.io
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: opds-sync
                port:
                  number: 80
```

- [ ] **Step 8: `network-policies.yaml`**

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: deny-all-default
  namespace: opds-sync
spec:
  podSelector: {}
  policyTypes: [Ingress, Egress]
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: opds-sync-allow
  namespace: opds-sync
spec:
  podSelector:
    matchLabels:
      app: opds-sync
  policyTypes: [Ingress, Egress]
  ingress:
    - from:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: traefik
      ports:
        - port: 8000
  egress:
    - to:
        - podSelector:
            matchLabels:
              app: postgres
      ports:
        - port: 5432
    - to: []  # JWKS / DNS
      ports:
        - port: 53
          protocol: UDP
        - port: 443
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: postgres-allow
  namespace: opds-sync
spec:
  podSelector:
    matchLabels:
      app: postgres
  policyTypes: [Ingress]
  ingress:
    - from:
        - podSelector:
            matchLabels:
              app: opds-sync
      ports:
        - port: 5432
```

- [ ] **Step 9: `secret.example.yaml`** (template only — real secret encrypted with SOPS, applied out-of-band):

```yaml
# Template. Copy to secret.yaml, fill in, then `sops --encrypt --in-place secret.yaml`.
# Apply manually: kubectl apply -f secret.yaml
apiVersion: v1
kind: Secret
metadata:
  name: opds-sync-secrets
  namespace: opds-sync
type: Opaque
stringData:
  postgres-user: opds_sync
  postgres-password: REPLACE_ME
  database-url: postgresql+asyncpg://opds_sync:REPLACE_ME@postgres.opds-sync.svc.cluster.local:5432/opds_sync
  authentik-issuer: https://auth.theficos.dedyn.io/application/o/quire/
  authentik-audience: quire
```

- [ ] **Step 10: `kustomization.yaml`**

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
namespace: opds-sync

resources:
  - namespace.yaml
  - postgres-pvc.yaml
  - postgres-service.yaml
  - postgres-statefulset.yaml
  - service.yaml
  - deployment.yaml
  - ingress.yaml
  - network-policies.yaml

images:
  - name: ghcr.io/REPLACE_OWNER/opds-sync
    newName: ghcr.io/vito/opds-sync   # update if your GHCR namespace differs
    newTag: latest
```

- [ ] **Step 11: `README.md`**

```markdown
# opds-sync deploy

Apply order on first deploy:

1. Create the encrypted secret (SOPS) and apply it:
   ```sh
   cp secret.example.yaml secret.yaml
   # edit secret.yaml with real values
   sops --encrypt --in-place secret.yaml
   sops --decrypt secret.yaml | kubectl apply -f -
   ```

2. Apply Kustomize manifests:
   ```sh
   kubectl apply -k .
   ```

3. Verify:
   ```sh
   kubectl -n opds-sync rollout status deploy/opds-sync
   curl https://sync.theficos.dedyn.io/sync/v1/healthz
   ```

## Authentik prerequisite

Create an OAuth2 application in Authentik **before** deploying:

- Name: Quire
- Slug: quire
- Provider type: OAuth2/OpenID
- Client type: **Public**
- PKCE: required
- Redirect URIs: `quire://oauth`
- Audience: `quire`

The audience must match `OPDS_SYNC_AUTHENTIK_AUDIENCE`.
```

- [ ] **Step 12: Validate the manifests build**

```sh
kubectl kustomize deploy/k8s/opds-sync >/tmp/rendered.yaml && head -40 /tmp/rendered.yaml
```

Expected: rendered YAML, no Kustomize errors. (Run from a machine with `kubectl` or `kustomize`; if not available, skip — CI / target host will catch issues.)

- [ ] **Step 13: Commit**

```bash
git add deploy/k8s/opds-sync
git commit -m ":wrench: chore(deploy): opds-sync Kustomize manifests

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 10: Android — `:auth` additions (Authentik config + token store)

**Files:**
- Create: `auth/src/main/java/io/theficos/ereader/auth/AuthentikConfig.kt`
- Create: `auth/src/main/java/io/theficos/ereader/auth/AuthState.kt`
- Create: `auth/src/main/java/io/theficos/ereader/auth/AuthTokenStore.kt`
- Create: `auth/src/test/java/io/theficos/ereader/auth/AuthTokenStoreTest.kt`
- Modify: `auth/build.gradle.kts` (add appauth)

- [ ] **Step 1: `AuthentikConfig.kt`**

```kotlin
package io.theficos.ereader.auth

data class AuthentikConfig(
    val issuer: String,             // e.g. https://auth.theficos.dedyn.io/application/o/quire/
    val clientId: String,           // public client id from Authentik
    val redirectUri: String = "quire://oauth",
    val scope: String = "openid profile email offline_access",
)
```

- [ ] **Step 2: `AuthState.kt`**

```kotlin
package io.theficos.ereader.auth

sealed interface AuthState {
    data object SignedOut : AuthState
    data class SignedIn(val sub: String, val email: String?, val accessExpiresAt: Long) : AuthState
    data object NeedsReauth : AuthState
}
```

- [ ] **Step 3: Add appauth to `auth/build.gradle.kts`**

Replace the file with:

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.theficos.ereader.auth"
    compileSdk = 34
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.appauth)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
```

- [ ] **Step 4: Write the failing token-store test `auth/src/test/java/io/theficos/ereader/auth/AuthTokenStoreTest.kt`**

```kotlin
package io.theficos.ereader.auth

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AuthTokenStoreTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun `empty store returns null`() {
        val store = AuthTokenStore(ctx)
        store.clear()
        assertThat(store.read()).isNull()
    }

    @Test fun `write then read round-trips`() {
        val store = AuthTokenStore(ctx)
        store.clear()
        val tokens = AuthTokens(accessToken = "a", refreshToken = "r", accessExpiresAtEpochMs = 12345L, sub = "u1", email = "u@x")
        store.write(tokens)
        assertThat(store.read()).isEqualTo(tokens)
    }

    @Test fun `clear removes everything`() {
        val store = AuthTokenStore(ctx)
        store.write(AuthTokens("a", "r", 1L, "u1", null))
        store.clear()
        assertThat(store.read()).isNull()
    }
}
```

- [ ] **Step 5: Implement `AuthTokenStore.kt`**

```kotlin
package io.theficos.ereader.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class AuthTokens(
    val accessToken: String,
    val refreshToken: String?,
    val accessExpiresAtEpochMs: Long,
    val sub: String,
    val email: String?,
)

class AuthTokenStore(context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "authentik_tokens",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun read(): AuthTokens? {
        val a = prefs.getString(KEY_ACCESS, null) ?: return null
        return AuthTokens(
            accessToken = a,
            refreshToken = prefs.getString(KEY_REFRESH, null),
            accessExpiresAtEpochMs = prefs.getLong(KEY_EXPIRES_AT, 0L),
            sub = prefs.getString(KEY_SUB, null) ?: return null,
            email = prefs.getString(KEY_EMAIL, null),
        )
    }

    fun write(tokens: AuthTokens) {
        prefs.edit()
            .putString(KEY_ACCESS, tokens.accessToken)
            .putString(KEY_REFRESH, tokens.refreshToken)
            .putLong(KEY_EXPIRES_AT, tokens.accessExpiresAtEpochMs)
            .putString(KEY_SUB, tokens.sub)
            .putString(KEY_EMAIL, tokens.email)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().commit()
    }

    private companion object {
        const val KEY_ACCESS = "access"
        const val KEY_REFRESH = "refresh"
        const val KEY_EXPIRES_AT = "expires_at"
        const val KEY_SUB = "sub"
        const val KEY_EMAIL = "email"
    }
}
```

- [ ] **Step 6: Run the test**

```sh
./scripts/dgradle :auth:test
```

Expected: PASS for `AuthTokenStoreTest` (and pre-existing `CalibreCredentialStoreTest`).

- [ ] **Step 7: Commit**

```bash
git add auth/build.gradle.kts auth/src/main/java/io/theficos/ereader/auth/AuthentikConfig.kt auth/src/main/java/io/theficos/ereader/auth/AuthState.kt auth/src/main/java/io/theficos/ereader/auth/AuthTokenStore.kt auth/src/test/java/io/theficos/ereader/auth/AuthTokenStoreTest.kt
git commit -m ":sparkles: feat(auth): Authentik config + Keystore-backed token store

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 11: Android — `AuthentikAuthenticator` (AppAuth wrapper)

**Files:**
- Create: `auth/src/main/java/io/theficos/ereader/auth/AuthentikAuthenticator.kt`

This task wraps AppAuth's discovery + authorization-code-with-PKCE + refresh flows. AppAuth's authorization step requires an `Activity` to launch the Custom Tab; we expose two entry points: `buildAuthorizationIntent(...)` for the UI to launch, and `handleAuthorizationResponse(intent)` for the redirect callback. The refresh path is fully background-able.

- [ ] **Step 1: Write the file**

```kotlin
package io.theficos.ereader.auth

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.suspendCancellableCoroutine
import net.openid.appauth.AuthState as AppAuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenResponse
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AuthentikAuthenticator(
    context: Context,
    private val config: AuthentikConfig,
    private val tokenStore: AuthTokenStore,
) {
    private val service = AuthorizationService(context.applicationContext)

    suspend fun discoverConfig(): AuthorizationServiceConfiguration =
        suspendCancellableCoroutine { cont ->
            AuthorizationServiceConfiguration.fetchFromIssuer(android.net.Uri.parse(config.issuer)) { c, ex ->
                if (c != null) cont.resume(c) else cont.resumeWithException(ex ?: IllegalStateException("OIDC discovery failed"))
            }
        }

    suspend fun buildAuthorizationIntent(): Intent {
        val serviceConfig = discoverConfig()
        val req = AuthorizationRequest.Builder(
            serviceConfig,
            config.clientId,
            ResponseTypeValues.CODE,
            android.net.Uri.parse(config.redirectUri),
        ).setScope(config.scope).build()
        return service.getAuthorizationRequestIntent(req)
    }

    /** Call from your redirect-handling activity's `onCreate`. Returns true on success. */
    suspend fun handleAuthorizationResponse(data: Intent): Boolean {
        val resp = AuthorizationResponse.fromIntent(data)
        val ex = AuthorizationException.fromIntent(data)
        if (resp == null) {
            tokenStore.clear()
            throw ex ?: IllegalStateException("Auth response missing")
        }
        val tokenResp = exchangeAuthorizationCode(resp)
        persist(tokenResp)
        return true
    }

    private suspend fun exchangeAuthorizationCode(resp: AuthorizationResponse): TokenResponse =
        suspendCancellableCoroutine { cont ->
            service.performTokenRequest(resp.createTokenExchangeRequest()) { tr, ex ->
                if (tr != null) cont.resume(tr) else cont.resumeWithException(ex ?: IllegalStateException("Token exchange failed"))
            }
        }

    /** Refresh the access token. Returns the new access token, or null if refresh failed. */
    suspend fun refresh(): String? {
        val current = tokenStore.read() ?: return null
        val refresh = current.refreshToken ?: return null
        val serviceConfig = runCatching { discoverConfig() }.getOrElse { return null }
        val state = AppAuthState(serviceConfig)
        return suspendCancellableCoroutine { cont ->
            val req = net.openid.appauth.TokenRequest.Builder(serviceConfig, config.clientId)
                .setGrantType("refresh_token")
                .setRefreshToken(refresh)
                .build()
            service.performTokenRequest(req) { tr, ex ->
                if (tr != null) {
                    persist(tr, fallback = current)
                    cont.resume(tr.accessToken)
                } else {
                    // Treat any refresh failure as terminal — caller handles re-auth UX.
                    tokenStore.clear()
                    cont.resume(null)
                }
            }
        }
    }

    private fun persist(tr: TokenResponse, fallback: AuthTokens? = null) {
        val access = tr.accessToken ?: fallback?.accessToken ?: return
        val expiresAt = tr.accessTokenExpirationTime ?: (System.currentTimeMillis() + 5 * 60_000L)
        val sub = tr.idToken?.let { extractSubFromIdToken(it) } ?: fallback?.sub ?: return
        val email = tr.idToken?.let { extractEmailFromIdToken(it) } ?: fallback?.email
        tokenStore.write(
            AuthTokens(
                accessToken = access,
                refreshToken = tr.refreshToken ?: fallback?.refreshToken,
                accessExpiresAtEpochMs = expiresAt,
                sub = sub,
                email = email,
            )
        )
    }

    private fun extractSubFromIdToken(jwt: String): String? = decodeIdTokenClaim(jwt, "sub")
    private fun extractEmailFromIdToken(jwt: String): String? = decodeIdTokenClaim(jwt, "email")

    private fun decodeIdTokenClaim(jwt: String, claim: String): String? {
        val parts = jwt.split(".")
        if (parts.size < 2) return null
        val payload = android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        val json = String(payload)
        // Avoid pulling a JSON dep here; light regex extraction is sufficient for "sub" / "email".
        val match = Regex("\"$claim\"\\s*:\\s*\"([^\"]+)\"").find(json)
        return match?.groupValues?.get(1)
    }

    fun close() {
        service.dispose()
    }
}
```

- [ ] **Step 2: Compile-check**

```sh
./scripts/dgradle :auth:assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add auth/src/main/java/io/theficos/ereader/auth/AuthentikAuthenticator.kt
git commit -m ":sparkles: feat(auth): AppAuth-based AuthentikAuthenticator (PKCE + refresh)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 12: Android — `SyncAuthInterceptor`

**Files:**
- Create: `auth/src/main/java/io/theficos/ereader/auth/SyncAuthInterceptor.kt`
- Modify: `auth/build.gradle.kts` (add okhttp dep)
- Create: `auth/src/test/java/io/theficos/ereader/auth/SyncAuthInterceptorTest.kt`

- [ ] **Step 1: Add okhttp to `auth/build.gradle.kts` (in `dependencies {}`)**

```kotlin
    implementation(libs.okhttp)
```

Plus the matching test dep:

```kotlin
    testImplementation(libs.okhttp.mockwebserver)
```

- [ ] **Step 2: Write the failing test `auth/src/test/java/io/theficos/ereader/auth/SyncAuthInterceptorTest.kt`**

```kotlin
package io.theficos.ereader.auth

import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class SyncAuthInterceptorTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private fun client(interceptor: SyncAuthInterceptor): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(interceptor).build()

    @Test fun `attaches bearer when token present`() {
        val provider = StubTokenProvider(access = "a1", refreshed = "a2")
        server.enqueue(MockResponse().setResponseCode(200))
        val resp = client(SyncAuthInterceptor(provider)).newCall(Request.Builder().url(server.url("/")).build()).execute()
        resp.close()
        val req = server.takeRequest()
        assertThat(req.getHeader("Authorization")).isEqualTo("Bearer a1")
    }

    @Test fun `refreshes once on 401 and retries`() {
        val provider = StubTokenProvider(access = "a1", refreshed = "a2")
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(200))
        val resp = client(SyncAuthInterceptor(provider)).newCall(Request.Builder().url(server.url("/")).build()).execute()
        resp.close()
        val first = server.takeRequest()
        val second = server.takeRequest()
        assertThat(first.getHeader("Authorization")).isEqualTo("Bearer a1")
        assertThat(second.getHeader("Authorization")).isEqualTo("Bearer a2")
        assertThat(provider.refreshCount).isEqualTo(1)
    }

    @Test fun `gives up and clears tokens on second 401`() {
        val provider = StubTokenProvider(access = "a1", refreshed = "a2")
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(401))
        val resp = client(SyncAuthInterceptor(provider)).newCall(Request.Builder().url(server.url("/")).build()).execute()
        resp.close()
        assertThat(resp.code).isEqualTo(401)
        assertThat(provider.cleared).isTrue()
    }

    private class StubTokenProvider(var access: String?, val refreshed: String?) : TokenProvider {
        var refreshCount = 0
        var cleared = false
        override fun currentAccessToken(): String? = access
        override fun refreshSync(): String? {
            refreshCount += 1
            access = refreshed
            return refreshed
        }
        override fun clearTokens() { cleared = true; access = null }
    }
}
```

- [ ] **Step 3: Run the test — confirm it fails**

```sh
./scripts/dgradle :auth:test --tests SyncAuthInterceptorTest
```

Expected: compile error — `TokenProvider` and `SyncAuthInterceptor` not yet defined.

- [ ] **Step 4: Implement `SyncAuthInterceptor.kt`**

```kotlin
package io.theficos.ereader.auth

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

interface TokenProvider {
    fun currentAccessToken(): String?
    /** Synchronously refresh; return new access token or null on failure. */
    fun refreshSync(): String?
    fun clearTokens()
}

class SyncAuthInterceptor(private val tokens: TokenProvider) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val initial = chain.request().attach(tokens.currentAccessToken())
        val first = chain.proceed(initial)
        if (first.code != 401) return first

        first.close()
        val refreshed = tokens.refreshSync()
        if (refreshed == null) {
            tokens.clearTokens()
            // Re-issue without auth so caller sees a 401 from the server.
            return chain.proceed(initial.newBuilder().removeHeader("Authorization").build())
        }
        val retried = chain.proceed(chain.request().attach(refreshed))
        if (retried.code == 401) {
            retried.close()
            tokens.clearTokens()
            return chain.proceed(chain.request().newBuilder().removeHeader("Authorization").build())
        }
        return retried
    }

    private fun Request.attach(token: String?): Request =
        if (token == null) this else newBuilder().header("Authorization", "Bearer $token").build()
}
```

- [ ] **Step 5: Run the test**

```sh
./scripts/dgradle :auth:test --tests SyncAuthInterceptorTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add auth/build.gradle.kts auth/src/main/java/io/theficos/ereader/auth/SyncAuthInterceptor.kt auth/src/test/java/io/theficos/ereader/auth/SyncAuthInterceptorTest.kt
git commit -m ":sparkles: feat(auth): OkHttp interceptor with refresh-once-retry-once

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 13: Android — `ProgressDtos.kt` + `SyncApi.kt` + `SyncClient.kt`

**Files:**
- Create: `data/sync/src/main/java/io/theficos/ereader/data/sync/ProgressDtos.kt`
- Create: `data/sync/src/main/java/io/theficos/ereader/data/sync/SyncApi.kt`
- Create: `data/sync/src/main/java/io/theficos/ereader/data/sync/SyncClient.kt`
- Create: `data/sync/src/main/java/io/theficos/ereader/data/sync/SyncResult.kt`
- Create: `data/sync/src/test/java/io/theficos/ereader/data/sync/ProgressDtosTest.kt`
- Create: `data/sync/src/test/java/io/theficos/ereader/data/sync/SyncClientTest.kt`

- [ ] **Step 1: Write `ProgressDtos.kt`**

```kotlin
package io.theficos.ereader.data.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DocumentIdDto(
    @SerialName("metadata_id") val metadataId: String? = null,
    @SerialName("content_hash") val contentHash: String,
)

@Serializable
data class ProgressItemDto(
    val document: DocumentIdDto,
    val locator: String,
    val percent: Double,
    @SerialName("client_updated_at") val clientUpdatedAt: String, // ISO-8601 with offset
)

@Serializable
data class ProgressPushBody(val items: List<ProgressItemDto>)

@Serializable
data class ProgressPushResultDto(
    val document: DocumentIdDto,
    val status: String, // "accepted" | "stale"
    @SerialName("server_client_updated_at") val serverClientUpdatedAt: String,
)

@Serializable
data class ProgressPushResponse(val results: List<ProgressPushResultDto>)

@Serializable
data class ProgressPullResponse(
    val items: List<ProgressItemDto>,
    @SerialName("server_time") val serverTime: String,
)
```

- [ ] **Step 2: Write the failing serialization test `ProgressDtosTest.kt`**

```kotlin
package io.theficos.ereader.data.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

class ProgressDtosTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun `push body round-trips`() {
        val body = ProgressPushBody(
            items = listOf(
                ProgressItemDto(
                    document = DocumentIdDto(metadataId = "m1", contentHash = "h1"),
                    locator = "epubcfi(/6)",
                    percent = 0.42,
                    clientUpdatedAt = "2026-05-05T12:00:00+00:00",
                )
            )
        )
        val encoded = json.encodeToString(ProgressPushBody.serializer(), body)
        assertThat(encoded).contains("\"metadata_id\":\"m1\"")
        assertThat(encoded).contains("\"content_hash\":\"h1\"")
        assertThat(encoded).contains("\"client_updated_at\":\"2026-05-05T12:00:00+00:00\"")
        val decoded = json.decodeFromString(ProgressPushBody.serializer(), encoded)
        assertThat(decoded).isEqualTo(body)
    }

    @Test fun `pull response decodes`() {
        val raw = """{"items":[{"document":{"metadata_id":null,"content_hash":"h"},"locator":"l","percent":0.1,"client_updated_at":"2026-05-05T12:00:00+00:00"}],"server_time":"2026-05-05T12:00:01+00:00"}"""
        val r = json.decodeFromString(ProgressPullResponse.serializer(), raw)
        assertThat(r.items).hasSize(1)
        assertThat(r.serverTime).isEqualTo("2026-05-05T12:00:01+00:00")
    }
}
```

- [ ] **Step 3: Run test — verify it fails (compilation only — DTOs are defined, but `kotlinx.serialization` plugin must be active on the module). It should pass given the build.gradle from Task 1.

```sh
./scripts/dgradle :data:sync:test --tests ProgressDtosTest
```

Expected: PASS (the test was actually written *with* the dependency in place).

- [ ] **Step 4: Write `SyncApi.kt` + `SyncResult.kt`**

`SyncResult.kt`:

```kotlin
package io.theficos.ereader.data.sync

sealed interface SyncResult<out T> {
    data class Success<T>(val value: T) : SyncResult<T>
    data object Unauthorized : SyncResult<Nothing>
    data class HttpFailure(val code: Int, val body: String) : SyncResult<Nothing>
    data class NetworkFailure(val cause: Throwable) : SyncResult<Nothing>
}
```

`SyncApi.kt`:

```kotlin
package io.theficos.ereader.data.sync

object SyncApi {
    const val PATH_PROGRESS_PUSH = "/sync/v1/progress"
    const val PATH_PROGRESS_PULL = "/sync/v1/progress"
    const val PATH_HEALTHZ = "/sync/v1/healthz"
}
```

- [ ] **Step 5: Write `SyncClient.kt`**

```kotlin
package io.theficos.ereader.data.sync

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class SyncClient(
    private val baseUrl: String,    // e.g. https://sync.theficos.dedyn.io
    private val okHttp: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    suspend fun pushProgress(body: ProgressPushBody): SyncResult<ProgressPushResponse> =
        post(SyncApi.PATH_PROGRESS_PUSH, body, ProgressPushBody.serializer(), ProgressPushResponse.serializer())

    suspend fun pullProgress(sinceIso8601: String): SyncResult<ProgressPullResponse> {
        val url = (baseUrl.trimEnd('/') + SyncApi.PATH_PROGRESS_PULL).toHttpUrl()
            .newBuilder().addQueryParameter("since", sinceIso8601).build()
        val req = Request.Builder().url(url).get().build()
        return execute(req, ProgressPullResponse.serializer())
    }

    private fun <Req, Resp> post(
        path: String,
        body: Req,
        reqSerializer: KSerializer<Req>,
        respSerializer: KSerializer<Resp>,
    ): SyncResult<Resp> {
        val payload = json.encodeToString(reqSerializer, body)
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .post(payload)
            .build()
        return execute(req, respSerializer)
    }

    private fun <T> execute(req: Request, serializer: KSerializer<T>): SyncResult<T> = try {
        okHttp.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            when {
                resp.code == 401 -> SyncResult.Unauthorized
                resp.isSuccessful -> SyncResult.Success(json.decodeFromString(serializer, raw))
                else -> SyncResult.HttpFailure(resp.code, raw)
            }
        }
    } catch (e: IOException) {
        SyncResult.NetworkFailure(e)
    }
}
```

- [ ] **Step 6: Write `SyncClientTest.kt`**

```kotlin
package io.theficos.ereader.data.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class SyncClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: SyncClient

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        client = SyncClient(baseUrl = server.url("/").toString().trimEnd('/'), okHttp = OkHttpClient())
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun `push returns success on 200`() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"results":[{"document":{"metadata_id":"m","content_hash":"h"},"status":"accepted","server_client_updated_at":"2026-05-05T12:00:00+00:00"}]}"""
        ))
        val r = client.pushProgress(ProgressPushBody(listOf(
            ProgressItemDto(DocumentIdDto("m", "h"), "loc", 0.1, "2026-05-05T12:00:00+00:00")
        )))
        check(r is SyncResult.Success)
        assertThat(r.value.results).hasSize(1)
    }

    @Test fun `pull returns Unauthorized on 401`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        val r = client.pullProgress("2026-01-01T00:00:00Z")
        assertThat(r).isInstanceOf(SyncResult.Unauthorized::class.java)
    }
}
```

- [ ] **Step 7: Run the tests**

```sh
./scripts/dgradle :data:sync:test
```

Expected: PASS for both tests.

- [ ] **Step 8: Commit**

```bash
git add data/sync/src/main/java/io/theficos/ereader/data/sync/ProgressDtos.kt data/sync/src/main/java/io/theficos/ereader/data/sync/SyncApi.kt data/sync/src/main/java/io/theficos/ereader/data/sync/SyncClient.kt data/sync/src/main/java/io/theficos/ereader/data/sync/SyncResult.kt data/sync/src/test/java/io/theficos/ereader/data/sync/ProgressDtosTest.kt data/sync/src/test/java/io/theficos/ereader/data/sync/SyncClientTest.kt
git commit -m ":sparkles: feat(sync): SyncClient + DTOs (push/pull progress)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 14: Room migration v2 → v3 (add `localUpdatedAt`, `syncedAt`, `sync_state`)

**Files:**
- Modify: `data/local/src/main/java/io/theficos/ereader/data/local/db/ProgressEntity.kt`
- Modify: `data/local/src/main/java/io/theficos/ereader/data/local/db/ProgressDao.kt`
- Create: `data/local/src/main/java/io/theficos/ereader/data/local/db/SyncStateEntity.kt`
- Create: `data/local/src/main/java/io/theficos/ereader/data/local/db/SyncStateDao.kt`
- Modify: `data/local/src/main/java/io/theficos/ereader/data/local/db/EReaderDatabase.kt`
- Modify: `data/local/src/main/java/io/theficos/ereader/data/local/ProgressRepository.kt`
- Create: `data/local/src/test/java/io/theficos/ereader/data/local/db/MigrationTest.kt`
- Create: `data/local/src/test/java/io/theficos/ereader/data/local/db/SyncStateDaoTest.kt`
- Modify: `data/local/src/test/java/io/theficos/ereader/data/local/db/ProgressDaoTest.kt`

- [ ] **Step 1: Update `ProgressEntity.kt`**

```kotlin
package io.theficos.ereader.data.local.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "progress",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("documentId", unique = true)],
)
data class ProgressEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentId: Long,
    val locator: String,
    val percent: Double,
    val updatedAt: Long,
    val localUpdatedAt: Long,
    val syncedAt: Long,
)
```

- [ ] **Step 2: Update `ProgressDao.kt`**

```kotlin
package io.theficos.ereader.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ProgressDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(progress: ProgressEntity)

    @Query("SELECT * FROM progress WHERE documentId = :docId LIMIT 1")
    suspend fun findByDocument(docId: Long): ProgressEntity?

    @Query("SELECT * FROM progress WHERE documentId = :docId LIMIT 1")
    fun observeByDocument(docId: Long): Flow<ProgressEntity?>

    @Query("SELECT * FROM progress WHERE localUpdatedAt > syncedAt")
    suspend fun dirty(): List<ProgressEntity>

    @Query("UPDATE progress SET syncedAt = :syncedAt WHERE documentId = :documentId")
    suspend fun markSynced(documentId: Long, syncedAt: Long)
}
```

- [ ] **Step 3: Create `SyncStateEntity.kt`**

```kotlin
package io.theficos.ereader.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val tableName: String,
    val lastPulledAt: Long,
)
```

- [ ] **Step 4: Create `SyncStateDao.kt`**

```kotlin
package io.theficos.ereader.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SyncStateDao {
    @Query("SELECT lastPulledAt FROM sync_state WHERE tableName = :tableName")
    suspend fun lastPulled(tableName: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun set(state: SyncStateEntity)
}
```

- [ ] **Step 5: Update `EReaderDatabase.kt`**

```kotlin
package io.theficos.ereader.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [DocumentEntity::class, ProgressEntity::class, SyncStateEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class EReaderDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao
    abstract fun progressDao(): ProgressDao
    abstract fun syncStateDao(): SyncStateDao

    companion object {
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE documents ADD COLUMN coverPath TEXT")
            }
        }

        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE progress ADD COLUMN localUpdatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE progress ADD COLUMN syncedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE progress SET localUpdatedAt = updatedAt")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS sync_state (" +
                        "tableName TEXT NOT NULL PRIMARY KEY, " +
                        "lastPulledAt INTEGER NOT NULL)"
                )
            }
        }

        fun build(context: Context): EReaderDatabase =
            Room.databaseBuilder(context, EReaderDatabase::class.java, "ereader.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
```

- [ ] **Step 6: Update `ProgressRepository.kt` to bump `localUpdatedAt` on save**

```kotlin
package io.theficos.ereader.data.local

import io.theficos.ereader.core.model.Progress
import io.theficos.ereader.data.local.db.ProgressDao
import io.theficos.ereader.data.local.db.ProgressEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ProgressRepository(private val dao: ProgressDao) {
    suspend fun get(documentId: Long): Progress? =
        dao.findByDocument(documentId)?.toDomain()

    fun observe(documentId: Long): Flow<Progress?> =
        dao.observeByDocument(documentId).map { it?.toDomain() }

    suspend fun save(progress: Progress) {
        val now = System.currentTimeMillis()
        dao.upsert(ProgressEntity(
            documentId = progress.documentId,
            locator = progress.locator,
            percent = progress.percent,
            updatedAt = progress.updatedAt,
            localUpdatedAt = now,
            syncedAt = 0L,
        ))
    }

    suspend fun dirty(): List<Progress> = dao.dirty().map { it.toDomain() }

    suspend fun markSynced(documentId: Long, syncedAt: Long) =
        dao.markSynced(documentId, syncedAt)

    private fun ProgressEntity.toDomain(): Progress =
        Progress(documentId = documentId, locator = locator, percent = percent, updatedAt = updatedAt)
}
```

- [ ] **Step 7: Update existing `ProgressDaoTest.kt` to populate the new fields**

Replace the existing `ProgressDaoTest.kt` with one that supplies `localUpdatedAt` + `syncedAt`:

```kotlin
package io.theficos.ereader.data.local.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProgressDaoTest {
    private lateinit var db: EReaderDatabase
    private lateinit var docs: DocumentDao
    private lateinit var dao: ProgressDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), EReaderDatabase::class.java
        ).allowMainThreadQueries().build()
        docs = db.documentDao()
        dao = db.progressDao()
    }

    @After fun tearDown() { db.close() }

    private fun newDoc(): Long = kotlinx.coroutines.runBlocking {
        docs.insert(DocumentEntity(metadataId = null, contentHash = "h", title = "t", author = null, downloadUrl = "u", localPath = "p", coverPath = null, downloadedAt = 0))
    }

    @Test fun `upsert replaces previous progress for same document`() = runTest {
        val docId = newDoc()
        dao.upsert(ProgressEntity(documentId = docId, locator = "loc1", percent = 0.1, updatedAt = 1, localUpdatedAt = 1, syncedAt = 0))
        dao.upsert(ProgressEntity(documentId = docId, locator = "loc2", percent = 0.5, updatedAt = 2, localUpdatedAt = 2, syncedAt = 0))
        val found = dao.findByDocument(docId)
        assertThat(found?.locator).isEqualTo("loc2")
    }

    @Test fun `dirty returns rows where localUpdatedAt greater than syncedAt`() = runTest {
        val a = newDoc()
        val b = kotlinx.coroutines.runBlocking { docs.insert(DocumentEntity(metadataId = null, contentHash = "h2", title = "t2", author = null, downloadUrl = "u", localPath = "p", coverPath = null, downloadedAt = 0)) }
        dao.upsert(ProgressEntity(documentId = a, locator = "x", percent = 0.1, updatedAt = 1, localUpdatedAt = 5, syncedAt = 5)) // not dirty
        dao.upsert(ProgressEntity(documentId = b, locator = "y", percent = 0.2, updatedAt = 1, localUpdatedAt = 6, syncedAt = 5)) // dirty
        val dirty = dao.dirty()
        assertThat(dirty.map { it.documentId }).containsExactly(b)
    }

    @Test fun `markSynced sets syncedAt`() = runTest {
        val docId = newDoc()
        dao.upsert(ProgressEntity(documentId = docId, locator = "x", percent = 0.1, updatedAt = 1, localUpdatedAt = 5, syncedAt = 0))
        dao.markSynced(docId, 5)
        val found = dao.findByDocument(docId)
        assertThat(found?.syncedAt).isEqualTo(5)
    }

    @Test fun `flow emits updates`() = runTest {
        val docId = newDoc()
        dao.observeByDocument(docId).test {
            assertThat(awaitItem()).isNull()
            dao.upsert(ProgressEntity(documentId = docId, locator = "x", percent = 0.2, updatedAt = 1, localUpdatedAt = 1, syncedAt = 0))
            assertThat(awaitItem()?.locator).isEqualTo("x")
            cancelAndIgnoreRemainingEvents()
        }
    }
}
```

- [ ] **Step 8: Write `SyncStateDaoTest.kt`**

```kotlin
package io.theficos.ereader.data.local.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SyncStateDaoTest {
    private lateinit var db: EReaderDatabase
    private lateinit var dao: SyncStateDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), EReaderDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.syncStateDao()
    }

    @After fun tearDown() { db.close() }

    @Test fun `lastPulled is null when unset`() = runTest {
        assertThat(dao.lastPulled("progress")).isNull()
    }

    @Test fun `set then read round-trips`() = runTest {
        dao.set(SyncStateEntity("progress", 12345L))
        assertThat(dao.lastPulled("progress")).isEqualTo(12345L)
    }

    @Test fun `set replaces existing`() = runTest {
        dao.set(SyncStateEntity("progress", 1L))
        dao.set(SyncStateEntity("progress", 2L))
        assertThat(dao.lastPulled("progress")).isEqualTo(2L)
    }
}
```

- [ ] **Step 9: Write a Room migration test `MigrationTest.kt`**

```kotlin
package io.theficos.ereader.data.local.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        EReaderDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun `migrate 2 to 3 backfills localUpdatedAt and creates sync_state`() {
        helper.createDatabase(DB, 2).use { db ->
            db.execSQL(
                "INSERT INTO documents (id, metadataId, contentHash, title, author, downloadUrl, localPath, coverPath, downloadedAt) " +
                    "VALUES (1, NULL, 'h', 't', NULL, 'u', 'p', NULL, 0)"
            )
            db.execSQL(
                "INSERT INTO progress (id, documentId, locator, percent, updatedAt) VALUES (1, 1, 'loc', 0.5, 42)"
            )
        }

        helper.runMigrationsAndValidate(DB, 3, true, EReaderDatabase.MIGRATION_2_3).use { db ->
            db.query("SELECT localUpdatedAt, syncedAt FROM progress WHERE id=1").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getLong(0)).isEqualTo(42L)
                assertThat(c.getLong(1)).isEqualTo(0L)
            }
            db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='sync_state'").use { c ->
                assertThat(c.moveToFirst()).isTrue()
            }
        }
    }

    private companion object { const val DB = "migration-test.db" }
}
```

- [ ] **Step 10: Run all `:data:local` tests**

```sh
./scripts/dgradle :data:local:test
```

Expected: PASS for `ProgressDaoTest`, `SyncStateDaoTest`, `MigrationTest`, and the existing `DocumentDaoTest`.

- [ ] **Step 11: Commit**

```bash
git add data/local
git commit -m ":sparkles: feat(data): Room migration v2→v3 (sync columns + sync_state)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 15: `SyncOrchestrator`

**Files:**
- Create: `data/sync/src/main/java/io/theficos/ereader/data/sync/SyncOrchestrator.kt`
- Create: `data/sync/src/test/java/io/theficos/ereader/data/sync/SyncOrchestratorTest.kt`

The orchestrator is push-then-pull. The push reads dirty rows from `ProgressRepository`; the pull writes back via `progressDao.upsert(...)`. To keep the orchestrator focused, document lookup (Room `documentDao`) is its own collaborator, and we *don't* mutate document metadata during sync (server stores identity-only by spec §6.2 decision C).

- [ ] **Step 1: Write the orchestrator**

```kotlin
package io.theficos.ereader.data.sync

import io.theficos.ereader.core.identity.DocumentIdentity
import io.theficos.ereader.core.model.Progress
import io.theficos.ereader.data.local.DocumentRepository
import io.theficos.ereader.data.local.ProgressRepository
import io.theficos.ereader.data.local.db.ProgressDao
import io.theficos.ereader.data.local.db.ProgressEntity
import io.theficos.ereader.data.local.db.SyncStateDao
import io.theficos.ereader.data.local.db.SyncStateEntity
import java.time.Instant

class SyncOrchestrator(
    private val client: SyncClient,
    private val progressRepo: ProgressRepository,
    private val progressDao: ProgressDao,
    private val documentRepo: DocumentRepository,
    private val syncState: SyncStateDao,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    /** Returns Success on full happy path, otherwise the first non-success result encountered. */
    suspend fun runOnce(): SyncResult<Unit> {
        // 1) PUSH dirty rows.
        val dirty = progressRepo.dirty()
        if (dirty.isNotEmpty()) {
            val items = dirty.map { progress ->
                val doc = documentRepo.findById(progress.documentId)
                    ?: return SyncResult.HttpFailure(0, "missing document for documentId=${progress.documentId}")
                ProgressItemDto(
                    document = DocumentIdDto(metadataId = doc.identity.metadataId, contentHash = doc.identity.contentHash),
                    locator = progress.locator,
                    percent = progress.percent,
                    clientUpdatedAt = Instant.ofEpochMilli(progress.updatedAt).toString(),
                )
            }
            when (val res = client.pushProgress(ProgressPushBody(items))) {
                is SyncResult.Success -> {
                    res.value.results.zip(dirty).forEach { (r, p) ->
                        // Mark syncedAt to the server's authoritative timestamp; if older, the row stays dirty
                        // and we'll re-push next time (acceptable for record-level LWW progress).
                        if (r.status == "accepted") {
                            progressRepo.markSynced(p.documentId, syncedAt = nowMillis())
                        } else {
                            // server kept its own value — we treat our local as superseded
                            progressRepo.markSynced(p.documentId, syncedAt = nowMillis())
                        }
                    }
                }
                else -> return res.asUnit()
            }
        }

        // 2) PULL deltas.
        val sinceMs = syncState.lastPulled(SYNC_TABLE) ?: 0L
        val sinceIso = Instant.ofEpochMilli(sinceMs).toString()
        val pulled = client.pullProgress(sinceIso)
        return when (pulled) {
            is SyncResult.Success -> {
                val response = pulled.value
                response.items.forEach { item ->
                    applyPulled(item)
                }
                val serverEpoch = Instant.parse(response.serverTime).toEpochMilli()
                syncState.set(SyncStateEntity(SYNC_TABLE, serverEpoch))
                SyncResult.Success(Unit)
            }
            else -> pulled.asUnit()
        }
    }

    private suspend fun applyPulled(item: ProgressItemDto) {
        val identity = DocumentIdentity(metadataId = item.document.metadataId, contentHash = item.document.contentHash)
        val doc = documentRepo.findByIdentity(identity) ?: return // we don't have the EPUB locally; ignore
        val incomingUpdatedAt = Instant.parse(item.clientUpdatedAt).toEpochMilli()
        val existing = progressDao.findByDocument(doc.id)
        if (existing != null && existing.localUpdatedAt >= incomingUpdatedAt) {
            // Local is newer or equal; keep local. Next push will carry it.
            return
        }
        progressDao.upsert(
            ProgressEntity(
                id = existing?.id ?: 0L,
                documentId = doc.id,
                locator = item.locator,
                percent = item.percent,
                updatedAt = incomingUpdatedAt,
                localUpdatedAt = incomingUpdatedAt,
                syncedAt = incomingUpdatedAt, // pulled rows are not "dirty"
            )
        )
    }

    private fun <T> SyncResult<T>.asUnit(): SyncResult<Unit> = when (this) {
        is SyncResult.Success -> SyncResult.Success(Unit)
        is SyncResult.Unauthorized -> this
        is SyncResult.HttpFailure -> this
        is SyncResult.NetworkFailure -> this
    }

    private companion object { const val SYNC_TABLE = "progress" }
}
```

**Note for the implementer:**
- `DocumentIdentity` lives in `:core:identity` (`io.theficos.ereader.core.identity`).
- `DocumentRepository.findById(id)` and `findByIdentity(identity)` exist (per the existing `:data:local` code referenced in §3 of the spec). Re-check signatures during implementation; if `findByIdentity` is private/internal, expose it `public` rather than reimplementing.

- [ ] **Step 2: Write `SyncOrchestratorTest.kt`** — exercises push happy path + pull happy path with MockWebServer + an in-memory Room DB.

```kotlin
package io.theficos.ereader.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.core.identity.DocumentIdentity
import io.theficos.ereader.core.model.Progress
import io.theficos.ereader.data.local.DocumentRepository
import io.theficos.ereader.data.local.ProgressRepository
import io.theficos.ereader.data.local.db.DocumentEntity
import io.theficos.ereader.data.local.db.EReaderDatabase
import io.theficos.ereader.data.local.db.SyncStateEntity
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SyncOrchestratorTest {
    private lateinit var server: MockWebServer
    private lateinit var db: EReaderDatabase
    private lateinit var orchestrator: SyncOrchestrator
    private lateinit var docs: DocumentRepository
    private lateinit var progress: ProgressRepository

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), EReaderDatabase::class.java
        ).allowMainThreadQueries().build()
        docs = DocumentRepository(db.documentDao())
        progress = ProgressRepository(db.progressDao())
        orchestrator = SyncOrchestrator(
            client = SyncClient(server.url("/").toString().trimEnd('/'), OkHttpClient()),
            progressRepo = progress,
            progressDao = db.progressDao(),
            documentRepo = docs,
            syncState = db.syncStateDao(),
            nowMillis = { 100L },
        )
    }

    @After fun tearDown() { db.close(); server.shutdown() }

    private suspend fun seedDoc(metadataId: String?, hash: String): Long {
        return db.documentDao().insert(DocumentEntity(
            metadataId = metadataId, contentHash = hash, title = "t", author = null,
            downloadUrl = "u", localPath = "p", coverPath = null, downloadedAt = 0,
        ))
    }

    @Test fun `push then pull happy path`() = runTest {
        val docId = seedDoc(metadataId = "m", hash = "h")
        progress.save(Progress(documentId = docId, locator = "loc1", percent = 0.5, updatedAt = 50L))

        // server accepts push
        server.enqueue(MockResponse().setBody(
            """{"results":[{"document":{"metadata_id":"m","content_hash":"h"},"status":"accepted","server_client_updated_at":"1970-01-01T00:00:00.050+00:00"}]}"""
        ))
        // server returns no new pull items, server_time = 200ms
        server.enqueue(MockResponse().setBody(
            """{"items":[],"server_time":"1970-01-01T00:00:00.200+00:00"}"""
        ))

        val result = orchestrator.runOnce()
        assertThat(result).isInstanceOf(SyncResult.Success::class.java)

        // dirty list should now be empty
        assertThat(progress.dirty()).isEmpty()
        // sync_state advanced
        assertThat(db.syncStateDao().lastPulled("progress")).isEqualTo(200L)
    }

    @Test fun `pull writes server progress when local is older`() = runTest {
        val docId = seedDoc(metadataId = "m", hash = "h")

        // first call: nothing to push; assume push request still happens with empty list.
        // SyncOrchestrator only POSTs when dirty isn't empty — so only one server request: the GET pull.
        server.enqueue(MockResponse().setBody(
            """{"items":[{"document":{"metadata_id":"m","content_hash":"h"},"locator":"server-loc","percent":0.7,"client_updated_at":"1970-01-01T00:00:00.500+00:00"}],"server_time":"1970-01-01T00:00:00.600+00:00"}"""
        ))

        val result = orchestrator.runOnce()
        assertThat(result).isInstanceOf(SyncResult.Success::class.java)

        val saved = progress.get(docId)
        assertThat(saved?.locator).isEqualTo("server-loc")
        assertThat(saved?.updatedAt).isEqualTo(500L)
    }

    @Test fun `unauthorized stops the pipeline`() = runTest {
        val docId = seedDoc(metadataId = "m", hash = "h")
        progress.save(Progress(documentId = docId, locator = "loc1", percent = 0.5, updatedAt = 50L))
        server.enqueue(MockResponse().setResponseCode(401))
        val result = orchestrator.runOnce()
        assertThat(result).isInstanceOf(SyncResult.Unauthorized::class.java)
    }
}
```

- [ ] **Step 3: Run the test**

```sh
./scripts/dgradle :data:sync:test --tests SyncOrchestratorTest
```

Expected: PASS.

If `DocumentRepository.findByIdentity` does not exist with that exact name, expose it now and rerun. Confirm the symbol with:

```sh
grep -rn "findByIdentity\|findByMetadataId\|findByContentHash" data/local/src/main/java/io/theficos/ereader/data/local/
```

If only `findByMetadataId` and `findByContentHash` exist on the DAO and the repository, add a `findByIdentity(identity: DocumentIdentity): Document?` method on `DocumentRepository` that applies the spec §5.4 lookup precedence and re-run.

- [ ] **Step 4: Commit**

```bash
git add data/sync/src/main/java/io/theficos/ereader/data/sync/SyncOrchestrator.kt data/sync/src/test/java/io/theficos/ereader/data/sync/SyncOrchestratorTest.kt
# also commit any DocumentRepository changes from Step 3 if needed:
git add data/local/src/main/java/io/theficos/ereader/data/local/DocumentRepository.kt 2>/dev/null || true
git commit -m ":sparkles: feat(sync): SyncOrchestrator (push-then-pull)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 16: `SyncWorker` (CoroutineWorker)

**Files:**
- Create: `data/sync/src/main/java/io/theficos/ereader/data/sync/SyncWorker.kt`

This task wraps `SyncOrchestrator` in a `CoroutineWorker`. The worker resolves its dependencies via a static `SyncDependencies` holder set up at app boot (DI is intentionally minimal in this codebase — see `AppContainer`).

- [ ] **Step 1: Write the worker**

```kotlin
package io.theficos.ereader.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters

class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val deps = SyncDependencies.holder ?: return Result.failure()
        return when (val res = deps.orchestrator.runOnce()) {
            is SyncResult.Success -> Result.success()
            is SyncResult.NetworkFailure -> Result.retry()
            is SyncResult.HttpFailure -> Result.retry()
            is SyncResult.Unauthorized -> Result.failure() // user must re-auth
        }
    }
}

object SyncDependencies {
    @Volatile var holder: Holder? = null
    data class Holder(val orchestrator: SyncOrchestrator)
}

object SyncEnqueuer {
    private const val UNIQUE_NAME = "quire-progress-sync"

    fun enqueue(context: Context, expedited: Boolean = false) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .apply { if (expedited) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST) }
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, req)
    }
}
```

- [ ] **Step 2: Compile-check**

```sh
./scripts/dgradle :data:sync:assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add data/sync/src/main/java/io/theficos/ereader/data/sync/SyncWorker.kt
git commit -m ":sparkles: feat(sync): SyncWorker + enqueue helpers

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 17: Wire `:data:sync` + Authentik into `AppContainer`

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/java/io/theficos/ereader/di/AppContainer.kt`
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: `app/build.gradle.kts`**: add the `:data:sync` dep and the AppAuth manifest placeholder.

In the `defaultConfig {}` block add:

```kotlin
        manifestPlaceholders["appAuthRedirectScheme"] = "quire"
        buildConfigField("String", "SYNC_BASE_URL", "\"https://sync.theficos.dedyn.io\"")
        buildConfigField("String", "AUTHENTIK_ISSUER", "\"https://auth.theficos.dedyn.io/application/o/quire/\"")
        buildConfigField("String", "AUTHENTIK_CLIENT_ID", "\"quire\"")
```

Enable BuildConfig (Compose-enabled module needs it explicit):

```kotlin
    buildFeatures { compose = true; buildConfig = true }
```

Add to `dependencies {}`:

```kotlin
    implementation(project(":data:sync"))
    implementation(libs.work.runtime.ktx)
```

- [ ] **Step 2: `app/src/main/AndroidManifest.xml`**: AppAuth's `RedirectUriReceiverActivity` is auto-merged from the AppAuth library; we just need to declare the scheme via the manifest placeholder. Verify the line below appears (typically inside `<application>`); if your existing manifest doesn't reference appauth at all, add:

```xml
        <activity
            android:name="net.openid.appauth.RedirectUriReceiverActivity"
            android:exported="true"
            android:theme="@android:style/Theme.NoDisplay"
            tools:node="merge">
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="${appAuthRedirectScheme}" />
            </intent-filter>
        </activity>
```

(This is in addition to whatever exists; ensure `xmlns:tools="http://schemas.android.com/tools"` is declared on the root `<manifest>` element.)

- [ ] **Step 3: `AppContainer.kt` — add fields for Authentik + sync**

Replace with:

```kotlin
package io.theficos.ereader.di

import android.content.Context
import io.theficos.ereader.BuildConfig
import io.theficos.ereader.auth.AuthentikAuthenticator
import io.theficos.ereader.auth.AuthentikConfig
import io.theficos.ereader.auth.AuthTokenStore
import io.theficos.ereader.auth.AuthTokens
import io.theficos.ereader.auth.CalibreCredentialStore
import io.theficos.ereader.auth.SyncAuthInterceptor
import io.theficos.ereader.auth.TokenProvider
import io.theficos.ereader.data.local.DocumentRepository
import io.theficos.ereader.data.local.ProgressRepository
import io.theficos.ereader.data.local.db.EReaderDatabase
import io.theficos.ereader.data.opds.BookDownloader
import io.theficos.ereader.data.opds.OpdsClient
import io.theficos.ereader.data.opds.OpdsHttpClient
import io.theficos.ereader.data.sync.SyncClient
import io.theficos.ereader.data.sync.SyncDependencies
import io.theficos.ereader.data.sync.SyncOrchestrator
import io.theficos.ereader.reader.ReaderPreferencesStore
import io.theficos.ereader.reader.ReadiumFactory
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.File

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val credentialStore: CalibreCredentialStore = CalibreCredentialStore(appContext)
    val authTokenStore: AuthTokenStore = AuthTokenStore(appContext)

    private val authentikConfig = AuthentikConfig(
        issuer = BuildConfig.AUTHENTIK_ISSUER,
        clientId = BuildConfig.AUTHENTIK_CLIENT_ID,
    )
    val authentikAuthenticator = AuthentikAuthenticator(appContext, authentikConfig, authTokenStore)

    private val tokenProvider: TokenProvider = object : TokenProvider {
        override fun currentAccessToken(): String? = authTokenStore.read()?.accessToken
        override fun refreshSync(): String? = runBlocking { authentikAuthenticator.refresh() }
        override fun clearTokens() { authTokenStore.clear() }
    }

    private val opdsHttp = OpdsHttpClient(credentialStore)
    val opdsClient: OpdsClient = OpdsClient(opdsHttp.okHttp)
    val bookDownloader: BookDownloader = BookDownloader(
        okHttp = opdsHttp.okHttp,
        booksDir = File(appContext.filesDir, "books"),
    )

    private val db: EReaderDatabase = EReaderDatabase.build(appContext)
    val documentRepository = DocumentRepository(db.documentDao())
    val progressRepository = ProgressRepository(db.progressDao())
    val readiumFactory = ReadiumFactory(appContext)
    val readerPreferencesStore = ReaderPreferencesStore(appContext)

    private val syncOkHttp: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(SyncAuthInterceptor(tokenProvider))
        .build()

    val syncClient: SyncClient = SyncClient(BuildConfig.SYNC_BASE_URL, syncOkHttp)
    val syncOrchestrator: SyncOrchestrator = SyncOrchestrator(
        client = syncClient,
        progressRepo = progressRepository,
        progressDao = db.progressDao(),
        documentRepo = documentRepository,
        syncState = db.syncStateDao(),
    )

    init {
        SyncDependencies.holder = SyncDependencies.Holder(syncOrchestrator)
    }

    fun authState(): AuthSnapshot {
        val t = authTokenStore.read()
        return if (t == null) AuthSnapshot.SignedOut else AuthSnapshot.SignedIn(sub = t.sub, email = t.email)
    }
}

sealed interface AuthSnapshot {
    data object SignedOut : AuthSnapshot
    data class SignedIn(val sub: String, val email: String?) : AuthSnapshot
}
```

- [ ] **Step 4: Compile-check**

```sh
./scripts/dgradle :app:assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts app/src/main/AndroidManifest.xml app/src/main/java/io/theficos/ereader/di/AppContainer.kt
git commit -m ":wrench: feat(app): wire Authentik + sync into AppContainer

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 18: Settings UI — Sync section

**Files:**
- Modify: `app/src/main/java/io/theficos/ereader/ui/settings/SettingsViewModel.kt`
- Modify: `app/src/main/java/io/theficos/ereader/ui/settings/SettingsScreen.kt`
- Create: `app/src/main/java/io/theficos/ereader/ui/settings/SyncAuthLauncher.kt`
- Modify: `app/src/main/java/io/theficos/ereader/MainActivity.kt`

The challenge: AppAuth's authorization step requires an `Activity.startActivityForResult`. We'll use `ActivityResultContracts.StartActivityForResult` from a Composable via `rememberLauncherForActivityResult`, kicked off by a click on "Sign in".

- [ ] **Step 1: Update `SettingsViewModel.kt`**

Replace with:

```kotlin
package io.theficos.ereader.ui.settings

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.theficos.ereader.auth.AuthentikAuthenticator
import io.theficos.ereader.auth.AuthTokenStore
import io.theficos.ereader.auth.CalibreCredentialStore
import io.theficos.ereader.auth.CalibreCredentials
import io.theficos.ereader.data.sync.SyncEnqueuer
import io.theficos.ereader.di.AuthSnapshot
import io.theficos.ereader.reader.ReaderPreferences
import io.theficos.ereader.reader.ReaderPreferencesStore
import io.theficos.ereader.reader.ReaderTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import android.content.Context

data class SyncUiState(
    val account: AuthSnapshot = AuthSnapshot.SignedOut,
    val signingIn: Boolean = false,
    val syncing: Boolean = false,
    val errorMessage: String? = null,
)

class SettingsViewModel(
    private val store: CalibreCredentialStore,
    private val readerStore: ReaderPreferencesStore,
    private val authStore: AuthTokenStore,
    private val authenticator: AuthentikAuthenticator,
) : ViewModel() {
    private val _calibre = MutableStateFlow(loadInitialCalibre())
    val calibre: StateFlow<CalibreUiState> = _calibre.asStateFlow()

    val readerPreferences: StateFlow<ReaderPreferences> = readerStore.flow

    private val _sync = MutableStateFlow(SyncUiState(account = readAccount()))
    val sync: StateFlow<SyncUiState> = _sync.asStateFlow()

    private fun readAccount(): AuthSnapshot {
        val t = authStore.read() ?: return AuthSnapshot.SignedOut
        return AuthSnapshot.SignedIn(t.sub, t.email)
    }

    private fun loadInitialCalibre(): CalibreUiState {
        val creds = store.get()
        return CalibreUiState(
            baseUrl = creds?.baseUrl.orEmpty(),
            username = creds?.username.orEmpty(),
            password = creds?.password.orEmpty(),
            saved = creds != null,
        )
    }

    fun onBaseUrlChange(value: String) { _calibre.value = _calibre.value.copy(baseUrl = value, saved = false) }
    fun onUsernameChange(value: String) { _calibre.value = _calibre.value.copy(username = value, saved = false) }
    fun onPasswordChange(value: String) { _calibre.value = _calibre.value.copy(password = value, saved = false) }

    fun saveCalibre() {
        val s = _calibre.value
        if (s.baseUrl.isBlank() || s.username.isBlank() || s.password.isBlank()) return
        viewModelScope.launch {
            store.put(CalibreCredentials(s.baseUrl.trim().trimEnd('/'), s.username, s.password))
            _calibre.value = s.copy(saved = true)
        }
    }

    fun setFontScale(value: Double) { readerStore.update { it.copy(fontScale = value.coerceIn(0.5, 2.0)) } }
    fun setTheme(theme: ReaderTheme) { readerStore.update { it.copy(theme = theme) } }
    fun setFontFamily(family: io.theficos.ereader.reader.ReaderFontFamily) { readerStore.update { it.copy(fontFamily = family) } }
    fun setLineSpacing(value: Double) { readerStore.update { it.copy(lineSpacing = value.coerceIn(1.0, 1.8)) } }

    suspend fun buildSignInIntent(): Intent? = try {
        _sync.value = _sync.value.copy(signingIn = true, errorMessage = null)
        authenticator.buildAuthorizationIntent()
    } catch (e: Exception) {
        _sync.value = _sync.value.copy(signingIn = false, errorMessage = "Sign-in init failed: ${e.message}")
        null
    }

    fun onSignInResultData(data: Intent?) {
        if (data == null) {
            _sync.value = _sync.value.copy(signingIn = false, errorMessage = "Sign-in cancelled")
            return
        }
        viewModelScope.launch {
            try {
                authenticator.handleAuthorizationResponse(data)
                _sync.value = _sync.value.copy(signingIn = false, account = readAccount())
            } catch (e: Exception) {
                _sync.value = _sync.value.copy(signingIn = false, errorMessage = "Sign-in failed: ${e.message}")
            }
        }
    }

    fun signOut() {
        authStore.clear()
        _sync.value = _sync.value.copy(account = AuthSnapshot.SignedOut)
    }

    fun syncNow(context: Context) {
        SyncEnqueuer.enqueue(context, expedited = true)
    }
}

data class CalibreUiState(
    val baseUrl: String,
    val username: String,
    val password: String,
    val saved: Boolean,
)
```

- [ ] **Step 2: Update `MainActivity.kt`** to construct the ViewModel with the new collaborators (the existing `SettingsViewModel` factory in `AppNavGraph.kt` may need to be updated — the file path was found in Task 0). Find the existing factory and add the two new arguments:

```sh
grep -n "SettingsViewModel(" app/src/main/java/io/theficos/ereader/ui/AppNavGraph.kt
```

Then edit `AppNavGraph.kt` so the factory line becomes:

```kotlin
                SettingsViewModel(
                    store = appContainer.credentialStore,
                    readerStore = appContainer.readerPreferencesStore,
                    authStore = appContainer.authTokenStore,
                    authenticator = appContainer.authentikAuthenticator,
                )
```

- [ ] **Step 3: Add a Sync section to `SettingsScreen.kt`**

Insert this section after the existing "Reader defaults" `QuireCard` and before the "About" `SectionLabel`. Add the imports for `ActivityResultContracts`, `LocalContext`, and `rememberLauncherForActivityResult`:

```kotlin
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import io.theficos.ereader.di.AuthSnapshot
import kotlinx.coroutines.launch
```

The Sync section:

```kotlin
        SectionLabel("Sync")
        QuireCard(modifier = Modifier.fillMaxWidth()) {
            val syncState by viewModel.sync.collectAsState()
            val context = LocalContext.current
            val scope = rememberCoroutineScope()

            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK) viewModel.onSignInResultData(result.data)
                else viewModel.onSignInResultData(null)
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (val acc = syncState.account) {
                    is AuthSnapshot.SignedOut -> {
                        Text("Signed out", style = MaterialTheme.typography.bodyMedium)
                        Button(
                            onClick = {
                                scope.launch {
                                    val intent = viewModel.buildSignInIntent()
                                    if (intent != null) launcher.launch(intent)
                                }
                            },
                            enabled = !syncState.signingIn,
                        ) { Text(if (syncState.signingIn) "Signing in…" else "Sign in") }
                    }
                    is AuthSnapshot.SignedIn -> {
                        Text("Signed in as ${acc.email ?: acc.sub}", style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { viewModel.syncNow(context) }) { Text("Sync now") }
                            OutlinedButton(onClick = viewModel::signOut) { Text("Sign out") }
                        }
                    }
                }
                syncState.errorMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
```

- [ ] **Step 4: Compile-check**

```sh
./scripts/dgradle :app:assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/theficos/ereader/ui/settings app/src/main/java/io/theficos/ereader/ui/AppNavGraph.kt
git commit -m ":sparkles: feat(app): Settings → Sync section (sign-in / sign-out / sync now)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 19: Trigger sync from Library + Reader

**Files:**
- Modify: `app/src/main/java/io/theficos/ereader/ui/library/LibraryScreen.kt`
- Modify: `app/src/main/java/io/theficos/ereader/ui/reader/ReaderScreen.kt`

- [ ] **Step 1: Library — pull-on-resume**

In `LibraryScreen.kt`, near the top of the `LibraryScreen` Composable body, add (importing as needed):

```kotlin
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import io.theficos.ereader.data.sync.SyncEnqueuer
```

Inside the body:

```kotlin
val context = LocalContext.current
LaunchedEffect(Unit) { SyncEnqueuer.enqueue(context, expedited = true) }
```

- [ ] **Step 2: Reader — push-on-pause**

In `ReaderScreen.kt`, observe the lifecycle and enqueue a sync on `ON_PAUSE`. Add imports:

```kotlin
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.theficos.ereader.data.sync.SyncEnqueuer
```

Inside `ReaderScreen`:

```kotlin
val context = LocalContext.current
val lifecycleOwner = LocalLifecycleOwner.current
DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_PAUSE) {
            SyncEnqueuer.enqueue(context, expedited = true)
        }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
}
```

If `LocalLifecycleOwner` is unresolved, add `implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")` to `app/build.gradle.kts`.

- [ ] **Step 3: Compile-check**

```sh
./scripts/dgradle :app:assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/io/theficos/ereader/ui/library/LibraryScreen.kt app/src/main/java/io/theficos/ereader/ui/reader/ReaderScreen.kt app/build.gradle.kts
git commit -m ":sparkles: feat(app): trigger sync on library resume + reader pause

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 20: Operations doc — Authentik prerequisite + first deploy

**Files:**
- Create: `docs/operations/2026-05-05-phase-2-authentik-and-deploy.md`

- [ ] **Step 1: Write the doc**

```markdown
# Phase 2 — Authentik setup & first opds-sync deploy

## 1. Authentik OAuth2 application

In Authentik admin (`https://auth.theficos.dedyn.io`):

1. Providers → Create → OAuth2/OpenID Provider
   - Name: `quire`
   - Client type: **Public**
   - Client ID: `quire`
   - Authorization flow: default-provider-authorization-explicit-consent (or implicit)
   - Redirect URIs: `quire://oauth`
   - Required scopes: `openid`, `profile`, `email`, `offline_access`
   - Subject mode: `Based on the User's hashed ID` (default)
   - Issuer mode: per-provider (so `iss` claim is stable)

2. Applications → Create
   - Name: `Quire`
   - Slug: `quire`
   - Provider: the one above
   - Launch URL: empty
   - Audience: `quire`

Verify:

```sh
curl https://auth.theficos.dedyn.io/application/o/quire/.well-known/openid-configuration | jq '.issuer, .jwks_uri'
```

The `issuer` value goes into `OPDS_SYNC_AUTHENTIK_ISSUER`.

## 2. Build the server image

```sh
cd server
docker build -t ghcr.io/<owner>/opds-sync:$(git rev-parse --short HEAD) .
docker push ghcr.io/<owner>/opds-sync:<sha>
```

(CI does this on `main`; for first deploy you can do it manually.)

## 3. Apply manifests

```sh
cd deploy/k8s/opds-sync
cp secret.example.yaml secret.yaml
# edit values
sops --encrypt --in-place secret.yaml
sops --decrypt secret.yaml | kubectl apply -f -

# update kustomization.yaml `images.newName` to your GHCR namespace, then:
kubectl apply -k .
kubectl -n opds-sync rollout status deploy/opds-sync
curl https://sync.theficos.dedyn.io/sync/v1/healthz
```

Expected output: `{"status":"ok"}`.

## 4. Wire the Android app

The relevant `BuildConfig` constants are baked at compile time from `app/build.gradle.kts`:

- `SYNC_BASE_URL` — `https://sync.theficos.dedyn.io`
- `AUTHENTIK_ISSUER` — must match step 1's `iss` claim
- `AUTHENTIK_CLIENT_ID` — `quire`

Adjust if your domains differ; rebuild and install.
```

- [ ] **Step 2: Commit**

```bash
git add docs/operations/2026-05-05-phase-2-authentik-and-deploy.md
git commit -m ":memo: docs: phase 2 Authentik setup + opds-sync first deploy

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 21: End-to-end ship-gate validation

**No code changes.** This task verifies the gate from spec §9.

- [ ] **Step 1: Confirm CI is green**

```sh
./scripts/dgradle test
cd server && uv run pytest -v
```

Expected: all PASS.

- [ ] **Step 2: Deploy server**

Per `docs/operations/2026-05-05-phase-2-authentik-and-deploy.md`. Confirm:

```sh
curl https://sync.theficos.dedyn.io/sync/v1/healthz   # {"status":"ok"}
curl https://sync.theficos.dedyn.io/sync/v1/readyz    # {"status":"ready"}
```

- [ ] **Step 3: Two-device test**

On emulator A and emulator B (or one phone + one emulator), install `:app:installDebug`. Both sign in via Settings → Sync → Sign in (the same Authentik user).

1. On A: open a book, advance ~10%, close the book (back out to library — `onPause` on the reader fires).
2. On B: open Library tab. The `LaunchedEffect` enqueues a pull. Tap the same book; the reader resumes within ~1 page of A's position.
3. Force-close A. On B: advance to 50%, close the book. Force-foreground A, open library. A's progress now reads 50%.
4. On B: Settings → Sync → Sign out. Confirm:
   - Account row reads "Signed out".
   - The local progress remains visible in the Library (current behavior preserves local state on sign-out — the spec calls this out as intentional in §10).
5. On B: Sign in again with the same account, open Library — pull restores the latest server state.

- [ ] **Step 4: Negative — auth boundary**

Manually invalidate the Authentik public key (e.g., temporarily edit `OPDS_SYNC_AUTHENTIK_AUDIENCE` to a wrong value and rollout). Trigger Sync now from B. Expect:

- The worker returns `Result.failure()` (Unauthorized).
- A subsequent foreground refresh attempt clears tokens; the Settings UI shows "Signed out".

Restore correct config and rollout.

- [ ] **Step 5: Mark Phase 2 complete**

If all of the above pass, the Phase 2 ship gate is met. Tag the release:

```sh
git tag -a phase-2 -m "Phase 2: progress sync"
git push --tags
```

(Skip the tag push if not desired; the user owns release tagging.)

- [ ] **Step 6: Commit any verification artifacts (none expected)**

If notes were added to `docs/operations/`, commit them. Otherwise, no commit on this task.

---

## Self-review

This section was completed before handing the plan back to you.

### Spec coverage
- §1 in (1) FastAPI server: Tasks 2–8.
- §1 in (2) Kustomize manifests: Task 9.
- §1 in (3) Authentik OIDC+PKCE: Tasks 10–12.
- §1 in (4) `:data:sync` module: Tasks 1, 13, 15, 16.
- §1 in (5) `ProgressEntity` columns: Task 14.
- §1 in (6) `sync_state` table: Task 14.
- §1 in (7) WorkManager + Settings UI: Tasks 16–19.
- §1 in (8) Identity parity test: Task 3.
- §6 server design: Tasks 4 (schema), 5 (auth), 6 (health), 7 (progress endpoints).
- §7 client design: Tasks 10–19.
- §8 identity parity: Task 3.
- §9 ship gate: Task 21.
- Operations / Authentik prerequisite: Task 20.

### Placeholder scan
- No "TBD"/"TODO". Every step contains the actual code, command, or check.
- One known instructive note in Task 11 about `EpubPreferences`-style API drift was carried as a *runtime caveat in the previous plan* and is **not** present here. All Kotlin types referenced (`AuthState`, `AuthTokens`, `TokenProvider`, `SyncResult`, `SyncOrchestrator`, `SyncWorker`, `SyncEnqueuer`, `AuthSnapshot`) are defined in earlier tasks of this plan.

### Type/method consistency
- `AuthTokens` defined in Task 10, used in Task 11 (`persist`, `extractSubFromIdToken`).
- `TokenProvider` interface defined in Task 12, implemented in Task 17 (`AppContainer`).
- `SyncResult` sealed interface defined in Task 13, consumed in Tasks 13 (`SyncClient`), 15 (`SyncOrchestrator.runOnce`), 16 (`SyncWorker.doWork`).
- `progressDao.dirty()` and `progressDao.markSynced(documentId, syncedAt)` defined in Task 14, called in Task 15.
- `progressRepo.dirty()` and `markSynced(...)` defined in Task 14, called in Task 15.
- `documentRepo.findByIdentity(...)` is used in Task 15; if the existing repo doesn't expose it, Task 15 Step 3 explicitly tells the implementer to add it before re-running tests.
- `SyncEnqueuer.enqueue(context, expedited)` defined in Task 16, called in Tasks 18 (Settings UI) and 19 (Library/Reader triggers).
- `AuthSnapshot` defined in Task 17 (`AppContainer.kt`), consumed in Task 18 (`SettingsViewModel`, `SettingsScreen`).

### Scope check
- One implementation plan; one phase. No decomposition needed.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-05-05-phase-2-progress-sync.md`. Two execution options:

**1. Subagent-Driven (recommended)** — fresh subagent per task, review between tasks, fast iteration.

**2. Inline Execution** — execute tasks in this session using executing-plans, batch execution with checkpoints.

Which approach?
