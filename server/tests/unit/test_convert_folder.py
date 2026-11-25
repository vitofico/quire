"""``core.convert.Folder`` against what a hostile worker can leave in the folder.

The worker shares only the job folder with the server, so everything under
``done/``, ``running/`` and ``workers/`` may be crafted: links, FIFOs, folders
where files belong, forged reports and dates in the future.
"""

from __future__ import annotations

import hashlib
import io
import json
import os
import threading
import time
import zipfile
from pathlib import Path
from types import SimpleNamespace
from uuid import uuid4

import pytest

from quire_server.core import convert

LIMIT = 1 << 20


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def epub(*entries: tuple[str, bytes], mimetype: bytes = b"application/epub+zip") -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as book:
        book.writestr(zipfile.ZipInfo("mimetype"), mimetype)
        book.writestr("META-INF/container.xml", b"<container/>")
        for name, data in entries:
            book.writestr(name, data, compress_type=zipfile.ZIP_DEFLATED)
    return buf.getvalue()


EPUB = epub(("OEBPS/text.xhtml", b"<html/>"))


@pytest.fixture
def folder(tmp_path) -> convert.Folder:
    folder = convert.Folder(tmp_path / "jobs")
    folder.prepare()
    return folder


def new_job() -> SimpleNamespace:
    return SimpleNamespace(id=uuid4(), attempt=1)


def report(job, data: bytes = EPUB, **fields) -> dict:
    return {
        "format": 1,
        "job": job.id.hex,
        "attempt": job.attempt,
        "outcome": "ready",
        "engine": "engine",
        "epub": {"sha256": sha(data), "bytes": len(data)},
    } | fields


def publish(folder, job, data: bytes | None = EPUB, result: dict | str | None = None) -> Path:
    out = folder.done / f"{job.id.hex}-{job.attempt}"
    out.mkdir()
    if data is not None:
        (out / "book.epub").write_bytes(data)
    result = report(job, data or EPUB) if result is None else result
    (out / "result.json").write_text(result if isinstance(result, str) else json.dumps(result))
    return out


def within(fn, seconds: float = 5.0):
    """Run ``fn`` in a thread; fail if it is still blocked after ``seconds``."""
    box: dict = {}

    def run():
        try:
            box["value"] = fn()
        except BaseException as exc:  # noqa: BLE001 - reported below
            box["error"] = exc

    thread = threading.Thread(target=run, daemon=True)
    thread.start()
    thread.join(seconds)
    assert not thread.is_alive(), "blocked on a worker's file"
    if "error" in box:
        raise box["error"]
    return box["value"]


def accept(folder, job, published, limit: int = LIMIT) -> dict:
    return within(lambda: folder.accept(job, published, limit))


def test_a_good_result_is_copied_into_a_file_of_the_servers_own(folder):
    job = new_job()
    published = publish(folder, job)
    worker_inode = (published / "book.epub").stat().st_ino
    update = accept(folder, job, published)
    assert update["state"] == "ready"
    stored = folder.result(job.id)
    assert stored.read_bytes() == EPUB
    assert stored.stat().st_ino != worker_inode
    assert stored.stat().st_mode & 0o777 == 0o600
    assert [p.name for p in folder.store.iterdir()] == [stored.name]


def test_a_linked_epub_is_refused_and_its_target_left_alone(folder, tmp_path):
    secret = tmp_path / "server-secret.txt"
    secret.write_bytes(b"DB_PASSWORD=synthetic\n")
    secret.chmod(0o644)
    job = new_job()
    published = publish(folder, job, data=None, result=report(job, secret.read_bytes()))
    os.symlink(secret, published / "book.epub")
    assert accept(folder, job, published)["failure_code"] == "invalid_result"
    assert list(folder.store.iterdir()) == []
    assert secret.stat().st_mode & 0o777 == 0o644


def test_a_published_folder_that_is_a_link_is_refused(folder, tmp_path):
    job = new_job()
    elsewhere = tmp_path / "elsewhere"
    elsewhere.mkdir()
    (elsewhere / "book.epub").write_bytes(EPUB)
    (elsewhere / "result.json").write_text(json.dumps(report(job)))
    os.symlink(elsewhere, folder.done / f"{job.id.hex}-1")
    published = folder.published(job)
    assert published is not None
    assert accept(folder, job, published)["failure_code"] == "invalid_result"


@pytest.mark.parametrize("name", ["result.json", "book.epub"])
def test_a_fifo_never_blocks_the_server(folder, name):
    job = new_job()
    published = publish(folder, job)
    (published / name).unlink()
    os.mkfifo(published / name)
    assert accept(folder, job, published)["failure_code"] == "invalid_result"


def test_a_folder_named_book_epub_is_refused(folder):
    job = new_job()
    published = publish(folder, job, data=None, result=report(job, b""))
    (published / "book.epub").mkdir()
    assert accept(folder, job, published)["failure_code"] == "invalid_result"
    assert list(folder.store.iterdir()) == []


def forged(job) -> dict[str, str]:
    good = report(job)
    return {
        "deep nesting": '{"format": 1, "x": ' + "[" * 200_000 + "]" * 200_000 + "}",
        "not an object": "[1, 2]",
        "format is true": json.dumps(good | {"format": True}),
        "attempt is text": json.dumps(good | {"attempt": "1"}),
        "another job": json.dumps(good | {"job": "0" * 32}),
        "unknown outcome": json.dumps(good | {"outcome": "done"}),
        "failure code is a list": json.dumps(good | {"outcome": "failed", "code": ["x"]}),
        "retryable is text": json.dumps(
            good | {"outcome": "failed", "code": "x", "retryable": "y"}
        ),
        "affected_pages is a number": json.dumps(good | {"affected_pages": 5}),
        "a page is text": json.dumps(good | {"affected_pages": [{"page": "3"}]}),
        "issues is a number": json.dumps(good | {"issues": 7}),
        "an issue is a list": json.dumps(good | {"issues": [[1]]}),
        "unknown severity": json.dumps(
            good | {"issues": [{"code": "c", "severity": "error", "message": "m"}]}
        ),
        "huge count": json.dumps(good | {"issue_counts": {"major": 10**400}}),
        "unknown count key": json.dumps(good | {"issue_counts": {"warning": 1}}),
        "detail is a number": json.dumps(good | {"detail": 3}),
        "bytes is negative": json.dumps(good | {"epub": good["epub"] | {"bytes": -1}}),
        "sha256 is upper case": json.dumps(
            good | {"epub": good["epub"] | {"sha256": sha(EPUB).upper()}}
        ),
        "over the size cap": json.dumps(good) + " " * convert.MAX_RESULT_JSON,
    }


@pytest.mark.parametrize("case", list(forged(new_job())))
def test_a_forged_report_fails_the_job(folder, case):
    job = new_job()
    published = publish(folder, job, result=forged(job)[case])
    update = accept(folder, job, published)
    assert (update["state"], update["failure_code"]) == ("failed", "invalid_result")
    assert update["summary"]["retryable"] is True
    assert list(folder.store.iterdir()) == []


@pytest.mark.parametrize(
    ("code", "said", "retryable", "kept"),
    [
        ("encrypted", True, False, "encrypted"),
        ("timed_out", True, False, "timed_out"),
        ("conversion_failed", True, True, "conversion_failed"),
        ("worker_error", False, True, "worker_error"),
        ("something_new", False, True, "worker_error"),
    ],
)
def test_failure_codes_and_who_decides_retryable(folder, code, said, retryable, kept):
    job = new_job()
    result = report(job) | {"outcome": "failed", "code": code, "retryable": said, "epub": None}
    update = accept(folder, job, publish(folder, job, data=None, result=result))
    assert (update["failure_code"], update["summary"]["retryable"]) == (kept, retryable)


@pytest.mark.parametrize(
    "book",
    [
        pytest.param(b"PK\x03\x04 not a zip at all", id="not a zip"),
        pytest.param(epub(mimetype=b"application/zip"), id="wrong media type"),
        pytest.param(epub(("../escape.xhtml", b"x")), id="parent folder"),
        pytest.param(epub(("/etc/escape", b"x")), id="absolute"),
        pytest.param(epub(("C:escape", b"x")), id="drive letter"),
        pytest.param(epub(("OEBPS\\..\\escape", b"x")), id="backslashes"),
    ],
)
def test_an_epub_that_is_not_a_plain_book_is_refused(folder, book):
    job = new_job()
    update = accept(folder, job, publish(folder, job, book))
    assert update["failure_code"] == "invalid_result"
    assert list(folder.store.iterdir()) == []


def test_mimetype_must_come_first(folder):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as book:
        book.writestr("META-INF/container.xml", b"<container/>")
        book.writestr("mimetype", b"application/epub+zip")
    job = new_job()
    assert accept(folder, job, publish(folder, job, buf.getvalue()))["state"] == "failed"


def test_sizes_are_capped(folder):
    job = new_job()
    assert accept(folder, job, publish(folder, job), limit=len(EPUB) - 1)["state"] == "failed"
    # Small packed, large unpacked: over the unpacked ceiling.
    bomb = epub(("OEBPS/zeros.xhtml", b"0" * (1 << 20)))
    job = new_job()
    assert accept(folder, job, publish(folder, job, bomb), limit=len(bomb))["state"] == "failed"
    job = new_job()
    assert accept(folder, job, publish(folder, job, bomb), limit=LIMIT)["state"] == "ready"


def test_the_engine_ignores_what_is_not_a_fresh_regular_heartbeat(folder):
    assert within(folder.engine) is None
    os.mkfifo(folder.workers / "fifo.json")
    assert within(folder.engine) is None
    (folder.workers / "fifo.json").unlink()
    (folder.workers / "folder.json").mkdir()
    assert within(folder.engine) is None
    ahead = time.time() + 3600
    beat = folder.workers / "w1.json"
    beat.write_text(json.dumps({"engine": "quire-convert 1"}))
    os.utime(beat, (ahead, ahead))
    assert within(folder.engine) is None
    os.utime(beat, None)
    assert within(folder.engine) == "quire-convert 1"
    beat.write_text("[" * 4000)
    assert within(folder.engine) is None
    beat.write_text(json.dumps({"engine": 3}))
    assert within(folder.engine) == "unknown"


def test_a_claim_dated_ahead_counts_from_now(folder):
    job = new_job()
    claim = folder.running / f"{job.id.hex}-1"
    claim.mkdir()
    ahead = time.time() + 3600
    os.utime(claim, (ahead, ahead))
    assert folder.claimed_at(job).timestamp() <= time.time()


def test_clearing_survives_whatever_a_worker_leaves(folder, tmp_path):
    keep = tmp_path / "keep.txt"
    keep.write_text("not the folder's")
    old = time.time() - 2 * 24 * 3600
    ahead = time.time() + 3600
    (folder.workers / "old-folder").mkdir()
    os.utime(folder.workers / "old-folder", (old, old))
    (folder.running / "junk").mkdir()
    os.utime(folder.running / "junk", (ahead, ahead))
    os.symlink(keep, folder.done / "link")
    os.mkfifo(folder.done / "fifo")
    (folder.done / "folder").mkdir()
    (folder.done / "folder" / "book.epub").mkdir()
    (folder.store / "left-folder").mkdir()
    within(lambda: folder.clear_orphans(set(), set()))
    for entry in (folder.workers, folder.running, folder.done, folder.store):
        assert list(entry.iterdir()) == []
    assert keep.read_text() == "not the folder's"


def test_folders_the_worker_writes_are_sticky(folder):
    modes = {p.name: p.stat().st_mode & 0o7777 for p in folder.root.iterdir()}
    assert modes == {
        "incoming": 0o700,
        "store": 0o700,
        "queue": 0o755,
        "running": 0o1777,
        "done": 0o1777,
        "workers": 0o1777,
    }


def test_one_upload_per_job_at_a_time(folder):
    job_id = uuid4()
    first = folder.begin_upload(job_id)
    assert first is not None and first.stat().st_mode & 0o777 == 0o600
    assert folder.begin_upload(job_id) is None
    assert folder.begin_upload(uuid4()) is not None
    quiet = time.time() - convert.UPLOAD_STALE_S - 1
    os.utime(first, (quiet, quiet))
    second = folder.begin_upload(job_id)
    assert second is not None and second != first
    assert not first.exists()


def test_a_broken_heartbeat_does_not_hide_a_good_one(folder):
    beat = folder.workers / "good.json"
    beat.write_text(json.dumps({"engine": "quire-convert 1"}))
    earlier = time.time() - 10
    os.utime(beat, (earlier, earlier))
    os.mkfifo(folder.workers / "fifo.json")
    assert within(folder.engine) == "quire-convert 1"
