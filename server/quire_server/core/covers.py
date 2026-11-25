"""Draw a designed cover: a typographic paperback from a few bytes of design.

The tidy call (``core/ai/details.py``) picks three colours, a motif, a
layout and a typeface. This module draws the cover from that, with the title
and author set in real type, so the text is always legible and correct. The
same design, title and author always give the same image, so the server
stores nothing and draws on request.

Type is set in one of three bundled faces (Literata, Lora, Noto Sans). A
character the face lacks is taken from the first fallback that has it: Noto
Sans (Latin, Greek, Cyrillic and more), Noto Naskh Arabic, Droid Sans
Fallback (Chinese and Japanese), then Nanum Gothic (Korean). Arabic is
shaped and set right to left when Pillow has its Raqm layout engine (libraqm
with FriBiDi, installed in the server image); without it the letters are
drawn unjoined. The fonts and their licences live in ``assets/fonts``.

Everything is drawn at twice the size and scaled down, which smooths the
edges of the shapes as well as the type.
"""

from __future__ import annotations

import hashlib
import io
import math
import random
import re
import unicodedata
from collections.abc import Callable
from functools import lru_cache
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont, features

# Part of the cover's ETag: bump it when a change here alters drawn covers.
RENDERER_VERSION = "1"
WIDTH, HEIGHT = 600, 900
_SCALE = 2
_W, _H = WIDTH * _SCALE, HEIGHT * _SCALE
_FONTS = Path(__file__).resolve().parent.parent / "assets" / "fonts"
_LAYOUT = ImageFont.Layout.RAQM if features.check("raqm") else ImageFont.Layout.BASIC

# Face per typeface: file, title weight, author weight.
_FACES = {
    "serif": ("Literata.ttf", 640, 460),
    "elegant": ("Lora.ttf", 600, 450),
    "sans": ("NotoSans.ttf", 720, 500),
}
_FALLBACKS = (
    "NotoSans.ttf",
    "NotoNaskhArabic.ttf",
    "DroidSansFallbackFull.ttf",
    "NanumGothic.ttf",
)

# Background, accent, ink: used when the stored colours are not #RRGGBB.
_PALETTES = (
    ("#F3EBDD", "#B4532A", "#2B2118"),
    ("#1F2A44", "#E0A458", "#F4EFE6"),
    ("#E8EFE9", "#3C6E57", "#17251E"),
    ("#2E1F27", "#D98F8F", "#F6ECEC"),
    ("#F4F1EA", "#2F5D8A", "#1A2430"),
    ("#3A3A3A", "#C9B37E", "#F2EEE6"),
)
_HEX = re.compile(r"^#[0-9a-fA-F]{6}$")


# ---------------------------------------------------------------------------
# Colour
# ---------------------------------------------------------------------------


def _rgb(value: str) -> tuple[int, int, int]:
    return (int(value[1:3], 16), int(value[3:5], 16), int(value[5:7], 16))


def _luminance(rgb: tuple[int, int, int]) -> float:
    def channel(c: int) -> float:
        c = c / 255
        return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4

    r, g, b = (channel(c) for c in rgb)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def _contrast(a: tuple[int, int, int], b: tuple[int, int, int]) -> float:
    la, lb = sorted((_luminance(a), _luminance(b)), reverse=True)
    return (la + 0.05) / (lb + 0.05)


def _mix(a: tuple[int, int, int], b: tuple[int, int, int], t: float) -> tuple[int, int, int]:
    return tuple(round(x + (y - x) * t) for x, y in zip(a, b, strict=True))  # type: ignore[return-value]


def _colours(design: dict, seed: int) -> tuple[tuple[int, int, int], ...]:
    """Background, accent, ink and a soft accent, legible whatever was stored.

    Colours that are not #RRGGBB fall back to a palette. Ink that does not
    reach a contrast of 4.5 against the background (the WCAG level for body
    text) becomes a near black or a near white, and an accent too close to
    the background is pulled toward the ink so the motif shows.
    """
    stored = [design.get(k) for k in ("background", "accent", "ink")]
    if all(isinstance(c, str) and _HEX.match(c) for c in stored):
        bg, accent, ink = (_rgb(c) for c in stored)
    else:
        bg, accent, ink = (_rgb(c) for c in _PALETTES[seed % len(_PALETTES)])
    if _contrast(ink, bg) < 4.5:
        dark, light = (24, 22, 20), (247, 243, 235)
        ink = dark if _contrast(dark, bg) >= _contrast(light, bg) else light
    if _contrast(accent, bg) < 1.6:
        accent = _mix(accent, ink, 0.45)
    return bg, accent, ink, _mix(accent, bg, 0.5)


# ---------------------------------------------------------------------------
# Type
# ---------------------------------------------------------------------------


@lru_cache(maxsize=64)
def _font(name: str, size: int, weight: int | None) -> ImageFont.FreeTypeFont:
    font = ImageFont.truetype(str(_FONTS / name), size, layout_engine=_LAYOUT)
    try:
        axes = font.get_variation_axes()
    except OSError:
        return font  # a static face
    values = []
    for axis in axes:
        label = axis["name"].decode() if isinstance(axis["name"], bytes) else str(axis["name"])
        if label == "Weight" and weight is not None:
            values.append(max(axis["minimum"], min(axis["maximum"], weight)))
        elif label == "Optical size":
            values.append(max(axis["minimum"], min(axis["maximum"], size // _SCALE)))
        else:
            values.append(axis["default"])
    font.set_variation_by_axes(values)
    return font


@lru_cache(maxsize=16)
def _probe_font(name: str) -> ImageFont.FreeTypeFont:
    # Basic layout draws exactly what the font maps. Raqm would decompose a
    # character the font lacks, such as a Korean syllable, into pieces the
    # font has, and the check would take the pieces for the character.
    return ImageFont.truetype(str(_FONTS / name), 32, layout_engine=ImageFont.Layout.BASIC)


def _glyph_image(name: str, text: str) -> bytes:
    font = _probe_font(name)
    image = Image.new("L", (96, 96))
    ImageDraw.Draw(image).text((16, 16), text, font=font, fill=255)
    return image.tobytes()


@lru_cache(maxsize=8192)
def has_glyph(name: str, char: str) -> bool:
    """Whether the font draws ``char`` as itself rather than as its
    missing-glyph box: the two renderings are compared."""
    return _glyph_image(name, char) != _glyph_image(name, "￿")


def _is_rtl(char: str) -> bool:
    return unicodedata.bidirectional(char) in ("R", "AL")


def _reads_rtl(text: str) -> bool:
    """Whether a line's first letter belongs to a right-to-left script."""
    strong = next((c for c in text if unicodedata.bidirectional(c) in ("L", "R", "AL")), "a")
    return _is_rtl(strong)


def _is_wide(char: str) -> bool:
    """Chinese, Japanese and Korean: a line may break between any two."""
    return unicodedata.east_asian_width(char) in ("W", "F")


class _Face:
    """One typeface at one size, with its fallbacks."""

    def __init__(self, face: str, size: int, weight: int) -> None:
        self.chain = (face, *(f for f in _FALLBACKS if f != face))
        self.size = size
        self.weight = weight

    def font(self, name: str) -> ImageFont.FreeTypeFont:
        return _font(name, self.size, self.weight)

    def runs(self, text: str) -> list[tuple[str, str]]:
        """``text`` cut into (font, text) runs, each character in the first
        font that has it. Spaces and joining marks stay in the current run."""
        runs: list[tuple[str, str]] = []
        for char in text:
            if runs and (char.isspace() or unicodedata.combining(char)):
                runs[-1] = (runs[-1][0], runs[-1][1] + char)
                continue
            name = next((f for f in self.chain if has_glyph(f, char)), self.chain[0])
            if runs and runs[-1][0] == name:
                runs[-1] = (name, runs[-1][1] + char)
            else:
                runs.append((name, char))
        return runs

    def _run_text(self, text: str) -> tuple[str, str | None]:
        if not any(_is_rtl(c) for c in text):
            return text, None
        if _LAYOUT == ImageFont.Layout.RAQM:
            return text, "rtl"
        return text[::-1], None  # unshaped, but in reading order

    def width(self, text: str) -> float:
        total = 0.0
        for name, run in self.runs(text):
            run, direction = self._run_text(run)
            total += self.font(name).getlength(run, direction=direction)
        return total

    def line_height(self) -> float:
        ascent, descent = self.font(self.chain[0]).getmetrics()
        return (ascent + descent) * 1.08

    def draw(self, d: ImageDraw.ImageDraw, x: float, y: float, text: str, fill) -> None:
        runs = self.runs(text)
        if _reads_rtl(text):
            runs = runs[::-1]
        ascent = self.font(self.chain[0]).getmetrics()[0]
        for name, run in runs:
            font = self.font(name)
            run, direction = self._run_text(run)
            # Align every run on the primary face's baseline.
            d.text((x, y + ascent), run, font=font, fill=fill, anchor="ls", direction=direction)
            x += font.getlength(run, direction=direction)


def _tokens(text: str) -> list[str]:
    """Words, and each wide character on its own; a token keeps the space
    that precedes it. Wide punctuation stays with the character before it,
    since a line never starts with it."""
    tokens: list[str] = []
    for word in re.findall(r"\s*\S+", text):
        lead = word[: len(word) - len(word.lstrip())]
        part = ""
        for char in word.lstrip():
            if (
                _is_wide(char)
                and unicodedata.category(char).startswith("P")
                and tokens
                and not part
            ):
                tokens[-1] += char
            elif _is_wide(char):
                if part:
                    tokens.append(lead + part)
                    lead, part = "", ""
                tokens.append(lead + char)
                lead = ""
            else:
                part += char
        if part:
            tokens.append(lead + part)
    return tokens


def _wrap(face: _Face, text: str, width: float) -> list[str]:
    lines: list[str] = []
    line = ""
    for token in _tokens(text):
        candidate = line + token if line else token.lstrip()
        if not line or face.width(candidate) <= width:
            line = candidate
            continue
        lines.append(line)
        line = token.lstrip()
    if line:
        lines.append(line)
    # A word wider than the line is cut where it no longer fits.
    out: list[str] = []
    for line in lines:
        while len(line) > 1 and face.width(line) > width:
            # The longest head that fits, found by halving: widths grow with
            # length, and measuring every head is quadratic in the word.
            lo, hi = 1, len(line) - 1
            while lo < hi:
                mid = (lo + hi + 1) // 2
                if face.width(line[:mid]) <= width:
                    lo = mid
                else:
                    hi = mid - 1
            out.append(line[:lo])
            line = line[lo:]
        out.append(line)
    return out


def _capped(text: str, cap: int) -> str:
    """At most ``cap`` characters, cut at a space when one is near, with an
    ellipsis: no cover has room for more, and drawing more only costs time."""
    if len(text) <= cap:
        return text
    head = text[: cap - 1]
    space = head.rfind(" ")
    if space >= cap // 2:
        head = head[:space]
    return head.rstrip() + "…"


def _ellipsize(face: _Face, line: str, width: float) -> str:
    while line and face.width(line + "…") > width:
        line = line[:-1].rstrip()
    return line + "…"


def _fit(
    face_name: str,
    weight: int,
    text: str,
    box_w: float,
    box_h: float,
    *,
    largest: int,
    smallest: int,
    max_lines: int,
) -> tuple[_Face, list[str]]:
    """The largest size at which ``text`` wraps into the box; at the smallest,
    the lines that fit, the last one cut with an ellipsis."""
    size = largest
    while True:
        face = _Face(face_name, size * _SCALE, weight)
        lines = _wrap(face, text, box_w)
        if len(lines) <= max_lines and len(lines) * face.line_height() <= box_h:
            return face, lines
        if size <= smallest:
            room = max(1, min(max_lines, int(box_h // face.line_height())))
            if len(lines) > room:
                lines = [*lines[: room - 1], _ellipsize(face, lines[room - 1], box_w)]
            return face, lines
        size -= 2


def _set(
    d: ImageDraw.ImageDraw,
    face: _Face,
    lines: list[str],
    box: tuple[float, float, float, float],
    fill,
    *,
    align: str,
    valign: str,
) -> float:
    """Draw ``lines`` in the box; returns the y below the last line."""
    x0, y0, x1, y1 = box
    height = len(lines) * face.line_height()
    y = {"top": y0, "middle": y0 + (y1 - y0 - height) / 2, "bottom": y1 - height}[valign]
    for line in lines:
        w = face.width(line)
        x = {"left": x0, "center": x0 + (x1 - x0 - w) / 2, "right": x1 - w}[align]
        face.draw(d, x, y, line, fill)
        y += face.line_height()
    return y


def _author_line(authors: list[str]) -> str:
    names = [a for a in authors if a.strip()]
    if len(names) <= 2:
        return " & ".join(names)
    return ", ".join(names[:-1]) + " & " + names[-1]


# ---------------------------------------------------------------------------
# Motifs: each draws on a transparent layer of its own size.
# ---------------------------------------------------------------------------

Motif = Callable[[ImageDraw.ImageDraw, int, int, tuple, tuple, random.Random], None]


def _sun(d, w, h, fg, soft, rng):
    cx, cy, r = w / 2, h / 2, min(w, h) / 2
    for i in range(24):
        a = 2 * math.pi * i / 24
        inner, outer = r * 0.56, r * (0.95 if i % 2 == 0 else 0.8)
        d.line(
            [
                (cx + inner * math.cos(a), cy + inner * math.sin(a)),
                (cx + outer * math.cos(a), cy + outer * math.sin(a)),
            ],
            fill=soft if i % 2 else fg,
            width=max(2, int(r * 0.035)),
        )
    d.ellipse((cx - r * 0.44, cy - r * 0.44, cx + r * 0.44, cy + r * 0.44), fill=fg)


def _moon(d, w, h, fg, soft, rng):
    cx, cy, r = w / 2, h / 2, min(w, h) * 0.38
    d.ellipse((cx - r * 1.25, cy - r * 1.25, cx + r * 1.25, cy + r * 1.25), outline=soft, width=4)
    d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=fg)
    off = r * 0.42
    d.ellipse(
        (cx - r + off, cy - r - off * 0.4, cx + r + off, cy + r - off * 0.4), fill=(0, 0, 0, 0)
    )


def _star(d, x, y, r, fill):
    points = []
    for i in range(10):
        a = -math.pi / 2 + math.pi * i / 5
        rr = r if i % 2 == 0 else r * 0.42
        points.append((x + rr * math.cos(a), y + rr * math.sin(a)))
    d.polygon(points, fill=fill)


def _stars(d, w, h, fg, soft, rng):
    for _ in range(16):
        r = rng.uniform(0.025, 0.07) * min(w, h)
        _star(
            d, rng.uniform(r, w - r), rng.uniform(r, h - r), r, fg if r > 0.05 * min(w, h) else soft
        )
    _star(d, w * 0.5, h * 0.5, min(w, h) * 0.18, fg)


def _waves(d, w, h, fg, soft, rng):
    rows = 9
    for row in range(rows):
        y0 = h * (row + 0.5) / rows
        amp = h / rows * 0.32
        phase = row * 0.6
        pts = [
            (x, y0 + amp * math.sin(phase + 2 * math.pi * x / (w / 2.2)))
            for x in range(0, w + 8, 8)
        ]
        d.line(pts, fill=fg if row % 3 == 0 else soft, width=max(3, int(h * 0.014)))


def _mountains(d, w, h, fg, soft, rng):
    d.polygon([(0, h), (w * 0.3, h * 0.25), (w * 0.62, h)], fill=soft)
    d.polygon([(w * 0.35, h), (w * 0.72, h * 0.12), (w * 1.05, h)], fill=fg)
    d.polygon(
        [(w * 0.62, h * 0.37), (w * 0.72, h * 0.12), (w * 0.79, h * 0.27), (w * 0.72, h * 0.32)],
        fill=soft,
    )
    d.ellipse((w * 0.12, h * 0.08, w * 0.24, h * 0.08 + w * 0.12), fill=soft)


def _rings(d, w, h, fg, soft, rng):
    cx, cy, r = w / 2, h / 2, min(w, h) / 2
    for i in range(7, 0, -1):
        rr = r * i / 7
        d.ellipse(
            (cx - rr, cy - rr, cx + rr, cy + rr),
            outline=fg if i % 2 else soft,
            width=max(3, int(r * (0.05 if i % 2 else 0.025))),
        )
    d.ellipse((cx - r * 0.08, cy - r * 0.08, cx + r * 0.08, cy + r * 0.08), fill=fg)


def _grid(d, w, h, fg, soft, rng):
    n = 8
    step = min(w, h) / n
    ox, oy = (w - step * n) / 2, (h - step * n) / 2
    for i in range(n + 1):
        d.line([(ox + i * step, oy), (ox + i * step, oy + n * step)], fill=soft, width=3)
        d.line([(ox, oy + i * step), (ox + n * step, oy + i * step)], fill=soft, width=3)
    for _ in range(5):
        cx, cy = rng.randrange(n), rng.randrange(n)
        d.rectangle(
            (ox + cx * step, oy + cy * step, ox + (cx + 1) * step, oy + (cy + 1) * step), fill=fg
        )


def _dots(d, w, h, fg, soft, rng):
    n = 11
    step = min(w, h) / n
    ox, oy = (w - step * n) / 2, (h - step * n) / 2
    for i in range(n):
        for j in range(n):
            x, y = ox + (i + 0.5) * step, oy + (j + 0.5) * step
            dist = math.hypot(i - (n - 1) / 2, j - (n - 1) / 2) / (n / 2)
            r = step * 0.42 * max(0.12, 1 - dist)
            d.ellipse((x - r, y - r, x + r, y + r), fill=fg if dist < 0.45 else soft)


def _stripes(d, w, h, fg, soft, rng):
    y = 0.0
    i = 0
    while y < h:
        band = h * (0.05 + 0.04 * (i % 3))
        if i % 2 == 0:
            d.rectangle((0, y, w, y + band), fill=fg if i % 4 == 0 else soft)
        y += band
        i += 1


def _diagonals(d, w, h, fg, soft, rng):
    step = max(w, h) / 14
    for i in range(-14, 30):
        x = i * step
        d.line(
            [(x, h), (x + h, 0)], fill=fg if i % 3 == 0 else soft, width=max(3, int(step * 0.22))
        )


def _arch(d, w, h, fg, soft, rng):
    aw = min(w * 0.72, h * 0.6)
    x0, x1 = (w - aw) / 2, (w + aw) / 2
    top = h * 0.08
    d.rectangle((x0, top + aw / 2, x1, h), fill=fg)
    d.pieslice((x0, top, x1, top + aw), 180, 360, fill=fg)
    inset = aw * 0.16
    d.rectangle((x0 + inset, top + aw / 2, x1 - inset, h), fill=soft)
    d.pieslice((x0 + inset, top + inset, x1 - inset, top + aw - inset), 180, 360, fill=soft)


def _diamonds(d, w, h, fg, soft, rng):
    n = 5
    dw, dh = w / n, h / n
    for i in range(n + 1):
        for j in range(n + 1):
            cx, cy = i * dw, j * dh
            pts = [(cx, cy - dh / 2), (cx + dw / 2, cy), (cx, cy + dh / 2), (cx - dw / 2, cy)]
            d.polygon(pts, outline=soft, width=3)
    cx, cy = w / 2, h / 2
    d.polygon([(cx, cy - dh), (cx + dw, cy), (cx, cy + dh), (cx - dw, cy)], fill=fg)


def _chevrons(d, w, h, fg, soft, rng):
    rows = 7
    for row in range(rows):
        y = h * (row + 0.3) / rows
        d.line(
            [(w * 0.08, y), (w * 0.5, y + h / rows * 0.7), (w * 0.92, y)],
            fill=fg if row % 2 == 0 else soft,
            width=max(4, int(h / rows * 0.28)),
            joint="curve",
        )


def _triangles(d, w, h, fg, soft, rng):
    n = 6
    tw, th = w / n, h / n
    for j in range(n):
        for i in range(n * 2 + 1):
            x = (i - 1) * tw / 2
            up = (i + j) % 2 == 0
            pts = (
                [(x, (j + 1) * th), (x + tw / 2, j * th), (x + tw, (j + 1) * th)]
                if up
                else [(x, j * th), (x + tw, j * th), (x + tw / 2, (j + 1) * th)]
            )
            pick = rng.random()
            if pick < 0.3:
                d.polygon(pts, fill=fg)
            elif pick < 0.65:
                d.polygon(pts, fill=soft)


def _rain(d, w, h, fg, soft, rng):
    for _ in range(60):
        x, y = rng.uniform(0, w), rng.uniform(0, h)
        length = rng.uniform(0.06, 0.16) * h
        d.line(
            [(x, y), (x - length * 0.3, y + length)],
            fill=fg if rng.random() < 0.3 else soft,
            width=max(3, int(min(w, h) * 0.018)),
        )


def _spiral(d, w, h, fg, soft, rng):
    cx, cy, r = w / 2, h / 2, min(w, h) * 0.47
    turns = 4.5
    pts = []
    for i in range(720):
        t = i / 719
        a = t * turns * 2 * math.pi
        pts.append((cx + r * t * math.cos(a), cy + r * t * math.sin(a)))
    d.line(pts, fill=fg, width=max(4, int(r * 0.05)), joint="curve")
    d.ellipse((cx - r, cy - r, cx + r, cy + r), outline=soft, width=3)


def _orbit(d, w, h, fg, soft, rng):
    cx, cy, r = w / 2, h / 2, min(w, h) / 2
    for k, tilt in enumerate((0.35, -0.5, 1.2)):
        pts = []
        for i in range(361):
            a = math.radians(i)
            x, y = r * 0.95 * math.cos(a), r * 0.34 * math.sin(a)
            pts.append(
                (
                    cx + x * math.cos(tilt) - y * math.sin(tilt),
                    cy + x * math.sin(tilt) + y * math.cos(tilt),
                )
            )
        d.line(pts, fill=soft if k else fg, width=max(3, int(r * 0.025)))
    d.ellipse((cx - r * 0.2, cy - r * 0.2, cx + r * 0.2, cy + r * 0.2), fill=fg)
    mx, my = cx + r * 0.82, cy - r * 0.33
    d.ellipse((mx - r * 0.06, my - r * 0.06, mx + r * 0.06, my + r * 0.06), fill=fg)


def _burst(d, w, h, fg, soft, rng):
    cx, cy = w / 2, h / 2
    reach = math.hypot(w, h)
    for i in range(36):
        a = 2 * math.pi * i / 36
        a2 = a + math.pi / 72
        d.polygon(
            [
                (cx, cy),
                (cx + reach * math.cos(a), cy + reach * math.sin(a)),
                (cx + reach * math.cos(a2), cy + reach * math.sin(a2)),
            ],
            fill=fg if i % 2 == 0 else soft,
        )
    r = min(w, h) * 0.08
    d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=fg)


def _squares(d, w, h, fg, soft, rng):
    cx, cy, r = w / 2, h / 2, min(w, h) / 2
    for i in range(8, 0, -1):
        s = r * i / 8
        a = math.radians(i * 7.5)
        pts = [
            (cx + s * math.cos(a + k * math.pi / 2), cy + s * math.sin(a + k * math.pi / 2))
            for k in range(4)
        ]
        d.polygon(pts, outline=fg if i % 2 else soft, width=max(3, int(r * 0.03)))


def _horizon(d, w, h, fg, soft, rng):
    line = h * 0.52
    r = min(w, h) * 0.3
    d.pieslice((w / 2 - r, line - r, w / 2 + r, line + r), 180, 360, fill=fg)
    y, gap, i = line, h * 0.03, 0
    while y < h:
        d.line([(0, y), (w, y)], fill=fg if i == 0 else soft, width=max(3, int(gap * 0.4)))
        y += gap
        gap *= 1.25
        i += 1


MOTIF_DRAWERS: dict[str, Motif] = {
    "sun": _sun,
    "moon": _moon,
    "stars": _stars,
    "waves": _waves,
    "mountains": _mountains,
    "rings": _rings,
    "grid": _grid,
    "dots": _dots,
    "stripes": _stripes,
    "diagonals": _diagonals,
    "arch": _arch,
    "diamonds": _diamonds,
    "chevrons": _chevrons,
    "triangles": _triangles,
    "rain": _rain,
    "spiral": _spiral,
    "orbit": _orbit,
    "burst": _burst,
    "squares": _squares,
    "horizon": _horizon,
}


def _motif(image: Image.Image, name: str, box, fg, soft, rng: random.Random) -> None:
    x0, y0, x1, y1 = (int(v) for v in box)
    layer = Image.new("RGBA", (x1 - x0, y1 - y0), (0, 0, 0, 0))
    MOTIF_DRAWERS.get(name, _rings)(ImageDraw.Draw(layer), x1 - x0, y1 - y0, fg, soft, rng)
    image.paste(layer, (x0, y0), layer)


# ---------------------------------------------------------------------------
# Layouts
# ---------------------------------------------------------------------------


# The most of each that a cover draws.
_TITLE_CAP = 200
_AUTHOR_CAP = 160


def draw_cover(design: dict, title: str, authors: list[str]) -> Image.Image:
    """The cover as a 600 x 900 RGB image."""
    seed = int.from_bytes(hashlib.sha256(f"{title}|{authors}".encode()).digest()[:8], "big")
    rng = random.Random(seed)
    bg, accent, ink, soft = _colours(design, seed)
    face_name, title_weight, author_weight = _FACES.get(design.get("typeface"), _FACES["serif"])
    layout = design.get("layout")
    motif = design.get("motif")
    title = _capped(title, _TITLE_CAP)
    author = _capped(_author_line(authors), _AUTHOR_CAP)

    image = Image.new("RGB", (_W, _H), bg)
    d = ImageDraw.Draw(image)
    m = 64 * _SCALE  # outer margin

    def title_in(box, *, largest, align, valign, max_lines=6):
        x0, y0, x1, y1 = box
        face, lines = _fit(
            face_name,
            title_weight,
            title,
            x1 - x0,
            y1 - y0,
            largest=largest,
            smallest=26,
            max_lines=max_lines,
        )
        return _set(d, face, lines, box, ink, align=align, valign=valign)

    def author_in(box, *, align, valign, fill=ink):
        if not author:
            return
        x0, y0, x1, y1 = box
        face, lines = _fit(
            face_name, author_weight, author, x1 - x0, y1 - y0, largest=30, smallest=18, max_lines=2
        )
        _set(d, face, lines, box, fill, align=align, valign=valign)

    if layout == "band":
        split = int(_H * 0.56)
        d.rectangle((0, 0, _W, split), fill=accent)
        _motif(image, motif, (0, 0, _W, split), bg, _mix(bg, accent, 0.55), rng)
        d.rectangle((0, split, _W, split + 6 * _SCALE), fill=ink)
        bottom = _H - m
        if author:
            author_in((m, bottom - 80 * _SCALE, _W - m, bottom), align="left", valign="bottom")
            bottom -= 96 * _SCALE
        title_in(
            (m, split + 40 * _SCALE, _W - m, bottom),
            largest=80,
            align="left",
            valign="top",
            max_lines=5,
        )
    elif layout == "frame":
        inset = 26 * _SCALE
        d.rectangle((inset, inset, _W - inset, _H - inset), outline=accent, width=8 * _SCALE)
        d.rectangle(
            (
                inset + 16 * _SCALE,
                inset + 16 * _SCALE,
                _W - inset - 16 * _SCALE,
                _H - inset - 16 * _SCALE,
            ),
            outline=soft,
            width=2 * _SCALE,
        )
        _motif(
            image,
            motif,
            (_W / 2 - 90 * _SCALE, 96 * _SCALE, _W / 2 + 90 * _SCALE, 276 * _SCALE),
            accent,
            soft,
            rng,
        )
        inner = m + 30 * _SCALE
        title_box = (inner, 310 * _SCALE, _W - inner, (700 if author else 780) * _SCALE)
        title_in(title_box, largest=64, align="center", valign="middle")
        if author:
            d.line(
                [(_W / 2 - 40 * _SCALE, 722 * _SCALE), (_W / 2 + 40 * _SCALE, 722 * _SCALE)],
                fill=accent,
                width=3 * _SCALE,
            )
            author_in((inner, 740 * _SCALE, _W - inner, 810 * _SCALE), align="center", valign="top")
    elif layout == "stack":
        _motif(image, motif, (0, _H * 0.55, _W, _H), accent, soft, rng)
        y = title_in(
            (m, m + 10 * _SCALE, _W - m, 400 * _SCALE),
            largest=84,
            align="left",
            valign="top",
            max_lines=5,
        )
        d.rectangle((m, y + 18 * _SCALE, m + 70 * _SCALE, y + 24 * _SCALE), fill=accent)
        author_in((m, y + 40 * _SCALE, _W - m, y + 110 * _SCALE), align="left", valign="top")
    else:  # classic
        title_bottom = 400 * _SCALE
        title_in(
            (m, 84 * _SCALE, _W - m, title_bottom), largest=72, align="center", valign="bottom"
        )
        d.line(
            [(_W / 2 - 50 * _SCALE, 430 * _SCALE), (_W / 2 + 50 * _SCALE, 430 * _SCALE)],
            fill=accent,
            width=3 * _SCALE,
        )
        motif_top = 470 * _SCALE
        motif_bottom = (740 if author else 820) * _SCALE
        size = motif_bottom - motif_top
        _motif(
            image,
            motif,
            (_W / 2 - size / 2, motif_top, _W / 2 + size / 2, motif_bottom),
            accent,
            soft,
            rng,
        )
        author_in((m, 770 * _SCALE, _W - m, 836 * _SCALE), align="center", valign="middle")

    return image.resize((WIDTH, HEIGHT), Image.Resampling.LANCZOS)


def cover_png(design: dict, title: str, authors: list[str]) -> bytes:
    out = io.BytesIO()
    draw_cover(design, title, authors).save(out, format="PNG", optimize=True)
    return out.getvalue()


# Where the title band of a painted cover starts and how far above it the
# band fades into the artwork, as shares of the height.
_BAND_TOP = 0.58
_FEATHER = 0.07


def draw_painted_cover(
    design: dict, title: str, authors: list[str], art: Image.Image
) -> Image.Image:
    """A cover on painted artwork: the artwork fills it and a band in the
    design's background colour carries the title and author. The band is
    solid wherever there is text, so the type keeps the contrast the design
    guarantees whatever the artwork looks like; above that it fades in."""
    seed = int.from_bytes(hashlib.sha256(f"{title}|{authors}".encode()).digest()[:8], "big")
    bg, accent, ink, _ = _colours(design, seed)
    face_name, title_weight, author_weight = _FACES.get(design.get("typeface"), _FACES["serif"])
    title = _capped(title, _TITLE_CAP)
    author = _capped(_author_line(authors), _AUTHOR_CAP)

    image = art.convert("RGB").resize((_W, _H), Image.Resampling.LANCZOS)
    top, feather = int(_H * _BAND_TOP), int(_H * _FEATHER)
    band = Image.new("RGB", (_W, _H - top + feather), bg)
    fade = Image.new("L", band.size, 255)
    f = ImageDraw.Draw(fade)
    for y in range(feather):
        f.line([(0, y), (_W, y)], fill=round(255 * y / feather))
    image.paste(band, (0, top - feather), fade)

    d = ImageDraw.Draw(image)
    m = 64 * _SCALE
    d.rectangle((m, top + 24 * _SCALE, m + 70 * _SCALE, top + 30 * _SCALE), fill=accent)
    bottom = _H - m
    if author:
        face, lines = _fit(
            face_name,
            author_weight,
            author,
            _W - 2 * m,
            80 * _SCALE,
            largest=30,
            smallest=18,
            max_lines=2,
        )
        _set(
            d,
            face,
            lines,
            (m, bottom - 80 * _SCALE, _W - m, bottom),
            ink,
            align="left",
            valign="bottom",
        )
        bottom -= 96 * _SCALE
    box = (m, top + 56 * _SCALE, _W - m, bottom)
    face, lines = _fit(
        face_name,
        title_weight,
        title,
        box[2] - box[0],
        box[3] - box[1],
        largest=72,
        smallest=26,
        max_lines=4,
    )
    _set(d, face, lines, box, ink, align="left", valign="top")
    return image.resize((WIDTH, HEIGHT), Image.Resampling.LANCZOS)


def painted_cover_jpeg(design: dict, title: str, authors: list[str], art: bytes) -> bytes:
    """``draw_painted_cover`` on stored artwork, as JPEG: a painting
    compresses far better that way than as PNG."""
    with Image.open(io.BytesIO(art)) as picture:
        cover = draw_painted_cover(design, title, authors, picture)
    out = io.BytesIO()
    cover.save(out, format="JPEG", quality=88, optimize=True, progressive=True)
    return out.getvalue()
