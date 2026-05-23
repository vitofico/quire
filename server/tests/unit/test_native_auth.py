"""Unit tests for :mod:`quire_server.core.native_auth`.

Scope: side-effect-free primitives only. The NativeAuth backend itself
(login/logout DB interactions) is covered by the integration suite.
"""

from __future__ import annotations

import pytest

from quire_server.core.native_auth import (
    ARGON2_MEMORY_COST_KIB,
    ARGON2_PARALLELISM,
    ARGON2_TIME_COST,
    MAX_PASSWORD_BYTES,
    canonical_email,
    generate_session_token,
    get_dummy_hash,
    get_password_hasher,
    hash_password,
    hash_session_token,
    verify_password,
)

# ----------------------------------------------------------------------------
# Argon2 parameter pinning
# ----------------------------------------------------------------------------


def test_argon2_parameters_match_owasp_strong_profile():
    """The deployed argon2id parameters sit above OWASP's current minimum.

    Tests pin the exact values so a future drive-by tweak to the constants
    surfaces in code review rather than silently weakening every new hash.
    """
    assert ARGON2_TIME_COST == 3
    assert ARGON2_MEMORY_COST_KIB == 64 * 1024  # 64 MiB
    assert ARGON2_PARALLELISM == 4


def test_hasher_singleton_uses_configured_parameters():
    h = get_password_hasher()
    assert h.time_cost == ARGON2_TIME_COST
    assert h.memory_cost == ARGON2_MEMORY_COST_KIB
    assert h.parallelism == ARGON2_PARALLELISM


def test_dummy_hash_is_a_valid_phc_string():
    """The dummy hash must be a real argon2id PHC string so verify() against
    it follows the same code path as a verify against a real user row.
    """
    dummy = get_dummy_hash()
    assert dummy.startswith("$argon2id$"), dummy
    # Same hasher must recognise the dummy as well-formed (returns False on
    # mismatch, not an exception).
    h = get_password_hasher()
    from argon2.exceptions import VerifyMismatchError

    with pytest.raises(VerifyMismatchError):
        h.verify(dummy, "definitely not the dummy plaintext")


# ----------------------------------------------------------------------------
# hash_password / verify_password
# ----------------------------------------------------------------------------


async def test_hash_and_verify_round_trip():
    pw = "correct horse battery staple"
    h = await hash_password(pw)
    assert await verify_password(h, pw) is True


async def test_verify_wrong_password_is_false():
    h = await hash_password("hunter2")
    assert await verify_password(h, "hunter3") is False


async def test_verify_malformed_hash_is_false_not_exception():
    """A garbage stored hash must not crash login; it returns False."""
    assert await verify_password("not-a-real-argon2-string", "anything") is False


async def test_hash_password_rejects_oversize_input():
    pw = "a" * (MAX_PASSWORD_BYTES + 1)
    with pytest.raises(ValueError, match="too long"):
        await hash_password(pw)


async def test_verify_oversize_returns_false_silently():
    """A login attempt with an absurdly-long password must not raise; the
    response shape stays identical to a plain wrong-password attempt."""
    h = await hash_password("short")
    over = "a" * (MAX_PASSWORD_BYTES + 1)
    assert await verify_password(h, over) is False


# ----------------------------------------------------------------------------
# Email canonicalization
# ----------------------------------------------------------------------------


def test_canonical_email_strips_whitespace_and_casefolds():
    assert canonical_email("  Alice@Example.COM ") == "alice@example.com"


def test_canonical_email_nfkc_normalizes_compatibility_forms():
    # `ﬃ` is the U+FB03 ligature; NFKC decomposes it to "ffi".
    assert canonical_email("oﬃce@x.com") == "office@x.com"


def test_canonical_email_does_not_strip_gmail_plus_tag():
    """Intentional non-feature: gmail-style tag handling is out of scope."""
    assert canonical_email("Bob+spam@example.com") == "bob+spam@example.com"


def test_canonical_email_empty_returns_empty():
    assert canonical_email("   ") == ""


# ----------------------------------------------------------------------------
# Session tokens
# ----------------------------------------------------------------------------


def test_generate_session_token_returns_distinct_pair():
    t1, h1 = generate_session_token()
    t2, h2 = generate_session_token()
    assert t1 != t2
    assert h1 != h2
    # Token uses urlsafe alphabet only.
    assert all(c.isalnum() or c in "-_" for c in t1)


def test_hash_session_token_is_sha256_hex():
    token = "deadbeef"
    h = hash_session_token(token)
    # 64 hex chars = 256 bits of SHA-256.
    assert len(h) == 64
    assert all(c in "0123456789abcdef" for c in h)


def test_hash_session_token_matches_generator_hash():
    """The pair-generator and the standalone hasher must agree byte-for-byte
    so the DB lookup at validate-time finds the row written at login-time.
    """
    token, expected_hash = generate_session_token()
    assert hash_session_token(token) == expected_hash
