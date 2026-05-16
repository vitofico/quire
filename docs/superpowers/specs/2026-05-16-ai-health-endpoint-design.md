# PR5 — AI provider health endpoint

**Date:** 2026-05-16
**Branch:** code-only (no migration)
**Effort:** Short
**Deps:** PR-A (mode flags / branch split)
**Status:** Draft — ready for review

## Goal

Surface AI provider + retrieval-source reachability to operators and users
without log-scraping. A single `GET /ai/v1/health` returns an in-memory
snapshot of the last observed call outcomes for:

- The OpenAI-compatible AI provider (`AI_BASE_URL`).
- Each retrieval source the server is configured to hit (Wikipedia, OpenLibrary).

The endpoint is operational only: it mounts when AI mode is enabled, and is
distinct from the always-on `/health` and `/readyz` k8s probes (which never
touched the AI provider and never will — provider downtime is not a deploy
failure).

## Why now

`PR-A` introduced three deploy modes and an always-on `/health` for k8s. That
endpoint reports `{ready, modes}` only — it never calls the provider. Operators
debugging "is the model unreachable today?" today either:

1. Tail the server logs for `ai.generate.error`, OR
2. Wait for a user to complain that an insight fails.

Both are bad. A small in-memory state holder, updated as a side-effect of
every `chat_structured` and retrieval call, is enough to answer the question
cheaply.

This also unblocks a small UX item: the Android Settings AI section can show
"Provider: reachable" / "unreachable — Timeout 3m ago" without bespoke
plumbing.

## Non-goals

- **Active health checks.** The server does not ping the provider or Wikipedia
  proactively. State updates only on real user-driven calls. (Active probing
  doubles cost on a hosted setup and races against rate limits.)
- **Persistent state across restarts.** The holder is process-local. On
  restart everything resets to `None` ("not yet checked"). Multi-replica
  observability is out of scope per the project's "in-process lock stays"
  non-goal.
- **Latency histograms.** Already captured in `ai_generation_log.latency_ms`
  (PR-C). Health endpoint reports reachability only, not performance.
- **Per-user state.** Health is server-wide. No `user_id` involvement.
- **Authn/authz.** The endpoint is unauthenticated for operational visibility.
  It leaks the configured `model_id` (already exposed by `/ai/v1/config` to
  authed users) and the names of enabled retrieval sources (already exposed
  by `/ai/v1/config`). Adding auth would force ops dashboards through Basic
  auth for no security gain.

## Data model

`opds_sync/core/ai/health_state.py` — new module.

```python
@dataclass
class RetrievalSourceState:
    reachable: bool | None = None         # tri-state: None = never observed
    last_checked_at: datetime | None = None

@dataclass
class AiHealthSnapshot:
    provider_reachable: bool | None = None
    provider_last_checked_at: datetime | None = None
    model_id: str | None = None           # latest model we observed succeed
    last_failure_at: datetime | None = None
    last_failure_class: str | None = None # type(exc).__name__ from the orchestrator
    retrieval_sources: dict[str, RetrievalSourceState] = field(default_factory=dict)


class AiHealthState:
    """Process-local in-memory health state for the AI provider and retrieval sources.

    All fields tri-state: None means "never observed". False means "last call failed".
    True means "last call succeeded". Render False as failing in the UI — but only
    after a transition from None or True.
    """

    def __init__(self) -> None: ...
    async def record_provider_success(self, *, model_id: str) -> None: ...
    async def record_provider_failure(self, *, error_class: str) -> None: ...
    async def record_retrieval(self, *, name: str, success: bool) -> None: ...
    async def snapshot(self) -> AiHealthSnapshot: ...
```

### Tri-state invariants (post-architect-review)

- `provider_reachable=None` ⇔ `provider_last_checked_at=None` (never observed).
- `provider_reachable=True` requires `provider_last_checked_at` non-null. Also
  clears `last_failure_at` and `last_failure_class` to `None` — the contract
  is "this is the current state", not "this is the all-time history". The
  audit trail for failures lives in `ai_generation_log` (PR-C) and the
  structured warning logs, not in this in-memory holder.
- `provider_reachable=False` requires `provider_last_checked_at`,
  `last_failure_at`, and `last_failure_class` all non-null. `model_id` is
  preserved from the most recent success (gives the operator "last seen
  model" context even when the model is currently down).
- For each retrieval source: `reachable=None` ⇔ `last_checked_at=None`. A
  successful round-trip sets `reachable=True`; a network/HTTP failure sets
  `reachable=False`.

The `model_id` field reports the **most recently observed model** on a
successful chat completion. We do not echo the configured `AI_MODEL` — that
is already returned by `/ai/v1/config` and would create a confusing
"configured=llama3.1:8b but reachable=false on a different model_id" state
when the provider rejects the configured model. Reporting the observed model
is the right operational signal.

### Concurrency

FastAPI runs request handlers on the asyncio event loop in a single thread per
worker. `chat_structured` and retrieval calls happen inside async coroutines.
Mutations of `AiHealthState` are short critical sections (single attribute
writes), so they are technically safe under the GIL even without a lock.

We still add an `asyncio.Lock` for the writer side because:

- Future iterations may pack multiple writes into a single update (e.g.
  "record provider success AND clear last_failure"). A lock now means we
  don't have to retrofit when that happens.
- Snapshots compose 6+ attributes plus a dict; a lock guarantees the reader
  sees a coherent view (no torn read where `provider_reachable=False` but
  `last_failure_class=None`).

Reads use the same lock and produce a deep-enough copy (dict copy + dataclass
copy) that the caller can mutate freely without poisoning the holder.

### Process-restart behavior

On restart, all fields reset to `None`. This is intentional and acceptable
for the use case ("did the last user-driven call work?"). Documented in
the endpoint response: clients render `null` as "not yet checked", which is
the truthful state until the first real call lands.

## Hook points

### Provider

`opds_sync/core/ai/service.py::InsightOrchestrator._do_generate` already wraps
the `chat_structured` call in `try/except`. We extend that block:

```python
try:
    payload = await self.ai.chat_structured(...)
except Exception as e:
    ...
    await self._health.record_provider_failure(error_class=type(e).__name__)
    raise
await self._health.record_provider_success(model_id=self.model_id)
```

The orchestrator gets a new `health_state: AiHealthState` field, supplied via
the constructor. `main.py` creates one `AiHealthState` instance per process
and wires it into the orchestrator.

### Retrieval

`opds_sync/core/ai/retrieval.py::Retriever`:

- `_fetch_wikipedia`: on any HTTP response (including 404, which Wikipedia
  legitimately returns for unknown titles) → `record_retrieval("wikipedia", True)`.
  The reachability signal is "did the network call complete?" — a 404 means
  Wikipedia was reached and answered cleanly. Only `httpx.HTTPError` (timeout,
  connection refused, DNS, etc.) or other non-HTTP-response failures →
  `record_retrieval("wikipedia", False)`.
- `lookup_openlibrary`: on any HTTP response (including non-200) →
  `record_retrieval("openlibrary", True)`. OpenLibrary's REST surface treats
  non-200 as a regular response, not an outage. Only `httpx.HTTPError` and
  similar transport failures → `record_retrieval("openlibrary", False)`.

Retrieval source names are restricted to the canonical lowercase set
(`"wikipedia"`, `"openlibrary"`) that the orchestrator already uses for
`sources_enabled`. The retriever passes them as bare string literals — no
configuration-driven naming. This prevents an `ai_sources` env var typo
from leaking into the health response as a new source row.

**Cache hits do not update state.** If the retrieval cache hits, the network
was not touched and the state from the last real fetch should remain. This
matters: a cached 30-day-old Wikipedia entry should not refresh "reachable=true"
because nothing was actually reached.

The `Retriever` constructor takes an optional `health_state: AiHealthState | None`.
When `None` (e.g. unit tests that don't care about health), updates are no-ops.

## Endpoint

`opds_sync/api/ai.py`:

```python
@router.get("/health", response_model=AiHealthResponse)
async def get_ai_health(request: Request) -> AiHealthResponse:
    state = getattr(request.app.state, "ai_health", None)
    if state is None:
        # AI router mounted (ai_enabled=true) but health holder wasn't wired —
        # only happens in the "enabled but unconfigured" branch of main.py.
        # Return an empty snapshot so the endpoint still behaves uniformly.
        return AiHealthResponse(...)  # all-null
    snap = await state.snapshot()
    return AiHealthResponse.from_snapshot(snap)
```

No auth. The endpoint mounts only when `ai_enabled=true` (router gate). AI-
disabled deploys → 404 (router not mounted).

### Response schema (`AiHealthResponse` in `ai_schemas.py`)

```json
{
  "provider_reachable": true,
  "provider_last_checked_at": "2026-05-16T14:32:11Z",
  "model_id": "llama3.1:8b",
  "last_failure_at": null,
  "last_failure_class": null,
  "retrieval_sources": [
    {"name": "wikipedia", "reachable": true, "last_checked_at": "2026-05-16T14:32:09Z"},
    {"name": "openlibrary", "reachable": null, "last_checked_at": null}
  ]
}
```

Retrieval sources are emitted as a list (not a map) for stable ordering and to
match the Android serializer style used elsewhere. Sources are listed even if
never observed (`reachable=null`), seeded from `settings.ai_sources` at app
startup. This way the UI shows "OpenLibrary: not yet checked" instead of
silently omitting it.

## Android

`:data:ai` adds `AiHealthResponse` DTOs and `AiClient.getHealth()`.
`AiRepository.fetchHealth()` is a one-shot `suspend` (no caching at the
repository level — the Settings screen calls it on screen entry).

Settings screen: small status block in the AI section under the model line.
Renders three states per row:

- `null` → `"Provider: not yet checked"` (muted text).
- `true` → `"Provider: reachable (model: llama3.1:8b)"`.
- `false` → `"Provider: unreachable — ProviderTimeout, 4m ago"` (error color).

Same shape for each retrieval source.

## Tests

### Server unit tests (`test_ai_health_state.py`)

- Fresh state: all fields null.
- `record_provider_success` → `provider_reachable=True`, `model_id` set,
  `provider_last_checked_at` set.
- `record_provider_failure` after success → `provider_reachable=False`,
  `last_failure_*` set; `model_id` preserved from prior success.
- `record_retrieval(name, True/False)` → entry created or updated; never
  overwrites other source entries.
- `snapshot()` returns an independent copy: mutating the snapshot doesn't
  mutate the holder.
- Concurrent writes: 1000 record-success calls from `asyncio.gather` produce
  no torn reads.

### Server integration tests (`test_ai_health_endpoint.py`)

- AI-disabled mode: `GET /ai/v1/health` → 404.
- AI-enabled but unconfigured: `GET /ai/v1/health` → 200, snapshot all-null.
- AI-enabled + configured + zero calls: 200, snapshot all-null except
  retrieval sources list with `reachable=null` entries.
- Provider call sequence:
  1. Fake AI returns 200 → call lookup → health shows `provider_reachable=true`.
  2. Fake AI raises `ProviderTimeout` → call lookup (catches) → health shows
     `provider_reachable=false`, `last_failure_class="ProviderTimeout"`,
     `model_id` still set from step 1.
  3. Fake AI 502 → `provider_reachable=false`, `last_failure_class=
     "ProviderUnreachable"`.
- Cache-hit case does NOT touch health state (second identical lookup → no
  new `provider_last_checked_at`).
- Retrieval reachable/unreachable transitions for both wikipedia and
  openlibrary.

### Android unit tests (`AiRepositoryHealthTest`)

- `getHealth` deserializes a typical response.
- `getHealth` deserializes an all-null response.
- `getHealth` propagates 404 (AI disabled) as `AiHttpException`.

## Schema-version implications

None. This PR does not touch `book_insights`, prompts, or any cached field.
The cache-version checklist does not apply.

## Documentation deltas

- `docs/sync-api.md` AI section gets a new `GET /ai/v1/health` row in the
  endpoint table, plus a small subsection describing the tri-state semantics
  and the no-active-probing rule.
- `server/README.md` env vars unchanged (no new flags).
- `docs/architecture.md` already mentions an AI module; no change needed
  (the health endpoint is implementation detail, not architecture).

## Risks

- **Forgotten hook in a future `chat_structured` caller.** Today there is
  exactly one call site. If a second arises and forgets to update health
  state, the snapshot drifts. Mitigation: keep the calls inside
  `InsightOrchestrator`, not on `AIClient` (the client doesn't know about
  reachability state; the orchestrator owns the policy).
- **Retrieval source naming drift.** Names are bare strings (`"wikipedia"`,
  `"openlibrary"`). If a future PR renames an external source, the Settings
  screen will show two rows for a brief period until ops restarts the
  server. Acceptable.
- **Multi-replica blindness.** With 2+ replicas behind a load balancer, each
  replica reports its own state. A failing-only-on-replica-B condition won't
  show on replica-A's `/ai/v1/health`. Out of scope per the project non-goal
  ("Multi-replica deployments — in-process lock stays").
