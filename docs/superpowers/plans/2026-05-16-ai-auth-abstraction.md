# Plan — AI auth abstraction (PR-B)

> Shipped in 3641c70 on 2026-05-16 as PR #14.

**Spec:** `docs/superpowers/specs/2026-05-16-ai-auth-abstraction-design.md`
**Approach:** TDD throughout. The unit/integration seams are clean (auth
dependency, settings, principal dataclass), so failing tests come first;
implementation follows.

Each task is a checkbox. Verification commands inline. Run `cd server` once
per shell and use `uv run` for python invocations.

## Phase 0 — Baseline

- [ ] **0.1 Confirm green baseline.** `cd server && uv run pytest -v` passes
      on a fresh checkout of `feat/ai-auth-abstraction`. Capture test count for
      "no regression" claim.

## Phase 1 — Settings

- [ ] **1.1 RED: `tests/unit/test_settings.py::test_ai_auth_mode_defaults_to_basic`.**
      Assert `Settings().ai_auth_mode == "basic"` and `Settings().ai_token_secrets is None`.
- [ ] **1.2 RED: `tests/unit/test_settings.py::test_ai_token_secrets_parses_json`.**
      Set env `OPDS_SYNC_AI_TOKEN_SECRETS='{"k1":"a"}'`; assert
      `Settings().ai_token_secrets == {"k1": "a"}`.
- [ ] **1.3 GREEN: edit `opds_sync/config.py`.** Add `ai_auth_mode`,
      `ai_token_secrets`, `ai_token_issuer`, `ai_token_audience` as documented in
      spec §4. Ensure `Literal["basic","token"]` import.
- [ ] **1.4 Verify:** `uv run pytest tests/unit/test_settings.py -v`.

## Phase 2 — `AiPrincipal` + `BasicAuthAiAuthenticator`

- [ ] **2.1 RED: `tests/unit/test_ai_auth.py::test_principal_shape`.**
      Construct an `AiPrincipal` directly; assert fields, frozen, hashable
      (tuple scopes, not list).
- [ ] **2.2 RED: `test_basic_auth_authenticator_valid`.**
      Build a `BasicAuthAiAuthenticator` over a stub `CalibreAuthValidator`
      (in-process httpx MockTransport). Send a valid Basic header on a fake
      Starlette `Request`; assert returned principal `(subject="alice",
      tenant_id="local", scopes=(), auth_mode="basic", request_id=None)`.
- [ ] **2.3 RED: `test_basic_auth_authenticator_missing_header`** → 401.
- [ ] **2.4 RED: `test_basic_auth_authenticator_invalid_credentials`** → 401.
- [ ] **2.5 RED: `test_basic_auth_principal_carries_request_id`.**
      Inside the test: set `request_id_var` via `token = request_id_var.set("rid-123")`,
      call authenticator, assert `principal.request_id == "rid-123"`.
      `request_id_var.reset(token)` in teardown.
- [ ] **2.6 GREEN: create `opds_sync/api/ai_auth.py`.** Implement
      `AiPrincipal` (frozen dataclass), `AiAuthenticator` (Protocol),
      `BasicAuthAiAuthenticator`. No token impl yet. `get_ai_principal`
      dependency that pulls `app.state.ai_authenticator`.
- [ ] **2.7 Verify:** `uv run pytest tests/unit/test_ai_auth.py -v`.

## Phase 3 — `TokenAiAuthenticator`

- [ ] **3.1 Test helper.** Inside `tests/unit/test_ai_auth.py`, add
      `_sign_token(claims: dict, *, secret: str, kid: str) -> str` and
      `_decode_segment(b64: str)` helpers. Wire-format follows spec §3.3.
- [ ] **3.2 RED: `test_token_valid_claims_returns_principal`.**
      Build authenticator with `{"k1": "<32B+ secret>"}`, mint a token,
      assert principal `(subject="acme-user", tenant_id="acme", scopes=("ai:read","ai:write"), auth_mode="token", request_id=None)`.
- [ ] **3.3 RED: kid rotation** — `test_token_two_kids_old_kid_accepted`.
      Authenticator built with both `k1` and `k2`; token signed under `k1`;
      accepted.
- [ ] **3.4 RED: failure modes** (one test each unless noted). Algorithm /
      header:
      - `test_token_alg_none_rejected`.
      - `test_token_alg_wrong_rejected` (e.g. `HS512`).
      - `test_token_alg_missing_rejected`.
      - `test_token_kid_missing_in_header_rejected`.
      - `test_token_kid_empty_in_header_rejected`.
      - `test_token_kid_in_payload_rejected`.
      - `test_token_unknown_kid_rejected`.
      Time:
      - `test_token_expired` (`exp = now - 1`).
      - `test_token_exp_equals_now_rejected` (boundary).
      - `test_token_iat_too_far_in_future` (`iat = now + 3600`).
      - `test_token_iat_at_boundary_accepted` (`iat = now + 300`).
      - `test_token_exp_lte_iat_rejected`.
      - `test_token_lifetime_over_24h_rejected`.
      Identity:
      - `test_token_wrong_iss`.
      - `test_token_wrong_aud`.
      Signature integrity:
      - `test_token_tampered_signature` (flip last byte).
      - `test_token_tampered_payload` (modify a claim, leave sig).
      - `test_token_signature_wrong_length_rejected`.
      Claim validation:
      - `test_token_missing_sub`, `test_token_missing_tenant_id`,
        `test_token_missing_iss`, `test_token_missing_aud`,
        `test_token_missing_exp`, `test_token_missing_iat`.
      - `test_token_exp_as_string_rejected`.
      - `test_token_iat_as_bool_rejected`.
      - `test_token_sub_as_list_rejected`.
      - `test_token_tenant_id_empty_rejected`.
      - `test_token_tenant_id_over_128_chars_rejected`.
      - `test_token_tenant_id_disallowed_chars_rejected` (`"a/b"`, `"a b"`).
      - `test_token_scope_non_string_rejected`.
      - `test_token_scope_empty_yields_empty_tuple`.
      - `test_token_scope_parses_into_tuple`.
      Wire format:
      - `test_token_bearer_prefix_missing`.
      - `test_token_wrong_scheme` (`Authorization: Basic ...`).
      - `test_token_bearer_extra_tokens_rejected` (`Bearer xxx yyy`).
      - `test_token_segment_count_wrong_rejected` (2 dots not 3).
      - `test_token_empty_segment_rejected`.
      - `test_token_padding_present_rejected` (`=` in any segment).
      - `test_token_non_base64url_chars_rejected` (`+`, `/`, whitespace).
      - `test_token_header_array_rejected`.
      - `test_token_payload_array_rejected`.
      - `test_token_payload_non_json_rejected`.
- [ ] **3.5 GREEN: implement `TokenAiAuthenticator` in `ai_auth.py`.**
      Pure-python HMAC-SHA256 verification per spec §3.3. `hmac.compare_digest`
      for sig compare. Clock injection via `clock: Callable[[], float] = time.time`
      constructor kwarg for tests.
- [ ] **3.6 Verify:** `uv run pytest tests/unit/test_ai_auth.py -v`.

## Phase 4 — Startup validation

- [ ] **4.1 RED: `tests/unit/test_main_ai_auth_validation.py`.** Cases:
      - token mode + `secrets=None` → `RuntimeError`.
      - token mode + `secrets={}` → `RuntimeError`.
      - token mode + secret < 32 bytes → `RuntimeError`.
      - token mode + non-string secret value → `RuntimeError`.
      - token mode + empty kid key → `RuntimeError`.
      - token mode + missing issuer → `RuntimeError`.
      - token mode + whitespace issuer → `RuntimeError`.
      - token mode + missing audience → `RuntimeError`.
      - token mode + whitespace audience → `RuntimeError`.
      - token mode + all set + AI provider unconfigured → no exception, auth
        is built, `/ai/v1/config` requires Bearer.
      - basic mode + missing token settings → no exception.
      - `ai_enabled=false`, token mode misconfigured → no exception (AI block skipped).
- [ ] **4.2 GREEN: edit `opds_sync/main.py`.** Add
      `_validate_ai_auth_settings(settings)` helper. Construct authenticator
      at the TOP of `if settings.ai_enabled:` block (before either the
      fully-configured `if` or the unconfigured `elif`). Token mode is
      respected for `/ai/v1/config` even in the unconfigured branch — no
      silent downgrade to basic.
- [ ] **4.3 Verify:** `uv run pytest tests/unit/test_main_ai_auth_validation.py -v`.

## Phase 5 — Wire `ai.py` routes through `AiPrincipal`

- [ ] **5.1 RED: update `tests/integration/test_ai_endpoints.py` fixture.**
      Augment the integration `client_factory` to override BOTH
      `current_user_id` AND `get_ai_principal`. The principal override returns
      a basic-mode `AiPrincipal` built from the same decoded Basic header.
      Existing assertions should continue to pass once routes consume the
      principal.
- [ ] **5.2 GREEN: edit `opds_sync/api/ai.py`.** Replace
      `user_id: Annotated[str, Depends(current_user_id)]` with
      `principal: Annotated[AiPrincipal, Depends(get_ai_principal)]` on all
      routes. Internal helpers (`_require_opt_in`) read `principal.subject`.
      Three orchestrator call sites: `tenant_id=principal.tenant_id` instead
      of `tenant_id="local"`. Pass `user_id=principal.subject` so orchestrator
      semantics are unchanged.
- [ ] **5.3 Verify:** `uv run pytest tests/integration/test_ai_endpoints.py -v`.

## Phase 6 — Mode-switching integration tests

- [ ] **6.1 RED: `tests/integration/test_ai_auth_modes.py`.** Test scenarios:
      - `basic` mode rejects `Authorization: Bearer …` requests (401).
      - `token` mode (configured) rejects `Authorization: Basic …` requests (401).
      - `token` mode + valid token → 200 on `/ai/v1/config`.
      - `token` mode + rotation: two kids, token signed under older → 200.
- [ ] **6.2 GREEN: ensure `client_factory` supports skipping the
      `get_ai_principal` override when the test wants real token behavior.**
      Add a `disable_auth_overrides=True` kwarg or similar; the new tests use
      it. Default behavior (used by existing tests) stays.
- [ ] **6.3 Verify:** `uv run pytest tests/integration/test_ai_auth_modes.py -v`.

## Phase 7 — Audit-log integration (PR-C cross-check)

- [ ] **7.1 RED: extend `tests/integration/test_ai_audit_log.py` with
      `test_token_auth_writes_tenant_id_to_log`.** Token-mode app + valid token
      with `tenant_id="acme"`, `sub="acme:alice"`. Setup must include
      opting the subject in via `PUT /ai/v1/preferences` (token-auth) or
      seeding the row before lookup, else the request short-circuits at 409.
      Perform `/ai/v1/insights/lookup`; query `ai_generation_log`; assert
      `tenant_id == "acme"` and `subject == "acme:alice"`.
- [ ] **7.1b RED: `test_token_auth_propagates_request_id_to_log`.**
      Token-mode app + valid token + explicit `X-Request-ID` header on the
      lookup call; assert that request_id appears in the audit row.
- [ ] **7.2 Verify:** `uv run pytest tests/integration/test_ai_audit_log.py -v`.

## Phase 8 — Full suite + mode matrix

- [ ] **8.1 Full suite, default mode.** `cd server && uv run pytest -v`.
      Confirm no regression vs Phase 0 baseline.
- [ ] **8.2 Sync-only mode.**
      `OPDS_SYNC_AI_ENABLED=false uv run pytest -v` — `requires_ai` tests skip.
- [ ] **8.3 AI-only mode.**
      `OPDS_SYNC_PROGRESS_ENABLED=false uv run pytest -v` — `requires_progress`
      tests skip.
- [ ] **8.4 Cache-key audit test.**
      `uv run pytest tests/integration/test_cache_key_audit.py -v` — passes
      unchanged.

## Phase 9 — Commit, push, PR

- [ ] **9.1 Pre-commit hooks.** `cd server && uv run ruff format` then
      `uv run ruff check .` from the worktree root.
- [ ] **9.2 Commit.** Conventional commit with gitmoji:
      `:sparkles: feat(server): AI auth abstraction seam`. **No Claude
      attribution.**
- [ ] **9.3 Push.** `git push -u origin feat/ai-auth-abstraction`.
- [ ] **9.4 PR.** `gh pr create --base main --head feat/ai-auth-abstraction`.
      Title: `feat(server): AI auth abstraction (seam)`. Body covers summary,
      what changed, test plan, GPT review summary, "no behavior change in
      default mode" callout. **No Claude attribution.**
