import pytest
from pydantic import ValidationError

from quire_server.api.library_schemas import LibraryItemRequest, LibrarySyncEntry

CATALOG = "3F2B9C1E-8D4A-4F6B-9A7E-2C5D1E0B4A93"


@pytest.mark.parametrize(
    ("model", "base"),
    [
        (LibraryItemRequest, {"content_hash": "ch", "title": "T"}),
        (LibrarySyncEntry, {"identity_hash": "ch", "title": "T"}),
    ],
)
def test_catalog_id_optional_canonical_and_validated(model, base):
    assert model.model_validate(base).catalog_id is None
    assert model.model_validate({**base, "catalog_id": None}).catalog_id is None
    assert model.model_validate({**base, "catalog_id": CATALOG}).catalog_id == CATALOG.lower()
    for bad in ("not-a-uuid", "", "a" * 65):
        with pytest.raises(ValidationError):
            model.model_validate({**base, "catalog_id": bad})
