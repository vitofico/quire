"""PR-ε / Lock #19 — prompt-version resolution helper.

Lives in ``core.ai._compat`` (not ``main.py``) to preserve the provider
lazy-import boundary (coordinator §3.18 / CC-12):

  * The existing test at ``server/tests/integration/test_lazy_imports.py``
    asserts that sync-only deploys never import ``quire_server.core.ai.*``.
  * ``main.py`` imports ``_resolve_prompt_version`` INSIDE the existing
    ``if settings.ai_enabled:`` block alongside the other ``core.ai.*``
    imports.

Importing ``prompts.PROMPT_VERSION`` at the top of ``main.py`` would break
that contract; routing through this module keeps it intact.
"""

from __future__ import annotations

from quire_server.core.ai.prompts import PROMPT_VERSION


def _resolve_prompt_version(env_value: str | None) -> str:
    """Resolve the runtime prompt_version per coordinator.md §3.1 / Lock #19.

    The in-code ``PROMPT_VERSION`` constant is the source of truth. The
    settings field ``ai_prompt_version`` (env var
    ``QUIRE_SERVER_AI_PROMPT_VERSION``) defaults to the legacy value ``"1"``;
    that value is treated as "unset" so the constant wins on untouched
    deploys. Any other value is honored verbatim — this is the emergency
    rollback override of Lock #2 (e.g. pin v4 during incident response while
    v5 misbehaves).
    """
    if env_value is None or env_value == "" or env_value == "1":
        return PROMPT_VERSION
    return env_value
