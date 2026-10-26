"""Provider-neutral OIDC login; callers issue their own opaque application sessions.

Only discovery from the configured issuer supplies endpoints and keys. Provider tokens
are discarded after validating identity; email is profile data, never an account key.
"""

import asyncio
import base64
import hashlib
import hmac
import ipaddress
import json
import math
import secrets
import time
from collections.abc import Callable
from dataclasses import dataclass, field, replace
from typing import Any, Protocol
from urllib.parse import quote, urlencode, urlsplit

import httpx
import jwt
from cryptography.hazmat.primitives.asymmetric.rsa import RSAPublicKey


class OIDCError(Exception):
    """An authentication attempt failed; the message contains no provider secrets."""


@dataclass(frozen=True)
class OIDCSettings:
    issuer: str
    client_id: str
    redirect_uri: str
    client_secret: str | None = field(default=None, repr=False)
    scopes: tuple[str, ...] = ("openid", "profile", "email")
    algorithms: tuple[str, ...] = ("RS256",)
    transaction_ttl_seconds: int = 300
    allow_insecure_loopback: bool = False

    def __post_init__(self) -> None:
        _validate_url(self.issuer, self.allow_insecure_loopback)
        _validate_url(self.redirect_uri, self.allow_insecure_loopback)
        if not self.client_id or "openid" not in self.scopes:
            raise ValueError("OIDC requires a client ID and the openid scope.")
        if not self.algorithms or not set(self.algorithms) <= {"RS256", "ES256", "EdDSA"}:
            raise ValueError("OIDC signing algorithms must be pinned asymmetric algorithms.")
        if not 30 <= self.transaction_ttl_seconds <= 600:
            raise ValueError("OIDC transaction lifetime must be between 30 and 600 seconds.")


@dataclass(frozen=True)
class OIDCTransaction:
    verifier: str = field(repr=False)
    nonce: str = field(repr=False)
    browser_hash: str = field(repr=False)
    expires_at: float
    purpose: str | None = None
    user_id: str | None = None
    session_hash: str | None = field(default=None, repr=False)
    requested_at: float | None = None


class TransactionStore(Protocol):
    """Shared deployments implement atomic take in their shared database."""

    async def put(self, state_hash: str, transaction: OIDCTransaction) -> None: ...

    async def take(self, state_hash: str) -> OIDCTransaction | None: ...


class MemoryTransactionStore:
    """Bounded, single-process store. A restart cancels outstanding login attempts."""

    def __init__(self, clock: Callable[[], float] = time.time, capacity: int = 10_000) -> None:
        self._clock = clock
        self._capacity = capacity
        self._transactions: dict[str, OIDCTransaction] = {}
        self._lock = asyncio.Lock()

    async def put(self, state_hash: str, transaction: OIDCTransaction) -> None:
        async with self._lock:
            now = self._clock()
            self._transactions = {
                key: value for key, value in self._transactions.items() if value.expires_at > now
            }
            if len(self._transactions) >= self._capacity:
                raise OIDCError("Too many pending sign-in attempts.")
            self._transactions[state_hash] = transaction

    async def take(self, state_hash: str) -> OIDCTransaction | None:
        async with self._lock:
            return self._transactions.pop(state_hash, None)


@dataclass(frozen=True)
class AuthorizationRequest:
    url: str = field(repr=False)
    state: str = field(repr=False)


@dataclass(frozen=True)
class OIDCIdentity:
    issuer: str
    subject: str
    email: str | None = None
    email_verified: bool = False
    display_name: str | None = None
    auth_time: float | None = None
    purpose: str | None = None
    user_id: str | None = None
    session_hash: str | None = field(default=None, repr=False)


class OIDCClient:
    def __init__(
        self,
        settings: OIDCSettings,
        *,
        transaction_store: TransactionStore | None = None,
        http_client: httpx.AsyncClient | None = None,
        clock: Callable[[], float] = time.time,
    ) -> None:
        self.settings = settings
        self._clock = clock
        self._transactions = transaction_store or MemoryTransactionStore(clock)
        self._http = http_client

    async def begin(
        self, browser_binding: str, *, purpose=None, user_id=None, session_hash=None
    ) -> AuthorizationRequest:
        """Bind login to a random HttpOnly, SameSite=Lax browser cookie owned by the caller."""
        if len(browser_binding) < 32:
            raise OIDCError("A browser binding is required for sign-in.")
        if purpose is not None and (
            purpose not in {"local-password", "oidc-link", "account-delete"}
            or not user_id
            or not session_hash
        ):
            raise OIDCError("Reauthentication requires a bound account session.")
        metadata = await self._discovery()
        state = secrets.token_urlsafe(32)
        nonce = secrets.token_urlsafe(32)
        verifier = secrets.token_urlsafe(64)
        await self._transactions.put(
            _digest(state),
            OIDCTransaction(
                verifier,
                nonce,
                _digest(browser_binding),
                self._clock() + self.settings.transaction_ttl_seconds,
                purpose,
                user_id,
                session_hash,
                self._clock() if purpose else None,
            ),
        )
        params = {
            "client_id": self.settings.client_id,
            "redirect_uri": self.settings.redirect_uri,
            "response_type": "code",
            "scope": " ".join(self.settings.scopes),
            "state": state,
            "nonce": nonce,
            "code_challenge": _base64(hashlib.sha256(verifier.encode()).digest()),
            "code_challenge_method": "S256",
        }
        if purpose:
            params.update(prompt="login", max_age="0")
        return AuthorizationRequest(
            metadata["authorization_endpoint"] + "?" + urlencode(params), state
        )

    async def complete(self, code: str, state: str, browser_binding: str) -> OIDCIdentity:
        """Consume the transaction before any network request, including failed attempts."""
        if not state or len(state) > 512:
            raise OIDCError("The sign-in attempt is invalid or expired.")
        transaction = await self._transactions.take(_digest(state))
        if (
            transaction is None
            or transaction.expires_at <= self._clock()
            or not hmac.compare_digest(transaction.browser_hash, _digest(browser_binding))
        ):
            raise OIDCError("The sign-in attempt is invalid or expired.")
        if not code or len(code) > 8192:
            raise OIDCError("The authorization code is invalid.")
        metadata = await self._discovery()
        form = {
            "grant_type": "authorization_code",
            "code": code,
            "redirect_uri": self.settings.redirect_uri,
            "client_id": self.settings.client_id,
            "code_verifier": transaction.verifier,
        }
        # Authentik and standard confidential providers support client_secret_basic.
        auth = None
        if self.settings.client_secret is not None:
            auth = httpx.BasicAuth(
                quote(self.settings.client_id, safe=""),
                quote(self.settings.client_secret, safe=""),
            )
        token = await self._json("POST", metadata["token_endpoint"], data=form, auth=auth)
        id_token = token.get("id_token")
        if not isinstance(id_token, str) or len(id_token) > 32_768:
            raise OIDCError("The identity provider did not return a valid ID token.")
        keys = await self._json("GET", metadata["jwks_uri"])
        identity = self._identity(id_token, keys, transaction.nonce)
        if transaction.requested_at is not None:
            if (
                identity.auth_time is None
                or not transaction.requested_at - 5 <= identity.auth_time <= self._clock() + 5
            ):
                raise OIDCError("The identity provider did not verify a fresh sign-in.")
        return replace(
            identity,
            purpose=transaction.purpose,
            user_id=transaction.user_id,
            session_hash=transaction.session_hash,
        )

    async def _discovery(self) -> dict[str, Any]:
        metadata = await self._json(
            "GET", self.settings.issuer.rstrip("/") + "/.well-known/openid-configuration"
        )
        if metadata.get("issuer") != self.settings.issuer:
            raise OIDCError("The identity provider issuer does not match configuration.")
        for key in ("authorization_endpoint", "token_endpoint", "jwks_uri"):
            try:
                _validate_url(metadata[key], self.settings.allow_insecure_loopback)
            except (KeyError, ValueError, TypeError) as exc:
                raise OIDCError("The identity provider advertises an invalid endpoint.") from exc
        for key, required in (
            ("response_types_supported", "code"),
            ("code_challenge_methods_supported", "S256"),
        ):
            if key in metadata and (
                not isinstance(metadata[key], list) or required not in metadata[key]
            ):
                raise OIDCError("The identity provider does not support secure code sign-in.")
        advertised = metadata.get("id_token_signing_alg_values_supported")
        if advertised is not None and (
            not isinstance(advertised, list)
            or not set(self.settings.algorithms).intersection(
                value for value in advertised if isinstance(value, str)
            )
        ):
            raise OIDCError("The identity provider does not support the pinned signing algorithm.")
        return metadata

    async def _json(self, method: str, url: str, **kwargs: Any) -> dict[str, Any]:
        try:
            if self._http is None:
                async with httpx.AsyncClient(timeout=10.0, follow_redirects=False) as client:
                    return await self._read_json(client, method, url, **kwargs)
            return await self._read_json(self._http, method, url, **kwargs)
        except (httpx.HTTPError, ValueError, TimeoutError) as exc:
            raise OIDCError("The identity provider request failed.") from exc

    async def _read_json(
        self, client: httpx.AsyncClient, method: str, url: str, **kwargs: Any
    ) -> dict[str, Any]:
        async with (
            asyncio.timeout(12),
            client.stream(method, url, timeout=10.0, follow_redirects=False, **kwargs) as response,
        ):
            response.raise_for_status()
            content = bytearray()
            async for chunk in response.aiter_bytes():
                if len(content) + len(chunk) > 1_048_576:
                    raise ValueError("Provider response too large")
                content.extend(chunk)
            result = json.loads(content)
            if not isinstance(result, dict):
                raise ValueError("Provider response is not an object")
            return result

    def _identity(self, token: str, jwks: dict[str, Any], nonce: str) -> OIDCIdentity:
        try:
            header = jwt.get_unverified_header(token)
            algorithm, kid = header.get("alg"), header.get("kid")
            if algorithm not in self.settings.algorithms or not isinstance(kid, str) or not kid:
                raise ValueError("Invalid signing header")
            if header.get("crit"):
                raise ValueError("Unsupported critical token header")
            keys = jwks.get("keys")
            if not isinstance(keys, list) or len(keys) > 100:
                raise ValueError("Invalid key set")
            candidates = [key for key in keys if isinstance(key, dict) and key.get("kid") == kid]
            if len(candidates) != 1:
                raise ValueError("Signing key is absent or ambiguous")
            key_data = candidates[0]
            if (
                key_data.get("use", "sig") != "sig"
                or "verify" not in key_data.get("key_ops", ["verify"])
                or key_data.get("alg", algorithm) != algorithm
                or "d" in key_data
            ):
                raise ValueError("Invalid signing key")
            key = jwt.PyJWK.from_dict(key_data, algorithm=algorithm)
            if isinstance(key.key, RSAPublicKey) and key.key.key_size < 2048:
                raise ValueError("RSA signing key is too small")
            claims = jwt.decode(
                token,
                key.key,
                algorithms=list(self.settings.algorithms),
                audience=self.settings.client_id,
                issuer=self.settings.issuer,
                options={"require": ["iss", "sub", "aud", "exp", "iat", "nonce"]},
                leeway=0,
            )
            subject = claims["sub"]
            if not isinstance(subject, str) or not subject or len(subject) > 255:
                raise ValueError("Invalid subject")
            if not isinstance(claims["nonce"], str) or not hmac.compare_digest(
                claims["nonce"], nonce
            ):
                raise ValueError("Invalid nonce")
            audience = claims["aud"]
            if (isinstance(audience, list) and len(audience) > 1) or "azp" in claims:
                if claims.get("azp") != self.settings.client_id:
                    raise ValueError("Invalid authorized party")
            for claim in ("exp", "iat", "nbf", "auth_time"):
                if claim in claims and (
                    isinstance(claims[claim], bool)
                    or not isinstance(claims[claim], (int, float))
                    or not math.isfinite(claims[claim])
                ):
                    raise ValueError("Invalid token time")
        except (jwt.PyJWTError, ValueError, TypeError, KeyError, OverflowError) as exc:
            raise OIDCError("The identity provider ID token is invalid.") from exc
        email = claims.get("email")
        name = claims.get("name")
        return OIDCIdentity(
            self.settings.issuer,
            subject,
            email if isinstance(email, str) else None,
            claims.get("email_verified") is True,
            name if isinstance(name, str) else None,
            claims.get("auth_time"),
        )


def _validate_url(value: str, allow_loopback: bool) -> None:
    if not isinstance(value, str) or any(char.isspace() for char in value):
        raise ValueError("OIDC URLs must be strings without whitespace.")
    parts = urlsplit(value)
    # Accessing port validates syntax and range; urlsplit alone does not.
    _ = parts.port
    if not parts.hostname or parts.username or parts.password or parts.query or parts.fragment:
        raise ValueError(
            "OIDC URLs need a host and cannot contain credentials, queries or fragments."
        )
    if parts.scheme == "https":
        return
    loopback = parts.hostname == "localhost"
    try:
        loopback = loopback or ipaddress.ip_address(parts.hostname).is_loopback
    except ValueError:
        pass
    if not (parts.scheme == "http" and allow_loopback and loopback):
        raise ValueError("OIDC URLs require HTTPS; explicit loopback fixtures may use HTTP.")


def _digest(value: str) -> str:
    return hashlib.sha256(value.encode()).hexdigest()


def _base64(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode()
