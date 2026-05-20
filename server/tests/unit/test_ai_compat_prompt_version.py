"""PR-ε / Lock #19 — unit tests for ``_resolve_prompt_version``.

The helper lives in ``quire_server.core.ai._compat`` so importing it stays
inside the AI lazy-import boundary (coordinator §3.18). These tests do not
need the database fixture.
"""

from __future__ import annotations

from quire_server.core.ai._compat import _resolve_prompt_version
from quire_server.core.ai.prompts import PROMPT_VERSION


def test_resolve_prompt_version_legacy_default_uses_constant():
    """The legacy ``"1"`` is treated as 'unset' — constant wins. Lock #19."""
    assert _resolve_prompt_version("1") == PROMPT_VERSION
    assert _resolve_prompt_version(None) == PROMPT_VERSION
    assert _resolve_prompt_version("") == PROMPT_VERSION


def test_resolve_prompt_version_emergency_override_honored():
    """Lock #2: explicit non-default value is an emergency override."""
    assert _resolve_prompt_version("4") == "4"
    assert _resolve_prompt_version("5") == "5"
    assert _resolve_prompt_version("99") == "99"
