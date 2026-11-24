"""Corpus evaluation: convert labelled books, score them, and write blinded review sheets.

Each book is converted through the public ``quire-convert convert`` command in its own
process, so elapsed time and peak memory are measured per book, the way a user runs it.
"""

from __future__ import annotations

import csv
import hashlib
import json
import logging
import os
import platform
import posixpath
import random
import re
import shutil
import statistics
import string
import subprocess
import sys
import time
import tomllib
import unicodedata
import zipfile
from dataclasses import dataclass, field
from datetime import UTC, datetime
from pathlib import Path
from typing import Any
from urllib.parse import unquote, urldefrag

from lxml import etree

from .job import sha256_file
from .models import PAGE_ANCHOR_PREFIX, Outcome
from .pipeline import VARIANTS, UsageError

log = logging.getLogger(__name__)

SPLITS = ("dev", "holdout")
HUMAN_COLUMNS = ("human_repair_minutes", "reviewer_id", "notes")
SHEET_COLUMNS = ("presentation_order", "book_id", "blinded_label", "epub", *HUMAN_COLUMNS)


# --------------------------------------------------------------------------- corpus


@dataclass
class CorpusBook:
    id: str
    path: Path
    labels: Path
    split: str
    family: str = ""
    language: str = ""
    strata: list[str] = field(default_factory=list)
    rights: str = ""


@dataclass
class Corpus:
    path: Path
    name: str
    reference_machine: Path | None
    frozen: bool
    frozen_at: str | None
    holdout_sha256: str | None
    books: list[CorpusBook]


def load_corpus(path: Path) -> Corpus:
    path = Path(path)
    try:
        data = tomllib.loads(path.read_text(encoding="utf-8"))
    except (OSError, tomllib.TOMLDecodeError) as exc:
        raise UsageError(f"cannot read corpus {path}: {exc}") from exc
    base = path.parent
    meta, gates = data.get("corpus", {}), data.get("gates", {})
    books, seen = [], set()
    for entry in data.get("book", []):
        missing = [k for k in ("id", "path", "labels", "split") if k not in entry]
        if missing:
            raise UsageError(f"corpus book entry lacks {', '.join(missing)}")
        if entry["split"] not in SPLITS:
            raise UsageError(f"book {entry['id']}: split must be dev or holdout")
        if entry["id"] in seen:
            raise UsageError(f"duplicate book id {entry['id']}")
        seen.add(entry["id"])
        books.append(
            CorpusBook(
                id=entry["id"],
                path=base / entry["path"],
                labels=base / entry["labels"],
                split=entry["split"],
                family=entry.get("family", ""),
                language=entry.get("language", ""),
                strata=list(entry.get("strata", [])),
                rights=entry.get("rights", ""),
            )
        )
    machine = meta.get("reference_machine")
    frozen_at = gates.get("frozen_at")
    return Corpus(
        path=path,
        name=meta.get("name", path.stem),
        reference_machine=base / machine if machine else None,
        frozen=bool(gates.get("frozen", False)),
        frozen_at=str(frozen_at) if frozen_at else None,
        holdout_sha256=gates.get("holdout_sha256") or None,
        books=books,
    )


def holdout_digest(corpus: Corpus) -> str:
    """Hash of every holdout book id with its PDF and labels checksums, sorted by id."""
    lines = []
    for book in sorted((b for b in corpus.books if b.split == "holdout"), key=lambda b: b.id):
        lines.append(f"{book.id}\t{sha256_file(book.path)}\t{sha256_file(book.labels)}")
    return hashlib.sha256("\n".join(lines).encode()).hexdigest()


def freeze(corpus_path: Path, *, now: datetime | None = None) -> str:
    """Freeze the gates: record the holdout hash and time in ``[gates]`` of corpus.toml."""
    corpus = load_corpus(corpus_path)
    if corpus.frozen:
        raise UsageError("the gates are already frozen; unfreeze by editing corpus.toml")
    digest = holdout_digest(corpus)
    stamp = (now or datetime.now(UTC)).isoformat(timespec="seconds")
    text = Path(corpus_path).read_text(encoding="utf-8")
    values = {"frozen": "true", "frozen_at": f'"{stamp}"', "holdout_sha256": f'"{digest}"'}
    Path(corpus_path).write_text(set_toml_keys(text, "gates", values), encoding="utf-8")
    return digest


def set_toml_keys(text: str, section: str, values: dict[str, str]) -> str:
    """Set ``key = value`` lines (values already TOML-encoded) inside one ``[section]``."""
    lines = text.splitlines()
    header = next((i for i, line in enumerate(lines) if line.strip() == f"[{section}]"), None)
    if header is None:
        lines += ["", f"[{section}]"]
        header = len(lines) - 1
    end = next(
        (i for i in range(header + 1, len(lines)) if lines[i].lstrip().startswith("[")),
        len(lines),
    )
    for key, value in values.items():
        pattern = re.compile(rf"^\s*{re.escape(key)}\s*=")
        at = next((i for i in range(header + 1, end) if pattern.match(lines[i])), None)
        if at is None:
            lines.insert(header + 1, f"{key} = {value}")
            end += 1
        else:
            lines[at] = f"{key} = {value}"
    return "\n".join(lines) + "\n"


def check_holdout(corpus: Corpus) -> None:
    if not corpus.frozen or not corpus.holdout_sha256:
        raise UsageError("the holdout stays closed until the gates are frozen (--freeze)")
    if holdout_digest(corpus) != corpus.holdout_sha256:
        raise UsageError("a holdout book or its labels changed after the gates were frozen")


def select_books(corpus: Corpus, holdout: bool) -> list[CorpusBook]:
    if holdout:
        check_holdout(corpus)
    split = "holdout" if holdout else "dev"
    return [b for b in corpus.books if b.split == split]


# --------------------------------------------------------------------------- machine


def machine_info() -> dict[str, Any]:
    from .tools import find_tools

    return {
        "cpu": _cpu_model(),
        "cores": os.cpu_count(),
        "ram_bytes": os.sysconf("SC_PAGE_SIZE") * os.sysconf("SC_PHYS_PAGES"),
        "os": platform.platform(),
        "python": platform.python_version(),
        "tools": find_tools().versions(),
    }


def _cpu_model() -> str:
    if sys.platform == "darwin":
        out = subprocess.run(
            ["sysctl", "-n", "machdep.cpu.brand_string"], capture_output=True, text=True
        )
        return out.stdout.strip() or platform.machine()
    cpuinfo = Path("/proc/cpuinfo")
    if cpuinfo.exists():
        for line in cpuinfo.read_text().splitlines():
            if line.startswith("model name"):
                return line.split(":", 1)[1].strip()
    return platform.processor() or platform.machine()


def record_machine(corpus_path: Path) -> Path:
    corpus = load_corpus(corpus_path)
    path = corpus.reference_machine or Path(corpus_path).parent / "machine.json"
    path.write_text(json.dumps(machine_info(), indent=2, sort_keys=True) + "\n")
    return path


# --------------------------------------------------------------------------- EPUB view

_BLOCK_TAGS = {
    "p", "div", "section", "article", "aside", "blockquote", "figure", "figcaption",
    "h1", "h2", "h3", "h4", "h5", "h6", "li", "dt", "dd", "pre", "td", "th", "caption",
    "header", "footer", "nav", "table", "body",
}  # fmt: skip
_IMAGE_TAGS = {"img", "image", "svg"}
_OPS_NS = "http://www.idpf.org/2007/ops"
_PARSER = etree.XMLParser(recover=True, resolve_entities=False, no_network=True, huge_tree=True)


@dataclass
class NavEntry:
    title: str
    target: tuple  # position key, see EpubView
    level: int


@dataclass
class Unit:
    """Text gathered under one nearest block element, in reading order."""

    text: str = ""
    has_image: bool = False


@dataclass
class Link:
    text: str
    target: tuple
    key: tuple  # position key of the link itself


@dataclass
class Position:
    page: int | None = None  # from the nearest qc-page-N anchor before the next content
    unit: int | None = None  # index of the unit holding the next content
    block: str | None = None  # next "href#id" that the report maps to source pages


@dataclass
class EpubView:
    """What evaluation needs from an EPUB. Position keys are ``("doc", href)`` for the
    start of a content document, ``("id", href, id)`` for an element and ``("link", n)``."""

    nav: list[NavEntry]
    units: list[Unit]
    links: list[Link]
    positions: dict[tuple, Position]
    has_page_anchors: bool
    block_pages: dict[str, list[int]]

    @property
    def text(self) -> str:
        return " ".join(u.text for u in self.units)

    def page_of(self, key: tuple) -> tuple[int | None, str]:
        """Source page of a position and how it was found: "anchor" (EPUB page anchors),
        "spans" (report block spans) or "unknown"."""
        pos = self.positions.get(key)
        if pos is None:
            return None, "unknown"
        if self.has_page_anchors and pos.page is not None:
            return pos.page, "anchor"
        if pos.block and self.block_pages.get(pos.block):
            return min(self.block_pages[pos.block]), "spans"
        return None, "unknown"

    def unit_text(self, key: tuple) -> str:
        pos = self.positions.get(key)
        return self.units[pos.unit].text if pos and pos.unit is not None else ""


def _local(tag: Any) -> str:
    return etree.QName(tag).localname.lower() if isinstance(tag, str) else ""


def _resolve(base_doc: str, href: str) -> tuple | None:
    if re.match(r"^[a-z][a-z0-9+.-]*:", href, re.I):
        return None  # external link
    path, frag = urldefrag(href)
    doc = posixpath.normpath(posixpath.join(posixpath.dirname(base_doc), unquote(path)))
    if not path:
        doc = base_doc
    return ("id", doc, unquote(frag)) if frag else ("doc", doc)


class _Walker:
    def __init__(self, block_pages: dict[str, list[int]]):
        self.block_pages = block_pages
        self.units: list[Unit] = []
        self.unit_owner: list[Any] = []
        self.links: list[Link] = []
        self.positions: dict[tuple, Position] = {}
        self.page: int | None = None
        self.has_anchors = False
        self.pending_page: list[tuple] = []
        self.pending_unit: list[tuple] = []
        self.pending_block: list[tuple] = []

    def mark(self, key: tuple, inside_unit: Any = None) -> None:
        self.positions[key] = Position()
        self.pending_page.append(key)
        self.pending_block.append(key)
        if inside_unit is not None and self.unit_owner and self.unit_owner[-1] is inside_unit:
            self.positions[key].unit = len(self.units) - 1
        else:
            self.pending_unit.append(key)

    def anchor(self, page: int) -> None:
        self.page, self.has_anchors = page, True
        self._flush_page()

    def block(self, ref: str) -> None:
        for key in self.pending_block:
            self.positions[key].block = ref
        self.pending_block.clear()

    def content(self, owner: Any, text: str = "", image: bool = False) -> None:
        if not self.unit_owner or self.unit_owner[-1] is not owner:
            self.units.append(Unit())
            self.unit_owner.append(owner)
        unit = self.units[-1]
        if text:
            unit.text = f"{unit.text} {text}".strip() if unit.text else text.strip()
        unit.has_image |= image
        self._flush_page()
        for key in self.pending_unit:
            self.positions[key].unit = len(self.units) - 1
        self.pending_unit.clear()

    def finish(self) -> None:
        """Positions still waiting for content at the end of the book keep the last page."""
        self._flush_page()

    def _flush_page(self) -> None:
        for key in self.pending_page:
            self.positions[key].page = self.page
        self.pending_page.clear()

    def walk_doc(self, doc: str, root: Any) -> None:
        self.mark(("doc", doc))
        body = next((e for e in root.iter() if _local(e.tag) == "body"), root)
        self._walk(doc, body, None)

    def _walk(self, doc: str, el: Any, owner: Any) -> None:
        name = _local(el.tag)
        if not name:
            return  # comment or processing instruction
        if name in _BLOCK_TAGS and (owner is None or _local(owner.tag) != "figure"):
            owner = el
        elem_id = el.get("id")
        if elem_id:
            self.mark(("id", doc, elem_id), inside_unit=owner if owner is not el else None)
            if elem_id.startswith(PAGE_ANCHOR_PREFIX):
                suffix = elem_id[len(PAGE_ANCHOR_PREFIX) :]
                if suffix.isdigit():
                    self.anchor(int(suffix))
            ref = f"{doc}#{elem_id}"
            if ref in self.block_pages:
                self.block(ref)
        if name == "a" and el.get("href"):
            target = _resolve(doc, el.get("href"))
            if target:
                key = ("link", len(self.links))
                self.mark(key, inside_unit=owner)
                self.links.append(Link(" ".join("".join(el.itertext()).split()), target, key))
        if name in _IMAGE_TAGS:
            self.content(owner, image=True)
        if el.text and el.text.strip() and name not in ("svg", "style", "script"):
            self.content(owner, " ".join(el.text.split()))
        for child in el:
            self._walk(doc, child, owner)
            if child.tail and child.tail.strip():
                self.content(owner, " ".join(child.tail.split()))


def read_epub(path: Path, block_pages: dict[str, list[int]] | None = None) -> EpubView:
    block_pages = block_pages or {}
    with zipfile.ZipFile(path) as zf:
        container = etree.fromstring(zf.read("META-INF/container.xml"), _PARSER)
        opf_path = next(e.get("full-path") for e in container.iter() if _local(e.tag) == "rootfile")
        opf = etree.fromstring(zf.read(opf_path), _PARSER)
        opf_dir = posixpath.dirname(opf_path)
        items = {}
        for item in (e for e in opf.iter() if _local(e.tag) == "item"):
            href = posixpath.normpath(posixpath.join(opf_dir, unquote(item.get("href", ""))))
            items[item.get("id")] = (href, item.get("properties", ""), item.get("media-type"))
        spine = next(e for e in opf.iter() if _local(e.tag) == "spine")
        walker = _Walker(block_pages)
        for ref in (e for e in spine if _local(e.tag) == "itemref"):
            href = items.get(ref.get("idref"), (None,))[0]
            if href and href in zf.namelist():
                walker.walk_doc(href, etree.fromstring(zf.read(href), _PARSER))
        walker.finish()
        nav = _read_nav(zf, items, spine.get("toc"))
    return EpubView(
        nav=nav,
        units=walker.units,
        links=walker.links,
        positions=walker.positions,
        has_page_anchors=walker.has_anchors,
        block_pages=block_pages,
    )


def _read_nav(zf: zipfile.ZipFile, items: dict, toc_id: str | None) -> list[NavEntry]:
    nav_href = next((h for h, props, _ in items.values() if "nav" in props.split()), None)
    if nav_href and nav_href in zf.namelist():
        root = etree.fromstring(zf.read(nav_href), _PARSER)
        navs = [e for e in root.iter() if _local(e.tag) == "nav"]
        toc = next((n for n in navs if n.get(f"{{{_OPS_NS}}}type") == "toc"), None)
        if toc is not None or navs:
            entries: list[NavEntry] = []
            _nav_list(toc if toc is not None else navs[0], nav_href, 0, entries)
            return entries
    ncx_href = items.get(toc_id, (None,))[0] if toc_id else None
    if ncx_href is None:
        ncx_href = next(
            (h for h, _, mt in items.values() if mt == "application/x-dtbncx+xml"), None
        )
    if ncx_href and ncx_href in zf.namelist():
        root = etree.fromstring(zf.read(ncx_href), _PARSER)
        nav_map = next((e for e in root.iter() if _local(e.tag) == "navmap"), None)
        entries = []
        if nav_map is not None:
            _ncx_points(nav_map, ncx_href, 1, entries)
        return entries
    return []


def _nav_list(el: Any, doc: str, depth: int, out: list[NavEntry]) -> None:
    for child in el:
        name = _local(child.tag)
        if name == "ol":
            _nav_list(child, doc, depth + 1, out)
        elif name == "li":
            link = next((c for c in child if _local(c.tag) in ("a", "span")), None)
            if link is not None and link.get("href"):
                target = _resolve(doc, link.get("href"))
                if target:
                    out.append(NavEntry(" ".join("".join(link.itertext()).split()), target, depth))
            _nav_list(child, doc, depth, out)
        else:
            _nav_list(child, doc, depth, out)


def _ncx_points(el: Any, doc: str, level: int, out: list[NavEntry]) -> None:
    for point in (c for c in el if _local(c.tag) == "navpoint"):
        label = next((e for e in point.iter() if _local(e.tag) == "text"), None)
        content = next((c for c in point if _local(c.tag) == "content"), None)
        target = _resolve(doc, content.get("src", "")) if content is not None else None
        if label is not None and target:
            out.append(NavEntry(" ".join("".join(label.itertext()).split()), target, level))
        _ncx_points(point, doc, level + 1, out)


# --------------------------------------------------------------------------- scoring


def norm_text(text: str) -> str:
    """Normalized for character error scoring: NFKC, no soft hyphens, single spaces."""
    return " ".join(unicodedata.normalize("NFKC", text).replace("­", "").split())


def norm_match(text: str) -> str:
    """Normalized for title and caption matching: also case and punctuation folded."""
    return " ".join(re.sub(r"[^\w\s]", " ", norm_text(text).casefold()).split())


def score_chapters(labels: dict, view: EpubView | None) -> dict[str, Any]:
    chapters = labels.get("chapters", [])
    nav = view.nav if view else []
    used: set[int] = set()
    matched = wrong_target = 0
    modes: set[str] = set()
    resolved = [view.page_of(entry.target) if view else (None, "unknown") for entry in nav]
    for chapter in chapters:
        title = norm_match(chapter["title"])
        candidates = [
            i for i, e in enumerate(nav) if i not in used and norm_match(e.title) == title
        ]
        by_page = [i for i in candidates if resolved[i][0] == chapter.get("page")]
        by_title = [i for i in candidates if resolved[i][0] is None]
        hit = (by_page or by_title or [None])[0]
        if hit is not None:
            modes.add(resolved[hit][1] if by_page else "title_only")
        if hit is None:
            wrong_target += bool(candidates)
            continue
        used.add(hit)
        matched += 1
    return {
        "labelled": len(chapters),
        "nav_entries": len(nav),
        "matched": matched,
        "wrong_target": wrong_target,
        "precision": matched / len(nav) if nav else None,
        "recall": matched / len(chapters) if chapters else None,
        "match_mode": _mode(modes),
    }


def _mode(modes: set[str]) -> str | None:
    if not modes:
        return None
    if modes == {"title_only"}:
        return "title_only"
    return "title_and_page" if "title_only" not in modes else "mixed"


def score_figures(labels: dict, view: EpubView | None) -> dict[str, Any]:
    figures = labels.get("figures", [])
    captioned = [f for f in figures if f.get("caption")]
    units = view.units if view else []
    complete = 0
    for figure in captioned:
        caption = norm_match(figure["caption"])
        for i, unit in enumerate(units):
            if caption and caption in norm_match(unit.text):
                near = units[max(0, i - 1) : i + 2]
                if any(u.has_image for u in near):
                    complete += 1
                    break
    return {
        "labelled": len(figures),
        "captioned": len(captioned),
        "pairs_complete": complete,
        "images_in_epub": sum(u.has_image for u in units),
    }


def score_notes(labels: dict, view: EpubView | None) -> dict[str, Any]:
    notes = labels.get("notes", [])
    correct = unverified_page = 0
    used: set[int] = set()
    links = view.links if view else []
    for note in notes:
        marker = norm_match(note["marker"])
        start = norm_match(note.get("text_start", ""))
        for n, link in enumerate(links):
            if n in used or norm_match(link.text) != marker:
                continue
            page, _ = view.page_of(link.key)
            if page is not None and page != note.get("page"):
                continue
            target = norm_match(view.unit_text(link.target))
            if start and start in target[: len(start) + 16]:
                used.add(n)
                correct += 1
                unverified_page += page is None
                break
    return {"annotated": len(notes), "correct": correct, "page_unverified": unverified_page}


def score_index(labels: dict, view: EpubView | None) -> dict[str, Any]:
    page_labels = labels.get("page_labels") or []
    refs = [(e["term"], p) for e in labels.get("index", []) for p in e.get("pages", [])]
    correct = unverified = 0
    links = view.links if view else []
    for term, label in refs:
        term_n = norm_match(term)
        found = None
        for link in links:
            if norm_match(link.text) != norm_match(label):
                continue
            if not norm_match(view.unit_text(link.key)).startswith(term_n):
                continue
            page, _ = view.page_of(link.target)
            if page is None or page >= len(page_labels):
                found = found or "unverified"
            elif page_labels[page] == label:
                found = "correct"
                break
        correct += found == "correct"
        unverified += found == "unverified"
    return {"annotated": len(refs), "correct": correct, "unverified": unverified}


def score_tokens(labels: dict, view: EpubView | None) -> dict[str, Any]:
    tokens = labels.get("critical_tokens", [])
    text = norm_text(view.text) if view else ""
    missing = [t for t in tokens if norm_text(t) not in text]
    return {"labelled": len(tokens), "present": len(tokens) - len(missing), "missing": missing}


def edit_distance_in(pattern: str, text: str) -> int:
    """Fewest edits turning ``pattern`` into some substring of ``text``."""
    prev = [0] * (len(text) + 1)
    for i, pc in enumerate(pattern, 1):
        cur = [i] + [0] * len(text)
        for j, tc in enumerate(text, 1):
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (pc != tc))
        prev = cur
    return min(prev)


def character_error_rate(passage: str, text: str, gram: int = 12) -> float | None:
    """CER of a gold passage against the best matching window of the book text. The window
    is located by exact k-gram hits, so the cost stays linear in the book size."""
    pattern = norm_text(passage)
    if not pattern:
        return None
    step = max(1, len(pattern) // 8)
    offsets = []
    for start in range(0, max(1, len(pattern) - gram + 1), step):
        at = text.find(pattern[start : start + gram])
        if at >= 0:
            offsets.append(at - start)
    if not offsets:
        return 1.0
    at = statistics.median_low(offsets)
    margin = len(pattern) // 2 + gram
    window = text[max(0, at - margin) : at + len(pattern) + margin]
    return min(1.0, edit_distance_in(pattern, window) / len(pattern))


def score_passages(labels: dict, view: EpubView | None) -> dict[str, Any]:
    text = norm_text(view.text) if view else ""
    rates = [character_error_rate(p["text"], text) for p in labels.get("passages", [])]
    rates = [r for r in rates if r is not None]
    return {
        "count": len(rates),
        "cer": [round(r, 4) for r in rates],
        "cer_mean": round(statistics.fmean(rates), 4) if rates else None,
        "cer_max": round(max(rates), 4) if rates else None,
    }


# Issue codes the alignment and coverage stages use for text that appeared or vanished.
_INVENTED = ("invented", "duplicat", "extra")
_OMITTED = ("omitted", "missing", "lost", "unaccounted")
_NOT_SPANS = ("nav.", "meta.")


def span_counts(issues: list[dict]) -> dict[str, int]:
    def counts(words: tuple[str, ...]) -> int:
        return sum(
            1
            for i in issues
            if not i["code"].startswith(_NOT_SPANS) and any(w in i["code"] for w in words)
        )

    return {"invented": counts(_INVENTED), "omitted": counts(_OMITTED)}


def score_book(
    labels: dict, outcome: Outcome, report: dict, epub: Path | None, elapsed: float
) -> dict[str, Any]:
    view = None
    if epub:
        try:
            view = read_epub(epub, report.get("block_pages"))
        except (zipfile.BadZipFile, KeyError, StopIteration, etree.XMLSyntaxError) as exc:
            log.warning("cannot read EPUB for scoring: %s", type(exc).__name__)
    return {
        "epub_readable": view is not None,
        "outcome": outcome.value,
        "reason": report.get("reason", ""),
        "elapsed_seconds": round(elapsed, 2),
        "peak_memory_bytes": (report.get("peak_memory") or {}).get("peak_bytes"),
        "issue_counts": report.get("issue_counts", {}),
        "page_anchors": bool(view and view.has_page_anchors),
        "chapters": score_chapters(labels, view),
        "figures": score_figures(labels, view),
        "notes": score_notes(labels, view),
        "index": score_index(labels, view),
        "critical_tokens": score_tokens(labels, view),
        "passages": score_passages(labels, view),
        "spans": span_counts(report.get("issues", [])),
    }


# --------------------------------------------------------------------------- running


def _convert_book(
    book: CorpusBook, variant: str, job_root: Path, out: Path
) -> tuple[Outcome, dict, Path | None, float]:
    """Run ``quire-convert convert`` for one book in its own process."""
    epub, report = out / "epub" / f"{book.id}.epub", out / "report" / f"{book.id}.json"
    for path in (epub, report):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.unlink(missing_ok=True)
    argv = [sys.executable, "-m", "quire_convert.cli", "convert", str(book.path)]
    argv += ["--out", str(epub), "--report", str(report), "--model", "off"]
    argv += ["--variant", variant, "--job-root", str(job_root), "--allow-review"]
    start = time.perf_counter()
    done = subprocess.run(argv, capture_output=True, text=True)
    elapsed = time.perf_counter() - start
    codes = {o.exit_code: o for o in Outcome}
    data = json.loads(report.read_text()) if report.exists() else {}
    outcome = codes.get(done.returncode, Outcome.FAILED)
    if done.returncode not in codes:
        data.setdefault("reason", f"convert exited {done.returncode}")
    return outcome, data, epub if epub.exists() else None, elapsed


def run(
    corpus_path: Path,
    *,
    variant: str,
    out_dir: Path | None = None,
    job_root: Path | None = None,
    holdout: bool = False,
) -> Path:
    """Convert and score every selected book; returns the results directory."""
    if variant == "assisted":
        raise UsageError("the assisted variant needs the model, which this release lacks")
    if variant not in VARIANTS:
        raise UsageError(f"unknown variant {variant!r}")
    corpus = load_corpus(corpus_path)
    books = select_books(corpus, holdout)
    out = Path(out_dir) if out_dir else corpus.path.parent / "results"
    variant_dir = out / variant
    variant_dir.mkdir(parents=True, exist_ok=True)
    job_root = Path(job_root) if job_root else out / "jobs"
    for book in books:
        labels = json.loads(book.labels.read_text(encoding="utf-8"))
        outcome, report, epub, elapsed = _convert_book(book, variant, job_root, variant_dir)
        result = {
            "book": book.id,
            "split": book.split,
            "family": book.family,
            "language": book.language,
            "strata": book.strata,
            "variant": variant,
            "epub": epub.relative_to(out).as_posix() if epub else None,
        } | score_book(labels, outcome, report, epub, elapsed)
        (variant_dir / f"{book.id}.json").write_text(json.dumps(result, indent=2) + "\n")
        log.info("book %s %s in %.1fs", book.id, outcome.value, elapsed)
    results = load_results(out)
    write_scoring_sheet(out, results)
    (out / "summary.md").write_text(summary_markdown(corpus, results), encoding="utf-8")
    return out


def load_results(out: Path) -> dict[str, dict[str, dict]]:
    """All per-book results in ``out``: variant -> book id -> result."""
    results: dict[str, dict[str, dict]] = {}
    for variant in sorted(VARIANTS + ("assisted",)):
        for path in sorted((out / variant).glob("*.json")):
            data = json.loads(path.read_text())
            results.setdefault(variant, {})[data["book"]] = data
    return results


# --------------------------------------------------------------------------- scoring sheet


def write_scoring_sheet(out: Path, results: dict[str, dict[str, dict]]) -> None:
    """Blinded review sheet: each (book, variant) EPUB is copied as ``review/<book>/<label>``
    under a random letter that stays stable across runs, rows are shuffled, and the key
    lives in blinding-key.json. Human columns already filled in are carried over."""
    key_path, sheet_path = out / "blinding-key.json", out / "scoring-sheet.csv"
    key = json.loads(key_path.read_text()) if key_path.exists() else {}
    seed = key.setdefault("seed", random.SystemRandom().randrange(2**32))
    books = key.setdefault("books", {})
    rows = []
    for variant, by_book in sorted(results.items()):
        for book_id, result in sorted(by_book.items()):
            if not result.get("epub"):
                continue
            labels = books.setdefault(book_id, {})
            if variant not in labels:
                free = [c for c in string.ascii_uppercase if c not in labels.values()]
                labels[variant] = random.Random(f"{seed}:{book_id}:{variant}").choice(free)
            label = labels[variant]
            copy = out / "review" / book_id / f"{label}.epub"
            copy.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(out / result["epub"], copy)
            epub = copy.relative_to(out).as_posix()
            rows.append({"book_id": book_id, "blinded_label": label, "epub": epub})
    random.Random(seed).shuffle(rows)
    previous = {}
    if sheet_path.exists():
        with open(sheet_path, newline="") as fh:
            previous = {(r["book_id"], r["blinded_label"]): r for r in csv.DictReader(fh)}
    with open(sheet_path, "w", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=SHEET_COLUMNS)
        writer.writeheader()
        for order, row in enumerate(rows, 1):
            old = previous.get((row["book_id"], row["blinded_label"]), {})
            human = {c: old.get(c, "") for c in HUMAN_COLUMNS}
            writer.writerow({"presentation_order": order, **row, **human})
    key_path.write_text(json.dumps(key, indent=2, sort_keys=True) + "\n")


# --------------------------------------------------------------------------- summary


def _ratio(num: int, den: int) -> str:
    return f"{num}/{den}" if den else "-"


def _fmt(value: float | None, digits: int = 3) -> str:
    return "-" if value is None else f"{value:.{digits}f}"


def _mib(value: int | None) -> str:
    return "-" if value is None else f"{value / 2**20:.0f}"


def _row(r: dict) -> list[str]:
    ch, fig, notes, idx = r["chapters"], r["figures"], r["notes"], r["index"]
    tok, pas, spans = r["critical_tokens"], r["passages"], r["spans"]
    return [
        r["book"],
        r["split"],
        r["outcome"],
        _fmt(ch["precision"], 2),
        _fmt(ch["recall"], 2),
        ch["match_mode"] or "-",
        _ratio(fig["pairs_complete"], fig["captioned"]),
        _ratio(notes["correct"], notes["annotated"]),
        _ratio(idx["correct"], idx["annotated"]),
        _ratio(tok["present"], tok["labelled"]),
        _fmt(pas["cer_mean"]),
        str(spans["invented"]),
        str(spans["omitted"]),
        _fmt(r["elapsed_seconds"], 1),
        _mib(r["peak_memory_bytes"]),
    ]


_HEADER = [
    "book", "split", "outcome", "ch P", "ch R", "ch match", "fig+caption", "notes", "index",
    "tokens", "CER", "invented", "omitted", "seconds", "peak MiB",
]  # fmt: skip


def _table(header: list[str], rows: list[list[str]]) -> list[str]:
    lines = ["| " + " | ".join(header) + " |", "|" + "---|" * len(header)]
    return lines + ["| " + " | ".join(row) + " |" for row in rows]


def _median(values: list[float | None]) -> float | None:
    values = [v for v in values if v is not None]
    return statistics.median(values) if values else None


def _worst(results: list[dict], metric, highest: bool) -> str:
    scored = [(metric(r), r["book"]) for r in results if metric(r) is not None]
    if not scored:
        return "-"
    value, book = max(scored) if highest else min(scored)
    return f"{book} ({_fmt(value, 3)})"


def _variant_section(variant: str, by_book: dict[str, dict]) -> list[str]:
    results = [by_book[b] for b in sorted(by_book)]
    total = len(results)
    ready = sum(r["outcome"] == "ready" for r in results)
    outcomes = {o.value: sum(r["outcome"] == o.value for r in results) for o in Outcome}

    def total_of(group: str, num: str, den: str) -> str:
        return _ratio(sum(r[group][num] for r in results), sum(r[group][den] for r in results))

    def recall(r):
        return r["chapters"]["recall"]

    def cer(r):
        return r["passages"]["cer_mean"]

    def elapsed(r):
        return r["elapsed_seconds"]

    def memory(r):
        return r["peak_memory_bytes"]

    lines = [f"## Variant `{variant}`", ""]
    lines += _table(_HEADER, [_row(r) for r in results]) + [""]
    lines += [
        f"- Usable-book yield (Ready over every book, Review and Unsupported included): "
        f"{_ratio(ready, total)}",
        "- Outcomes: " + ", ".join(f"{k} {v}" for k, v in outcomes.items()),
        f"- Median chapter recall {_fmt(_median([recall(r) for r in results]), 2)}, "
        f"worst {_worst(results, recall, highest=False)}",
        f"- Median CER {_fmt(_median([cer(r) for r in results]))}, "
        f"worst {_worst(results, cer, highest=True)}",
        f"- Median seconds {_fmt(_median([elapsed(r) for r in results]), 1)}, "
        f"worst {_worst(results, elapsed, highest=True)}",
        f"- Median peak MiB {_mib(_median([memory(r) for r in results]))}, "
        f"worst {_worst(results, lambda r: (memory(r) or 0) / 2**20, highest=True)}",
        f"- Raw counts: chapters matched {total_of('chapters', 'matched', 'labelled')}, "
        f"wrong targets {sum(r['chapters']['wrong_target'] for r in results)}, "
        f"figure-caption pairs {total_of('figures', 'pairs_complete', 'captioned')}, "
        f"notes {total_of('notes', 'correct', 'annotated')}, "
        f"index {total_of('index', 'correct', 'annotated')}, "
        f"critical tokens {total_of('critical_tokens', 'present', 'labelled')}, "
        f"invented spans {sum(r['spans']['invented'] for r in results)}, "
        f"omitted spans {sum(r['spans']['omitted'] for r in results)}",
        "",
    ]
    return lines


def _comparison(results: dict[str, dict[str, dict]]) -> list[str]:
    variants = sorted(results)
    books = sorted({b for by_book in results.values() for b in by_book})
    header = ["book"] + [f"{v} outcome / ch R / CER" for v in variants]
    rows = []
    for book in books:
        row = [book]
        for v in variants:
            r = results[v].get(book)
            row.append(
                "-"
                if r is None
                else f"{r['outcome']} / {_fmt(r['chapters']['recall'], 2)} / "
                f"{_fmt(r['passages']['cer_mean'])}"
            )
        rows.append(row)
    yields = ["yield"] + [
        _ratio(sum(r["outcome"] == "ready" for r in results[v].values()), len(results[v]))
        for v in variants
    ]
    return ["## Paired comparison", ""] + _table(header, rows + [yields]) + [""]


def summary_markdown(corpus: Corpus, results: dict[str, dict[str, dict]]) -> str:
    lines = [f"# Evaluation: {corpus.name}", ""]
    gates = f"frozen at {corpus.frozen_at}" if corpus.frozen else "not frozen"
    lines += [
        f"Gates {gates}. Chapter match mode `title_only` means no page check was possible.",
        "",
    ]
    for variant in sorted(results):
        lines += _variant_section(variant, results[variant])
    if len(results) > 1:
        lines += _comparison(results)
    return "\n".join(lines)
