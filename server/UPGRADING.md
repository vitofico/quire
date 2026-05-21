# Upgrading the Self-Hosted Quire Server

This file documents breaking changes in `server/docker-compose.yml` (the minimal
self-hoster compose) and the manual steps required to upgrade without losing
data.

If you run the full compose (`server/docker-compose.full.yml`) you can ignore
this file — that stack already uses the new names.

---

## 2026-05-21 — Postgres volume rename: `opds_sync_pg` → `pg_data`

### What changed

The named volume backing Postgres in `server/docker-compose.yml` was renamed
from `opds_sync_pg` to `pg_data` to match `docker-compose.full.yml` and the
rest of the rebrand from `opds-sync` to `quire-server`.

The Postgres role, database name, and password env var are **unchanged**
(still `opds_sync` / `POSTGRES_PASSWORD`). Only the Docker volume identifier
moved.

### Why this needs manual action

Docker named volumes are not migrated automatically. If you simply `git pull`
and run `docker compose up -d`, Compose will create a fresh, empty `pg_data`
volume and your existing data in `opds_sync_pg` (or `<project>_opds_sync_pg`)
will be orphaned — still on disk, but unused. Postgres will start empty and
the migration script will rebuild the schema from scratch.

**Follow the steps below before running `docker compose up -d` against the new
compose file.**

### Migration steps (preserves data)

All commands assume your working directory is the one containing
`docker-compose.yml` (i.e. `server/` in this repo, or wherever you've
deployed it).

#### 1. Stop the stack

```bash
docker compose down
```

This stops the containers but **does not delete volumes**. Your data is safe.

#### 2. Discover the real volume name

Docker Compose prefixes volume names with the project name (the directory
name by default, or whatever you set via `-p` / `COMPOSE_PROJECT_NAME`). List
volumes to find the actual name:

```bash
docker volume ls | grep opds_sync_pg
```

You should see something like:

```
local     server_opds_sync_pg
```

or, if you used `-p quire`:

```
local     quire_opds_sync_pg
```

Export the names you found so the rest of the commands are copy-pasteable:

```bash
export OLD_VOLUME=server_opds_sync_pg       # ← replace with what you saw above
export NEW_VOLUME=server_pg_data            # ← same prefix, new suffix
```

#### 3. Create the new volume and copy data into it

```bash
docker volume create "$NEW_VOLUME"

docker run --rm \
  -v "$OLD_VOLUME":/from \
  -v "$NEW_VOLUME":/to \
  alpine sh -c "cd /from && tar cf - . | (cd /to && tar xf -)"
```

The `tar | tar` pipe preserves permissions and ownership, which Postgres is
strict about.

#### 4. Verify the new volume looks right

```bash
docker run --rm -v "$NEW_VOLUME":/data alpine ls -la /data
```

You should see Postgres files like `PG_VERSION`, `base/`, `global/`,
`pg_wal/`, etc., owned by uid/gid `70:70` (the alpine postgres uid) or
`999:999` (debian) depending on which Postgres image you ran previously.

Optional sanity check — boot Postgres against the new volume and confirm the
database exists:

```bash
docker run --rm -v "$NEW_VOLUME":/var/lib/postgresql/data \
  -e POSTGRES_PASSWORD=ignored \
  postgres:16-alpine \
  postgres --version
```

#### 5. Start the stack with the new compose

```bash
git pull            # if you haven't already
docker compose up -d
docker compose logs -f postgres quire-server
```

Postgres should come up healthy, `migrate.py` should report no pending
migrations (your schema is already there), and the API should serve requests
against your existing data.

#### 6. Once you've confirmed everything works, drop the old volume

**Only after you've verified the app works against the new volume.** Until
then, keep the old one as a safety net.

```bash
docker volume rm "$OLD_VOLUME"
```

### Rollback

If something goes wrong, the old volume is still intact. Revert the compose
file (or simply edit the `volumes:` block to point back at `opds_sync_pg`)
and `docker compose up -d` — you'll be back where you started.
