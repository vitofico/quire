# Plan — PR5 AI provider health endpoint

> Shipped in ca23ded on 2026-05-16 as PR #15.

**Spec:** [`2026-05-16-ai-health-endpoint-design.md`](../specs/2026-05-16-ai-health-endpoint-design.md)
**Branch:** `feat/ai-health-endpoint`
**Author/Owner:** PR5 agent
**Status:** Ready

## Big picture

Add a process-local in-memory health holder, wire it into the AI orchestrator
and retriever, expose it as `GET /ai/v1/health`, and surface the snapshot in
the Android Settings AI section. Code-only PR: no migrations, no new env
vars, no new external destinations.

## Pre-flight

- Worktree: `.claude/worktrees/pr5-ai-health` on `feat/ai-health-endpoint`
  branched from `origin/main`. ✓ done.
- Test infra exists: `server/tests/{unit,integration}` with `client_factory`
  fixture in integration/conftest.py and a working fake-AI-via-httpx-transport
  pattern.

## Steps

### 1. Server: state holder (TDD)

Files: `server/opds_sync/core/ai/health_state.py`,
`server/tests/unit/test_ai_health_state.py`.

1. Write `test_ai_health_state.py` first. Cases:
   - `AiHealthState()` snapshot has all-null fields (`provider_reachable=None`
     AND `provider_last_checked_at=None`; never one without the other).
   - `record_provider_success(model_id="m")` sets reachable=True, model_id,
     `provider_last_checked_at` non-null, and clears `last_failure_at` /
     `last_failure_class` to None.
   - `record_provider_failure(error_class="ProviderTimeout")` on a fresh
     state: reachable=False, `provider_last_checked_at` non-null,
     `last_failure_at` non-null, `last_failure_class="ProviderTimeout"`,
     `model_id=None` (no prior success).
   - `record_provider_failure` after a prior success: model_id preserved
     from prior success; everything else as above.
   - Success-after-failure clears failure fields (recovery semantics).
   - `record_retrieval("wikipedia", True)` adds entry with `reachable=True`
     and non-null `last_checked_at`.
   - `record_retrieval("openlibrary", False)` adds independent entry; does
     not modify wikipedia entry.
   - Snapshot is an independent deep-ish copy (mutating snapshot.retrieval_sources
     doesn't mutate the holder).
   - 1000-call concurrent `asyncio.gather` writes: snapshot remains consistent
     (reachable matches model_id correctly; tri-state invariants hold).
2. Implement `health_state.py`:
   - `RetrievalSourceState` dataclass with tri-state `reachable: bool | None`.
   - `AiHealthSnapshot` dataclass.
   - `AiHealthState` class with `asyncio.Lock`-guarded mutations and
     `snapshot()` that returns an independent copy.
3. Run unit tests until green.

**Verification:** `uv run pytest server/tests/unit/test_ai_health_state.py -v`

### 2. Server: wire into orchestrator

Files: `server/opds_sync/core/ai/service.py`,
`server/tests/unit/test_ai_service.py` (regression).

1. Add `health_state: AiHealthState | None = None` constructor param to
   `InsightOrchestrator`. `None` = no-op updates (keeps existing unit tests
   that don't care about health from breaking).
2. In `_do_generate` `try/except` around `chat_structured`:
   - On success → `await self._health.record_provider_success(model_id=self.model_id)`.
   - On failure → `await self._health.record_provider_failure(error_class=type(e).__name__)`
     before re-raise.
3. Run existing service tests to confirm no regression.

**Verification:** `uv run pytest server/tests/unit/test_ai_service.py -v`

### 3. Server: wire into Retriever

Files: `server/opds_sync/core/ai/retrieval.py`,
`server/tests/unit/test_ai_retrieval.py` (regression).

1. Add `health_state: AiHealthState | None = None` to `Retriever.__init__`.
2. In `_fetch_wikipedia`:
   - Any HTTP response received (including 404, 5xx) →
     `record_retrieval("wikipedia", True)`. The network call completed, so
     reachability is True. We do not differentiate "Wikipedia returned a
     bad page" from "Wikipedia returned a good page" — both prove
     reachability.
   - `httpx.HTTPError` (timeout, connection refused, DNS) →
     `record_retrieval("wikipedia", False)`.
3. In `lookup_openlibrary`:
   - Any HTTP response received → `record_retrieval("openlibrary", True)`.
   - `httpx.HTTPError` → `record_retrieval("openlibrary", False)`.
4. Cache hits do NOT touch state (the early returns before HTTP call are
   already untouched by these edits).
5. Add tests in `test_ai_retrieval.py` verifying state transitions.

**Verification:** `uv run pytest server/tests/unit/test_ai_retrieval.py -v`

### 4. Server: schema + endpoint

Files: `server/opds_sync/api/ai_schemas.py`,
`server/opds_sync/api/ai.py`.

1. Add `RetrievalSourceHealth` and `AiHealthResponse` Pydantic models.
   `retrieval_sources` is a list, not a map, for ordering and Android-friendly
   serialization.
2. Add `from_snapshot(snap: AiHealthSnapshot, *, seed_sources: tuple[str, ...])`
   classmethod that emits one entry per configured source even if it has no
   state yet (so the UI never silently drops a source).
3. Add `GET /ai/v1/health` route. No `Depends(current_user_id)` — unauth'd.
4. Mount: in `main.py`, when AI is enabled + configured, create the health
   holder, attach to `app.state.ai_health`, and pass to the orchestrator + a
   retriever-factory closure so the retriever gets it too.
5. In the "enabled but unconfigured" branch, also create a holder and attach
   it so the endpoint still answers with all-null (instead of mismatch with
   `/ai/v1/config`).

**Verification:** `uv run pytest server/tests/integration/test_ai_endpoints.py -v`

### 5. Server: endpoint integration tests

File: `server/tests/integration/test_ai_health_endpoint.py` (new).

Cases (all using `client_factory` + `configure_ai` patterns):

1. `test_ai_health_404_when_disabled`: AI disabled → 404.
2. `test_ai_health_200_when_enabled_unconfigured`: AI enabled, no
   base_url/model → 200, all-null snapshot. retrieval_sources empty list
   (no configured sources, nothing to seed).
3. `test_ai_health_seeded_sources_before_any_call`: AI configured with
   `ai_sources=wikipedia,openlibrary` and no lookups yet → both sources
   appear with `reachable=null`.
4. `test_ai_health_provider_success`: fake AI returns 200 → lookup completes
   → health shows `provider_reachable=true`, `model_id`, `provider_last_checked_at`
   populated.
5. `test_ai_health_provider_timeout`: fake AI httpx mock raises
   `httpx.ReadTimeout` (mapped to `ProviderTimeout` in `AIClient`) → health
   shows `provider_reachable=false`, `last_failure_class="ProviderTimeout"`,
   `provider_last_checked_at` non-null.
6. `test_ai_health_provider_502`: fake AI returns 502 → health shows
   `provider_reachable=false`, `last_failure_class="ProviderUnreachable"`.
7. `test_ai_health_provider_400`: fake AI returns 4xx → health shows
   `provider_reachable=false`, `last_failure_class="ProviderRejected"`.
8. `test_ai_health_provider_parse_error`: fake AI returns malformed JSON →
   `last_failure_class="ProviderParseError"`.
9. `test_ai_health_recovery_clears_failure`: failure → success → snapshot's
   `last_failure_at` and `last_failure_class` are null again.
10. `test_ai_health_cache_hit_doesnt_touch_state`: success → record initial
    timestamp → cache-hit lookup → snapshot's `provider_last_checked_at`
    unchanged.
11. `test_ai_health_no_auth_required`: endpoint returns 200 with no
    Authorization header (operational visibility).
12. `test_retriever_cache_hit_doesnt_touch_state` (unit-level): direct
    Retriever test with a mocked `_read_cache` returning a hit — assert no
    `record_retrieval` calls happen.
13. `test_retriever_wikipedia_404_records_reachable_true`: synthetic 404 →
    health shows `wikipedia.reachable=true` (network worked).

**Verification:** `uv run pytest server/tests/integration/test_ai_health_endpoint.py -v`

### 6. Server: full test sweep

```sh
cd server
uv run ruff format .
uv run ruff check . --fix
uv run pytest tests/ -v
```

All three modes (full, sync-only, AI-only) must still pass.

### 7. Android: DTOs + client

Files: `data/ai/src/main/java/io/theficos/ereader/data/ai/AiDtos.kt`,
`data/ai/src/main/java/io/theficos/ereader/data/ai/AiClient.kt`,
`data/ai/src/test/java/io/theficos/ereader/data/ai/AiClientTest.kt` (extend if exists; new test file otherwise).

1. Add `RetrievalSourceHealth` and `AiHealthResponse` `@Serializable` classes
   mirroring the server schema.
2. Add `suspend fun getHealth(): AiHealthResponse` to `AiClient`.
3. Add a test verifying deserialization (typical + all-null shape).

### 8. Android: repository + ViewModel + UI

Files: `app/src/main/java/io/theficos/ereader/data/ai/AiRepository.kt`,
`app/src/test/java/io/theficos/ereader/data/ai/AiRepositoryHealthTest.kt` (new),
`app/src/main/java/io/theficos/ereader/ui/settings/SettingsViewModel.kt`,
`app/src/main/java/io/theficos/ereader/ui/settings/SettingsScreen.kt`.

1. `AiRepository.fetchHealth()`: thin suspend wrapper; no caching at the
   repo level. Catches `AiHttpException` returns `null` (AI disabled or any
   error -> UI just hides the row).
2. `AiRepositoryHealthTest`: MockWebServer-based test that calls
   `repo.fetchHealth()`, returns canned JSON, asserts the DTO values.
3. `SettingsViewModel`:
   - `AiState` gains `health: AiHealthResponse? = null`.
   - On init alongside `aiRepository.refresh()`, fire `fetchHealth()` and
     update the state.
   - Add `refreshAiHealth()` callable from the screen if needed.
4. `SettingsScreen`: under the existing AI block (when configured), render a
   small "Status" subsection:
   - "Provider: reachable (model: <id>)" / "Provider: unreachable —
     <error_class>, <relative_time>" / "Provider: not yet checked".
   - One line per retrieval source with the same tri-state rendering.
   - Reuse the existing `formatRelative` helper for timestamps.

### 9. Android: full sweep

```sh
./scripts/dgradle :data:ai:test :app:test
./scripts/dgradle :app:lintDebug
```

### 10. Docs

File: `docs/sync-api.md`. Add a row to the AI endpoint table and a short
subsection for `GET /ai/v1/health` describing tri-state semantics, the
no-active-probing rule, and the process-restart behavior.

### 11. GPT architect review

Per workflow: read
`/Users/vito/.claude/plugins/marketplaces/jarrodwatts-claude-delegator/prompts/architect.md`,
delegate via `mcp__codex__codex` with sandbox=read-only. Ask:

1. Tri-state correctness (`None` vs `False` rendering).
2. Async safety: simple `asyncio.Lock` adequate or do we need
   `contextvars` / `threading.Lock` for any reason?
3. Retrieval-source naming convention durability.
4. Process-restart behavior — confirm reset-to-null is acceptable.
5. Should retrieval-source state be seeded from `settings.ai_sources` at
   startup, or lazy on first call?

Max 3 rounds. Capture verdict in PR body.

### 12. Commit + push + PR

- Pre-commit hooks run on commit.
- Commit message: `:sparkles: feat: AI provider health endpoint`. **No Claude
  trailer.**
- Push: `git push -u origin feat/ai-health-endpoint`.
- PR: `gh pr create --base main --head feat/ai-health-endpoint`.

## Definition of done

- Spec + plan + sync-api.md update committed.
- Server unit + integration tests green in all three modes.
- Android tests green.
- GPT verdict captured in PR body.
- PR open against `main`. STOP.

## Risks / unknowns

- The orchestrator's `_do_generate` already has a structured `try/except`
  around `chat_structured`. Inserting the health-state hook there is the
  minimal change; verify no other code path produces a successful or failed
  generation that bypasses this method (regenerate goes through the same
  helper).
- The retriever's caching layer must not record health on cache hits.
  Verify the early `return` after `_read_cache` returns non-None bypasses
  the network code paths.

## Out of scope (documented for next batch)

- Active probing.
- Multi-replica observability (would require external store).
- Latency histograms in the response (use `ai_generation_log` joins from
  PR-C).
- Operator-only auth.
