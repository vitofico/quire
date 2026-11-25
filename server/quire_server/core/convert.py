"""PDF to EPUB conversion: the folder Quire Server shares with the converter worker.

The worker (``quire-convert worker`` from ``converter/``) has no database, no
account credentials and no network. Everything it learns about a job is in the
job folder, and the server owns every job state in ``convert_jobs``::

    incoming/                 server only: uploads being received
    store/<id>.pdf            server only: the source while the job runs
    store/<id>.epub           server only: the verified result
    queue/<id>-<attempt>/     server: source.pdf + job.json, renamed in whole
    running/<id>-<attempt>/   worker: its claim (mkdir, so one worker wins)
    done/<id>-<attempt>/      worker: result.json (+ book.epub), renamed in whole
    workers/<worker>.json     worker: heartbeat with the engine revision

The attempt number in a folder name is the token. ``sweep`` accepts
``done/<id>-<n>`` only while the row waits for attempt ``n``, so a worker that
died, timed out or lost its job cannot publish over a newer attempt. Deleting
the queue entry tells the worker to stop.

Every change of job state (create, enqueue, cancel, acknowledge, the sweep)
holds one Postgres advisory lock, so the sweep never sees a half-made job and
two server processes never both reconcile.

Logs name a job by id and carry states, codes and sizes, never a file name or
any text from a book.
"""

from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import os
import re
import shutil
import time
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any
from uuid import UUID

from sqlalchemy import delete, select, text
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from quire_server.config import Settings
from quire_server.db.models import ConvertJob

logger = logging.getLogger(__name__)

FORMAT = 1
MODES = ("local",)
ACTIVE = ("created", "queued", "running")
# States that still keep a file or a slot, so they expire.
HOLDING = (*ACTIVE, "ready", "review", "failed")
FAILURE_CODES = frozenset(
    {
        "encrypted",
        "too_many_pages",
        "unsupported",
        "unreadable_pdf",
        "conversion_failed",
        "timed_out",
        "worker_error",
        "worker_lost",
        "invalid_result",
    }
)
RETENTION = timedelta(hours=24)
# A job whose source never arrives stops counting against the owner after this.
UPLOAD_WINDOW = timedelta(hours=1)
# Rows outlive their files by a day, so the daily limit still counts them.
ROW_RETENTION = timedelta(hours=48)
LEASE_GRACE = timedelta(minutes=5)
MAX_ATTEMPTS = 2
WORKER_FRESH_S = 120
# A claim or upload left behind is cleared once it is this old (seconds).
ORPHAN_AGE_S = 60
UPLOAD_IDLE_S = 3600
MAX_RESULT_JSON = 1 << 20
MAX_ISSUES = 100
SWEEP_S = 5.0

_NAME = re.compile(r"([0-9a-f]{32})-([1-9][0-9]{0,2})")
_STORED = re.compile(r"([0-9a-f]{32})\.(pdf|epub)")
_SHA256 = re.compile(r"[0-9a-f]{64}")


def now() -> datetime:
    return datetime.now(UTC)


_KEY = "hashtextextended('quire:convert', 0)"


async def lock(session: AsyncSession) -> None:
    """Hold the conversion lock until this transaction ends."""
    await session.execute(text(f"SELECT pg_advisory_xact_lock({_KEY})"))


async def try_lock(session: AsyncSession) -> bool:
    return bool(await session.scalar(text(f"SELECT pg_try_advisory_xact_lock({_KEY})")))


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as fh:
        while chunk := fh.read(1 << 20):
            digest.update(chunk)
    return digest.hexdigest()


def _rmtree(path: Path) -> None:
    shutil.rmtree(path, ignore_errors=True)


def _age_s(path: Path) -> float:
    try:
        return time.time() - path.stat().st_mtime
    except OSError:
        return 0.0


class Folder:
    """The shared job folder."""

    def __init__(self, root: str | Path) -> None:
        self.root = Path(root)
        self.incoming = self.root / "incoming"
        self.store = self.root / "store"
        self.queue = self.root / "queue"
        self.running = self.root / "running"
        self.done = self.root / "done"
        self.workers = self.root / "workers"

    def prepare(self) -> None:
        """Create the layout. The worker may run as another user, so the three
        folders it writes are open to every user; the folder itself belongs to
        the server and the worker alone (see docs/configuration.md)."""
        self.root.mkdir(parents=True, exist_ok=True)
        for path, mode in (
            (self.incoming, 0o700),
            (self.store, 0o700),
            (self.queue, 0o755),
            (self.running, 0o777),
            (self.done, 0o777),
            (self.workers, 0o777),
        ):
            path.mkdir(exist_ok=True)
            path.chmod(mode)

    def source(self, job_id: UUID) -> Path:
        return self.store / f"{job_id.hex}.pdf"

    def result(self, job_id: UUID) -> Path:
        return self.store / f"{job_id.hex}.epub"

    def enqueue(self, job: ConvertJob, settings: Settings) -> None:
        """Hand ``store/<id>.pdf`` to the workers as the job's current attempt."""
        name = f"{job.id.hex}-{job.attempt}"
        staging = self.queue / f".{name}"
        _rmtree(staging)
        staging.mkdir()
        try:
            os.link(self.source(job.id), staging / "source.pdf")
        except OSError:
            shutil.copyfile(self.source(job.id), staging / "source.pdf")
        (staging / "source.pdf").chmod(0o644)
        spec = {
            "format": FORMAT,
            "job": job.id.hex,
            "attempt": job.attempt,
            "mode": job.mode,
            "sha256": job.source_sha256,
            "bytes": job.source_bytes,
            "max_pages": settings.convert_max_pages,
            "time_limit_s": settings.convert_time_limit_s,
        }
        (staging / "job.json").write_text(json.dumps(spec, sort_keys=True))
        (staging / "job.json").chmod(0o644)
        staging.chmod(0o755)
        os.rename(staging, self.queue / name)

    def withdraw(self, job_id: UUID) -> None:
        """Take every attempt of the job off the queue; a worker on it stops."""
        for entry in _named(self.queue, job_id):
            _rmtree(entry)

    def forget(self, job_id: UUID) -> None:
        """Everything the job left: queue entries, results, stored files."""
        self.withdraw(job_id)
        for entry in _named(self.done, job_id):
            _rmtree(entry)
        self.source(job_id).unlink(missing_ok=True)
        self.result(job_id).unlink(missing_ok=True)

    def engine(self) -> str | None:
        """The engine revision of the worker seen most recently, or None when no
        worker has checked in for two minutes."""
        newest: tuple[float, Path] | None = None
        try:
            beats = list(self.workers.glob("*.json"))
        except OSError:
            return None
        for beat in beats:
            try:
                seen = beat.stat().st_mtime
            except OSError:
                continue
            if time.time() - seen < WORKER_FRESH_S and (newest is None or seen > newest[0]):
                newest = (seen, beat)
        if newest is None:
            return None
        try:
            with open(newest[1], "rb") as fh:
                engine = json.loads(fh.read(4096)).get("engine")
        except (OSError, ValueError, AttributeError):
            engine = None
        return engine[:200] if isinstance(engine, str) and engine else "unknown"

    def claimed_at(self, job: ConvertJob) -> datetime | None:
        try:
            seen = (self.running / f"{job.id.hex}-{job.attempt}").stat().st_mtime
        except OSError:
            return None
        return datetime.fromtimestamp(seen, UTC)

    def queued(self, job: ConvertJob) -> bool:
        return (self.queue / f"{job.id.hex}-{job.attempt}" / "job.json").is_file()

    def published(self, job: ConvertJob) -> Path | None:
        path = self.done / f"{job.id.hex}-{job.attempt}"
        return path if path.is_dir() else None

    def accept(self, job: ConvertJob, published: Path) -> dict[str, Any]:
        """Column updates for a published attempt: the EPUB moves into the store
        and is checked against what the worker reported."""
        try:
            with open(published / "result.json", "rb") as fh:
                raw = fh.read(MAX_RESULT_JSON + 1)
            result = json.loads(raw) if len(raw) <= MAX_RESULT_JSON else None
        except (OSError, ValueError):
            result = None
        if (
            not isinstance(result, dict)
            or result.get("format") != FORMAT
            or result.get("job") != job.id.hex
            or result.get("attempt") != job.attempt
        ):
            return _failed("invalid_result")
        engine = result.get("engine")
        update: dict[str, Any] = {}
        if isinstance(engine, str) and engine:
            update["engine_revision"] = engine[:200]
        outcome = result.get("outcome")
        if outcome == "failed":
            code = result.get("code")
            return update | _failed(
                code if code in FAILURE_CODES else "worker_error",
                _text(result.get("detail")),
                result.get("retryable") is True,
            )
        epub = result.get("epub")
        if (
            outcome not in ("ready", "review")
            or not isinstance(epub, dict)
            or not isinstance(epub.get("sha256"), str)
            or not _SHA256.fullmatch(epub["sha256"])
            or not isinstance(epub.get("bytes"), int)
        ):
            return _failed("invalid_result")
        target = self.result(job.id)
        try:
            os.replace(published / "book.epub", target)
            target.chmod(0o600)
            intact = (
                target.stat().st_size == epub["bytes"] and sha256_file(target) == epub["sha256"]
            )
        except OSError:
            intact = False
        if not intact:
            target.unlink(missing_ok=True)
            return _failed("invalid_result")
        summary = {
            "rendition_id": _text(epub.get("rendition_id")),
            "detail": _text(result.get("detail")),
            "issue_counts": {
                str(k)[:40]: v
                for k, v in (result.get("issue_counts") or {}).items()
                if isinstance(v, int)
            }
            if isinstance(result.get("issue_counts"), dict)
            else {},
            "affected_pages": [
                {"page": p["page"], "label": _text(p.get("label"), 40)}
                for p in result.get("affected_pages") or []
                if isinstance(p, dict) and isinstance(p.get("page"), int)
            ][:2000],
            "issues": [
                {
                    "code": _text(i.get("code"), 80),
                    "severity": _text(i.get("severity"), 20),
                    "page": i.get("page") if isinstance(i.get("page"), int) else None,
                    "page_label": _text(i.get("page_label"), 40),
                    "message": _text(i.get("message")),
                }
                for i in result.get("issues") or []
                if isinstance(i, dict)
            ][:MAX_ISSUES],
        }
        return update | {
            "state": outcome,
            "result_sha256": epub["sha256"],
            "result_bytes": epub["bytes"],
            "summary": summary,
        }

    def clear_orphans(self, live: set[str], kept: set[str]) -> None:
        """Remove what no row is waiting for. ``live`` holds the ``<id>-<attempt>``
        names of queued and running rows, ``kept`` the store files rows still own."""
        for folder in (self.queue, self.done):
            for entry in _entries(folder):
                if entry.name.startswith("."):
                    if _age_s(entry) > ORPHAN_AGE_S:
                        _rmtree(entry)
                elif entry.name not in live:
                    _rmtree(entry)
        # A withdrawn worker gets a minute to stop before its claim goes.
        for entry in _entries(self.running):
            if entry.name not in live and _age_s(entry) > ORPHAN_AGE_S:
                _rmtree(entry)
        for entry in _entries(self.store):
            if entry.name not in kept:
                entry.unlink(missing_ok=True)
        for entry in _entries(self.incoming):
            if _age_s(entry) > UPLOAD_IDLE_S:
                entry.unlink(missing_ok=True)
        for entry in _entries(self.workers):
            if _age_s(entry) > 24 * 3600:
                entry.unlink(missing_ok=True)


def _entries(folder: Path) -> list[Path]:
    try:
        return list(folder.iterdir())
    except OSError:
        return []


def _named(folder: Path, job_id: UUID) -> list[Path]:
    prefix = job_id.hex + "-"
    return [entry for entry in _entries(folder) if entry.name.startswith(prefix)]


def _text(value: Any, limit: int = 500) -> str | None:
    return value[:limit] if isinstance(value, str) else None


def _failed(code: str, detail: str | None = None, retryable: bool = False) -> dict[str, Any]:
    return {
        "state": "failed",
        "failure_code": code,
        "summary": {"detail": detail, "retryable": retryable},
    }


def _set(job: ConvertJob, update: dict[str, Any], at: datetime) -> None:
    for key, value in update.items():
        setattr(job, key, value)
    job.updated_at = at


async def sweep(
    factory: async_sessionmaker[AsyncSession], folder: Folder, settings: Settings
) -> bool:
    """One reconcile pass. Returns False when another process holds the lock."""
    async with factory() as session, session.begin():
        if not await try_lock(session):
            return False
        at = now()
        await session.execute(delete(ConvertJob).where(ConvertJob.created_at < at - ROW_RETENTION))
        jobs = (
            await session.scalars(select(ConvertJob).where(ConvertJob.state.in_(HOLDING)))
        ).all()
        for job in jobs:
            await asyncio.to_thread(_reconcile, folder, settings, job, at)
        live = {f"{j.id.hex}-{j.attempt}" for j in jobs if j.state in ("queued", "running")}
        kept = {f"{j.id.hex}.pdf" for j in jobs if j.state in ("queued", "running")} | {
            f"{j.id.hex}.epub" for j in jobs if j.state in ("ready", "review")
        }
        await asyncio.to_thread(folder.clear_orphans, live, kept)
    return True


def _reconcile(folder: Folder, settings: Settings, job: ConvertJob, at: datetime) -> None:
    before = job.state
    if job.expires_at <= at or (job.state == "created" and job.created_at + UPLOAD_WINDOW <= at):
        folder.forget(job.id)
        _set(job, {"state": "expired"}, at)
    elif job.state in ("queued", "running"):
        _advance(folder, settings, job, at)
    if job.state != before:
        logger.info(
            "event=convert.state job=%s attempt=%s state=%s code=%s",
            job.id,
            job.attempt,
            job.state,
            job.failure_code,
        )
    if job.state not in ("queued", "running"):
        folder.withdraw(job.id)
        folder.source(job.id).unlink(missing_ok=True)


def _advance(folder: Folder, settings: Settings, job: ConvertJob, at: datetime) -> None:
    published = folder.published(job)
    if published is not None:
        _set(job, folder.accept(job, published), at)
        _rmtree(published)
        return
    claimed = folder.claimed_at(job)
    if claimed is not None:
        if job.state == "queued":
            _set(job, {"state": "running", "started_at": claimed}, at)
        deadline = job.started_at + timedelta(seconds=settings.convert_time_limit_s) + LEASE_GRACE
        if deadline > at:
            return
        # The worker on this attempt is gone or stuck: try once more, then give up.
        if job.attempt >= MAX_ATTEMPTS:
            _set(job, _failed("worker_lost"), at)
            return
        folder.withdraw(job.id)
        _set(job, {"state": "queued", "attempt": job.attempt + 1, "started_at": None}, at)
    elif job.state == "running":
        # A worker that stops releases its claim: the attempt waits in the queue again.
        _set(job, {"state": "queued", "started_at": None}, at)
    if not folder.queued(job):
        if not folder.source(job.id).is_file():
            _set(job, _failed("worker_lost"), at)
            return
        folder.enqueue(job, settings)


async def run_sweeps(factory, folder: Folder, settings: Settings) -> None:
    """The server's background loop; one failed pass is logged and the next one runs."""
    while True:
        try:
            await sweep(factory, folder, settings)
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001 - the loop must outlive one bad pass
            logger.warning("event=convert.sweep_failed error=%s", type(exc).__name__)
        await asyncio.sleep(SWEEP_S)
