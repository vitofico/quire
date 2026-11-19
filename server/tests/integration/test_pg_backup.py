import asyncio
from datetime import UTC, datetime
from uuid import uuid4

import pytest
from alembic.config import Config
from sqlalchemy import select, text
from sqlalchemy.engine import make_url
from sqlalchemy.ext.asyncio import create_async_engine

from quire_server.db.models import Document, NativeSession, NativeUser
from scripts import pg_backup
from scripts.migrate import run_migrations

_EXPIRING_AUTH_TABLES = ("native_sessions", "oidc_transactions", "pairing_tickets")


@pytest.fixture
def backup_archive(postgres_container, monkeypatch, tmp_path):
    """Run the backup CLI with the matching tools inside the Postgres testcontainer."""
    container = postgres_container.get_wrapped_container()
    destination = tmp_path.resolve() / "backup"
    archive = destination / "core.dump"
    container_archive = f"/tmp/quire-backup-{uuid4().hex}.dump"

    def invoke(arguments, env):
        command = [container_archive if arg == str(archive) else arg for arg in arguments]
        result = container.exec_run(
            command,
            environment={
                "PGHOST": "127.0.0.1",
                "PGPORT": "5432",
                "PGUSER": env["PGUSER"],
                "PGPASSWORD": env["PGPASSWORD"],
                "PGDATABASE": env["PGDATABASE"],
            },
        )
        if result.exit_code:
            raise ValueError(f"{arguments[0]} failed in the testcontainer: {result.output!r}")
        if command[0] == "pg_dump" and "--file" in command:
            copied = container.exec_run(["cat", container_archive])
            assert copied.exit_code == 0, copied.output
            archive.write_bytes(copied.output)
        return result.output

    monkeypatch.setattr(pg_backup, "command", invoke)
    yield destination
    container.exec_run(["rm", "-f", container_archive])


@pytest.fixture
async def backup_target(postgres_url, backup_archive):
    url = make_url(postgres_url)
    names = [f"backup_test_{uuid4().hex}" for _ in range(2)]
    admin = create_async_engine(url, isolation_level="AUTOCOMMIT")
    created = []
    try:
        async with admin.connect() as connection:
            for name in names:
                await connection.execute(text(f"CREATE DATABASE {name}"))
                created.append(name)

        source_url, target_url = (
            url.set(database=name).render_as_string(hide_password=False) for name in names
        )
        config = Config("alembic.ini")
        config.set_main_option("sqlalchemy.url", source_url)
        await asyncio.to_thread(run_migrations, config, progress_enabled=True, ai_enabled=True)

        source = create_async_engine(source_url)
        try:
            async with source.begin() as connection:
                # Inert database markers. The session is already expired and revoked.
                expired = datetime(2000, 1, 1, tzinfo=UTC)
                user_id = await connection.scalar(
                    NativeUser.__table__.insert()
                    .values(
                        email="backup-reader@example.invalid", password_hash="not-a-password-hash"
                    )
                    .returning(NativeUser.id)
                )
                await connection.execute(
                    NativeSession.__table__.insert().values(
                        token_hash="not-a-token-hash",
                        user_id=user_id,
                        expires_at=expired,
                        revoked_at=expired,
                    )
                )
                await connection.execute(
                    text(
                        "INSERT INTO oidc_transactions (state_hash, payload, expires_at) "
                        "VALUES ('not-a-state-hash', CAST('{}' AS json), :expires_at)"
                    ),
                    {"expires_at": expired},
                )
                await connection.execute(
                    text(
                        "INSERT INTO pairing_tickets "
                        "(ticket_hash, user_id, session_hash, expires_at) "
                        "VALUES ('not-a-ticket-hash', :user_id, 'not-a-token-hash', :expires_at)"
                    ),
                    {"user_id": user_id, "expires_at": expired},
                )
                await connection.execute(
                    Document.__table__.insert().values(
                        user_id="backup-reader",
                        metadata_id="backup-metadata",
                        content_hash="backup-content",
                        identity_hash_version=2,
                    )
                )
        finally:
            await source.dispose()

        await asyncio.to_thread(pg_backup.backup, source_url, backup_archive)
        yield target_url, backup_archive
    finally:
        try:
            async with admin.connect() as connection:
                for name in reversed(created):
                    await connection.execute(text(f"DROP DATABASE {name}"))
        finally:
            await admin.dispose()


async def test_backup_restores_documents_but_discards_auth_state(backup_target):
    target_url, archive = backup_target
    await asyncio.to_thread(pg_backup.restore, target_url, archive)

    target = create_async_engine(target_url)
    try:
        async with target.connect() as connection:
            rows = await connection.execute(
                select(
                    Document.user_id,
                    Document.metadata_id,
                    Document.content_hash,
                    Document.identity_hash_version,
                )
            )
            assert rows.all() == [("backup-reader", "backup-metadata", "backup-content", 2)]
            assert (
                await connection.scalar(select(NativeUser.email)) == "backup-reader@example.invalid"
            )
            for table in _EXPIRING_AUTH_TABLES:
                assert await connection.scalar(text(f"SELECT count(*) FROM {table}")) == 0, table
    finally:
        await target.dispose()


async def test_restore_refuses_nonempty_database(backup_target):
    target_url, archive = backup_target
    target = create_async_engine(target_url)
    try:
        async with target.begin() as connection:
            await connection.execute(text("CREATE TABLE restore_sentinel (value text NOT NULL)"))
            await connection.execute(text("INSERT INTO restore_sentinel VALUES ('keep me')"))

        with pytest.raises(ValueError, match="restore target must be.*empty database"):
            await asyncio.to_thread(pg_backup.restore, target_url, archive)

        async with target.connect() as connection:
            assert await connection.scalar(text("SELECT value FROM restore_sentinel")) == "keep me"
            tables = await connection.scalars(
                text("SELECT tablename FROM pg_tables WHERE schemaname = 'public'")
            )
            assert tables.all() == ["restore_sentinel"]
    finally:
        await target.dispose()
