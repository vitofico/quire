"""Delivery receipts: one durable row per (owner, catalog instance, destination, rendition).

The row says what the last delivery of that key is known to have achieved. A per-key lock
file serializes deliveries of the same key across threads and processes; the operating
system drops the lock when its holder dies, so a crashed delivery never blocks a retry.
"""

from __future__ import annotations

import contextlib
import dataclasses
import enum
import fcntl
import hashlib
import os
import sqlite3
import time
from collections.abc import Iterator
from dataclasses import dataclass
from pathlib import Path

RECEIPTS_FILE = "deliveries.sqlite"

_SCHEMA = """
CREATE TABLE IF NOT EXISTS receipts (
    owner TEXT NOT NULL,
    instance TEXT NOT NULL,
    destination TEXT NOT NULL,
    rendition_id TEXT NOT NULL,
    epub_sha256 TEXT NOT NULL,
    remote_book_id INTEGER,
    state TEXT NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    last_attempt_at REAL,
    last_confirmed_at REAL,
    last_error TEXT,
    created_at REAL NOT NULL,
    updated_at REAL NOT NULL,
    PRIMARY KEY (owner, instance, destination, rendition_id)
)
"""


class State(enum.StrEnum):
    PENDING = "pending"  # claimed; with attempts > 0 a write may have reached the catalog
    SUBMITTED = "submitted"  # the catalog accepted the file; not yet seen in a readback
    SAVED = "saved"  # a readback found this rendition on the destination
    CONFLICT = "conflict"  # a different EPUB is on the destination; left untouched
    FAILED = "failed"  # confirmed not written; safe to retry
    AMBIGUOUS = "ambiguous"  # a write may or may not have landed: check the catalog

    @property
    def exit_code(self) -> int:
        return {"saved": 0, "failed": 1, "conflict": 3}.get(self.value, 2)


class Busy(Exception):
    """Another delivery of the same key holds the lock; ``receipt`` is its in-flight row."""

    def __init__(self, receipt: Receipt | None):
        super().__init__("another delivery of this book to this destination is in progress")
        self.receipt = receipt


@dataclass(frozen=True)
class ReceiptKey:
    owner: str
    instance: str
    destination: str  # "book:<id>" for an existing record, "new" for a new-book request
    rendition_id: str

    def digest(self) -> str:
        parts = "\x1f".join((self.owner, self.instance, self.destination, self.rendition_id))
        return hashlib.sha256(parts.encode()).hexdigest()[:32]


@dataclass
class Receipt:
    key: ReceiptKey
    epub_sha256: str
    state: State = State.PENDING
    remote_book_id: int | None = None
    attempts: int = 0
    last_attempt_at: float | None = None
    last_confirmed_at: float | None = None
    last_error: str | None = None

    @property
    def write_uncertain(self) -> bool:
        """True when an earlier write may have reached the catalog without being confirmed."""
        if self.state is State.PENDING:
            return self.attempts > 0
        return self.state in (State.SUBMITTED, State.AMBIGUOUS)


_FIELDS = (
    "epub_sha256",
    "remote_book_id",
    "state",
    "attempts",
    "last_attempt_at",
    "last_confirmed_at",
    "last_error",
)


class ReceiptStore:
    def __init__(self, path: Path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.lock_dir = self.path.with_name(self.path.name + ".locks")
        self.lock_dir.mkdir(exist_ok=True)
        # Switching a new file to WAL fails at once with "database is locked" when another
        # process does the same, without waiting out the busy timeout: serialize the setup.
        fd = os.open(self.lock_dir / "setup.lock", os.O_CREAT | os.O_RDWR, 0o600)
        try:
            fcntl.flock(fd, fcntl.LOCK_EX)
            with self._connect() as db:
                db.execute("PRAGMA journal_mode=WAL")
                db.execute(_SCHEMA)
        finally:
            os.close(fd)

    @contextlib.contextmanager
    def _connect(self) -> Iterator[sqlite3.Connection]:
        db = sqlite3.connect(self.path, timeout=30, isolation_level=None)
        try:
            yield db
        finally:
            db.close()

    def get(self, key: ReceiptKey) -> Receipt | None:
        with self._connect() as db:
            return self._select(db, key)

    def _select(self, db: sqlite3.Connection, key: ReceiptKey) -> Receipt | None:
        row = db.execute(
            f"SELECT {', '.join(_FIELDS)} FROM receipts WHERE owner = ? AND instance = ? "
            "AND destination = ? AND rendition_id = ?",
            dataclasses.astuple(key),
        ).fetchone()
        if row is None:
            return None
        values = dict(zip(_FIELDS, row, strict=True))
        values["state"] = State(values["state"])
        return Receipt(key, **values)

    def save(self, receipt: Receipt) -> Receipt:
        now = time.time()
        values = [getattr(receipt, f) for f in _FIELDS]
        values[_FIELDS.index("state")] = receipt.state.value
        with self._connect() as db:
            db.execute("BEGIN IMMEDIATE")
            db.execute(
                f"UPDATE receipts SET {', '.join(f'{f} = ?' for f in _FIELDS)}, updated_at = ? "
                "WHERE owner = ? AND instance = ? AND destination = ? AND rendition_id = ?",
                (*values, now, *dataclasses.astuple(receipt.key)),
            )
            db.execute("COMMIT")
        return receipt

    def _get_or_create(self, key: ReceiptKey, epub_sha256: str) -> Receipt:
        with self._connect() as db:
            db.execute("BEGIN IMMEDIATE")
            receipt = self._select(db, key)
            if receipt is None:
                now = time.time()
                db.execute(
                    "INSERT INTO receipts (owner, instance, destination, rendition_id, "
                    "epub_sha256, state, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    (*dataclasses.astuple(key), epub_sha256, State.PENDING.value, now, now),
                )
                receipt = Receipt(key, epub_sha256)
            db.execute("COMMIT")
        return receipt

    @contextlib.contextmanager
    def claim(
        self, key: ReceiptKey, epub_sha256: str, *, timeout: float = 600.0
    ) -> Iterator[Receipt]:
        """Hold the key's lock for the duration of one delivery and yield its receipt.

        A second delivery of the same key waits up to ``timeout`` seconds, then raises
        Busy carrying the in-flight receipt.
        """
        fd = os.open(self.lock_dir / f"{key.digest()}.lock", os.O_RDWR | os.O_CREAT, 0o600)
        try:
            deadline = time.monotonic() + timeout
            while True:
                try:
                    fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    break
                except BlockingIOError:
                    if time.monotonic() >= deadline:
                        raise Busy(self.get(key)) from None
                    time.sleep(0.05)
            yield self._get_or_create(key, epub_sha256)
        finally:
            os.close(fd)  # closing the descriptor releases the lock
