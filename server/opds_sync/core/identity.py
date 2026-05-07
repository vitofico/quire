import re

_SCHEMES = ("isbn", "uuid", "calibre", "mobi-asin", "asin", "doi", "url")
# Order matters: longer/compound schemes (e.g. "mobi-asin") must precede their prefixes ("asin")
# since the regex breaks on first alternative match. Mirror the Kotlin ordering.
_SCHEME_PREFIX = re.compile(rf"^({'|'.join(_SCHEMES)})[:\s]+")
_WHITESPACE_AND_HYPHEN = re.compile(r"[\s-]")


def normalize_metadata_id(raw: str | None) -> str | None:
    if raw is None:
        return None
    s = raw.strip().lower()
    if not s:
        return None
    if s.startswith("urn:"):
        s = s[len("urn:") :]
    s = _SCHEME_PREFIX.sub("", s, count=1)
    s = _WHITESPACE_AND_HYPHEN.sub("", s)
    return s or None
