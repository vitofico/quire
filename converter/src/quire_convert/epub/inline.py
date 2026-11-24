"""Edit a block element in text-offset space while keeping its inline markup.

Offsets index ``text_of(el)``: the block's own text with whitespace collapsed, exactly
what ``Book.ensure_block_ids`` reports (text inside nested blocks is excluded). A
position is resolved to the earliest equivalent DOM point, so an offset between
``"foo "`` and ``<em>bar</em>`` lands after ``"foo "`` rather than inside the emphasis.
"""

from __future__ import annotations

import copy
from dataclasses import dataclass

from lxml import etree

from .book import is_block, remove_keep_tail
from .package import local_name

_VOID = frozenset({"img", "br", "hr", "wbr", "svg", "math", "video", "audio", "object"})


@dataclass
class _Piece:
    node: etree._Element
    attr: str  # "text" or "tail"
    start: int  # offset in the raw (uncollapsed) concatenation
    value: str


@dataclass
class _Layout:
    text: str  # collapsed text, == text_of(el)
    raw_index: list[int]  # raw offset of each collapsed character
    pieces: list[_Piece]


def _layout(el: etree._Element) -> _Layout:
    if local_name(el) == "img":
        raise ValueError("an img has no editable text")
    pieces: list[_Piece] = []
    pos = 0

    def add(node: etree._Element, attr: str, value: str | None) -> None:
        nonlocal pos
        pieces.append(_Piece(node, attr, pos, value or ""))
        pos += len(value or "")

    def collect(node: etree._Element) -> None:
        for child in node:
            if isinstance(child.tag, str) and not is_block(child):
                if local_name(child) not in ("script", "style"):
                    add(child, "text", child.text)
                    collect(child)
            add(child, "tail", child.tail)

    add(el, "text", el.text)
    collect(el)
    chars: list[str] = []
    raw_index: list[int] = []
    space_at = None
    for r, ch in enumerate("".join(p.value for p in pieces)):
        if ch.isspace():
            if chars and space_at is None:
                space_at = r
            continue
        if space_at is not None:
            chars.append(" ")
            raw_index.append(space_at)
            space_at = None
        chars.append(ch)
        raw_index.append(r)
    return _Layout("".join(chars), raw_index, pieces)


def text_of(el: etree._Element) -> str:
    """The block's own text, whitespace collapsed (same as ``OutputBlock.text``)."""
    if local_name(el) == "img":
        return " ".join(el.get("alt", "").split())
    return _layout(el).text


def locate(el: etree._Element, offset: int) -> tuple[etree._Element, str, int]:
    """The DOM point before character ``offset`` of ``text_of(el)``.

    Returns ``(node, attr, index)``: the point is ``index`` characters into
    ``node.text`` (attr ``"text"``) or ``node.tail`` (attr ``"tail"``). ``offset`` may
    equal the text length, meaning the end of the block's text.
    """
    lay = _layout(el)
    n = len(lay.text)
    if not 0 <= offset <= n:
        raise IndexError(f"offset {offset} outside 0..{n}")
    if n == 0:
        return el, "text", len(el.text or "")
    raw = lay.raw_index[offset] if offset < n else lay.raw_index[-1] + 1
    for piece in lay.pieces:
        if piece.start <= raw <= piece.start + len(piece.value):
            return piece.node, piece.attr, raw - piece.start
    raise AssertionError("offset maps outside every text piece")


def insert_at(el: etree._Element, offset: int, new_el: etree._Element) -> None:
    """Insert ``new_el`` (an inline element, e.g. a page-break span) before ``offset``."""
    node, attr, i = locate(el, offset)
    value = (node.text if attr == "text" else node.tail) or ""
    new_el.tail = ((new_el.tail or "") + value[i:]) or None
    if attr == "text":
        node.text = value[:i] or None
        node.insert(0, new_el)
    else:
        node.tail = value[:i] or None
        parent = node.getparent()
        parent.insert(parent.index(node) + 1, new_el)


def split_at(el: etree._Element, offset: int) -> etree._Element:
    """Split ``el`` before ``offset``; the second half becomes a new sibling and is returned.

    Inline ancestors of the split point are cloned into the second half, so emphasis,
    language spans and links continue across the split. Ids stay with the first half,
    except ids of elements that lie wholly in the second half. Inline wrappers left
    empty by the split are dropped; either half may end up with no text.
    """
    node, attr, i = locate(el, offset)
    path = _path(el, node)
    right = copy.deepcopy(el)
    left_chain, right_chain = _chain(el, path), _chain(right, path)
    _keep_before(left_chain, attr, i)
    _keep_after(right_chain, attr, i)
    for kept in right_chain:
        if kept is right or kept.getparent() is not None:
            kept.attrib.pop("id", None)
    _drop_empty_wrappers(left_chain[1:])
    _drop_empty_wrappers(right_chain[1:])
    right.tail, el.tail = el.tail, None
    parent = el.getparent()
    if parent is not None:
        parent.insert(parent.index(el) + 1, right)
    return right


def merge(a: etree._Element, b: etree._Element, sep: str = "") -> etree._Element:
    """Append ``b``'s content to ``a`` (joined by ``sep``) and remove ``b``; return ``a``.

    ``b``'s id survives as an empty ``<span id>`` at the join point, so links and page
    anchors that targeted ``b`` keep working.
    """
    if a is b or b in a.iterancestors() or a in b.iterancestors():
        raise ValueError("merge needs two separate elements")
    tail_target = a[-1] if len(a) else None

    def append_text(value: str) -> None:
        nonlocal tail_target
        if not value:
            return
        if tail_target is None:
            a.text = (a.text or "") + value
        else:
            tail_target.tail = (tail_target.tail or "") + value

    append_text(sep)
    if (ident := b.get("id")) is not None:
        ns = etree.QName(a).namespace
        anchor = a.makeelement(f"{{{ns}}}span" if ns else "span", {"id": ident})
        a.append(anchor)
        tail_target = anchor
        b.attrib.pop("id")
    append_text(b.text or "")
    for child in list(b):
        a.append(child)
    remove_keep_tail(b)
    return a


# --------------------------------------------------------------------------- helpers


def _path(root: etree._Element, node: etree._Element) -> list[int]:
    path = []
    while node is not root:
        parent = node.getparent()
        if parent is None:
            raise ValueError("node is not inside the element")
        path.append(parent.index(node))
        node = parent
    return path[::-1]


def _chain(root: etree._Element, path: list[int]) -> list[etree._Element]:
    chain = [root]
    for index in path:
        chain.append(chain[-1][index])
    return chain


def _keep_before(chain: list[etree._Element], attr: str, i: int) -> None:
    last = chain[-1]
    if attr == "text":
        last.text = (last.text or "")[:i] or None
        for child in list(last):
            last.remove(child)
    else:
        last.tail = (last.tail or "")[:i] or None
    for depth, node in enumerate(chain[1:], 1):
        for sibling in list(node.itersiblings()):
            node.getparent().remove(sibling)
        if depth < len(chain) - 1 or attr == "text":
            node.tail = None


def _keep_after(chain: list[etree._Element], attr: str, i: int) -> None:
    last = chain[-1]
    if attr == "text":
        last.text = (last.text or "")[i:] or None
        levels = chain[1:]
    else:
        parent = last.getparent()
        rest = (last.tail or "")[i:] or None
        for sibling in list(last.itersiblings(preceding=True)):
            parent.remove(sibling)
        parent.remove(last)
        parent.text = rest
        levels = chain[1:-1]
    for node in reversed(levels):
        parent = node.getparent()
        for sibling in list(node.itersiblings(preceding=True)):
            parent.remove(sibling)
        parent.text = None


def _drop_empty_wrappers(nodes: list[etree._Element]) -> None:
    for node in reversed(nodes):
        if node.getparent() is None:
            continue
        empty = not (node.text or "") and len(node) == 0
        if empty and node.get("id") is None and local_name(node) not in _VOID:
            remove_keep_tail(node)
