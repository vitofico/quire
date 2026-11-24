"""Acceptance test against a disposable Calibre-Web Automated container.

Calibre-Web Automated is a Calibre-Web fork that serves the same web routes, so the stock
adapter is exercised through its web UI, and its ingest folder through the CWA adapter.
The image must already be present: these tests never pull. Each scenario logs in as its
own user because the server allows only three logins per user per minute.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import socket
import subprocess
import threading
import time
import urllib.parse
import uuid
from pathlib import Path

import pytest
from deliver_support import make_epub, make_job

from quire_convert.deliver import epub_identity, load_deliverable
from quire_convert.deliver.calibre_web import (
    QUALIFIED_VERSION,
    CalibreWeb,
    Http,
    NewBookDestination,
    Policy,
    RecordDestination,
    csrf_token,
    run_delivery,
)
from quire_convert.deliver.cwa_ingest import IngestDestination, IngestFolder
from quire_convert.deliver.receipts import ReceiptKey, ReceiptStore, State

IMAGE = "crocodilestick/calibre-web-automated:v4.0.8"
ADMIN = ("admin", "admin123")  # the image's documented first-run login
PASSWORD = "Quire-test-pass-1234"
READBACK = Policy(wait=120, ambiguous_after=900)
USERS = {
    "alice": ("download", "upload", "edit"),
    "bob": ("download", "upload", "edit"),
    "carol": ("download", "upload", "edit"),
    "dave": ("download",),  # no upload role
    "frank": ("download", "upload"),
    "gina": ("download", "upload"),
    "hank": ("download", "upload"),
}

pytestmark = [pytest.mark.tools, pytest.mark.docker]


def _docker_ready() -> str | None:
    if shutil.which("docker") is None:
        return "docker is not installed"
    if subprocess.run(["docker", "info"], capture_output=True).returncode != 0:
        return "the docker daemon is not running"
    if subprocess.run(["docker", "image", "inspect", IMAGE], capture_output=True).returncode:
        return f"{IMAGE} is not present locally (not pulled by tests)"
    return None


def _free_port() -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


class Catalog:
    def __init__(self, root: Path):
        self.root = root
        self.name = f"qc-deliver-{uuid.uuid4().hex[:8]}"
        self.port = _free_port()
        self.url = f"http://127.0.0.1:{self.port}"
        self.library, self.ingest = root / "library", root / "ingest"
        for d in ("config", "library", "ingest"):
            (root / d).mkdir()

    def start(self) -> None:
        mounts = [
            f"{self.root / d}:/{m}"
            for d, m in [
                ("config", "config"),
                ("library", "calibre-library"),
                ("ingest", "cwa-book-ingest"),
            ]
        ]
        cmd = ["docker", "run", "-d", "--name", self.name, "-p", f"127.0.0.1:{self.port}:8083"]
        cmd += ["-e", f"PUID={os.getuid()}", "-e", f"PGID={os.getgid()}", "-e", "TZ=UTC"]
        cmd += ["-e", "CWA_WATCH_MODE=poll"]
        for m in mounts:
            cmd += ["-v", m]
        subprocess.run([*cmd, IMAGE], check=True, capture_output=True)
        deadline = time.monotonic() + 240
        while time.monotonic() < deadline:
            try:
                if Http(self.url, timeout=5).request("GET", "/login").status == 200:
                    return
            except Exception:
                pass
            time.sleep(2)
        raise RuntimeError("the catalog did not start")

    def stop(self) -> None:
        subprocess.run(["docker", "rm", "-f", "-v", self.name], capture_output=True)

    def calibredb(self, *args: str) -> str:
        cmd = ["docker", "exec", "--user", f"{os.getuid()}:{os.getgid()}", "-e", "HOME=/tmp"]
        cmd += [self.name, "calibredb", *args, "--library-path=/calibre-library"]
        return subprocess.run(cmd, check=True, capture_output=True, text=True).stdout

    def add_record(self, pdf: Path, title: str, isbn: str, epub: Path | None = None) -> int:
        target = f"/tmp/{uuid.uuid4().hex}.pdf"
        subprocess.run(["docker", "cp", str(pdf), f"{self.name}:{target}"], check=True)
        out = self.calibredb(
            "add",
            "--duplicates",
            target,
            "--title",
            title,
            "--authors",
            "A. Writer",
            "--isbn",
            isbn,
        )
        book_id = int(re.search(r"Added book ids: (\d+)", out).group(1))
        if epub is not None:
            target = f"/tmp/{uuid.uuid4().hex}.epub"
            subprocess.run(["docker", "cp", str(epub), f"{self.name}:{target}"], check=True)
            self.calibredb("add_format", str(book_id), target)
        return book_id

    def records(self) -> dict[int, dict]:
        out = self.calibredb(
            "list", "--fields", "title,authors,identifiers,formats", "--for-machine"
        )
        return {row["id"]: row for row in json.loads(out)}

    def file(self, record: dict, fmt: str) -> Path:
        path = next(p for p in record["formats"] if p.lower().endswith("." + fmt))
        return self.library / Path(path).relative_to("/calibre-library")

    def format_writes(self, book_id: int) -> int:
        logs = subprocess.run(["docker", "logs", self.name], capture_output=True, text=True)
        text = logs.stdout + logs.stderr
        return len(re.findall(rf"Added new format for book id {book_id}:", text))

    def create_users(self) -> None:
        http = Http(self.url)
        CalibreWeb(http, *ADMIN).login()
        for name, roles in USERS.items():
            token = csrf_token(http.request("GET", "/admin/user/new").text)
            form = {"csrf_token": token, "name": name, "email": f"{name}@example.test"}
            form |= {"password": PASSWORD, "default_language": "all", "locale": "en"}
            form |= {f"{role}_role": "on" for role in roles}
            resp = http.request(
                "POST",
                "/admin/user/new",
                body=urllib.parse.urlencode(form).encode(),
                headers={"Content-Type": "application/x-www-form-urlencoded"},
            )
            assert "created" in resp.text, f"could not create user {name}"


@pytest.fixture(scope="module")
def catalog(tmp_path_factory):
    reason = _docker_ready()
    if reason:
        pytest.skip(reason)
    cat = Catalog(tmp_path_factory.mktemp("cwa"))
    try:
        cat.start()
        cat.create_users()
        yield cat
    finally:
        cat.stop()


@pytest.fixture(scope="module")
def pdf(tmp_path_factory):
    canvas = pytest.importorskip("reportlab.pdfgen.canvas")
    path = tmp_path_factory.mktemp("pdf") / "bridge.pdf"
    page = canvas.Canvas(str(path))
    page.drawString(72, 720, "The Bridge, a scanned edition.")
    page.save()
    return path


def _deliver(
    catalog,
    job,
    receipts: Path,
    user: str,
    book_id=None,
    *,
    http=Http,
    ingest=None,
    policy=READBACK,
):
    d = load_deliverable(job.dir)
    client = CalibreWeb(http(catalog.url), user, PASSWORD)
    if ingest is not None:
        dest, where = IngestDestination(client, d, ingest), "new"
    elif book_id is None:
        dest, where = NewBookDestination(client, d), "new"
    else:
        dest, where = RecordDestination(client, book_id, d), f"book:{book_id}"
    key = ReceiptKey(user, catalog.url, where, d.rendition_id)
    return run_delivery(ReceiptStore(receipts), key, d, dest, policy)


class Crash(BaseException):
    pass


class DropUploadResponse(Http):
    def request(self, method, path, **kwargs):
        resp = super().request(method, path, **kwargs)
        if method == "POST" and path == "/upload":
            raise Crash("the response was lost and the client died")
        return resp


def _sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def test_interrupted_attach_then_retry_saves_exactly_once(catalog, pdf, tmp_path):
    assert QUALIFIED_VERSION.endswith(IMAGE.rsplit(":", 1)[1])
    book = catalog.add_record(pdf, "The Bridge", "9780000000002")
    edition = catalog.add_record(pdf, "The Bridge", "9780000000019")  # same title, other record
    before = catalog.records()
    pdf_sha = _sha(catalog.file(before[book], "pdf"))
    job = make_job(tmp_path / "jobs", "urn:uuid:0a0a0a0a-1111-5111-8111-000000000001")
    receipts = tmp_path / "receipts.sqlite"

    with pytest.raises(Crash):
        _deliver(catalog, job, receipts, "alice", book, http=DropUploadResponse)
    # A new client process: same receipts, fresh session.
    receipt = _deliver(catalog, job, receipts, "alice", book)

    assert (receipt.state, receipt.remote_book_id, receipt.attempts) == (State.SAVED, book, 1)
    after = catalog.records()
    assert len(after) == len(before)
    record = after[book]
    assert sorted(Path(p).suffix for p in record["formats"]) == [".epub", ".pdf"]
    assert _sha(catalog.file(record, "pdf")) == pdf_sha
    for field in ("title", "authors", "identifiers"):
        assert record[field] == before[book][field]
    rendition = load_deliverable(job.dir).rendition_id
    assert rendition in epub_identity(catalog.file(record, "epub")).identifiers
    assert after[edition]["formats"] == before[edition]["formats"]
    assert catalog.format_writes(book) == 1


def test_pre_existing_different_epub_is_a_conflict(catalog, pdf, tmp_path):
    other = make_epub(tmp_path / "other.epub", "urn:uuid:0a0a0a0a-2222-5222-8222-00000000000f")
    book = catalog.add_record(pdf, "The Lighthouse", "9780000000026", epub=other)
    before = catalog.records()[book]
    epub_sha = _sha(catalog.file(before, "epub"))
    job = make_job(tmp_path / "jobs", "urn:uuid:0a0a0a0a-2222-5222-8222-000000000002")
    receipt = _deliver(catalog, job, tmp_path / "r.sqlite", "carol", book)
    assert receipt.state is State.CONFLICT and "different EPUB" in receipt.last_error
    after = catalog.records()[book]
    assert after["formats"] == before["formats"]
    assert _sha(catalog.file(after, "epub")) == epub_sha
    assert catalog.format_writes(book) == 0


def test_concurrent_saves_of_the_same_key_write_once(catalog, pdf, tmp_path):
    book = catalog.add_record(pdf, "The Harbour", "9780000000033")
    job = make_job(tmp_path / "jobs", "urn:uuid:0a0a0a0a-3333-5333-8333-000000000003")
    results = []

    def run():
        results.append(_deliver(catalog, job, tmp_path / "r.sqlite", "bob", book))

    threads = [threading.Thread(target=run) for _ in range(2)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert [r.state for r in results] == [State.SAVED, State.SAVED]
    assert catalog.format_writes(book) == 1


def test_denied_upload_permission_fails(catalog, pdf, tmp_path):
    book = catalog.add_record(pdf, "The Quay", "9780000000040")
    before = catalog.records()[book]
    job = make_job(tmp_path / "jobs", "urn:uuid:0a0a0a0a-4444-5444-8444-000000000004")
    receipt = _deliver(catalog, job, tmp_path / "r.sqlite", "dave", book)
    assert receipt.state is State.FAILED and "no upload permission" in receipt.last_error
    assert catalog.records()[book]["formats"] == before["formats"]


def test_ingest_handoff_that_never_imports_stays_submitted_then_ambiguous(catalog, tmp_path):
    unwatched = tmp_path / "not-watched"  # CWA never sees this folder
    unwatched.mkdir()
    job = make_job(
        tmp_path / "jobs",
        "urn:uuid:0a0a0a0a-5555-5555-8555-000000000005",
        title="The Unseen Canal",
    )
    receipts, target = tmp_path / "r.sqlite", IngestFolder(unwatched)
    quick = Policy(wait=5, ambiguous_after=900)
    receipt = _deliver(catalog, job, receipts, "frank", ingest=target, policy=quick)
    assert (receipt.state, receipt.attempts) == (State.SUBMITTED, 1)
    late = Policy(wait=1, ambiguous_after=0)
    receipt = _deliver(catalog, job, receipts, "frank", ingest=target, policy=late)
    assert (receipt.state, receipt.attempts) == (State.AMBIGUOUS, 1)
    assert len(list(unwatched.iterdir())) == 1  # handed off once, never again


def test_ingest_handoff_is_saved_only_after_readback(catalog, tmp_path):
    job = make_job(
        tmp_path / "jobs",
        "urn:uuid:0a0a0a0a-6666-5666-8666-000000000006",
        title="The Ingested Ferry",
    )
    target = IngestFolder(catalog.ingest, staging=catalog.root / "staging")
    receipt = _deliver(catalog, job, tmp_path / "r.sqlite", "gina", ingest=target)
    assert receipt.state is State.SAVED and receipt.remote_book_id
    record = catalog.records()[receipt.remote_book_id]
    rendition = load_deliverable(job.dir).rendition_id
    assert rendition in epub_identity(catalog.file(record, "epub")).identifiers


def test_new_book_through_the_web_upload(catalog, tmp_path):
    job = make_job(
        tmp_path / "jobs",
        "urn:uuid:0a0a0a0a-7777-5777-8777-000000000007",
        title="The Uploaded Tram",
    )
    receipts = tmp_path / "r.sqlite"
    receipt = _deliver(catalog, job, receipts, "hank")
    assert receipt.state is State.SAVED and receipt.remote_book_id
    again = _deliver(catalog, job, receipts, "hank")
    assert (again.state, again.remote_book_id, again.attempts) == (
        State.SAVED,
        receipt.remote_book_id,
        1,
    )
