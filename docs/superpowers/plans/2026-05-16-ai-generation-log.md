# `ai_generation_log` Table + Cache-Key Tenant Audit Implementation Plan

> Shipped in 1c17f51 on 2026-05-16 as PR #11.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce the `ai_generation_log` table on the `ai` alembic branch as the per-call tenant audit trail, codify the cache-integrity invariant as a regression test, fix PR-A test fallout from materializing the first `ai` branch, and stop reading `book_insights.generated_by` for any decision.

**Architecture:** A new `AIGenerationLog` SQLAlchemy model is migrated in `migrations/versions/ai_001_generation_log.py` (first migration on the `ai` branch, `down_revision = "0004"`, `branch_labels = ("ai",)` per PR-A's convention). The `InsightOrchestrator` writes one log row per `get()`-hit / `generate()` / `regenerate()` call, capturing `tenant_id` (per-call kwarg, default `"local"`), `subject` (the existing `user_id` arg), `request_id` (from PR-A's `request_id_var` ContextVar), `model_id`, `prompt_version`, `latency_ms`, and `status` (`hit`/`miss`). Errors emit a structured log line instead of a DB row. A parametrized integration test asserts no shared-cache table carries a tenant column. Test fixtures are adjusted to use the production-mode-aware migration wrapper.

**Tech Stack:** Python 3.12 · FastAPI · SQLAlchemy 2 (async) · Alembic · PostgreSQL 16 (testcontainers) · pytest / pytest-asyncio · uv

---

## File Map

| File | Action | Responsibility |
|------|--------|----------------|
| `server/migrations/versions/ai_001_generation_log.py` | Create | First labeled migration on `ai` branch; creates `ai_generation_log` table + indexes. |
| `server/opds_sync/db/models.py` | Modify | Add `AIGenerationLog` model; add cache-integrity invariant docstrings above `BookInsight` and `ExternalSourceCacheEntry`. |
| `server/opds_sync/core/ai/service.py` | Modify | Add per-call `tenant_id` kwarg on `get/generate/regenerate`; emit one log row per call via `_log_generation` helper; structured error log on AI client raise; mark `generated_by` write deprecated. |
| `server/opds_sync/api/ai.py` | Modify | Pass `user_id` + `tenant_id="local"` into all orchestrator calls (including `get()`). |
| `server/tests/conftest.py` | Modify | Replace `alembic_upgrade` fixture body with `scripts.migrate.run_migrations(cfg, progress_enabled=True, ai_enabled=True)`. |
| `server/tests/unit/conftest.py` | Modify | Add `ai_generation_log` to truncate list with `CASCADE`. |
| `server/tests/unit/test_ai_service.py` | Modify | Add 9 unit tests (hit/miss/get/coalesce/request-id/tenant default/tenant override/error-log). |
| `server/tests/integration/test_schema.py` | Modify | Add `test_ai_generation_log_table_exists` + `test_ai_generation_log_round_trip`. |
| `server/tests/integration/test_ai_audit_log.py` | Create | Two-tenant convergence test using **separate sessions**: one insight, two log rows, distinct tenants. |
| `server/tests/integration/test_cache_key_audit.py` | Create | Parametrized regression test asserting shared-cache tables carry no tenant columns. |
| `server/tests/integration/test_readyz_migration_state.py` | Modify | Update assertions: `["0004"]` → `["ai_001"]` for the "AI head" test; stamp to `0004` for the "neither mode" test. |

---

## Working directory

All paths below are relative to `/Users/vito/repos/quire/.claude/worktrees/pr-c-ai-generation-log` (the PR-C worktree).

All `pytest` / `alembic` invocations run from the `server/` subdirectory:

```bash
cd /Users/vito/repos/quire/.claude/worktrees/pr-c-ai-generation-log/server
uv run pytest ...
```

---

### Task 1: Migration `ai_001_generation_log` + introspection test

**Files:**
- Create: `server/migrations/versions/ai_001_generation_log.py`
- Modify: `server/tests/integration/test_schema.py`

- [ ] **Step 1: Write the failing test asserting the table exists after migrations**

Append to `server/tests/integration/test_schema.py`:

```python
import pytest
from sqlalchemy import inspect


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
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /Users/vito/repos/quire/.claude/worktrees/pr-c-ai-generation-log/server
uv run pytest tests/integration/test_schema.py::test_ai_generation_log_table_exists -v
```

Expected: FAIL with `NoSuchTableError` or `KeyError: 'ai_generation_log'`.

- [ ] **Step 3: Create the migration file**

Create `server/migrations/versions/ai_001_generation_log.py`:

```python
"""ai_001_generation_log: per-call tenant audit log keyed to book_insights.

First migration on the `ai` branch (per PR-A's branching convention).

Schema:
- ai_generation_log records one row per AI insight call (hit, miss; errors
  go to structured logs because they have no FK target). It is the future
  billing/audit substrate; the shared cache table `book_insights` stays
  tenant-blind.
- FK on book_insight_id is ON DELETE CASCADE: invalidating an insight cleans
  up its audit children.
- The check constraint accepts 'hit' | 'miss' | 'error' to remain forward-
  compatible with a future PR that introduces error rows (via nullable FK or
  sentinel rows). PR-C only emits 'hit' and 'miss'.

Revision ID: ai_001
Revises: 0004
Create Date: 2026-05-16 00:00:00.000000
"""

import sqlalchemy as sa
from alembic import op

revision = "ai_001"
down_revision = "0004"
branch_labels = ("ai",)
depends_on = None


def upgrade() -> None:
    op.create_table(
        "ai_generation_log",
        sa.Column("id", sa.BigInteger(), primary_key=True, autoincrement=True),
        sa.Column(
            "book_insight_id",
            sa.BigInteger(),
            sa.ForeignKey("book_insights.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column(
            "tenant_id",
            sa.String(),
            nullable=False,
            server_default=sa.text("'local'"),
        ),
        sa.Column("subject", sa.String(), nullable=False),
        sa.Column("request_id", sa.String(), nullable=True),
        sa.Column("model_id", sa.String(), nullable=False),
        sa.Column("prompt_version", sa.String(), nullable=False),
        sa.Column("latency_ms", sa.Integer(), nullable=True),
        sa.Column("status", sa.String(), nullable=False),
        sa.Column("error_class", sa.String(), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
        sa.CheckConstraint(
            "status IN ('hit', 'miss', 'error')",
            name="ck_ai_generation_log_status",
        ),
    )
    op.create_index(
        "ix_ai_generation_log_tenant_created",
        "ai_generation_log",
        ["tenant_id", "created_at"],
    )
    op.create_index(
        "ix_ai_generation_log_book_insight",
        "ai_generation_log",
        ["book_insight_id"],
    )


def downgrade() -> None:
    op.drop_index(
        "ix_ai_generation_log_book_insight", table_name="ai_generation_log"
    )
    op.drop_index(
        "ix_ai_generation_log_tenant_created", table_name="ai_generation_log"
    )
    op.drop_table("ai_generation_log")
```

- [ ] **Step 4: Verify alembic recognizes the branch**

```bash
cd /Users/vito/repos/quire/.claude/worktrees/pr-c-ai-generation-log/server
uv run alembic heads --verbose
```

Expected: `Rev: ai_001 (head)` with `Branch names: ai` and `Parent: 0004`.

- [ ] **Step 5: Run the introspection test to verify it passes**

```bash
uv run pytest tests/integration/test_schema.py::test_ai_generation_log_table_exists -v
```

Expected: PASS.

- [ ] **Step 6: Run the full test suite — expect some readyz failures now**

```bash
uv run pytest -q 2>&1 | tail -20
```

Expected: 2 failures in `test_readyz_migration_state.py` (heads_applied mismatch). These are addressed in Task 2. All other tests pass.

- [ ] **Step 7: Commit**

```bash
cd /Users/vito/repos/quire/.claude/worktrees/pr-c-ai-generation-log
git add server/migrations/versions/ai_001_generation_log.py server/tests/integration/test_schema.py
git commit -m ":sparkles: feat(server): ai_001 migration — ai_generation_log table"
```

---

### Task 2: Fix PR-A test fallout (conftest + readyz tests)

**Files:**
- Modify: `server/tests/conftest.py`
- Modify: `server/tests/integration/test_readyz_migration_state.py`

- [ ] **Step 1: Update `alembic_upgrade` fixture to use the mode-aware wrapper**

In `server/tests/conftest.py`, replace the `alembic_upgrade` fixture body:

```python
@pytest.fixture(scope="session")
def alembic_upgrade(postgres_url: str) -> None:
    # Use the production mode-aware wrapper instead of `alembic upgrade head`.
    # `head` is ambiguous once multiple branches exist; the wrapper handles
    # backbone + per-branch upgrades correctly. We pass both flags true so
    # the test schema reaches `ai@head` and (eventually) `progress@head`.
    cfg = AlembicConfig("alembic.ini")
    cfg.set_main_option("sqlalchemy.url", postgres_url)
    from scripts.migrate import run_migrations

    run_migrations(cfg, progress_enabled=True, ai_enabled=True)
```

- [ ] **Step 2: Update readyz assertions for `ai_001` head**

In `server/tests/integration/test_readyz_migration_state.py`:

(a) Rename and update `test_readyz_200_when_at_backbone_no_labels`:

```python
async def test_readyz_200_when_at_ai_head(monkeypatch, postgres_url, alembic_upgrade):
    """With ai_001 materialized and ai mode on, the required head is ai@head."""
    app = _build_app(monkeypatch, postgres_url, progress=True, ai=True)
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://t") as c:
        r = await c.get("/readyz")
    assert r.status_code == 200
    body = r.json()
    assert body["ready"] is True
    assert body["heads_applied"] == ["ai_001"]
```

(b) Update `test_readyz_200_with_neither_mode_at_backbone` to stamp the DB to `0004` before checking (the session fixture brings the DB up to `ai_001`, which is not the "neither mode at backbone" scenario this test is asserting):

```python
async def test_readyz_200_with_neither_mode_at_backbone(
    monkeypatch, postgres_url, alembic_upgrade, restore_after
):
    """With neither mode enabled, backbone tip (0004) is the only required head.

    Stamp the DB to 0004 explicitly because the session-scoped fixture brings
    everything up to ai@head; we want to exercise the "fresh sync-only deploy
    that never materialized the ai branch" code path.
    """
    await _stamp(postgres_url, "0004")
    app = _build_app(monkeypatch, postgres_url, progress=False, ai=False)
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://t") as c:
        r = await c.get("/readyz")
    assert r.status_code == 200
    body = r.json()
    assert body["modes"] == []
    assert body["heads_applied"] == ["0004"]
```

Note: `restore_after` already exists in the file and stamps back to `0004` post-test, so subsequent tests start clean.

- [ ] **Step 3: Run the readyz tests to verify they pass**

```bash
uv run pytest tests/integration/test_readyz_migration_state.py -v
```

Expected: all 4 readyz tests PASS.

- [ ] **Step 4: Run the full suite to confirm no regressions**

```bash
uv run pytest -q
```

Expected: 120 passed (119 prior + 1 new schema test).

- [ ] **Step 5: Commit**

```bash
git add server/tests/conftest.py server/tests/integration/test_readyz_migration_state.py
git commit -m ":white_check_mark: test(server): mode-aware migrate fixture + readyz expects ai@head"
```

---

### Task 3: ORM model `AIGenerationLog` + invariant comments + truncate fix

**Files:**
- Modify: `server/opds_sync/db/models.py`
- Modify: `server/tests/unit/conftest.py`
- Modify: `server/tests/integration/test_schema.py`

- [ ] **Step 1: Write the failing ORM round-trip test**

Append to `server/tests/integration/test_schema.py`:

```python
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
```

- [ ] **Step 2: Run to verify failure**

```bash
uv run pytest tests/integration/test_schema.py::test_ai_generation_log_round_trip -v
```

Expected: FAIL with `ImportError: cannot import name 'AIGenerationLog'`.

- [ ] **Step 3: Add the model + invariant docstrings**

In `server/opds_sync/db/models.py`:

(a) Immediately above the `BookInsight` class, prepend:

```python
# ============================================================================
# Cache-integrity invariant (PR-C, 2026-05-16)
# ----------------------------------------------------------------------------
# `book_insights` is a SHARED CACHE: one row serves every tenant who requests
# the same identity+model+prompt+tone. The cross-tenant cache-hit property is
# load-bearing for hosted Quire Cloud AI economics.
#
# Therefore this table MUST NOT carry `user_id`, `tenant_id`, `subject`, or
# any other principal column read for cache decisions. Per-call audit and
# billing attribution live in `ai_generation_log` (FK to book_insights.id).
#
# `generated_by` is grandfathered: a NOT NULL column from before this
# invariant existed. PR-C stops reading it; a follow-up will null it; a
# later migration will drop it. Until then it is write-only legacy.
# ============================================================================
class BookInsight(Base):
    ...
```

(b) Immediately above `ExternalSourceCacheEntry`, prepend:

```python
# Cache-integrity invariant: shared cache, MUST NOT carry tenant columns.
# See the comment above BookInsight for the full rule.
class ExternalSourceCacheEntry(Base):
    ...
```

(c) Append at the end of the file:

```python
class AIGenerationLog(Base):
    """Per-call audit row anchored to `book_insights.id`.

    One row per `get()`-hit / `generate()` / `regenerate()` call, regardless of
    cache state. Future billing rollups query `(tenant_id, created_at)`; the
    audit UI queries `(book_insight_id)`.

    `status` is permissive ('hit' | 'miss' | 'error') so a future PR can
    introduce error rows without a schema bump. PR-C only emits 'hit' and
    'miss'; errors go to structured logs because they have no FK target.
    """

    __tablename__ = "ai_generation_log"
    __table_args__ = (
        CheckConstraint(
            "status IN ('hit', 'miss', 'error')",
            name="ck_ai_generation_log_status",
        ),
        Index("ix_ai_generation_log_tenant_created", "tenant_id", "created_at"),
        Index("ix_ai_generation_log_book_insight", "book_insight_id"),
    )

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    book_insight_id: Mapped[int] = mapped_column(
        BigInteger,
        ForeignKey("book_insights.id", ondelete="CASCADE"),
        nullable=False,
    )
    tenant_id: Mapped[str] = mapped_column(
        String, nullable=False, server_default=text("'local'"), default="local"
    )
    subject: Mapped[str] = mapped_column(String, nullable=False)
    request_id: Mapped[str | None] = mapped_column(String, nullable=True)
    model_id: Mapped[str] = mapped_column(String, nullable=False)
    prompt_version: Mapped[str] = mapped_column(String, nullable=False)
    latency_ms: Mapped[int | None] = mapped_column(Integer, nullable=True)
    status: Mapped[str] = mapped_column(String, nullable=False)
    error_class: Mapped[str | None] = mapped_column(String, nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
```

- [ ] **Step 4: Update `tests/unit/conftest.py` truncate to handle the new FK child**

In `server/tests/unit/conftest.py`, replace the truncate block:

```python
@pytest.fixture(autouse=True)
async def _truncate_external_source_cache(request, engine: AsyncEngine):
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
```

- [ ] **Step 5: Run the round-trip test to verify it passes**

```bash
uv run pytest tests/integration/test_schema.py::test_ai_generation_log_round_trip -v
```

Expected: PASS.

- [ ] **Step 6: Run the full suite — no regressions**

```bash
uv run pytest -q
```

Expected: 121 passed.

- [ ] **Step 7: Commit**

```bash
git add server/opds_sync/db/models.py server/tests/unit/conftest.py server/tests/integration/test_schema.py
git commit -m ":sparkles: feat(server): AIGenerationLog ORM model + cache-integrity invariant comments"
```

---

### Task 4: Cache-key audit regression test

**Files:**
- Create: `server/tests/integration/test_cache_key_audit.py`

- [ ] **Step 1: Write the audit test (it will pass on the existing schema)**

Create `server/tests/integration/test_cache_key_audit.py`:

```python
"""Cache-integrity invariant: shared-cache tables MUST NOT carry tenant columns.

This regression test is *future-proof*: as PR2 (insight_identity_aliases) and
PR3 (book_themes) land, their model classes must be added to the parametrize
list. If a future PR accidentally adds `user_id` to a shared cache table,
this test fails at CI time, by design.

`generated_by` on BookInsight is grandfathered and explicitly allow-listed
with a comment pointing to the deprecation plan (PR-C stops reading it; a
follow-up nulls it; a later migration drops it).
"""

from __future__ import annotations

import pytest
from sqlalchemy import inspect

from opds_sync.db.models import BookInsight, ExternalSourceCacheEntry

FORBIDDEN_COLUMNS = frozenset(
    {"user_id", "tenant_id", "subject", "principal_id"}
)

# Grandfathered legacy columns per table. PR-C stops READING `generated_by`;
# a follow-up will null then drop it. Until dropped, it stays in the schema.
GRANDFATHERED: dict[str, frozenset[str]] = {
    "book_insights": frozenset({"generated_by"}),
}

SHARED_CACHE_TABLES = [
    pytest.param(BookInsight, id="book_insights"),
    pytest.param(ExternalSourceCacheEntry, id="external_source_cache"),
    # Future entries (add when the model lands):
    # pytest.param(BookTheme, id="book_themes"),           # PR3
    # pytest.param(InsightIdentityAlias, id="insight_identity_aliases"),  # PR2
]


@pytest.mark.requires_ai
@pytest.mark.parametrize("model_cls", SHARED_CACHE_TABLES)
async def test_shared_cache_table_has_no_tenant_columns(engine, model_cls) -> None:
    table_name = model_cls.__tablename__

    def _columns(sync_conn) -> set[str]:
        insp = inspect(sync_conn)
        return {c["name"] for c in insp.get_columns(table_name)}

    async with engine.connect() as conn:
        cols = await conn.run_sync(_columns)

    grandfathered = GRANDFATHERED.get(table_name, frozenset())
    offending = (cols & FORBIDDEN_COLUMNS) - grandfathered
    assert not offending, (
        f"shared-cache table {table_name!r} carries forbidden tenant columns: "
        f"{sorted(offending)}. Per the cache-integrity invariant, tenant audit "
        f"belongs in ai_generation_log, not on the shared cache row."
    )
```

- [ ] **Step 2: Run the test — it should pass immediately**

```bash
uv run pytest tests/integration/test_cache_key_audit.py -v
```

Expected: PASS for both `book_insights` and `external_source_cache`.

- [ ] **Step 3: Quick mechanic-verification one-liner**

```bash
uv run python -c "
from opds_sync.db.models import BookInsight
existing = {c.name for c in BookInsight.__table__.columns}
existing.add('user_id')
from tests.integration.test_cache_key_audit import FORBIDDEN_COLUMNS, GRANDFATHERED
grandfathered = GRANDFATHERED.get('book_insights', frozenset())
offending = (existing & FORBIDDEN_COLUMNS) - grandfathered
assert offending == {'user_id'}, offending
print('audit-test mechanic verified')
"
```

Expected: `audit-test mechanic verified`.

- [ ] **Step 4: Commit**

```bash
git add server/tests/integration/test_cache_key_audit.py
git commit -m ":white_check_mark: test(server): cache-integrity invariant audit test"
```

---

### Task 5: Service writes `'miss'` and `'hit'` rows + per-call `tenant_id` kwarg

**Files:**
- Modify: `server/opds_sync/core/ai/service.py`
- Modify: `server/tests/unit/test_ai_service.py`

- [ ] **Step 1: Write the failing unit tests**

Append to `server/tests/unit/test_ai_service.py`:

```python
from opds_sync.db.models import AIGenerationLog  # noqa: E402


@pytest.mark.asyncio
async def test_generate_miss_writes_log_row(session: AsyncSession, make_orchestrator):
    orch = make_orchestrator()
    ident = DocumentIdentity(metadata_id=None, content_hash="ch-log-miss")
    await orch.generate(session, ident, MetadataBundle(title="X"), user_id="alice")

    rows = (await session.execute(select(AIGenerationLog))).scalars().all()
    assert len(rows) == 1
    row = rows[0]
    assert row.status == "miss"
    assert row.subject == "alice"
    assert row.tenant_id == "local"  # default when kwarg omitted
    assert row.model_id == "test-model"
    assert row.prompt_version == "t1"
    assert row.latency_ms is not None and row.latency_ms >= 0
    assert row.error_class is None
    assert row.book_insight_id is not None


@pytest.mark.asyncio
async def test_second_generate_writes_hit_row_with_same_fk(
    session: AsyncSession, make_orchestrator
):
    orch = make_orchestrator()
    ident = DocumentIdentity(metadata_id=None, content_hash="ch-log-hit")
    await orch.generate(session, ident, MetadataBundle(title="X"), user_id="alice")
    await orch.generate(session, ident, MetadataBundle(title="X"), user_id="bob")

    rows = (
        (await session.execute(select(AIGenerationLog).order_by(AIGenerationLog.id)))
        .scalars()
        .all()
    )
    assert len(rows) == 2
    assert [r.status for r in rows] == ["miss", "hit"]
    assert [r.subject for r in rows] == ["alice", "bob"]
    assert rows[0].book_insight_id == rows[1].book_insight_id
    assert rows[1].latency_ms == 0  # cache lookup cost


@pytest.mark.asyncio
async def test_log_uses_passed_tenant_id(session: AsyncSession, make_orchestrator):
    orch = make_orchestrator()
    ident = DocumentIdentity(metadata_id=None, content_hash="ch-tenant-kwarg")
    await orch.generate(
        session, ident, MetadataBundle(title="X"), user_id="alice", tenant_id="acme"
    )

    rows = (await session.execute(select(AIGenerationLog))).scalars().all()
    assert len(rows) == 1
    assert rows[0].tenant_id == "acme"
```

- [ ] **Step 2: Run to verify failures**

```bash
uv run pytest tests/unit/test_ai_service.py::test_generate_miss_writes_log_row tests/unit/test_ai_service.py::test_second_generate_writes_hit_row_with_same_fk tests/unit/test_ai_service.py::test_log_uses_passed_tenant_id -v
```

Expected: all three FAIL (no log rows being written; `tenant_id` kwarg unrecognized).

- [ ] **Step 3: Update `service.py` — imports**

In `server/opds_sync/core/ai/service.py`, update the model import and add the ContextVar import:

```python
from opds_sync.core.logging_ctx import request_id_var
from opds_sync.db.models import AIGenerationLog, AIUsageDaily, BookInsight
```

- [ ] **Step 4: Add the `_log_generation` helper to `InsightOrchestrator`**

Insert this method on the class (e.g., after `_acquire_identity_lock`):

```python
async def _log_generation(
    self,
    session: AsyncSession,
    *,
    book_insight_id: int,
    subject: str,
    tenant_id: str,
    status: str,
    latency_ms: int | None,
    error_class: str | None = None,
) -> None:
    """Stage one ai_generation_log row. Caller commits the surrounding tx."""
    session.add(
        AIGenerationLog(
            book_insight_id=book_insight_id,
            tenant_id=tenant_id,
            subject=subject,
            request_id=(request_id_var.get() or None),
            model_id=self.model_id,
            prompt_version=self.prompt_version,
            latency_ms=latency_ms,
            status=status,
            error_class=error_class,
        )
    )
```

- [ ] **Step 5: Update `generate()` signature + body**

Replace the existing `generate()` method with:

```python
async def generate(
    self,
    session: AsyncSession,
    ident: DocumentIdentity,
    bundle: MetadataBundle,
    *,
    user_id: str,
    style: AiStyle | None = None,
    tenant_id: str = "local",
) -> BookInsightResponse:
    tone = _tone_of(style)
    row = await self._cache_lookup(session, ident, tone=tone, allow_backfill=True)
    if row is not None:
        await self._log_generation(
            session,
            book_insight_id=row.id,
            subject=user_id,
            tenant_id=tenant_id,
            status="hit",
            latency_ms=0,
        )
        await session.commit()
        return self._row_to_response(row)

    lock = await self._acquire_identity_lock(ident, tone=tone)
    async with lock:
        row = await self._cache_lookup(session, ident, tone=tone, allow_backfill=True)
        if row is not None:
            await self._log_generation(
                session,
                book_insight_id=row.id,
                subject=user_id,
                tenant_id=tenant_id,
                status="hit",
                latency_ms=0,
            )
            await session.commit()
            return self._row_to_response(row)

        await self._reserve_budget(session, user_id=user_id, is_regen=False)
        await self._bucket.acquire()
        row = await self._do_generate(
            session,
            ident,
            bundle,
            user_id=user_id,
            tenant_id=tenant_id,
            style=style,
            tone=tone,
            feedback=None,
            previous_insight_ids=None,
        )
        return self._row_to_response(row)
```

- [ ] **Step 6: Update `regenerate()` signature + body**

Replace the existing `regenerate()` method with:

```python
async def regenerate(
    self,
    session: AsyncSession,
    ident: DocumentIdentity,
    bundle: MetadataBundle,
    *,
    user_id: str,
    reason: str,
    style: AiStyle | None = None,
    tenant_id: str = "local",
) -> BookInsightResponse:
    """Supersede the existing live row (if any) and generate a fresh one."""
    tone = _tone_of(style)
    lock = await self._acquire_identity_lock(ident, tone=tone)
    async with lock:
        existing = await self._cache_lookup(session, ident, tone=tone, allow_backfill=False)
        previous_ids: list[int] = []
        if existing is not None:
            previous_ids = list(existing.previous_insight_ids or [])
            previous_ids.append(existing.id)
            existing.superseded_at = datetime.now(UTC)
            await session.commit()

        await self._reserve_budget(session, user_id=user_id, is_regen=True)
        await self._bucket.acquire()
        row = await self._do_generate(
            session,
            ident,
            bundle,
            user_id=user_id,
            tenant_id=tenant_id,
            style=style,
            tone=tone,
            feedback=reason,
            previous_insight_ids=previous_ids or None,
        )
        return self._row_to_response(row)
```

- [ ] **Step 7: Update `_do_generate()` to accept `tenant_id`, log the `miss` row, and handle errors**

Replace the entire `_do_generate` method with:

```python
async def _do_generate(
    self,
    session: AsyncSession,
    ident: DocumentIdentity,
    bundle: MetadataBundle,
    *,
    user_id: str,
    tenant_id: str,
    style: AiStyle | None,
    tone: str,
    feedback: str | None,
    previous_insight_ids: list[int] | None,
) -> BookInsight:
    async with self._sem:
        citations = await self._retrieve(session, bundle)
        user_prompt = compose_user_prompt(bundle, citations, style=style, feedback=feedback)
        t0 = time.monotonic()
        try:
            payload = await self.ai.chat_structured(
                system=SYSTEM_PROMPT,
                user=user_prompt,
                schema=BookInsightPayload,
                timeout_s=self._ai_timeout_s,
            )
        except Exception as e:
            # Errors don't produce an ai_generation_log row (no FK target).
            # The structured log line is the operator-facing audit trail;
            # request_id is attached by RequestIdLogFilter (record.request_id).
            latency_ms = int((time.monotonic() - t0) * 1000)
            logger.warning(
                "event=ai.generate.error tenant_id=%s subject=%s model=%s "
                "prompt_version=%s latency_ms=%d error_class=%s",
                tenant_id,
                user_id,
                self.model_id,
                self.prompt_version,
                latency_ms,
                type(e).__name__,
            )
            raise
        latency_ms = int((time.monotonic() - t0) * 1000)
        logger.info(
            "ai.generate content_hash=%s model=%s latency_ms=%d sources=%s regen=%s",
            ident.content_hash,
            self.model_id,
            latency_ms,
            ",".join(sorted({c.kind for c in citations})) or "-",
            bool(feedback),
        )

    if bundle.series_name:
        payload.series = SeriesInsight(
            name=bundle.series_name,
            position=bundle.series_position,
        )

    sources = list(citations)
    sources.append(Citation(kind="model", title=self.model_id, snippet="generated"))
    row = BookInsight(
        metadata_id=ident.metadata_id,
        content_hash=ident.content_hash,
        model_id=self.model_id,
        prompt_version=self.prompt_version,
        tone=tone,
        sources_used=list({c.kind for c in citations}),
        payload=payload.model_dump(),
        sources=[c.model_dump() for c in sources],
        # generated_by is grandfathered: NOT NULL legacy column. PR-C still
        # WRITES it (to satisfy the constraint) but NEVER READS it. A
        # follow-up PR will null then drop it. The replacement audit trail
        # is AIGenerationLog (FK from book_insight_id).
        generated_by=user_id,
        previous_insight_ids=previous_insight_ids,
    )
    session.add(row)
    await session.flush()  # populate row.id before logging
    await self._log_generation(
        session,
        book_insight_id=row.id,
        subject=user_id,
        tenant_id=tenant_id,
        status="miss",
        latency_ms=latency_ms,
    )
    await session.commit()
    await session.refresh(row)
    return row
```

- [ ] **Step 8: Run the three new tests to verify they pass**

```bash
uv run pytest tests/unit/test_ai_service.py::test_generate_miss_writes_log_row tests/unit/test_ai_service.py::test_second_generate_writes_hit_row_with_same_fk tests/unit/test_ai_service.py::test_log_uses_passed_tenant_id -v
```

Expected: PASS.

- [ ] **Step 9: Run the full suite to confirm no regressions**

```bash
uv run pytest -q
```

Expected: 124 passed (121 prior + 3 new).

- [ ] **Step 10: Commit**

```bash
git add server/opds_sync/core/ai/service.py server/tests/unit/test_ai_service.py
git commit -m ":sparkles: feat(server): orchestrator writes ai_generation_log per call"
```

---

### Task 6: `get()` logs `'hit'` on cache hit; threads `user_id` + `tenant_id`

**Files:**
- Modify: `server/opds_sync/core/ai/service.py`
- Modify: `server/opds_sync/api/ai.py`
- Modify: `server/tests/unit/test_ai_service.py`

- [ ] **Step 1: Write the failing tests**

Append to `server/tests/unit/test_ai_service.py`:

```python
@pytest.mark.asyncio
async def test_get_hit_writes_log_row(session: AsyncSession, make_orchestrator):
    orch = make_orchestrator()
    ident = DocumentIdentity(metadata_id=None, content_hash="ch-get-hit")
    await orch.generate(session, ident, MetadataBundle(title="X"), user_id="alice")
    # baseline: one miss row from the generate
    assert len((await session.execute(select(AIGenerationLog))).scalars().all()) == 1

    out = await orch.get(session, ident, user_id="alice")
    assert out is not None

    rows = (
        (await session.execute(select(AIGenerationLog).order_by(AIGenerationLog.id)))
        .scalars()
        .all()
    )
    assert len(rows) == 2
    assert rows[1].status == "hit"
    assert rows[1].latency_ms == 0


@pytest.mark.asyncio
async def test_get_miss_writes_no_log_row(session: AsyncSession, make_orchestrator):
    orch = make_orchestrator()
    ident = DocumentIdentity(metadata_id=None, content_hash="ch-get-miss")
    assert await orch.get(session, ident) is None

    rows = (await session.execute(select(AIGenerationLog))).scalars().all()
    assert rows == []
```

- [ ] **Step 2: Run to verify failure**

```bash
uv run pytest tests/unit/test_ai_service.py::test_get_hit_writes_log_row tests/unit/test_ai_service.py::test_get_miss_writes_no_log_row -v
```

Expected: `test_get_hit_writes_log_row` FAILS (no row written on hit). `test_get_miss_writes_no_log_row` PASSES (no rows written ever — `get()` doesn't log yet).

- [ ] **Step 3: Update `get()` to thread `user_id` + `tenant_id` and log on hit**

In `server/opds_sync/core/ai/service.py`, replace the `get()` method body:

```python
async def get(
    self,
    session: AsyncSession,
    ident: DocumentIdentity,
    *,
    user_id: str | None = None,
    style: AiStyle | None = None,
    tenant_id: str = "local",
) -> BookInsightResponse | None:
    tone = _tone_of(style)
    row = await self._cache_lookup(session, ident, tone=tone, allow_backfill=False)
    if row is None:
        return None
    if user_id is not None:
        await self._log_generation(
            session,
            book_insight_id=row.id,
            subject=user_id,
            tenant_id=tenant_id,
            status="hit",
            latency_ms=0,
        )
        await session.commit()
    return self._row_to_response(row)
```

`user_id=None` (no audit) remains the test-only path for fast cache probes that don't need an audit row.

- [ ] **Step 4: Update API call sites to pass `user_id` and `tenant_id="local"`**

In `server/opds_sync/api/ai.py`, find the four orchestrator call sites:

```bash
grep -n "orch\.get\|orch\.generate\|orch\.regenerate" server/opds_sync/api/ai.py
```

Update each to add `tenant_id="local"`, and update the `orch.get(...)` call to also include `user_id=user_id`. Example for the GET endpoint (line ~212):

Before:
```python
out = await orch.get(session, body.identity, style=style)
```

After:
```python
out = await orch.get(session, body.identity, user_id=user_id, style=style, tenant_id="local")
```

For `orch.generate(...)` and `orch.regenerate(...)` (lines ~161 and ~186), append `tenant_id="local"` to the kwargs. `user_id` is already passed.

(Hardcoded `"local"` is the explicit PR-B seam. PR-B will replace these with `principal.tenant_id`.)

- [ ] **Step 5: Run new tests + full suite**

```bash
uv run pytest tests/unit/test_ai_service.py::test_get_hit_writes_log_row tests/unit/test_ai_service.py::test_get_miss_writes_no_log_row -v
uv run pytest -q
```

Expected: both new tests PASS; full suite green (126 passed).

- [ ] **Step 6: Commit**

```bash
git add server/opds_sync/core/ai/service.py server/opds_sync/api/ai.py server/tests/unit/test_ai_service.py
git commit -m ":sparkles: feat(server): orchestrator.get() logs hit rows; API threads tenant_id=local"
```

---

### Task 7: Coalesced waiters each get their own log row + request_id propagation tests

**Files:**
- Modify: `server/tests/unit/test_ai_service.py`

- [ ] **Step 1: Write the failing/regression tests**

Append to `server/tests/unit/test_ai_service.py`:

```python
@pytest.mark.asyncio
async def test_concurrent_generations_emit_one_miss_and_n_minus_one_hits(
    session: AsyncSession, make_orchestrator
):
    """Coalesced waiters: one model call but N log rows, one per waiter.

    Uses the existing shared-session pattern (mirrors test_concurrent_generations_
    collapse_to_one_model_call). Cross-session FK visibility is exercised by the
    multi-tenant integration test instead.
    """
    orch = make_orchestrator()
    ident = DocumentIdentity(metadata_id=None, content_hash="ch-coalesce-log")
    bundle = MetadataBundle(title="Coalesce")

    await asyncio.gather(
        orch.generate(session, ident, bundle, user_id="u1"),
        orch.generate(session, ident, bundle, user_id="u2"),
        orch.generate(session, ident, bundle, user_id="u3"),
    )

    assert len(orch.ai.calls) == 1

    rows = (
        (await session.execute(select(AIGenerationLog).order_by(AIGenerationLog.id)))
        .scalars()
        .all()
    )
    assert len(rows) == 3
    statuses = sorted(r.status for r in rows)
    assert statuses == ["hit", "hit", "miss"]
    # All three FK the same insight
    assert len({r.book_insight_id for r in rows}) == 1
    # Three distinct subjects
    assert sorted(r.subject for r in rows) == ["u1", "u2", "u3"]


@pytest.mark.asyncio
async def test_log_carries_request_id_when_set(
    session: AsyncSession, make_orchestrator
):
    from opds_sync.core.logging_ctx import request_id_var

    token = request_id_var.set("test-req-abc123")
    try:
        orch = make_orchestrator()
        ident = DocumentIdentity(metadata_id=None, content_hash="ch-req-id")
        await orch.generate(session, ident, MetadataBundle(title="X"), user_id="alice")
    finally:
        request_id_var.reset(token)

    rows = (await session.execute(select(AIGenerationLog))).scalars().all()
    assert len(rows) == 1
    assert rows[0].request_id == "test-req-abc123"


@pytest.mark.asyncio
async def test_log_default_tenant_id_is_local(session: AsyncSession, make_orchestrator):
    orch = make_orchestrator()
    ident = DocumentIdentity(metadata_id=None, content_hash="ch-tenant-default")
    await orch.generate(session, ident, MetadataBundle(title="X"), user_id="alice")

    rows = (await session.execute(select(AIGenerationLog))).scalars().all()
    assert len(rows) == 1
    assert rows[0].tenant_id == "local"
```

- [ ] **Step 2: Run the tests**

```bash
uv run pytest tests/unit/test_ai_service.py::test_concurrent_generations_emit_one_miss_and_n_minus_one_hits tests/unit/test_ai_service.py::test_log_carries_request_id_when_set tests/unit/test_ai_service.py::test_log_default_tenant_id_is_local -v
```

Expected: all PASS (Task 5/6 already wired the implementation; these are regression guards).

- [ ] **Step 3: Run the full suite**

```bash
uv run pytest -q
```

Expected: 129 passed.

- [ ] **Step 4: Commit**

```bash
git add server/tests/unit/test_ai_service.py
git commit -m ":white_check_mark: test(server): coalesce, request_id, tenant_id default regression tests"
```

---

### Task 8: Fix root-logger filter attachment so child-logger records get request_id

**Files:**
- Modify: `server/opds_sync/main.py`
- Modify: `server/tests/unit/test_logging_ctx.py` (or new test)

**Why this task:** PR-A attaches `RequestIdLogFilter` to the root logger via `logging.getLogger().addFilter(...)`. Python's logging propagation does NOT apply logger-level filters from parent loggers to records originating in child loggers. As a result, records emitted by `opds_sync.core.ai.service` (like the new `event=ai.generate.error` warning) reach the root logger's handlers without `record.request_id` set. Fix: attach the filter to the root logger's handler(s) instead, so it runs on any record routed through those handlers (including propagated ones).

- [ ] **Step 1: Write a failing test asserting propagation correctness**

Append to `server/tests/unit/test_logging_ctx.py`:

```python
import logging

import pytest

from opds_sync.core.logging_ctx import RequestIdLogFilter, request_id_var


def _attach_filter_like_main(filt: logging.Filter) -> list[logging.Handler]:
    """Mirror main.py's intended attachment: filter on each root handler."""
    root = logging.getLogger()
    attached: list[logging.Handler] = []
    for h in root.handlers:
        h.addFilter(filt)
        attached.append(h)
    return attached


def test_filter_attached_to_root_handler_applies_to_child_logger_records(caplog):
    """Records emitted by a CHILD logger must end up with request_id when the
    filter is attached to the root logger's handler (not the root logger).

    This is the production-faithful attachment that ai.generate.error relies on.
    """
    logging.basicConfig(level=logging.WARNING)  # ensure a root handler exists
    filt = RequestIdLogFilter()
    attached = _attach_filter_like_main(filt)
    token = request_id_var.set("rid-propagation-test")
    try:
        child = logging.getLogger("opds_sync.core.ai.service")
        with caplog.at_level(logging.WARNING, logger="opds_sync.core.ai.service"):
            child.warning("hello from child")
    finally:
        request_id_var.reset(token)
        for h in attached:
            h.removeFilter(filt)

    # The caplog handler is installed on root; the filter runs on its records.
    rec = next(r for r in caplog.records if r.getMessage() == "hello from child")
    assert getattr(rec, "request_id", "") == "rid-propagation-test"
```

- [ ] **Step 2: Run to see whether the failure mode actually exists today**

```bash
uv run pytest tests/unit/test_logging_ctx.py::test_filter_attached_to_root_handler_applies_to_child_logger_records -v
```

If FAIL: confirms the bug; proceed to fix in Step 3.
If PASS: the local pytest setup may already propagate-and-filter correctly via `caplog`; document but still proceed (the production fix is harmless).

- [ ] **Step 3: Update `main.py` to attach the filter to handlers**

In `server/opds_sync/main.py`, replace:

```python
    logging.basicConfig(level=settings.log_level)
    # Inject request_id into every log record produced after this point.
    logging.getLogger().addFilter(RequestIdLogFilter())
```

with:

```python
    logging.basicConfig(level=settings.log_level)
    # Inject request_id into every log record routed through the root
    # handlers. Logger-level filters on the root logger do NOT apply to
    # records propagated up from child loggers, so we attach the filter to
    # the handlers themselves. Idempotent if create_app() runs more than
    # once (e.g., in tests).
    _filter = RequestIdLogFilter()
    for _h in logging.getLogger().handlers:
        if not any(isinstance(f, RequestIdLogFilter) for f in _h.filters):
            _h.addFilter(_filter)
```

- [ ] **Step 3b: Update `logging_ctx.py` docstring**

In `server/opds_sync/core/logging_ctx.py`, replace the misleading example in `RequestIdLogFilter.__doc__`:

```python
class RequestIdLogFilter(logging.Filter):
    """Inject the current request_id into every log record.

    IMPORTANT: attach to HANDLERS, not to loggers. Logger-level filters do
    NOT apply to records propagated up from child loggers — only to records
    logged directly to that logger. The production wiring lives in
    `main.py::create_app()`; mirror it in tests by adding the filter to
    `caplog.handler` or to your own handler instance.
    """
```

- [ ] **Step 4: Verify the new test passes and no existing tests broke**

```bash
uv run pytest tests/unit/test_logging_ctx.py -v
uv run pytest -q
```

Expected: new test PASS; full suite green.

- [ ] **Step 5: Commit**

```bash
git add server/opds_sync/main.py server/tests/unit/test_logging_ctx.py
git commit -m ":bug: fix(server): attach RequestIdLogFilter to root handlers so child-logger records get request_id"
```

---

### Task 9: Error path emits structured log line (no DB row)

**Files:**
- Modify: `server/tests/unit/test_ai_service.py`

- [ ] **Step 1: Write the failing test**

Append to `server/tests/unit/test_ai_service.py`:

```python
class _ExplodingAIClient:
    def __init__(self) -> None:
        self.calls: list[dict] = []

    async def chat_structured(self, *, system, user, schema, timeout_s):
        self.calls.append({"system": system, "user": user})
        raise RuntimeError("simulated provider failure")


@pytest.mark.asyncio
async def test_generate_error_emits_structured_log(session: AsyncSession, caplog):
    """Errors emit a structured `event=ai.generate.error` warning carrying
    tenant_id, subject, model, prompt_version, error_class. The request_id
    ContextVar is read by the log filter (attached to caplog's handler) and
    surfaces as record.request_id. No ai_generation_log row is written.
    """
    import logging

    from opds_sync.core.ai.service import InsightOrchestrator
    from opds_sync.core.logging_ctx import RequestIdLogFilter, request_id_var

    # Attach the filter to caplog's handler to mirror main.py's handler-level
    # attachment (see Task 8). Without this, record.request_id won't be set
    # because caplog's handler is added independently of `basicConfig`.
    filt = RequestIdLogFilter()
    caplog.handler.addFilter(filt)
    token = request_id_var.set("req-err-xyz")
    try:
        orch = InsightOrchestrator(
            ai=_ExplodingAIClient(),
            retriever_factory=lambda s: FakeRetriever(),
            sources_enabled=(),
            model_id="boom-model",
            prompt_version="t1",
            max_concurrency=1,
            ai_timeout_s=5.0,
        )
        ident = DocumentIdentity(metadata_id=None, content_hash="ch-error")

        with caplog.at_level(logging.WARNING, logger="opds_sync.core.ai.service"):
            with pytest.raises(RuntimeError, match="simulated provider failure"):
                await orch.generate(
                    session,
                    ident,
                    MetadataBundle(title="X"),
                    user_id="alice",
                    tenant_id="acme",
                )
    finally:
        request_id_var.reset(token)
        caplog.handler.removeFilter(filt)

    rows = (await session.execute(select(AIGenerationLog))).scalars().all()
    assert rows == []  # no DB row for errors

    error_records = [r for r in caplog.records if "event=ai.generate.error" in r.getMessage()]
    assert len(error_records) == 1, (
        f"expected exactly one ai.generate.error log record, got {len(error_records)}"
    )
    rec = error_records[0]
    msg = rec.getMessage()
    assert "tenant_id=acme" in msg
    assert "subject=alice" in msg
    assert "model=boom-model" in msg
    assert "prompt_version=t1" in msg
    assert "error_class=RuntimeError" in msg
    # request_id surfaces on the record because the filter is on the handler.
    assert getattr(rec, "request_id", "") == "req-err-xyz"
```

- [ ] **Step 2: Run the test**

```bash
uv run pytest tests/unit/test_ai_service.py::test_generate_error_emits_structured_log -v
```

Expected: PASS (Task 5's `_do_generate` already added the structured log line in the exception handler).

- [ ] **Step 3: Run the full suite**

```bash
uv run pytest -q
```

Expected: 130 passed.

- [ ] **Step 4: Commit**

```bash
git add server/tests/unit/test_ai_service.py
git commit -m ":white_check_mark: test(server): structured error log on AI client failure"
```

---

### Task 10: Multi-tenant convergence + concurrent-coalesce integration tests (separate sessions)

**Files:**
- Create: `server/tests/integration/test_ai_audit_log.py`

- [ ] **Step 1: Write the integration tests using separate `AsyncSession` instances**

Two tests in this file:
1. **Sequential** two-tenant convergence (cross-session cache visibility).
2. **Concurrent** N-waiter coalesce (real lock contention across separate sessions).

Create `server/tests/integration/test_ai_audit_log.py`:

```python
"""Multi-tenant convergence: two synthetic tenants share one book_insights
row but produce two ai_generation_log rows with distinct tenant_id values.

Uses SEPARATE AsyncSession instances per tenant — faithfully exercising
cross-session FK visibility (tenant B's session must see the committed
book_insights row from tenant A's session) and matching how the production
app-state singleton orchestrator handles concurrent requests.
"""

from __future__ import annotations

from collections.abc import AsyncIterator

import pytest
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from opds_sync.api.ai_schemas import DocumentIdentity, MetadataBundle
from opds_sync.core.ai.service import InsightOrchestrator
from opds_sync.db.models import AIGenerationLog, BookInsight


class _FakeAIClient:
    def __init__(self) -> None:
        self.calls: list[dict] = []
        self.next_payload = {
            "schema_version": 2,
            "intro": "Two-tenant convergence.",
            "confidence": "high",
        }

    async def chat_structured(self, *, system, user, schema, timeout_s):
        self.calls.append({"system": system, "user": user})
        return schema.model_validate(self.next_payload)


class _FakeRetriever:
    async def lookup_wikipedia(self, **kw):
        return []

    async def lookup_openlibrary(self, **kw):
        return []


@pytest.fixture
async def session_factory(engine) -> AsyncIterator[async_sessionmaker[AsyncSession]]:
    yield async_sessionmaker(engine, expire_on_commit=False, class_=AsyncSession)


@pytest.mark.requires_ai
@pytest.mark.asyncio
async def test_two_tenants_share_one_insight_two_log_rows(session_factory, session):
    """Tenant A misses + generates; Tenant B hits the same row. Two log entries."""
    # Cleanup is handled by the unit conftest's truncate fixture for the unit
    # test suite, but this is an integration test — wipe explicitly.
    from sqlalchemy import text

    async with session_factory() as cleanup:
        await cleanup.execute(
            text(
                "TRUNCATE TABLE ai_generation_log, external_source_cache, "
                "book_insights, ai_usage_daily CASCADE"
            )
        )
        await cleanup.commit()

    # One shared orchestrator (mirrors app.state singleton).
    orch = InsightOrchestrator(
        ai=_FakeAIClient(),
        retriever_factory=lambda s: _FakeRetriever(),
        sources_enabled=(),
        model_id="multi-tenant-model",
        prompt_version="mt1",
        max_concurrency=2,
        ai_timeout_s=5.0,
    )

    ident = DocumentIdentity(metadata_id="tenant-shared", content_hash="ch-multi")
    bundle = MetadataBundle(title="Shared")

    # Tenant A: cold miss (own session)
    async with session_factory() as s_a:
        out_a = await orch.generate(
            s_a, ident, bundle, user_id="alice@tenant-a", tenant_id="tenant-a"
        )

    # Tenant B: hits A's cache row (own session)
    async with session_factory() as s_b:
        out_b = await orch.generate(
            s_b, ident, bundle, user_id="bob@tenant-b", tenant_id="tenant-b"
        )

    # Cross-tenant cache hit confirmed
    assert out_a.payload.intro == out_b.payload.intro
    assert len(orch.ai.calls) == 1  # one model call total
    # (generated_by is grandfathered: don't assert on it — keeps the "no read
    # sites" grep clean.)

    # Inspect the persisted state from a third session
    async with session_factory() as s_check:
        insights = (
            (
                await s_check.execute(
                    select(BookInsight).where(BookInsight.content_hash == "ch-multi")
                )
            )
            .scalars()
            .all()
        )
        assert len(insights) == 1
        insight = insights[0]
        # generated_by intentionally NOT asserted: PR-C stops READING the
        # column; an assertion here would re-introduce a read site that the
        # cache-key audit grep is meant to catch.

        logs = (
            (await s_check.execute(select(AIGenerationLog).order_by(AIGenerationLog.id)))
            .scalars()
            .all()
        )
        assert len(logs) == 2
        assert {l.tenant_id for l in logs} == {"tenant-a", "tenant-b"}
        assert [l.status for l in logs] == ["miss", "hit"]
        assert {l.book_insight_id for l in logs} == {insight.id}
        assert {l.subject for l in logs} == {"alice@tenant-a", "bob@tenant-b"}


class _SlowAIClient:
    """Fake AI client that blocks until released, then returns once.

    Lets us prove that concurrent generate() calls actually serialize through
    the per-identity lock (rather than racing to multiple model calls).
    """

    def __init__(self) -> None:
        self.calls: list[dict] = []
        self.release = __import__("asyncio").Event()
        self.next_payload = {
            "schema_version": 2,
            "intro": "Coalesced.",
            "confidence": "high",
        }

    async def chat_structured(self, *, system, user, schema, timeout_s):
        self.calls.append({"system": system, "user": user})
        await self.release.wait()
        return schema.model_validate(self.next_payload)


@pytest.mark.requires_ai
@pytest.mark.asyncio
async def test_concurrent_waiters_coalesce_across_sessions(session_factory, session):
    """N concurrent waiters with separate sessions → 1 miss + (N-1) hits.

    The slow fake AI client blocks on an asyncio.Event so all waiters reach
    the lock at the same time. We release the event after a tick to ensure
    they actually queue on the lock rather than racing.
    """
    import asyncio

    from sqlalchemy import text

    async with session_factory() as cleanup:
        await cleanup.execute(
            text(
                "TRUNCATE TABLE ai_generation_log, external_source_cache, "
                "book_insights, ai_usage_daily CASCADE"
            )
        )
        await cleanup.commit()

    slow_client = _SlowAIClient()
    orch = InsightOrchestrator(
        ai=slow_client,
        retriever_factory=lambda s: _FakeRetriever(),
        sources_enabled=(),
        model_id="coalesce-model",
        prompt_version="c1",
        max_concurrency=4,
        ai_timeout_s=5.0,
    )

    ident = DocumentIdentity(metadata_id=None, content_hash="ch-conc-coalesce")
    bundle = MetadataBundle(title="Conc")

    async def _one_waiter(user_id: str, tenant_id: str):
        async with session_factory() as s:
            return await orch.generate(
                s, ident, bundle, user_id=user_id, tenant_id=tenant_id
            )

    # Launch three waiters concurrently. They will all queue on the lock;
    # the lock-holder will block on slow_client.release.
    tasks = [
        asyncio.create_task(_one_waiter("u1", "t1")),
        asyncio.create_task(_one_waiter("u2", "t2")),
        asyncio.create_task(_one_waiter("u3", "t3")),
    ]

    # Wait until exactly one model call is in flight (the lock-holder reached
    # chat_structured). Bounded loop avoids `sleep(0.05)` flakiness.
    deadline = asyncio.get_event_loop().time() + 5.0
    while len(slow_client.calls) == 0 and asyncio.get_event_loop().time() < deadline:
        await asyncio.sleep(0.005)
    # Give other tasks one more scheduling slice so any racing waiter would
    # have had time to also enter chat_structured. Then assert exactly one.
    await asyncio.sleep(0.02)
    assert len(slow_client.calls) == 1, (
        f"expected 1 in-flight model call, got {len(slow_client.calls)}; "
        "waiters didn't coalesce on the lock"
    )

    # Release the lock-holder so it can complete; the two waiters then hit.
    slow_client.release.set()
    results = await asyncio.gather(*tasks)
    assert len(results) == 3
    # Still exactly one model call total.
    assert len(slow_client.calls) == 1

    async with session_factory() as s_check:
        insights = (
            (
                await s_check.execute(
                    select(BookInsight).where(BookInsight.content_hash == "ch-conc-coalesce")
                )
            )
            .scalars()
            .all()
        )
        assert len(insights) == 1

        logs = (
            (await s_check.execute(select(AIGenerationLog).order_by(AIGenerationLog.id)))
            .scalars()
            .all()
        )
        assert len(logs) == 3
        statuses = sorted(l.status for l in logs)
        assert statuses == ["hit", "hit", "miss"]
        assert {l.book_insight_id for l in logs} == {insights[0].id}
        assert sorted(l.subject for l in logs) == ["u1", "u2", "u3"]
        assert sorted(l.tenant_id for l in logs) == ["t1", "t2", "t3"]
```

- [ ] **Step 2: Run the integration tests**

```bash
uv run pytest tests/integration/test_ai_audit_log.py -v
```

Expected: both tests PASS.

- [ ] **Step 3: Run the full suite to confirm**

```bash
uv run pytest -q
```

Expected: 132 passed.

- [ ] **Step 4: Commit**

```bash
git add server/tests/integration/test_ai_audit_log.py
git commit -m ":white_check_mark: test(server): two-tenant convergence on shared insight (separate sessions)"
```

---

### Task 11: Mode-matrix verification

**Files:**
- None modified; verification only.

- [ ] **Step 1: Verify the suite passes in sync-only mode**

```bash
cd /Users/vito/repos/quire/.claude/worktrees/pr-c-ai-generation-log/server
OPDS_SYNC_PROGRESS_ENABLED=true OPDS_SYNC_AI_ENABLED=false uv run pytest -q
```

Expected: `requires_ai` tests skip (count visible in summary); all others pass.

- [ ] **Step 2: Verify the suite passes in ai-only mode**

```bash
OPDS_SYNC_PROGRESS_ENABLED=false OPDS_SYNC_AI_ENABLED=true uv run pytest -q
```

Expected: `requires_progress` tests skip; AI tests pass.

- [ ] **Step 3: Verify the default (full-stack) suite is green**

```bash
uv run pytest -q
```

Expected: full green (132 passed).

- [ ] **Step 4: No commit** — this task is verification only.

---

### Task 12: Push, open PR, paste GPT verdict

**Files:**
- Push branch `feat/ai-generation-log` to origin.
- Open PR with base `feat/alembic-mode-split`.

- [ ] **Step 1: Push**

```bash
cd /Users/vito/repos/quire/.claude/worktrees/pr-c-ai-generation-log
git push -u origin feat/ai-generation-log
```

- [ ] **Step 2: Open the PR (HEREDOC body — NO Claude attribution)**

```bash
gh pr create --base feat/alembic-mode-split --head feat/ai-generation-log \
  --title "feat(server): ai_generation_log table + cache-key tenant audit" \
  --body "$(cat <<'EOF'
## Summary

PR-C of the 2026-05-16 roadmap batch, stacked on PR-A (alembic mode split). Server-only.

- Adds `ai_generation_log` as the first migration on the `ai` branch (`ai_001`, `down_revision = "0004"`, `branch_labels = ("ai",)`).
- `InsightOrchestrator` writes one row per `get()`-hit / `generate()` / `regenerate()` call: `status` is `hit` or `miss`, FK'd to `book_insights.id`, carrying `tenant_id` (per-call kwarg, default `"local"`), `subject` (the existing `user_id`), `request_id` (from PR-A's `request_id_var` ContextVar), `model_id`, `prompt_version`, `latency_ms`.
- Errors emit a structured `logger.warning("ai.generate.error tenant_id=... subject=... model=... prompt_version=... latency_ms=... error_class=...")` line and raise. No DB row (no FK target). Tested via `caplog`.
- Stops reading `book_insights.generated_by` for any decision (audit confirmed: no read sites today). The column stays as legacy-write-only; a follow-up will null then drop it.

## Cache-integrity invariant (codified)

> The shared-cache tables — `book_insights`, `external_source_cache`, and (in future PRs) `book_themes`, `insight_identity_aliases` whose `user_id` is NULL — MUST NOT carry any user-identifying column (`user_id`, `tenant_id`, `subject`, `principal_id`) read for any cache decision.

A parametrized regression test (`tests/integration/test_cache_key_audit.py`) introspects each shared-cache table via SQLAlchemy reflection and asserts the absence of tenant columns. **Future PRs that add a shared-cache table MUST add it to the parametrize list** — PR2 (`insight_identity_aliases`), PR3 (`book_themes`). The test docstring restates this so the next PR author can't miss it.

The invariant is also documented as a code comment above `BookInsight` and `ExternalSourceCacheEntry` in `db/models.py`.

## Coalesced-waiter logging semantics

When N concurrent `generate()` callers serialize on the per-identity lock: one model call, N log rows (1 `miss` + N-1 `hit`), each carrying its own `(tenant_id, subject, request_id)`. Asserted by `test_concurrent_generations_emit_one_miss_and_n_minus_one_hits`. Multi-session cross-tenant visibility asserted by `tests/integration/test_ai_audit_log.py::test_two_tenants_share_one_insight_two_log_rows`. This is what makes downstream per-tenant billing possible.

## Per-call tenant_id (PR-B seam)

`tenant_id` is a per-call kwarg on `get/generate/regenerate`, defaulting to `"local"`. The API layer (`opds_sync/api/ai.py`) hardcodes `tenant_id="local"` today; PR-B will swap that for `principal.tenant_id` once the `AiPrincipal` plumbing exists. Constructor-level state was rejected because the orchestrator is an app-state singleton — a process-wide default can't carry per-waiter tenant info.

## Error rows

The `status` check constraint allows `'hit' | 'miss' | 'error'`, but PR-C only emits hit/miss. Errors have no `book_insights` FK target (the row is committed AFTER `chat_structured` returns); a future PR may add nullable-FK or sentinel rows. The permissive constraint avoids a schema bump when that PR lands. Until then, error observability lives in the structured log channel — tested in `test_generate_error_emits_structured_log`.

## PR-A test fallout (fixed in this PR)

Materializing `ai_001` changed which alembic head the test fixture lands on. Two `test_readyz_migration_state.py` assertions and the session-scoped `alembic_upgrade` fixture were updated:

- `tests/conftest.py::alembic_upgrade` now calls `scripts.migrate.run_migrations(cfg, progress_enabled=True, ai_enabled=True)` instead of `command.upgrade(cfg, "head")`. The wrapper handles multi-head correctly and matches production.
- `test_readyz_200_when_at_backbone_no_labels` → renamed `test_readyz_200_when_at_ai_head`; expects `["ai_001"]`.
- `test_readyz_200_with_neither_mode_at_backbone` stamps to `0004` explicitly so it exercises the "fresh sync-only deploy that never materialized ai" scenario.

## What's NOT in this PR

- `generated_by` nulling/drop — separate follow-up PRs (intentional phasing).
- PR-B's real `AiPrincipal.tenant_id` plumbing — API layer hardcodes `"local"` for now.
- Error-row DB emission — out of scope per the trade-off above.

## Downgrade story

```bash
alembic downgrade ai@-1
```

Drops `ai_generation_log`. CASCADE FK means deleting a cache row already deletes its log children. PR-A's wrapper handles per-branch downgrades manually as designed.

## Test plan

- [x] `tests/integration/test_schema.py` — new `test_ai_generation_log_table_exists`, `test_ai_generation_log_round_trip`.
- [x] `tests/integration/test_cache_key_audit.py` — parametrized regression test.
- [x] `tests/integration/test_ai_audit_log.py` — two-tenant convergence with separate sessions.
- [x] `tests/unit/test_ai_service.py` — 9 new tests (miss, hit, get-hit, get-miss-no-log, coalesce, request_id, tenant default, tenant override, error log).
- [x] `tests/unit/conftest.py` truncate fixture updated to handle the new FK child.
- [x] `tests/integration/test_readyz_migration_state.py` assertions updated for `ai_001` head.
- [x] Full suite passes in full-stack mode.
- [x] Full suite passes in sync-only mode (`requires_ai` tests skip cleanly).
- [x] Full suite passes in ai-only mode (`requires_progress` tests skip cleanly).

## Review summary

GPT architect review captured during plan iteration — replace this paragraph with the final verdict + verbatim bottom-line from the last review round before opening the PR. Earlier rounds surfaced concerns about per-call tenant attribution, test-fixture mode awareness, FK-cascade in TRUNCATE, error-row semantics, and concurrent-waiter test fidelity — all addressed in the PR's tests and code.

<!-- IMPLEMENTER: paste final verdict here before `gh pr create` runs -->

## For downstream PR agents

- **PR4 (language column)**: your migration is `ai_002_insight_language` with `down_revision = "ai_001"` and `branch_labels = None`. The cache-key audit test does not need changes (`language` is a cache-key dimension, not a tenant column).
- **PR2 (identity aliases)**: your migration chains off the then-current `ai@head`. Add `InsightIdentityAlias` to `SHARED_CACHE_TABLES` in `tests/integration/test_cache_key_audit.py`. The user-scoped alias variant may need per-row policy rather than a column-level allow-list — design at PR2 review time.
- **PR3 (themes)**: your migration chains off the then-current `ai@head`. Add `BookTheme` to `SHARED_CACHE_TABLES` — `book_themes` is fully shared, no `user_id` allowed.
- **PR-B (auth abstraction)**: replace the hardcoded `tenant_id="local"` in `opds_sync/api/ai.py` with `principal.tenant_id` from the `AiPrincipal` dependency. Per-call kwarg is already there; only the API-layer line changes.
EOF
)"
```

- [ ] **Step 3: Capture the PR URL and final report**

The parent agent needs:

- PR URL (`gh pr view --json url -q .url`).
- Branch: `feat/ai-generation-log`.
- Migration `revision_id`: `ai_001` — this is what PR4's `down_revision` must point to.
- Summary of deviations from the spec (if any).

---

## Self-review checklist (run before handing off)

- [x] Every spec section maps to at least one task (invariant § → Task 3+4; schema § → Task 1; model § → Task 3; service § → Tasks 5, 6, 8; tests § → Tasks 1, 3, 4, 5, 6, 7, 8, 9; downgrade § → Task 1's `downgrade()`).
- [x] PR-A test fallout handled (Task 2 — conftest + readyz).
- [x] Truncate-cascade handled (Task 3 — `tests/unit/conftest.py`).
- [x] Per-call `tenant_id` kwarg used everywhere — no `default_tenant_id` constructor state.
- [x] Error path covered with structured log + test (Task 5 + Task 8).
- [x] Multi-tenant test uses separate sessions (Task 9).
- [x] No `TODO`, `TBD`, or `similar to Task N` placeholders.
- [x] Type names consistent (`AIGenerationLog`, `_log_generation`, `tenant_id` kwarg everywhere).
- [x] Commit messages use gitmoji + conventional commits, no Claude attribution.
