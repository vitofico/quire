"""``/convert/v1``: turn a PDF into an EPUB from the apps.

The app creates a job (idempotency key, checksum and size), uploads the PDF,
polls the job, downloads the EPUB and acknowledges it, which deletes both files.
A separate worker does the converting (``core.convert``); nothing leaves the
server and no model is involved.

Mounted only while ``QUIRE_SERVER_CONVERT_ENABLED`` is on. Any login the server
accepts can use it; jobs are keyed by the account's storage user id, so another
account's job looks exactly like a missing one.
"""

from __future__ import annotations

import asyncio
import hashlib
import logging
import os
import time
from datetime import timedelta
from typing import Annotated, Literal
from uuid import UUID, uuid4

from fastapi import APIRouter, Depends, HTTPException, Request, Response, status
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from quire_server.api.no_store import NoStoreRoute
from quire_server.config import Settings, get_settings
from quire_server.core import convert
from quire_server.core.auth import current_user_id
from quire_server.db.models import ConvertJob
from quire_server.db.session import get_session

logger = logging.getLogger(__name__)

MEDIA_TYPE = "application/pdf"
# A PDF starts with its header within the first KiB and ends with %%EOF.
SIGNATURE_WINDOW = 1024
WRITE_BATCH = 1 << 20
# A body that sends nothing for this long is dropped (408).
BODY_IDLE_S = 60.0
FLUSH_S = 15.0

router = APIRouter(tags=["convert"], route_class=NoStoreRoute)

Owner = Annotated[str, Depends(current_user_id)]
Session = Annotated[AsyncSession, Depends(get_session)]
Config = Annotated[Settings, Depends(get_settings)]


def _folder(request: Request) -> convert.Folder:
    return request.app.state.convert_folder


Folder = Annotated[convert.Folder, Depends(_folder)]


class JobCreate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    idempotency_key: str = Field(pattern=r"^[A-Za-z0-9_-]{8,128}$")
    sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    bytes: int = Field(ge=1)
    mode: Literal["local"] = "local"


def _view(job: ConvertJob) -> dict:
    summary = job.summary or {}
    finished = job.state in ("ready", "review")
    return {
        "id": str(job.id),
        "state": job.state,
        "mode": job.mode,
        "attempt": job.attempt,
        "created_at": job.created_at.isoformat(),
        "updated_at": job.updated_at.isoformat(),
        "expires_at": job.expires_at.isoformat(),
        "source": {"sha256": job.source_sha256, "bytes": job.source_bytes},
        "engine_revision": job.engine_revision,
        "result": {
            "sha256": job.result_sha256,
            "bytes": job.result_bytes,
            "rendition_id": summary.get("rendition_id"),
            "issue_counts": summary.get("issue_counts", {}),
        }
        if finished
        else None,
        "review": {
            "reason": summary.get("detail"),
            "affected_pages": summary.get("affected_pages", []),
            "issues": summary.get("issues", []),
        }
        if job.state == "review"
        else None,
        "failure": {
            "code": job.failure_code,
            "retryable": bool(summary.get("retryable")),
            "detail": summary.get("detail"),
        }
        if job.state == "failed"
        else None,
    }


async def _job(session: AsyncSession, owner: str, job_id: UUID) -> ConvertJob:
    job = await session.scalar(
        select(ConvertJob).where(ConvertJob.id == job_id, ConvertJob.user_id == owner)
    )
    if job is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "job_not_found")
    return job


@router.get("/capabilities")
async def capabilities(folder: Folder, settings: Config, owner: Owner) -> dict:
    engine = await asyncio.to_thread(folder.engine)
    return {
        "available": engine is not None,
        "engine_revision": engine,
        "accepted_media_types": [MEDIA_TYPE],
        "max_bytes": settings.convert_max_bytes,
        "max_pages": settings.convert_max_pages,
        "time_limit_s": settings.convert_time_limit_s,
        "max_active_jobs": settings.convert_max_active_per_owner,
        "daily_limit": settings.convert_daily_limit,
        "retention_hours": int(convert.RETENTION.total_seconds() // 3600),
        "modes": [{"id": "local", "sends_content_off_server": False}],
    }


@router.post("/jobs", status_code=status.HTTP_201_CREATED)
async def create_job(
    body: JobCreate,
    response: Response,
    folder: Folder,
    settings: Config,
    owner: Owner,
    session: Session,
) -> dict:
    if body.bytes > settings.convert_max_bytes:
        raise HTTPException(status.HTTP_413_CONTENT_TOO_LARGE, "source_too_large")
    # Read before taking the lock: the heartbeat is the worker's file.
    engine = await asyncio.to_thread(folder.engine)
    async with session.begin():
        await convert.lock(session)
        existing = await session.scalar(
            select(ConvertJob).where(
                ConvertJob.user_id == owner, ConvertJob.idempotency_key == body.idempotency_key
            )
        )
        if existing is not None:
            if (existing.source_sha256, existing.source_bytes, existing.mode) != (
                body.sha256,
                body.bytes,
                body.mode,
            ):
                raise HTTPException(status.HTTP_409_CONFLICT, "idempotency_conflict")
            response.status_code = status.HTTP_200_OK
            return _view(existing)
        at = convert.now()
        active = await session.scalar(
            select(func.count())
            .select_from(ConvertJob)
            .where(ConvertJob.user_id == owner, ConvertJob.state.in_(convert.ACTIVE))
        )
        if active >= settings.convert_max_active_per_owner:
            raise HTTPException(429, "too_many_active_jobs", headers={"Retry-After": "60"})
        window = at - timedelta(days=1)
        recent = (
            await session.scalars(
                select(ConvertJob.created_at)
                .where(ConvertJob.user_id == owner, ConvertJob.created_at > window)
                .order_by(ConvertJob.created_at)
            )
        ).all()
        if len(recent) >= settings.convert_daily_limit:
            wait = recent[len(recent) - settings.convert_daily_limit] - window
            retry = str(max(int(wait.total_seconds()) + 1, 1))
            raise HTTPException(429, "daily_limit_reached", headers={"Retry-After": retry})
        if engine is None:
            raise HTTPException(503, "converter_unavailable", headers={"Retry-After": "60"})
        job = ConvertJob(
            id=uuid4(),
            user_id=owner,
            idempotency_key=body.idempotency_key,
            mode=body.mode,
            source_sha256=body.sha256,
            source_bytes=body.bytes,
            engine_revision=engine,
            state="created",
            attempt=1,
            created_at=at,
            updated_at=at,
            expires_at=at + convert.RETENTION,
        )
        session.add(job)
    logger.info("event=convert.created job=%s bytes=%s", job.id, job.source_bytes)
    return _view(job)


@router.put("/jobs/{job_id}/source", status_code=status.HTTP_202_ACCEPTED)
async def upload_source(
    job_id: UUID, request: Request, folder: Folder, settings: Config, owner: Owner, session: Session
) -> dict:
    """The PDF itself, as the raw request body. The request-size middleware holds
    this route to ``convert_max_bytes`` instead of the global limit, with or
    without a Content-Length. One upload per job at a time."""
    media_type = request.headers.get("content-type", "").split(";", 1)[0].strip().lower()
    if media_type != MEDIA_TYPE:
        raise HTTPException(status.HTTP_415_UNSUPPORTED_MEDIA_TYPE, "unsupported_media_type")
    async with session.begin():
        await convert.lock(session)
        job = await _job(session, owner, job_id)
        if job.state != "created":
            raise HTTPException(status.HTTP_409_CONFLICT, "not_accepting_source")
        expected, sha256 = job.source_bytes, job.source_sha256
        declared = request.headers.get("content-length")
        if declared is not None and declared.strip() != str(expected):
            raise HTTPException(422, "size_mismatch")
        part = await asyncio.to_thread(folder.begin_upload, job.id)
        if part is None:
            raise HTTPException(status.HTTP_409_CONFLICT, "upload_in_progress")
    # Nothing below needs the database until the upload is complete.
    await session.close()

    try:
        problem = await _receive(request, part, expected, sha256)
        if problem == "upload_timed_out":
            raise HTTPException(status.HTTP_408_REQUEST_TIMEOUT, problem)
        if problem is not None:
            logger.info("event=convert.upload_refused job=%s reason=%s", job_id, problem)
            raise HTTPException(422, problem)
        async with session.begin():
            await convert.lock(session)
            job = await _job(session, owner, job_id)
            if job.state != "created":
                raise HTTPException(status.HTTP_409_CONFLICT, "not_accepting_source")
            try:
                os.replace(part, folder.source(job.id))
            except FileNotFoundError:
                # This upload went quiet for so long that a newer one took over.
                raise HTTPException(status.HTTP_409_CONFLICT, "upload_in_progress") from None
            job.state = "queued"
            job.updated_at = convert.now()
            await asyncio.to_thread(folder.enqueue, job, settings)
    finally:
        part.unlink(missing_ok=True)
    logger.info("event=convert.queued job=%s attempt=%s", job.id, job.attempt)
    return _view(job)


async def _receive(request: Request, part, expected: int, sha256: str) -> str | None:
    """Stream the body to ``part``; the refusal code, or None when it is the PDF
    the job was created for. Writes at least every ``FLUSH_S`` while bytes
    arrive, so the part file shows the upload is alive (``Folder.begin_upload``)."""
    digest = hashlib.sha256()
    received = 0
    head = b""
    tail = b""
    pending: list[bytes] = []
    buffered = 0
    flushed = time.monotonic()
    body = aiter(request.stream())
    fh = await asyncio.to_thread(open, part, "wb")
    try:
        while True:
            try:
                chunk = await asyncio.wait_for(anext(body), BODY_IDLE_S)
            except StopAsyncIteration:
                break
            except TimeoutError:
                return "upload_timed_out"
            if not chunk:
                continue
            received += len(chunk)
            if received > expected:
                return "size_mismatch"
            digest.update(chunk)
            pending.append(chunk)
            buffered += len(chunk)
            if len(head) < SIGNATURE_WINDOW:
                head = (head + chunk)[:SIGNATURE_WINDOW]
            tail = (tail + chunk)[-SIGNATURE_WINDOW:]
            if buffered >= WRITE_BATCH or time.monotonic() - flushed >= FLUSH_S:
                await asyncio.to_thread(_write, fh, pending)
                pending.clear()
                buffered = 0
                flushed = time.monotonic()
        await asyncio.to_thread(_write, fh, pending)
    finally:
        await asyncio.to_thread(fh.close)
    if received != expected:
        return "size_mismatch"
    if digest.hexdigest() != sha256:
        return "checksum_mismatch"
    if b"%PDF-" not in head or b"%%EOF" not in tail:
        return "not_a_pdf"
    return None


def _write(fh, chunks: list[bytes]) -> None:
    fh.write(b"".join(chunks))
    fh.flush()


@router.get("/jobs")
async def list_jobs(owner: Owner, session: Session) -> dict:
    jobs = await session.scalars(
        select(ConvertJob)
        .where(ConvertJob.user_id == owner)
        .order_by(ConvertJob.created_at.desc(), ConvertJob.id)
    )
    return {"jobs": [_view(job) for job in jobs]}


@router.get("/jobs/{job_id}")
async def get_job(job_id: UUID, owner: Owner, session: Session) -> dict:
    return _view(await _job(session, owner, job_id))


@router.get("/jobs/{job_id}/epub")
async def download(job_id: UUID, folder: Folder, owner: Owner, session: Session) -> Response:
    job = await _job(session, owner, job_id)
    if job.state in ("acknowledged", "expired"):
        raise HTTPException(status.HTTP_410_GONE, "result_gone")
    if job.state not in ("ready", "review"):
        raise HTTPException(status.HTTP_409_CONFLICT, "not_ready")
    # Opened before the session closes: a later acknowledge or expiry deletes the
    # name, not the bytes this response is sending.
    try:
        fh = await asyncio.to_thread(open, folder.result(job.id), "rb")
    except FileNotFoundError:
        raise HTTPException(status.HTTP_410_GONE, "result_gone") from None
    await session.close()

    def chunks():
        with fh:
            while chunk := fh.read(1 << 16):
                yield chunk

    return StreamingResponse(
        chunks(),
        media_type="application/epub+zip",
        headers={
            "Content-Length": str(job.result_bytes),
            "ETag": f'"{job.result_sha256}"',
            "Content-Disposition": f'attachment; filename="{job.id}.epub"',
            "X-Content-Type-Options": "nosniff",
        },
    )


async def _finish(
    job_id: UUID,
    owner: Owner,
    session: AsyncSession,
    folder: convert.Folder,
    *,
    allowed: tuple[str, ...],
    settled: tuple[str, ...],
    target: str,
    refusal: str,
) -> dict:
    async with session.begin():
        await convert.lock(session)
        job = await _job(session, owner, job_id)
        if job.state in allowed:
            job.state = target
            job.updated_at = convert.now()
        elif job.state not in settled:
            raise HTTPException(status.HTTP_409_CONFLICT, refusal)
    await asyncio.to_thread(folder.forget, job.id)
    logger.info("event=convert.state job=%s attempt=%s state=%s", job.id, job.attempt, job.state)
    return _view(job)


@router.post("/jobs/{job_id}/cancel")
async def cancel(job_id: UUID, folder: Folder, owner: Owner, session: Session) -> dict:
    return await _finish(
        job_id,
        owner,
        session,
        folder,
        allowed=convert.ACTIVE,
        settled=("cancelled",),
        target="cancelled",
        refusal="not_cancellable",
    )


@router.post("/jobs/{job_id}/acknowledge")
async def acknowledge(job_id: UUID, folder: Folder, owner: Owner, session: Session) -> dict:
    return await _finish(
        job_id,
        owner,
        session,
        folder,
        allowed=("ready", "review", "failed"),
        settled=("acknowledged", "cancelled", "expired"),
        target="acknowledged",
        refusal="not_finished",
    )
