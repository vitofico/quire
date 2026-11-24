"""In-memory, mutable EPUB model read through the documented structure (container -> OPF).

Every href in the model is an archive path (relative to the archive root, unquoted),
optionally with ``#fragment``. Links inside content documents stay relative in the XHTML;
``iter_links`` resolves them and ``rewrite_links`` writes them back relative.
"""

from __future__ import annotations

import copy
import posixpath
import re
import zipfile
from collections.abc import Iterator
from dataclasses import dataclass, field
from pathlib import Path

from lxml import etree

from ..models import BLOCK_ID_PREFIX, OutputBlock, StageError
from .package import (
    CONTAINER_NS,
    DC_NS,
    EPUB_NS,
    LINK_ATTRS,
    NCX_NS,
    NCX_TYPE,
    OPF_NS,
    XHTML_NS,
    local_name,
    relative_href,
    resolve_href,
    scheme,
)

BLOCK_TAGS = frozenset(
    {"p", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote", "pre", "figure"}
    | {"figcaption", "dt", "dd", "td", "th"}
)
# Containers that disqualify a div from being a text block of its own.
_STRUCTURAL = BLOCK_TAGS | {
    "div",
    "section",
    "article",
    "aside",
    "nav",
    "header",
    "footer",
    "main",
    "table",
    "ul",
    "ol",
    "dl",
    "img",
}
_MEDIA = frozenset({"img", "svg", "video", "audio", "object", "embed", "iframe", "math", "canvas"})
_PARSER = etree.XMLParser(resolve_entities=False, no_network=True, huge_tree=True)


@dataclass
class ContentDoc:
    href: str
    id: str
    tree: etree._Element  # the <html> root element
    media_type: str = "application/xhtml+xml"
    linear: bool = True

    @property
    def body(self) -> etree._Element:
        body = self.tree.find(f"{{{XHTML_NS}}}body")
        if body is None:
            raise StageError("epub", f"{self.href} has no body", retryable=False)
        return body


@dataclass
class Asset:
    href: str
    data: bytes
    media_type: str
    properties: set[str] = field(default_factory=set)


@dataclass
class BookMetadata:
    title: str | None = None
    subtitle: str | None = None
    authors: list[str] = field(default_factory=list)
    author_sort: str | None = None
    language: str | None = None
    identifiers: list[str] = field(default_factory=list)  # [0] is the rendition id slot
    publisher: str | None = None
    date: str | None = None
    description: str | None = None
    subjects: list[str] = field(default_factory=list)
    series: str | None = None
    series_index: str | None = None
    rights: str | None = None
    cover_href: str | None = None


@dataclass
class NavEntry:
    title: str
    href: str  # archive path with fragment; "" for a heading-only entry
    level: int = 1
    children: list[NavEntry] = field(default_factory=list)


@dataclass
class Landmark:
    type: str  # epub:type, e.g. "bodymatter", "toc", "cover"
    title: str
    href: str


class Book:
    def __init__(
        self,
        *,
        opf_href: str,
        nav_href: str,
        ncx_href: str,
        docs: list[ContentDoc],
        assets: dict[str, Asset],
        metadata: BookMetadata,
        toc: list[NavEntry],
        page_list: list[tuple[str, str]],
        landmarks: list[Landmark],
    ):
        self.opf_href = opf_href
        self.nav_href = nav_href
        self.ncx_href = ncx_href
        self.docs = docs
        self.assets = assets
        self.metadata = metadata
        self.toc = toc
        self.page_list = page_list  # (printed label, archive href#id)
        self.landmarks = landmarks
        self.stripped: list[str] = []  # active content removed by save(), per package.sanitize
        self._ids: dict[str, dict[str, etree._Element]] = {}

    # ----------------------------------------------------------------------- loading

    @classmethod
    def load(cls, path: Path) -> Book:
        try:
            with zipfile.ZipFile(path) as zf:
                return _Loader(zf).book()
        except (KeyError, etree.XMLSyntaxError, zipfile.BadZipFile) as exc:
            raise StageError("epub", f"cannot read EPUB: {exc}", retryable=False) from exc

    # ----------------------------------------------------------------------- lookup

    def doc(self, href: str) -> ContentDoc:
        for doc in self.docs:
            if doc.href == href:
                return doc
        raise KeyError(href)

    def element(self, anchor: str) -> tuple[ContentDoc, etree._Element]:
        """The content document and element for ``"href#id"`` (the body for a bare href)."""
        href, _, frag = anchor.partition("#")
        doc = self.doc(href)
        if not frag:
            return doc, doc.body
        el = self._ids.get(href, {}).get(frag)
        if el is None or el.get("id") != frag or el.getroottree().getroot() is not doc.tree:
            index: dict[str, etree._Element] = {}
            for node in doc.tree.iter(etree.Element):
                if (ident := node.get("id")) is not None:
                    index.setdefault(ident, node)
            self._ids[href] = index
            el = index.get(frag)
        if el is None:
            raise KeyError(anchor)
        return doc, el

    def iter_links(self) -> Iterator[tuple[ContentDoc, etree._Element, str, str]]:
        """Yield (doc, element, attribute, target) for every reference in the content.

        ``target`` is the resolved archive path with fragment for local references and
        the raw attribute value for anything with a URL scheme.
        """
        for doc in self.docs:
            for el in doc.tree.iter(etree.Element):
                for attr in LINK_ATTRS:
                    value = el.get(attr)
                    if value is None:
                        continue
                    local = scheme(value) is None
                    yield doc, el, attr, resolve_href(doc.href, value) if local else value

    def rewrite_links(self, mapping: dict[str, str]) -> int:
        """Point every reference to an old target in ``mapping`` at its new target.

        Covers content links, the toc, the page list and landmarks. Returns the count.
        """
        count = 0
        for doc, el, attr, target in list(self.iter_links()):
            if (new := mapping.get(target)) is not None:
                el.set(attr, relative_href(doc.href, new))
                count += 1
        return count + self._rewrite_nav(mapping)

    def _rewrite_nav(self, mapping: dict[str, str]) -> int:
        count = 0
        for entry in _walk(self.toc):
            if entry.href in mapping:
                entry.href = mapping[entry.href]
                count += 1
        for i, (label, href) in enumerate(self.page_list):
            if href in mapping:
                self.page_list[i] = (label, mapping[href])
                count += 1
        for mark in self.landmarks:
            if mark.href in mapping:
                mark.href = mapping[mark.href]
                count += 1
        return count

    # ----------------------------------------------------------------------- blocks

    def ensure_block_ids(self) -> list[OutputBlock]:
        """Give every block element an id (keeping existing ones) and list them in order.

        New ids are ``qc-b{n:05d}``, numbered in spine order and skipping any id already
        used in the book, so calling this again changes nothing. A block's text is its
        normalized own text: text inside nested blocks belongs to those blocks only
        (a figure's caption text is reported on its figcaption).
        """
        taken = {
            ident
            for doc in self.docs
            for node in doc.tree.iter(etree.Element)
            if (ident := node.get("id")) is not None
        }
        counter = 0
        blocks = []
        for doc in self.docs:
            for el in doc.body.iter(etree.Element):
                if not is_block(el):
                    continue
                if el.get("id") is None:
                    while (ident := f"{BLOCK_ID_PREFIX}b{counter:05d}") in taken:
                        counter += 1
                    el.set("id", ident)
                    taken.add(ident)
                    counter += 1
                blocks.append(
                    OutputBlock(
                        id=el.get("id"), href=doc.href, tag=local_name(el), text=_own_text(el)
                    )
                )
        return blocks

    # ----------------------------------------------------------------------- assets

    def add_asset(self, href: str, data: bytes, media_type: str, properties=()) -> Asset:
        if any(doc.href == href for doc in self.docs) or href in (
            self.opf_href,
            self.nav_href,
            self.ncx_href,
        ):
            raise ValueError(f"{href} is already a package or content document")
        asset = Asset(href=href, data=data, media_type=media_type, properties=set(properties))
        self.assets[href] = asset
        return asset

    def remove_asset(self, href: str) -> None:
        del self.assets[href]
        if self.metadata.cover_href == href:
            self.metadata.cover_href = None

    def set_cover(self, href: str) -> None:
        """Mark an existing image asset as the cover (manifest cover-image + EPUB 2 meta)."""
        if href not in self.assets:
            raise KeyError(href)
        for asset in self.assets.values():
            asset.properties.discard("cover-image")
        self.assets[href].properties.add("cover-image")
        self.metadata.cover_href = href

    # ----------------------------------------------------------------------- splitting

    def split_doc(self, href: str, element_id: str) -> str:
        """Split ``href`` before ``element_id`` into a new spine document; return its href.

        The element and everything after it move to the new document. When the element
        sits inside wrapper elements, the wrapper chain is split too: shallow copies of
        the wrappers (without their ids) hold the moved part. Every reference in the
        book to an id that moved is rewritten, as are relative links inside the moved
        content. When nothing precedes the element the document is left alone and
        ``href`` is returned.
        """
        doc, el = self.element(f"{href}#{element_id}")
        body = doc.body
        chain = [el]
        while chain[-1].getparent() is not body:
            parent = chain[-1].getparent()
            if parent is None:
                raise ValueError(f"{href}#{element_id} is not inside the body")
            chain.append(parent)
        if not _has_content_before(body, el):
            return href
        links = [link for link in self.iter_links() if scheme(link[3]) is None]
        new_doc = self._new_doc_after(doc)
        _move_tail(chain, body, new_doc.body)
        for wrapper in chain[1:]:
            if len(wrapper) == 0 and not (wrapper.text or "").strip() and wrapper.get("id") is None:
                remove_keep_tail(wrapper)
        moved = {
            f"{href}#{ident}": f"{new_doc.href}#{ident}"
            for node in new_doc.body.iter(etree.Element)
            if (ident := node.get("id")) is not None
        }
        for owner, node, attr, target in links:
            if node.getroottree().getroot() is new_doc.tree:
                owner = new_doc
            new_target = moved.get(target, target)
            if owner is not doc or new_target != target:
                node.set(attr, relative_href(owner.href, new_target))
        self._rewrite_nav(moved)
        self._ids.pop(href, None)
        return new_doc.href

    def _new_doc_after(self, doc: ContentDoc) -> ContentDoc:
        stem, ext = posixpath.splitext(doc.href)
        used = {d.href for d in self.docs} | set(self.assets)
        used |= {self.opf_href, self.nav_href, self.ncx_href}
        ids = {d.id for d in self.docs}
        n = 1
        while f"{stem}-qc{n}{ext}" in used or _doc_id(f"{stem}-qc{n}{ext}") in ids:
            n += 1
        new_href = f"{stem}-qc{n}{ext}"
        root = etree.Element(doc.tree.tag, dict(doc.tree.attrib), nsmap=doc.tree.nsmap)
        head = doc.tree.find(f"{{{XHTML_NS}}}head")
        if head is not None:
            root.append(copy.deepcopy(head))
            root[-1].tail = None
        body_attrs = {k: v for k, v in doc.body.attrib.items() if k != "id"}
        etree.SubElement(root, f"{{{XHTML_NS}}}body", body_attrs)
        new_doc = ContentDoc(
            href=new_href,
            id=_doc_id(new_href),
            tree=root,
            media_type=doc.media_type,
            linear=doc.linear,
        )
        self.docs.insert(self.docs.index(doc) + 1, new_doc)
        return new_doc

    # ----------------------------------------------------------------------- saving

    def save(self, path: Path, *, modified: str) -> None:
        """Serialize deterministically (see package.write_epub); records stripped content."""
        from .package import write_epub

        self.stripped.extend(write_epub(self, path, modified=modified))


# --------------------------------------------------------------------------- helpers


def _walk(entries: list[NavEntry]) -> Iterator[NavEntry]:
    for entry in entries:
        yield entry
        yield from _walk(entry.children)


def _doc_id(href: str) -> str:
    return "qc-" + re.sub(r"[^A-Za-z0-9_-]", "_", href)


def _norm(text: str) -> str:
    return " ".join(text.split())


def _is_xhtml(el: etree._Element) -> bool:
    return isinstance(el.tag, str) and el.tag.startswith(f"{{{XHTML_NS}}}")


def is_block(el: etree._Element) -> bool:
    if not _is_xhtml(el):
        return False
    name = local_name(el)
    if name in BLOCK_TAGS:
        return True
    if name == "img":
        return not any(_is_xhtml(a) and local_name(a) in BLOCK_TAGS for a in el.iterancestors())
    if name == "div":
        has_blocks = any(
            _is_xhtml(d) and local_name(d) in _STRUCTURAL for d in el.iterdescendants()
        )
        return not has_blocks and bool(_norm("".join(el.itertext())))
    return False


def _own_text(el: etree._Element) -> str:
    if local_name(el) == "img":
        return _norm(el.get("alt", ""))
    parts: list[str] = [el.text or ""]

    def collect(node: etree._Element) -> None:
        for child in node:
            if isinstance(child.tag, str) and not is_block(child):
                if local_name(child) not in ("script", "style"):
                    parts.append(child.text or "")
                    collect(child)
            parts.append(child.tail or "")

    collect(el)
    return _norm("".join(parts))


def _has_content_before(body: etree._Element, target: etree._Element) -> bool:
    ancestors = set(target.iterancestors())
    for node in body.iter():
        if node is target:
            return False
        if not isinstance(node.tag, str):
            if (node.tail or "").strip():
                return True
            continue
        if (node.text or "").strip() or local_name(node) in _MEDIA:
            return True
        if node not in ancestors and node is not body and (node.tail or "").strip():
            return True
    return False


def _move_tail(chain: list[etree._Element], body: etree._Element, new_body: etree._Element):
    # chain = [element, innermost wrapper, ..., wrapper that is a child of body]
    clones = {body: new_body}
    parent_clone = new_body
    for wrapper in reversed(chain[1:]):
        attrs = {k: v for k, v in wrapper.attrib.items() if k != "id"}
        parent_clone = etree.SubElement(parent_clone, wrapper.tag, attrs)
        clones[wrapper] = parent_clone
    for i, node in enumerate(chain):
        dest = clones[node.getparent()]
        following = list(node.itersiblings())
        for sibling in ([node] if i == 0 else []) + following:
            dest.append(sibling)


def remove_keep_tail(el: etree._Element) -> None:
    parent = el.getparent()
    if el.tail:
        prev = el.getprevious()
        if prev is not None:
            prev.tail = (prev.tail or "") + el.tail
        else:
            parent.text = (parent.text or "") + el.tail
    parent.remove(el)


class _Loader:
    """Reads one archive: container.xml -> OPF -> manifest, spine, metadata, navigation."""

    def __init__(self, zf: zipfile.ZipFile):
        self.zf = zf
        container = self.parse("META-INF/container.xml")
        rootfile = container.find(f".//{{{CONTAINER_NS}}}rootfile")
        if rootfile is None or not rootfile.get("full-path"):
            raise KeyError("container.xml names no rootfile")
        self.opf_href = rootfile.get("full-path")
        self.opf = self.parse(self.opf_href)
        self.items: dict[str, tuple[str, str, set[str]]] = {}
        for item in self.opf.iterfind(f"{{{OPF_NS}}}manifest/{{{OPF_NS}}}item"):
            href = resolve_href(self.opf_href, item.get("href", "")).partition("#")[0]
            props = set((item.get("properties") or "").split())
            self.items[item.get("id")] = (href, item.get("media-type", ""), props)

    def parse(self, name: str) -> etree._Element:
        return etree.fromstring(self.zf.read(name), _PARSER)

    def book(self) -> Book:
        spine = self.opf.find(f"{{{OPF_NS}}}spine")
        if spine is None:
            raise KeyError("OPF has no spine")
        nav_id = next((i for i, (_, _, p) in self.items.items() if "nav" in p), None)
        ncx_id = spine.get("toc") or next(
            (i for i, (_, mt, _) in self.items.items() if mt == NCX_TYPE), None
        )
        special = {i for i in (nav_id, ncx_id) if i}
        docs = []
        for ref in spine.iterfind(f"{{{OPF_NS}}}itemref"):
            idref = ref.get("idref")
            if idref in special:
                continue
            href, media_type, _ = self.items[idref]
            linear = ref.get("linear", "yes") != "no"
            docs.append(ContentDoc(href, idref, self.parse(href), media_type, linear))
        in_spine = {d.id for d in docs} | special
        assets = {
            href: Asset(href, self.zf.read(href), media_type, props)
            for ident, (href, media_type, props) in self.items.items()
            if ident not in in_spine
        }
        opf_dir = posixpath.dirname(self.opf_href)
        nav_href = self.items[nav_id][0] if nav_id else posixpath.join(opf_dir, "nav.xhtml")
        ncx_href = self.items[ncx_id][0] if ncx_id else posixpath.join(opf_dir, "toc.ncx")
        toc, page_list, landmarks = [], [], []
        if nav_id:
            toc, page_list, landmarks = _read_nav(self.parse(nav_href), nav_href)
        if not toc and ncx_id:
            toc, ncx_pages = _read_ncx(self.parse(ncx_href), ncx_href)
            page_list = page_list or ncx_pages
        return Book(
            opf_href=self.opf_href,
            nav_href=nav_href,
            ncx_href=ncx_href,
            docs=docs,
            assets=assets,
            metadata=self.metadata(),
            toc=toc,
            page_list=page_list,
            landmarks=landmarks,
        )

    def metadata(self) -> BookMetadata:
        md = self.opf.find(f"{{{OPF_NS}}}metadata")
        meta = BookMetadata()
        if md is None:
            return meta
        dc = f"{{{DC_NS}}}"
        opf_attr = f"{{{OPF_NS}}}"
        refines: dict[tuple[str, str], str] = {}
        legacy: dict[str, str] = {}
        props: dict[str, list[etree._Element]] = {}
        for el in md.iterfind(f"{{{OPF_NS}}}meta"):
            if el.get("refines") and el.get("property"):
                refines[(el.get("refines").lstrip("#"), el.get("property"))] = _norm(el.text or "")
            elif el.get("property"):
                props.setdefault(el.get("property"), []).append(el)
            elif el.get("name"):
                legacy[el.get("name")] = el.get("content", "")

        def texts(tag: str) -> list[tuple[etree._Element, str]]:
            return [(e, t) for e in md.iterfind(dc + tag) if (t := _norm(e.text or ""))]

        titles = texts("title")
        kinds = [refines.get((e.get("id", ""), "title-type")) for e, _ in titles]
        main = next((t for (_, t), k in zip(titles, kinds, strict=True) if k == "main"), None)
        meta.title = main or (titles[0][1] if titles else None)
        meta.subtitle = next(
            (t for (_, t), k in zip(titles, kinds, strict=True) if k == "subtitle"), None
        )
        for el, name in texts("creator"):
            role = refines.get((el.get("id", ""), "role")) or el.get(opf_attr + "role")
            if role in (None, "aut"):
                meta.authors.append(name)
                if meta.author_sort is None:
                    meta.author_sort = refines.get((el.get("id", ""), "file-as")) or el.get(
                        opf_attr + "file-as"
                    )
        unique = self.opf.get("unique-identifier")
        idents = texts("identifier")
        meta.identifiers = [t for e, t in idents if e.get("id") == unique]
        meta.identifiers += [t for e, t in idents if e.get("id") != unique]
        for name in ("language", "publisher", "date", "description", "rights"):
            found = texts(name)
            setattr(meta, name, found[0][1] if found else None)
        meta.subjects = [t for _, t in texts("subject")]
        series = props.get("belongs-to-collection", [])
        if series:
            meta.series = _norm(series[0].text or "") or None
            meta.series_index = refines.get((series[0].get("id", ""), "group-position"))
        meta.series = meta.series or legacy.get("calibre:series") or None
        meta.series_index = meta.series_index or legacy.get("calibre:series_index") or None
        cover = next((h for h, _, p in self.items.values() if "cover-image" in p), None)
        if cover is None and legacy.get("cover") in self.items:
            cover = self.items[legacy["cover"]][0]
        meta.cover_href = cover
        return meta


def _read_nav(tree: etree._Element, base: str):
    h = f"{{{XHTML_NS}}}"
    toc: list[NavEntry] = []
    page_list: list[tuple[str, str]] = []
    landmarks: list[Landmark] = []
    for nav in tree.iter(h + "nav"):
        kinds = (nav.get(f"{{{EPUB_NS}}}type") or "").split()
        ol = nav.find(h + "ol")
        if ol is None:
            continue
        if "toc" in kinds and not toc:
            toc = _nav_entries(ol, base, 1)
        elif "page-list" in kinds:
            page_list = [(e.title, e.href) for e in _nav_entries(ol, base, 1)]
        elif "landmarks" in kinds:
            for li in ol.iterfind(h + "li"):
                a = li.find(h + "a")
                if a is not None and a.get("href"):
                    landmarks.append(
                        Landmark(
                            type=a.get(f"{{{EPUB_NS}}}type", ""),
                            title=_norm("".join(a.itertext())),
                            href=resolve_href(base, a.get("href")),
                        )
                    )
    return toc, page_list, landmarks


def _nav_entries(ol: etree._Element, base: str, level: int) -> list[NavEntry]:
    h = f"{{{XHTML_NS}}}"
    entries = []
    for li in ol.iterfind(h + "li"):
        label = li.find(h + "a")
        if label is None:
            label = li.find(h + "span")
        href = label.get("href") if label is not None else None
        child = li.find(h + "ol")
        entries.append(
            NavEntry(
                title=_norm("".join(label.itertext())) if label is not None else "",
                href=resolve_href(base, href) if href else "",
                level=level,
                children=_nav_entries(child, base, level + 1) if child is not None else [],
            )
        )
    return entries


def _read_ncx(tree: etree._Element, base: str):
    n = f"{{{NCX_NS}}}"

    def label(el: etree._Element) -> str:
        return _norm(el.findtext(f"{n}navLabel/{n}text") or "")

    def src(el: etree._Element) -> str:
        content = el.find(n + "content")
        return resolve_href(base, content.get("src")) if content is not None else ""

    def points(parent: etree._Element, level: int) -> list[NavEntry]:
        return [
            NavEntry(label(p), src(p), level, points(p, level + 1))
            for p in parent.iterfind(n + "navPoint")
        ]

    nav_map = tree.find(n + "navMap")
    toc = points(nav_map, 1) if nav_map is not None else []
    pages = [(label(p), src(p)) for p in tree.iterfind(f"{n}pageList/{n}pageTarget")]
    return toc, pages
