# Quire Server

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
| `docker-compose.yml`       | postgres + quire-server                                  | You already run calibre-web (and maybe TLS) elsewhere and only want the sync/AI server.   |
| `docker-compose.full.yml`  | postgres + calibre-web + quire-server + Caddy (TLS)      | You want the whole stack behind a single base URL with TLS, matching the production k8s ingress. |

Both files need Docker Compose v2.24.0 or newer: they load `.env` with the
long `env_file` syntax (`path` plus `required`), which older Compose
versions do not understand.

### Minimal: bring your own proxy

```sh
cd server
cp .env.example .env
# Edit .env: at minimum set QUIRE_SERVER_CWA_BASE_URL and POSTGRES_PASSWORD.
docker compose up -d
curl http://localhost:8000/health
```

quire-server listens on `${QUIRE_SERVER_PORT:-8000}`. Every `QUIRE_SERVER_*`
line in `.env` reaches the server, including the AI provider block, so the
minimal compose supports every deploy mode. The exception is
`QUIRE_SERVER_DATABASE_URL`, pinned by the compose file to the bundled
Postgres; edit the compose file to use an external database. Point Quire's
"sync URL" at it and Quire's "OPDS URL" at your existing calibre-web. Two
URLs to configure in the app; you handle TLS yourself if exposing to the
internet.

With this file, `.env` must exist for every compose command, including
`ps`, `logs`, and `down`, because `QUIRE_SERVER_CWA_BASE_URL` is required
and the compose file refuses to start without it. The full-stack compose
below pins its own calibre-web URL and starts without a `.env`.

### Full-stack reference compose

A Caddy front-end with path-based routing that mirrors the production
Kubernetes ingress, so the Android app only needs to know one base URL
(the Caddy hostname) — calibre-web's OPDS catalog AND quire-server's
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
#   - if AI is enabled (default), uncomment + fill the QUIRE_SERVER_AI_* vars
docker compose -f docker-compose.full.yml up -d
curl -fsSk https://localhost/health
```

Routing inside the Caddy front-end (`caddy/Caddyfile`):

```caddyfile
{$QUIRE_SITE_ADDRESS:localhost} {
    tls internal

    @quire path /sync/* /ai/* /library/* /health /readyz
    handle @quire {
        reverse_proxy quire-server:8000
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
# Unauth health probes (quire-server mounts these at the root)
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

| Mode       | `QUIRE_SERVER_PROGRESS_ENABLED` | `QUIRE_SERVER_AI_ENABLED` | What the Caddy front-end serves                                  |
| ---------- | ---------------------------- | ---------------------- | ---------------------------------------------------------------- |
| Full stack | `true` (default)             | `true` (default)       | `/sync/v1/*` + `/library/v1/*` (items + stats) + `/ai/v1/*` + calibre-web at `/` |
| Sync only  | `true`                       | `false`                | `/sync/v1/*` + `/library/v1/*` (items + stats) + calibre-web at `/` |
| AI only    | `false`                      | `true`                 | `/ai/v1/*` only — drop the `calibre-web` service from the compose for a leaner stack |

Set both flags in `.env`. Sync-only deploys don't need
`QUIRE_SERVER_AI_*`; AI-only / Cloud-style deploys that don't run calibre-web
should set `QUIRE_SERVER_AUTH_BACKEND=native`, which makes `/ai/v1/*`
authenticate against the same NativeAuth session tokens as the rest of the
API (no separate AI token config). The older `QUIRE_SERVER_AI_AUTH_MODE=token`
HMAC path is **deprecated** as of Phase 0, task X-2 (removal scheduled in 2
minor releases). See the "AI auth mode" section below.

##### Mode examples

**Full stack** (default — nothing to change):

```dotenv
QUIRE_SERVER_PROGRESS_ENABLED=true
QUIRE_SERVER_AI_ENABLED=true
QUIRE_SERVER_AI_BASE_URL=https://ollama.example.com/v1
QUIRE_SERVER_AI_MODEL=gpt-oss:120b-cloud
QUIRE_SERVER_AI_API_KEY=sk-...
```

**Sync only** (privacy purists; no LLM calls leave the host):

```dotenv
QUIRE_SERVER_PROGRESS_ENABLED=true
QUIRE_SERVER_AI_ENABLED=false
# QUIRE_SERVER_AI_* may be omitted — they're unused.
```

In sync-only mode:

- The Caddyfile's `/ai/*` matcher still routes to quire-server, which
  returns **404** for those paths because the AI router is not mounted.
  This is intentional, not a broken proxy.
- `GET /health` returns the health payload without an `ai` mode key
  (the `ai` block is omitted when `QUIRE_SERVER_AI_ENABLED=false`).
- `GET /ai/v1/health` returns 404 (the router itself is gone, not
  just the endpoint).

If you want a cleaner 404 surface, drop the `/ai/*` token from the
Caddyfile's `@quire` matcher so those paths fall through to
calibre-web (which also 404s — same outcome, different actor).

**AI only** (future hosted Quire Cloud AI — no calibre-web in the
stack). The full edit set for `docker-compose.full.yml` + `caddy/Caddyfile`:

1. Delete the entire `calibre-web:` service block.
2. Inside `caddy.depends_on`, remove the `calibre-web: condition: service_healthy`
   entry. Caddy must NOT depend on a service that doesn't exist, or
   `docker compose config/up` fails.
3. In `caddy/Caddyfile`, replace the trailing default handler
   `handle { reverse_proxy calibre-web:8083 }` with
   `handle { respond 404 }` so unknown paths return a clean 404
   rather than a connection failure to a missing upstream.

`.env` for AI-only:

```dotenv
QUIRE_SERVER_PROGRESS_ENABLED=false
QUIRE_SERVER_AI_ENABLED=true
QUIRE_SERVER_AI_BASE_URL=https://...
QUIRE_SERVER_AI_MODEL=...
QUIRE_SERVER_AI_API_KEY=...
QUIRE_SERVER_AI_AUTH_MODE=token
QUIRE_SERVER_AI_TOKEN_SECRETS='{"kid-2026-05": "..."}'
QUIRE_SERVER_AI_TOKEN_ISSUER=https://issuer.example.com
QUIRE_SERVER_AI_TOKEN_AUDIENCE=quire-server
```

### Migrations

Migrations run automatically on container start via `scripts/migrate.py`,
which respects the deploy-mode flags `QUIRE_SERVER_PROGRESS_ENABLED` and
`QUIRE_SERVER_AI_ENABLED` (both default `true`). See `migrations/README.md`
for the branch-label convention. The image is published to
`ghcr.io/vitofico/quire-server:latest` by `server-ci.yaml`.

### Deploy modes

| Mode             | `QUIRE_SERVER_PROGRESS_ENABLED` | `QUIRE_SERVER_AI_ENABLED` | Mounts                                                  |
| ---------------- | ---------------------------- | ---------------------- | ------------------------------------------------------- |
| Full stack       | `true` (default)             | `true` (default)       | `/sync/v1/*`, `/library/v1/*` (items + stats), `/ai/v1/*` |
| Sync only        | `true`                       | `false`                | `/sync/v1/*`, `/library/v1/*` (items + stats)           |
| AI only          | `false`                      | `true`                 | `/ai/v1/*`                                              |

`/health` and `/readyz` are mounted on the root in every mode.

Update the health-probe path: it moved from `/sync/v1/healthz` (pre-PR-A) to
`/health` in PR-A. The k8s manifests in `theficos-cluster` need a one-line
bump alongside this release.

### Environment variables

Every setting is an environment variable with the `QUIRE_SERVER_` prefix,
matched case-insensitively. Both compose files load `.env` wholesale, so a
line in `.env` is all it takes. At boot the server logs one
`event=config.warning` line per problem it can detect (an unknown or
misspelled variable, set in the environment or in `.env`; AI enabled
without a provider; a provider URL without `/v1`; or both
`QUIRE_SERVER_PROGRESS_ENABLED` and `QUIRE_SERVER_AI_ENABLED` false) and
repeats the same list under `warnings` in `GET /health`, so `curl
http://localhost:8000/health` is the first thing to check when something
does not work.

Settings live in `.env`. Compose forwards the whole file to the container;
a variable exported only in the shell, for example
`QUIRE_SERVER_AI_ENABLED=false docker compose up`, is not forwarded any
more.

`QUIRE_SERVER_PORT` is read by the minimal compose only, for the host port
mapping. The server always listens on 8000 inside the container.

#### Required and deploy mode

| Var | Default | Purpose |
| --- | --- | --- |
| `QUIRE_SERVER_DATABASE_URL` | local Postgres | SQLAlchemy URL (asyncpg). Both composes pin it under `environment:`, which wins over `.env`, so a line in `.env` is ignored; edit the compose file for an external database. |
| `QUIRE_SERVER_CWA_BASE_URL` | in-cluster Calibre | Upstream calibre-web URL for Basic auth proxying. Required in the minimal compose (it refuses to start without it); the full compose pins it to its own calibre-web. |
| `QUIRE_SERVER_PROGRESS_ENABLED` | `true` | Mounts `/sync/v1/*` and `/library/v1/*`. Disable for AI-only mode. |
| `QUIRE_SERVER_AI_ENABLED` | `true` | Mounts `/ai/v1/*`. With no provider configured the server boots with a warning and the app reports AI as unconfigured. |
| `QUIRE_SERVER_AUTH_BACKEND` | `calibreweb` | `calibreweb` verifies credentials against calibre-web; `native` keeps its own users and sessions (see "AI auth mode"). |
| `QUIRE_SERVER_LOG_LEVEL` | `INFO` | Python log level: `DEBUG`, `INFO`, `WARNING`, `ERROR`. |

#### AI provider and tuning

| Var | Default | Purpose |
| --- | --- | --- |
| `QUIRE_SERVER_AI_BASE_URL` | unset | OpenAI-compatible endpoint including `/v1`, for example `https://ollama.com/v1` or `http://host.docker.internal:11434/v1` for an Ollama on the Docker host. Empty counts as unset. |
| `QUIRE_SERVER_AI_MODEL` | unset | Model id as the provider names it, for example `gpt-oss:120b-cloud`. Empty counts as unset. |
| `QUIRE_SERVER_AI_API_KEY` | unset | Bearer token; never logged or returned. Empty counts as unset and sends no `Authorization` header. |
| `QUIRE_SERVER_AI_TIMEOUT_S` | `120` | Seconds to wait for one model answer. The server retries once on malformed output, so one request can take twice this. CPU-only hosts often need `300` or more. |
| `QUIRE_SERVER_AI_RETRIEVAL_TIMEOUT_S` | `8` | Seconds for each Wikipedia / Open Library lookup. |
| `QUIRE_SERVER_AI_SOURCES` | `wikipedia,openlibrary` | Comma-separated retrieval sources. Empty disables retrieval and shrinks the prompt to the book metadata, which is the first thing to try when a small local model keeps timing out. |
| `QUIRE_SERVER_AI_MAX_CONCURRENCY` | `4` | Parallel model calls allowed at once. |
| `QUIRE_SERVER_AI_RATE_PER_MIN` | `10` | Process-wide token bucket against the provider. |
| `QUIRE_SERVER_AI_DAILY_BUDGET` | `200` | Per-user generations per UTC day; `0` disables. |
| `QUIRE_SERVER_AI_REGEN_DAILY_LIMIT` | `3` | Per-user `/insights/regenerate` ceiling per UTC day. |
| `QUIRE_SERVER_AI_PROMOTE_DAILY_LIMIT` | `100` | Per-user `/insights/promote` ceiling per UTC day; process-local counter, `0` disables. |
| `QUIRE_SERVER_AI_PROFILE_REFRESH_DAILY_LIMIT` | `3` | Reader Profile refreshes per user per UTC day. |
| `QUIRE_SERVER_AI_PROFILE_TIMEOUT_S` | `90` | Timeout in seconds for one Reader Profile model call. The server retries once when the model answers off-schema, so a refresh can take up to twice this. |

#### Auth probes and request limits

| Var | Default | Purpose |
| --- | --- | --- |
| `QUIRE_SERVER_CWA_PROBE_PATH` | `/opds` | Path on calibre-web hit by the auth probe. |
| `QUIRE_SERVER_CWA_PROBE_TIMEOUT_S` | `3.0` | HTTP timeout for the auth probe. |
| `QUIRE_SERVER_AUTH_CACHE_POSITIVE_TTL_S` | `60` | Seconds a successful probe is cached. |
| `QUIRE_SERVER_AUTH_CACHE_NEGATIVE_TTL_S` | `10` | Seconds a rejected probe is cached. |
| `QUIRE_SERVER_AUTH_CACHE_MAX_ENTRIES` | `1024` | Upper bound on the auth-probe cache. |
| `QUIRE_SERVER_NATIVE_SESSION_TTL_S` | `2592000` (30 days) | Session lifetime under `QUIRE_SERVER_AUTH_BACKEND=native`. |
| `QUIRE_SERVER_MAX_REQUEST_BYTES` | `1048576` (1 MiB) | `RequestSizeMiddleware` threshold; oversized requests get 413. |
| `QUIRE_SERVER_LIBRARY_SYNC_MAX_ITEMS` | `500` | Cap on entries per `POST /library/v1/sync` call. |

#### Advanced and deprecated

Leave these alone unless a section of this README sends you here.

| Var | Default | Purpose |
| --- | --- | --- |
| `QUIRE_SERVER_AI_PROMPT_VERSION` | `"1"` (means unset) | Pins the AI prompt version for cache-key compatibility during a model regression. `"1"` and empty fall back to the in-code constant; see PR-ε for runtime resolution. |
| `QUIRE_SERVER_AI_AUTH_MODE` | `basic` | `basic` (default, wraps the calibre-web verifier) or `token` (HMAC-SHA256, **deprecated**; use `QUIRE_SERVER_AUTH_BACKEND=native` instead). See "AI auth mode" below. |
| `QUIRE_SERVER_AI_TOKEN_SECRETS` | unset | Token mode: JSON `{kid: secret}`. Each secret 32 bytes or more; multiple kids enable rotation. |
| `QUIRE_SERVER_AI_TOKEN_ISSUER` | unset | Token mode: required; validated against `iss`. |
| `QUIRE_SERVER_AI_TOKEN_AUDIENCE` | unset | Token mode: required; validated against `aud`. |
| `QUIRE_SERVER_AI_METADATA_SERVER_LOOKUP_ENABLED` | `false` | **Deprecated (Phase 0, 2026-05-22).** When `true`, `/ai/v1/insights/{lookup,regenerate}` rebuild a `MetadataBundle` from the caller's `library_items` row when the client omits the `bundle` block. Boots emit a `DeprecationWarning` and a `logging.warning`. Removal two minor releases after the Phase 0 release. |

#### Push-model API: deprecated server-side metadata fallback (Phase 0, 2026-05-22)

`POST /ai/v1/insights/{lookup,regenerate}` now require a `bundle`
(`MetadataBundle`) block in the request body — clients are the sole source
of book metadata. Requests that omit `bundle` are rejected with `400
metadata_required`.

For one migration window, `QUIRE_SERVER_AI_METADATA_SERVER_LOOKUP_ENABLED=true`
restores the legacy behavior: when `bundle` is absent, the server
reconstructs a `MetadataBundle` from the caller's `library_items` row keyed
by identity. The flag is **off by default**, **deprecated since this
release**, and **will be removed 2 minor releases later**. Boots with the
flag enabled emit a `DeprecationWarning` and a `logging.warning` so the
deprecation is visible to both Python tooling and operators reading
container logs. Plan your client cutover within the window.

The push-model contract (request shape, identity-hint hierarchy, alias
resolution) is documented in `docs/sync-api.md` under `POST
/ai/v1/insights/lookup` and `POST /ai/v1/insights/regenerate`.

#### AI auth mode (PR-B, 2026-05-16)

`/ai/v1/*` routes go through a pluggable `AiAuthenticator` (sync routes are
unaffected). The concrete authenticator is chosen from **both**
`QUIRE_SERVER_AI_AUTH_MODE` and the primary `QUIRE_SERVER_AUTH_BACKEND`:

- **`basic`** (default) — follows the primary auth backend:
  - with `AUTH_BACKEND=calibreweb` (OSS default), wraps the calibre-web Basic
    verifier; `AiPrincipal.tenant_id` is always `"local"`. No extra config.
  - with `AUTH_BACKEND=native`, `/ai/v1/*` delegates to the **same**
    `NativeAuth` session tokens that govern `/auth/v1`, `/sync/v1`, and
    `/library/v1` — one identity layer, no separate AI token issuer. The
    principal's `subject` is the `native:<id>` user scope and `tenant_id`
    stays `"local"`. This is the supported, non-deprecated path for
    Cloud-style deploys, and the replacement for `token` mode below.
- **`token`** (**deprecated** as of Phase 0, task X-2 — removal scheduled in 2
  minor releases) — HMAC-SHA256 bearer tokens. Wire format:
  `header.payload.signature` with header `{alg=HS256, kid}` and payload claims
  `{iss, aud, exp, iat, sub, tenant_id, scope?}`, each segment URL-safe
  base64 with no padding. Token issuance is out of scope here — this server
  only verifies. **Use `QUIRE_SERVER_AUTH_BACKEND=native` (NativeAuth) as the
  long-term replacement**: NativeAuth (added by Phase 0, task S-1) is the
  primary `AuthBackend` for session-token authentication and is the new
  home for Cloud-style multi-tenant deployments. The `token` code path
  remains fully functional through the deprecation window; existing HS256
  tokens continue to validate until their normal expiry and no client-side
  rotation is required during the window. A `DeprecationWarning` plus a
  `WARNING`-level log record (`event=config.deprecated
  setting=QUIRE_SERVER_AI_AUTH_MODE value=token`) fires at startup when this
  mode is active with `AI_ENABLED=true`.

Token-mode misconfiguration (`QUIRE_SERVER_AI_TOKEN_SECRETS` missing or empty,
any secret shorter than 32 bytes, missing issuer or audience) raises at
startup and crashloops the process. There is **no silent downgrade to
basic** — a hosted deployment that intended token mode must crashloop rather
than accept anything.

Secret rotation: list every active `kid` in `QUIRE_SERVER_AI_TOKEN_SECRETS`;
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

## Slow models and timeouts

One insight is one model call bounded by `QUIRE_SERVER_AI_TIMEOUT_S`
(default 120 s), retried once when the model answers with malformed JSON,
so a single `/ai/v1/insights/lookup` can take twice that plus a few seconds
of Wikipedia and Open Library retrieval. The app reads
`generation_timeout_s` from `GET /ai/v1/config` and waits twice that plus
30 s, so raising the variable on the server is the whole fix; the app
adapts on its next config refresh.

A quick answer from `ollama run` proves little. That prompt is a few words;
Quire's is a few thousand characters of metadata, retrieved snippets and the
JSON schema, and on a CPU the prompt evaluation is most of the wall time.
Ollama also unloads a model after five idle minutes, so the first request
after a pause pays the load time again (`OLLAMA_KEEP_ALIVE=30m` on the Ollama
side keeps it resident). The `event=ai.generate.error` log line carries
`prompt_chars`, the size of what the model was given.

When a small local model keeps timing out, work down this ladder:

1. Set `QUIRE_SERVER_AI_TIMEOUT_S=600` and try again. If it now succeeds, the
   model is simply slow; keep the higher value or pick a faster model.
2. Set `QUIRE_SERVER_AI_SOURCES=` (empty). Retrieval is skipped and the prompt
   holds only the book metadata. If that succeeds, the model cannot digest
   the retrieved context in time; leave retrieval off or use a larger model.
3. Point `QUIRE_SERVER_AI_BASE_URL` at a hosted model. Ollama's free tier
   with `gpt-oss:120b-cloud` answers in seconds and needs no local GPU.

When the provider fails, the server answers with a JSON body (`detail.code`,
`detail.message`, `detail.hint`) and logs the same hint on the
`event=ai.generate.error` line:

| What you see | Meaning | What to do |
| --- | --- | --- |
| 504 `provider_timeout` | The model did not answer in time. Usual with any model on CPU, and on the first call after Ollama unloaded the model. | Follow the ladder above: raise `QUIRE_SERVER_AI_TIMEOUT_S`, then try `QUIRE_SERVER_AI_SOURCES=` to shrink the prompt, then a hosted model such as `gpt-oss:120b-cloud` on Ollama's free tier. |
| 502 `provider_unreachable` | The container could not reach `QUIRE_SERVER_AI_BASE_URL` (connection refused, firewall, or no answer to the TCP connect within 10 s). | Test from inside the container: `docker compose exec quire-server python -c "import os,urllib.request;print(urllib.request.urlopen(os.environ['QUIRE_SERVER_AI_BASE_URL']+'/models').status)"`. For an Ollama on the Docker host use `http://host.docker.internal:11434/v1`. |
| 502 `provider_rejected`, `provider_status` 401 or 403 | The provider refused the key. | Check `QUIRE_SERVER_AI_API_KEY`. |
| 502 `provider_rejected`, `provider_status` 404 | The provider does not know the model. | Check `QUIRE_SERVER_AI_MODEL`; `ollama pull <model>` for a local Ollama. |
| 502 `provider_invalid_output` | The model answered, but not with the JSON structure Quire asks for. | Small models often cannot; try a larger one. |

`GET /ai/v1/health` (authenticated) shows the last failure class and when
the provider was last reachable. `GET /health` lists boot-time
configuration warnings.

## AI smoke test

End-to-end check that the `/ai/v1/*` surface is wired correctly against a
real provider. Requires `QUIRE_SERVER_AI_ENABLED=true` and a reachable
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

Once the cache has at least one row, the bulk-sync and reader-profile
surfaces are also reachable from the same shell session:

```sh
# Insight bulk-sync (PR-η; first call uses empty cursor; the response's
# `next_cursor` is a (generated_at, id) tuple - persist the full pair).
curl -fsS -H "Authorization: Basic $AUTH" "$BASE/insights/sync?limit=100" | jq

# Reader profile (PR-α/β; opt-in required via /preferences; refresh
# capped at QUIRE_SERVER_AI_PROFILE_REFRESH_DAILY_LIMIT per UTC day).
curl -fsS -H "Authorization: Basic $AUTH" "$BASE/profile" | jq
```

When the server runs inside a Kubernetes cluster and is not exposed
locally, the same three calls work from inside the pod:

```sh
AUTH=$(printf '%s' "$USER:$PASS" | base64)
kubectl -n <namespace> exec -i deploy/quire-server -- python3 - "$AUTH" <<'PY'
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
# curl -fsS http://quire-server.<ns>.svc.cluster.local:8000/ai/v1/health | jq
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
