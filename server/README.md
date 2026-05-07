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
