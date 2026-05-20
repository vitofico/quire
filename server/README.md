# opds-sync

Quire sync server. FastAPI + Postgres. Source of truth for reading state.

See [`../docs/architecture.md`](../docs/architecture.md) for the system
design and [`../docs/sync-api.md`](../docs/sync-api.md) for the REST surface.

## Local dev

```sh
cd server
uv venv && source .venv/bin/activate
uv pip install -e ".[dev]"
uv run pytest
uv run uvicorn quire_server.main:app --reload
```

Tests require Docker (testcontainers spins up Postgres).

## Self-hosting via docker-compose

Two reference composes ship in this directory. Pick one:

| File                       | Brings up                                             | Use when                                                                                  |
| -------------------------- | ----------------------------------------------------- | ----------------------------------------------------------------------------------------- |
| `docker-compose.yml`       | postgres + opds-sync                                  | You already run calibre-web (and maybe TLS) elsewhere and only want the sync/AI server.   |
| `docker-compose.full.yml`  | postgres + calibre-web + opds-sync + Caddy (TLS)      | You want the whole stack behind a single base URL with TLS, matching the production k8s ingress. |

### Minimal: bring your own proxy

```sh
cd server
cp .env.example .env
# Edit .env: at minimum set OPDS_SYNC_CWA_BASE_URL and POSTGRES_PASSWORD.
docker compose up -d
curl http://localhost:8000/health
```

opds-sync listens on `${OPDS_SYNC_PORT:-8000}`. Point Quire's "sync
URL" at it and Quire's "OPDS URL" at your existing calibre-web. Two
URLs to configure in the app; you handle TLS yourself if exposing to
the internet.

### Full-stack reference compose

A Caddy front-end with path-based routing that mirrors the production
Kubernetes ingress, so the Android app only needs to know one base URL
(the Caddy hostname) — calibre-web's OPDS catalog AND opds-sync's
`/sync/*`, `/library/*`, `/ai/*` endpoints all live under the same
origin.

```sh
cd server
cp .env.example .env
# Edit .env. At minimum:
#   - POSTGRES_PASSWORD
#   - PUID/PGID (your host user)
#   - QUIRE_SITE_ADDRESS (or leave `localhost` for self-signed reference setup)
#   - mount your calibre library: uncomment the library volume in
#     docker-compose.full.yml and edit the host path
#   - if AI is enabled (default), uncomment + fill the OPDS_SYNC_AI_* vars
docker compose -f docker-compose.full.yml up -d
curl -fsSk https://localhost/health
```

Routing inside the Caddy front-end (`caddy/Caddyfile`):

```caddyfile
{$QUIRE_SITE_ADDRESS:localhost} {
    tls internal

    @opds path /sync/* /ai/* /library/* /health /readyz
    handle @opds {
        reverse_proxy opds-sync:8000
    }

    handle {
        reverse_proxy calibre-web:8083
    }
}
```

Smoke commands (replace `https://localhost` with `https://<your-host>`
for non-default `QUIRE_SITE_ADDRESS`; `-k` skips cert verification
against `tls internal`):

```sh
# Unauth health probes (opds-sync mounts these at the root)
curl -fsSk https://localhost/health
curl -fsSk https://localhost/readyz

# AI provider health (unauth — see PR5)
curl -fsSk https://localhost/ai/v1/health | jq

# Authenticated sync surfaces (Basic auth proxied to calibre-web)
USER=admin
read -rs PASS && echo
AUTH=$(printf '%s' "$USER:$PASS" | base64)
curl -fsSk -H "Authorization: Basic $AUTH" "https://localhost/library/v1/items"
curl -fsSk -H "Authorization: Basic $AUTH" "https://localhost/library/v1/stats" | jq
curl -fsSk -H "Authorization: Basic $AUTH" "https://localhost/sync/v1/progress?since=0"

# Calibre-web root (fall-through)
curl -fsSkI https://localhost/
```

#### `tls internal` caveat

The reference Caddyfile uses `tls internal`, which issues a
self-signed certificate from Caddy's built-in CA. This is fine for
localhost and lab setups (Caddy persists the CA in the `caddy_data`
volume), but browsers and Android will reject the certificate until
either:

- You install Caddy's root CA on each client. With the Caddy
  container running, `docker compose -f docker-compose.full.yml exec
  caddy cat /data/caddy/pki/authorities/local/root.crt` prints it.
- You replace `tls internal` with a real-cert directive. The simplest
  swap is to set `QUIRE_SITE_ADDRESS` to a public hostname (e.g.
  `ebooks.example.com`) AND drop the `tls internal` line from
  `caddy/Caddyfile` — Caddy then provisions a Let's Encrypt cert
  automatically via ACME (port 80 must be reachable from the
  internet for HTTP-01). For DNS-01, BYO certs, or other strategies
  see the Caddy docs.

#### Deploy modes in the full-stack compose

The same image supports three modes via env flags. The defaults below
match the table in [Deploy modes](#deploy-modes).

| Mode       | `OPDS_SYNC_PROGRESS_ENABLED` | `OPDS_SYNC_AI_ENABLED` | What the Caddy front-end serves                                  |
| ---------- | ---------------------------- | ---------------------- | ---------------------------------------------------------------- |
| Full stack | `true` (default)             | `true` (default)       | `/sync/v1/*` + `/library/v1/*` (items + stats) + `/ai/v1/*` + calibre-web at `/` |
| Sync only  | `true`                       | `false`                | `/sync/v1/*` + `/library/v1/*` (items + stats) + calibre-web at `/` |
| AI only    | `false`                      | `true`                 | `/ai/v1/*` only — drop the `calibre-web` service from the compose for a leaner stack |

Set both flags in `.env`. Sync-only deploys don't need
`OPDS_SYNC_AI_*`; AI-only deploys don't need calibre-web auth once
PR-B's token mode is selected (`OPDS_SYNC_AI_AUTH_MODE=token`).

### Migrations

Migrations run automatically on container start via `scripts/migrate.py`,
which respects the deploy-mode flags `OPDS_SYNC_PROGRESS_ENABLED` and
`OPDS_SYNC_AI_ENABLED` (both default `true`). See `migrations/README.md`
for the branch-label convention. The image is published to
`ghcr.io/vitofico/opds-sync:latest` by `server-ci.yaml`.

### Deploy modes

| Mode             | `OPDS_SYNC_PROGRESS_ENABLED` | `OPDS_SYNC_AI_ENABLED` | Mounts                                                  |
| ---------------- | ---------------------------- | ---------------------- | ------------------------------------------------------- |
| Full stack       | `true` (default)             | `true` (default)       | `/sync/v1/*`, `/library/v1/*` (items + stats), `/ai/v1/*` |
| Sync only        | `true`                       | `false`                | `/sync/v1/*`, `/library/v1/*` (items + stats)           |
| AI only          | `false`                      | `true`                 | `/ai/v1/*`                                              |

`/health` and `/readyz` are mounted on the root in every mode.

Update the health-probe path: it moved from `/sync/v1/healthz` (pre-PR-A) to
`/health` in PR-A. The k8s manifests in `theficos-cluster` need a one-line
bump alongside this release.

### Environment variables

`OPDS_SYNC_` prefix; full list in `quire_server/config.py`. Most-load-bearing:

| Var                                 | Default                                | Purpose                                                                 |
| ----------------------------------- | -------------------------------------- | ----------------------------------------------------------------------- |
| `OPDS_SYNC_DATABASE_URL`            | local Postgres                         | SQLAlchemy URL (asyncpg).                                               |
| `OPDS_SYNC_CWA_BASE_URL`            | in-cluster Calibre                     | Upstream calibre-web URL for Basic auth proxying.                       |
| `OPDS_SYNC_PROGRESS_ENABLED`        | `true`                                 | Mounts `/sync/v1/*`. Disable for AI-only mode.                          |
| `OPDS_SYNC_AI_ENABLED`              | `true`                                 | Mounts `/ai/v1/*` and enables lazy AI provider imports.                 |
| `OPDS_SYNC_MAX_REQUEST_BYTES`       | `1048576` (1 MiB)                      | `RequestSizeMiddleware` threshold; oversized requests get 413.          |
| `OPDS_SYNC_AI_BASE_URL`             | unset                                  | Required when `AI_ENABLED=true`; OpenAI-compatible endpoint.            |
| `OPDS_SYNC_AI_MODEL`                | unset                                  | Required when `AI_ENABLED=true`; model id.                              |
| `OPDS_SYNC_AI_API_KEY`              | unset                                  | Bearer token; never logged or returned.                                 |
| `OPDS_SYNC_AI_RATE_PER_MIN`         | `10`                                   | Process-wide token bucket against `AI_BASE_URL`.                        |
| `OPDS_SYNC_AI_DAILY_BUDGET`         | `200`                                  | Per-user generations per UTC day; 0 disables.                           |
| `OPDS_SYNC_AI_REGEN_DAILY_LIMIT`    | `3`                                    | Per-user `/insights/regenerate` ceiling per UTC day.                    |
| `OPDS_SYNC_AI_AUTH_MODE`            | `basic`                                | `basic` (default, wraps calibre-web verifier) or `token` (HMAC-SHA256). |
| `OPDS_SYNC_AI_TOKEN_SECRETS`        | unset                                  | Token mode: JSON `{kid: secret}`. Each secret ≥32 bytes; multiple kids enable rotation. |
| `OPDS_SYNC_AI_TOKEN_ISSUER`         | unset                                  | Token mode: required; validated against `iss`.                          |
| `OPDS_SYNC_AI_TOKEN_AUDIENCE`       | unset                                  | Token mode: required; validated against `aud`.                          |
| `OPDS_SYNC_AUTH_CACHE_POSITIVE_TTL_S` | `60`                                 | Cached `200` from the auth probe.                                       |
| `OPDS_SYNC_AUTH_CACHE_NEGATIVE_TTL_S` | `10`                                 | Cached `401` from the auth probe.                                       |

#### AI auth mode (PR-B, 2026-05-16)

`/ai/v1/*` routes go through a pluggable `AiAuthenticator` (sync routes are
unaffected). Two modes:

- **`basic`** (default) — wraps the existing calibre-web Basic verifier;
  `AiPrincipal.tenant_id` is always `"local"`. No additional config required.
- **`token`** — HMAC-SHA256 bearer tokens. Wire format: `header.payload.signature`
  with header `{alg=HS256, kid}` and payload claims
  `{iss, aud, exp, iat, sub, tenant_id, scope?}`, each segment URL-safe
  base64 with no padding. Token issuance is out of scope here — this server
  only verifies.

Token-mode misconfiguration (`OPDS_SYNC_AI_TOKEN_SECRETS` missing or empty,
any secret shorter than 32 bytes, missing issuer or audience) raises at
startup and crashloops the process. There is **no silent downgrade to
basic** — a hosted deployment that intended token mode must crashloop rather
than accept anything.

Secret rotation: list every active `kid` in `OPDS_SYNC_AI_TOKEN_SECRETS`;
the verifier accepts tokens signed under any registered kid. Mint with the
newest. Drop a retired kid from the JSON only after every issued token under
it has expired (token `exp` is capped at 24h, so a 24h overlap window is the
floor). Verification failures all collapse to a single `401 invalid
credentials`; per-failure reasons live in structured logs only
(`event=ai.auth.token_rejected`).

`X-Request-ID` is read or generated by `RequestIDMiddleware`, bound to a
`contextvars` ContextVar so logs carry it, and echoed back on every response
(including 413 / 4xx / 5xx). Pass one in to correlate a client trace with
server logs.

### Deploy-time migrations

The container entrypoint runs `python /app/scripts/migrate.py`, **not**
`alembic upgrade head`. The wrapper upgrades the unlabeled `0001..0004`
backbone, then `alembic upgrade <branch>@head` for each enabled+materialized
branch (`progress`, `ai`). Branches with no migration files yet are skipped.
See `migrations/README.md` for the splice rule and labeling convention.

## AI smoke test

End-to-end check that the `/ai/v1/*` surface is wired correctly against a
real provider. Requires `OPDS_SYNC_AI_ENABLED=true` and a reachable
OpenAI-compatible endpoint (Ollama, llama.cpp, vLLM, OpenAI, …).

Set credentials for an existing user, then hit the three endpoints in
order. The lookup call will perform a real model call and consume one
unit from the user's daily budget.

```sh
USER=admin
read -rs PASS && echo
AUTH=$(printf '%s' "$USER:$PASS" | base64)
BASE=http://localhost:8000/ai/v1

curl -fsS -H "Authorization: Basic $AUTH" "$BASE/config"

curl -fsS -X PUT -H "Authorization: Basic $AUTH" -H "Content-Type: application/json" \
  -d '{"ai_enabled":true}' "$BASE/preferences"

curl -fsS -X POST -H "Authorization: Basic $AUTH" -H "Content-Type: application/json" \
  -d '{
    "identity":{"metadata_id":"9780553293357","content_hash":"smoketest"},
    "bundle":{"title":"Foundation","author":"Isaac Asimov","publisher":"Bantam Spectra"}
  }' "$BASE/insights/lookup"
```

Expect: `/config` reports `configured: true` with the model id, `/preferences`
echoes `ai_enabled: true`, and `/insights/lookup` returns a populated
`payload.summary`, at least one Wikipedia or OpenLibrary `sources` entry,
and `payload.confidence` of `medium` or `high`.

When the server runs inside a Kubernetes cluster and is not exposed
locally, the same three calls work from inside the pod:

```sh
AUTH=$(printf '%s' "$USER:$PASS" | base64)
kubectl -n <namespace> exec -i deploy/opds-sync -- python3 - "$AUTH" <<'PY'
import json, sys, urllib.request, urllib.error
AUTH = sys.argv[1]
BASE = "http://127.0.0.1:8000/ai/v1"
HDRS = {"Authorization": f"Basic {AUTH}", "Content-Type": "application/json"}
def call(method, path, body=None):
    data = json.dumps(body).encode() if body else None
    req = urllib.request.Request(BASE + path, data=data, headers=HDRS, method=method)
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            print(r.status, r.read().decode())
    except urllib.error.HTTPError as e:
        print(e.code, e.read().decode())
call("GET",  "/config")
call("PUT",  "/preferences", {"ai_enabled": True})
call("POST", "/insights/lookup", {
    "identity": {"metadata_id": "9780553293357", "content_hash": "smoketest"},
    "bundle":   {"title": "Foundation", "author": "Isaac Asimov", "publisher": "Bantam Spectra"},
})
PY
```

## Health and library smoke

The `/ai/v1/health` endpoint is **unauthenticated** (parity with the root
`/health` / `/readyz` probes); no Basic header needed:

```sh
curl -fsS http://localhost:8000/ai/v1/health | jq
# In-cluster:
# curl -fsS http://opds-sync.<ns>.svc.cluster.local:8000/ai/v1/health | jq
```

Expected after the AI smoke above: `provider_reachable: true`, `model_id`
matching `AI_MODEL`, `retrieval_sources[].reachable` true for any source
that returned a citation. Reachability is tri-state — `null` means "not yet
observed this process" (cleared on every restart).

The `/library/v1/items` endpoint is authenticated like sync. PUT one item,
list it back, then soft-delete:

```sh
USER=admin
read -rs PASS && echo
AUTH=$(printf '%s' "$USER:$PASS" | base64)
BASE=http://localhost:8000/library/v1

curl -fsS -X PUT -H "Authorization: Basic $AUTH" -H "Content-Type: application/json" \
  -d '{"item":{
    "content_hash":"smoketest",
    "title":"Foundation",
    "authors":["Isaac Asimov"],
    "metadata_id":"9780553293357"
  }}' "$BASE/items" | jq

curl -fsS -H "Authorization: Basic $AUTH" "$BASE/items" | jq '.items[0]'

curl -fsS -X DELETE -H "Authorization: Basic $AUTH" -H "Content-Type: application/json" \
  -d '{"item":{"content_hash":"smoketest"}}' "$BASE/items" | jq '.deleted_at'

# Per-user stats roll-up (PR9, 2026-05-17). Joins library_items with
# progress and the live book_insights cache.
curl -fsS -H "Authorization: Basic $AUTH" "$BASE/stats" | jq
```

Expect the PUT response to include server-assigned `created_at` /
`updated_at` and `deleted_at: null`; GET without `since` returns the live
row; DELETE returns the row with a populated `deleted_at`. A second DELETE
is a no-op (timestamps preserved — see `docs/sync-api.md` for the
tombstone semantics). The `/stats` response includes `total_books`,
`finished_count`, `in_progress_count`, `top_authors`, `top_themes`, and
a constant `themes_caveat` string — see `docs/sync-api.md` for the
schema and the load-bearing DISTINCT-ON CTE rationale.
