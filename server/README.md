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
uv run uvicorn opds_sync.main:app --reload
```

Tests require Docker (testcontainers spins up Postgres).

## Self-hosting via docker-compose

```sh
cd server
cp .env.example .env
# Edit .env: at minimum set OPDS_SYNC_CWA_BASE_URL and POSTGRES_PASSWORD.
docker compose up -d
curl http://localhost:8000/health
```

Migrations run automatically on container start via `scripts/migrate.py`,
which respects the deploy-mode flags `OPDS_SYNC_PROGRESS_ENABLED` and
`OPDS_SYNC_AI_ENABLED` (both default `true`). See `migrations/README.md`
for the branch-label convention. The image is published to
`ghcr.io/vitofico/opds-sync:latest` by `server-ci.yaml`.

### Deploy modes

| Mode             | `OPDS_SYNC_PROGRESS_ENABLED` | `OPDS_SYNC_AI_ENABLED` | Mounts                          |
| ---------------- | ---------------------------- | ---------------------- | ------------------------------- |
| Full stack       | `true` (default)             | `true` (default)       | `/sync/v1/*`, `/ai/v1/*`        |
| Sync only        | `true`                       | `false`                | `/sync/v1/*`                    |
| AI only          | `false`                      | `true`                 | `/ai/v1/*`                      |

`/health` and `/readyz` are mounted on the root in every mode.

Update the health-probe path: it moved from `/sync/v1/healthz` (pre-PR-A) to
`/health` in PR-A. The k8s manifests in `theficos-cluster` need a one-line
bump alongside this release.

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
