# Quire Server

Quire sync server. FastAPI + Postgres. Source of truth for synced reading state.
The Android reader’s memory bookmarks and optional notes remain device-local;
progress sync, highlight sync and the read-only APIs below do not receive them.

Native mode exposes `GET /auth/v1/me` with the existing `native:N` storage
identity, current CSRF proof and deployed capabilities. Generic browser identity
uses `QUIRE_IDENTITY_PUBLIC_URL` (fixed HTTPS public origin), optional paired
`QUIRE_IDENTITY_OIDC_ISSUER`/`QUIRE_IDENTITY_OIDC_CLIENT_ID`, optional confidential
`QUIRE_IDENTITY_OIDC_CLIENT_SECRET`, and independent 32-character
`QUIRE_IDENTITY_SERVICE_TOKEN` for internal lifecycle operations. Explicit
`QUIRE_IDENTITY_ALLOW_INSECURE_LOOPBACK=true` permits a localhost-only fixture.

`/auth/v1/browser/login` issues a native HttpOnly/Lax cookie. OIDC code + PKCE,
state, nonce and strict issuer/audience/JWKS validation reuse the same session
backend. Exact issuer + subject mappings never auto-link by email. Registration,
verification and recovery belong to the configured IdP; the core does not copy
its password database. Calibre mode and existing bearer API login remain intact.
Operator-only `scripts/create_native_account.py` prompts privately for native
credentials. Login attempts have bounded per-IP/global/concurrent admission.
Do not enable access logs that record OIDC callback query credentials.

Authenticated `POST /auth/v1/pairing` creates a random 120-second one-use ticket;
`POST /auth/v1/pairing/exchange` yields a native device session and server-issued
stable account subject. Cookie mutations require exact Origin and CSRF proof.
Session revocation invalidates unused child tickets. `auth_002` migrates external
identities, transaction/pairing state, session kinds and closure fences forward.
`auth_003` adds two-minute one-use account deletion proofs bound to the live
session and account. `/auth/v1/account/delete-authorization` verifies the current
native password; `/auth/v1/oidc/start?reauth=account-delete` instead forces a fresh
IdP login with validated auth_time and exact linked subject. The purpose-bound
callback validates the server-held transaction's live session, without requiring
an ordinary cookie on that callback; a presented cookie must match. Companions
must consume `/auth/v1/account/delete-authorization/consume` before queueing
customer-requested destructive work. CSRF or a paired bearer alone is insufficient.

Optional `QUIRE_POLICY_URL` + `QUIRE_POLICY_TOKEN` fetch an authenticated generic
`/cloud/internal/policy/{native:N}` response. Exact account, bounded body/total
deadline and a maximum 120-second expiry are validated. Fields include
`limits.monthly_jobs`, optional fixed `quota_window_start`, `sync_writes` and
`account_closed`. The latter denies newly issued hosted sessions after recovery
of a closed account. Enabled hosted policy gates progress/library writes and
optional model jobs; reads/exports/cache remain available. On policy outage new
jobs and hosted writes fail closed. Unconfigured policy preserves self-hosting.
`ai_009` atomically admits monthly logical jobs across generation, regeneration
and model-backed profiles. Cache hits/stats-only profiles are free; failed jobs
retain their slot and bounded provider retries share one debit.

`GET /auth/v1/account/export` includes only caller-owned private data.
`/internal/v1/accounts/{native:N}/identity|close|purge|finalize` requires the
separate lifecycle service credential. Closure serializes with in-flight writes,
revokes sessions/devices and purges private reading state while preserving shared
cached insights. Finalization requires a purged account. Keep internal routes
outside public ingress; companions use these HTTP seams, never this database.

`scripts/pg_backup.py backup <new-directory>` creates a `pg_dump` archive and
SHA256 manifest. Tools/server must have the same PostgreSQL major; the image
includes PostgreSQL 16 tools. `restore` requires a new empty target and clears every native
session, pairing ticket and OIDC exchange. Restore offline with the current
external closure ledger and reconcile before reopening; an older cloud privacy
snapshot must not overwrite newer deletion receipts. Backup encryption,
retention and IdP recovery configuration are operator responsibilities. No EPUB
file storage or commercial plan is implied by these generic seams.

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

Development requires Python 3.12 or newer and uv. Tests require Docker
(testcontainers spins up Postgres). See the
[development guide](../docs/development.md) for checks and module boundaries.

### Highlight sync

`POST /sync/v1/highlights` pushes up to 500 private annotations; `GET
/sync/v1/highlights?since=0&limit=500` pulls revision-ordered pages including
deletion tombstones. Both use the existing calibre-web Basic authentication
(or configured native backend) and are mounted only in full and sync-only
deployments. UUIDs are scoped to the authenticated user. Quotes, opaque
locators, colours and book metadata are separate fields; no EPUB upload is
required and removing a download does not delete its highlights.

Push results include each authoritative winning row. Equal client timestamps
use a deterministic complete-payload ordering, with tombstones winning over
live rows. Identity hash versions are promoted to the maximum learned value
independently of which payload wins. The decimal `next_cursor` belongs to one server/account; clients
save it after applying a page and continue while `has_more` is true. A 409
`highlight_cursor_reset_required` requires restarting the pull at zero.
Per-user transaction locks order revision allocation, commit and reads, so
offline client clocks and in-flight writes cannot create cursor gaps.
Tombstones are retained indefinitely. Native account export and purge include
highlights and their cursor state. See [`../docs/sync-api.md`](../docs/sync-api.md)
for the wire format and tie ordering; the shared Android/Python contract
fixture is `tests/fixtures/highlight-sync.json`.

### Read-only Agent/MCP access

Quire Server exposes a remote Streamable HTTP MCP endpoint at `/mcp` and
read-only REST queries at `/agent/v1/highlights`. Clients can list, search and
retrieve their own stored, synced highlights with book attribution. They cannot
read device-local memory bookmarks or their notes. Both surfaces follow
the progress/full-mode gate and use the configured primary account backend.

For a public HTTPS host, set `QUIRE_SERVER_MCP_PUBLIC_URL=https://books.example.com`
in `.env`, restart the server, and configure a header-capable MCP client with
`https://books.example.com/mcp` and its existing Basic or native Bearer
Authorization header. No OAuth discovery or browser-cookie login is provided
by this MCP endpoint. The reference Caddy routes are included; other reverse
proxies must forward `/mcp` and `/agent/*` to Quire Server with Host preserved.

See [Agent and MCP access](../docs/agent-access.md) for authentication, tools,
preview limits, pagination and REST examples.

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

Every setting, a minimum `.env` for each deploy mode, recipes for Ollama and
other AI providers, and how to check that a change took effect are in
[`docs/configuration.md`](../docs/configuration.md). A `QUIRE_SERVER_*` name
the server does not recognise is listed under `warnings` in
`curl http://localhost:8000/health`.

### Full-stack reference compose

A Caddy front-end with path-based routing, so the Android app only needs
to know one base URL
(the Caddy hostname) — calibre-web's OPDS catalog AND quire-server's
`/sync/*`, `/library/*`, `/ai/*`, `/agent/*`, `/mcp` endpoints and the `/quire-admin` status
page all live under the same origin.

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

    @quire path /sync/* /ai/* /library/* /agent/* /mcp /mcp/* /quire-admin /quire-admin/* /health /readyz
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

# Server status (needs QUIRE_SERVER_ADMIN_USERS=$USER; see "Server status page")
curl -fsSk -H "Authorization: Basic $AUTH" "https://localhost/quire-admin/v1/status" | jq

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

The same image supports three modes via env flags, described in
[`docs/configuration.md`](../docs/configuration.md#deploy-modes). What each
mode means for the Caddy front-end:

| Mode       | `QUIRE_SERVER_PROGRESS_ENABLED` | `QUIRE_SERVER_AI_ENABLED` | What the Caddy front-end serves                                  |
| ---------- | ---------------------------- | ---------------------- | ---------------------------------------------------------------- |
| Full stack | `true` (default)             | `true` (default)       | `/sync/v1/*` + `/library/v1/*` (items + stats) + `/ai/v1/*` + calibre-web at `/` |
| Sync only  | `true`                       | `false`                | `/sync/v1/*` + `/library/v1/*` (items + stats) + calibre-web at `/` |
| AI only    | `false`                      | `true`                 | `/ai/v1/*` only — drop the `calibre-web` service from the compose for a leaner stack |

Set both flags in `.env`; the minimum `.env` for each mode is in
[`docs/configuration.md`](../docs/configuration.md#minimum-env-per-mode).

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

Without calibre-web in the stack, logins need
`QUIRE_SERVER_AUTH_BACKEND=native`, which has no way to create accounts yet
(see "AI auth mode" below). A self-hosted AI-only server keeps calibre-web
for logins; its `.env` is in
[`docs/configuration.md`](../docs/configuration.md#minimum-env-per-mode).

### Migrations

Migrations run automatically on container start via `scripts/migrate.py`,
which respects the deploy-mode flags `QUIRE_SERVER_PROGRESS_ENABLED` and
`QUIRE_SERVER_AI_ENABLED` (both default `true`). See `migrations/README.md`
for the branch-label convention. The image is published to
`ghcr.io/vitofico/quire-server:latest` by `server-ci.yaml`.

### Deploy modes

`/health` and `/readyz` are mounted on the root in every mode, and so is the
`/quire-admin` status page once `QUIRE_SERVER_ADMIN_USERS` names someone.
The two flags and what each mode mounts are in
[`docs/configuration.md`](../docs/configuration.md#deploy-modes).

Update the health-probe path: it moved from `/sync/v1/healthz` (pre-PR-A) to
`/health` in PR-A. The k8s manifests in `theficos-cluster` need a one-line
bump alongside this release.

### Push-model API: deprecated server-side metadata fallback (Phase 0, 2026-05-22)

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

### AI auth mode (PR-B, 2026-05-16)

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

## Server status page

`/quire-admin` is a page for whoever runs the server. Open it in a browser,
log in with your calibre-web account, and it shows:

- the build (the commit the image was made from) and which parts of the API
  are switched on;
- the configuration problems the server found at startup, the same list as
  `GET /health`;
- the AI provider: model and endpoint, whether the last model call got
  through, and a **Test AI connection** button that sends one short request
  and reports the outcome with the same message and hint the app would get;
- whether the database migrations are up to date;
- an **AI settings** form that changes the AI time limits, the lookup
  sources, the rate limit and the daily quotas without a restart. A setting
  the environment or `.env` gives is shown greyed out, because the
  environment wins; see "Changing AI settings on the page" in
  `docs/configuration.md`;
- every setting with the value the server is running with, marked `set` when
  it came from the environment or `.env`, `saved` when it was saved on this
  page, and `default` when nothing set it. Keys and URL passwords show as
  `***`. A line you wrote in `.env` that shows up as `default` never reached
  the container.

The page is off until you name who may see it, in `.env`:

```sh
QUIRE_SERVER_ADMIN_USERS=alice
```

Restart the server and open `https://<your-host>/quire-admin`. Any other
account gets a 403; with the variable empty, every `/quire-admin` path is a
404. The same data is available as JSON for scripts (`-u` makes curl ask for
the password):

```sh
curl -fsSk -u alice https://localhost/quire-admin/v1/status | jq
curl -fsSk -u alice -X POST https://localhost/quire-admin/v1/ai/probe | jq
curl -fsSk -u alice -X PUT https://localhost/quire-admin/v1/settings \
  -H 'Content-Type: application/json' \
  -d '{"QUIRE_SERVER_AI_TIMEOUT_S": 285, "QUIRE_SERVER_AI_DAILY_BUDGET": null}' | jq
```

`PUT /quire-admin/v1/settings` takes variable names; `null` removes a saved
value so the default applies again. It saves all of the changes or none, and
a refused one comes back as a 422 naming the variable and the reason.

Worth knowing:

- The test gets the insight time limit in force in total, the budget of one
  insight model call, so a local model that is still loading is not reported
  as broken. Its outcome also updates `GET /ai/v1/health`.
- The browser login needs `QUIRE_SERVER_AUTH_BACKEND=calibreweb`. Under
  `native`, list users as `native:<id>` and call the JSON endpoints with
  `Authorization: Bearer <session token>`.
- The reference Caddyfile routes `/quire-admin` to quire-server. Behind any
  other reverse proxy, forward `/quire-admin` and `/quire-admin/*` to
  quire-server like `/ai/*`; otherwise the request lands on calibre-web.
- Your password travels the way the app sends it, in a Basic auth header, so
  open the page over HTTPS.

## Slow models and timeouts

Moved to [`docs/configuration.md`](../docs/configuration.md): the recipe for
[CPU-only local models](../docs/configuration.md#cpu-only-local-models)
covers the timeouts and the ladder to work down, and
[Reading AI errors](../docs/configuration.md#reading-ai-errors) explains
every `error_class` in the `event=ai.generate.error` log line and every code
the AI endpoints answer with.

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

## License

Quire Server is licensed under the GNU Affero General Public License,
version 3 only ([`LICENSE`](LICENSE)). If you run a modified version as a
network service, the AGPL requires you to offer its users the corresponding
source. The Quire Android app is licensed separately under Apache-2.0 (see
the [root `LICENSE`](../LICENSE)).
