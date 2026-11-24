import pytest
from deliver_support import make_job


@pytest.fixture
def job(tmp_path):
    return make_job(tmp_path / "jobs", "urn:uuid:11111111-2222-5333-8444-555555555555")
