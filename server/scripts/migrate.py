#!/usr/bin/env python3
"""Forward-only deploy migrator (PR-A).

Reads QUIRE_SERVER_PROGRESS_ENABLED / QUIRE_SERVER_AI_ENABLED (with
one-cycle back-compat for the legacy prefix via the env-compat helper —
see `quire_server/_env_compat.py`), then:
  1. Always upgrades the unlabeled backbone to its tip (e.g. 0004 today).
  2. Per enabled+materialized branch: runs `alembic upgrade <branch>@head`.

Idempotent. Forward-only. Per-branch downgrades remain a manual op
(`alembic downgrade <branch>@-1`).

Implementation notes:
- We use Alembic's `ScriptDirectory` + `command.upgrade()` rather than
  shelling out to the `alembic` CLI and parsing its output. The latter is
  brittle (output format isn't stable API and revision-id substrings
  collide with label substrings).
- Step 1 (backbone) is always run, even when both flags are false, because
  the backbone is the foundation every branch depends on and a fresh DB
  must reach 0004 regardless of mode.
"""

from __future__ import annotations

import logging
import os
import sys
from pathlib import Path

from alembic import command
from alembic.config import Config
from alembic.script import ScriptDirectory

logging.basicConfig(level=logging.INFO, format="[migrate] %(message)s")
logger = logging.getLogger("migrate")


def _is_truthy(val: str | None, default: bool = True) -> bool:
    if val is None:
        return default
    return val.strip().lower() in {"1", "true", "yes", "on"}


def _declared_labels(rev) -> tuple[str, ...]:
    """Return labels DECLARED on this revision (not inherited from descendants).

    Alembic's public `rev.branch_labels` property propagates labels backward to
    every ancestor of a labeled revision, which makes it useless for the question
    "where is the branch label declared?". The canonical declared-labels storage
    is `_orig_branch_labels`, which has been stable across Alembic 1.x.
    """
    return tuple(getattr(rev, "_orig_branch_labels", ()) or ())


def _existing_branch_labels(script: ScriptDirectory) -> set[str]:
    """Return all branch labels declared anywhere in the script directory."""
    labels: set[str] = set()
    for rev in script.walk_revisions():
        labels.update(_declared_labels(rev))
    return labels


def _backbone_head(script: ScriptDirectory) -> str:
    """Return the last revision on the unlabeled linear backbone.

    Walks oldest→newest until the first revision that DECLARES a branch_label;
    returns the prior revision (the backbone tip). We do NOT take a
    `get_heads()` shortcut because it would return a branch head (e.g.
    'ai_001') once any branch exists, which is not the backbone tip.
    """
    backbone_tip: str | None = None
    for rev in reversed(list(script.walk_revisions())):
        if _declared_labels(rev):
            break
        backbone_tip = rev.revision
    if backbone_tip is None:
        raise RuntimeError("no unlabeled backbone found in script directory")
    return backbone_tip


def _upgrade_branch(cfg: Config, branch: str) -> None:
    logger.info("upgrading %s@head", branch)
    command.upgrade(cfg, f"{branch}@head")


def run_migrations(cfg: Config, *, progress_enabled: bool, ai_enabled: bool) -> None:
    """Run the migrate procedure with explicit args (used by tests + main)."""
    script = ScriptDirectory.from_config(cfg)
    labels = _existing_branch_labels(script)

    # Step 1: always ensure the backbone is applied.
    backbone = _backbone_head(script)
    logger.info("upgrading backbone to %s", backbone)
    command.upgrade(cfg, backbone)

    # Step 2: per-branch upgrades, deterministic order.
    if "core" in labels:
        _upgrade_branch(cfg, "core")

    if progress_enabled and "progress" in labels:
        _upgrade_branch(cfg, "progress")
    elif progress_enabled:
        logger.info("progress enabled but no progress branch in script directory; skipping")

    if ai_enabled and "ai" in labels:
        _upgrade_branch(cfg, "ai")
    elif ai_enabled:
        logger.info("ai enabled but no ai branch in script directory; skipping")


def main() -> int:
    cfg_path = Path(os.environ.get("ALEMBIC_INI", "alembic.ini"))
    if not cfg_path.exists():
        logger.error("alembic config not found at %s", cfg_path)
        return 2

    cfg = Config(str(cfg_path))
    # Imported here so test patches of `scripts.migrate.run_migrations`
    # (and ideally also `resolve_env_prefix_value` for assertion-only
    # cases) take effect on this module's attribute, not the source module.
    from quire_server._env_compat import resolve_env_prefix_value

    progress_enabled = _is_truthy(resolve_env_prefix_value("QUIRE_SERVER_PROGRESS_ENABLED"))
    ai_enabled = _is_truthy(resolve_env_prefix_value("QUIRE_SERVER_AI_ENABLED"))
    logger.info("modes: progress=%s ai=%s", progress_enabled, ai_enabled)

    run_migrations(cfg, progress_enabled=progress_enabled, ai_enabled=ai_enabled)
    logger.info("done")
    return 0


if __name__ == "__main__":
    sys.exit(main())
