"""Calibre-Web adapter: attach an EPUB to a catalog record, or add it as a new book.

The client drives the same web forms a browser does, as the requesting user, so the
catalog's own upload and download permissions apply. Routes and forms come from the
pinned source (``cps/web.py``, ``cps/editbooks.py`` and the ``detail.html`` template):

- ``GET /login`` and ``POST /login`` (username, password, csrf_token);
- ``GET /book/<id>``: the record page, whose ``formats meta-chip`` lists every format;
- ``GET /download/<id>/<format>``: one format's file (needs the download role);
- ``POST /upload`` with ``btn-upload-format`` and ``book_id``: "Upload Format" on the
  edit page, which adds a format to a record; with ``btn-upload``: a new book;
- ``GET /ajax/listbooks?search=``: the book table's JSON, used to find new books.

Uploading a format the record already has replaces it on the server, so the adapter
never uploads unless it has just read the record's formats and found no EPUB, and it
reads them again afterwards. Calibre-Web Automated queues format uploads to its ingest
service, so a write shows up seconds later: every write is confirmed by a readback.
"""

from __future__ import annotations

import html
import http.cookiejar
import json
import logging
import re
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import BinaryIO, Literal, Protocol

from ..job import sha256_file
from . import Deliverable, DeliveryError, EpubIdentity, epub_identity
from .receipts import Receipt, ReceiptKey, ReceiptStore, State

log = logging.getLogger(__name__)

# The release the routes and forms above were read from and the adapter was tested on.
# Calibre-Web Automated is a Calibre-Web fork that keeps these web routes.
QUALIFIED_VERSION = "calibre-web-automated v4.0.8"

_PAGE_LIMIT = 16 << 20
_DOWNLOAD_LIMIT = 2 << 30


class CatalogError(Exception):
    """The catalog answered and refused or could not do what was asked: nothing was written."""

    def __init__(self, reason: str):
        super().__init__(reason)
        self.reason = reason


class TransportError(Exception):
    """No complete answer arrived (connection lost, timeout): a write may have landed."""

    def __init__(self, reason: str):
        super().__init__(reason)
        self.reason = reason


@dataclass(frozen=True)
class Response:
    status: int
    url: str
    body: bytes

    @property
    def text(self) -> str:
        return self.body.decode("utf-8", "replace")

    @property
    def path(self) -> str:
        return urllib.parse.urlsplit(self.url).path.rstrip("/")


class Http:
    """A cookie-keeping HTTP session. Tests replace ``request`` to inject faults."""

    def __init__(self, base_url: str, *, timeout: float = 120.0):
        parts = urllib.parse.urlsplit(base_url)
        if parts.scheme not in ("http", "https") or not parts.netloc:
            raise DeliveryError("the catalog URL must be http:// or https://")
        if parts.username or parts.password:
            raise DeliveryError("put no credentials in the catalog URL; use --password-env")
        self.base = base_url.rstrip("/")
        self.timeout = timeout
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar())
        )

    def relative(self, location: str) -> str:
        """A server-sent link as a path under the base URL (which may have a prefix)."""
        path = urllib.parse.urlsplit(location).path
        prefix = urllib.parse.urlsplit(self.base).path
        return path[len(prefix) :] if prefix and path.startswith(prefix + "/") else path

    def request(
        self,
        method: str,
        path: str,
        *,
        body: bytes | None = None,
        headers: dict[str, str] | None = None,
        sink: BinaryIO | None = None,
    ) -> Response:
        """Send one request, following redirects. With ``sink``, a 200 body is streamed
        into it instead of being returned."""
        req = urllib.request.Request(
            self.base + path, data=body, method=method, headers=headers or {}
        )
        try:
            try:
                resp = self.opener.open(req, timeout=self.timeout)
            except urllib.error.HTTPError as exc:
                resp = exc
            with resp:
                status = resp.status
                if sink is not None and status == 200:
                    _copy(resp, sink, _DOWNLOAD_LIMIT)
                    return Response(status, resp.geturl(), b"")
                data = resp.read(_PAGE_LIMIT + 1)
                if len(data) > _PAGE_LIMIT:
                    raise CatalogError("the catalog sent an oversized page")
                return Response(status, resp.geturl(), data)
        except (urllib.error.URLError, OSError) as exc:
            raise TransportError(f"{method} {path.split('?')[0]}: {type(exc).__name__}") from exc


def _copy(src: BinaryIO, sink: BinaryIO, limit: int) -> None:
    total = 0
    while chunk := src.read(1 << 20):
        total += len(chunk)
        if total > limit:
            raise CatalogError("the catalog file is larger than the download limit")
        sink.write(chunk)


def _multipart(
    fields: dict[str, str], file_field: str, filename: str, data: bytes
) -> tuple[bytes, dict[str, str]]:
    boundary = uuid.uuid4().hex
    parts = [
        f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n'.encode()
        for name, value in fields.items()
    ]
    parts.append(
        f'--{boundary}\r\nContent-Disposition: form-data; name="{file_field}"; '
        f'filename="{filename}"\r\nContent-Type: application/epub+zip\r\n\r\n'.encode()
    )
    body = b"".join([*parts, data, f"\r\n--{boundary}--\r\n".encode()])
    return body, {"Content-Type": f"multipart/form-data; boundary={boundary}"}


_CSRF_INPUT = re.compile(r"<input\b[^>]*\bname=\"csrf_token\"[^>]*>")
_VALUE = re.compile(r"\bvalue=\"([^\"]*)\"")
_FLASH_DANGER = re.compile(r"<div\b[^>]*\bid=\"flash_danger\"[^>]*>(.*?)</div>", re.S)
_FORMAT_CHIP = re.compile(r"class=\"formats meta-chip\"(.*?)</div>", re.S)
_PILL = re.compile(r"meta-pill[^>]*>\s*([A-Za-z0-9_]+)\s*\(")


def csrf_token(page: str) -> str:
    tag = _CSRF_INPUT.search(page)
    match = _VALUE.search(tag.group(0)) if tag else None
    if not match:
        raise CatalogError("the catalog page carries no CSRF token")
    return html.unescape(match.group(1))


def flash_errors(page: str) -> list[str]:
    return [html.unescape(re.sub(r"<[^>]+>", " ", m)).strip() for m in _FLASH_DANGER.findall(page)]


def record_formats(page: str, book_id: int) -> set[str] | None:
    """Formats on a record page, or None when the page shows no format list at all."""
    chip = _FORMAT_CHIP.search(page)
    if not chip:
        return None
    found = re.findall(rf"/download/{book_id}/([A-Za-z0-9_]+)", chip.group(1))
    found += _PILL.findall(chip.group(1))
    return {f.upper() for f in found}


@dataclass(frozen=True)
class Record:
    book_id: int
    formats: set[str]
    csrf: str


class CalibreWeb:
    def __init__(self, http: Http, user: str, password: str):
        self.http = http
        self.user = user
        self._password = password

    def login(self) -> None:
        page = self.http.request("GET", "/login")
        if page.status != 200:
            raise CatalogError(f"the login page answered {page.status}")
        token = csrf_token(page.text)
        form = {"username": self.user, "password": self._password, "csrf_token": token}
        resp = self.http.request(
            "POST",
            "/login",
            body=urllib.parse.urlencode(form).encode(),
            headers={"Content-Type": "application/x-www-form-urlencoded"},
        )
        if resp.status != 200 or resp.path.endswith("/login"):
            detail = "; ".join(flash_errors(resp.text)) or f"status {resp.status}"
            raise CatalogError(f"login was refused: {detail}")
        log.info("logged in to the catalog")

    def _get_page(self, path: str) -> Response:
        resp = self.http.request("GET", path)
        if resp.path.endswith("/login"):
            raise CatalogError("the catalog session expired")
        return resp

    def record(self, book_id: int) -> Record:
        resp = self._get_page(f"/book/{book_id}")
        if resp.status == 403:
            raise CatalogError(f"no permission to view record {book_id}")
        if resp.status != 200 or not resp.path.endswith(f"/book/{book_id}"):
            raise CatalogError(f"record {book_id} does not exist or is not visible")
        formats = record_formats(resp.text, book_id)
        if formats is None:
            raise CatalogError(f"cannot read the formats of record {book_id}")
        return Record(book_id, formats, csrf_token(resp.text))

    def download_identity(self, book_id: int) -> tuple[EpubIdentity, str]:
        """Download a record's EPUB and return its identifiers and sha256."""
        with tempfile.NamedTemporaryFile(prefix="quire-deliver-", suffix=".epub") as tmp:
            resp = self.http.request("GET", f"/download/{book_id}/epub", sink=tmp)
            if resp.status != 200 or resp.path.endswith("/login"):
                raise CatalogError(f"cannot download the EPUB (status {resp.status})")
            tmp.flush()
            digest = sha256_file(Path(tmp.name))
            try:
                identity = epub_identity(Path(tmp.name))
            except DeliveryError as exc:
                raise CatalogError(f"the record's EPUB is unreadable: {exc}") from exc
        return identity, digest

    def _upload(self, fields: dict[str, str], file_field: str, epub: Path, name: str) -> None:
        body, headers = _multipart(fields, file_field, name, epub.read_bytes())
        resp = self.http.request("POST", "/upload", body=body, headers=headers)
        if resp.status == 403:
            raise CatalogError(f"user {self.user!r} has no upload permission on this catalog")
        if resp.status == 404:
            raise CatalogError("uploads are disabled on this catalog")
        if resp.status != 200:
            raise CatalogError(f"the upload was refused (status {resp.status})")
        if resp.path.endswith("/login"):
            raise CatalogError("the catalog session expired before the upload")
        page = resp
        try:
            location = json.loads(resp.text).get("location")
        except ValueError:
            location = None
        if location:  # the page that shows the upload's flashed errors, if any
            page = self.http.request("GET", self.http.relative(location))
        errors = flash_errors(page.text)
        if errors:
            raise CatalogError("the catalog rejected the upload: " + "; ".join(errors))

    def attach_epub(self, record: Record, epub: Path, name: str) -> None:
        fields = {"csrf_token": record.csrf, "book_id": str(record.book_id)}
        self._upload(fields, "btn-upload-format", epub, name)

    def upload_new(self, epub: Path, name: str) -> None:
        index = self._get_page("/")
        self._upload({"csrf_token": csrf_token(index.text)}, "btn-upload", epub, name)

    def search(self, query: str | None) -> list[int]:
        """Ids of the newest records matching ``query`` in the book table."""
        params = {"offset": "0", "limit": "50"}
        if query:
            params["search"] = query
        resp = self._get_page("/ajax/listbooks?" + urllib.parse.urlencode(params))
        try:
            rows = json.loads(resp.text)["rows"]
            return [int(row["id"]) for row in rows]
        except (ValueError, KeyError, TypeError) as exc:
            reason = f"the book table answer was unreadable (status {resp.status})"
            raise CatalogError(reason) from exc


# --------------------------------------------------------------------------- delivery flow


@dataclass(frozen=True)
class Located:
    kind: Literal["found", "conflict", "absent"]
    book_id: int | None = None
    reason: str = ""


class Destination(Protocol):
    def open(self) -> None: ...
    def locate(self) -> Located: ...
    def write(self) -> None: ...


@dataclass(frozen=True)
class Policy:
    wait: float = 60.0  # how long one run polls the catalog for a write to show up
    ambiguous_after: float = 900.0  # an unconfirmed write older than this becomes ambiguous
    retry_ambiguous: bool = False  # the caller checked the catalog: allow a new write
    lock_timeout: float = 600.0


def upload_name(deliverable: Deliverable) -> str:
    """A file name that carries no book text."""
    return f"quire-{deliverable.sha256[:16]}.epub"


class RecordDestination:
    """An existing catalog record, named explicitly by id."""

    def __init__(self, client: CalibreWeb, book_id: int, deliverable: Deliverable):
        self.client, self.book_id, self.deliverable = client, book_id, deliverable
        self._record: Record | None = None

    def open(self) -> None:
        self.client.login()

    def locate(self) -> Located:
        record = self._record = self.client.record(self.book_id)
        if "EPUB" not in record.formats:
            return Located("absent", self.book_id)
        try:
            identity, digest = self.client.download_identity(self.book_id)
        except CatalogError as exc:
            reason = f"record {self.book_id} already has an EPUB that could not be compared"
            return Located("conflict", self.book_id, f"{reason} ({exc.reason}); left untouched")
        if self.deliverable.matches(identity, digest):
            return Located("found", self.book_id)
        reason = f"record {self.book_id} already has a different EPUB; left untouched"
        return Located("conflict", self.book_id, reason)

    def write(self) -> None:
        assert self._record is not None, "locate() reads the record before every write"
        self.client.attach_epub(self._record, self.deliverable.epub, upload_name(self.deliverable))


class NewBookDestination:
    """A new catalog record created by uploading the EPUB."""

    def __init__(self, client: CalibreWeb, deliverable: Deliverable):
        self.client, self.deliverable = client, deliverable

    def open(self) -> None:
        self.client.login()

    def locate(self) -> Located:
        ids = find_rendition(self.client, self.deliverable)
        return Located("found", ids[0]) if ids else Located("absent")

    def write(self) -> None:
        self.client.upload_new(self.deliverable.epub, upload_name(self.deliverable))


def find_rendition(client: CalibreWeb, deliverable: Deliverable) -> list[int]:
    """Records holding this rendition, among the newest matching its title."""
    matches = []
    for book_id in client.search(deliverable.title):
        try:
            if "EPUB" not in client.record(book_id).formats:
                continue
            identity, digest = client.download_identity(book_id)
        except CatalogError:
            continue
        if deliverable.matches(identity, digest):
            matches.append(book_id)
    return sorted(matches)


def run_delivery(
    store: ReceiptStore,
    key: ReceiptKey,
    deliverable: Deliverable,
    dest: Destination,
    policy: Policy | None = None,
) -> Receipt:
    """Deliver once under the key's lock: reconcile the destination, write only when the
    catalog shows no EPUB and no earlier write is unaccounted for, then confirm by readback.
    """
    policy = policy or Policy()
    with store.claim(key, deliverable.sha256, timeout=policy.lock_timeout) as receipt:
        receipt.epub_sha256 = deliverable.sha256
        try:
            dest.open()
            seen = dest.locate()
            if seen.kind != "absent":
                return _settle(store, receipt, seen)
            if receipt.write_uncertain and not (
                receipt.state is State.AMBIGUOUS and policy.retry_ambiguous
            ):
                if receipt.state is not State.AMBIGUOUS:
                    seen = _poll(dest, policy.wait)
                    if seen.kind != "absent":
                        return _settle(store, receipt, seen)
                return _unconfirmed(store, receipt, policy)
            seen = dest.locate()  # the last look before writing
            if seen.kind != "absent":
                return _settle(store, receipt, seen)
        except (CatalogError, TransportError) as exc:
            return _refused(store, receipt, exc.reason)

        receipt.state = State.PENDING
        receipt.attempts += 1
        receipt.last_attempt_at = time.time()
        receipt.last_error = None
        store.save(receipt)
        log.info("writing to the catalog, attempt %d", receipt.attempts)
        try:
            dest.write()
        except CatalogError as exc:
            receipt.state, receipt.last_error = State.FAILED, exc.reason
            return store.save(receipt)
        except TransportError as exc:
            receipt.last_error = f"no answer to the write: {exc.reason}"
            store.save(receipt)
        else:
            receipt.state = State.SUBMITTED
            store.save(receipt)
        try:
            seen = _poll(dest, policy.wait)
        except (CatalogError, TransportError) as exc:  # _poll only raises on its last look
            receipt.last_error = exc.reason
            seen = Located("absent")
        if seen.kind != "absent":
            return _settle(store, receipt, seen)
        return _unconfirmed(store, receipt, policy)


def _poll(dest: Destination, wait: float) -> Located:
    """Read the destination until it shows something or ``wait`` seconds pass."""
    deadline = time.monotonic() + wait
    interval = min(2.0, max(0.05, wait / 20))
    while True:
        last = time.monotonic() >= deadline
        try:
            seen = dest.locate()
        except (CatalogError, TransportError):
            if last:
                raise
            seen = Located("absent")
        if seen.kind != "absent" or last:
            return seen
        time.sleep(interval)


def _settle(store: ReceiptStore, receipt: Receipt, seen: Located) -> Receipt:
    receipt.remote_book_id = seen.book_id
    if seen.kind == "found":
        receipt.state, receipt.last_error = State.SAVED, None
        receipt.last_confirmed_at = time.time()
    else:
        receipt.state, receipt.last_error = State.CONFLICT, seen.reason
    log.info("delivery %s, record %s", receipt.state.value, seen.book_id)
    return store.save(receipt)


def _unconfirmed(store: ReceiptStore, receipt: Receipt, policy: Policy) -> Receipt:
    """No readback found the rendition: keep waiting, or say the outcome is unknown."""
    age = time.time() - (receipt.last_attempt_at or time.time())
    if age >= policy.ambiguous_after or receipt.state is State.AMBIGUOUS:
        receipt.state = State.AMBIGUOUS
        receipt.last_error = receipt.last_error or "no readback found the book: check the catalog"
    return store.save(receipt)


def _refused(store: ReceiptStore, receipt: Receipt, reason: str) -> Receipt:
    """A read or login failed before any new write. An earlier unconfirmed write keeps its
    state, so it is never mistaken for a confirmed failure that is safe to repeat."""
    if not receipt.write_uncertain:
        receipt.state = State.FAILED
    receipt.last_error = reason
    return store.save(receipt)
