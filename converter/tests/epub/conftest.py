import io
import zipfile
from pathlib import Path

import pytest
from PIL import Image, ImageDraw

from quire_convert.tools import find_tools

OPF = """<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="other">urn:isbn:9780000000002</dc:identifier>
    <dc:identifier id="bookid">urn:uuid:00000000-0000-4000-8000-000000000001</dc:identifier>
    <dc:title id="t1">The River</dc:title>
    <dc:title id="t2">A Story</dc:title>
    <meta refines="#t1" property="title-type">main</meta>
    <meta refines="#t2" property="title-type">subtitle</meta>
    <dc:creator id="c1">Mara Rossi</dc:creator>
    <meta refines="#c1" property="role" scheme="marc:relators">aut</meta>
    <meta refines="#c1" property="file-as">Rossi, Mara</meta>
    <dc:creator id="c2">Some Editor</dc:creator>
    <meta refines="#c2" property="role" scheme="marc:relators">edt</meta>
    <dc:language>en</dc:language>
    <dc:publisher>Small Press</dc:publisher>
    <dc:date>2024</dc:date>
    <dc:subject>Rivers</dc:subject>
    <meta name="calibre:series" content="Waters"/>
    <meta name="calibre:series_index" content="2"/>
    <meta property="dcterms:modified">2024-01-01T00:00:00Z</meta>
  </metadata>
  <manifest>
    <item id="ch1" href="Text/ch1.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch2" href="Text/ch2.xhtml" media-type="application/xhtml+xml"/>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="css" href="Styles/style.css" media-type="text/css"/>
    <item id="fig" href="Images/fig.png" media-type="image/png" properties="cover-image"/>
  </manifest>
  <spine>
    <itemref idref="ch1"/>
    <itemref idref="ch2"/>
  </spine>
</package>
"""

HEAD = """<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"
  lang="en" xml:lang="en">
<head><title>{title}</title>
<link rel="stylesheet" type="text/css" href="../Styles/style.css"/></head>
"""

CH1 = (
    HEAD.format(title="One")
    + """<body>
<h1 id="c1">Chapter One</h1>
<p>First paragraph<a epub:type="noteref" id="r1" href="#n1">1</a> of the river.</p>
<div class="wrap">
  <p>Second paragraph.</p>
  <h2 id="s2">Section Two</h2>
  <p>Third paragraph, see <a href="ch2.xhtml#target">the target</a>.</p>
</div>
<figure><img src="../Images/fig.png" alt="A bridge"/>
<figcaption>Figure 1. Bridge.</figcaption></figure>
<aside epub:type="footnote" id="n1"><p>The note. <a href="#r1">Back</a></p></aside>
<div class="plain">Loose div text</div>
</body>
</html>
"""
)

CH2 = (
    HEAD.format(title="Two")
    + """<body>
<h1>Chapter Two</h1>
<p id="target">Target paragraph, back to <a href="ch1.xhtml#s2">section two</a>.</p>
<script>alert(1)</script>
<p onclick="steal()">Clickable</p>
<p><img src="https://example.com/track.png" alt="tracker"/>Tail stays.</p>
</body>
</html>
"""
)

NAV = """<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><title>Nav</title></head>
<body>
<nav epub:type="toc"><ol>
  <li><a href="Text/ch1.xhtml#c1">Chapter One</a>
    <ol><li><a href="Text/ch1.xhtml#s2">Section Two</a></li></ol></li>
  <li><a href="Text/ch2.xhtml">Chapter Two</a></li>
</ol></nav>
<nav epub:type="page-list" hidden=""><ol>
  <li><a href="Text/ch1.xhtml#c1">1</a></li>
  <li><a href="Text/ch1.xhtml#s2">2</a></li>
</ol></nav>
<nav epub:type="landmarks" hidden=""><ol>
  <li><a epub:type="bodymatter" href="Text/ch1.xhtml#c1">Start</a></li>
</ol></nav>
</body>
</html>
"""

CONTAINER = """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>
"""


def png(blank: bool = False) -> bytes:
    img = Image.new("RGB", (120, 80), "white")
    if not blank:
        ImageDraw.Draw(img).rectangle((20, 20, 100, 60), outline="black", width=3)
    out = io.BytesIO()
    img.save(out, format="PNG")
    return out.getvalue()


def write_epub(path: Path, files: dict[str, bytes | str]) -> Path:
    with zipfile.ZipFile(path, "w") as zf:
        zf.writestr("mimetype", "application/epub+zip", compress_type=zipfile.ZIP_STORED)
        for name, data in files.items():
            zf.writestr(name, data, compress_type=zipfile.ZIP_DEFLATED)
    return path


@pytest.fixture
def sample_epub(tmp_path: Path) -> Path:
    return write_epub(
        tmp_path / "sample.epub",
        {
            "META-INF/container.xml": CONTAINER,
            "OEBPS/content.opf": OPF,
            "OEBPS/nav.xhtml": NAV,
            "OEBPS/Text/ch1.xhtml": CH1,
            "OEBPS/Text/ch2.xhtml": CH2,
            "OEBPS/Styles/style.css": "@import url(https://example.com/x.css);\np { margin: 0 }\n",
            "OEBPS/Images/fig.png": png(),
        },
    )


@pytest.fixture
def make_png():
    return png


@pytest.fixture(scope="session")
def toolchain():
    return find_tools()
