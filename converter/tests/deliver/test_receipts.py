import threading
import time

import pytest
from deliver_support import make_epub, make_job

from quire_convert.deliver import DeliveryError, epub_identity, load_deliverable
from quire_convert.deliver.receipts import Busy, Receipt, ReceiptKey, ReceiptStore, State
from quire_convert.models import Outcome

KEY = ReceiptKey("alice", "http://cat", "book:7", "urn:uuid:r1")


def test_claim_creates_and_save_persists(tmp_path):
    store = ReceiptStore(tmp_path / "r.sqlite")
    with store.claim(KEY, "sha-1") as receipt:
        assert (receipt.state, receipt.attempts) == (State.PENDING, 0)
        receipt.state, receipt.attempts, receipt.remote_book_id = State.SAVED, 1, 7
        store.save(receipt)
    again = ReceiptStore(tmp_path / "r.sqlite").get(KEY)
    assert (again.state, again.attempts, again.remote_book_id, again.epub_sha256) == (
        State.SAVED,
        1,
        7,
        "sha-1",
    )
    other = ReceiptKey("alice", "http://cat", "book:8", "urn:uuid:r1")
    assert store.get(other) is None


def test_second_claim_of_same_key_waits_then_reports_busy(tmp_path):
    store = ReceiptStore(tmp_path / "r.sqlite")
    held, release = threading.Event(), threading.Event()

    def holder():
        with store.claim(KEY, "sha-1"):
            held.set()
            release.wait(5)

    thread = threading.Thread(target=holder)
    thread.start()
    held.wait(5)
    with pytest.raises(Busy) as busy:
        with store.claim(KEY, "sha-1", timeout=0.2):
            pass
    assert busy.value.receipt.state is State.PENDING
    other = ReceiptKey("bob", "http://cat", "book:7", "urn:uuid:r1")
    with store.claim(other, "sha-1", timeout=0.2):  # other keys are not blocked
        pass
    release.set()
    thread.join()
    start = time.monotonic()
    with store.claim(KEY, "sha-1", timeout=1):
        assert time.monotonic() - start < 0.5


def test_lock_is_released_when_the_holder_dies(tmp_path):
    store = ReceiptStore(tmp_path / "r.sqlite")
    with pytest.raises(KeyboardInterrupt):
        with store.claim(KEY, "sha-1"):
            raise KeyboardInterrupt
    with store.claim(KEY, "sha-1", timeout=0.2):
        pass


@pytest.mark.parametrize(
    ("state", "attempts", "uncertain"),
    [
        (State.PENDING, 0, False),
        (State.PENDING, 1, True),
        (State.SUBMITTED, 1, True),
        (State.AMBIGUOUS, 1, True),
        (State.FAILED, 1, False),
        (State.CONFLICT, 0, False),
        (State.SAVED, 1, False),
    ],
)
def test_write_uncertain(state, attempts, uncertain):
    assert Receipt(KEY, "s", state=state, attempts=attempts).write_uncertain is uncertain


def test_exit_codes():
    codes = {s: s.exit_code for s in State}
    assert codes == {
        State.SAVED: 0,
        State.FAILED: 1,
        State.SUBMITTED: 2,
        State.AMBIGUOUS: 2,
        State.PENDING: 2,
        State.CONFLICT: 3,
    }


# --------------------------------------------------------------------------- deliverable


def test_load_deliverable_from_dir_or_manifest(tmp_path):
    job = make_job(tmp_path, "urn:uuid:aaaa", extra_ids=["isbn:9780000000002"])
    for path in (job.dir, job.dir / "manifest.json"):
        d = load_deliverable(path)
        assert d.rendition_id == "urn:uuid:aaaa"
        assert d.title == "The Bridge"
        assert d.epub == job.artifact("epub")
        assert d.sha256 == job.read_manifest()["artifacts"]["epub"]["sha256"]


def test_only_ready_books_by_default(tmp_path):
    review = make_job(tmp_path, "urn:uuid:bbbb", outcome=Outcome.REVIEW)
    with pytest.raises(DeliveryError, match="allow-review"):
        load_deliverable(review.dir)
    assert load_deliverable(review.dir, allow_review=True).rendition_id == "urn:uuid:bbbb"
    failed = make_job(tmp_path, "urn:uuid:cccc", outcome=Outcome.FAILED)
    with pytest.raises(DeliveryError, match="failed"):
        load_deliverable(failed.dir, allow_review=True)


def test_tampered_epub_is_refused(tmp_path):
    job = make_job(tmp_path, "urn:uuid:dddd")
    make_epub(job.artifact("epub"), "urn:uuid:other")
    with pytest.raises(DeliveryError, match="checksum"):
        load_deliverable(job.dir)


def test_epub_identity_reads_identifiers_in_order(tmp_path):
    path = make_epub(tmp_path / "b.epub", "urn:uuid:first", extra_ids=["calibre:2", "isbn:1"])
    identity = epub_identity(path)
    assert identity.identifiers == ("urn:uuid:first", "calibre:2", "isbn:1")
    assert identity.rendition_id == "urn:uuid:first"
    (tmp_path / "junk.epub").write_bytes(b"not a zip")
    with pytest.raises(DeliveryError):
        epub_identity(tmp_path / "junk.epub")
