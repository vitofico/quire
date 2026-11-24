"""Fill the EPUB's package metadata from evidence instead of Calibre's defaults.

Candidates come from, in rising trust: the PDF info dictionary, a printed back-cover or
jacket blurb (description only), XMP, the title and copyright pages, the detected
language, and the catalog record. Each field takes the
most trusted candidate that survived the junk filters; a material disagreement with a
less trusted source becomes a ``meta.conflict`` issue. Nothing is invented from prose.
"""

from __future__ import annotations

import io
import logging
import re
import statistics
import unicodedata
from dataclasses import dataclass
from typing import Any

from PIL import Image

from ..context import ConvertContext
from ..epub.package import XHTML_NS
from ..models import Line, MetadataField, PageInventory, Severity, StageError
from ..source.language import primary
from ..source.render import render_region

log = logging.getLogger(__name__)

TRUST = {
    "info": 0,
    "blurb_page": 1,
    "xmp": 2,
    "title_page": 3,
    "copyright_page": 3,
    "detected": 4,
    "catalog": 5,
}
SCALARS = (
    "title",
    "subtitle",
    "publisher",
    "date",
    "description",
    "series",
    "series_index",
    "rights",
    "language",
)
FRONT_PAGES = 8
COVER_DPI = 150
_FURNITURE = {"folio", "running_header", "running_footer"}

_SOFTWARE = {
    "microsoft", "word", "excel", "powerpoint", "office", "adobe", "acrobat", "indesign",
    "illustrator", "photoshop", "distiller", "quark", "quarkxpress", "framemaker", "pdf",
    "pdfcreator", "pdfwriter", "reportlab", "latex", "pdftex", "pdflatex", "xetex", "tex",
    "libreoffice", "openoffice", "writer", "ghostscript", "cairo", "skia", "calibre",
    "scribus", "pages", "printer", "primopdf", "nitro", "foxit", "wkhtmltopdf", "prince",
    "macos", "windows", "quartz", "pdfmaker", "mac", "os", "x", "for", "library",
    "opensource",
}  # fmt: skip
_JUNK_TITLES = {"untitled", "untitled document", "unknown", "document", "source", "title", "none"}
_JUNK_PEOPLE = {
    "administrator", "admin", "user", "owner", "unknown", "anonymous", "author", "root",
    "guest", "utente", "proprietario", "sconosciuto", "anonimo", "autore", "none",
}  # fmt: skip
_JUNK_SUBJECTS = {"unspecified", "none", "n/a", "untitled", "unknown", "subject"}
_FILE_EXT = re.compile(r"\.(docx?|pdf|odt|rtf|txt|indd|pages|tex|qxd|ps|html?|epub|mobi)$", re.I)
_FILE_LIKE = re.compile(r"^(?:[\w-]*_[\w-]*|[a-z]+\d+)$", re.I)
_VERSION = re.compile(r"^v?\d[\d.]*$|^cs\d*$|^\(.*\)$", re.I)

_HEADINGS = {
    "contents", "table of contents", "index", "preface", "foreword", "introduction",
    "acknowledgements", "acknowledgments", "dedication", "prologue", "indice", "sommario",
    "prefazione", "introduzione", "ringraziamenti", "premessa", "prologo",
}  # fmt: skip
_NAME_STOP = {
    "a", "an", "the", "of", "from", "and", "novel", "memoir", "stories", "poems", "book",
    "edition", "volume", "letters", "press", "books", "publishing", "romanzo", "racconti",
    "poesie", "edizioni", "editore", "il", "lo", "gli", "un", "una", "dei", "delle",
}  # fmt: skip
_PARTICLES = {"de", "di", "da", "del", "della", "van", "von", "der", "den", "du", "dos", "le", "la"}
_SUFFIXES = {"jr", "jr.", "sr", "sr.", "ii", "iii", "iv"}

_COPYRIGHT_MARKERS = re.compile(
    r"©|\(c\)\s*\d{4}|\bcopyright\b|\bisbn\b|published by|all rights reserved|first published"
    r"|prima edizione|tutti i diritti riservati|edito da|pubblicato da|finito di stampare",
    re.I,
)
_ISBN = re.compile(r"ISBN(?:[- ]?1[03])?[:\s]*([0-9][0-9\s-]{8,20}[0-9Xx])", re.I)
_DOI = re.compile(r"\b(10\.\d{4,9}/[^\s\"<>]+)")
_YEAR = re.compile(r"\b(1[5-9]\d\d|20\d\d)\b")
_COPYRIGHT_LINE = re.compile(r"©|\(c\)\s*\d{4}|\bcopyright\b", re.I)
_HOLDER = re.compile(
    r"(?:©|\(c\)|copyright)\s*(?:©\s*)?(?:\d{4}(?:\s*[,\-–]\s*\d{4})*)\s*(?:by\s+)?(.+)$", re.I
)
_EDITION_LINE = re.compile(r"first published|prima edizione|first edition", re.I)
_RESERVED = re.compile(r"all rights reserved|tutti i diritti riservati", re.I)
_PUBLISHER = re.compile(r"\b(?:published\b.*?\bby|edito da|pubblicato da|editore:)\s+(.+)", re.I)
# Back-cover cues. Strong ones mark a page as a blurb on their own; weak ones need a second.
_STRONG_CUES = {
    "praise_for": re.compile(r"^\s*(?:advance\s+)?(?:praise|acclaim)\s+for\b", re.I),
    "review_quote": re.compile(r"^\s*[—–]\s*(?:the\s+)?[A-Z]"),
    "cover_note": re.compile(
        r"\b(?:in copertina|quarta di copertina|l['’]autore|l['’]autrice)\b", re.I
    ),
}
_WEAK_CUES = {
    "isbn": re.compile(r"\bISBN\b", re.I),
    "price": re.compile(r"[$£€]\s?\d+[.,]\d{2}\b|\b\d+[.,]\d{2}\s?(?:€|EUR|USD|GBP)\b"),
    "author_bio": re.compile(
        r"\bis the author of\b|\bè (?:anche )?(?:l['’])?(?:autore|autrice) di\b", re.I
    ),
}
_STATEMENT = re.compile(
    r"©|\bcopyright\b|all rights reserved|published by|first published|prima edizione"
    r"|tutti i diritti riservati|edito da|pubblicato da",
    re.I,
)
_LOCATOR = re.compile(r"(?:\.{2,}|\s)\s*[\divxlc]+$|,\s*\d+(?:[-–]\d+)?(?:,\s*\d+)*$", re.I)
_QUOTE_OPEN = tuple("\"“‘'«")
_BLURB_MIN_WORDS = 20
_AUTHOR_SPLIT = re.compile(r"\s*;\s*|\s+&\s+|\s+and\s+|\s+e\s+")


@dataclass
class Candidate:
    field: str
    value: Any
    source: str
    evidence: str


# --------------------------------------------------------------------------- identifiers


def isbn13_valid(digits: str) -> bool:
    if not re.fullmatch(r"97[89]\d{10}", digits):
        return False
    total = sum(int(d) * (1 if i % 2 == 0 else 3) for i, d in enumerate(digits))
    return total % 10 == 0


def isbn10_valid(digits: str) -> bool:
    if not re.fullmatch(r"\d{9}[\dX]", digits):
        return False
    total = sum((10 - i) * (10 if d == "X" else int(d)) for i, d in enumerate(digits))
    return total % 11 == 0


def isbn10_to_13(digits: str) -> str:
    stem = "978" + digits[:9]
    check = -sum(int(d) * (1 if i % 2 == 0 else 3) for i, d in enumerate(stem)) % 10
    return stem + str(check)


def normalize_isbn(raw: str) -> str | None:
    """A verified ISBN-13 for ``raw`` (ISBN-10 converted), or None when the check fails."""
    digits = re.sub(r"[\s-]", "", re.sub(r"^(?:urn:)?isbn:?", "", raw.strip(), flags=re.I))
    digits = digits.upper()
    if isbn13_valid(digits):
        return digits
    if isbn10_valid(digits):
        return isbn10_to_13(digits)
    return None


def _identifier(raw: str, *, keep_other: bool) -> tuple[str, str] | None:
    """(identifier, evidence tag) for a raw ISBN/DOI; other schemes only when ``keep_other``."""
    raw = raw.strip()
    doi = re.fullmatch(r"(?:doi:\s*|https?://(?:dx\.)?doi\.org/)?(10\.\d{4,9}/\S+)", raw, re.I)
    if doi:
        return f"doi:{doi.group(1).rstrip('.,;)')}", "doi=pattern_ok"
    isbn = normalize_isbn(raw)
    if isbn:
        kind = "isbn13" if len(re.sub(r"\D", "", raw)) >= 13 else "isbn10"
        return f"urn:isbn:{isbn}", f"{kind}=check_ok"
    if keep_other and re.fullmatch(r"[a-z][a-z0-9+.-]*:\S+", raw, re.I):
        return raw, "scheme=as_given"
    return None


def _isbn_key(identifier: str) -> str:
    isbn = normalize_isbn(identifier) if "isbn" in identifier.lower() else None
    return isbn or identifier.lower()


# --------------------------------------------------------------------------- junk filters


def _norm(text: str) -> str:
    text = unicodedata.normalize("NFKD", text).casefold()
    return " ".join(re.sub(r"[^\w]+", " ", text).split())


def _software(text: str) -> bool:
    words = re.split(r"[\s/,;:-]+", text.casefold())
    words = [w for w in words if w]
    named = any(w in _SOFTWARE for w in words)
    return named and all(w in _SOFTWARE or _VERSION.match(w) for w in words)


def junk_title(text: str, stems: set[str]) -> bool:
    clean = text.strip()
    low = _norm(clean)
    return (
        not low
        or low in _JUNK_TITLES
        or low in stems
        or re.match(r"microsoft (word|powerpoint|excel)\b", low) is not None
        or re.fullmatch(r"(document|doc|untitled|book|draft)\s*\d*", low) is not None
        or bool(_FILE_EXT.search(clean))
        or (" " not in clean and bool(_FILE_LIKE.match(clean)))
        or _software(clean)
    )


def junk_person(text: str) -> bool:
    low = _norm(text)
    return (
        not low
        or low in _JUNK_PEOPLE
        or "@" in text
        or re.fullmatch(r"(user|admin|owner|utente)\s*\d*", low) is not None
        or _software(text)
    )


def junk_subject(text: str) -> bool:
    return _norm(text) in _JUNK_SUBJECTS or not _norm(text)


# --------------------------------------------------------------------------- names


def _display_name(name: str) -> str:
    """'Marsh, Elena' -> 'Elena Marsh'; other names unchanged."""
    name = " ".join(name.split())
    parts = [p.strip() for p in name.split(",")]
    if len(parts) == 2 and all(parts) and parts[1].casefold() not in _SUFFIXES:
        return f"{parts[1]} {parts[0]}"
    return name


def sort_name(name: str) -> str:
    """'Elena Marsh' -> 'Marsh, Elena'; keeps lowercase particles and Jr./III suffixes."""
    words = name.replace(",", " ").split()
    suffix = []
    while len(words) > 2 and words[-1].casefold() in _SUFFIXES:
        suffix.insert(0, words.pop())
    if len(words) < 2:
        return name
    sort = f"{words[-1]}, {' '.join(words[:-1])}"
    return f"{sort}, {' '.join(suffix)}" if suffix else sort


def _split_people(text: str) -> list[str]:
    names = [_display_name(p) for p in _AUTHOR_SPLIT.split(text.strip()) if p.strip()]
    return [n for n in names if not junk_person(n)]


def _looks_like_name(text: str) -> bool:
    words = text.split()
    if not 2 <= len(words) <= 4:
        return False
    for word in words:
        low = word.casefold()
        if low in _PARTICLES:
            continue
        if low in _NAME_STOP or not re.fullmatch(r"[A-ZÀ-Þ][\w'’.-]*\.?", word):
            return False
    return True


# --------------------------------------------------------------------------- sources


def _from_info(info: dict[str, str], stems: set[str]) -> list[Candidate]:
    found: list[Candidate] = []
    title = info.get("title", "")
    if title and not junk_title(title, stems):
        found.append(Candidate("title", title, "info", "info:title"))
    authors = _split_people(info.get("author", ""))
    if authors:
        found.append(Candidate("authors", authors, "info", "info:author"))
    subject = info.get("subject", "")
    if subject and not junk_subject(subject):
        found.append(Candidate("description", subject, "info", "info:subject"))
    subjects = [s.strip() for s in re.split(r"[,;]", info.get("keywords", ""))]
    subjects = [s for s in subjects if s and not junk_subject(s)]
    if subjects:
        found.append(Candidate("subjects", subjects, "info", "info:keywords"))
    return found


def _listify(value: Any) -> list[str]:
    if value is None:
        return []
    items = value if isinstance(value, (list, tuple)) else [value]
    return [" ".join(str(v).split()) for v in items if str(v).strip()]


def _from_xmp(xmp: dict[str, Any], stems: set[str]) -> list[Candidate]:
    found: list[Candidate] = []

    def first(key: str) -> str:
        values = _listify(xmp.get(key))
        return values[0] if values else ""

    title = first("dc_title")
    if title and not junk_title(title, stems):
        found.append(Candidate("title", title, "xmp", "xmp:dc_title"))
    authors = [a for v in _listify(xmp.get("dc_creator")) for a in _split_people(v)]
    if authors:
        found.append(Candidate("authors", authors, "xmp", "xmp:dc_creator"))
    if primary(first("dc_language")):
        found.append(Candidate("language", first("dc_language"), "xmp", "xmp:dc_language"))
    publisher = first("dc_publisher")
    if publisher and not junk_person(publisher):
        found.append(Candidate("publisher", publisher, "xmp", "xmp:dc_publisher"))
    date = _date(first("dc_date"))
    if date:
        found.append(Candidate("date", date, "xmp", "xmp:dc_date"))
    description = first("dc_description")
    if description and not junk_subject(description):
        found.append(Candidate("description", description, "xmp", "xmp:dc_description"))
    subjects = [s for s in _listify(xmp.get("dc_subject")) if not junk_subject(s)]
    if subjects:
        found.append(Candidate("subjects", subjects, "xmp", "xmp:dc_subject"))
    for raw in _listify(xmp.get("dc_identifier")):
        ident = _identifier(raw, keep_other=False)
        if ident:
            found.append(Candidate("identifiers", ident[0], "xmp", f"xmp:dc_identifier {ident[1]}"))
    return found


def _from_catalog(catalog: dict[str, Any], stems: set[str]) -> list[Candidate]:
    found: list[Candidate] = []
    for field in SCALARS:
        value = catalog.get(field)
        if value is None or not str(value).strip():
            continue
        value = " ".join(str(value).split())
        if field == "date":
            value = _date(value)
        if field in ("title", "subtitle") and junk_title(value, stems):
            continue
        if value:
            found.append(Candidate(field, value, "catalog", f"catalog:{field}"))
    authors = [_display_name(a) for a in _listify(catalog.get("authors"))]
    authors = [a for a in authors if not junk_person(a)]
    if authors:
        found.append(Candidate("authors", authors, "catalog", "catalog:authors"))
        if catalog.get("author_sort"):
            sort = str(catalog["author_sort"]).strip()
            found.append(Candidate("author_sort", sort, "catalog", "catalog:author_sort"))
    subjects = [s for s in _listify(catalog.get("subjects")) if not junk_subject(s)]
    if subjects:
        found.append(Candidate("subjects", subjects, "catalog", "catalog:subjects"))
    for key in ("identifiers", "isbn"):
        for raw in _listify(catalog.get(key)):
            ident = _identifier(raw, keep_other=True)
            if ident:
                found.append(Candidate("identifiers", ident[0], "catalog", f"catalog:{key}"))
    return found


def _date(text: str) -> str | None:
    match = re.match(r"\s*(\d{4})(?:-(\d{2})(?:-(\d{2}))?)?", text or "")
    if not match or not _YEAR.fullmatch(match.group(1)):
        return None
    return "-".join(g for g in match.groups() if g)


# --------------------------------------------------------------------------- front matter


def _lines(page: PageInventory) -> list[tuple[str, Line]]:
    return [
        (block.id, line)
        for block in page.blocks
        if block.role not in _FURNITURE
        for line in block.lines
        if line.text.strip()
    ]


def _size(line: Line) -> float:
    return max((w.font_size or 0.0) for w in line.words) if line.words else 0.0


def _body_size(pages: list[PageInventory]) -> float:
    sizes = [
        w.font_size for page in pages for _, line in _lines(page) for w in line.words if w.font_size
    ]
    return statistics.median(sizes) if sizes else 0.0


def _front(pages: list[PageInventory]) -> list[PageInventory]:
    front = pages[:FRONT_PAGES]
    return front + [pages[-1]] if len(pages) > FRONT_PAGES else front


def _from_copyright(pages: list[PageInventory]) -> tuple[list[Candidate], set[int], list[str]]:
    """Candidates from copyright pages, their page indices, and the copyright holders."""
    found: list[Candidate] = []
    indices: set[int] = set()
    holders: list[str] = []
    for page in _front(pages):
        lines = _lines(page)
        if not any(_COPYRIGHT_MARKERS.search(line.text) for _, line in lines):
            continue
        indices.add(page.index)
        notices: list[str] = []
        reserved: list[str] = []
        for bid, line in lines:
            text = " ".join(line.text.split())
            for match in _ISBN.finditer(text):
                ident = _identifier(match.group(1), keep_other=False)
                if ident:
                    found.append(
                        Candidate("identifiers", ident[0], "copyright_page", f"{bid} {ident[1]}")
                    )
                else:
                    log.info("rejected an ISBN with a bad check digit in %s", bid)
            for match in _DOI.finditer(text):
                ident = _identifier(match.group(1), keep_other=False)
                if ident:
                    found.append(
                        Candidate("identifiers", ident[0], "copyright_page", f"{bid} {ident[1]}")
                    )
            years = _YEAR.findall(text)
            if _COPYRIGHT_LINE.search(text):
                notices.append(text)
                if years:
                    found.append(Candidate("date", max(years), "copyright_page", f"{bid} year=1"))
                holder = _HOLDER.search(text)
                if holder:
                    holders += _split_people(re.split(r"\.\s", holder.group(1))[0].rstrip(". "))
            elif _EDITION_LINE.search(text) and years:
                found.append(Candidate("date", min(years), "copyright_page", f"{bid} edition=1"))
            elif _RESERVED.search(text):
                reserved.append(text)
            publisher = _PUBLISHER.search(text)
            if publisher:
                name = re.split(r"[,;]|\s+\d{4}\b|\s+in\s+", publisher.group(1))[0].strip(" .")
                if name and len(name) <= 80 and not junk_person(name):
                    cue = f"{bid} cue=published_by"
                    found.append(Candidate("publisher", name, "copyright_page", cue))
        if notices:
            rights = " ".join(notices + reserved)
            found.append(Candidate("rights", rights, "copyright_page", f"p{page.index}"))
    # The first copyright line's year beats an edition year: put edition dates last.
    found.sort(key=lambda c: c.field == "date" and "edition=1" in c.evidence)
    return found, indices, holders


def _title_page(
    pages: list[PageInventory], skip: set[int], body: float
) -> tuple[PageInventory, list[tuple[str, Line]]] | None:
    """The first front page with few words and a large centered line near the top."""
    if body <= 0:
        return None
    for page in pages[:FRONT_PAGES]:
        lines = _lines(page)
        if page.index in skip or not lines or len(lines) > 12:
            continue
        if sum(len(line.words) for _, line in lines) > 60:
            continue
        top = max(lines, key=lambda item: _size(item[1]))[1]
        centered = abs((top.box.x0 + top.box.x1) / 2 - 0.5) <= 0.08
        if _size(top) >= 1.5 * body and centered and top.box.y0 < 0.6:
            if _norm(top.text) not in _HEADINGS:
                return page, lines
    return None


def _from_title_page(
    pages: list[PageInventory], skip: set[int], known_people: list[str]
) -> list[Candidate]:
    body = _body_size(pages)
    hit = _title_page(pages, skip, body)
    if hit is None:
        return []
    page, lines = hit
    top_size = max(_size(line) for _, line in lines)
    title_lines = [(b, ln) for b, ln in lines if abs(_size(ln) - top_size) < 0.5]
    title = " ".join(" ".join(ln.text.split()) for _, ln in title_lines)
    title_bottom = max(ln.box.y1 for _, ln in title_lines)
    found = [
        Candidate(
            "title",
            title,
            "title_page",
            " ".join(sorted({b for b, _ in title_lines})) + f" size={top_size:g} body={body:g}",
        )
    ]
    known = {_norm(p) for p in known_people}
    rest = [(b, ln) for b, ln in lines if (b, ln) not in title_lines and _size(ln) >= 0.95 * body]
    author_line = None
    for bid, line in rest:
        text = " ".join(line.text.split())
        prefixed = re.match(r"(?:by|di|a cura di)\s+(.+)", text, re.I)
        names = _split_people(prefixed.group(1) if prefixed else text)
        if not names:
            continue
        if prefixed:
            cue = "by_prefix"
        elif all(_norm(n) in known for n in names):
            cue = "known_name"
        elif all(_looks_like_name(n) for n in names):
            cue = "name_shape"
        else:
            continue
        found.append(Candidate("authors", names, "title_page", f"{bid} cue={cue}"))
        author_line = line
        break
    limit = author_line.box.y0 if author_line and author_line.box.y0 > title_bottom else 1.0
    for bid, line in rest:
        below = title_bottom - 0.01 <= line.box.y0 < limit
        if line is not author_line and below and _size(line) > 1.05 * body:
            found.append(
                Candidate(
                    "subtitle",
                    " ".join(line.text.split()),
                    "title_page",
                    f"{bid} size={_size(line):g}",
                )
            )
            break
    return [c for c in found if c.field != "title" or not junk_title(c.value, set())]


# --------------------------------------------------------------------------- blurb


def _paragraph(lines: list[Line]) -> str:
    """Line texts joined with single spaces; a line-end hyphen before a lowercase letter
    is a broken word and is closed up, as the paragraph repair does."""
    text = ""
    for line in lines:
        part = " ".join(line.text.split())
        if re.search(r"[^\W\d_]-$", text) and part[:1].islower():
            text = text[:-1] + part
        else:
            text = f"{text} {part}" if text else part
    return text


def _blurb_cues(texts: list[str]) -> list[str]:
    found = [name for name, rx in _STRONG_CUES.items() if any(rx.search(t) for t in texts)]
    weak = [name for name, rx in _WEAK_CUES.items() if any(rx.search(t) for t in texts)]
    return found + weak if found or len(weak) >= 2 else []


def _blurb_page(page: PageInventory) -> Candidate | None:
    """Promotional prose printed on ``page``, or None when it could be anything else."""
    if any(block.role == "folio" for block in page.blocks):
        return None  # numbered pages belong to the book's body flow
    lines = [" ".join(line.text.split()) for _, line in _lines(page)]
    if not lines or any(_norm(t) in _HEADINGS or _STATEMENT.search(t) for t in lines):
        return None
    if sum(1 for t in lines if _LOCATOR.search(t)) >= 0.3 * len(lines):
        return None  # contents or index
    cues = _blurb_cues(lines)
    if not cues:
        return None
    every = [*_STRONG_CUES.values(), *_WEAK_CUES.values()]
    ids, paragraphs = [], []
    for block in page.blocks:
        if block.role in _FURNITURE:
            continue
        texts = [" ".join(line.text.split()) for line in block.lines]
        text = _paragraph(block.lines)
        if len(text.split()) < 8 or text.startswith(_QUOTE_OPEN):
            continue  # labels, titles and review quotes are not the description
        if any(rx.search(t) for rx in every for t in texts):
            continue
        ids.append(block.id)
        paragraphs.append(text)
    if not paragraphs or sum(len(p.split()) for p in paragraphs) < _BLURB_MIN_WORDS:
        return None
    if paragraphs[0][:1].islower():
        return None  # continues a sentence from the page before
    evidence = f"{' '.join(ids)} page={page.index} cue={cues[0]}"
    return Candidate("description", "\n\n".join(paragraphs), "blurb_page", evidence)


def _from_blurb(pages: list[PageInventory]) -> list[Candidate]:
    """A back-cover or jacket blurb from the last two pages or the two after a cover."""
    if len(pages) < 2:
        return []
    order = [len(pages) - 1, len(pages) - 2]
    if cover_evidence(pages[0], _body_size(pages)):
        order += [1, 2]
    for index in dict.fromkeys(i for i in order if 0 < i < len(pages)):
        found = _blurb_page(pages[index])
        if found:
            return [found]
    return []


# --------------------------------------------------------------------------- resolution


def _differs(field: str, a: Any, b: Any) -> bool:
    if field == "authors" or field == "subjects":
        return {_norm(x) for x in a} != {_norm(x) for x in b}
    if field == "date":
        return str(a)[:4] != str(b)[:4]
    if field == "language":
        return primary(a) != primary(b)
    x, y = _norm(str(a)), _norm(str(b))
    return x != y and not (x.startswith(y) or y.startswith(x))


def _resolve(ctx: ConvertContext, field: str, cands: list[Candidate]) -> Candidate | None:
    ranked = sorted(
        (c for c in cands if c.field == field), key=lambda c: -TRUST[c.source]
    )  # stable: the first gathered wins a tie
    if not ranked:
        return None
    best = ranked[0]
    flagged: set[str] = set()
    for other in ranked[1:]:
        if other.source in flagged or other.source == best.source:
            continue
        if _differs(field, best.value, other.value):
            flagged.add(other.source)
            ctx.issue(
                "meta.conflict",
                Severity.MINOR,
                f"{field}: {best.source} kept over a different {other.source} value",
                evidence=f"meta:{field} kept=src:{best.source} over=src:{other.source}",
                action="kept_higher_trust",
            )
    return best


def _text(value: Any) -> str | None:
    if value is None:
        return None
    return "; ".join(value) if isinstance(value, list) else str(value)


def _set(ctx: ConvertContext, field: str, value: Any, cand: Candidate) -> None:
    md = ctx.book.metadata
    before = getattr(md, field)
    setattr(md, field, value)
    ctx.metadata.append(MetadataField(field, value, cand.source, cand.evidence))
    ctx.record(
        "metadata",
        f"set_{field}",
        field,
        cand.evidence.split(),
        before=_text(before),
        after=_text(value),
    )


def _drop(ctx: ConvertContext, field: str, empty: Any, why: str = "calibre:default") -> None:
    md = ctx.book.metadata
    before = getattr(md, field)
    setattr(md, field, empty)
    ctx.record("metadata", f"drop_{field}", field, [why], before=_text(before))


# --------------------------------------------------------------------------- apply


def apply(ctx: ConvertContext) -> None:
    source, md = ctx.source, ctx.book.metadata
    stems = {_norm(ctx.pdf_path.stem), "source"}
    cands = _from_info(source.info, stems) + _from_xmp(source.xmp, stems)
    copyright_cands, copyright_pages, holders = _from_copyright(source.pages)
    known = holders + [a for c in cands if c.field == "authors" for a in c.value]
    cands += copyright_cands + _from_title_page(source.pages, copyright_pages, known)
    cands += _from_blurb(source.pages)
    if source.language:
        cands.append(Candidate("language", source.language, "detected", "source:language"))
    if ctx.catalog_metadata:
        cands += _from_catalog(ctx.catalog_metadata, stems)

    for field in SCALARS:
        best = _resolve(ctx, field, cands)
        if best:
            _set(ctx, field, best.value, best)
        elif field == "title" and md.title and junk_title(md.title, stems):
            _drop(ctx, "title", None)
        elif field == "language" and md.language:
            _drop(ctx, "language", None)  # Calibre's default, not evidence
    if md.subtitle and md.title and _norm(md.subtitle) in _norm(md.title):
        _drop(ctx, "subtitle", None, "meta:subtitle_in_title")

    authors = _resolve(ctx, "authors", cands)
    if authors:
        _set(ctx, "authors", authors.value, authors)
        given = next((c for c in cands if c.field == "author_sort"), None)
        if authors.source == "catalog" and given:
            _set(ctx, "author_sort", given.value, given)
        else:
            _set(ctx, "author_sort", sort_name(authors.value[0]), authors)
    else:
        if md.authors and all(junk_person(a) for a in md.authors):
            _drop(ctx, "authors", [])
        if md.author_sort and (not md.authors or junk_person(md.author_sort)):
            _drop(ctx, "author_sort", None)

    subjects = _resolve(ctx, "subjects", cands)
    if subjects:
        _set(ctx, "subjects", subjects.value, subjects)
    elif any(junk_subject(s) for s in md.subjects):
        _drop(ctx, "subjects", [s for s in md.subjects if not junk_subject(s)])

    _add_identifiers(ctx, cands)
    _set_doc_titles(ctx)
    _cover(ctx)

    if not md.title:
        ctx.issue(
            "meta.missing_title",
            Severity.MAJOR,
            "no source names the book's title",
            action="flagged",
        )
    if not md.authors:
        ctx.issue(
            "meta.missing_author",
            Severity.MAJOR,
            "no source names the book's author",
            action="flagged",
        )
    log.info("metadata: %d fields filled from %d candidates", len(ctx.metadata), len(cands))


def _add_identifiers(ctx: ConvertContext, cands: list[Candidate]) -> None:
    md = ctx.book.metadata
    seen = {_isbn_key(i) for i in md.identifiers}
    ranked = sorted((c for c in cands if c.field == "identifiers"), key=lambda c: -TRUST[c.source])
    for cand in ranked:
        key = _isbn_key(cand.value)
        if key in seen:
            continue
        seen.add(key)
        md.identifiers.append(cand.value)  # slot 0 stays the rendition id
        ctx.metadata.append(MetadataField("identifiers", cand.value, cand.source, cand.evidence))
        ctx.record(
            "metadata", "add_identifier", "identifiers", cand.evidence.split(), after=cand.value
        )


def _set_doc_titles(ctx: ConvertContext) -> None:
    title = ctx.book.metadata.title
    if not title:
        return
    changed = 0
    for doc in ctx.book.docs:
        el = doc.tree.find(f"{{{XHTML_NS}}}head/{{{XHTML_NS}}}title")
        if el is not None and el.text != title:
            el.text = title
            changed += 1
    if changed:
        ctx.record("metadata", "set_doc_titles", "content_docs", [f"docs={changed}"], after=title)


# --------------------------------------------------------------------------- cover


def cover_evidence(page: PageInventory, body: float) -> str | None:
    """Evidence that ``page`` is visually a cover, or None for an ordinary page."""
    lines = _lines(page)
    words = sum(len(line.words) for _, line in lines)
    if words > 60:
        return None
    art = min(1.0, sum(region.box.area for region in page.images))
    largest = max((_size(line) for _, line in lines), default=0.0)
    big_title = body > 0 and largest >= 1.6 * body
    if art >= 0.5 or (big_title and page.ink_coverage >= 0.3):
        return f"p{page.index} art={art:.2f} ink={page.ink_coverage:.2f} words={words}"
    return None


def _cover(ctx: ConvertContext) -> None:
    book = ctx.book
    if book.metadata.cover_href or not ctx.source.pages:
        return
    evidence = cover_evidence(ctx.source.pages[0], _body_size(ctx.source.pages))
    if evidence is None:
        return
    png = ctx.workdir / "metadata" / "cover.png"
    try:
        render_region(ctx.pdf_path, 0, None, COVER_DPI, png, ctx.toolchain)
    except StageError as exc:
        log.warning("cover render failed: %s", exc.reason)
        ctx.issue("meta.cover_failed", Severity.MINOR, "first page could not be rendered")
        return
    out = io.BytesIO()
    with Image.open(png) as img:
        img.convert("RGB").save(out, format="JPEG", quality=85, optimize=True)
    base = book.opf_href.rpartition("/")[0]
    href = f"{base}/cover.jpg" if base else "cover.jpg"
    if href in book.assets or any(doc.href == href for doc in book.docs):
        href = href.replace("cover.jpg", "qc-cover.jpg")
    book.add_asset(href, out.getvalue(), "image/jpeg")
    book.set_cover(href)
    ctx.metadata.append(MetadataField("cover", href, "first_page", evidence))
    ctx.record("metadata", "set_cover", "cover", evidence.split(), after=href)
