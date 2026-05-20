"""Back-compat helper for the OPDS_SYNC_ -> QUIRE_SERVER_ env prefix rename.

For one release cycle the server accepts both prefixes with the new prefix
winning. The legacy prefix triggers a one-shot WARNING per process per key.

Per Lock #21: only os.environ is consulted. .env files MUST use the new
prefix (documented as a known limitation in server/README.md).

Drop this module + its tests when the next release cycle starts; the
follow-up cleanup PR will remove the legacy prefix entirely.

Why a custom module instead of `env_prefix_aliases`: pydantic-settings is
locked at 2.14.1 (server/uv.lock), which does not implement
`env_prefix_aliases`. The kwarg is silently ignored, which is worse than
absent.
"""

from __future__ import annotations

import logging
import os
import threading
from typing import Any

from pydantic.fields import FieldInfo
from pydantic_settings.sources.providers.env import EnvSettingsSource

logger = logging.getLogger(__name__)

_LEGACY_PREFIX = "OPDS_SYNC_"
_NEW_PREFIX = "QUIRE_SERVER_"

_LOG_LOCK = threading.Lock()
_LEGACY_LOGGED: set[str] = set()
_BOTH_LOGGED: set[str] = set()


def reset_log_state_for_testing() -> None:
    """Test-only: clear one-shot WARNING state so each test starts clean.

    Public to tests only; do not call from production code.
    """
    with _LOG_LOCK:
        _LEGACY_LOGGED.clear()
        _BOTH_LOGGED.clear()


def resolve_env_prefix_value(
    new_key: str,
    *,
    legacy_key: str | None = None,
    default: str | None = None,
) -> str | None:
    """Read env, preferring QUIRE_SERVER_* over OPDS_SYNC_*.

    Args:
        new_key: full env var name with the new prefix
            (e.g. "QUIRE_SERVER_AI_ENABLED").
        legacy_key: full env var name with the legacy prefix. If None,
            derived from new_key by swapping QUIRE_SERVER_ -> OPDS_SYNC_.
        default: returned if neither key is set.

    Logs:
        WARNING once per process when only the legacy key is set.
        WARNING once per process when BOTH are set (new wins).

    Per Lock #21: only consults os.environ; the dotenv source is NOT
    routed through this helper. Users with OPDS_SYNC_* in .env must
    rename to QUIRE_SERVER_* or export real env vars.
    """
    if legacy_key is None:
        if not new_key.startswith(_NEW_PREFIX):
            raise ValueError(
                f"resolve_env_prefix_value expected new_key to start with "
                f"{_NEW_PREFIX!r}; got {new_key!r}"
            )
        legacy_key = _LEGACY_PREFIX + new_key[len(_NEW_PREFIX):]

    new_val = os.environ.get(new_key)
    legacy_val = os.environ.get(legacy_key)

    if new_val is not None and legacy_val is not None:
        with _LOG_LOCK:
            if new_key not in _BOTH_LOGGED:
                _BOTH_LOGGED.add(new_key)
                logger.warning(
                    "env.prefix.both_set new=%s legacy=%s (new wins)",
                    new_key,
                    legacy_key,
                )
        return new_val
    if new_val is not None:
        return new_val
    if legacy_val is not None:
        with _LOG_LOCK:
            if legacy_key not in _LEGACY_LOGGED:
                _LEGACY_LOGGED.add(legacy_key)
                logger.warning(
                    "env.prefix.legacy_used legacy=%s "
                    "(rename to %s before next release)",
                    legacy_key,
                    new_key,
                )
        return legacy_val
    return default


class LegacyEnvSettingsSource(EnvSettingsSource):
    """Settings source that consults both prefixes per field.

    The tuple returned by a pydantic-settings source is
    (value, field_key, value_is_complex). field_key is the model field key
    (e.g. "ai_enabled" or "ai_token_secrets"), NOT the env var name.
    value_is_complex must reflect whether the field is a complex type
    (list/dict/JSON) so the base class's prepare_field_value triggers
    JSON decoding.

    Implementation: delegate to the base class's _extract_field_info to
    learn (field_key, env_name, value_is_complex) for the new prefix, then
    derive the legacy env name by swapping the prefix and route the lookup
    through resolve_env_prefix_value.
    """

    def get_field_value(
        self, field: FieldInfo, field_name: str
    ) -> tuple[Any, str, bool]:
        # _extract_field_info yields (field_key, env_name, value_is_complex)
        # for each declared env-name candidate of this field. With env_prefix=
        # "QUIRE_SERVER_", the env_name carries the NEW prefix.
        env_val: str | None = None
        field_key = field_name
        value_is_complex = False
        for fk, env_name, vic in self._extract_field_info(field, field_name):
            field_key = fk
            value_is_complex = vic
            # Derive the legacy env name from the new env name.
            env_upper = env_name.upper()
            if env_upper.startswith(_NEW_PREFIX):
                legacy_env_name = _LEGACY_PREFIX + env_upper[len(_NEW_PREFIX):]
                env_val = resolve_env_prefix_value(
                    env_upper, legacy_key=legacy_env_name
                )
            else:
                # Defensive: field with an alias that does not carry our prefix
                # -> fall back to env-only lookup with no legacy mapping.
                env_val = self.env_vars.get(env_name)
            if env_val is not None:
                break

        return env_val, field_key, value_is_complex
