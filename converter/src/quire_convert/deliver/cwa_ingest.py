"""Calibre-Web Automated ingest-folder adapter, for new books only.

The EPUB is staged outside the watched folder on the same filesystem, checked against its
checksum, given the configured ownership, and renamed into the folder in one step, so the
ingest service never sees a partial file. The handoff makes the delivery Submitted; only a
catalog readback that finds the rendition makes it Saved. A file leaving the folder proves
nothing: the ingest service also removes files it failed to import.
"""

from __future__ import annotations

import logging
import os
import shutil
from dataclasses import dataclass
from pathlib import Path

from ..job import fsync_path, sha256_file
from . import Deliverable, DeliveryError
from .calibre_web import CalibreWeb, CatalogError, NewBookDestination, upload_name

log = logging.getLogger(__name__)


@dataclass(frozen=True)
class IngestFolder:
    folder: Path
    staging: Path | None = None  # default: <folder>/../.quire-staging
    owner: tuple[int, int] | None = None  # uid, gid the ingest service expects
    mode: int | None = None

    @property
    def staging_dir(self) -> Path:
        return self.staging or self.folder.parent / ".quire-staging"


def handoff(deliverable: Deliverable, target: IngestFolder) -> Path:
    """Place the complete, verified EPUB in the ingest folder atomically. Returns its path."""
    folder, staging = target.folder, target.staging_dir
    if not folder.is_dir():
        raise DeliveryError(f"the ingest folder {folder} does not exist")
    if staging.resolve().is_relative_to(folder.resolve()):
        raise DeliveryError("the staging folder must be outside the watched ingest folder")
    staging.mkdir(parents=True, exist_ok=True)
    if staging.stat().st_dev != folder.stat().st_dev:
        raise DeliveryError("the staging folder must be on the ingest folder's filesystem")
    name = upload_name(deliverable)
    staged = staging / f".{name}.part"
    try:
        shutil.copyfile(deliverable.epub, staged)
        fsync_path(staged)
        if sha256_file(staged) != deliverable.sha256:
            raise DeliveryError("the staged copy does not match the EPUB checksum")
        if target.mode is not None:
            os.chmod(staged, target.mode)
        if target.owner is not None:
            os.chown(staged, *target.owner)
        final = folder / name
        os.rename(staged, final)
    except BaseException:
        staged.unlink(missing_ok=True)
        raise
    try:
        fsync_path(folder)
    except OSError:  # the file is in place; only its durability is unconfirmed
        log.warning("could not flush the ingest folder after the handoff")
    return final


class IngestDestination(NewBookDestination):
    """A new book submitted through the ingest folder and confirmed through the web UI."""

    def __init__(self, client: CalibreWeb, deliverable: Deliverable, target: IngestFolder):
        super().__init__(client, deliverable)
        self.target = target

    def write(self) -> None:
        try:
            handoff(self.deliverable, self.target)
        except (DeliveryError, OSError) as exc:  # raised before or by the rename: no handoff
            raise CatalogError(f"the ingest handoff failed: {exc}") from exc
