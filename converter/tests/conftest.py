from pathlib import Path

import pytest
from fixtures.build import build_all


@pytest.fixture(scope="session")
def fixture_pdfs(tmp_path_factory: pytest.TempPathFactory) -> dict[str, tuple[Path, dict]]:
    return build_all(tmp_path_factory.mktemp("fixture-pdfs"))
