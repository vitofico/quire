"""Keep the docs and compose files from drifting away from ``Settings``.

Issue #104: settings written in ``.env`` never reached the container because
the compose files forwarded an explicit allowlist, and the README table was
missing seven fields. These checks fail the unit suite whenever:

* a ``QUIRE_SERVER_*`` name appears in the docs or compose files that the
  server does not read (a typo, or a field that was renamed or removed);
* ``.env.example`` stops mentioning one of the names in
  ``ENV_EXAMPLE_MUST_MENTION`` below (a mention inside a comment counts,
  since the ``[ai]`` block is commented out on purpose);
* a compose file stops loading ``.env`` wholesale.
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest

from quire_server.config import COMPOSE_ONLY_ENV_VARS, ENV_PREFIX, Settings

SERVER_DIR = Path(__file__).resolve().parents[2]
REPO_DIR = SERVER_DIR.parent

DOC_FILES = {
    "server/.env.example": SERVER_DIR / ".env.example",
    "server/README.md": SERVER_DIR / "README.md",
    "server/docker-compose.yml": SERVER_DIR / "docker-compose.yml",
    "server/docker-compose.full.yml": SERVER_DIR / "docker-compose.full.yml",
    "docs/sync-api.md": REPO_DIR / "docs" / "sync-api.md",
}

# Matches QUIRE_SERVER_FOO. Does not match the README's glob QUIRE_SERVER_AI_*
# or the bare prefix QUIRE_SERVER_ used in prose.
TOKEN_RE = re.compile(r"\bQUIRE_SERVER_[A-Z0-9_]+\b(?!\*)")

KNOWN = {f"{ENV_PREFIX}{name.upper()}" for name in Settings.model_fields} | COMPOSE_ONLY_ENV_VARS

ENV_EXAMPLE_MUST_MENTION = (
    "QUIRE_SERVER_CWA_BASE_URL",
    "QUIRE_SERVER_PROGRESS_ENABLED",
    "QUIRE_SERVER_AI_ENABLED",
    "QUIRE_SERVER_AUTH_BACKEND",
    "QUIRE_SERVER_LOG_LEVEL",
    "QUIRE_SERVER_AI_BASE_URL",
    "QUIRE_SERVER_AI_MODEL",
    "QUIRE_SERVER_AI_API_KEY",
    "QUIRE_SERVER_AI_TIMEOUT_S",
    "QUIRE_SERVER_AI_RETRIEVAL_TIMEOUT_S",
)

ENV_FILE_RE = re.compile(
    r"^\s+env_file:\s*\n\s+- path: \.env\s*\n\s+required: false\s*$", re.MULTILINE
)


@pytest.mark.parametrize("label", sorted(DOC_FILES))
def test_every_documented_variable_is_read_by_the_server(label):
    text = DOC_FILES[label].read_text(encoding="utf-8")
    unknown = sorted(set(TOKEN_RE.findall(text)) - KNOWN)
    assert unknown == [], f"{label} mentions variables the server does not read: {unknown}"


def test_env_example_covers_what_a_new_operator_needs():
    text = DOC_FILES["server/.env.example"].read_text(encoding="utf-8")
    present = set(TOKEN_RE.findall(text))
    missing = [name for name in ENV_EXAMPLE_MUST_MENTION if name not in present]
    assert missing == [], f"server/.env.example is missing: {missing}"


@pytest.mark.parametrize("label", ["server/docker-compose.yml", "server/docker-compose.full.yml"])
def test_compose_loads_dotenv_wholesale(label):
    text = DOC_FILES[label].read_text(encoding="utf-8")
    assert ENV_FILE_RE.search(text), f"{label} must load .env via env_file (path + required: false)"


def test_readme_documents_every_setting():
    text = DOC_FILES["server/README.md"].read_text(encoding="utf-8")
    present = set(TOKEN_RE.findall(text))
    missing = sorted(
        f"{ENV_PREFIX}{name.upper()}"
        for name in Settings.model_fields
        if f"{ENV_PREFIX}{name.upper()}" not in present
    )
    assert missing == [], f"server/README.md is missing rows for: {missing}"
