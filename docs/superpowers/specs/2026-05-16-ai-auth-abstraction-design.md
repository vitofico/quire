# Spec — AI auth abstraction (PR-B)

> Shipped in 3641c70 on 2026-05-16 as PR #14.

**Date:** 2026-05-16
**Branch:** `feat/ai-auth-abstraction`
**Roadmap reference:** `.claude/local/quire-ai/2026-05-16-next-deliverables.md` §PR-B
**Status:** Draft for architect review

## 1. Motivation

Today every `/ai/v1/*` route depends on `current_user_id`, which validates a
Basic-auth header by probing calibre-web. The string returned is then used both
as a logical "subject" (who triggered the call) and — implicitly — as the only
identity scope in existence. There is no separate notion of "tenant".

PR-C introduced a `tenant_id` kwarg on `InsightOrchestrator.{get,generate,regenerate}`
and the API layer currently passes the hardcoded literal `"local"` at three
call sites. That is the seam this PR formalizes.

To unlock the future hosted "Quire Cloud AI" mode (AI-only deploy, multi-tenant)
without forcing a rewrite of the request path, we need:

1. A typed `AiPrincipal` that bundles `(subject, tenant_id, scopes, auth_mode, request_id)` —
   the same shape under Basic-auth (single-tenant) and HMAC tokens (multi-tenant).
2. A pluggable `AiAuthenticator` protocol so the same routes work in both modes.
3. A FastAPI dependency `get_ai_principal` that picks the implementation off
   the `OPDS_SYNC_AI_AUTH_MODE` setting.
4. Two implementations: `BasicAuthAiAuthenticator` (wraps today's calibre-web
   verifier) and `TokenAiAuthenticator` (HMAC-SHA256 over canonical claims,
   stub — not wired by default).
5. `kid` rotation from day one (a JSON map `{kid: secret}` lets us mint under
   the newest secret while still validating tokens signed under any active
   secret).

This PR is a **seam-only** change. It does not introduce a token-issuance
endpoint. It does not change behavior for the default `basic` deploy. Sync
routes are untouched.

## 2. Non-goals

- A `POST /ai/v1/token` endpoint or any other token issuance flow.
- JWT support — HMAC-SHA256 is sufficient for the hosted single-issuer case.
  JWT can come later when third-party IdPs need to mint our tokens.
- Per-tenant rate limiting / quota enforcement (the existing per-user budget
  stays exactly as today).
- Changes to sync routes (`/sync/v1/*`).
- Multi-tenant data storage changes (PR-C's cache-integrity invariant remains
  the rule; this PR only changes the call-site that supplied the literal
  `"local"`).

## 3. Data shapes

### 3.1 `AiPrincipal`

```python
@dataclass(frozen=True, slots=True)
class AiPrincipal:
    subject: str          # user id (basic) or token `sub` claim
    tenant_id: str        # "local" under basic; token `tenant_id` claim otherwise
    scopes: tuple[str, ...]  # e.g. ("ai:read", "ai:write"); empty in basic mode for now
    auth_mode: Literal["basic", "token"]
    request_id: str | None   # read from request_id_var ContextVar at construction
```

Implementation detail: a frozen dataclass over a Pydantic model. The principal
is constructed once per request inside the auth dependency, then read-only.
Pydantic adds field-coercion overhead with no validation upside (the inputs
come from validated sources or are constants).

`scopes` is a tuple so the dataclass remains hashable. We keep it as an empty
tuple under `basic` mode; the token verifier can populate it from a `scope`
claim later. This PR does not consume `scopes` in any route — but it must
exist on the type so the future expansion is non-breaking.

### 3.2 `AiAuthenticator` protocol

```python
class AiAuthenticator(Protocol):
    async def authenticate(self, request: Request) -> AiPrincipal: ...
```

One method. Implementations are responsible for reading whatever headers they
care about, raising `HTTPException(401)` on failure, and returning a fully
populated `AiPrincipal` on success.

### 3.3 Token claims (HMAC-SHA256)

**Header** (JSON, URL-safe base64-encoded, no `=` padding):

| Field | Type   | Required | Notes |
|-------|--------|----------|-------|
| `alg` | string | yes      | Must equal `"HS256"` exactly. Any other value (including `"none"`) → 401. |
| `kid` | string | yes      | Key id. Resolved against `ai_token_secrets`. Header-only — must NOT appear in payload. |

**Payload claims** (JSON, URL-safe base64-encoded, no `=` padding):

| Claim       | Type   | Required | Notes                                                 |
|-------------|--------|----------|-------------------------------------------------------|
| `iss`       | string | yes      | Issuer; must equal `OPDS_SYNC_AI_TOKEN_ISSUER`.       |
| `aud`       | string | yes      | Audience; must equal `OPDS_SYNC_AI_TOKEN_AUDIENCE`.   |
| `exp`       | int    | yes      | Unix epoch seconds. Token rejected if `now >= exp`.   |
| `iat`       | int    | yes      | Unix epoch seconds. Rejected if more than 5 min in future. Also rejected if `exp <= iat`. Max lifetime `exp - iat <= 24h` (hard cap; prevents accidentally-eternal tokens since this PR has no replay protection). |
| `sub`       | string | yes      | Non-empty bounded string (max 128 chars), regex `[A-Za-z0-9._:@-]+`. Becomes `AiPrincipal.subject`. |
| `tenant_id` | string | yes      | Non-empty bounded string (max 128 chars), regex `[A-Za-z0-9._:-]+`. Becomes `AiPrincipal.tenant_id`. |
| `scope`     | string | no       | Space-separated tokens. Parsed into `scopes` tuple; empty/missing → empty tuple. Non-string `scope` → 401. |

**`sub` namespacing invariant**: The token `sub` claim MUST be globally
unique under the issuer (e.g. tenant-qualified at issuance: `acme:alice`).
The server stores `principal.subject` directly into `user_ai_preferences.user_id`,
`ai_usage_daily.user_id`, and `ai_generation_log.subject`. Two tenants with
overlapping `sub` values (e.g. both have `sub=alice`) would collide on
preferences and quotas. This is an issuer responsibility, not a server-side
one: the server does not concatenate `tenant_id` into the storage subject
because that would break per-user preference continuity if a user's tenant
ever changes. Documented in the env var help.

**Wire format** (compact, similar to JWT but HMAC-only):
`header.payload.signature`, each segment URL-safe base64 (`-`/`_` substitutes,
no `=` padding). Header is `{"alg":"HS256","kid":"<kid>"}`. Payload is the
JSON-encoded claim object. Signature is HMAC-SHA256 (32 raw bytes) over the
ASCII string `<header_b64>.<payload_b64>`, then URL-safe base64-encoded.

**Strict canonicalization**:
- All three segments URL-safe base64. No `=` padding character allowed in
  any segment (rejected even if the decode would otherwise succeed).
- No non-base64url characters (`+`, `/`, whitespace) allowed.
- All three segments must be non-empty.
- Exactly two `.` separators (three segments). Any other count → 401.
- Decoded signature must be exactly 32 bytes (the HMAC-SHA256 output size);
  shorter or longer → 401.

**Verification order (and corresponding failure reason for the structured log)**:
1. Parse `Authorization: Bearer <token>`. Missing scheme, wrong scheme,
   extra whitespace, or trailing tokens → 401 (`malformed_authorization`).
2. Split into three non-empty segments. Wrong count → 401 (`malformed_token`).
3. Strict-base64-decode header. Non-base64url, padded, or non-JSON → 401
   (`malformed_header`).
4. Header MUST have exactly `alg` and `kid` keys (extras tolerated but
   logged). `alg != "HS256"` → 401 (`bad_alg`).
5. Missing/empty/non-string `kid` → 401 (`missing_kid`).
6. Resolve `kid` against `ai_token_secrets`. Unknown → 401 (`unknown_kid`).
7. Strict-base64-decode signature. Wrong length (not 32 bytes) → 401
   (`bad_signature`).
8. Recompute HMAC over `<header_b64>.<payload_b64>` using the resolved
   secret. Compare via `hmac.compare_digest`. Mismatch → 401 (`bad_signature`).
9. Strict-base64-decode payload. Non-JSON or non-object → 401 (`malformed_payload`).
10. Reject if payload contains a `kid` claim (header-only). → 401 (`kid_in_payload`).
11. Validate types:
    - `iss`, `aud`: strings.
    - `exp`, `iat`: `int` (not `bool`; Python's `isinstance(True, int)` is
      true, so check `type(x) is int` or `isinstance(x, int) and not isinstance(x, bool)`).
    - `sub`, `tenant_id`: non-empty strings matching the regex.
    - `scope` (if present): string.
12. Check `iss == expected_issuer`. Mismatch → 401 (`bad_issuer`).
13. Check `aud == expected_audience`. Mismatch → 401 (`bad_audience`).
14. Check `exp > now`. Failure → 401 (`expired`).
15. Check `iat <= now + 300`. Failure → 401 (`iat_in_future`).
16. Check `exp > iat` and `exp - iat <= 86_400`. Failure → 401 (`bad_lifetime`).
17. Build principal. `scopes` = `tuple(s for s in scope.split(" ") if s)` if
    `scope` present else `()`.

All failures return identical `401 Unauthorized` to the client. The failure
reason appears only in the structured log line (key: `auth_failure_reason`).
Principle: never leak validation internals to an unauthenticated caller.

#### 3.3.1 `kid` rotation

`ai_token_secrets` is a JSON object env var like `{"k1":"<32+ bytes>","k2":"<32+ bytes>"}`.
Verification picks the secret whose `kid` matches the token's header. Both
keys are simultaneously valid for verification; rotation is "add the new key,
let old tokens expire, remove the old key". No issuance endpoint in this PR
means there's nothing to "switch to the new key for minting" — that's the
issuer's job whenever that PR happens.

Minimum secret length enforced at startup: 32 UTF-8 bytes (a 256-bit random
secret is the recommended floor; SHA-256's own block size is 64 bytes but
HMAC accepts arbitrary-length keys, so 32 bytes is a policy choice, not a
crypto requirement). Shorter secrets are a configuration error. Whitespace-
only secrets technically pass the length check; this is the operator's
problem, not the server's (32 random bytes is documented).

### 3.4 `get_ai_principal` dependency

```python
async def get_ai_principal(
    request: Request,
    authenticator: Annotated[AiAuthenticator, Depends(get_ai_authenticator)],
) -> AiPrincipal:
    return await authenticator.authenticate(request)
```

`get_ai_authenticator` is a transitive dependency that returns the singleton
authenticator instance stored on `app.state.ai_authenticator` (constructed
once at startup based on `ai_auth_mode`).

## 4. Settings

Two new fields on `Settings` in `config.py`:

```python
# AI authentication mode for /ai/v1/*. Sync routes are unaffected.
ai_auth_mode: Literal["basic", "token"] = "basic"

# JSON map of kid → secret (UTF-8 string). Required when ai_auth_mode == "token".
# Token issuance happens elsewhere (future PR); this server only verifies.
# Multiple kids enable rotation: tokens signed under any listed kid are
# accepted. Add the new kid, let old tokens expire, then remove the old kid.
ai_token_secrets: dict[str, str] | None = None

# Required when ai_auth_mode == "token". Validated against the token's iss
# claim.
ai_token_issuer: str | None = None

# Required when ai_auth_mode == "token". Validated against the token's aud
# claim.
ai_token_audience: str | None = None
```

`ai_token_secrets` is parsed from a JSON string env var via Pydantic's
built-in JSON-string handling (Pydantic v2 auto-parses JSON for dict fields
when the env value starts with `{`).

### 4.1 Startup validation

`main.py::create_app()` calls a `_validate_ai_auth_settings(settings)` helper
right after the AI router branch decides to mount. The helper:

- If `ai_auth_mode == "basic"`: no-op (today's behavior).
- If `ai_auth_mode == "token"`:
  - `ai_token_secrets` must be a non-empty dict. Empty / `None` → raise
    `RuntimeError` with a clear message ("token mode requires
    `OPDS_SYNC_AI_TOKEN_SECRETS` to be a non-empty JSON object").
  - Every secret value must be at least 32 bytes when encoded as UTF-8.
    Short secret → raise `RuntimeError`.
  - `ai_token_issuer` and `ai_token_audience` must both be set. Missing →
    raise `RuntimeError`.

This is the "fail startup loudly" requirement. The error happens during
`create_app`, which means k8s rolling deploys catch it via crash-loop —
no silently-accepting-everything failure mode is possible.

If `ai_enabled` is `false` the entire AI block is skipped, so auth mode
is also not validated. Sync-only deploys are unaffected.

## 5. Wiring

### 5.1 `opds_sync/api/ai_auth.py` (new)

Module structure:

```
ai_auth.py
├── AiPrincipal           (frozen dataclass)
├── AiAuthenticator       (Protocol)
├── BasicAuthAiAuthenticator
│       ├── __init__(validator: CalibreAuthValidator)
│       └── authenticate(request) -> AiPrincipal
├── TokenAiAuthenticator
│       ├── __init__(secrets, issuer, audience, clock=time.time)
│       └── authenticate(request) -> AiPrincipal
└── get_ai_principal      (FastAPI dependency)
    get_ai_authenticator  (depends on app.state.ai_authenticator)
```

`BasicAuthAiAuthenticator` does:
1. Read `Authorization` header. Missing → 401.
2. Delegate to the existing `CalibreAuthValidator.validate()` to verify and
   extract `user_id` (lowercased CWA username).
3. Construct `AiPrincipal(subject=user_id, tenant_id="local", scopes=(),
   auth_mode="basic", request_id=request_id_var.get() or None)`.

`request_id_var.get()` returns the empty string when unset (per PR-A's
default). We coerce to `None` for the principal so consumers don't have to
guard `if request_id and ...`.

`TokenAiAuthenticator` reads `Authorization: Bearer <token>`, runs the
verification algorithm from §3.3, and builds the principal from the verified
claims. The same `request_id_var` coercion applies.

### 5.2 `opds_sync/api/ai.py` (changes)

All endpoints currently depending on `current_user_id` switch to depending on
`AiPrincipal`. The three `tenant_id="local"` literals at the orchestrator call
sites become `principal.tenant_id`. The `user_id` kwarg to the orchestrator
stays — internally it's the `subject`, and we pass `principal.subject`.

Internal helpers like `_require_opt_in(session, user_id)` keep their
signatures: they consume `principal.subject` at call time.

### 5.3 `main.py::create_app()` (changes)

The authenticator is built **once per `create_app()`** at the top of the AI
branch — applies to both the fully-configured `if` branch AND the "AI
enabled but provider unconfigured" `elif` branch. There is no scenario where
the AI router is mounted without an authenticator, and there is no
scenario where `ai_auth_mode=token` silently downgrades to basic.

```python
from opds_sync.api.ai_auth import (
    BasicAuthAiAuthenticator,
    TokenAiAuthenticator,
)

if settings.ai_enabled:
    # Validates and raises on any token-mode misconfiguration BEFORE any
    # AI router could mount. Sync-only deploys (ai_enabled=false) skip
    # this entirely.
    _validate_ai_auth_settings(settings)

    if settings.ai_auth_mode == "basic":
        app.state.ai_authenticator = BasicAuthAiAuthenticator(
            validator=app.state.auth_validator
        )
    else:  # "token"
        app.state.ai_authenticator = TokenAiAuthenticator(
            secrets=settings.ai_token_secrets,
            issuer=settings.ai_token_issuer,
            audience=settings.ai_token_audience,
        )

    if settings.ai_base_url and settings.ai_model:
        # full AI wiring (orchestrator + router) — see existing code
        ...
    else:
        # AI enabled but unconfigured: still mount the router for /config.
        # Auth mode is whatever the operator set; token-mode /config calls
        # require valid Bearer tokens. This is intentional — half-configured
        # token deploys MUST require tokens, not fall back to Basic.
        from opds_sync.api.ai import router as ai_router
        app.include_router(ai_router, prefix="/ai/v1")
```

**Important behavior**: if `ai_auth_mode=token` and provider is unconfigured,
`/ai/v1/config` still requires a valid Bearer token. This is intentional —
the operator chose token mode, so `/config` must respect it. The previous
draft had this wrong (it forced basic in the elif branch); that would have
been a silent security regression.

### 5.4 Sync routes — unchanged

`/sync/v1/*` continues to depend on `current_user_id` (and `CalibreWebUser` /
`CalibreAuthValidator`). The `AiPrincipal` type lives in `opds_sync.api.ai_auth`
and is not imported anywhere outside the AI module surface.

## 6. Cache-hit attribution under token auth

PR-C writes `ai_generation_log` rows whose `tenant_id` comes from the
orchestrator's kwarg. With this PR:

- Default `basic` mode: `principal.tenant_id == "local"`, log rows carry
  `"local"` (identical to today).
- `token` mode: `principal.tenant_id` is whatever the token's `tenant_id`
  claim says. Log rows carry the real tenant.

The shared cache row (`book_insights`) is still tenant-blind. Multiple tenants
hitting the same identity still produce one `book_insights` row plus per-call
`ai_generation_log` rows tagged with each tenant's id. This is exactly what
PR-C designed for.

Tests assert this end-to-end: a token-auth call writes the `ai_generation_log`
row with the token's claimed `tenant_id`, not `"local"`.

## 7. Tests

### 7.1 Unit (`server/tests/unit/test_ai_auth.py`, new)

**`BasicAuthAiAuthenticator`**:
- Valid credential → `AiPrincipal(subject="alice", tenant_id="local", scopes=(), auth_mode="basic", request_id=None)`.
- Valid credential with request_id_var set → `request_id` matches.
- Missing `Authorization` header → `HTTPException(401)`.
- Invalid credential → `HTTPException(401)`.
- The principal's `request_id` is `None` (not `""`) when the contextvar is at default.

**`TokenAiAuthenticator`** (one test each unless noted):
- Valid token, single kid → correct principal.
- Valid token, two kids registered, signed under newer → accepted.
- Valid token, two kids registered, signed under older → accepted (rotation).
- `alg != "HS256"` (e.g. `"none"`, `"HS512"`) → 401.
- `alg` missing → 401.
- `kid` missing in header → 401.
- `kid` empty string in header → 401.
- `kid` present in payload (forbidden) → 401.
- Unknown `kid` → 401.
- Expired token (`exp < now`) → 401.
- `exp == now` → 401 (boundary).
- `iat` more than 5 min in future → 401.
- `iat == now + 300` → accepted (boundary).
- `exp <= iat` → 401.
- `exp - iat > 86400` → 401 (max lifetime cap).
- Wrong `iss` → 401.
- Wrong `aud` → 401.
- Tampered signature (flip last byte) → 401.
- Tampered payload (modify a claim, keep sig) → 401.
- Missing required claim: `sub`, `tenant_id`, `iss`, `aud`, `exp`, `iat` — one
  test per claim → 401.
- Wrong claim type: `exp` as string, `iat` as bool, `sub` as list, `tenant_id`
  as empty string, `tenant_id` over 128 chars, `tenant_id` with disallowed
  chars (`"a/b"`, `"a b"`) — one test per case → 401.
- `scope` as non-string → 401.
- `scope` empty / missing → principal `scopes == ()`.
- `scope = "ai:read ai:write"` → `scopes == ("ai:read", "ai:write")`.
- Bearer prefix missing → 401.
- Wrong scheme (`Basic …`) → 401.
- `Bearer` with extra trailing tokens (`Bearer xxx yyy`) → 401.
- Wrong segment count (2 dots not 3) → 401.
- Empty segment in token → 401.
- `=` padding present in any segment → 401.
- Non-base64url chars (`+`, `/`, whitespace) in a segment → 401.
- Signature segment decodes to ≠ 32 bytes → 401.
- Header is JSON array (not object) → 401.
- Payload is JSON array (not object) → 401.
- Payload non-JSON → 401.
- Unicode `tenant_id` containing control chars → 401 (regex blocks).

The HMAC signing helper for tests lives in the test module itself
(`_sign_token(claims, *, secret, kid, alg="HS256", payload_kid=None)` — the
`alg` and `payload_kid` kwargs let tests construct malformed tokens). We
deliberately do NOT ship a production minter — issuance is out of scope.

### 7.2 Unit (`server/tests/unit/test_settings.py`, additions)

- `test_ai_auth_mode_defaults_to_basic`.
- `test_ai_token_secrets_parses_json_object`.
- Token-mode startup validation (in a dedicated `test_main_ai_auth_validation.py`):
  - `ai_auth_mode=token`, secrets `None` → `RuntimeError` from `create_app()`.
  - `ai_auth_mode=token`, secrets `{}` (empty dict) → `RuntimeError`.
  - `ai_auth_mode=token`, malformed JSON in env var → Pydantic raises at
    `Settings()` construction (validated separately in test_settings).
  - `ai_auth_mode=token`, secrets JSON-decodes to a non-object (e.g. `[]`)
    → Pydantic raises (dict-typed field).
  - `ai_auth_mode=token`, secret value < 32 UTF-8 bytes → `RuntimeError`.
  - `ai_auth_mode=token`, secret value is non-string → `RuntimeError`.
  - `ai_auth_mode=token`, secret key (`kid`) is empty string → `RuntimeError`.
  - `ai_auth_mode=token`, issuer missing → `RuntimeError`.
  - `ai_auth_mode=token`, issuer is whitespace-only → `RuntimeError`.
  - `ai_auth_mode=token`, audience missing → `RuntimeError`.
  - `ai_auth_mode=token`, audience is whitespace-only → `RuntimeError`.
  - `ai_auth_mode=token`, all set + AI provider not configured → no exception
    (auth is built; router mounts for `/config` only; `/config` requires
    valid Bearer).
  - `ai_auth_mode=basic` → no exception regardless of token settings.
  - `ai_enabled=false`, `ai_auth_mode=token`, secrets missing → no exception
    (the AI block is skipped entirely).

### 7.3 Integration (`server/tests/integration/test_ai_auth_modes.py`, new)

Three scenarios, each spinning up a fresh app via `client_factory` with the
matching env vars:

- `basic` mode rejects token-style requests: `Authorization: Bearer <whatever>` → 401.
- `token` mode rejects basic-style requests: `Authorization: Basic <base64>` → 401.
- `token` mode with a freshly-signed token + correct claims → 200 on
  `/ai/v1/config`.
- `token` mode with two kids registered, request signed under the older kid
  → 200. Demonstrates rotation.

### 7.4 Integration (`server/tests/integration/test_ai_audit_log.py`, additions)

Augment the existing PR-C audit-log integration test:

- `test_token_auth_writes_tenant_id_to_log`: token-mode app + valid token
  with `tenant_id="acme"`, `sub="acme:alice"`. **Setup:** the token-auth flow
  must opt the subject in via `PUT /ai/v1/preferences` (or seed a
  `UserAIPreference` row directly) before `/insights/lookup`, otherwise the
  lookup short-circuits at 409 (`not_opted_in`) before any
  `ai_generation_log` row is written. The opt-in itself is a token-auth call
  too. Then perform `/ai/v1/insights/lookup`; assert the
  `ai_generation_log` row's `tenant_id == "acme"` and `subject == "acme:alice"`.
- `test_token_auth_propagates_request_id_to_log`: token-mode app + valid
  token + explicit `X-Request-ID: rid-test-1` on the request. Assert the
  `ai_generation_log` row's `request_id == "rid-test-1"`. This locks in
  that PR-C's ContextVar-at-log-write-time reading actually picks up the
  request id middleware set.

This is the load-bearing assertion that PR-C's plumbing actually receives
the PR-B principal's tenant.

### 7.5 Cache-key audit test

PR-C's `tests/integration/test_cache_key_audit.py` continues to pass
unchanged. This PR adds no shared-cache tables.

### 7.6 Mode-matrix coverage

All new tests use `pytest.mark.requires_ai`. They skip cleanly in sync-only
mode and run in full + ai-only modes. No `requires_progress` tests added.

## 8. Risks and mitigations

| Risk | Mitigation |
|------|-----------|
| Token verification leaks timing info that distinguishes "bad signature" from "unknown kid". | All 401 responses are identical; only logs differentiate. `hmac.compare_digest` on the signature compare. |
| Operator misconfigures token mode (forgets secret, short secret) and ships a permissive deployment. | Startup validation raises; pod crashloops; this is the loudest possible failure. |
| Existing integration tests rely on `app.dependency_overrides[current_user_id]`. | Tests that mock auth still need to override `current_user_id` for the basic path AND, separately, may need to override `get_ai_principal` once AI routes use it. We update the existing `client_factory` fixture to override BOTH dependencies so token-style tests can opt-in by skipping the override. |
| `request_id_var` contextvar default `""` becomes `None` in the principal. | Test it explicitly; downstream consumers (logs, audit) check for truthy. |
| Adding `Literal["basic","token"]` to `Settings` may not parse correctly under Pydantic v2. | Pydantic v2 handles literals natively; smoke-test in unit. |
| HMAC token replay (same valid token used twice). | Out of scope: HMAC has no nonce. Replay protection belongs to the issuer (short `exp`, optional `jti` + Redis denylist) and is a future PR. Document. |
| Empty / whitespace-only secrets pass the length check via UTF-8 multi-byte chars. | The check is on `len(secret.encode("utf-8")) >= 32`. A pathological secret with 32 whitespace bytes is still 32 bytes by the standard. We accept this — it's the operator's job to use a random secret, not the server's job to validate randomness. |
| `kid` collision between issuer and verifier when rotating. | Use distinct `kid` values per secret (UUID or year-numbered); document this in the env var help. |
| `sub` collision across tenants (e.g. tenant `acme` and tenant `beta` both have `sub=alice`). | Issuer responsibility: `sub` must be globally unique under the issuer (e.g. tenant-qualified at issuance, `acme:alice`). Documented in spec §3.3 and env var help; server does not enforce. |
| Background task reuses an `AiPrincipal` after the request context resets. | `principal.request_id` is captured at construction; PR-C reads `request_id_var` at log-write time, which would be empty in a background task. Background work must explicitly bind the ContextVar (`request_id_var.set(principal.request_id or "")`) or pass the id directly. Documented in code comment on `AiPrincipal`. PR-B does not currently spawn background tasks from the API layer, so this is forward-looking. |
| `alg=none` HMAC bypass / algorithm confusion. | Explicit `alg == "HS256"` check is the first thing after header decode (verification step 4). Tested. |

## 9. Acceptance checklist

- [ ] `opds_sync/api/ai_auth.py` exists with `AiPrincipal`, `AiAuthenticator`,
      `BasicAuthAiAuthenticator`, `TokenAiAuthenticator`, `get_ai_principal`.
- [ ] `opds_sync/config.py` gains `ai_auth_mode`, `ai_token_secrets`,
      `ai_token_issuer`, `ai_token_audience`.
- [ ] `opds_sync/main.py::create_app()` validates token-mode settings, builds
      the authenticator, and stores it on `app.state.ai_authenticator`.
- [ ] `opds_sync/api/ai.py` routes depend on `AiPrincipal` instead of
      `current_user_id`. The three `tenant_id="local"` literals become
      `principal.tenant_id`.
- [ ] Sync routes unchanged (grep `/sync/v1` for `AiPrincipal` → empty).
- [ ] Tests pass under all three mode-matrix combinations.
- [ ] Cache-key audit test still passes (no new shared-cache columns).
- [ ] `requires_ai` tests skip in sync-only mode.
- [ ] Spec + plan committed.
- [ ] PR body documents: default mode = `basic` = no behavior change; token
      mode is a stub with no issuance endpoint; `kid` rotation supported
      from day one.

## 10. Out of scope (explicit)

- Token issuance endpoint (`POST /ai/v1/token`) — future PR.
- JWT (RS256/EdDSA/etc.) — future PR if/when third-party IdPs need to mint.
- Replay protection / `jti` denylist — future PR.
- Per-tenant rate limiting / quota — future PR.
- Android changes — none. The hosted client will need a token; that's a
  Quire Cloud AI app concern, not the open-source app.
- Documentation refresh for `docs/sync-api.md` — handled by the batch's
  closing doc-review step.
