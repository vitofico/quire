"""``quire-convert worker``: run the conversions Quire Server queues in a shared folder.

The server and the worker share one directory and nothing else: no database, no
account credentials, no network. The server owns every job state; the worker only
claims, converts and publishes.

Layout (``<root>`` is ``--jobs``)::

    queue/<job>-<attempt>/source.pdf, job.json   written by the server, renamed in whole
    running/<job>-<attempt>/                      the claim: mkdir, so only one worker wins
    running/<job>-<attempt>/out/                  result.json (+ book.epub) being written
    done/<job>-<attempt>/                         out/ renamed here: the published result
    workers/<worker>.json                         heartbeat with the engine revision

The attempt number in every name is the token: the server accepts a result only for
the attempt it is waiting for, so a worker that died, timed out or lost its job can
never publish over a newer attempt. The queue entry is the "keep going" signal: when
the server cancels, expires or requeues a job it deletes the entry, and the worker
stops that conversion. The claim folder stays until the server reconciles, so the
same attempt is never claimed twice.

Each conversion runs in a child process (``python -m quire_convert.worker``) with the
job's time limit. When it ends, however it ends, the whole process tree is killed; on
Linux the worker is a child subreaper, so even a process that detached itself is
found. The worker then exits: it waits for one job, converts it and stops, and the
container restarts it. A PDF that took over Calibre therefore cannot stay behind to
watch later jobs. It can still see the jobs queued while it runs, because they share
the folder; only a container or a user per job would close that.
"""

from __future__ import annotations

import json
import logging
import os
import re
import shutil
import signal
import socket
import subprocess
import sys
import time
from collections.abc import Callable
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from . import __version__
from .job import atomic_write_bytes, sha256_file
from .models import Outcome, StageError

log = logging.getLogger(__name__)

FORMAT = 1
NAME = re.compile(r"[0-9a-f]{32}-[1-9][0-9]{0,2}")
MAX_ISSUES = 100
HEARTBEAT_S = 30.0
PRECHECK_TIMEOUT_S = 120.0
DIRS = ("queue", "running", "done", "workers")

# A runner converts one claimed job into ``out/child.json`` (+ ``out/book.epub``) and
# returns None, or returns the failure code when it could not: "timed_out",
# "cancelled" or "worker_error".
Runner = Callable[["Claim", Callable[[], bool], Callable[[], None]], str | None]


def engine_revision(versions: dict[str, str]) -> str:
    """What converts a book here, as one string the server records with each job."""
    calibre, epubcheck = versions.get("calibre", "unknown"), versions.get("epubcheck", "unknown")
    return f"quire-convert {__version__}; calibre {calibre}; epubcheck {epubcheck}"


class Claim:
    """One attempt this worker owns: ``running/<name>`` exists and is ours."""

    def __init__(self, root: Path, name: str, job: dict[str, Any]) -> None:
        self.name = name
        self.job = job
        self.queued = root / "queue" / name
        self.dir = root / "running" / name
        self.out = self.dir / "out"

    @property
    def source(self) -> Path:
        return self.queued / "source.pdf"

    def still_wanted(self) -> bool:
        return (self.queued / "job.json").is_file()


class Worker:
    def __init__(
        self,
        root: Path,
        *,
        engine: str,
        runner: Runner | None = None,
        poll_s: float = 2.0,
        worker_id: str | None = None,
    ) -> None:
        self.root = Path(root)
        self.engine = engine
        self.runner = runner or run_child
        self.poll_s = poll_s
        self.worker_id = worker_id or f"{socket.gethostname()}-{os.getpid()}"
        self.stopping = False
        self._beat_at = 0.0

    # ------------------------------------------------------------------ loop

    def run(self, *, once: bool = False) -> int:
        """Wait for a job, convert it and return; with ``once``, return at once
        when nothing is queued."""
        for name in DIRS:
            if not (self.root / name).is_dir():
                log.error("job folder %s/ is missing; is the server's folder mounted?", name)
                return 1
        while not self.stopping:
            self.heartbeat()
            claim = self.claim()
            if claim is not None:
                self.process(claim)
                return 0
            if once:
                return 0
            time.sleep(self.poll_s)
        return 0

    def heartbeat(self, *, force: bool = False) -> None:
        now = time.monotonic()
        if not force and now - self._beat_at < HEARTBEAT_S:
            return
        self._beat_at = now
        beat = {
            "format": FORMAT,
            "engine": self.engine,
            "updated_at": datetime.now(UTC).isoformat(),
        }
        try:
            _write(self.root / "workers" / f"{self.worker_id}.json", _json(beat))
        except OSError as exc:
            log.warning("heartbeat not written: %s", type(exc).__name__)

    def claim(self) -> Claim | None:
        """Claim the oldest queued attempt nobody has claimed yet."""
        queue = self.root / "queue"
        entries = []
        for entry in queue.iterdir():
            if NAME.fullmatch(entry.name):
                try:
                    entries.append((entry.stat().st_mtime, entry.name))
                except FileNotFoundError:
                    continue
        for _, name in sorted(entries):
            claim_dir = self.root / "running" / name
            try:
                claim_dir.mkdir()
            except FileExistsError:
                continue
            try:
                job = json.loads((queue / name / "job.json").read_text())
            except (OSError, ValueError):
                # Cancelled between the listing and the claim, or not ours to read.
                shutil.rmtree(claim_dir, ignore_errors=True)
                continue
            atomic_write_bytes(
                claim_dir / "claim.json",
                _json({"worker": self.worker_id, "claimed_at": datetime.now(UTC).isoformat()}),
            )
            log.info("event=convert.claimed job=%s", name)
            return Claim(self.root, name, job)
        return None

    def process(self, claim: Claim) -> None:
        """Convert one claimed attempt and publish its result, unless it was withdrawn."""
        started = time.monotonic()
        result: dict[str, Any] | None = None
        problem = _check_job(claim)
        if problem is not None:
            result = _failure("worker_error", problem)
        else:
            code = self.runner(claim, self._withdrawn(claim), self.heartbeat)
            if code == "cancelled":
                self._abandon(claim)
                return
            if code is not None:
                shutil.rmtree(claim.out, ignore_errors=True)
                result = _failure(code, _FAILURE_DETAIL[code])
            else:
                result = _read_child(claim.out)
        if not claim.still_wanted():
            self._abandon(claim)
            return
        result |= {
            "format": FORMAT,
            "job": claim.name.rsplit("-", 1)[0],
            "attempt": int(claim.name.rsplit("-", 1)[1]),
            "engine": self.engine,
            "seconds": round(time.monotonic() - started, 1),
        }
        try:
            claim.out.mkdir(exist_ok=True)
            if result.get("epub") is None:
                (claim.out / "book.epub").unlink(missing_ok=True)
            _write(claim.out / "result.json", _json(result))
            os.rename(claim.out, self.root / "done" / claim.name)
        except OSError as exc:
            # The server withdrew the attempt and cleared its folder meanwhile.
            error = type(exc).__name__
            log.warning("event=convert.publish_failed job=%s error=%s", claim.name, error)
        _clear_scratch(claim)
        log.info(
            "event=convert.published job=%s outcome=%s code=%s seconds=%s",
            claim.name,
            result.get("outcome"),
            result.get("code"),
            result["seconds"],
        )

    def _withdrawn(self, claim: Claim) -> Callable[[], bool]:
        return lambda: self.stopping or not claim.still_wanted()

    def _abandon(self, claim: Claim) -> None:
        # Nothing was published. On shutdown the claim goes too, so the same attempt
        # can be claimed again; a cancelled job's claim is the server's to remove.
        if self.stopping:
            shutil.rmtree(claim.dir, ignore_errors=True)
        else:
            _clear_scratch(claim)
            shutil.rmtree(claim.out, ignore_errors=True)
        log.info("event=convert.abandoned job=%s stopping=%s", claim.name, self.stopping)


_FAILURE_DETAIL = {
    "timed_out": "the conversion ran past the time limit",
    "worker_error": "the conversion stopped unexpectedly",
}


def _check_job(claim: Claim) -> str | None:
    job = claim.job
    try:
        if job.get("format") != FORMAT:
            return "unknown job format"
        if not isinstance(job.get("max_pages"), int) or not isinstance(
            job.get("time_limit_s"), int
        ):
            return "job file is missing its limits"
        if sha256_file(claim.source) != job.get("sha256"):
            return "source checksum does not match the job"
    except OSError:
        return "source is unreadable"
    return None


def _failure(code: str, detail: str, retryable: bool = False) -> dict[str, Any]:
    return {
        "outcome": Outcome.FAILED.value,
        "code": code,
        "detail": detail,
        "retryable": retryable,
        "epub": None,
    }


def _read_child(out: Path) -> dict[str, Any]:
    try:
        data = json.loads((out / "child.json").read_text())
    except (OSError, ValueError):
        return _failure("worker_error", _FAILURE_DETAIL["worker_error"])
    (out / "child.json").unlink()
    return data


def _clear_scratch(claim: Claim) -> None:
    shutil.rmtree(claim.dir / "work", ignore_errors=True)
    (claim.dir / "child.log").unlink(missing_ok=True)


def _write(path: Path, data: bytes) -> None:
    # Readable by a server running as another user (``atomic_write_bytes`` makes 0600).
    atomic_write_bytes(path, data)
    path.chmod(0o664)


def _json(data: Any) -> bytes:
    return json.dumps(data, indent=2, sort_keys=True).encode() + b"\n"


# ---------------------------------------------------------------------- child process


def run_child(claim: Claim, withdrawn: Callable[[], bool], tick: Callable[[], None]) -> str | None:
    """Run the conversion in its own process session and supervise it."""
    argv = [
        sys.executable,
        "-m",
        "quire_convert.worker",
        str(claim.source),
        str(claim.out),
        str(claim.job["max_pages"]),
    ]
    return supervise(argv, claim.dir, claim.job["time_limit_s"], withdrawn, tick)


def supervise(
    argv: list[str],
    cwd: Path,
    time_limit_s: float,
    withdrawn: Callable[[], bool],
    tick: Callable[[], None],
) -> str | None:
    # The child's output may name pages or tools; it stays in the claim folder, which
    # is cleared after publishing, and never reaches the worker's own log.
    with open(cwd / "child.log", "wb") as child_log:
        proc = subprocess.Popen(
            argv,
            cwd=cwd,
            stdin=subprocess.DEVNULL,
            stdout=child_log,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )
    deadline = time.monotonic() + time_limit_s
    try:
        while True:
            try:
                code = proc.wait(timeout=1.0)
                break
            except subprocess.TimeoutExpired:
                pass
            tick()
            if withdrawn():
                return "cancelled"
            if time.monotonic() > deadline:
                return "timed_out"
        return None if code == 0 else "worker_error"
    finally:
        # Whatever the child left running goes with it, also after a clean exit.
        kill_tree(proc)


def kill_tree(proc: subprocess.Popen) -> None:
    """SIGKILL the child, its process group, and on Linux every descendant: Calibre
    starts its own session, so a group kill alone would leave it running. As a
    subreaper (``become_subreaper``) the worker also adopts what detached itself
    from the child, so those are its own descendants until they are collected."""
    pids = _descendants(proc.pid)
    try:
        os.killpg(proc.pid, signal.SIGKILL)
    except (ProcessLookupError, PermissionError):
        pass
    proc.kill()
    proc.wait()
    for _ in range(100):
        for pid in pids:
            try:
                os.kill(pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
        _reap()
        pids = _descendants(os.getpid())
        if not pids:
            return
        time.sleep(0.05)
    log.warning("event=convert.processes_left count=%s", len(pids))


def _reap() -> None:
    """Collect the adopted processes that have died, so none lingers as a zombie."""
    while True:
        try:
            pid, _ = os.waitpid(-1, os.WNOHANG)
        except ChildProcessError:
            return
        if pid == 0:
            return


def become_subreaper() -> bool:
    """On Linux, adopt every orphan the conversion leaves (PR_SET_CHILD_SUBREAPER),
    so ``kill_tree`` finds processes that detached from the child."""
    if not sys.platform.startswith("linux"):
        return False
    import ctypes

    try:
        return ctypes.CDLL(None, use_errno=True).prctl(36, 1, 0, 0, 0) == 0
    except (OSError, AttributeError):
        return False


def _descendants(root: int) -> list[int]:
    proc = Path("/proc")
    if not proc.is_dir():
        return []
    children: dict[int, list[int]] = {}
    for entry in proc.iterdir():
        if not entry.name.isdigit():
            continue
        try:
            stat = (entry / "stat").read_text()
        except OSError:
            continue
        # "pid (comm) state ppid ...": comm may hold spaces or parentheses.
        ppid = int(stat.rsplit(")", 1)[1].split()[1])
        children.setdefault(ppid, []).append(int(entry.name))
    found, stack = [], [root]
    while stack:
        for child in children.get(stack.pop(), []):
            found.append(child)
            stack.append(child)
    return found


def convert_one(src: Path, out: Path, max_pages: int) -> dict[str, Any]:
    """The child's work: refuse what the server does not convert, then run the pipeline
    with the CLI's default settings, so the rendition id depends on the PDF alone."""
    from .pipeline import convert, rendition_identifier
    from .source import poppler
    from .tools import find_tools

    tools = find_tools()
    if tools.pdfinfo is None:
        return _failure("worker_error", "pdfinfo is missing from the worker")
    try:
        info = poppler.pdfinfo(str(tools.pdfinfo), src, PRECHECK_TIMEOUT_S)
    except poppler.PasswordRequired:
        return _failure("encrypted", "the PDF is encrypted")
    except StageError:
        return _failure("unreadable_pdf", "the PDF could not be read")
    if info.encrypted:
        return _failure("encrypted", "the PDF is encrypted")
    if info.pages > max_pages:
        return _failure("too_many_pages", f"{info.pages} pages, the limit is {max_pages}")

    result = convert(src, job_root=out.parent / "work")
    report = result.report
    if result.outcome is Outcome.UNSUPPORTED:
        return _failure("unsupported", report.reason)
    if result.outcome is Outcome.FAILED:
        failure = report.failure or {}
        stage = failure.get("stage", "unknown")
        return _failure("conversion_failed", f"failed at {stage}", bool(failure.get("retryable")))
    epub = out / "book.epub"
    shutil.copyfile(result.epub_path, epub)
    blocking = [i for i in report.issues if i.blocks_ready]
    return {
        "outcome": result.outcome.value,
        "code": None,
        "detail": report.reason,
        "retryable": False,
        "epub": {
            "sha256": sha256_file(epub),
            "bytes": epub.stat().st_size,
            "rendition_id": rendition_identifier(report.input_sha256, report.settings),
        },
        "issue_counts": report.issue_counts,
        # The pipeline counts pages from 0; the server and its readers from 1.
        "affected_pages": [p | {"page": p["page"] + 1} for p in report.affected_pages],
        "issues": [
            {
                "code": i.code,
                "severity": i.severity.value,
                "page": None if i.page is None else i.page + 1,
                "page_label": i.page_label,
                "message": i.message,
            }
            for i in blocking[:MAX_ISSUES]
        ],
    }


def _child_main(argv: list[str]) -> int:
    src, out, max_pages = Path(argv[0]), Path(argv[1]), int(argv[2])
    out.mkdir(parents=True, exist_ok=True)
    try:
        data = convert_one(src, out, max_pages)
    except Exception as exc:  # the parent turns a missing child.json into worker_error
        print(f"conversion crashed with {type(exc).__name__}", file=sys.stderr)
        return 1
    atomic_write_bytes(out / "child.json", _json(data))
    return 0


# ---------------------------------------------------------------------- entry point


def main(jobs: Path, *, once: bool, poll_s: float) -> int:
    """``quire-convert worker``: group-writable files, so a server running as another
    user of the same group can clear what the worker leaves."""
    from .tools import find_tools

    os.umask(0o002)
    if not become_subreaper():
        log.info("event=convert.no_subreaper")
    tools = find_tools()
    tools.require("ebook_convert", "java", "epubcheck_jar", "pdfinfo")
    worker = Worker(jobs, engine=engine_revision(tools.versions()), poll_s=poll_s)

    def stop(signum, frame):
        worker.stopping = True

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    log.info("event=convert.worker_started engine=%r", worker.engine)
    worker.heartbeat(force=True)
    return worker.run(once=once)


if __name__ == "__main__":
    sys.exit(_child_main(sys.argv[1:]))
