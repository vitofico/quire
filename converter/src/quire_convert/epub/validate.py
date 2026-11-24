"""Independent checks on the final serialized archive: EPUBCheck plus a semantic pass.

The semantic pass reads the ZIP itself and never goes through Book, so a bug in the
model or the packager cannot hide from it.
"""

from __future__ import annotations

import io
import json
import posixpath
import re
import subprocess
import zipfile
from dataclasses import dataclass, field
from pathlib import Path
from urllib.parse import unquote

from lxml import etree
from PIL import Image

from ..models import Issue, Severity, StageError
from ..tools import Toolchain

XHTML = "http://www.w3.org/1999/xhtml"
OPS = "http://www.idpf.org/2007/ops"
OPF = "http://www.idpf.org/2007/opf"
NCX = "http://www.daisy.org/z3986/2005/ncx/"
CONTAINER = "urn:oasis:names:tc:opendocument:xmlns:container"
XLINK_HREF = "{http://www.w3.org/1999/xlink}href"
_REF_ATTRS = ("href", "src", XLINK_HREF, "poster", "data")
_ANCHORS = {"a", "area"}
_SCHEME = re.compile(r"^([a-zA-Z][a-zA-Z0-9+.-]*):")
_NOTE_TYPES = {"footnote", "endnote", "note", "rearnote"}
_NOTE_ROLES = {"doc-footnote", "doc-endnote"}
# An image is "blank" when fewer than this share of its pixels differ visibly from the
# most common value: a white page with a speck of dust, a lost vector drawing.
_INK_SHARE = 0.0005
_INK_DELTA = 24
_PARSER = etree.XMLParser(resolve_entities=False, no_network=True, huge_tree=True)


@dataclass
class EpubcheckResult:
    ok: bool
    fatal: list[dict] = field(default_factory=list)
    errors: list[dict] = field(default_factory=list)
    warnings: list[dict] = field(default_factory=list)
    version: str = ""


def run_epubcheck(epub: Path, toolchain: Toolchain, *, timeout: float = 300) -> EpubcheckResult:
    """Run the pinned EPUBCheck jar with JSON output on stdout.

    Messages keep EPUBCheck's own fields: ID, severity, message and locations (each
    reduced to path, line, column).
    """
    toolchain.require("java", "epubcheck_jar")
    argv = [str(toolchain.java), "-jar", str(toolchain.epubcheck_jar), str(epub), "--json", "-"]
    try:
        proc = subprocess.run(argv, capture_output=True, timeout=timeout, stdin=subprocess.DEVNULL)
    except subprocess.TimeoutExpired:
        raise StageError("epubcheck", f"timed out after {timeout:.0f}s", retryable=True) from None
    try:
        report = json.loads(proc.stdout)
    except json.JSONDecodeError:
        raise StageError(
            "epubcheck", f"no JSON report (exit {proc.returncode})", retryable=False
        ) from None
    by_severity: dict[str, list[dict]] = {"FATAL": [], "ERROR": [], "WARNING": []}
    for message in report.get("messages", []):
        bucket = by_severity.get(message.get("severity", ""))
        if bucket is not None:
            locations = [
                {k: loc.get(k) for k in ("path", "line", "column")}
                for loc in message.get("locations") or []
            ]
            bucket.append(
                {
                    "ID": message.get("ID"),
                    "severity": message.get("severity"),
                    "message": message.get("message"),
                    "locations": locations,
                }
            )
    return EpubcheckResult(
        ok=not by_severity["FATAL"] and not by_severity["ERROR"],
        fatal=by_severity["FATAL"],
        errors=by_severity["ERROR"],
        warnings=by_severity["WARNING"],
        version=report.get("checker", {}).get("checkerVersion", ""),
    )


# --------------------------------------------------------------------------- semantic check


def semantic_check(epub: Path) -> list[Issue]:
    """Check what EPUBCheck does not: targets land, notes return, images show something."""
    issues: list[Issue] = []
    with zipfile.ZipFile(epub) as zf:
        _Checker(zf, issues).run()
    return issues


def _issue(issues: list[Issue], code: str, severity: Severity, message: str, **kw) -> None:
    issues.append(Issue(code=code, severity=severity, message=message, **kw))


def _local(el: etree._Element) -> str:
    return etree.QName(el).localname


def _resolve(base: str, ref: str) -> tuple[str, str]:
    path, _, frag = ref.partition("#")
    target = posixpath.normpath(posixpath.join(posixpath.dirname(base), unquote(path)))
    return (target if path else base), unquote(frag)


def _scheme(ref: str) -> str | None:
    if ref.startswith("//"):
        return "//"
    match = _SCHEME.match(ref.strip())
    return match.group(1).lower() if match else None


class _Checker:
    def __init__(self, zf: zipfile.ZipFile, issues: list[Issue]):
        self.zf = zf
        self.issues = issues
        self.names = set(zf.namelist())
        self.trees: dict[str, etree._Element | None] = {}
        self.ids: dict[str, set[str]] = {}

    def add(self, code: str, severity: Severity, message: str, **kw) -> None:
        _issue(self.issues, code, severity, message, **kw)

    def tree(self, name: str) -> etree._Element | None:
        """Parsed member, or None when missing or malformed (reported by the caller)."""
        if name not in self.trees:
            try:
                self.trees[name] = etree.fromstring(self.zf.read(name), _PARSER)
            except (KeyError, etree.XMLSyntaxError):
                self.trees[name] = None
        return self.trees[name]

    def has_id(self, name: str, ident: str) -> bool:
        if name not in self.ids:
            tree = self.tree(name)
            self.ids[name] = {v for v in tree.xpath("//@id")} if tree is not None else set()
        return ident in self.ids[name]

    def run(self) -> None:
        if not self.mimetype_ok():
            return
        container = self.tree("META-INF/container.xml")
        rootfile = container.find(f".//{{{CONTAINER}}}rootfile") if container is not None else None
        opf_name = rootfile.get("full-path") if rootfile is not None else None
        opf = self.tree(opf_name) if opf_name else None
        if opf is None:
            self.add("epub.no_package", Severity.CRITICAL, "package document missing or malformed")
            return
        manifest = {}
        for item in opf.iterfind(f"{{{OPF}}}manifest/{{{OPF}}}item"):
            href, _ = _resolve(opf_name, item.get("href", ""))
            manifest[item.get("id")] = (
                href,
                item.get("media-type", ""),
                item.get("properties", ""),
            )
        spine = [
            manifest[ref.get("idref")][0]
            for ref in opf.iterfind(f"{{{OPF}}}spine/{{{OPF}}}itemref")
            if ref.get("idref") in manifest
        ]
        for href, media_type, _ in manifest.values():
            if href not in self.names:
                self.add(
                    "asset.missing",
                    Severity.CRITICAL,
                    "manifest item missing from the archive",
                    evidence=href,
                )
            elif media_type.startswith("image/") and media_type != "image/svg+xml":
                self.check_image(href)
        for href in spine:
            self.check_document(href)
        for href, _, props in manifest.values():
            if "nav" in props.split():
                self.check_nav(href)
            elif href.endswith(".ncx"):
                self.check_ncx(href)

    def mimetype_ok(self) -> bool:
        infos = self.zf.infolist()
        first = infos[0] if infos else None
        if (
            first is None
            or first.filename != "mimetype"
            or first.compress_type != zipfile.ZIP_STORED
            or self.zf.read(first) != b"application/epub+zip"
        ):
            self.add(
                "epub.mimetype",
                Severity.CRITICAL,
                "mimetype must be the first, stored member",
            )
            return False
        return True

    def check_image(self, href: str) -> None:
        try:
            with Image.open(io.BytesIO(self.zf.read(href))) as img:
                img.load()
                gray = _flatten(img)
        except Exception:  # noqa: BLE001 - any decoder failure means the asset is unusable
            self.add("asset.undecodable", Severity.CRITICAL, "image does not decode", evidence=href)
            return
        hist = gray.histogram()
        total = sum(hist)
        mode = max(range(256), key=hist.__getitem__)
        near = sum(hist[max(0, mode - _INK_DELTA) : mode + _INK_DELTA + 1])
        if total and (total - near) / total < _INK_SHARE:
            self.add(
                "asset.blank",
                Severity.MAJOR,
                "image is visually blank",
                evidence=f"{href} ink share {(total - near) / total:.5f}",
            )

    def check_document(self, href: str) -> None:
        tree = self.tree(href)
        if tree is None:
            self.add(
                "epub.unparseable",
                Severity.CRITICAL,
                "spine document missing or not well-formed XML",
                evidence=href,
            )
            return
        for el in tree.iter(etree.Element):
            name = _local(el)
            anchor = f"{href}#{el.get('id')}" if el.get("id") else href
            if name == "script" or any(
                etree.QName(a).localname.lower().startswith("on") for a in el.attrib
            ):
                self.add(
                    "epub.active_content",
                    Severity.MAJOR,
                    f"script content on <{name}>",
                    output_anchor=anchor,
                )
            if _is_noteref(el) and el.get("href") and _scheme(el.get("href")) is None:
                self.check_note(href, el, anchor)  # reports its own broken target
                continue
            for attr in _REF_ATTRS:
                if (value := el.get(attr)) is not None:
                    self.check_ref(href, name, attr, value, anchor)

    def check_ref(self, href: str, name: str, attr: str, value: str, anchor: str) -> None:
        kind = _scheme(value)
        if kind is not None:
            if kind in ("javascript", "vbscript"):
                self.add("epub.active_content", Severity.MAJOR, "script URL", output_anchor=anchor)
            elif name not in _ANCHORS and kind != "data":
                self.add(
                    "epub.remote_resource",
                    Severity.MAJOR,
                    f"<{name}> loads a remote resource",
                    output_anchor=anchor,
                )
            return
        target, frag = _resolve(href, value)
        if target not in self.names:
            hyperlink = name in _ANCHORS
            self.add(
                "epub.broken_link" if hyperlink else "asset.missing",
                Severity.MAJOR if hyperlink else Severity.CRITICAL,
                f"<{name} {etree.QName(attr).localname}> points to a missing member",
                output_anchor=anchor,
                evidence=f"{href} -> {target}",
            )
        elif frag and not self.has_id(target, frag):
            self.add(
                "epub.missing_target",
                Severity.MAJOR,
                "link fragment has no element with that id in the target document",
                output_anchor=anchor,
                evidence=f"{href} -> {target}#{frag}",
            )

    def check_note(self, href: str, ref: etree._Element, anchor: str) -> None:
        target, frag = _resolve(href, ref.get("href"))
        note = self.find(target, frag)
        if note is None:
            self.add(
                "note.broken_ref",
                Severity.CRITICAL,
                "note reference does not reach a note",
                output_anchor=anchor,
                evidence=f"{href} -> {target}#{frag}",
            )
            return
        note = next((n for n in [note, *note.iterancestors()] if _is_note(n)), note)
        for link in note.iter(f"{{{XHTML}}}a"):
            back = link.get("href")
            if not back or _scheme(back) is not None:
                continue
            back_doc, back_frag = _resolve(target, back)
            if link.get("role") != "doc-backlink" and back_doc != href:
                continue  # a cross-reference inside the note, not a way back
            if back_doc not in self.names or (back_frag and not self.has_id(back_doc, back_frag)):
                self.add(
                    "note.no_return",
                    Severity.CRITICAL,
                    "note backlink does not resolve",
                    output_anchor=anchor,
                    evidence=f"{target}#{frag} -> {back_doc}#{back_frag}",
                )

    def find(self, name: str, ident: str) -> etree._Element | None:
        tree = self.tree(name)
        if tree is None:
            return None
        if not ident:
            return tree
        found = tree.xpath("//*[@id=$i]", i=ident)
        return found[0] if found else None

    def check_nav(self, href: str) -> None:
        tree = self.tree(href)
        if tree is None:
            self.add("nav.unparseable", Severity.CRITICAL, "navigation document is not XML")
            return
        for nav in tree.iter(f"{{{XHTML}}}nav"):
            kind = nav.get(f"{{{OPS}}}type", "")
            for a in nav.iter(f"{{{XHTML}}}a"):
                self.check_nav_target(href, a.get("href", ""), kind)

    def check_ncx(self, href: str) -> None:
        tree = self.tree(href)
        if tree is None:
            self.add("nav.unparseable", Severity.CRITICAL, "NCX is not XML")
            return
        for content in tree.iter(f"{{{NCX}}}content"):
            self.check_nav_target(href, content.get("src", ""), "ncx")

    def check_nav_target(self, base: str, ref: str, kind: str) -> None:
        if not ref or _scheme(ref) is not None:
            return
        target, frag = _resolve(base, ref)
        if target not in self.names or (frag and not self.has_id(target, frag)):
            self.add(
                "nav.broken_target",
                Severity.CRITICAL,
                f"{kind or 'nav'} entry points nowhere",
                evidence=f"{base} -> {target}#{frag}" if frag else f"{base} -> {target}",
            )


def _is_noteref(el: etree._Element) -> bool:
    types = (el.get(f"{{{OPS}}}type") or "").split()
    return "noteref" in types or el.get("role") == "doc-noteref"


def _is_note(el: etree._Element) -> bool:
    types = set((el.get(f"{{{OPS}}}type") or "").split())
    return bool(types & _NOTE_TYPES) or el.get("role") in _NOTE_ROLES


def _flatten(img: Image.Image) -> Image.Image:
    """Grayscale view with transparency composited on white, as a reader would show it."""
    if img.mode in ("RGBA", "LA", "PA") or "transparency" in img.info:
        rgba = img.convert("RGBA")
        white = Image.new("RGBA", rgba.size, (255, 255, 255, 255))
        return Image.alpha_composite(white, rgba).convert("L")
    return img.convert("L")
