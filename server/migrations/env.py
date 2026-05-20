import asyncio
from logging.config import fileConfig

from alembic import context
from sqlalchemy.ext.asyncio import async_engine_from_config

from quire_server.config import get_settings
from quire_server.db.models import Base

config = context.config
if config.config_file_name is not None:
    # disable_existing_loggers=False: without this, fileConfig DISABLES every
    # logger that was created before alembic ran (including all `quire_server.*`
    # loggers). The migrate.py wrapper invokes env.py during container start,
    # so production process loggers would arrive at request-handling time
    # already disabled — silencing structured logs like `event=ai.generate.error`.
    fileConfig(config.config_file_name, disable_existing_loggers=False)

target_metadata = Base.metadata


def _ensure_url() -> None:
    """Ensure the database URL is set.

    Priority:
    1. Already set by caller (e.g. test fixture via cfg.set_main_option)
    2. QUIRE_SERVER_DATABASE_URL env var / pydantic settings (legacy
       OPDS_SYNC_DATABASE_URL still accepted via _env_compat for one
       release cycle)
    3. alembic.ini default (localhost fallback)

    We only override if the config still holds the ini default, so test
    fixtures that call cfg.set_main_option() before command.upgrade() win.
    """
    url = config.get_main_option("sqlalchemy.url")
    # DB name remains `opds_sync` as a deliberate non-rename (Lock #20).
    ini_default = "postgresql+asyncpg://postgres:postgres@localhost:5432/opds_sync"
    if not url or url == ini_default:
        settings_url = get_settings().database_url
        if settings_url != ini_default:
            config.set_main_option("sqlalchemy.url", settings_url)


def run_migrations_offline() -> None:
    _ensure_url()
    context.configure(
        url=config.get_main_option("sqlalchemy.url"),
        target_metadata=target_metadata,
        literal_binds=True,
    )
    with context.begin_transaction():
        context.run_migrations()


def do_run_migrations(connection):
    context.configure(connection=connection, target_metadata=target_metadata)
    with context.begin_transaction():
        context.run_migrations()


async def run_migrations_online() -> None:
    _ensure_url()
    connectable = async_engine_from_config(
        config.get_section(config.config_ini_section, {}),
        prefix="sqlalchemy.",
        future=True,
    )
    async with connectable.connect() as connection:
        await connection.run_sync(do_run_migrations)
    await connectable.dispose()


if context.is_offline_mode():
    run_migrations_offline()
else:
    asyncio.run(run_migrations_online())
