"""Builders for small EPUBs and published jobs used by the delivery tests."""

import json
import zipfile
from pathlib import Path

from quire_convert.job import Job
from quire_convert.models import Outcome


def make_epub(path: Path, rendition_id: str, title: str = "The Bridge", extra_ids=()) -> Path:
    """A minimal valid EPUB 3 whose first dc:identifier is ``rendition_id``."""
    ids = "".join(f"<dc:identifier>{i}</dc:identifier>" for i in extra_ids)
    opf = f"""<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="qc-id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="qc-id">{rendition_id}</dc:identifier>{ids}
    <dc:title>{title}</dc:title>
    <dc:creator>A. Writer</dc:creator>
    <dc:language>en</dc:language>
    <meta property="dcterms:modified">2020-01-01T00:00:00Z</meta>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine><itemref idref="c1"/></spine>
</package>"""
    container = """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>"""
    page = (
        '<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml" '
        'xmlns:epub="http://www.idpf.org/2007/ops"><head><title>{0}</title></head>'
        "<body>{1}</body></html>"
    )
    nav = page.format(
        "Contents", '<nav epub:type="toc"><ol><li><a href="c1.xhtml">One</a></li></ol></nav>'
    )
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w") as zf:
        zf.writestr("mimetype", "application/epub+zip", compress_type=zipfile.ZIP_STORED)
        zf.writestr("META-INF/container.xml", container)
        zf.writestr("OEBPS/content.opf", opf)
        zf.writestr("OEBPS/nav.xhtml", nav)
        zf.writestr("OEBPS/c1.xhtml", page.format("One", "<h1>One</h1><p>Text.</p>"))
    return path


def make_job(root: Path, rendition_id: str, outcome=Outcome.READY, **epub_kwargs) -> Job:
    """A published job whose attempt holds a small EPUB."""
    job = Job.open_or_create(root, job_id=rendition_id.rsplit(":", 1)[-1][:16])
    attempt = job.new_attempt()
    artifacts = {"report": attempt.path("report.json")}
    attempt.path("report.json").write_text(json.dumps({"outcome": outcome.value}))
    if outcome in (Outcome.READY, Outcome.REVIEW):
        artifacts["epub"] = make_epub(attempt.path("book.epub"), rendition_id, **epub_kwargs)
    job.publish(attempt, outcome, artifacts, {"outcome": outcome.value})
    return job
