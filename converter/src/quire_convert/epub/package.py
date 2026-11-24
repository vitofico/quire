"""Deterministic EPUB 3.3 serialization of a Book, plus the href helpers both sides share.

The same Book saved twice gives byte-identical archives: package files are regenerated
from the model, members go in a fixed order with fixed timestamps, compression and
attributes, and nothing time- or host-dependent is written except ``modified``.
"""

from __future__ import annotations

import os
import posixpath
import re
import zipfile
from pathlib import Path
from typing import TYPE_CHECKING
from urllib.parse import quote, unquote

from lxml import etree

from ..models import StageError

if TYPE_CHECKING:
    from .book import Book, NavEntry

XHTML_NS = "http://www.w3.org/1999/xhtml"
EPUB_NS = "http://www.idpf.org/2007/ops"
OPF_NS = "http://www.idpf.org/2007/opf"
DC_NS = "http://purl.org/dc/elements/1.1/"
NCX_NS = "http://www.daisy.org/z3986/2005/ncx/"
CONTAINER_NS = "urn:oasis:names:tc:opendocument:xmlns:container"
SVG_NS = "http://www.w3.org/2000/svg"
MATHML_NS = "http://www.w3.org/1998/Math/MathML"
XLINK_NS = "http://www.w3.org/1999/xlink"
XML_LANG = "{http://www.w3.org/XML/1998/namespace}lang"
XHTML_TYPE = "application/xhtml+xml"
NCX_TYPE = "application/x-dtbncx+xml"

# Attributes that reference another resource or location.
LINK_ATTRS = ("href", "src", f"{{{XLINK_NS}}}href", "poster", "data")
ANCHOR_TAGS = frozenset({"a", "area"})

_ZIP_TIME = (1980, 1, 1, 0, 0, 0)
_SCHEME = re.compile(r"^([a-zA-Z][a-zA-Z0-9+.-]*):")
_MODIFIED = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$")
_REMOTE_CSS = r"(?:[a-zA-Z][a-zA-Z0-9+.-]*:(?<!data:)|//)"
_CSS_IMPORT = re.compile(r"@import\s+(?:url\(\s*)?['\"]?\s*" + _REMOTE_CSS + r"[^;]*;?", re.I)
_CSS_URL = re.compile(r"url\(\s*['\"]?\s*" + _REMOTE_CSS + r"[^)]*\)", re.I)


# --------------------------------------------------------------------------- hrefs


def local_name(el: etree._Element) -> str:
    return etree.QName(el).localname


def scheme(ref: str) -> str | None:
    """Lower-cased URL scheme, ``"//"`` for protocol-relative refs, None for local refs."""
    if ref.startswith("//"):
        return "//"
    match = _SCHEME.match(ref.strip())
    return match.group(1).lower() if match else None


def resolve_href(base: str, ref: str) -> str:
    """Resolve a local ``ref`` found in member ``base`` to an archive path (plus fragment)."""
    path, _, frag = ref.partition("#")
    target = base.partition("#")[0]
    if path:
        target = posixpath.normpath(posixpath.join(posixpath.dirname(target), unquote(path)))
    return f"{target}#{unquote(frag)}" if frag else target


def relative_href(from_href: str, target: str) -> str:
    """Inverse of resolve_href: the ref to write in ``from_href`` to reach ``target``."""
    path, _, frag = target.partition("#")
    if path == from_href and frag:
        return "#" + quote(frag, safe="")
    rel = posixpath.relpath(path, posixpath.dirname(from_href) or ".")
    return quote(rel, safe="/") + ("#" + quote(frag, safe="") if frag else "")


# --------------------------------------------------------------------------- sanitizing


def sanitize(book: Book) -> list[str]:
    """Remove active content and remote resources in place; return what was removed.

    The notes name documents, elements and attributes only, never URLs or book text.
    """
    notes: list[str] = []
    for doc in book.docs:
        for el in list(doc.tree.iter(etree.Element)):
            if el.getroottree().getroot() is not doc.tree:
                continue  # inside an element removed earlier in this pass
            name = local_name(el)
            if name == "script":
                notes.append(f"{doc.href}: removed <script>")
                _remove(el)
                continue
            for attr in list(el.attrib):
                local = etree.QName(attr).localname
                if local.lower().startswith("on"):
                    del el.attrib[attr]
                    notes.append(f"{doc.href}: removed {local} attribute on <{name}>")
            if _strip_refs(el, name):
                notes.append(f"{doc.href}: removed <{name}> with a remote or script reference")
                _remove(el)
                continue
            if name == "style" and el.text:
                cleaned = _clean_css(el.text)
                if cleaned != el.text:
                    el.text = cleaned
                    notes.append(f"{doc.href}: removed remote CSS reference in <style>")
    for asset in book.assets.values():
        if asset.media_type == "text/css":
            text = asset.data.decode("utf-8", errors="replace")
            cleaned = _clean_css(text)
            if cleaned != text:
                asset.data = cleaned.encode("utf-8")
                notes.append(f"{asset.href}: removed remote CSS reference")
    return notes


def _strip_refs(el: etree._Element, name: str) -> bool:
    """Drop script links on anchors; True when a non-anchor element must go entirely."""
    for attr in LINK_ATTRS:
        value = el.get(attr)
        if value is None:
            continue
        kind = scheme(value)
        if name in ANCHOR_TAGS:
            if kind in ("javascript", "vbscript"):
                del el.attrib[attr]
        elif kind is not None and kind != "data":
            return True
    return False


def _clean_css(text: str) -> str:
    return _CSS_URL.sub("none", _CSS_IMPORT.sub("", text))


def _remove(el: etree._Element) -> None:
    parent = el.getparent()
    if parent is None:
        return
    if el.tail:
        prev = el.getprevious()
        if prev is not None:
            prev.tail = (prev.tail or "") + el.tail
        else:
            parent.text = (parent.text or "") + el.tail
    parent.remove(el)


# --------------------------------------------------------------------------- writing


def write_epub(book: Book, path: Path, *, modified: str) -> list[str]:
    """Sanitize ``book`` and write it to ``path`` atomically; return the sanitize notes."""
    if not _MODIFIED.match(modified):
        raise ValueError(f"modified must look like 2024-01-31T00:00:00Z, got {modified!r}")
    if not book.metadata.identifiers:
        raise StageError("package", "the book has no identifier", retryable=False)
    notes = sanitize(book)
    ids = _manifest_ids(book)
    members: list[tuple[str, bytes]] = [
        ("mimetype", b"application/epub+zip"),
        ("META-INF/container.xml", _container(book.opf_href)),
        (book.opf_href, _opf(book, ids, modified)),
        (book.nav_href, _nav(book)),
        (book.ncx_href, _ncx(book)),
    ]
    members += [(doc.href, _content(doc)) for doc in book.docs]
    members += [(href, book.assets[href].data) for href in sorted(book.assets)]
    names = [name for name, _ in members]
    if len(set(names)) != len(names):
        raise StageError("package", "two members share an archive path", retryable=False)
    _write_zip(members, path)
    return notes


def _write_zip(members: list[tuple[str, bytes]], path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(f".{path.name}.tmp")
    with zipfile.ZipFile(tmp, "w") as zf:
        for name, data in members:
            info = zipfile.ZipInfo(name, date_time=_ZIP_TIME)
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            if name == "mimetype":
                info.compress_type = zipfile.ZIP_STORED
                zf.writestr(info, data)
            else:
                info.compress_type = zipfile.ZIP_DEFLATED
                zf.writestr(info, data, compresslevel=9)
    os.replace(tmp, path)


def _xml(root: etree._Element) -> bytes:
    return etree.tostring(root, xml_declaration=True, encoding="utf-8", pretty_print=True)


def _container(opf_href: str) -> bytes:
    root = etree.Element(f"{{{CONTAINER_NS}}}container", version="1.0", nsmap={None: CONTAINER_NS})
    rootfiles = etree.SubElement(root, f"{{{CONTAINER_NS}}}rootfiles")
    etree.SubElement(
        rootfiles,
        f"{{{CONTAINER_NS}}}rootfile",
        {"full-path": opf_href, "media-type": "application/oebps-package+xml"},
    )
    return _xml(root)


def _content(doc) -> bytes:
    if doc.media_type == XHTML_TYPE and doc.tree.tag != f"{{{XHTML_NS}}}html":
        raise StageError("package", f"{doc.href} is not namespaced XHTML", retryable=False)
    etree.cleanup_namespaces(doc.tree, top_nsmap={"epub": EPUB_NS, "xlink": XLINK_NS})
    return etree.tostring(doc.tree, xml_declaration=True, encoding="utf-8")


def _manifest_ids(book: Book) -> dict[str, str]:
    ids = {doc.href: doc.id for doc in book.docs}
    ids[book.nav_href] = "qc-nav"
    ids[book.ncx_href] = "qc-ncx"
    for n, href in enumerate(sorted(book.assets), 1):
        ids[href] = f"qc-res-{n:04d}"
    if len(set(ids.values())) != len(ids):
        raise StageError("package", "manifest ids collide", retryable=False)
    return ids


def _opf(book: Book, ids: dict[str, str], modified: str) -> bytes:
    opf = f"{{{OPF_NS}}}"
    root = etree.Element(
        opf + "package",
        {"version": "3.0", "unique-identifier": "qc-id"},
        nsmap={None: OPF_NS, "dc": DC_NS},
    )
    _metadata(etree.SubElement(root, opf + "metadata"), book, ids, modified)
    manifest = etree.SubElement(root, opf + "manifest")

    def item(href: str, media_type: str, properties: set[str]) -> None:
        attrs = {"id": ids[href], "href": relative_href(book.opf_href, href)}
        attrs["media-type"] = media_type
        if properties:
            attrs["properties"] = " ".join(sorted(properties))
        etree.SubElement(manifest, opf + "item", attrs)

    for doc in book.docs:
        item(doc.href, doc.media_type, _doc_properties(doc.tree))
    item(book.nav_href, XHTML_TYPE, {"nav"})
    item(book.ncx_href, NCX_TYPE, set())
    for href in sorted(book.assets):
        asset = book.assets[href]
        props = set(asset.properties) - {"cover-image"}
        if href == book.metadata.cover_href:
            props.add("cover-image")
        item(href, asset.media_type, props)
    spine = etree.SubElement(root, opf + "spine", toc=ids[book.ncx_href])
    for doc in book.docs:
        attrs = {"idref": doc.id}
        if not doc.linear:
            attrs["linear"] = "no"
        etree.SubElement(spine, opf + "itemref", attrs)
    return _xml(root)


def _metadata(md: etree._Element, book: Book, ids: dict[str, str], modified: str) -> None:
    meta = book.metadata
    dc = f"{{{DC_NS}}}"

    def add(tag: str, text: str, **attrs: str) -> etree._Element:
        el = etree.SubElement(md, tag, attrs)
        el.text = text
        return el

    def refine(target: str, prop: str, text: str, **attrs: str) -> None:
        add(f"{{{OPF_NS}}}meta", text, refines=f"#{target}", property=prop, **attrs)

    add(dc + "identifier", meta.identifiers[0], id="qc-id")
    for value in meta.identifiers[1:]:
        add(dc + "identifier", value)
    # dc:title is required; "Untitled" only stands in when no source named the book.
    add(dc + "title", meta.title or "Untitled", id="qc-title")
    refine("qc-title", "title-type", "main")
    if meta.subtitle:
        add(dc + "title", meta.subtitle, id="qc-subtitle")
        refine("qc-subtitle", "title-type", "subtitle")
    add(dc + "language", meta.language or "und")
    for n, author in enumerate(meta.authors, 1):
        add(dc + "creator", author, id=f"qc-creator-{n}")
        refine(f"qc-creator-{n}", "role", "aut", scheme="marc:relators")
        if n == 1 and meta.author_sort:
            refine("qc-creator-1", "file-as", meta.author_sort)
    for tag, value in (
        ("publisher", meta.publisher),
        ("date", meta.date),
        ("description", meta.description),
    ):
        if value:
            add(dc + tag, value)
    for subject in meta.subjects:
        add(dc + "subject", subject)
    if meta.rights:
        add(dc + "rights", meta.rights)
    if meta.series:
        add(f"{{{OPF_NS}}}meta", meta.series, property="belongs-to-collection", id="qc-series")
        refine("qc-series", "collection-type", "series")
        if meta.series_index:
            refine("qc-series", "group-position", meta.series_index)
        # Calibre and Calibre-Web read series from these legacy OPF 2 metas.
        etree.SubElement(md, f"{{{OPF_NS}}}meta", name="calibre:series", content=meta.series)
        if meta.series_index:
            etree.SubElement(
                md, f"{{{OPF_NS}}}meta", name="calibre:series_index", content=meta.series_index
            )
    if meta.cover_href and meta.cover_href in ids:
        etree.SubElement(md, f"{{{OPF_NS}}}meta", name="cover", content=ids[meta.cover_href])
    add(f"{{{OPF_NS}}}meta", modified, property="dcterms:modified")


def _doc_properties(tree: etree._Element) -> set[str]:
    props = set()
    if next(tree.iter(f"{{{SVG_NS}}}svg"), None) is not None:
        props.add("svg")
    if next(tree.iter(f"{{{MATHML_NS}}}math"), None) is not None:
        props.add("mathml")
    return props


def _toc_entries(book: Book) -> list[NavEntry]:
    if book.toc:
        return book.toc
    # A nav toc must not be empty; point a single generated entry at the first document.
    from .book import NavEntry

    first = next((d for d in book.docs if d.linear), book.docs[0])
    return [NavEntry(title=book.metadata.title or "Start", href=first.href)]


def _nav(book: Book) -> bytes:
    h = f"{{{XHTML_NS}}}"
    lang = book.metadata.language or "und"
    root = etree.Element(
        h + "html", {"lang": lang, XML_LANG: lang}, nsmap={None: XHTML_NS, "epub": EPUB_NS}
    )
    head = etree.SubElement(root, h + "head")
    etree.SubElement(head, h + "title").text = book.metadata.title or "Contents"
    body = etree.SubElement(root, h + "body")

    def nav(kind: str, role: str | None) -> etree._Element:
        attrs = {f"{{{EPUB_NS}}}type": kind, "id": kind}
        if role:
            attrs["role"] = role
        if kind != "toc":
            attrs["hidden"] = ""
        return etree.SubElement(etree.SubElement(body, h + "nav", attrs), h + "ol")

    def link(parent: etree._Element, title: str, href: str, **attrs: str) -> etree._Element:
        li = etree.SubElement(parent, h + "li")
        if href:
            a = etree.SubElement(li, h + "a", {"href": relative_href(book.nav_href, href)})
            for key, value in attrs.items():
                a.set(key, value)
        else:
            a = etree.SubElement(li, h + "span")
        a.text = title
        return li

    def toc(parent: etree._Element, entries: list[NavEntry]) -> None:
        for entry in entries:
            li = link(parent, entry.title, entry.href)
            if entry.children:
                toc(etree.SubElement(li, h + "ol"), entry.children)

    toc(nav("toc", "doc-toc"), _toc_entries(book))
    if book.page_list:
        ol = nav("page-list", "doc-pagelist")
        for label, href in book.page_list:
            link(ol, label, href)
    if book.landmarks:
        ol = nav("landmarks", None)
        for mark in book.landmarks:
            link(ol, mark.title, mark.href, **{f"{{{EPUB_NS}}}type": mark.type})
    return _xml(root)


def _ncx(book: Book) -> bytes:
    n = f"{{{NCX_NS}}}"
    lang = book.metadata.language or "und"
    root = etree.Element(n + "ncx", {"version": "2005-1", XML_LANG: lang}, nsmap={None: NCX_NS})
    head = etree.SubElement(root, n + "head")
    entries = _toc_entries(book)
    for name, content in (
        ("dtb:uid", book.metadata.identifiers[0]),
        ("dtb:depth", str(_depth(entries))),
        ("dtb:totalPageCount", "0"),
        ("dtb:maxPageNumber", "0"),
    ):
        etree.SubElement(head, n + "meta", name=name, content=content)
    title = etree.SubElement(root, n + "docTitle")
    etree.SubElement(title, n + "text").text = book.metadata.title or "Untitled"
    # NCX requires one playOrder per distinct target, numbered in first-seen order.
    order: dict[str, int] = {}

    def target(parent: etree._Element, tag: str, ident: str, label: str, href: str):
        play = order.setdefault(href, len(order) + 1)
        el = etree.SubElement(parent, n + tag, id=ident, playOrder=str(play))
        etree.SubElement(etree.SubElement(el, n + "navLabel"), n + "text").text = label
        etree.SubElement(el, n + "content", src=relative_href(book.ncx_href, href))
        return el

    counter = iter(range(1, 1_000_000))

    def points(parent: etree._Element, items: list[NavEntry]) -> None:
        for entry in items:
            href = entry.href or _first_href(entry)
            if not href:
                continue
            el = target(parent, "navPoint", f"np-{next(counter)}", entry.title, href)
            points(el, entry.children)

    points(etree.SubElement(root, n + "navMap"), entries)
    if book.page_list:
        page_list = etree.SubElement(root, n + "pageList")
        for k, (label, href) in enumerate(book.page_list, 1):
            el = target(page_list, "pageTarget", f"pt-{k}", label, href)
            el.set("type", "normal")
            if label.isdigit():
                el.set("value", label)
    return _xml(root)


def _depth(entries: list[NavEntry]) -> int:
    return max((1 + _depth(e.children) for e in entries), default=0)


def _first_href(entry: NavEntry) -> str:
    for child in entry.children:
        if href := child.href or _first_href(child):
            return href
    return ""
