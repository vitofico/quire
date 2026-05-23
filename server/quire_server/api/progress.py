import logging
from datetime import UTC, datetime
from typing import Annotated, Literal

from fastapi import APIRouter, Depends, Query
from pydantic import BaseModel, Field, field_serializer
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from quire_server.core.auth import current_user_id
from quire_server.db.models import Document, Progress
from quire_server.db.session import get_session

logger = logging.getLogger(__name__)

router = APIRouter(tags=["progress"])


class DocumentIdentity(BaseModel):
    metadata_id: str | None = None
    content_hash: str
    # Phase 0, task F-1: identity-hash algorithm version travelling on the
    # wire. Default `1` keeps old clients (pre-versioning) round-trippable;
    # new clients send their own value and the server's write paths apply
    # `max(existing, incoming)` so an old client cannot downgrade a row.
    identity_hash_version: int = Field(default=1, ge=1)


class ProgressItem(BaseModel):
    document: DocumentIdentity
    locator: str
    percent: float
    client_updated_at: datetime
    finished_at: datetime | None = None
    # pr-α (Bundle 3) / coordinator §3.10: terminal-state invariant.
    # Mutually exclusive with `finished_at` on write — the handler
    # clears the opposite on a terminal-state flip.
    abandoned_at: datetime | None = None


class ProgressPushBody(BaseModel):
    items: list[ProgressItem]


class ProgressPushResult(BaseModel):
    document: DocumentIdentity
    status: Literal["accepted", "stale"]
    server_client_updated_at: datetime

    @field_serializer("server_client_updated_at")
    def _serialize_dt(self, v: datetime) -> str:
        if v.tzinfo is None:
            v = v.replace(tzinfo=UTC)
        return v.isoformat()


class ProgressPushResponse(BaseModel):
    results: list[ProgressPushResult]


class ProgressPullItem(BaseModel):
    document: DocumentIdentity
    locator: str
    percent: float
    client_updated_at: datetime
    finished_at: datetime | None = None
    abandoned_at: datetime | None = None

    @field_serializer("client_updated_at")
    def _serialize_client_updated_at(self, v: datetime) -> str:
        if v.tzinfo is None:
            v = v.replace(tzinfo=UTC)
        return v.isoformat()

    @field_serializer("finished_at")
    def _serialize_finished_at(self, v: datetime | None) -> str | None:
        if v is None:
            return None
        if v.tzinfo is None:
            v = v.replace(tzinfo=UTC)
        return v.isoformat()

    @field_serializer("abandoned_at")
    def _serialize_abandoned_at(self, v: datetime | None) -> str | None:
        if v is None:
            return None
        if v.tzinfo is None:
            v = v.replace(tzinfo=UTC)
        return v.isoformat()


class ProgressPullResponse(BaseModel):
    items: list[ProgressPullItem]
    server_time: datetime


async def _resolve_or_create_document(
    session: AsyncSession, user_id: str, ident: DocumentIdentity
) -> Document:
    """Per spec §5.4: metadata_id first, then content_hash, else create."""
    if ident.metadata_id:
        existing = (
            await session.execute(
                select(Document).where(
                    Document.user_id == user_id, Document.metadata_id == ident.metadata_id
                )
            )
        ).scalar_one_or_none()
        if existing:
            # Phase 0, task F-1: downgrade protection. An old client (sending
            # `1`) MUST NOT overwrite a row written by a newer client.
            if ident.identity_hash_version > existing.identity_hash_version:
                existing.identity_hash_version = ident.identity_hash_version
            return existing
    existing = (
        await session.execute(
            select(Document).where(
                Document.user_id == user_id, Document.content_hash == ident.content_hash
            )
        )
    ).scalar_one_or_none()
    if existing:
        # Backfill metadata_id if we just learned it
        if ident.metadata_id and existing.metadata_id is None:
            existing.metadata_id = ident.metadata_id
        # Phase 0, task F-1: downgrade protection (see above).
        if ident.identity_hash_version > existing.identity_hash_version:
            existing.identity_hash_version = ident.identity_hash_version
        return existing
    doc = Document(
        user_id=user_id,
        metadata_id=ident.metadata_id,
        content_hash=ident.content_hash,
        identity_hash_version=ident.identity_hash_version,
    )
    session.add(doc)
    await session.flush()  # populate doc.pk
    return doc


@router.post("/progress", response_model=ProgressPushResponse)
async def push_progress(
    body: ProgressPushBody,
    user_id: Annotated[str, Depends(current_user_id)],
    session: Annotated[AsyncSession, Depends(get_session)],
) -> ProgressPushResponse:
    results: list[ProgressPushResult] = []
    for item in body.items:
        doc = await _resolve_or_create_document(session, user_id, item.document)
        # Phase 0, task F-1: the result echoes the persisted Document's
        # identity_hash_version (which may be >= the incoming one after the
        # downgrade-protection logic in _resolve_or_create_document).
        persisted_identity = DocumentIdentity(
            metadata_id=doc.metadata_id,
            content_hash=doc.content_hash,
            identity_hash_version=doc.identity_hash_version,
        )
        existing = (
            await session.execute(select(Progress).where(Progress.document_pk == doc.pk))
        ).scalar_one_or_none()
        # Terminal-state invariant (coordinator §3.10 / Lock #6): a row may
        # be finished OR abandoned, never both. When the client sets one
        # terminal flag we explicitly clear the other so the row passes the
        # `ck_progress_abandoned_xor_finished` check constraint. "Finished
        # wins" if (defensively) the client sets both — finishing wipes any
        # abandoned mark. `percent` is preserved on the abandon transition
        # so abandoning at 60% remembers 60%.
        if item.finished_at is not None:
            abandoned_at = None
        else:
            abandoned_at = item.abandoned_at

        if existing is None:
            session.add(
                Progress(
                    document_pk=doc.pk,
                    locator=item.locator,
                    percent=item.percent,
                    client_updated_at=item.client_updated_at,
                    finished_at=item.finished_at,
                    abandoned_at=abandoned_at,
                )
            )
            results.append(
                ProgressPushResult(
                    document=persisted_identity,
                    status="accepted",
                    server_client_updated_at=item.client_updated_at,
                )
            )
            continue
        if item.client_updated_at > existing.client_updated_at:
            existing.locator = item.locator
            existing.percent = item.percent
            existing.client_updated_at = item.client_updated_at
            existing.finished_at = item.finished_at
            existing.abandoned_at = abandoned_at
            results.append(
                ProgressPushResult(
                    document=persisted_identity,
                    status="accepted",
                    server_client_updated_at=item.client_updated_at,
                )
            )
        else:
            results.append(
                ProgressPushResult(
                    document=persisted_identity,
                    status="stale",
                    server_client_updated_at=existing.client_updated_at,
                )
            )
    await session.commit()
    return ProgressPushResponse(results=results)


@router.get("/progress", response_model=ProgressPullResponse)
async def pull_progress(
    since: Annotated[str, Query()],
    user_id: Annotated[str, Depends(current_user_id)],
    session: Annotated[AsyncSession, Depends(get_session)],
) -> ProgressPullResponse:
    # Accept ISO-8601 with either +HH:MM or Z; httpx/browsers encode '+' as space in query strings
    since_dt = datetime.fromisoformat(since.replace(" ", "+"))
    rows = (
        await session.execute(
            select(Progress, Document)
            .join(Document, Document.pk == Progress.document_pk)
            .where(Document.user_id == user_id, Progress.client_updated_at > since_dt)
            .order_by(Progress.client_updated_at)
        )
    ).all()
    items: list[ProgressPullItem] = []
    for p, d in rows:
        # Defensive read (coordinator §3.10): the DB check constraint
        # forbids both terminal flags being set on new writes, but legacy
        # rows from before the constraint landed may still exist. Drop
        # `abandoned_at` when `finished_at` is also set (finished wins)
        # and emit a structured warning so we can spot the corrupt row.
        effective_abandoned_at = p.abandoned_at
        if p.finished_at is not None and p.abandoned_at is not None:
            logger.warning(
                "progress.terminal_state_both_set document_pk=%s finished_at=%s abandoned_at=%s",
                p.document_pk,
                p.finished_at,
                p.abandoned_at,
            )
            effective_abandoned_at = None
        items.append(
            ProgressPullItem(
                document=DocumentIdentity(
                    metadata_id=d.metadata_id,
                    content_hash=d.content_hash,
                    identity_hash_version=d.identity_hash_version,
                ),
                locator=p.locator,
                percent=p.percent,
                client_updated_at=p.client_updated_at,
                finished_at=p.finished_at,
                abandoned_at=effective_abandoned_at,
            )
        )
    server_time = datetime.now().astimezone()
    return ProgressPullResponse(items=items, server_time=server_time)
