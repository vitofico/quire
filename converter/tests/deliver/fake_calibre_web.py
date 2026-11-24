"""A small in-process stand-in for the Calibre-Web routes the adapter uses.

It reproduces the behaviour that matters for delivery: session login with a CSRF token,
the record page's format list, role checks (403), format uploads that replace an existing
format (as the real server does), optional asynchronous processing (as Calibre-Web
Automated's ingest queue does), and the book table JSON.
"""

from __future__ import annotations

import html
import json
import re
import secrets
import tempfile
import threading
import time
import urllib.parse
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from quire_convert.deliver import epub_identity


@dataclass
class Book:
    title: str
    formats: dict[str, bytes] = field(default_factory=dict)


@dataclass
class User:
    password: str
    roles: set[str] = field(default_factory=lambda: {"upload", "download"})


class FakeCalibreWeb:
    def __init__(self):
        self.users = {"alice": User("secret-pw")}
        self.books: dict[int, Book] = {}
        self.sessions: dict[str, dict] = {}
        self.uploads = 0  # POST /upload requests that reached the handler
        self.delay: float | None = None  # process format uploads this many seconds later
        self.drop_uploads = False  # accept uploads but never apply them
        self.competitor: bytes | None = None  # a rival EPUB that lands instead of ours
        self.lock = threading.Lock()
        self._server = ThreadingHTTPServer(("127.0.0.1", 0), _handler(self))
        self._thread = threading.Thread(
            target=self._server.serve_forever, args=(0.05,), daemon=True
        )

    @property
    def url(self) -> str:
        return f"http://127.0.0.1:{self._server.server_address[1]}"

    def add_book(self, title: str, **formats: bytes) -> int:
        with self.lock:
            book_id = max(self.books, default=0) + 1
            self.books[book_id] = Book(title, {k.upper(): v for k, v in formats.items()})
            return book_id

    def __enter__(self):
        self._thread.start()
        return self

    def __exit__(self, *exc):
        self._server.shutdown()
        self._server.server_close()

    # ------------------------------------------------------------------ server logic

    def apply_format(self, book_id: int, fmt: str, data: bytes) -> None:
        def apply():
            with self.lock:
                self.books[book_id].formats[fmt] = data

        if self.drop_uploads:
            return
        if self.competitor is not None:
            data = self.competitor
        if self.delay:
            threading.Timer(self.delay, apply).start()
        else:
            apply()

    def add_new(self, data: bytes) -> None:
        with tempfile.NamedTemporaryFile(suffix=".epub") as tmp:
            tmp.write(data)
            tmp.flush()
            title = epub_identity(Path(tmp.name)).title or "Unknown"
        with self.lock:
            book_id = max(self.books, default=0) + 1
            self.books[book_id] = Book(title)
        self.apply_format(book_id, "EPUB", data)


def _page(csrf: str, body: str = "", flash: str = "") -> str:
    danger = f'<div id="flash_danger" class="alert alert-danger">{html.escape(flash)}</div>'
    return (
        "<html><body>"
        + (danger if flash else "")
        + f'<input type="hidden" name="csrf_token" value="{csrf}">'
        + body
        + "</body></html>"
    )


def _multipart(body: bytes, content_type: str) -> tuple[dict[str, str], dict[str, bytes]]:
    boundary = re.search(r"boundary=([^;]+)", content_type).group(1).encode()
    fields, files = {}, {}
    for part in body.split(b"--" + boundary)[1:-1]:
        head, _, data = part.strip(b"\r\n").partition(b"\r\n\r\n")
        name = re.search(rb'name="([^"]+)"', head).group(1).decode()
        if b"filename=" in head:
            files[name] = data
        else:
            fields[name] = data.decode()
    return fields, files


def _handler(app: FakeCalibreWeb):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        # --------------------------------------------------------------- helpers

        def _session(self) -> tuple[str, dict]:
            cookie = self.headers.get("Cookie", "")
            match = re.search(r"session=(\w+)", cookie)
            sid = match.group(1) if match and match.group(1) in app.sessions else None
            if sid is None:
                sid = secrets.token_hex(8)
                app.sessions[sid] = {"csrf": secrets.token_hex(8), "user": None}
            return sid, app.sessions[sid]

        def _send(self, status: int, body: str | bytes, sid: str, ctype="text/html") -> None:
            data = body.encode() if isinstance(body, str) else body
            self.send_response(status)
            self.send_header("Set-Cookie", f"session={sid}; Path=/")
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def _redirect(self, location: str, sid: str) -> None:
            self.send_response(302)
            self.send_header("Set-Cookie", f"session={sid}; Path=/")
            self.send_header("Location", location)
            self.send_header("Content-Length", "0")
            self.end_headers()

        def _body(self) -> bytes:
            return self.rfile.read(int(self.headers.get("Content-Length", 0)))

        # --------------------------------------------------------------- routes

        def do_GET(self):
            sid, session = self._session()
            url = urllib.parse.urlsplit(self.path)
            if url.path == "/login":
                flash = session.pop("flash", "")
                return self._send(200, _page(session["csrf"], "<form>login</form>", flash), sid)
            user = app.users.get(session["user"] or "")
            if user is None:
                return self._redirect("/login", sid)
            if url.path in ("/", "/admin/book/1", "/tasks"):
                return self._send(200, _page(session["csrf"], flash=session.pop("flash", "")), sid)
            if match := re.fullmatch(r"/book/(\d+)", url.path):
                book = app.books.get(int(match.group(1)))
                if book is None:
                    return self._redirect("/", sid)
                pills = "".join(
                    f'<a href="/download/{match.group(1)}/{f.lower()}/x.{f.lower()}" '
                    f'class="meta-pill meta-link">{f} (1 KiB)</a>'
                    for f in sorted(book.formats)
                )
                chip = f'<div class="formats meta-chip"><span>{pills}</span></div>'
                return self._send(200, _page(session["csrf"], chip), sid)
            if match := re.fullmatch(r"/download/(\d+)/(\w+)(/.*)?", url.path):
                if "download" not in user.roles:
                    return self._send(403, "forbidden", sid)
                book = app.books.get(int(match.group(1)))
                data = book.formats.get(match.group(2).upper()) if book else None
                if data is None:
                    return self._send(404, "missing", sid)
                return self._send(200, data, sid, "application/epub+zip")
            if url.path == "/ajax/listbooks":
                query = urllib.parse.parse_qs(url.query).get("search", [""])[0].lower()
                rows = [
                    {"id": i, "title": b.title}
                    for i, b in sorted(app.books.items(), reverse=True)
                    if query in b.title.lower()
                ]
                return self._send(200, json.dumps({"rows": rows}), sid, "application/json")
            return self._send(404, "not found", sid)

        def do_POST(self):
            sid, session = self._session()
            body = self._body()
            if self.path == "/login":
                form = dict(urllib.parse.parse_qsl(body.decode()))
                user = app.users.get(form.get("username", ""))
                if form.get("csrf_token") != session["csrf"]:
                    return self._send(400, "The CSRF token is missing.", sid)
                if user is None or user.password != form.get("password"):
                    session["flash"] = "Wrong Username or Password"
                    return self._redirect("/login", sid)
                session["user"] = form["username"]
                return self._redirect("/", sid)
            if self.path != "/upload":
                return self._send(404, "not found", sid)
            user = app.users.get(session["user"] or "")
            if user is None:
                return self._redirect("/login", sid)
            if "upload" not in user.roles:
                return self._send(403, "forbidden", sid)
            fields, files = _multipart(body, self.headers["Content-Type"])
            if fields.get("csrf_token") != session["csrf"]:
                return self._send(400, "The CSRF token is missing.", sid)
            with app.lock:
                app.uploads += 1
            if "btn-upload-format" in files:
                book_id = int(fields["book_id"])
                if book_id not in app.books:
                    session["flash"] = "Cannot upload format: Book no longer exists in library"
                    return self._send(200, json.dumps({"location": "/"}), sid)
                app.apply_format(book_id, "EPUB", files["btn-upload-format"])
                return self._send(200, json.dumps({"location": "/admin/book/1"}), sid)
            app.add_new(files["btn-upload"])
            return self._send(200, json.dumps({"location": "/tasks"}), sid)

    return Handler


def wait_until(predicate, timeout: float = 5.0) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return True
        time.sleep(0.02)
    return predicate()
