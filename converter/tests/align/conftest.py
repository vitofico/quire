import shutil

import pytest

from quire_convert.epub.calibre import run_calibre
from quire_convert.source.preflight import inspect_pdf
from quire_convert.tools import find_tools


@pytest.fixture(scope="session")
def calibre_fixture(fixture_pdfs, tmp_path_factory):
    """``get(name) -> (inventory, calibre epub path)`` for a fixture PDF, built once."""
    tools = find_tools()
    if tools.ebook_convert is None or shutil.which("pdftotext") is None:
        pytest.skip("calibre or poppler not installed")
    cache = {}

    def get(name: str):
        if name not in cache:
            pdf, _labels = fixture_pdfs[name]
            work = tmp_path_factory.mktemp(f"align-{name}")
            inv = inspect_pdf(pdf, work / "source", toolchain=tools)
            run = run_calibre(pdf, work / "calibre.epub", work / "calibre", tools)
            cache[name] = (inv, run.epub_path)
        return cache[name]

    return get
