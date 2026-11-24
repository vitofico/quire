import threading

import pytest
from deliver_support import make_epub
from fake_calibre_web import FakeCalibreWeb, User, wait_until

from quire_convert.deliver import load_deliverable
from quire_convert.deliver.calibre_web import (
    CalibreWeb,
    Http,
    NewBookDestination,
    Policy,
    RecordDestination,
    TransportError,
    record_formats,
    run_delivery,
)
from quire_convert.deliver.receipts import ReceiptKey, ReceiptStore, State

FAST = Policy(wait=0.5, ambiguous_after=60)
PDF = b"%PDF-1.4 original"


class Crash(BaseException):
    """The delivery process dies: nothing in the adapter may catch this."""


class DropUploadResponse(Http):
    """Sends each upload to the server, then loses the answer."""

    error: type[BaseException] = Crash

    def request(self, method, path, **kwargs):
        resp = super().request(method, path, **kwargs)
        if method == "POST" and path == "/upload":
            raise self.error("connection reset after the write")
        return resp


@pytest.fixture
def server():
    with FakeCalibreWeb() as fake:
        yield fake


def deliver(server, job, tmp_path, book_id=None, *, http=Http, user="alice", policy=FAST):
    d = load_deliverable(job.dir)
    client = CalibreWeb(http(server.url), user, server.users[user].password)
    if book_id is None:
        dest, where = NewBookDestination(client, d), "new"
    else:
        dest, where = RecordDestination(client, book_id, d), f"book:{book_id}"
    key = ReceiptKey(user, server.url, where, d.rendition_id)
    return run_delivery(ReceiptStore(tmp_path / "receipts.sqlite"), key, d, dest, policy)


def test_attach_to_record_with_pdf(server, job, tmp_path):
    book = server.add_book("The Bridge", pdf=PDF)
    receipt = deliver(server, job, tmp_path, book)
    assert (receipt.state, receipt.remote_book_id, receipt.attempts) == (State.SAVED, book, 1)
    assert receipt.last_confirmed_at is not None
    assert set(server.books[book].formats) == {"PDF", "EPUB"}
    assert server.books[book].formats["PDF"] == PDF
    assert server.books[book].formats["EPUB"] == job.artifact("epub").read_bytes()
    again = deliver(server, job, tmp_path, book)
    assert (again.state, again.attempts, server.uploads) == (State.SAVED, 1, 1)


def test_same_rendition_rewritten_by_the_catalog_counts_as_saved(server, job, tmp_path):
    rid = load_deliverable(job.dir).rendition_id
    rewritten = make_epub(tmp_path / "cat.epub", rid, extra_ids=["calibre:1", "uuid:x"])
    book = server.add_book("The Bridge", pdf=PDF, epub=rewritten.read_bytes())
    receipt = deliver(server, job, tmp_path, book)
    assert (receipt.state, receipt.attempts, server.uploads) == (State.SAVED, 0, 0)


def test_a_different_epub_is_preserved(server, job, tmp_path):
    other = make_epub(tmp_path / "other.epub", "urn:uuid:someone-else").read_bytes()
    book = server.add_book("The Bridge", pdf=PDF, epub=other)
    receipt = deliver(server, job, tmp_path, book)
    assert receipt.state is State.CONFLICT
    assert "different EPUB" in receipt.last_error
    assert (server.uploads, server.books[book].formats["EPUB"]) == (0, other)


def test_unverifiable_existing_epub_is_a_conflict(server, job, tmp_path):
    server.users["alice"].roles = {"upload"}  # cannot download to compare
    other = make_epub(tmp_path / "other.epub", "urn:uuid:someone-else").read_bytes()
    book = server.add_book("The Bridge", pdf=PDF, epub=other)
    receipt = deliver(server, job, tmp_path, book)
    assert receipt.state is State.CONFLICT and "could not be compared" in receipt.last_error
    assert server.uploads == 0


def test_denied_upload_permission_fails_cleanly(server, job, tmp_path):
    server.users["alice"] = User("secret-pw", roles={"download"})
    book = server.add_book("The Bridge", pdf=PDF)
    receipt = deliver(server, job, tmp_path, book)
    assert receipt.state is State.FAILED
    assert "no upload permission" in receipt.last_error
    assert set(server.books[book].formats) == {"PDF"}
    server.users["alice"].roles.add("upload")  # a confirmed failure is safe to retry
    assert deliver(server, job, tmp_path, book).state is State.SAVED


def test_wrong_password_and_missing_record_fail(server, job, tmp_path):
    book = server.add_book("The Bridge", pdf=PDF)
    server.users["alice"].password = "changed"  # pragma: allowlist secret
    d = load_deliverable(job.dir)
    client = CalibreWeb(Http(server.url), "alice", "secret-pw")
    key = ReceiptKey("alice", server.url, f"book:{book}", d.rendition_id)
    store = ReceiptStore(tmp_path / "r.sqlite")
    receipt = run_delivery(store, key, d, RecordDestination(client, book, d), FAST)
    assert receipt.state is State.FAILED and "Wrong Username" in receipt.last_error
    receipt = deliver(server, job, tmp_path, 99)
    assert receipt.state is State.FAILED and "does not exist" in receipt.last_error


def test_crash_after_the_write_then_restart_reconciles_without_a_second_write(
    server, job, tmp_path
):
    book = server.add_book("The Bridge", pdf=PDF)
    with pytest.raises(Crash):
        deliver(server, job, tmp_path, book, http=DropUploadResponse)
    d = load_deliverable(job.dir)
    store = ReceiptStore(tmp_path / "receipts.sqlite")
    interrupted = store.get(ReceiptKey("alice", server.url, f"book:{book}", d.rendition_id))
    assert (interrupted.state, interrupted.attempts) == (State.PENDING, 1)
    receipt = deliver(server, job, tmp_path, book)  # a fresh client and store
    assert (receipt.state, receipt.attempts, server.uploads) == (State.SAVED, 1, 1)
    assert set(server.books[book].formats) == {"PDF", "EPUB"}


def test_lost_answer_is_reconciled_in_the_same_run(server, job, tmp_path):
    class Lost(DropUploadResponse):
        error = TransportError

    book = server.add_book("The Bridge", pdf=PDF)
    receipt = deliver(server, job, tmp_path, book, http=Lost)
    assert (receipt.state, receipt.attempts, server.uploads) == (State.SAVED, 1, 1)


def test_queued_write_stays_submitted_then_saves_on_readback(server, job, tmp_path):
    server.delay = 1.0  # like Calibre-Web Automated's ingest queue
    book = server.add_book("The Bridge", pdf=PDF)
    receipt = deliver(server, job, tmp_path, book, policy=Policy(wait=0.1))
    assert receipt.state is State.SUBMITTED
    assert wait_until(lambda: "EPUB" in server.books[book].formats)
    receipt = deliver(server, job, tmp_path, book, policy=Policy(wait=0.1))
    assert (receipt.state, receipt.attempts, server.uploads) == (State.SAVED, 1, 1)


def test_write_that_never_lands_becomes_ambiguous_and_needs_an_explicit_retry(
    server, job, tmp_path
):
    server.drop_uploads = True
    book = server.add_book("The Bridge", pdf=PDF)
    slow = Policy(wait=0.1, ambiguous_after=60)
    assert deliver(server, job, tmp_path, book, policy=slow).state is State.SUBMITTED
    assert deliver(server, job, tmp_path, book, policy=slow).state is State.SUBMITTED
    late = Policy(wait=0.1, ambiguous_after=0)
    receipt = deliver(server, job, tmp_path, book, policy=late)
    assert receipt.state is State.AMBIGUOUS and "check the catalog" in receipt.last_error
    assert deliver(server, job, tmp_path, book, policy=late).state is State.AMBIGUOUS
    assert server.uploads == 1
    server.drop_uploads = False
    retry = Policy(wait=0.5, ambiguous_after=0, retry_ambiguous=True)
    receipt = deliver(server, job, tmp_path, book, policy=retry)
    assert (receipt.state, receipt.attempts, server.uploads) == (State.SAVED, 2, 2)


def test_competing_write_is_reported_not_replaced(server, job, tmp_path):
    rival = make_epub(tmp_path / "rival.epub", "urn:uuid:rival").read_bytes()
    server.competitor = rival  # another client's EPUB lands on the record instead
    book = server.add_book("The Bridge", pdf=PDF)
    receipt = deliver(server, job, tmp_path, book)
    assert receipt.state is State.CONFLICT
    assert (server.uploads, server.books[book].formats["EPUB"]) == (1, rival)
    assert deliver(server, job, tmp_path, book).state is State.CONFLICT
    assert server.uploads == 1


def test_concurrent_saves_of_the_same_key_write_once(server, job, tmp_path):
    server.delay = 0.3
    book = server.add_book("The Bridge", pdf=PDF)
    results = []

    def run():
        results.append(deliver(server, job, tmp_path, book, policy=Policy(wait=3)))

    threads = [threading.Thread(target=run) for _ in range(3)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert [r.state for r in results] == [State.SAVED] * 3
    assert server.uploads == 1


def test_new_book_is_found_by_rendition_not_by_title(server, job, tmp_path):
    other = make_epub(tmp_path / "other.epub", "urn:uuid:other-edition").read_bytes()
    edition = server.add_book("The Bridge", pdf=PDF, epub=other)
    receipt = deliver(server, job, tmp_path)
    assert receipt.state is State.SAVED and receipt.remote_book_id != edition
    assert server.books[edition].formats["EPUB"] == other
    again = deliver(server, job, tmp_path)
    assert (again.state, again.remote_book_id, server.uploads) == (
        State.SAVED,
        receipt.remote_book_id,
        1,
    )
    assert len(server.books) == 2


def test_record_formats_parsing():
    page = (
        '<div class="formats meta-chip"><span>File sizes</span><span class="meta-value">'
        '<a href="/cw/download/4/pdf/4.pdf" class="meta-pill meta-link">PDF (4 KiB)</a>'
        '<span class="meta-pill">EPUB (1 KiB)</span></span></div>'
    )
    assert record_formats(page, 4) == {"PDF", "EPUB"}
    assert record_formats("<html>no list</html>", 4) is None


def test_server_links_resolve_under_a_prefixed_base_url():
    http = Http("https://books.example/calibre/")
    assert http.relative("/calibre/admin/book/4") == "/admin/book/4"
    assert http.relative("https://books.example/calibre/tasks") == "/tasks"
    assert Http("http://cat:8083").relative("/admin/book/4") == "/admin/book/4"
    with pytest.raises(Exception, match="no credentials"):
        Http("http://user:pw@cat:8083")  # pragma: allowlist secret
