"""Deliver a verified EPUB from a job to a Calibre-Web catalog.

Only the EPUB named by the job manifest is sent, and only after its bytes match the
manifest checksum. A book is identified on the catalog by its rendition id: the EPUB's
first ``dc:identifier``, which the converter sets to a stable ``urn:uuid``.
"""

from __future__ import annotations

import json
import zipfile
from dataclasses import dataclass
from pathlib import Path

from lxml import etree

from ..job import MANIFEST, sha256_file
from ..models import Outcome

_OPF_NS = "http://www.idpf.org/2007/opf"
_DC_NS = "http://purl.org/dc/elements/1.1/"
_CONTAINER_NS = "urn:oasis:names:tc:opendocument:xmlns:container"
_XML_LIMIT = 8 << 20
_PARSER = etree.XMLParser(resolve_entities=False, no_network=True, load_dtd=False)


class DeliveryError(ValueError):
    """The job cannot be delivered as asked (no EPUB, wrong outcome, checksum mismatch)."""


@dataclass(frozen=True)
class EpubIdentity:
    identifiers: tuple[str, ...]  # every dc:identifier, document order
    title: str | None

    @property
    def rendition_id(self) -> str | None:
        return self.identifiers[0] if self.identifiers else None


@dataclass(frozen=True)
class Deliverable:
    epub: Path
    sha256: str
    rendition_id: str
    title: str | None
    job_dir: Path

    def matches(self, identity: EpubIdentity | None, sha256: str | None) -> bool:
        """Is a catalog EPUB this rendition? Catalogs may rewrite the OPF (Calibre adds its
        own identifiers), so the rendition id surviving anywhere counts, as do equal bytes."""
        if sha256 is not None and sha256 == self.sha256:
            return True
        return identity is not None and self.rendition_id in identity.identifiers


def _read_member(zf: zipfile.ZipFile, name: str) -> bytes:
    info = zf.getinfo(name)
    if info.file_size > _XML_LIMIT:
        raise DeliveryError(f"{name} is too large to read")
    return zf.read(info)


def epub_identity(path: Path) -> EpubIdentity:
    """Identifiers and title from an EPUB's package document. Raises DeliveryError when the
    file is not a readable EPUB."""
    try:
        with zipfile.ZipFile(path) as zf:
            container = etree.fromstring(_read_member(zf, "META-INF/container.xml"), _PARSER)
            rootfile = container.find(f".//{{{_CONTAINER_NS}}}rootfile")
            if rootfile is None or not rootfile.get("full-path"):
                raise DeliveryError("the EPUB has no package document")
            opf = etree.fromstring(_read_member(zf, rootfile.get("full-path")), _PARSER)
    except (OSError, KeyError, zipfile.BadZipFile, etree.XMLSyntaxError) as exc:
        raise DeliveryError(f"not a readable EPUB ({type(exc).__name__})") from exc
    metadata = opf.find(f"{{{_OPF_NS}}}metadata")
    if metadata is None:
        return EpubIdentity((), None)
    identifiers = tuple(
        e.text.strip() for e in metadata.iter(f"{{{_DC_NS}}}identifier") if (e.text or "").strip()
    )
    titles = [e.text.strip() for e in metadata.iter(f"{{{_DC_NS}}}title") if (e.text or "").strip()]
    return EpubIdentity(identifiers, titles[0] if titles else None)


def load_deliverable(path: Path, *, allow_review: bool = False) -> Deliverable:
    """The verified EPUB of a job, given its directory or its manifest.json."""
    path = Path(path)
    manifest_path = path / MANIFEST if path.is_dir() else path
    try:
        manifest = json.loads(manifest_path.read_text())
    except (OSError, ValueError) as exc:
        raise DeliveryError(f"cannot read job manifest {manifest_path}") from exc
    outcome = manifest.get("outcome")
    allowed = {Outcome.READY.value} | ({Outcome.REVIEW.value} if allow_review else set())
    if outcome not in allowed:
        hint = " (pass --allow-review to deliver it anyway)" if outcome == "review" else ""
        raise DeliveryError(f"the job outcome is {outcome}, not ready{hint}")
    entry = manifest.get("artifacts", {}).get("epub")
    if not entry:
        raise DeliveryError("the job manifest names no EPUB")
    job_dir = manifest_path.parent
    epub = job_dir / entry["path"]
    if not epub.resolve().is_relative_to(job_dir.resolve()):
        raise DeliveryError("the manifest's EPUB path leaves the job directory")
    if not epub.is_file() or sha256_file(epub) != entry["sha256"]:
        raise DeliveryError("the EPUB does not match its manifest checksum")
    identity = epub_identity(epub)
    if identity.rendition_id is None:
        raise DeliveryError("the EPUB has no dc:identifier to recognise it by")
    return Deliverable(epub, entry["sha256"], identity.rendition_id, identity.title, job_dir)
