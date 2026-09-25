"""Keep the docs and compose files from drifting away from ``Settings``.

Issue #104: settings written in ``.env`` never reached the container because
the compose files forwarded an explicit allowlist, and the README table was
missing seven fields. These checks fail the unit suite whenever:

* a ``QUIRE_SERVER_*`` name appears in the docs or compose files that the
  server does not read (a typo, or a field that was renamed or removed);
* ``docs/configuration.md`` has no heading for a variable the code reads:
  a ``Settings`` field, a compose or Caddyfile placeholder, or a name read
  straight from ``os.environ``;
* ``docs/configuration.md`` has a heading for a variable nothing reads;
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
GUIDE = "docs/configuration.md"

DOC_FILES = {
    "server/.env.example": SERVER_DIR / ".env.example",
    "server/README.md": SERVER_DIR / "README.md",
    "server/docker-compose.yml": SERVER_DIR / "docker-compose.yml",
    "server/docker-compose.full.yml": SERVER_DIR / "docker-compose.full.yml",
    "docs/sync-api.md": REPO_DIR / "docs" / "sync-api.md",
    GUIDE: REPO_DIR / "docs" / "configuration.md",
}

# Matches QUIRE_SERVER_FOO. Does not match the README's glob QUIRE_SERVER_AI_*
# or the bare prefix QUIRE_SERVER_ used in prose.
TOKEN_RE = re.compile(r"\bQUIRE_SERVER_[A-Z0-9_]+\b(?!\*)")

KNOWN = {f"{ENV_PREFIX}{name.upper()}" for name in Settings.model_fields} | COMPOSE_ONLY_ENV_VARS

# The guide shows the boot warning a misspelled variable produces, so it names
# one on purpose.
DELIBERATE_TYPOS = {GUIDE: {"QUIRE_SERVER_AI_TIMEOUT"}}

# Hand-maintained: the `[ai]` block in `.env.example` is deliberately commented
# out, so this list cannot be derived from `Settings.model_fields`.
ENV_EXAMPLE_MUST_MENTION = (
    "QUIRE_SERVER_CWA_BASE_URL",
    "QUIRE_SERVER_PROGRESS_ENABLED",
    "QUIRE_SERVER_AI_ENABLED",
    "QUIRE_SERVER_AUTH_BACKEND",
    "QUIRE_SERVER_LOG_LEVEL",
    "QUIRE_SERVER_ADMIN_USERS",
    "QUIRE_SERVER_AI_BASE_URL",
    "QUIRE_SERVER_AI_MODEL",
    "QUIRE_SERVER_AI_API_KEY",
    "QUIRE_SERVER_AI_TIMEOUT_S",
    "QUIRE_SERVER_AI_RETRIEVAL_TIMEOUT_S",
)

ENV_FILE_RE = re.compile(
    r"^\s+env_file:\s*\n\s+- path: \.env\s*\n\s+required: false\s*$", re.MULTILINE
)

# `${NAME}`, `${NAME:-default}` and `${NAME:?message}` in a compose file;
# `{$NAME}` and `{$NAME:default}` in a Caddyfile.
COMPOSE_VAR_RE = re.compile(r"\$\{([A-Z_][A-Z0-9_]*)")
CADDY_VAR_RE = re.compile(r"\{\$([A-Z_][A-Z0-9_]*)")
# os.environ.get("NAME"), os.getenv("NAME"), os.environ["NAME"]
ENVIRON_READ_RE = re.compile(
    r"""os\.(?:environ\.get|getenv)\(\s*["']([A-Z_][A-Z0-9_]*)["']"""
    r"""|os\.environ\[\s*["']([A-Z_][A-Z0-9_]*)["']\s*\]"""
)
# A backticked variable name inside a Markdown heading of level 2 to 6.
HEADING_RE = re.compile(r"^#{2,6} .*$", re.MULTILINE)
HEADING_NAME_RE = re.compile(r"`([A-Z_][A-Z0-9_]*)`")


def _compose_and_caddy_vars() -> set[str]:
    names: set[str] = set()
    for path in (SERVER_DIR / "docker-compose.yml", SERVER_DIR / "docker-compose.full.yml"):
        names.update(COMPOSE_VAR_RE.findall(path.read_text(encoding="utf-8")))
    names.update(CADDY_VAR_RE.findall((SERVER_DIR / "caddy" / "Caddyfile").read_text("utf-8")))
    return names


def _environ_reads() -> set[str]:
    names: set[str] = set()
    for root in ("quire_server", "scripts", "migrations"):
        for path in (SERVER_DIR / root).rglob("*.py"):
            for match in ENVIRON_READ_RE.finditer(path.read_text(encoding="utf-8")):
                names.add(match.group(1) or match.group(2))
    return names


VARIABLE_SOURCES = {
    "Settings fields": {f"{ENV_PREFIX}{name.upper()}" for name in Settings.model_fields},
    "compose-only names": set(COMPOSE_ONLY_ENV_VARS),
    "compose and Caddyfile placeholders": _compose_and_caddy_vars(),
    "direct os.environ reads": _environ_reads(),
}


def _guide_headings() -> set[str]:
    text = DOC_FILES[GUIDE].read_text(encoding="utf-8")
    return {
        name for heading in HEADING_RE.findall(text) for name in HEADING_NAME_RE.findall(heading)
    }


@pytest.mark.parametrize("label", sorted(DOC_FILES))
def test_every_documented_variable_is_read_by_the_server(label):
    text = DOC_FILES[label].read_text(encoding="utf-8")
    allowed = KNOWN | DELIBERATE_TYPOS.get(label, set())
    unknown = sorted(set(TOKEN_RE.findall(text)) - allowed)
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


@pytest.mark.parametrize("source", sorted(VARIABLE_SOURCES))
def test_configuration_guide_has_a_heading_for_every_variable(source):
    missing = sorted(VARIABLE_SOURCES[source] - _guide_headings())
    assert missing == [], f"{GUIDE} has no heading for these {source}: {missing}"


def test_configuration_guide_headings_name_only_variables_that_exist():
    known = set().union(*VARIABLE_SOURCES.values())
    stale = sorted(_guide_headings() - known)
    assert stale == [], f"{GUIDE} has headings for variables nothing reads: {stale}"
