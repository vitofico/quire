import dataclasses
import threading

import pytest
from fake_calibre_web import FakeCalibreWeb

from quire_convert.deliver import DeliveryError, load_deliverable
from quire_convert.deliver.calibre_web import CalibreWeb, Http, Policy, run_delivery
from quire_convert.deliver.cwa_ingest import IngestDestination, IngestFolder, handoff
from quire_convert.deliver.receipts import ReceiptKey, ReceiptStore, State


@pytest.fixture
def server():
    with FakeCalibreWeb() as fake:
        yield fake


@pytest.fixture
def folder(tmp_path):
    path = tmp_path / "ingest"
    path.mkdir()
    return path


def test_handoff_is_complete_atomic_and_outside_staging(job, folder):
    d = load_deliverable(job.dir)
    final = handoff(d, IngestFolder(folder, mode=0o640))
    assert final.parent == folder and final.name.endswith(".epub")
    assert d.title not in final.name
    assert final.read_bytes() == d.epub.read_bytes()
    assert final.stat().st_mode & 0o777 == 0o640
    assert list(IngestFolder(folder).staging_dir.iterdir()) == []


def test_handoff_refuses_unsafe_staging_and_bad_bytes(job, folder):
    d = load_deliverable(job.dir)
    with pytest.raises(DeliveryError, match="outside"):
        handoff(d, IngestFolder(folder, staging=folder / "tmp"))
    with pytest.raises(DeliveryError, match="checksum"):
        handoff(dataclasses.replace(d, sha256="0" * 64), IngestFolder(folder))
    assert list(folder.iterdir()) == []


def deliver(server, job, tmp_path, folder, policy):
    d = load_deliverable(job.dir)
    client = CalibreWeb(Http(server.url), "alice", "secret-pw")
    key = ReceiptKey("alice", server.url, "new", d.rendition_id)
    dest = IngestDestination(client, d, IngestFolder(folder))
    return run_delivery(ReceiptStore(tmp_path / "r.sqlite"), key, d, dest, policy)


def test_import_that_never_completes_stays_submitted_then_ambiguous(server, job, tmp_path, folder):
    receipt = deliver(server, job, tmp_path, folder, Policy(wait=0.1, ambiguous_after=60))
    assert (receipt.state, receipt.attempts) == (State.SUBMITTED, 1)
    for f in folder.iterdir():  # the ingest service removes the file but imports nothing
        f.unlink()
    receipt = deliver(server, job, tmp_path, folder, Policy(wait=0.1, ambiguous_after=60))
    assert receipt.state is State.SUBMITTED
    receipt = deliver(server, job, tmp_path, folder, Policy(wait=0.1, ambiguous_after=0))
    assert receipt.state is State.AMBIGUOUS and receipt.attempts == 1
    assert list(folder.iterdir()) == []  # no second handoff


def test_import_confirmed_by_readback(server, job, tmp_path, folder):
    def ingest_service(stop):
        while not stop.wait(0.05):
            for f in folder.glob("*.epub"):
                server.add_new(f.read_bytes())
                f.unlink()

    stop = threading.Event()
    thread = threading.Thread(target=ingest_service, args=(stop,))
    thread.start()
    try:
        receipt = deliver(server, job, tmp_path, folder, Policy(wait=3))
    finally:
        stop.set()
        thread.join()
    assert (receipt.state, receipt.remote_book_id, receipt.attempts) == (State.SAVED, 1, 1)
    again = deliver(server, job, tmp_path, folder, Policy(wait=0.1))
    assert (again.state, again.attempts, len(server.books)) == (State.SAVED, 1, 1)
