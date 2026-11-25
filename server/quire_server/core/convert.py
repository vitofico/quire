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

Everything under ``running/``, ``done/`` and ``workers/`` is written by the
worker and treated as hostile: opened without following links and without
blocking, accepted only as regular files of a bounded size, and the EPUB is
copied into a file the server creates (never moved), checked as a zip on the
way. A result that does not check out fails its job ``invalid_result``.

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
import math
import os
import re
import shutil
import stat
import time
import zipfile
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any
from uuid import UUID, uuid4

from sqlalchemy import delete, select, text
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from quire_server.config import Settings
from quire_server.db.models import ConvertJob

logger = logging.getLogger(__name__)

FORMAT = 1
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
# Faults of the service, not of the PDF: another job may well succeed.
RETRYABLE = frozenset({"worker_error", "worker_lost", "invalid_result"})
SEVERITIES = ("critical", "major", "minor", "info")
RETENTION = timedelta(hours=24)
# A job whose source never arrives stops counting against the owner after this.
UPLOAD_WINDOW = timedelta(hours=1)
# Rows outlive their files by a day, so the daily limit still counts them.
ROW_RETENTION = timedelta(hours=48)
LEASE_GRACE = timedelta(minutes=5)
MAX_ATTEMPTS = 2
WORKER_FRESH_S = 120
# A claim left behind is cleared once it is this old (seconds).
ORPHAN_AGE_S = 60
# An upload writes its part file at least every minute; one untouched for
# longer than this belongs to no live request.
UPLOAD_STALE_S = 120
# A worker's clock may run a little ahead; a time further ahead is not believed.
CLOCK_SKEW_S = 5
MAX_RESULT_JSON = 1 << 20
MAX_HEARTBEAT = 4096
MAX_ISSUES = 100
MAX_PAGES_LISTED = 2000
# The EPUB may be at most this many times the upload ceiling, and unpack to at
# most this many times its own ceiling.
RESULT_RATIO = 2
UNPACKED_RATIO = 5
MAX_EPUB_ENTRIES = 10_000
EPUB_MEDIA_TYPE = b"application/epub+zip"
SWEEP_S = 5.0

_SHA256 = re.compile(r"[0-9a-f]{64}")
_READ = os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK | os.O_CLOEXEC
_DIR = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC


class Untrusted(ValueError):
    """Something the worker wrote that the protocol does not allow."""


def now() -> datetime:
    return datetime.now(UTC)


def result_limit(settings: Settings) -> int:
    return RESULT_RATIO * settings.convert_max_bytes


_KEY = "hashtextextended('quire:convert', 0)"


async def lock(session: AsyncSession) -> None:
    """Hold the conversion lock until this transaction ends."""
    await session.execute(text(f"SELECT pg_advisory_xact_lock({_KEY})"))


async def try_lock(session: AsyncSession) -> bool:
    return bool(await session.scalar(text(f"SELECT pg_try_advisory_xact_lock({_KEY})")))


def _discard(path: Path) -> None:
    """Delete a file, link or folder, never following a link; quietly."""
    try:
        if stat.S_ISDIR(path.lstat().st_mode):
            shutil.rmtree(path)
        else:
            path.unlink()
    except OSError:
        pass


def _age_s(path: Path) -> float:
    """Seconds since ``path`` changed: infinite when its time is in the future,
    so a worker cannot keep an entry fresh by dating it ahead."""
    try:
        age = time.time() - path.lstat().st_mtime
    except OSError:
        return 0.0
    return math.inf if age < -CLOCK_SKEW_S else max(age, 0.0)


def _need(condition: bool) -> None:
    if not condition:
        raise Untrusted


def _open_file(name: str | Path, limit: int, dir_fd: int | None = None) -> tuple[int, int]:
    """Open a regular file of at most ``limit`` bytes: not a link, a FIFO, a
    device or a folder. Returns the descriptor and the size."""
    fd = os.open(name, _READ, dir_fd=dir_fd)
    try:
        info = os.fstat(fd)
        _need(stat.S_ISREG(info.st_mode) and info.st_size <= limit)
    except BaseException:
        os.close(fd)
        raise
    return fd, info.st_size


def _read_file(name: str | Path, limit: int, dir_fd: int | None = None) -> bytes:
    fd, _ = _open_file(name, limit, dir_fd)
    with os.fdopen(fd, "rb") as fh:
        data = fh.read(limit + 1)
    _need(len(data) <= limit)
    return data


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
        folders it writes are open to every user, sticky so nobody removes
        another's entries; the folder itself belongs to the server and the
        worker alone (see docs/configuration.md)."""
        self.root.mkdir(parents=True, exist_ok=True)
        for path, mode in (
            (self.incoming, 0o700),
            (self.store, 0o700),
            (self.queue, 0o755),
            (self.running, 0o1777),
            (self.done, 0o1777),
            (self.workers, 0o1777),
        ):
            path.mkdir(exist_ok=True)
            path.chmod(mode)

    def source(self, job_id: UUID) -> Path:
        return self.store / f"{job_id.hex}.pdf"

    def result(self, job_id: UUID) -> Path:
        return self.store / f"{job_id.hex}.epub"

    def begin_upload(self, job_id: UUID) -> Path | None:
        """A new, empty part file for the job's upload, or None while another
        upload of the same job is still arriving. Called under the lock."""
        prefix = f"{job_id.hex}."
        for entry in _entries(self.incoming):
            if entry.name.startswith(prefix):
                if _age_s(entry) <= UPLOAD_STALE_S:
                    return None
                _discard(entry)
        part = self.incoming / f"{prefix}{uuid4().hex}.part"
        os.close(os.open(part, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_CLOEXEC, 0o600))
        return part

    def enqueue(self, job: ConvertJob, settings: Settings) -> None:
        """Hand ``store/<id>.pdf`` to the workers as the job's current attempt."""
        name = f"{job.id.hex}-{job.attempt}"
        staging = self.queue / f".{name}"
        _discard(staging)
        staging.mkdir()
        try:
            os.link(self.source(job.id), staging / "source.pdf")
        except OSError:
            shutil.copyfile(self.source(job.id), staging / "source.pdf")
        (staging / "source.pdf").chmod(0o644)
        spec = {
            "format": FORMAT,
            "sha256": job.source_sha256,
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
            _discard(entry)

    def forget(self, job_id: UUID) -> None:
        """Everything the job left: queue entries, results, stored files."""
        self.withdraw(job_id)
        for entry in _named(self.done, job_id):
            _discard(entry)
        _discard(self.source(job_id))
        _discard(self.result(job_id))

    def engine(self) -> str | None:
        """The engine revision of the worker seen most recently, or None when no
        worker has checked in for two minutes."""
        fresh = sorted(
            (age, beat)
            for beat in _entries(self.workers)
            if beat.name.endswith(".json") and (age := _age_s(beat)) < WORKER_FRESH_S
        )
        for _, beat in fresh:
            try:
                engine = json.loads(_read_file(beat, MAX_HEARTBEAT)).get("engine")
            except (OSError, ValueError, AttributeError, RecursionError):
                # Not a regular file (a FIFO or a folder among them) or not JSON.
                continue
            return engine[:200] if isinstance(engine, str) and engine else "unknown"
        return None

    def claimed_at(self, job: ConvertJob) -> datetime | None:
        try:
            seen = (self.running / f"{job.id.hex}-{job.attempt}").lstat().st_mtime
        except OSError:
            return None
        return datetime.fromtimestamp(min(seen, time.time()), UTC)

    def queued(self, job: ConvertJob) -> bool:
        return (self.queue / f"{job.id.hex}-{job.attempt}" / "job.json").is_file()

    def published(self, job: ConvertJob) -> Path | None:
        path = self.done / f"{job.id.hex}-{job.attempt}"
        try:
            path.lstat()
        except OSError:
            return None
        return path

    def accept(self, job: ConvertJob, published: Path, limit: int) -> dict[str, Any]:
        """Column updates for a published attempt. The EPUB is copied into the
        store and checked against what the worker reported; anything that does
        not check out fails the job ``invalid_result``."""
        try:
            return self._accept(job, published, limit)
        except Exception as exc:  # noqa: BLE001 - whatever the worker wrote, the job fails
            logger.info("event=convert.result_refused job=%s error=%s", job.id, type(exc).__name__)
            return _failed("invalid_result")

    def _accept(self, job: ConvertJob, published: Path, limit: int) -> dict[str, Any]:
        folder = os.open(published, _DIR)
        try:
            report = _report(json.loads(_read_file("result.json", MAX_RESULT_JSON, folder)), job)
            update = {"engine_revision": report["engine"]} if report["engine"] else {}
            if report["outcome"] == "failed":
                return update | _failed(report["code"], report["detail"], report["retryable"])
            epub = report["epub"]
            _need(epub["bytes"] <= limit)
            self._take_epub(job, folder, epub, limit)
        finally:
            os.close(folder)
        summary = {
            key: report[key] for key in ("detail", "issue_counts", "affected_pages", "issues")
        }
        return update | {
            "state": report["outcome"],
            "result_sha256": epub["sha256"],
            "result_bytes": epub["bytes"],
            "summary": summary | {"rendition_id": epub["rendition_id"]},
        }

    def _take_epub(self, job: ConvertJob, folder: int, epub: dict[str, Any], limit: int) -> None:
        """Copy ``book.epub`` into a file of the server's own, hashing on the way,
        then put it in place. The worker's file never enters the store."""
        source, size = _open_file("book.epub", limit, folder)
        copy = self.store / f".{job.id.hex}.{uuid4().hex}"
        try:
            with os.fdopen(source, "rb") as src:
                _need(size == epub["bytes"])
                target = os.open(copy, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_CLOEXEC, 0o600)
                digest, copied = hashlib.sha256(), 0
                with os.fdopen(target, "wb") as dst:
                    while chunk := src.read(1 << 20):
                        copied += len(chunk)
                        _need(copied <= epub["bytes"])
                        digest.update(chunk)
                        dst.write(chunk)
            _need(copied == epub["bytes"] and digest.hexdigest() == epub["sha256"])
            _check_epub(copy, UNPACKED_RATIO * limit)
            os.replace(copy, self.result(job.id))
        except BaseException:
            _discard(copy)
            raise

    def clear_orphans(self, live: set[str], kept: set[str]) -> None:
        """Remove what no row is waiting for. ``live`` holds the ``<id>-<attempt>``
        names of queued and running rows, ``kept`` the store files rows still own."""

        def unwanted(entry: Path) -> bool:
            if entry.name.startswith("."):
                return _age_s(entry) > ORPHAN_AGE_S
            return entry.name not in live

        rules = (
            (self.queue, unwanted),
            (self.done, unwanted),
            # A withdrawn worker gets a minute to stop before its claim goes.
            (self.running, lambda e: e.name not in live and _age_s(e) > ORPHAN_AGE_S),
            (self.store, lambda e: e.name not in kept),
            (self.incoming, lambda e: _age_s(e) > UPLOAD_STALE_S),
            (self.workers, lambda e: _age_s(e) > 24 * 3600),
        )
        for folder, gone in rules:
            for entry in _entries(folder):
                if gone(entry):
                    _discard(entry)


def _entries(folder: Path) -> list[Path]:
    try:
        return list(folder.iterdir())
    except OSError:
        return []


def _named(folder: Path, job_id: UUID) -> list[Path]:
    prefix = job_id.hex + "-"
    return [entry for entry in _entries(folder) if entry.name.startswith(prefix)]


def _check_epub(path: Path, unpacked_limit: int) -> None:
    """A cheap look at the zip: a readable central directory, ``mimetype`` first
    with the EPUB media type, no name that escapes the book, bounded entries."""
    with zipfile.ZipFile(path) as book:
        entries = book.infolist()
        _need(0 < len(entries) <= MAX_EPUB_ENTRIES)
        first = entries[0]
        _need(first.filename == "mimetype" and first.file_size == len(EPUB_MEDIA_TYPE))
        _need(book.read(first) == EPUB_MEDIA_TYPE)
        total = 0
        for entry in entries:
            parts = entry.filename.replace("\\", "/").split("/")
            _need(parts[0] != "" and ".." not in parts and ":" not in parts[0])
            total += entry.file_size
        _need(total <= unpacked_limit)


def _int(value: Any, low: int = 0, high: int = 10**9) -> int:
    _need(type(value) is int and low <= value <= high)
    return value


def _str(value: Any, limit: int = 500, *, optional: bool = True) -> str | None:
    if value is None and optional:
        return None
    _need(isinstance(value, str))
    return value[:limit]


def _choice(value: Any, choices: tuple[str, ...]) -> str:
    _need(value in choices)
    return value


def _list(value: Any) -> list:
    if value is None:
        return []
    _need(isinstance(value, list))
    return value


def _report(raw: Any, job: ConvertJob) -> dict[str, Any]:
    """The worker's ``result.json``, every field checked for its type."""
    _need(isinstance(raw, dict))
    _need(_int(raw.get("format")) == FORMAT)
    _need(raw.get("job") == job.id.hex and _int(raw.get("attempt")) == job.attempt)
    outcome = _choice(raw.get("outcome"), ("ready", "review", "failed"))
    report: dict[str, Any] = {
        "outcome": outcome,
        "engine": _str(raw.get("engine"), 200) or None,
        "detail": _str(raw.get("detail")),
    }
    if outcome == "failed":
        code = _str(raw.get("code"), 40, optional=False)
        retryable = raw.get("retryable", False)
        _need(isinstance(retryable, bool))
        return report | {
            "code": code if code in FAILURE_CODES else "worker_error",
            # Only the converter knows whether a stage failure may pass next time.
            "retryable": retryable and code == "conversion_failed",
        }
    epub = raw.get("epub")
    _need(isinstance(epub, dict))
    sha256 = epub.get("sha256")
    _need(isinstance(sha256, str) and _SHA256.fullmatch(sha256) is not None)
    counts = raw.get("issue_counts") or {}
    _need(isinstance(counts, dict) and all(key in SEVERITIES for key in counts))
    pages = _list(raw.get("affected_pages"))
    issues = _list(raw.get("issues"))
    for entry in (*pages, *issues):
        _need(isinstance(entry, dict))
    return report | {
        "epub": {
            "sha256": sha256,
            "bytes": _int(epub.get("bytes"), 1, 2**62),
            "rendition_id": _str(epub.get("rendition_id")),
        },
        "issue_counts": {key: _int(value, 0, 10**6) for key, value in counts.items()},
        "affected_pages": [
            {"page": _int(p.get("page"), 1, 10**6), "label": _str(p.get("label"), 40)}
            for p in pages
        ][:MAX_PAGES_LISTED],
        "issues": [
            {
                "code": _str(i.get("code"), 80, optional=False),
                "severity": _choice(i.get("severity"), SEVERITIES),
                "page": None if i.get("page") is None else _int(i.get("page"), 1, 10**6),
                "page_label": _str(i.get("page_label"), 40),
                "message": _str(i.get("message")),
            }
            for i in issues
        ][:MAX_ISSUES],
    }


def _failed(code: str, detail: str | None = None, retryable: bool = False) -> dict[str, Any]:
    return {
        "state": "failed",
        "failure_code": code,
        "result_sha256": None,
        "result_bytes": None,
        "summary": {"detail": detail, "retryable": retryable or code in RETRYABLE},
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
            await asyncio.to_thread(_reconcile_safely, folder, settings, job, at)
        live = {f"{j.id.hex}-{j.attempt}" for j in jobs if j.state in ("queued", "running")}
        kept = {f"{j.id.hex}.pdf" for j in jobs if j.state in ("queued", "running")} | {
            f"{j.id.hex}.epub" for j in jobs if j.state in ("ready", "review")
        }
        await asyncio.to_thread(folder.clear_orphans, live, kept)
    return True


def _reconcile_safely(folder: Folder, settings: Settings, job: ConvertJob, at: datetime) -> None:
    """One job's pass; a job that cannot be reconciled fails alone, with its
    files gone, so the next job and the next pass still run."""
    try:
        _reconcile(folder, settings, job, at)
    except Exception as exc:  # noqa: BLE001 - one bad job must not stop the sweep
        logger.warning("event=convert.reconcile_failed job=%s error=%s", job.id, type(exc).__name__)
        _set(job, _failed("invalid_result"), at)
        folder.forget(job.id)


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
        _discard(folder.source(job.id))


def _advance(folder: Folder, settings: Settings, job: ConvertJob, at: datetime) -> None:
    published = folder.published(job)
    if published is not None:
        _set(job, folder.accept(job, published, result_limit(settings)), at)
        _discard(published)
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
