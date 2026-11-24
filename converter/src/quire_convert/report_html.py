"""Static HTML review report: source crops beside the matching EPUB excerpts.

A local diagnostics artifact for a reviewer, so it may show book text and images. It is
self-contained (inline CSS, no scripts, no remote resources): ``index.html`` plus rendered
PNGs under ``crops/`` and copied EPUB images under ``assets/``. EPUB excerpts are rebuilt
from an allow-list of tags with every text node escaped, so book markup cannot run.
"""

from __future__ import annotations

import hashlib
import logging
import os
import posixpath
from html import escape
from pathlib import Path

from lxml import etree

from .context import ConvertContext
from .epub.package import resolve_href, scheme
from .models import Box, Issue, OutputBlock, Severity, StageError
from .report import count_issues
from .source.render import render_region

log = logging.getLogger(__name__)

CROP_DPI = 100
PAGE_DPI = 72
MAX_RENDERS = 200  # issue crops first, then page images
MAX_PAGE_EXCERPT_BLOCKS = 6  # blocks shown on an issue card without an output anchor
CROP_MARGIN = 0.01

_SEVERITY_ORDER = {s: n for n, s in enumerate(Severity)}
_KEEP = frozenset(
    {"p", "h1", "h2", "h3", "h4", "h5", "h6", "b", "strong", "i", "em", "u", "s", "small"}
    | {"sup", "sub", "span", "br", "blockquote", "pre", "code", "ul", "ol", "li", "dl", "dt"}
    | {"dd", "table", "thead", "tbody", "tfoot", "tr", "td", "th", "caption", "figure"}
    | {"figcaption", "div", "section", "article", "aside", "hr", "q", "cite", "abbr", "a"}
)
_VOID = frozenset({"br", "hr", "img"})
_OMIT = frozenset({"svg", "math", "video", "audio", "object", "embed", "iframe", "canvas"})
_IMAGE_TYPES = {"image/png": ".png", "image/jpeg": ".jpg", "image/gif": ".gif"}
_IMAGE_TYPES |= {"image/webp": ".webp"}


class _Renderer:
    """Renders crops under one budget; a failed or capped render yields None."""

    def __init__(self, ctx: ConvertContext, out_dir: Path):
        self.ctx = ctx
        self.dir = out_dir / "crops"
        self.done: dict[tuple, str | None] = {}
        self.wanted = 0
        self.rendered = 0
        self.failed = 0

    def render(self, page: int, box: Box | None, dpi: int, name: str) -> str | None:
        key = (page, box, dpi)
        if key in self.done:
            return self.done[key]
        self.wanted += 1
        rel = None
        if self.rendered < MAX_RENDERS:
            try:
                render_region(
                    self.ctx.pdf_path, page, box, dpi, self.dir / name, self.ctx.toolchain
                )
                self.rendered += 1
                rel = f"crops/{name}"
            except (StageError, OSError) as exc:
                self.failed += 1
                log.warning("report crop of page %d failed: %s", page, type(exc).__name__)
        self.done[key] = rel
        return rel

    @property
    def capped(self) -> bool:
        return self.wanted - self.failed > self.rendered


class _Excerpts:
    """Serializes EPUB elements as sanitized HTML, copying their images into assets/."""

    def __init__(self, ctx: ConvertContext, out_dir: Path):
        self.book = ctx.book
        self.dir = out_dir / "assets"
        self.copied: dict[str, str] = {}

    def element(self, anchor: str):
        try:
            doc, el = self.book.element(anchor)
        except KeyError:
            return None
        return None if el is doc.body else (doc.href, el)

    def html(self, href: str, el: etree._Element) -> str:
        parts: list[str] = []
        self._node(href, el, parts)
        return "".join(parts)

    def _node(self, href: str, el: etree._Element, out: list[str]) -> None:
        if isinstance(el.tag, str):
            name = etree.QName(el).localname
            if name == "img":
                out.append(self._img(href, el))
            elif name in _OMIT:
                out.append(f'<span class="omitted">[{name}]</span>')
            elif name in _KEEP:
                out.append(f"<{name}>")
                self._children(href, el, out)
                if name not in _VOID:
                    out.append(f"</{name}>")
            elif name not in ("script", "style", "noscript", "template", "head"):
                self._children(href, el, out)  # unknown wrapper: keep its content only
        out.append(escape(el.tail or ""))

    def _children(self, href: str, el: etree._Element, out: list[str]) -> None:
        out.append(escape(el.text or ""))
        for child in el:
            self._node(href, child, out)

    def _img(self, href: str, el: etree._Element) -> str:
        alt = escape(el.get("alt") or "", quote=True)
        src = el.get("src") or ""
        rel = None if not src or scheme(src) is not None else self._copy(resolve_href(href, src))
        if rel is None:
            return f'<span class="omitted">[image{": " + alt if alt else ""}]</span>'
        return f'<img src="{rel}" alt="{alt}">'

    def _copy(self, target: str) -> str | None:
        target = target.partition("#")[0]
        if target in self.copied:
            return self.copied[target]
        asset = self.book.assets.get(target)
        ext = _IMAGE_TYPES.get(asset.media_type) if asset else None
        if ext is None:
            return None
        name = hashlib.sha256(target.encode()).hexdigest()[:16] + ext
        self.dir.mkdir(parents=True, exist_ok=True)
        (self.dir / name).write_bytes(asset.data)
        self.copied[target] = f"assets/{name}"
        return self.copied[target]


def write_html_report(ctx: ConvertContext, out_dir: Path) -> Path:
    """Write ``out_dir/index.html`` (plus crops/ and assets/) and return its path."""
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    renderer = _Renderer(ctx, out_dir)
    excerpts = _Excerpts(ctx, out_dir)
    page_blocks = _page_blocks(ctx.blocks)
    issues = sorted(
        enumerate(ctx.issues),
        key=lambda item: (_SEVERITY_ORDER[item[1].severity], _page_key(item[1].page), item[0]),
    )
    issue_cards = [
        _issue_card(n, issue, ctx, renderer, excerpts, page_blocks) for n, issue in issues
    ]
    page_images = {
        page.index: renderer.render(page.index, None, PAGE_DPI, f"page-{page.index:04d}.png")
        for page in ctx.source.pages
    }
    body = [
        _header(ctx, renderer),
        _section("Issues", issue_cards, "No issues were raised."),
        _section(
            "Repairs",
            [_repair_card(n, r) for n, r in enumerate(ctx.repairs)],
            "No repairs were applied.",
        ),
        _page_strip(ctx, page_images),
        _section(
            "Pages",
            [
                _page_section(ctx, p.index, page_images, excerpts, page_blocks)
                for p in ctx.source.pages
            ],
            "The source has no pages.",
        ),
    ]
    title = escape(ctx.book.metadata.title or ctx.pdf_path.name)
    html = (
        '<!doctype html>\n<html lang="en"><head><meta charset="utf-8">'
        '<meta name="viewport" content="width=device-width, initial-scale=1">'
        f"<title>Review: {title}</title><style>{_CSS}</style></head><body>"
        + "\n".join(body)
        + "</body></html>\n"
    )
    path = out_dir / "index.html"
    tmp = out_dir / ".index.html.tmp"
    tmp.write_text(html, encoding="utf-8")
    os.replace(tmp, path)
    log.info("html report: %d issues, %d crops", len(ctx.issues), renderer.rendered)
    return path


# --------------------------------------------------------------------------- sections


def _header(ctx: ConvertContext, renderer: _Renderer) -> str:
    counts = count_issues(ctx.issues)
    blocking = sum(1 for i in ctx.issues if i.blocks_ready)
    sev = "".join(
        f'<li class="sev-{s}"><span>{escape(s)}</span><b>{n}</b></li>' for s, n in counts.items()
    )
    families: dict[str, int] = {}
    for r in ctx.repairs:
        families[r.family] = families.get(r.family, 0) + 1
    fam = ", ".join(f"{escape(f)} {n}" for f, n in sorted(families.items())) or "none"
    cap = ""
    if renderer.capped:
        cap = (
            f'<p class="note">Rendered {renderer.rendered} of {renderer.wanted - renderer.failed}'
            f" images: the report caps renders at {MAX_RENDERS}, so some crops and pages show"
            " no image.</p>"
        )
    if renderer.failed:
        cap += f'<p class="note">{renderer.failed} renders failed.</p>'
    src = ctx.source
    return (
        f"<header><h1>{escape(ctx.book.metadata.title or ctx.pdf_path.name)}</h1>"
        f'<p class="meta">{escape(ctx.pdf_path.name)} · {src.page_count} pages · variant '
        f"{escape(ctx.variant)} · sha256 {escape(src.sha256[:16])}</p>"
        f'<ul class="counts">{sev}</ul>'
        f"<p>Issues blocking Ready: {blocking}. Repairs: {len(ctx.repairs)} ({fam}).</p>"
        f"{cap}{_coverage(ctx)}{_metadata(ctx)}</header>"
    )


def _coverage(ctx: ConvertContext) -> str:
    cov = (ctx.alignment.coverage if ctx.alignment is not None else None) or {}
    if not cov:
        return "<p>No coverage summary (coverage accounting did not run).</p>"
    rows = []
    for key in ("prose", "notes"):
        c = cov.get(key) or {}
        rows.append(
            (key, _pct(c.get("ratio")), f"{c.get('aligned', 0)} of {c.get('tokens', 0)} words")
        )
    ill = cov.get("illustrations") or {}
    rows.append(
        (
            "illustrations",
            _pct(ill.get("ratio")),
            f"{ill.get('accounted', 0)} of {ill.get('regions', 0)} regions",
        )
    )
    ink = cov.get("unexplained_ink") or {}
    rows.append(("unexplained ink", "", f"{ink.get('accounted', 0)} of {ink.get('regions', 0)}"))
    furn = cov.get("furniture") or {}
    rows.append(("page furniture", "", f"{furn.get('kept', 0)} of {furn.get('blocks', 0)} kept"))
    out = cov.get("output") or {}
    rows.append(
        (
            "EPUB words",
            "",
            f"{out.get('tokens', 0)} words, {out.get('unaligned', 0)} unaligned, "
            f"{out.get('duplicated', 0)} duplicated, {out.get('moved', 0)} moved",
        )
    )
    low = [p for p, r in (cov.get("pages") or {}).items() if r is not None and r < 1]
    if low:
        rows.append(("pages below 100%", "", ", ".join(low)))
    body = "".join(
        f"<tr><th>{escape(a)}</th><td>{escape(b)}</td><td>{escape(c)}</td></tr>" for a, b, c in rows
    )
    return f"<h2>Coverage</h2><table>{body}</table>"


def _metadata(ctx: ConvertContext) -> str:
    if not ctx.metadata:
        return "<h2>Metadata</h2><p>No metadata fields were filled.</p>"
    rows = "".join(
        f"<tr><th>{escape(m.name)}</th><td>{escape(_value(m.value))}</td>"
        f"<td>{escape(m.source)}</td><td><code>{escape(m.evidence)}</code></td></tr>"
        for m in ctx.metadata
    )
    return (
        "<h2>Metadata</h2><table><tr><th>field</th><th>value</th><th>source</th>"
        f"<th>evidence</th></tr>{rows}</table>"
    )


def _section(title: str, items: list[str], empty: str) -> str:
    inner = "\n".join(items) if items else f"<p>{escape(empty)}</p>"
    return f'<section id="{title.lower()}"><h2>{escape(title)}</h2>{inner}</section>'


def _issue_card(
    n: int,
    issue: Issue,
    ctx: ConvertContext,
    renderer: _Renderer,
    excerpts: _Excerpts,
    page_blocks: dict[int, list[OutputBlock]],
) -> str:
    crop = None
    if issue.page is not None and 0 <= issue.page < len(ctx.source.pages):
        if issue.region is not None:
            box = _padded(issue.region)
            crop = renderer.render(issue.page, box, CROP_DPI, f"issue-{n:04d}.png")
        else:
            name = f"page-{issue.page:04d}.png"
            crop = renderer.render(issue.page, None, PAGE_DPI, name)
    facts = [
        ("page", _page_name(ctx, issue.page)),
        ("anchor", issue.output_anchor or ""),
        ("evidence", issue.evidence),
        ("action", issue.action),
        ("uncertainty", issue.uncertainty),
        ("material", "yes" if issue.material else "no"),
    ]
    dl = "".join(f"<dt>{escape(k)}</dt><dd>{escape(v)}</dd>" for k, v in facts if v)
    page_link = f' <a href="#page-{issue.page}">page section</a>' if issue.page is not None else ""
    return (
        f'<article class="card sev-{issue.severity.value}" id="issue-{n}">'
        f'<h3><span class="badge">{escape(issue.severity.value)}</span> '
        f"<code>{escape(issue.code)}</code>{page_link}</h3>"
        f"<p>{escape(issue.message)}</p><dl>{dl}</dl>"
        f'<div class="pair"><figure class="source">{_img(crop, "source crop")}'
        f"<figcaption>PDF {escape(_page_name(ctx, issue.page))}</figcaption></figure>"
        f'<div class="epub">{_issue_excerpt(issue, excerpts, page_blocks)}</div></div></article>'
    )


def _issue_excerpt(
    issue: Issue, excerpts: _Excerpts, page_blocks: dict[int, list[OutputBlock]]
) -> str:
    """The block at the issue's anchor, then the other blocks aligned to its page."""
    parts = []
    target = excerpts.element(issue.output_anchor) if issue.output_anchor else None
    if target is not None:
        parts.append(f"<h4>EPUB at {escape(issue.output_anchor)}</h4>")
        parts.append(f'<div class="block target">{excerpts.html(*target)}</div>')
    blocks = page_blocks.get(issue.page, []) if issue.page is not None else []
    if target is not None:
        blocks = [b for b in blocks if f"{b.href}#{b.id}" != issue.output_anchor]
    if blocks:
        parts.append(
            "<h4>Also aligned to this page</h4>" if target else "<h4>EPUB on this page</h4>"
        )
        parts.append(_excerpt_blocks(blocks[:MAX_PAGE_EXCERPT_BLOCKS], excerpts))
        if (more := len(blocks) - MAX_PAGE_EXCERPT_BLOCKS) > 0:
            parts.append(f'<p class="note">{more} more blocks in the page section.</p>')
    return "".join(parts) or '<p class="note">No EPUB text is aligned to this page.</p>'


def _repair_card(n: int, repair) -> str:
    evidence = " ".join(repair.evidence)
    return (
        f'<article class="card repair" id="repair-{n}"><h3><code>{escape(repair.family)}.'
        f"{escape(repair.action)}</code></h3><dl><dt>target</dt><dd>{escape(repair.target)}</dd>"
        f"<dt>evidence</dt><dd><code>{escape(evidence)}</code></dd></dl>"
        f'<div class="pair"><div><h4>before</h4><pre>{escape(_value(repair.before))}</pre></div>'
        f"<div><h4>after</h4><pre>{escape(_value(repair.after))}</pre></div></div></article>"
    )


def _page_strip(ctx: ConvertContext, page_images: dict[int, str | None]) -> str:
    items = "".join(
        f'<a href="#page-{i}">{_img(page_images.get(i), "page " + str(i))}'
        f"<span>{escape(_page_name(ctx, i))}</span></a>"
        for i in range(len(ctx.source.pages))
    )
    return f'<nav class="strip" aria-label="Pages">{items}</nav>'


def _page_section(
    ctx: ConvertContext,
    index: int,
    page_images: dict[int, str | None],
    excerpts: _Excerpts,
    page_blocks: dict[int, list[OutputBlock]],
) -> str:
    links = " ".join(
        f'<a href="#issue-{n}">{escape(i.code)}</a>'
        for n, i in enumerate(ctx.issues)
        if i.page == index
    )
    issues = f"<p>Issues: {links}</p>" if links else ""
    blocks = page_blocks.get(index, [])
    epub = _excerpt_blocks(blocks, excerpts) or '<p class="note">No EPUB text aligned.</p>'
    return (
        f'<article class="page" id="page-{index}"><h3>{escape(_page_name(ctx, index))}</h3>'
        f'{issues}<div class="pair"><figure class="source">'
        f"{_img(page_images.get(index), 'page image')}</figure>"
        f'<div class="epub">{epub}</div></div></article>'
    )


# --------------------------------------------------------------------------- helpers


def _page_blocks(blocks: list[OutputBlock]) -> dict[int, list[OutputBlock]]:
    pages: dict[int, list[OutputBlock]] = {}
    for block in blocks:
        for page in sorted({s.page for s in block.spans}):
            pages.setdefault(page, []).append(block)
    return pages


def _excerpt_blocks(blocks: list[OutputBlock], excerpts: _Excerpts) -> str:
    """Each block once, skipping blocks nested inside one already shown."""
    found = [f for b in blocks if (f := excerpts.element(f"{b.href}#{b.id}"))]
    shown = {el for _, el in found}
    parts = []
    for href, el in found:
        if any(anc in shown for anc in el.iterancestors()):
            continue
        parts.append(f'<div class="block" title="{escape(posixpath.basename(href))}">')
        parts.append(excerpts.html(href, el))
        parts.append("</div>")
    return "".join(parts)


def _img(rel: str | None, alt: str) -> str:
    if rel is None:
        return '<span class="missing">no image</span>'
    return f'<img src="{escape(rel, quote=True)}" alt="{escape(alt, quote=True)}" loading="lazy">'


def _padded(box: Box) -> Box:
    m = CROP_MARGIN
    return Box(
        max(0.0, box.x0 - m), max(0.0, box.y0 - m), min(1.0, box.x1 + m), min(1.0, box.y1 + m)
    )


def _page_name(ctx: ConvertContext, page: int | None) -> str:
    if page is None:
        return "no page"
    label = None
    if 0 <= page < len(ctx.source.pages):
        inv = ctx.source.pages[page]
        label = inv.label or (inv.folio_candidates[0] if inv.folio_candidates else None)
    return f"page {page}" + (f" (printed {label})" if label else "")


def _page_key(page: int | None) -> int:
    return page if page is not None else -1


def _pct(ratio: float | None) -> str:
    return "n/a" if ratio is None else f"{ratio:.1%}"


def _value(value) -> str:
    if value is None:
        return ""
    if isinstance(value, (list, tuple)):
        return ", ".join(str(v) for v in value)
    return str(value)


_CSS = """
:root{color-scheme:light dark;--bg:#fff;--fg:#1b1b1b;--muted:#5f6368;--card:#f6f7f9;
--line:#d9dce1;--crit:#b3261e;--major:#b26a00;--minor:#5b6b00;--info:#2a5db0}
@media (prefers-color-scheme:dark){:root{--bg:#16181c;--fg:#e8e8e8;--muted:#a0a4ab;
--card:#1f2228;--line:#33373e;--crit:#ff8a80;--major:#ffb74d;--minor:#c5d86d;--info:#8ab4f8}}
*{box-sizing:border-box}
body{margin:0 auto;max-width:1200px;padding:16px;background:var(--bg);color:var(--fg);
font:15px/1.5 system-ui,sans-serif}
h1{margin:.2em 0}h2{border-bottom:1px solid var(--line);padding-bottom:.2em;margin-top:1.6em}
h3{margin:0 0 .4em;font-size:1.05em}h4{margin:0 0 .4em;font-size:.9em;color:var(--muted)}
code,pre{font-family:ui-monospace,monospace;font-size:.88em}
pre{white-space:pre-wrap;word-break:break-word;margin:0}
.meta,.note{color:var(--muted)}
table{border-collapse:collapse;width:100%;font-size:.92em}
th,td{border-bottom:1px solid var(--line);padding:.25em .5em;text-align:left;vertical-align:top}
.counts{list-style:none;padding:0;display:flex;gap:.6em;flex-wrap:wrap}
.counts li{background:var(--card);border-left:4px solid var(--line);padding:.3em .7em}
.counts b{margin-left:.5em}
.card,.page{background:var(--card);border:1px solid var(--line);border-left-width:5px;
border-radius:6px;padding:12px;margin:12px 0}
.sev-critical{border-left-color:var(--crit)}.sev-major{border-left-color:var(--major)}
.sev-minor{border-left-color:var(--minor)}.sev-info{border-left-color:var(--info)}
.badge{text-transform:uppercase;font-size:.75em;letter-spacing:.05em;color:var(--muted)}
dl{display:grid;grid-template-columns:max-content 1fr;gap:.1em .8em;margin:.4em 0;
font-size:.9em}dt{color:var(--muted)}dd{margin:0;word-break:break-word}
.pair{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:12px;margin-top:8px}
@media (max-width:700px){.pair{grid-template-columns:minmax(0,1fr)}}
figure{margin:0}figcaption{color:var(--muted);font-size:.85em}
.source img{max-width:100%;background:#fff;border:1px solid var(--line)}
.epub{background:var(--bg);border:1px solid var(--line);padding:8px 12px;overflow-wrap:anywhere}
.epub img{max-width:100%}
.block{border-bottom:1px dashed var(--line)}.block:last-child{border-bottom:0}
.target{border-left:3px solid var(--info);padding-left:8px}
.omitted,.missing{color:var(--muted);font-style:italic}
.strip{display:flex;gap:6px;overflow-x:auto;padding:8px 0;border-bottom:1px solid var(--line)}
.strip a{flex:0 0 auto;text-align:center;font-size:.75em;color:var(--muted);text-decoration:none}
.strip img{display:block;height:110px;background:#fff;border:1px solid var(--line)}
a{color:var(--info)}
"""
