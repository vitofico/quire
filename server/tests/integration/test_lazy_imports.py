"""Lazy provider import boundary verification.

PR-A requires that:
  * sync-only mode never imports the AI client / retrieval / service / prompts
    or the /ai router module.
  * ai-only mode never imports the /sync (progress) router module.

We use subprocess isolation (not `importlib.reload`) because module caches in
the test process bleed between cases and there's no honest way to "undo" an
import. The subprocess imports opds_sync.main, calls create_app(), then
prints sys.modules so the parent can assert.
"""

from __future__ import annotations

import json
import subprocess
import sys
import textwrap

import pytest

# Modules that should NOT load in sync-only mode (provider boundary).
SYNC_ONLY_FORBIDDEN = [
    "opds_sync.api.ai",
    "opds_sync.core.ai.client",
    "opds_sync.core.ai.retrieval",
    "opds_sync.core.ai.service",
    "opds_sync.core.ai.prompts",
]

# Modules that should NOT load in ai-only mode.
AI_ONLY_FORBIDDEN = [
    "opds_sync.api.progress",
]


def _run_in_subprocess(env_overrides: dict[str, str], postgres_url: str) -> set[str]:
    """Spawn a fresh interpreter, build the app, return the loaded module set."""
    code = textwrap.dedent(
        """
        import json, sys
        from opds_sync.config import get_settings
        get_settings.cache_clear()
        import opds_sync.main as m
        # Calling create_app() (not just importing the module) triggers any
        # mode-gated imports. Both paths must stay lazy in non-AI modes.
        m.create_app()
        print(json.dumps(sorted(sys.modules.keys())))
        """
    ).strip()

    env = {
        "OPDS_SYNC_DATABASE_URL": postgres_url,
        "OPDS_SYNC_CWA_BASE_URL": "http://test-cwa",
        "PATH": "/usr/bin:/bin",
    }
    env.update(env_overrides)

    # `sys.executable -c` so we pick up the same venv that runs pytest.
    result = subprocess.run(
        [sys.executable, "-c", code],
        capture_output=True,
        text=True,
        env=env,
        timeout=60,
    )
    if result.returncode != 0:
        rc = result.returncode
        out = result.stdout
        err = result.stderr
        pytest.fail(f"subprocess failed (rc={rc}):\nstdout={out}\nstderr={err}")
    # The last line is the JSON list; earlier lines may be log output from
    # configure() / logging.basicConfig.
    lines = [line for line in result.stdout.strip().splitlines() if line.startswith("[")]
    assert lines, f"no JSON output in subprocess stdout: {result.stdout!r}"
    return set(json.loads(lines[-1]))


def test_sync_only_does_not_import_ai_modules(postgres_url: str):
    loaded = _run_in_subprocess(
        {
            "OPDS_SYNC_PROGRESS_ENABLED": "true",
            "OPDS_SYNC_AI_ENABLED": "false",
        },
        postgres_url,
    )
    for forbidden in SYNC_ONLY_FORBIDDEN:
        assert forbidden not in loaded, f"sync-only mode loaded forbidden module: {forbidden}"


def test_ai_only_does_not_import_progress_router(postgres_url: str):
    loaded = _run_in_subprocess(
        {
            "OPDS_SYNC_PROGRESS_ENABLED": "false",
            "OPDS_SYNC_AI_ENABLED": "true",
        },
        postgres_url,
    )
    for forbidden in AI_ONLY_FORBIDDEN:
        assert forbidden not in loaded, f"ai-only mode loaded forbidden module: {forbidden}"


def test_neither_mode_loads_neither(postgres_url: str):
    loaded = _run_in_subprocess(
        {
            "OPDS_SYNC_PROGRESS_ENABLED": "false",
            "OPDS_SYNC_AI_ENABLED": "false",
        },
        postgres_url,
    )
    for forbidden in SYNC_ONLY_FORBIDDEN + AI_ONLY_FORBIDDEN:
        assert forbidden not in loaded, f"neither mode loaded forbidden module: {forbidden}"
