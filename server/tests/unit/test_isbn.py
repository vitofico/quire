from quire_server.core.isbn import to_isbn13


def test_isbn13_passthrough():
    assert to_isbn13("9780261103573") == "9780261103573"


def test_isbn13_strips_hyphens_and_spaces():
    assert to_isbn13("978-0-261-10357-3") == "9780261103573"
    assert to_isbn13("  9780261103573 ") == "9780261103573"


def test_isbn10_converts_to_isbn13():
    assert to_isbn13("0261103571") == "9780261103573"


def test_isbn10_with_x_check_digit():
    assert to_isbn13("080442957X") == "9780804429573"


def test_invalid_checksum_returns_none():
    assert to_isbn13("9780261103574") is None
    assert to_isbn13("0261103572") is None


def test_garbage_returns_none():
    assert to_isbn13("") is None
    assert to_isbn13("hello") is None
    assert to_isbn13("12345") is None
