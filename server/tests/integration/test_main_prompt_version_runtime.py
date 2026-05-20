"""Runtime regression test for the PR-ε prompt-version wiring fix.

Verifies that ``create_app()`` constructs ``InsightOrchestrator`` with the
correct ``prompt_version`` under three scenarios:

  1. Default (no env override): orchestrator emits the in-code constant.
  2. Legacy ``"1"`` sentinel: orchestrator emits the in-code constant.
  3. Emergency override (``"4"`` set): orchestrator emits ``"4"``.

This is the cross-module test that would have caught the original bug
(``settings.ai_prompt_version`` flowing through unchanged from default).
"""

from __future__ import annotations

import pytest

pytestmark = pytest.mark.requires_ai


def _build_app(monkeypatch, postgres_url: str, env_overrides: dict[str, str]):
    """Build a fresh FastAPI app with the AI-enabled, AI-configured branch active."""
    monkeypatch.setenv("QUIRE_SERVER_DATABASE_URL", postgres_url)
    monkeypatch.setenv("QUIRE_SERVER_CWA_BASE_URL", "http://test-cwa")
    monkeypatch.setenv("QUIRE_SERVER_AI_ENABLED", "true")
    monkeypatch.setenv("QUIRE_SERVER_AI_BASE_URL", "http://test-ai/v1")
    monkeypatch.setenv("QUIRE_SERVER_AI_MODEL", "test-model")
    for k, v in env_overrides.items():
        monkeypatch.setenv(k, v)

    from quire_server.config import get_settings

    get_settings.cache_clear()
    from quire_server.main import create_app

    return create_app()


def test_runtime_prompt_version_default_uses_constant(monkeypatch, postgres_url, alembic_upgrade):
    """Default deploys (env var unset, settings default ``"1"``) → constant wins.

    PR-ε bug regression: before the fix, ``settings.ai_prompt_version`` flowed
    through unchanged so production was always emitting ``"1"`` instead of the
    real ``prompts.PROMPT_VERSION`` (Lock #19).
    """
    from quire_server.core.ai.prompts import PROMPT_VERSION

    app = _build_app(monkeypatch, postgres_url, {})
    orch = app.state.ai_orchestrator
    assert orch.prompt_version == PROMPT_VERSION
    # Explicit pin catches accidental constant drift.
    assert orch.prompt_version == "5"


def test_runtime_prompt_version_legacy_sentinel(monkeypatch, postgres_url, alembic_upgrade):
    """``QUIRE_SERVER_AI_PROMPT_VERSION="1"`` → still resolves to constant (Lock #19)."""
    from quire_server.core.ai.prompts import PROMPT_VERSION

    app = _build_app(monkeypatch, postgres_url, {"QUIRE_SERVER_AI_PROMPT_VERSION": "1"})
    orch = app.state.ai_orchestrator
    assert orch.prompt_version == PROMPT_VERSION


def test_runtime_prompt_version_emergency_override(monkeypatch, postgres_url, alembic_upgrade):
    """``QUIRE_SERVER_AI_PROMPT_VERSION="4"`` → emergency rollback honored (Lock #2)."""
    app = _build_app(monkeypatch, postgres_url, {"QUIRE_SERVER_AI_PROMPT_VERSION": "4"})
    orch = app.state.ai_orchestrator
    assert orch.prompt_version == "4"
