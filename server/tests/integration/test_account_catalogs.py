"""The `catalogs_001` migration and the key-file check against real Postgres."""

from __future__ import annotations

import asyncio
from uuid import uuid4

import pytest
from alembic import command
from alembic.config import Config
from sqlalchemy import inspect, text
from sqlalchemy.exc import DBAPIError, IntegrityError

from quire_server.config import Settings
from quire_server.core.catalog_secrets import CatalogSecrets, create_catalog_key
from quire_server.db.models import AccountCatalog


def _row(user_id: str = "native:1", **values) -> AccountCatalog:
    catalog_id = values.pop("id", uuid4())
    sealed = CatalogSecrets({"k1": bytes(32)}).encrypt(
        user_id, catalog_id, {"url": "https://example.org/opds"}
    )
    defaults = dict(name="Gutenberg", kind="opds", host="https://example.org", position=0)
    return AccountCatalog(user_id=user_id, id=catalog_id, secret=sealed, **defaults | values)


async def test_rows_are_keyed_by_user_and_id(session):
    shared_id = uuid4()
    session.add_all([_row("native:1", id=shared_id), _row("native:2", id=shared_id)])
    await session.flush()

    stored = await session.get(AccountCatalog, ("native:1", shared_id))
    assert stored.created_at is not None and stored.updated_at is not None
    session.add(_row("native:1", id=shared_id))
    with pytest.raises(IntegrityError):
        async with session.begin_nested():
            await session.flush()


@pytest.mark.parametrize("values", [{"kind": "kavita"}, {"name": "n" * 61}])
async def test_kind_and_name_length_are_enforced(session, values):
    session.add(_row(**values))
    with pytest.raises(DBAPIError) as error:
        async with session.begin_nested():
            await session.flush()
    assert error.value.orig.sqlstate in {"23514", "22001"}


async def test_no_key_file_is_created_while_saved_catalogs_exist(session, tmp_path):
    settings = Settings(_env_file=None, catalog_key_file=str(tmp_path / "catalog.key"))
    session.add(_row())
    await session.flush()

    assert await create_catalog_key(settings, session) is None
    assert list(tmp_path.iterdir()) == []

    await session.rollback()
    assert await create_catalog_key(settings, session) is not None
    assert (tmp_path / "catalog.key").exists()


async def test_downgrade_drops_the_table_and_upgrade_restores_it(postgres_url, engine):
    cfg = Config("alembic.ini")
    cfg.set_main_option("sqlalchemy.url", postgres_url)

    async def tables() -> set[str]:
        async with engine.connect() as conn:
            return set(await conn.run_sync(lambda c: inspect(c).get_table_names()))

    async def versions() -> set[str]:
        async with engine.connect() as conn:
            return set(await conn.scalars(text("SELECT version_num FROM alembic_version")))

    await asyncio.to_thread(command.downgrade, cfg, "catalogs@-1")
    try:
        assert "account_catalogs" not in await tables()
        assert "catalogs_001" not in await versions()
    finally:
        await asyncio.to_thread(command.upgrade, cfg, "catalogs@head")
    assert "account_catalogs" in await tables()
    assert "catalogs_001" in await versions()
