"""Always-on health endpoints (PR-A): /health and /readyz.

These mount at the root of the application (no prefix) regardless of mode,
so k8s liveness/readiness probes work in every deploy mode.

GET /health   liveness — does NOT touch the DB; returns enabled modes.
GET /readyz   readiness — checks DB connectivity AND that all required
              migration heads (per the enabled-modes + script-directory state)
              are present in `alembic_version`.
"""

from __future__ import annotations

import logging
from pathlib import Path

from fastapi import APIRouter, status
from fastapi.responses import JSONResponse
from sqlalchemy import text

from opds_sync.config import get_settings
from opds_sync.db.session import session_scope

router = APIRouter(tags=["health"])

logger = logging.getLogger(__name__)


def _enabled_modes(progress_enabled: bool, ai_enabled: bool) -> list[str]:
    """Stable order: progress before ai. Empty when both flags are false."""
    modes: list[str] = []
    if progress_enabled:
        modes.append("progress")
    if ai_enabled:
        modes.append("ai")
    return modes


def _alembic_ini_path() -> Path:
    """Locate alembic.ini relative to cwd. In the container it's /app/alembic.ini;
    in tests and `cd server`, it's ./alembic.ini."""
    return Path("alembic.ini")


def _declared_labels(rev) -> tuple[str, ...]:
    """Labels DECLARED on this revision (not propagated from descendants).

    `rev.branch_labels` (public) propagates labels backward to every ancestor,
    which makes it useless for "where is the label declared?". `_orig_branch_labels`
    is the canonical declared-labels storage and has been stable across
    Alembic 1.x.
    """
    return tuple(getattr(rev, "_orig_branch_labels", ()) or ())


def _existing_branch_labels(script) -> set[str]:
    """Set of all branch labels declared anywhere in the script directory."""
    labels: set[str] = set()
    for rev in script.walk_revisions():
        labels.update(_declared_labels(rev))
    return labels


def _backbone_head(script) -> str:
    """Return the last revision on the unlabeled linear backbone.

    Walks oldest→newest until the first revision that DECLARES a branch_label;
    returns the prior revision. We do NOT take a `get_heads()` shortcut
    because it would return a branch head (e.g. 'ai_001') once any branch
    exists, which is not the backbone tip.
    """
    backbone_tip: str | None = None
    # walk_revisions() is newest-to-oldest; reverse for oldest-to-newest.
    for rev in reversed(list(script.walk_revisions())):
        if _declared_labels(rev):
            break
        backbone_tip = rev.revision
    if backbone_tip is None:
        raise RuntimeError("no unlabeled backbone found in script directory")
    return backbone_tip


def _required_heads(script, progress_enabled: bool, ai_enabled: bool) -> set[str]:
    """Compute which migration heads the DB must contain for the current mode.

    Each enabled+materialized branch contributes its head. If nothing
    materialized ends up in the set, fall back to requiring the backbone
    tip (so a DB stamped below the backbone still fails readiness).
    """
    labels = _existing_branch_labels(script)
    required: set[str] = set()

    candidates = [(True, "core"), (progress_enabled, "progress"), (ai_enabled, "ai")]
    for enabled, label in candidates:
        if enabled and label in labels:
            rev = script.get_revision(f"{label}@head")
            required.add(rev.revision)

    if not required:
        required.add(_backbone_head(script))

    return required


def _is_at_or_past(script, current: str, target: str) -> bool:
    """Return True if `current` is `target` or any descendant of `target`."""
    if current == target:
        return True
    rev = script.get_revision(current)
    if rev is None:
        return False
    # Walk down_revisions backward to base; if we encounter `target`, current
    # descends from it.
    seen: set[str] = set()
    stack: list[str] = list(rev.down_revision or [])
    if isinstance(rev.down_revision, str):
        stack = [rev.down_revision]
    elif rev.down_revision is None:
        stack = []
    else:
        stack = list(rev.down_revision)
    while stack:
        rid = stack.pop()
        if rid in seen:
            continue
        seen.add(rid)
        if rid == target:
            return True
        parent = script.get_revision(rid)
        if parent is None:
            continue
        if isinstance(parent.down_revision, str):
            stack.append(parent.down_revision)
        elif parent.down_revision:
            stack.extend(parent.down_revision)
    return False


def _missing(script, required: set[str], current: set[str]) -> set[str]:
    """Return required revisions not satisfied by any current revision.

    A required revision R is satisfied if any current revision C equals R or
    descends from R. This handles the "DB has ai_001, neither mode enabled,
    fallback required is 0004" case: 0004 is an ancestor of ai_001, so the
    backbone is satisfied even though current != required.
    """
    out: set[str] = set()
    for r in required:
        if not any(_is_at_or_past(script, c, r) for c in current):
            out.add(r)
    return out


async def _db_alembic_heads() -> set[str]:
    """Return the set of revisions in the DB's alembic_version table."""
    async with session_scope() as s:
        result = await s.execute(text("SELECT version_num FROM alembic_version"))
        return {row[0] for row in result.fetchall()}


@router.get("/health")
async def health() -> dict:
    settings = get_settings()
    return {
        "ready": True,
        "modes": _enabled_modes(settings.progress_enabled, settings.ai_enabled),
    }


@router.get("/readyz")
async def readyz():
    settings = get_settings()
    modes = _enabled_modes(settings.progress_enabled, settings.ai_enabled)

    # DB connectivity.
    try:
        async with session_scope() as s:
            await s.execute(text("SELECT 1"))
    except Exception as e:  # noqa: BLE001 — readiness must not leak details
        logger.warning("readyz: db unreachable: %s", e)
        return JSONResponse(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            content={"ready": False, "detail": "db unreachable", "modes": modes},
        )

    # Heads check.
    try:
        from alembic.config import Config
        from alembic.script import ScriptDirectory

        cfg = Config(str(_alembic_ini_path()))
        script = ScriptDirectory.from_config(cfg)
        required = _required_heads(script, settings.progress_enabled, settings.ai_enabled)
        current = await _db_alembic_heads()
    except Exception as e:  # noqa: BLE001
        logger.warning("readyz: heads check failed: %s", e)
        return JSONResponse(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            content={"ready": False, "detail": "alembic state unreadable", "modes": modes},
        )

    missing = _missing(script, required, current)
    if missing:
        return JSONResponse(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            content={
                "ready": False,
                "detail": "migrations behind",
                "modes": modes,
                "missing": sorted(missing),
                "current": sorted(current),
            },
        )

    return {
        "ready": True,
        "modes": modes,
        "heads_applied": sorted(current),
    }
