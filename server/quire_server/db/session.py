from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from sqlalchemy.ext.asyncio import (
    AsyncEngine,
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)

from quire_server.config import get_settings


def make_engine(database_url: str | None = None) -> AsyncEngine:
    url = database_url or get_settings().database_url
    return create_async_engine(url, pool_pre_ping=True, future=True)


def make_session_factory(engine: AsyncEngine) -> async_sessionmaker[AsyncSession]:
    return async_sessionmaker(engine, expire_on_commit=False, class_=AsyncSession)


_engine: AsyncEngine | None = None
_factory: async_sessionmaker[AsyncSession] | None = None


def configure(engine: AsyncEngine) -> None:
    global _engine, _factory
    _engine = engine
    _factory = make_session_factory(engine)


def _factories() -> async_sessionmaker[AsyncSession]:
    global _engine, _factory
    if _factory is None:
        _engine = make_engine()
        _factory = make_session_factory(_engine)
    return _factory


@asynccontextmanager
async def session_scope() -> AsyncIterator[AsyncSession]:
    factory = _factories()
    async with factory() as session:
        yield session


async def get_session() -> AsyncIterator[AsyncSession]:  # FastAPI dependency
    async with session_scope() as s:
        yield s
