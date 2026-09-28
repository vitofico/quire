"""AI settings an admin can change while the server runs (issue #102).

The settings in ``EDITABLE`` can be changed on the ``/quire-admin`` status
page without editing ``.env`` and restarting. For each one, the value in
force comes from the first of:

1. The environment, ``.env`` included. A value set there wins, and the page
   shows it as locked, so a deploy described by a compose file or a
   Kubernetes manifest never drifts from what that file says.
2. A value saved on the status page: one row in ``server_settings``.
3. The code default in ``Settings``.

Everything that uses these settings reads them from the ``RuntimeSettings``
on ``app.state.runtime_settings``. The process that saves a value applies it
at once; any other server process re-reads the table at most
``REFRESH_AFTER_S`` seconds later.
"""

from __future__ import annotations

import asyncio
import logging
import math
import time
from collections.abc import Callable, Iterable, Mapping
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any

from fastapi import FastAPI, Request
from sqlalchemy import delete, func, select
from sqlalchemy.dialects.postgresql import insert

from quire_server.config import (
    ENV_PREFIX,
    KNOWN_AI_SOURCES,
    Settings,
    _ai_source_names,
    get_settings,
    parse_ai_sources,
)
from quire_server.db.models import ServerSetting
from quire_server.db.session import session_scope

logger = logging.getLogger(__name__)

# The settings the status page can change. Connection targets and secrets
# (`ai_base_url`, `ai_model`, `ai_api_key`) stay environment-only: the server
# decides at boot whether AI is configured from the first two.
EDITABLE: tuple[str, ...] = (
    "ai_timeout_s",
    "ai_profile_timeout_s",
    "ai_sources",
    "ai_rate_per_min",
    "ai_daily_budget",
    "ai_regen_daily_limit",
)

REFRESH_AFTER_S = 30.0

# (type, smallest, largest, what the value must be). The lower bounds follow
# the configuration guide: a rate below 1 behaves as 1, a zero budget turns
# the budget off, a zero regeneration limit blocks regeneration. The upper
# bounds only keep out values that are typos, or too large to use (an int
# the rate limiter cannot turn into a float). The app gives up after 10
# minutes whatever the time limit says.
_NUMBERS: dict[str, tuple[type, float, float, str]] = {
    "ai_timeout_s": (float, 0, 3600, "a number of seconds above 0, at most 3600"),
    "ai_profile_timeout_s": (float, 0, 3600, "a number of seconds above 0, at most 3600"),
    "ai_rate_per_min": (int, 1, 1_000_000, "a whole number from 1 to 1000000"),
    "ai_daily_budget": (int, 0, 1_000_000, "a whole number from 0 to 1000000"),
    "ai_regen_daily_limit": (int, 0, 1_000_000, "a whole number from 0 to 1000000"),
}


def env_name(key: str) -> str:
    """``ai_timeout_s`` as ``QUIRE_SERVER_AI_TIMEOUT_S``."""
    return f"{ENV_PREFIX}{key.upper()}"


class InvalidSetting(ValueError):
    """A value the status page must not save, with a sentence saying why."""


class SettingsRejected(Exception):
    """One or more changes were refused; ``errors`` maps each key to a sentence."""

    def __init__(self, errors: dict[str, str]) -> None:
        super().__init__("; ".join(errors.values()))
        self.errors = errors


def parse_value(key: str, raw: object) -> Any:
    """``raw`` as the value ``key`` has in ``Settings``, or ``InvalidSetting``.

    Accepts what a form sends (text) and what JSON sends (numbers, or a list
    of names for ``ai_sources``). Sources come back as the canonical comma
    list, so a saved value reads the same way the environment's does.
    """
    name = env_name(key)
    if key == "ai_sources":
        if isinstance(raw, str):
            names = _ai_source_names(raw)
        elif isinstance(raw, list | tuple) and all(isinstance(n, str) for n in raw):
            names = _ai_source_names(",".join(raw))
        else:
            raise InvalidSetting(f"{name} must be a list of source names.")
        unknown = [n for n in names if n not in KNOWN_AI_SOURCES]
        if unknown:
            raise InvalidSetting(
                f"{name} knows only {' and '.join(KNOWN_AI_SOURCES)}, not {', '.join(unknown)}."
            )
        return ",".join(dict.fromkeys(names))

    kind, minimum, maximum, must_be = _NUMBERS[key]
    value: float | int
    try:
        if isinstance(raw, bool):
            raise ValueError
        if kind is int:
            value = int(raw.strip()) if isinstance(raw, str) else int(raw)  # type: ignore[arg-type]
            if isinstance(raw, float) and not raw.is_integer():
                raise ValueError
        else:
            value = float(raw.strip()) if isinstance(raw, str) else float(raw)  # type: ignore[arg-type]
            if not math.isfinite(value):
                raise ValueError
    except (TypeError, ValueError, OverflowError):
        # OverflowError: int(float("inf")); JSON bodies may carry Infinity.
        raise InvalidSetting(f"{name} must be {must_be}.") from None
    if value < minimum or value > maximum or (kind is float and value == minimum):
        raise InvalidSetting(f"{name} must be {must_be}.")
    return value


@dataclass(frozen=True)
class SavedSetting:
    value: Any
    updated_at: datetime
    updated_by: str


class RuntimeSettings:
    """The value in force for each ``EDITABLE`` setting, and the way to save one."""

    def __init__(self, settings: Settings, *, refresh_after_s: float = REFRESH_AFTER_S) -> None:
        self._settings = settings
        self._refresh_after_s = refresh_after_s
        self._saved: dict[str, SavedSetting] = {}
        self._read_at: float | None = None
        self._lock = asyncio.Lock()
        self._listeners: list[Callable[[], None]] = []

    # -- reading --------------------------------------------------------------

    def locked(self, key: str) -> bool:
        """Whether the environment sets ``key``, which then cannot be changed here."""
        return key in self._settings.model_fields_set

    def saved(self, key: str) -> SavedSetting | None:
        """The value saved on the status page, if it is the one in force."""
        return None if self.locked(key) else self._saved.get(key)

    def source(self, key: str) -> str:
        """``set`` (environment), ``saved`` (status page) or ``default``."""
        if self.locked(key):
            return "set"
        return "saved" if key in self._saved else "default"

    def get(self, key: str) -> Any:
        saved = self.saved(key)
        return getattr(self._settings, key) if saved is None else saved.value

    @staticmethod
    def default(key: str) -> Any:
        return Settings.model_fields[key].default

    @property
    def sources(self) -> tuple[str, ...]:
        """The retrieval sources in force, as ``parse_ai_sources`` reads them."""
        return parse_ai_sources(self.get("ai_sources"))

    def on_change(self, listener: Callable[[], None]) -> None:
        """Call ``listener`` now and whenever the values in force change."""
        self._listeners.append(listener)
        listener()

    # -- the database ---------------------------------------------------------

    def _fresh(self) -> bool:
        if self._read_at is None:
            return False
        return time.monotonic() - self._read_at < self._refresh_after_s

    async def refresh(self, *, force: bool = False) -> None:
        """Re-read the saved values if the last read is older than the refresh interval.

        Never raises. A table that cannot be read (database down, migrations
        behind) leaves the values in force as they were, and the next read is
        tried after the same interval rather than on every request.
        """
        if not force and self._fresh():
            return
        async with self._lock:
            if not force and self._fresh():
                return
            try:
                async with session_scope() as session:
                    rows = (await session.scalars(select(ServerSetting))).all()
            except Exception as exc:  # noqa: BLE001 — the values in force still work
                logger.warning(
                    "event=runtime_settings.unreadable error_class=%s", type(exc).__name__
                )
                self._read_at = time.monotonic()
                return
            self._read_at = time.monotonic()
            self._replace(rows)

    def _set(self, saved: dict[str, SavedSetting]) -> None:
        if saved != self._saved:
            self._saved = saved
            for listener in self._listeners:
                listener()

    def _replace(self, rows: Iterable[ServerSetting]) -> None:
        saved: dict[str, SavedSetting] = {}
        for row in rows:
            if row.key not in EDITABLE:
                continue
            try:
                value = parse_value(row.key, row.value)
            except InvalidSetting as exc:
                # Only a hand-edited row gets here; the page never saves one.
                logger.warning("event=runtime_settings.ignored key=%s reason=%s", row.key, exc)
                continue
            saved[row.key] = SavedSetting(value, row.updated_at, row.updated_by)
        self._set(saved)

    def check(self, changes: Mapping[str, object]) -> dict[str, Any]:
        """Parse every change, or raise ``SettingsRejected`` naming each bad one.

        ``None`` for a key means "remove the saved value", which brings back
        the default.
        """
        parsed: dict[str, Any] = {}
        errors: dict[str, str] = {}
        for key, raw in changes.items():
            if key not in EDITABLE:
                errors[key] = f"{env_name(key)} cannot be changed on the status page."
            elif self.locked(key):
                errors[key] = (
                    f"{env_name(key)} is set in the server's environment, which takes "
                    "precedence. Remove it there and restart the server to change it here."
                )
            elif raw is None:
                parsed[key] = None
            else:
                try:
                    parsed[key] = parse_value(key, raw)
                except InvalidSetting as exc:
                    errors[key] = str(exc)
        if errors:
            raise SettingsRejected(errors)
        return parsed

    async def save(self, changes: Mapping[str, object], *, user: str) -> None:
        """Check every change, then write them all in one transaction."""
        parsed = self.check(changes)
        if not parsed:
            return
        async with session_scope() as session:
            for key, value in parsed.items():
                if value is None:
                    await session.execute(delete(ServerSetting).where(ServerSetting.key == key))
                    continue
                stmt = insert(ServerSetting).values(key=key, value=str(value), updated_by=user)
                await session.execute(
                    stmt.on_conflict_do_update(
                        index_elements=[ServerSetting.key],
                        set_={
                            "value": stmt.excluded.value,
                            "updated_by": stmt.excluded.updated_by,
                            "updated_at": func.now(),
                        },
                    )
                )
            await session.commit()
        logger.info(
            "event=runtime_settings.saved user_id=%s keys=%s", user, ",".join(sorted(parsed))
        )
        # Apply what was committed here and now, so the change holds even if
        # the read-back below fails; the read-back then brings the database's
        # own timestamps and anything another process saved meanwhile.
        saved = dict(self._saved)
        now = datetime.now(UTC)
        for key, value in parsed.items():
            if value is None:
                saved.pop(key, None)
            else:
                saved[key] = SavedSetting(value, now, user)
        self._set(saved)
        await self.refresh(force=True)


def runtime_settings(app: FastAPI) -> RuntimeSettings:
    """The app's ``RuntimeSettings``; one over the environment alone if none is wired."""
    runtime = getattr(app.state, "runtime_settings", None)
    return runtime if runtime is not None else RuntimeSettings(get_settings())


async def refresh_runtime_settings(request: Request) -> None:
    """Router dependency: bring saved values up to date before an AI request."""
    runtime = getattr(request.app.state, "runtime_settings", None)
    if runtime is not None:
        await runtime.refresh()
