"""State threaded through the convert pipeline and handed to every repair."""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import TYPE_CHECKING, Any

from .models import Issue, MetadataField, OutputBlock, Repair, Severity, SourceInventory

if TYPE_CHECKING:
    from .epub.book import Book
    from .tools import Toolchain


@dataclass
class ConvertContext:
    pdf_path: Path
    workdir: Path  # private, job-scoped scratch space
    source: SourceInventory
    book: Book
    toolchain: Toolchain
    variant: str = "rules"  # "baseline" (Calibre only), "rules", "assisted"
    settings: dict[str, Any] = field(default_factory=dict)
    catalog_metadata: dict[str, Any] | None = None  # highest-trust metadata, if supplied
    blocks: list[OutputBlock] = field(default_factory=list)  # aligned output blocks
    issues: list[Issue] = field(default_factory=list)
    repairs: list[Repair] = field(default_factory=list)
    metadata: list[MetadataField] = field(default_factory=list)
    diagnostics: bool = False  # True only for local review runs: may keep book text

    def issue(self, code: str, severity: Severity, message: str, **kw: Any) -> Issue:
        item = Issue(code=code, severity=severity, message=message, **kw)
        self.issues.append(item)
        return item

    def record(self, family: str, action: str, target: str, evidence: list[str], **kw: Any):
        item = Repair(family=family, action=action, target=target, evidence=evidence, **kw)
        self.repairs.append(item)
        return item
