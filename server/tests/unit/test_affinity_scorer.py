from quire_server.core.affinity import (
    AFFINITY_VERSION,
    normalize_author,
    normalize_series,
    normalize_subject,
    score_affinity,
)


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


def _lib(title, authors, subjects, status, series=None, language="en"):
    return {"authors": authors, "subjects": subjects, "series_name": series,
            "language": language, "status": status}


def test_cold_start_returns_unknown():
    res = score_affinity(
        scanned={
            "authors": ["Jane Doe"], "subjects": ["mystery"],
            "series_name": None, "language": "en"},
        library=[_lib("A", ["Jane Doe"], ["mystery"], "finished")],
    )
    assert res.band == "unknown"
    assert res.score is None
    assert any(r.kind == "coldstart" for r in res.reasons)


def test_strong_author_and_theme_match():
    lib = [_lib(f"b{i}", ["Jane Doe"], ["mystery"], "finished") for i in range(4)]
    res = score_affinity(
        scanned={
            "authors": ["Jane Doe"], "subjects": ["mystery"],
            "series_name": None, "language": "en"},
        library=lib,
    )
    assert res.band in ("strong", "moderate")
    assert res.score is not None and res.score >= 60
    assert any(r.kind == "author" and r.polarity == "positive" for r in res.reasons)


def test_abandoned_theme_is_negative():
    lib = [_lib(f"b{i}", ["X"], ["horror"], "abandoned") for i in range(3)] + \
          [_lib(f"f{i}", ["Y"], ["mystery"], "finished") for i in range(3)]
    res = score_affinity(
        scanned={"authors": ["Z"], "subjects": ["horror"], "series_name": None, "language": "en"},
        library=lib,
    )
    assert any(r.kind == "theme" and r.polarity == "negative" for r in res.reasons)


def test_version_present():
    res = score_affinity(
        scanned={"authors": [], "subjects": [], "series_name": None, "language": "en"}, library=[])
    assert res.version == AFFINITY_VERSION
    assert res.band == "unknown"


def test_confidence_guard_caps_score_and_band_consistently():
    # Single signal family (author only) would raw to 50+25=75 ("strong");
    # the guard must cap BOTH score and band so they never disagree.
    lib = [_lib(f"b{i}", ["Jane Doe"], ["cooking"], "finished") for i in range(4)]
    res = score_affinity(
        scanned={"authors": ["Jane Doe"], "subjects": ["astronomy"],
                 "series_name": None, "language": None},
        library=lib,
    )
    assert res.score is not None and res.score <= 74
    assert res.band == "moderate"


def test_zero_affinity_theme_emits_no_reason():
    # A theme with an exact 50/50 finish/abandon split contributes 0 points
    # and must NOT surface as a positive reason.
    lib = ([_lib(f"m{i}", ["A"], ["mystery"], "finished") for i in range(3)]
           + [_lib("n1", ["B"], ["noir"], "finished"),
              _lib("n2", ["C"], ["noir"], "abandoned")])
    res = score_affinity(
        scanned={"authors": ["Nobody"], "subjects": ["noir"],
                 "series_name": None, "language": None},
        library=lib,
    )
    assert not any(r.kind == "theme" for r in res.reasons)
