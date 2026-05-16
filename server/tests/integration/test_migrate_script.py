"""Integration tests for scripts/migrate.py against a real Postgres + Alembic.

Cases covered:
1. Default state (no branch labels): wrapper upgrades backbone, DB ends at 0004,
   `alembic_version` contains exactly {"0004"}.
2. Idempotent: running twice in a row is a no-op.
3. Synthetic branched script directory (tmp copy of migrations + an ai_test_001
   revision with branch_labels=("ai",)):
   - ai_enabled=true → upgrades to ai_test_001.
   - ai_enabled=false → stays at 0004.
"""

from __future__ import annotations

import asyncio
import shutil
import textwrap
from pathlib import Path

from alembic.config import Config
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncEngine, create_async_engine


async def _alembic_versions(engine: AsyncEngine) -> set[str]:
    async with engine.connect() as conn:
        result = await conn.execute(text("SELECT version_num FROM alembic_version"))
        return {row[0] for row in result.fetchall()}


def _make_cfg(url: str, script_location: str | None = None) -> Config:
    cfg = Config("alembic.ini")
    cfg.set_main_option("sqlalchemy.url", url)
    if script_location:
        cfg.set_main_option("script_location", script_location)
    return cfg


async def _run_migrations_in_thread(cfg, *, progress_enabled: bool, ai_enabled: bool):
    """Alembic's env.py uses asyncio.run() internally, which can't run inside an
    already-running event loop. Push the call into a worker thread."""
    from scripts.migrate import run_migrations

    await asyncio.to_thread(
        run_migrations, cfg, progress_enabled=progress_enabled, ai_enabled=ai_enabled
    )


async def _downgrade_in_thread(cfg, target: str) -> None:
    from alembic import command as alembic_command

    await asyncio.to_thread(alembic_command.downgrade, cfg, target)


async def test_default_state_upgrades_backbone_then_ai_branch(postgres_url: str):
    """Fresh DB → wrapper upgrades to backbone, then to ai@head (ai_001)."""

    # Wipe DB to fresh state.
    eng = create_async_engine(postgres_url, future=True)
    async with eng.begin() as conn:
        await conn.execute(text("DROP SCHEMA public CASCADE"))
        await conn.execute(text("CREATE SCHEMA public"))

    cfg = _make_cfg(postgres_url)
    await _run_migrations_in_thread(cfg, progress_enabled=True, ai_enabled=True)

    versions = await _alembic_versions(eng)
    await eng.dispose()
    # PR-C materialized the `ai` branch; ai_enabled=True advances to ai@head.
    assert versions == {"ai_001"}


async def test_idempotent_second_run(postgres_url: str):
    """Running the wrapper twice in a row → still at ai@head, no errors."""

    cfg = _make_cfg(postgres_url)
    await _run_migrations_in_thread(cfg, progress_enabled=True, ai_enabled=True)
    await _run_migrations_in_thread(cfg, progress_enabled=True, ai_enabled=True)

    eng = create_async_engine(postgres_url, future=True)
    versions = await _alembic_versions(eng)
    await eng.dispose()
    assert versions == {"ai_001"}


async def test_synthetic_ai_branch_upgrades_when_enabled(postgres_url: str, tmp_path: Path):
    """Copy real migrations to tmp + add a synthetic ai_test_002 chained off
    ai_001; verify enabled run advances to ai_test_002 (the new ai@head)."""

    # First, ensure DB is at ai@head (real migrations include ai_001).
    real_cfg = _make_cfg(postgres_url)
    await _run_migrations_in_thread(real_cfg, progress_enabled=True, ai_enabled=True)

    # Build a synthetic script directory.
    synth_dir = tmp_path / "migrations"
    shutil.copytree("migrations", synth_dir)
    versions_dir = synth_dir / "versions"
    # Chain off ai_001 (the real first ai migration) with branch_labels=None,
    # because the `ai` label is already claimed by ai_001.
    (versions_dir / "ai_test_002.py").write_text(
        textwrap.dedent(
            '''
            """synthetic ai branch test migration.

            Revision ID: ai_test_002
            Revises: ai_001
            Create Date: 2026-05-16 00:00:00.000000
            """

            import sqlalchemy as sa
            from alembic import op

            revision = "ai_test_002"
            down_revision = "ai_001"
            branch_labels = None
            depends_on = None


            def upgrade() -> None:
                op.execute("CREATE TABLE IF NOT EXISTS ai_branch_smoke (id int)")


            def downgrade() -> None:
                op.execute("DROP TABLE IF EXISTS ai_branch_smoke")
            '''
        )
    )

    cfg = _make_cfg(postgres_url, script_location=str(synth_dir))
    await _run_migrations_in_thread(cfg, progress_enabled=True, ai_enabled=True)

    eng = create_async_engine(postgres_url, future=True)
    versions = await _alembic_versions(eng)
    # ai branch advances to the new tip.
    assert "ai_test_002" in versions, versions
    await eng.dispose()

    # Cleanup: roll back the synthetic migration so other tests aren't affected.
    await _downgrade_in_thread(cfg, "ai_001")


async def test_synthetic_ai_branch_skipped_when_disabled(postgres_url: str, tmp_path: Path):
    """With ai_enabled=False, the wrapper skips advancing the ai branch.

    Pre-condition: DB is rolled back to 0004 (no ai branch applied), then the
    wrapper is invoked with ai_enabled=False. ai_test_002 (the synthetic head)
    must NOT be applied; the backbone stays at 0004.
    """
    # Stamp DB back to backbone (pre-ai-branch state) for this test.
    real_cfg = _make_cfg(postgres_url)
    await _downgrade_in_thread(real_cfg, "0004")

    synth_dir = tmp_path / "migrations"
    shutil.copytree("migrations", synth_dir)
    (synth_dir / "versions" / "ai_test_002.py").write_text(
        textwrap.dedent(
            '''
            """synthetic ai branch test migration."""
            import sqlalchemy as sa
            from alembic import op

            revision = "ai_test_002"
            down_revision = "ai_001"
            branch_labels = None
            depends_on = None

            def upgrade() -> None:
                op.execute("CREATE TABLE IF NOT EXISTS ai_branch_smoke_2 (id int)")

            def downgrade() -> None:
                op.execute("DROP TABLE IF EXISTS ai_branch_smoke_2")
            '''
        )
    )

    cfg = _make_cfg(postgres_url, script_location=str(synth_dir))
    await _run_migrations_in_thread(cfg, progress_enabled=True, ai_enabled=False)

    eng = create_async_engine(postgres_url, future=True)
    versions = await _alembic_versions(eng)
    await eng.dispose()
    # ai branch skipped → ai_test_002 NOT applied, and ai_001 NOT applied either.
    assert "ai_test_002" not in versions
    assert "ai_001" not in versions
    # Backbone still at 0004.
    assert "0004" in versions

    # Restore DB for subsequent tests.
    await _run_migrations_in_thread(real_cfg, progress_enabled=True, ai_enabled=True)
