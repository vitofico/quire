"""Encryption at rest for the logins of synced catalogs.

Each catalog saved to an account keeps its address and login in one
AES-256-GCM ciphertext, ``<key id>:<base64 of nonce + sealed JSON>``. The
associated data binds it to the row's ``(user_id, id)``, so a row copied to
another account fails to decrypt instead of leaking.

The key never lives in the database, so a dump exposes nothing usable. It
comes from ``QUIRE_SERVER_CATALOG_KEYS``, else from the key file, else a new
key file is created on first start (``create_catalog_key``). With no key the
feature is off; there is no plaintext fallback.
"""

from __future__ import annotations

import base64
import binascii
import json
import logging
import os
import re
import secrets
import tempfile
from collections.abc import Mapping
from pathlib import Path
from typing import Literal
from uuid import UUID

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from sqlalchemy import exists, select
from sqlalchemy.ext.asyncio import AsyncSession

from quire_server.config import Settings
from quire_server.db.models import AccountCatalog

logger = logging.getLogger(__name__)

KeySource = Literal["environment", "file", "none"]

_KEY_ID = re.compile(r"[A-Za-z0-9_-]{1,32}")
_NONCE_BYTES = 12


class CatalogSecretError(Exception):
    """A stored catalog secret cannot be read: unknown key, altered, or moved to another row."""


class CatalogSecrets:
    """Seals catalog secrets with the first key; opens them with any key."""

    def __init__(self, keys: Mapping[str, bytes]) -> None:
        self._ciphers = {key_id: AESGCM(key) for key_id, key in keys.items()}
        self._sealing_id = next(iter(self._ciphers))

    def encrypt(self, user_id: str, catalog_id: UUID, secret: Mapping[str, object]) -> str:
        nonce = os.urandom(_NONCE_BYTES)
        plain = json.dumps(secret, separators=(",", ":")).encode("utf-8")
        sealed = self._ciphers[self._sealing_id].encrypt(
            nonce, plain, _binding(user_id, catalog_id)
        )
        return f"{self._sealing_id}:{base64.urlsafe_b64encode(nonce + sealed).decode('ascii')}"

    def sealed_with_current_key(self, ciphertext: str) -> bool:
        return ciphertext.partition(":")[0] == self._sealing_id

    def decrypt(self, user_id: str, catalog_id: UUID, ciphertext: str) -> dict:
        key_id, _, body = ciphertext.partition(":")
        cipher = self._ciphers.get(key_id)
        if cipher is None:
            raise CatalogSecretError("no configured catalog key has this id")
        try:
            raw = base64.urlsafe_b64decode(body.encode("ascii"))
            plain = cipher.decrypt(
                raw[:_NONCE_BYTES], raw[_NONCE_BYTES:], _binding(user_id, catalog_id)
            )
        except (ValueError, InvalidTag):
            # ValueError covers bad base64, non-ASCII text and a short nonce.
            raise CatalogSecretError("catalog secret does not decrypt") from None
        return json.loads(plain)


def _binding(user_id: str, catalog_id: UUID) -> bytes:
    return json.dumps(["quire-catalog", user_id, str(catalog_id)]).encode("utf-8")


def _parse_keys(raw: str, origin: str) -> CatalogSecrets:
    """Read a key map. Errors name ``origin`` and the key id, never a key."""
    try:
        data = json.loads(raw)
    except ValueError:
        data = None
    if not isinstance(data, dict) or not data:
        raise RuntimeError(
            f"{origin} must be a JSON object mapping a key id to a base64 32-byte key"
        )
    keys: dict[str, bytes] = {}
    for key_id, value in data.items():
        if not _KEY_ID.fullmatch(key_id):
            raise RuntimeError(f"{origin} has a key id that is not 1-32 letters, digits, - or _")
        try:
            key = base64.b64decode(value, validate=True) if isinstance(value, str) else b""
        except binascii.Error:
            key = b""
        if len(key) != 32:
            raise RuntimeError(f"{origin}[{key_id!r}] is not a base64-encoded 32-byte key")
        keys[key_id] = key
    return CatalogSecrets(keys)


def _key_file(settings: Settings) -> Path | None:
    return Path(settings.catalog_key_file) if settings.catalog_key_file else None


def load_catalog_secrets(settings: Settings) -> tuple[CatalogSecrets | None, KeySource]:
    """The keys already configured: the environment first, then the key file.

    Never creates a key. A key map that does not parse raises, so a typo
    stops the server at boot instead of turning the feature off.
    """
    if settings.catalog_keys is not None and settings.catalog_keys.get_secret_value().strip():
        raw = settings.catalog_keys.get_secret_value()
        return _parse_keys(raw, "QUIRE_SERVER_CATALOG_KEYS"), "environment"
    path = _key_file(settings)
    if path is not None:
        try:
            raw = path.read_text(encoding="utf-8")
        except FileNotFoundError:
            pass
        else:
            return _parse_keys(raw, f"The catalog key file {path}"), "file"
    return None, "none"


async def create_catalog_key(settings: Settings, session: AsyncSession) -> CatalogSecrets | None:
    """Create the key file on first start, when nothing else supplied a key.

    Call only after ``load_catalog_secrets`` found none. Returns ``None``,
    after logging why catalog sync stays off, when the file's folder is
    missing or not writable, or when catalog rows already exist: those were
    sealed with a key this server does not have (a restore without the key
    file, or another replica's file), and a new key would not read them.
    Any other failure (the database, the file system) also leaves the
    feature off rather than stopping the server.
    """
    try:
        return await _create_catalog_key(settings, session)
    except Exception as exc:
        # The class only: a message could quote a path or the key file's content.
        logger.error(
            "Catalog sync is off: creating the catalog key failed (%s)", type(exc).__name__
        )
        return None


async def _create_catalog_key(settings: Settings, session: AsyncSession) -> CatalogSecrets | None:
    path = _key_file(settings)
    if path is None or not os.access(path.parent, os.W_OK | os.X_OK):
        logger.warning(
            "Catalog sync is off: QUIRE_SERVER_CATALOG_KEYS is not set and the folder of "
            "QUIRE_SERVER_CATALOG_KEY_FILE (%s) is missing or not writable",
            path.parent if path is not None else "unset",
        )
        return None
    if await session.scalar(select(exists().select_from(AccountCatalog))):
        logger.warning(
            "Catalog sync is off: saved catalogs exist but no key was found in "
            "QUIRE_SERVER_CATALOG_KEYS or QUIRE_SERVER_CATALOG_KEY_FILE (%s). Restore the "
            "key file from the backup taken with the database, or supply the key",
            path,
        )
        return None

    key_id = secrets.token_hex(4)
    content = json.dumps({key_id: base64.b64encode(os.urandom(32)).decode("ascii")})
    # Write a private temporary file, then hard-link it into place: the link
    # fails if the key file exists, so of two servers starting together only
    # one key wins, and neither ever reads a half-written file.
    fd, temporary = tempfile.mkstemp(dir=path.parent, prefix=".catalog-key-")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            handle.write(content)
            handle.flush()
            os.fsync(handle.fileno())
        try:
            os.link(temporary, path)
        except FileExistsError:
            pass
        else:
            folder = os.open(path.parent, os.O_RDONLY)
            try:
                os.fsync(folder)
            finally:
                os.close(folder)
            logger.info(
                "Created the catalog key file %s; back it up together with the database", path
            )
    finally:
        os.unlink(temporary)
    return _parse_keys(path.read_text(encoding="utf-8"), f"The catalog key file {path}")
