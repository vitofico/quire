"""Integration tests for scripts/migrate.py against a real Postgres + Alembic.

Cases covered:
1. Default state with real migrations: wrapper upgrades backbone + ai branch,
   DB ends at ai@head (currently `ai_005`).
2. Idempotent: running twice in a row is a no-op.
3. Synthetic branched script directory (tmp copy of migrations + an ai_test_006
   revision chained off the current ai@head):
   - ai_enabled=true → upgrades to ai_test_006.
   - ai_enabled=false → stays at 0004 (no ai branch applied).
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
    """Fresh DB → wrapper upgrades to backbone, then to ai@head (ai_005)."""

    # Wipe DB to fresh state.
    eng = create_async_engine(postgres_url, future=True)
    async with eng.begin() as conn:
        await conn.execute(text("DROP SCHEMA public CASCADE"))
        await conn.execute(text("CREATE SCHEMA public"))

    cfg = _make_cfg(postgres_url)
    await _run_migrations_in_thread(cfg, progress_enabled=True, ai_enabled=True)

    versions = await _alembic_versions(eng)
    await eng.dispose()
    # ai branch is at ai_005 (pr-α / Bundle 3 added reader_profiles). The
    # progress branch is at progress_002 (pr-α added abandoned_at).
    assert versions == {"ai_005", "progress_002"}


async def test_idempotent_second_run(postgres_url: str):
    """Running the wrapper twice in a row → still at every branch head, no errors."""

    cfg = _make_cfg(postgres_url)
    await _run_migrations_in_thread(cfg, progress_enabled=True, ai_enabled=True)
    await _run_migrations_in_thread(cfg, progress_enabled=True, ai_enabled=True)

    eng = create_async_engine(postgres_url, future=True)
    versions = await _alembic_versions(eng)
    await eng.dispose()
    assert versions == {"ai_005", "progress_002"}


async def test_synthetic_ai_branch_upgrades_when_enabled(postgres_url: str, tmp_path: Path):
    """Copy real migrations to tmp + add a synthetic ai_test_006 chained off
    ai_005; verify enabled run advances to ai_test_006 (the new ai@head)."""

    # First, ensure DB is at ai@head (real migrations include ai_001 .. ai_005).
    real_cfg = _make_cfg(postgres_url)
    await _run_migrations_in_thread(real_cfg, progress_enabled=True, ai_enabled=True)

    # Build a synthetic script directory.
    synth_dir = tmp_path / "migrations"
    shutil.copytree("migrations", synth_dir)
    versions_dir = synth_dir / "versions"
    # Chain off ai_005 (the current ai@head) with branch_labels=None — the `ai`
    # label is already claimed by ai_001.
    (versions_dir / "ai_test_006.py").write_text(
        textwrap.dedent(
            '''
            """synthetic ai branch test migration.

            Revision ID: ai_test_006
            Revises: ai_005
            Create Date: 2026-05-20 00:00:00.000000
            """

            import sqlalchemy as sa
            from alembic import op

            revision = "ai_test_006"
            down_revision = "ai_005"
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
    assert "ai_test_006" in versions, versions
    await eng.dispose()

    # Cleanup: roll back the synthetic migration so other tests aren't affected.
    await _downgrade_in_thread(cfg, "ai_005")


async def test_synthetic_ai_branch_skipped_when_disabled(postgres_url: str, tmp_path: Path):
    """With ai_enabled=False, the wrapper skips advancing the ai branch.

    Pre-condition: DB is rolled back to 0004 (no ai branch applied), then the
    wrapper is invoked with ai_enabled=False. ai_test_006 (the synthetic head)
    must NOT be applied; the backbone stays at 0004.
    """
    # Stamp DB back to backbone (pre-ai-branch state) for this test.
    real_cfg = _make_cfg(postgres_url)
    await _downgrade_in_thread(real_cfg, "0004")

    synth_dir = tmp_path / "migrations"
    shutil.copytree("migrations", synth_dir)
    (synth_dir / "versions" / "ai_test_006.py").write_text(
        textwrap.dedent(
            '''
            """synthetic ai branch test migration."""
            import sqlalchemy as sa
            from alembic import op

            revision = "ai_test_006"
            down_revision = "ai_005"
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
    # ai branch skipped → ai_test_006 not applied, ai_005 not applied,
    # ai_001 not applied. The `progress` branch still advanced.
    assert "ai_test_006" not in versions
    assert "ai_005" not in versions
    assert "ai_004" not in versions
    assert "ai_003" not in versions
    assert "ai_001" not in versions
    # progress branch advanced (progress_enabled=True). The backbone itself
    # is no longer a head once progress_002 sits on top of 0004.
    assert "progress_002" in versions

    # Restore DB for subsequent tests.
    await _run_migrations_in_thread(real_cfg, progress_enabled=True, ai_enabled=True)
