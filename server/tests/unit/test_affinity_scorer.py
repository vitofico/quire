from quire_server.core.affinity import normalize_author, normalize_series, normalize_subject


def test_normalize_subject_drops_generic_and_aliases():
    assert normalize_subject("Fiction") is None
    assert normalize_subject("Science Fiction") == "science fiction"
    assert normalize_subject("Sci-Fi") == "science fiction"
    assert normalize_subject("Detective and mystery stories") == "mystery"
    assert normalize_subject("  Historical Fiction (Genre) ") == "historical fiction"


def test_normalize_author_handles_last_first_and_diacritics():
    assert normalize_author("Le Guin, Ursula K.") == "ursula k. le guin"
    assert normalize_author("Gabriel García Márquez") == "gabriel garcia marquez"


def test_normalize_series():
    assert normalize_series("The Expanse ") == "the expanse"
