"""NativeAuth primitives: password hashing, email canonicalization, tokens.

Phase 0, task S-1 (per
``docs/superpowers/specs/2026-05-22-quire-monetization-design.md``,
"Architectural changes → Thin auth abstraction in ``quire_server``").

This module holds the side-effect-free primitives used by the NativeAuth
backend. The backend class itself lives in ``quire_server.core.auth_backend``.
Splitting the two keeps the cryptographic surface trivially auditable.

Scope discipline: this is the MINIMUM Cloud needs. No password reset, no
magic-link delivery, no refresh tokens, no lockout. Those are deferred per
spec.

Argon2 parameters (m=64 MiB, t=3, p=4) sit above OWASP's current Argon2id
minimum (m=19 MiB, t=2, p=1) and inside RFC 9106's recommended low-memory
profile. The cost is calibrated so a single ``verify`` takes well under a
second on the target Hetzner CX-class hardware; if capacity hurts later,
reduce ``parallelism`` before reducing memory.

Async story: argon2-cffi's ``hash`` / ``verify`` are synchronous and CPU-
bound for tens of milliseconds. Running them on the asyncio event loop
would stall every other request for the duration. All callers therefore
go through ``anyio.to_thread.run_sync``.

References:
* `OWASP Password Storage Cheat Sheet
  <https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html>`_
* `RFC 9106 <https://www.rfc-editor.org/rfc/rfc9106.html>`_
"""

from __future__ import annotations

import hashlib
import secrets
import unicodedata

import anyio
from argon2 import PasswordHasher
from argon2.exceptions import (
    InvalidHashError,
    VerificationError,
    VerifyMismatchError,
)

# ----------------------------------------------------------------------------
# Argon2 configuration
# ----------------------------------------------------------------------------
# These constants are the single source of truth; the PasswordHasher singleton
# is built from them. Tests import them directly to assert the deployed values.

ARGON2_TIME_COST = 3
ARGON2_MEMORY_COST_KIB = 64 * 1024  # 64 MiB
ARGON2_PARALLELISM = 4
ARGON2_HASH_LEN = 32
ARGON2_SALT_LEN = 16

# Hard cap on raw password byte length, applied BEFORE argon2 sees the input.
# argon2-cffi happily accepts arbitrarily large inputs but a 1 MiB password
# is purely a denial-of-service vector. The cap is generous (any human
# password fits) and intentionally NOT exposed as config.
MAX_PASSWORD_BYTES = 1024


def _build_hasher() -> PasswordHasher:
    return PasswordHasher(
        time_cost=ARGON2_TIME_COST,
        memory_cost=ARGON2_MEMORY_COST_KIB,
        parallelism=ARGON2_PARALLELISM,
        hash_len=ARGON2_HASH_LEN,
        salt_len=ARGON2_SALT_LEN,
    )


# Module-level singleton. Building a PasswordHasher is cheap, but constructing
# one per request would obscure the "all hashes use the same parameters"
# invariant that ``check_needs_rehash`` callers depend on. Tests that need to
# vary parameters build their own.
_HASHER: PasswordHasher = _build_hasher()


# Constant-time dummy hash: precomputed once at import time so the
# unknown-email login path can ``verify`` against a real-shaped argon2 PHC
# string. The plaintext is itself random so a clever attacker cannot guess
# it offline and time the comparison. The value of the dummy doesn't matter
# beyond "it's never going to match a real password".
_DUMMY_HASH: str = _HASHER.hash(secrets.token_urlsafe(32))


def get_password_hasher() -> PasswordHasher:
    """Return the module-level PasswordHasher singleton."""
    return _HASHER


def get_dummy_hash() -> str:
    """Return the precomputed dummy hash used for unknown-email login paths."""
    return _DUMMY_HASH


# ----------------------------------------------------------------------------
# Async wrappers
# ----------------------------------------------------------------------------


async def hash_password(plain: str) -> str:
    """Argon2id hash a password off the event loop.

    Raises ``ValueError`` if the password exceeds ``MAX_PASSWORD_BYTES`` after
    UTF-8 encoding. Empty passwords are accepted at this layer; the router
    is responsible for rejecting them via pydantic ``min_length=1``.
    """
    if len(plain.encode("utf-8")) > MAX_PASSWORD_BYTES:
        raise ValueError("password too long")
    return await anyio.to_thread.run_sync(_HASHER.hash, plain)


async def verify_password(stored_hash: str, plain: str) -> bool:
    """Argon2 verify off the event loop.

    Returns ``True`` on match, ``False`` on mismatch / malformed hash. Never
    raises. Callers MUST run this exactly once per login attempt regardless
    of whether the user exists — see the dummy-hash trick above.

    Oversize passwords return ``False`` rather than raising so the unknown-
    user code path (which receives the user-supplied plaintext blindly)
    cannot be probed via a different error shape.
    """
    if len(plain.encode("utf-8")) > MAX_PASSWORD_BYTES:
        return False
    try:
        return await anyio.to_thread.run_sync(_HASHER.verify, stored_hash, plain)
    except VerifyMismatchError:
        return False
    except (VerificationError, InvalidHashError):
        # A malformed stored hash should not crash login; treat as miss. The
        # operator's monitoring picks up such rows separately.
        return False


# ----------------------------------------------------------------------------
# Email canonicalization
# ----------------------------------------------------------------------------


def canonical_email(raw: str) -> str:
    """Return the canonical storage form of an email address.

    Rules (intentionally minimal):
      * strip leading/trailing whitespace
      * NFKC normalize the Unicode form
      * casefold (which subsumes ``lower()`` for ASCII)

    NOT IMPLEMENTED ON PURPOSE: gmail-style ``+tag`` stripping, domain
    aliasing (``googlemail.com`` ↔ ``gmail.com``), IDN punycoding. Those
    are policy decisions the OSS server should not make; Cloud's signup
    flow can normalize further before calling ``register``.
    """
    return unicodedata.normalize("NFKC", raw.strip()).casefold()


# ----------------------------------------------------------------------------
# Session tokens
# ----------------------------------------------------------------------------

# 32 bytes of CSPRNG entropy → 43-char urlsafe-base64 string. Plenty of
# entropy (256 bits) for an opaque bearer token. We keep ``token_urlsafe`` so
# the token survives unencoded transport through ``Authorization: Bearer``.
SESSION_TOKEN_ENTROPY_BYTES = 32


def generate_session_token() -> tuple[str, str]:
    """Mint a fresh opaque session token.

    Returns ``(token_plain, token_hash_hex)``. The plaintext is what the
    client sees once (in the login response) and sends back in subsequent
    ``Authorization: Bearer`` headers. The hash is what the DB stores.

    Hash function: SHA-256 hex digest. We are NOT using HMAC because there
    is no server-side secret here: the hash exists only to prevent leak-by-
    DB-read, not to authenticate the token. Token entropy alone (256 bits)
    is sufficient against guessing.
    """
    token_plain = secrets.token_urlsafe(SESSION_TOKEN_ENTROPY_BYTES)
    token_hash_hex = hashlib.sha256(token_plain.encode("ascii")).hexdigest()
    return token_plain, token_hash_hex


def hash_session_token(token_plain: str) -> str:
    """Return the storage form for an incoming bearer token."""
    return hashlib.sha256(token_plain.encode("ascii")).hexdigest()
