"""Generic pg_dump manifest backup and explicit empty-database restore."""

import argparse
import asyncio
import hashlib
import json
import os
import re
import subprocess
from datetime import UTC, datetime
from pathlib import Path

from sqlalchemy import text
from sqlalchemy.engine import make_url
from sqlalchemy.ext.asyncio import create_async_engine


def environment(database_url):
    url = make_url(database_url)
    if url.get_backend_name() != "postgresql":
        raise ValueError("PostgreSQL URL required")
    result = os.environ.copy()
    result.update(
        PGHOST=url.host or "localhost",
        PGPORT=str(url.port or 5432),
        PGUSER=url.username or "postgres",
        PGPASSWORD=url.password or "",
        PGDATABASE=url.database or "postgres",
    )
    return result


def command(arguments, env):
    result = subprocess.run(arguments, env=env, capture_output=True, check=False)
    if result.returncode:
        raise ValueError("PostgreSQL backup tool failed; check availability and permissions")
    return result.stdout


def compatible_major(database_url, tool):
    env = environment(database_url)
    server = (
        int(command(["psql", "--no-password", "-Atc", "SHOW server_version_num"], env)) // 10000
    )
    version = command([tool, "--version"], env).decode()
    match = re.search(r"(\d+)\.", version)
    if match is None or int(match[1]) != server:
        raise ValueError("backup tools must match the PostgreSQL server major version")
    return server


def backup(database_url, destination):
    major = compatible_major(database_url, "pg_dump")
    destination = Path(destination).resolve()
    destination.mkdir(mode=0o700)
    archive = destination / "core.dump"
    env = environment(database_url)
    command(
        [
            "pg_dump",
            "--no-password",
            "--format=custom",
            "--no-owner",
            "--no-acl",
            "--file",
            str(archive),
        ],
        env,
    )
    command(["pg_restore", "--list", str(archive)], env)
    archive.chmod(0o600)
    manifest = {
        "version": 1,
        "engine": "postgresql",
        "postgres_major": major,
        "created_at": datetime.now(UTC).isoformat(),
        "sha256": hashlib.sha256(archive.read_bytes()).hexdigest(),
    }
    (destination / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    (destination / "manifest.json").chmod(0o600)


async def assert_empty(database_url):
    engine = create_async_engine(database_url)
    try:
        async with engine.connect() as connection:
            count = await connection.scalar(
                text(
                    "SELECT count(*) FROM pg_class c JOIN pg_namespace n "
                    "ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relkind IN ('r','p')"
                )
            )
            if count:
                raise ValueError("restore target must be an explicitly selected empty database")
    finally:
        await engine.dispose()


def restore(database_url, source):
    source = Path(source).resolve()
    manifest = json.loads((source / "manifest.json").read_text())
    archive = source / "core.dump"
    if (
        manifest.get("version") != 1
        or manifest.get("engine") != "postgresql"
        or (hashlib.sha256(archive.read_bytes()).hexdigest() != manifest.get("sha256"))
    ):
        raise ValueError("backup manifest mismatch")
    if compatible_major(database_url, "pg_restore") != manifest.get("postgres_major"):
        raise ValueError("restore target must match the captured PostgreSQL major version")
    asyncio.run(assert_empty(database_url))
    env = environment(database_url)
    command(
        [
            "pg_restore",
            "--no-password",
            "--exit-on-error",
            "--no-owner",
            "--no-acl",
            "--dbname",
            env["PGDATABASE"],
            str(archive),
        ],
        env,
    )
    # A restore must not resurrect bearer sessions or pending auth exchanges.
    command(
        [
            "psql",
            "--no-password",
            "--set",
            "ON_ERROR_STOP=1",
            "--command",
            "TRUNCATE native_sessions CASCADE; DELETE FROM oidc_transactions; "
            "DELETE FROM pairing_tickets;",
        ],
        env,
    )


def main():
    from quire_server.config import get_settings

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["backup", "restore"])
    parser.add_argument("path", type=Path)
    args = parser.parse_args()
    try:
        (backup if args.mode == "backup" else restore)(get_settings().database_url, args.path)
    except (ValueError, OSError):
        raise SystemExit(
            "Backup/restore failed; existing databases are never overwritten."
        ) from None


if __name__ == "__main__":
    main()
