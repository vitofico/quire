"""The ``quire-convert deliver`` subcommand."""

from __future__ import annotations

import argparse
import os
import sys
import urllib.parse
from pathlib import Path

from ..pipeline import UsageError
from . import DeliveryError, load_deliverable
from .calibre_web import (
    QUALIFIED_VERSION,
    CalibreWeb,
    Http,
    NewBookDestination,
    Policy,
    RecordDestination,
    run_delivery,
)
from .cwa_ingest import IngestDestination, IngestFolder
from .receipts import RECEIPTS_FILE, Busy, ReceiptKey, ReceiptStore, State


def add_arguments(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("job", type=Path, help="job directory or its manifest.json")
    parser.add_argument("--to", choices=["calibre-web", "cwa-ingest"], required=True)
    parser.add_argument("--url", required=True, help="catalog base URL")
    parser.add_argument("--user", required=True, help="catalog user the book is saved as")
    parser.add_argument(
        "--password-env", required=True, metavar="VAR", help="environment variable to read"
    )
    target = parser.add_mutually_exclusive_group()
    target.add_argument("--book-id", type=int, help="attach the EPUB to this record")
    target.add_argument("--new", action="store_true", help="add the EPUB as a new book")
    parser.add_argument("--instance", help="catalog name in receipts (default: the URL)")
    parser.add_argument("--receipts", type=Path, help=f"default: <job root>/{RECEIPTS_FILE}")
    parser.add_argument("--allow-review", action="store_true", help="also deliver Review books")
    parser.add_argument("--folder", type=Path, help="cwa-ingest: the watched ingest folder")
    parser.add_argument("--staging", type=Path, help="cwa-ingest: staging folder, same disk")
    parser.add_argument("--chown", metavar="UID:GID", help="cwa-ingest: ownership to hand off")
    parser.add_argument("--chmod", metavar="MODE", help="cwa-ingest: octal file mode")
    parser.add_argument(
        "--wait", type=float, default=60.0, help="seconds to poll for the book to appear"
    )
    parser.add_argument(
        "--ambiguous-after",
        type=float,
        default=900.0,
        help="seconds after which an unconfirmed write is reported as ambiguous",
    )
    parser.add_argument(
        "--retry-ambiguous",
        action="store_true",
        help="after checking the catalog, allow a new write for an ambiguous delivery",
    )


def _ingest_folder(args: argparse.Namespace) -> IngestFolder:
    if args.folder is None:
        raise UsageError("--to cwa-ingest needs --folder")
    if args.book_id is not None:
        raise UsageError("--to cwa-ingest adds new books only; use --to calibre-web --book-id")
    owner = mode = None
    try:
        if args.chown:
            uid, gid = args.chown.split(":")
            owner = (int(uid), int(gid))
        if args.chmod:
            mode = int(args.chmod, 8)
    except ValueError as exc:
        raise UsageError("--chown takes UID:GID and --chmod an octal mode") from exc
    return IngestFolder(args.folder, args.staging, owner, mode)


def _instance_name(url: str) -> str:
    parts = urllib.parse.urlsplit(url)
    port = f":{parts.port}" if parts.port else ""
    return f"{parts.scheme}://{parts.hostname}{port}{parts.path.rstrip('/')}"


def run(args: argparse.Namespace) -> int:
    password = os.environ.get(args.password_env)
    if not password:
        raise UsageError(f"environment variable {args.password_env} is not set")
    if args.to == "calibre-web" and args.book_id is None and not args.new:
        raise UsageError("name the target: --book-id N for an existing record, or --new")
    ingest = _ingest_folder(args) if args.to == "cwa-ingest" else None
    try:
        deliverable = load_deliverable(args.job, allow_review=args.allow_review)
        client = CalibreWeb(Http(args.url), args.user, password)
    except DeliveryError as exc:
        raise UsageError(str(exc)) from exc

    instance = args.instance or _instance_name(args.url)
    destination = "new" if args.book_id is None else f"book:{args.book_id}"
    key = ReceiptKey(args.user, instance, destination, deliverable.rendition_id)
    if ingest is not None:
        dest = IngestDestination(client, deliverable, ingest)
    elif args.book_id is not None:
        dest = RecordDestination(client, args.book_id, deliverable)
    else:
        dest = NewBookDestination(client, deliverable)
    receipts = args.receipts or deliverable.job_dir.parent / RECEIPTS_FILE
    policy = Policy(args.wait, args.ambiguous_after, args.retry_ambiguous)
    try:
        receipt = run_delivery(ReceiptStore(receipts), key, deliverable, dest, policy)
    except Busy as busy:
        state = busy.receipt.state.value if busy.receipt else State.PENDING.value
        print(f"{state}: another delivery of this book to {destination} is in progress")
        return 2

    where = f"record {receipt.remote_book_id}" if receipt.remote_book_id else destination
    detail = f"; {receipt.last_error}" if receipt.last_error else ""
    if receipt.state is State.AMBIGUOUS:
        detail += "; check the catalog, the EPUB stays at " + str(deliverable.epub)
    print(f"{receipt.state.value}: {where} on {key.instance} (attempts {receipt.attempts}){detail}")
    print(f"receipt in {receipts}; adapter qualified on {QUALIFIED_VERSION}", file=sys.stderr)
    return receipt.state.exit_code
