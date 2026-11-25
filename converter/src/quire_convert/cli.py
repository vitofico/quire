"""``quire-convert`` command line: inspect, convert and evaluate."""

from __future__ import annotations

import argparse
import json
import logging
import os
import shutil
import sys
import tempfile
from collections import Counter
from pathlib import Path
from typing import Any, NoReturn

from .job import atomic_write_bytes, fsync_path
from .models import USAGE_EXIT_CODE, Outcome, SourceInventory, StageError
from .pipeline import UsageError

log = logging.getLogger("quire_convert")


class _Parser(argparse.ArgumentParser):
    """Usage errors exit 64: exit code 2 already means Review."""

    def error(self, message: str) -> NoReturn:
        self.print_usage(sys.stderr)
        self.exit(USAGE_EXIT_CODE, f"{self.prog}: error: {message}\n")


def default_job_root() -> Path:
    cache = os.environ.get("XDG_CACHE_HOME") or Path.home() / ".cache"
    return Path(cache) / "quire-convert" / "jobs"


def build_parser() -> _Parser:
    parser = _Parser(prog="quire-convert", description="PDF to reflowable EPUB converter")
    parser.add_argument("-v", "--verbose", action="store_true", help="log stage progress")
    sub = parser.add_subparsers(dest="command", required=True)

    inspect = sub.add_parser("inspect", help="describe a PDF without converting it")
    inspect.add_argument("pdf", type=Path)
    inspect.add_argument("--json", action="store_true", help="print the summary as JSON")

    convert = sub.add_parser("convert", help="convert a PDF into an EPUB and a report")
    convert.add_argument("pdf", type=Path)
    convert.add_argument("--out", type=Path, required=True, help="EPUB destination")
    convert.add_argument("--report", type=Path, required=True, help="report.json destination")
    convert.add_argument("--model", choices=["off"], default="off", help="only off for now")
    convert.add_argument("--variant", choices=["baseline", "rules"], default="rules")
    convert.add_argument("--job-root", type=Path, default=None)
    convert.add_argument("--metadata", type=Path, help="catalog metadata JSON (highest trust)")
    convert.add_argument("--diagnostics", action="store_true", help="keep book text locally")
    convert.add_argument("--html-report", type=Path, help="write a local HTML review report")
    convert.add_argument(
        "--allow-review", action="store_true", help="also write --out for a Review outcome"
    )

    evaluate = sub.add_parser("evaluate", help="score a corpus of labelled books")
    evaluate.add_argument("corpus", type=Path)
    evaluate.add_argument("--variant", choices=["baseline", "rules", "assisted"])
    evaluate.add_argument("--out", type=Path, help="results directory (default: next to corpus)")
    evaluate.add_argument("--job-root", type=Path, default=None)
    evaluate.add_argument("--holdout", action="store_true", help="run the frozen holdout books")
    evaluate.add_argument("--freeze", action="store_true", help="freeze gates and holdout hash")
    evaluate.add_argument("--record-machine", action="store_true", help="write machine.json")

    worker = sub.add_parser("worker", help="convert the jobs Quire Server queues in a folder")
    worker.add_argument("--jobs", type=Path, required=True, help="folder shared with the server")
    worker.add_argument("--once", action="store_true", help="convert at most one job, then exit")
    worker.add_argument("--poll-seconds", type=float, default=2.0, help="idle wait between scans")

    from .deliver.command import add_arguments as add_deliver_arguments

    add_deliver_arguments(sub.add_parser("deliver", help="save a converted EPUB to Calibre-Web"))
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    logging.basicConfig(
        level=logging.INFO if args.verbose or args.command == "worker" else logging.WARNING,
        format="%(levelname)s %(name)s: %(message)s",
    )
    try:
        if args.command == "inspect":
            return _inspect(args)
        if args.command == "convert":
            return _convert(args)
        if args.command == "worker":
            from .worker import main as worker

            return worker(args.jobs, once=args.once, poll_s=args.poll_seconds)
        if args.command == "deliver":
            from .deliver.command import run as deliver

            return deliver(args)
        return _evaluate(args)
    except UsageError as exc:
        parser.exit(USAGE_EXIT_CODE, f"quire-convert: error: {exc}\n")
    except StageError as exc:
        print(f"quire-convert: failed at {exc.stage}: {exc.reason}", file=sys.stderr)
        return Outcome.FAILED.exit_code


# --------------------------------------------------------------------------- inspect


def _require_file(path: Path) -> None:
    if not path.is_file():
        raise UsageError(f"{path} is not a readable file")


def _inspect(args: argparse.Namespace) -> int:
    _require_file(args.pdf)
    from .source.preflight import inspect_pdf
    from .tools import find_tools

    with tempfile.TemporaryDirectory(prefix="quire-inspect-") as work:
        source = inspect_pdf(args.pdf, Path(work), toolchain=find_tools())
    summary = inspect_summary(source)
    if args.json:
        print(json.dumps(summary, indent=2, sort_keys=True))
    else:
        print(format_inspect(summary))
    return 0


def label_runs(labels: list[str | None]) -> list[dict[str, Any]]:
    """Group consecutive pages into runs of one numbering style (arabic, roman or none)."""
    runs: list[dict[str, Any]] = []
    expected = None
    for index, label in enumerate(labels):
        style, value = _label_value(label)
        if runs and runs[-1]["style"] == style and value == expected:
            runs[-1]["end"], runs[-1]["last"] = index, label
        else:
            runs.append({"style": style, "start": index, "end": index, "first": label})
            runs[-1]["last"] = label
        expected = None if value is None else value + 1
    return runs


_ROMAN = {"i": 1, "v": 5, "x": 10, "l": 50, "c": 100, "d": 500, "m": 1000}


def _label_value(label: str | None) -> tuple[str, int | None]:
    if label is None:
        return "none", None
    if label.isdigit():
        return "arabic", int(label)
    low = label.lower()
    if low and all(ch in _ROMAN for ch in low):
        total = 0
        for ch, nxt in zip(low, low[1:] + " ", strict=True):
            value = _ROMAN[ch]
            total += -value if nxt in _ROMAN and _ROMAN[nxt] > value else value
        return "roman", total
    return "other", None


def inspect_summary(source: SourceInventory) -> dict[str, Any]:
    """Everything ``inspect`` reports: counts, flags and measurements, never book text."""
    return {
        "sha256": source.sha256,
        "pages": source.page_count,
        "encryption": _encryption(source),
        "language": source.language,
        "page_types": dict(sorted(Counter(p.page_type for p in source.pages).items())),
        "page_flags": dict(sorted(Counter(f for p in source.pages for f in p.flags).items())),
        "page_label_runs": label_runs([p.label for p in source.pages]),
        "bookmarks": len(source.bookmarks),
        "complexity": source.complexity,
        "unsupported": source.unsupported,
        "resource": source.resource,
    }


def _encryption(source: SourceInventory) -> str:
    if not source.encrypted:
        return "no"
    return "yes, needs a password to open" if source.needs_password else "yes"


def format_inspect(summary: dict[str, Any]) -> str:
    def pairs(d: dict[str, Any]) -> str:
        return ", ".join(f"{k} {v}" for k, v in d.items()) or "none"

    runs = [
        f"{r['style']} {r['first']}-{r['last']} (pages {r['start']}-{r['end']})"
        for r in summary["page_label_runs"]
    ]
    lines = [
        f"pages: {summary['pages']}",
        f"page types: {pairs(summary['page_types'])}",
        f"page flags: {pairs(summary['page_flags'])}",
        f"encrypted: {summary['encryption']}",
        f"language: {summary['language'] or 'unknown'}",
        f"page labels: {'; '.join(runs) or 'none'}",
        f"bookmarks: {summary['bookmarks']}",
        f"complexity: {pairs(summary['complexity'])}",
        f"resources: {pairs(summary['resource'])}",
        f"unsupported: {'; '.join(summary['unsupported']) or 'no, in scope'}",
    ]
    return "\n".join(lines)


# --------------------------------------------------------------------------- convert


def _load_metadata(path: Path | None) -> dict[str, Any] | None:
    if path is None:
        return None
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        raise UsageError(f"cannot read catalog metadata {path}: {type(exc).__name__}") from exc
    if not isinstance(data, dict):
        raise UsageError("catalog metadata must be a JSON object")
    return data


def copy_atomic(src: Path, dest: Path) -> None:
    """Copy ``src`` to ``dest`` so ``dest`` is either absent/old or complete."""
    dest.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=dest.parent, prefix=f".{dest.name}.", suffix=".tmp")
    os.close(fd)
    try:
        shutil.copyfile(src, tmp)
        fsync_path(Path(tmp))
        os.replace(tmp, dest)
    except BaseException:
        Path(tmp).unlink(missing_ok=True)
        raise
    fsync_path(dest.parent)


def _convert(args: argparse.Namespace) -> int:
    from .pipeline import convert

    _require_file(args.pdf)
    result = convert(
        args.pdf,
        job_root=args.job_root or default_job_root(),
        variant=args.variant,
        catalog_metadata=_load_metadata(args.metadata),
        diagnostics=args.diagnostics,
        html_report_dir=args.html_report,
    )
    args.report.parent.mkdir(parents=True, exist_ok=True)
    atomic_write_bytes(args.report, result.attempt.path("report.json").read_bytes())
    publish = result.outcome is Outcome.READY or (
        result.outcome is Outcome.REVIEW and args.allow_review
    )
    if publish and result.epub_path is not None:
        copy_atomic(result.epub_path, args.out)
        where = f"EPUB written to {args.out}"
    else:
        where = "no EPUB written"
    print(
        f"{result.outcome.value}: {result.report.reason}; {where}; "
        f"job {result.job.dir} attempt {result.attempt.number}",
        file=sys.stderr,
    )
    return result.exit_code


# --------------------------------------------------------------------------- evaluate


def _evaluate(args: argparse.Namespace) -> int:
    from . import evaluate

    _require_file(args.corpus)
    if args.freeze:
        digest = evaluate.freeze(args.corpus)
        print(f"holdout frozen: {digest}", file=sys.stderr)
    if args.record_machine:
        path = evaluate.record_machine(args.corpus)
        print(f"machine recorded in {path}", file=sys.stderr)
    if args.variant is None:
        if args.freeze or args.record_machine:
            return 0
        raise UsageError("evaluate needs --variant (or --freeze / --record-machine)")
    out = evaluate.run(
        args.corpus,
        variant=args.variant,
        out_dir=args.out,
        job_root=args.job_root,
        holdout=args.holdout,
    )
    print(f"results in {out}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
