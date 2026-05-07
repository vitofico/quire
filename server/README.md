# opds-sync

Quire sync server. FastAPI + Postgres. See
[`docs/superpowers/specs/2026-05-05-phase-2-progress-sync.md`](../docs/superpowers/specs/2026-05-05-phase-2-progress-sync.md).

## Local dev

```sh
cd server
uv venv && source .venv/bin/activate
uv pip install -e ".[dev]"
uv run pytest
uv run uvicorn opds_sync.main:app --reload
```

Tests require Docker (testcontainers spins up Postgres).
