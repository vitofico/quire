import zipfile

from lxml import etree

from quire_convert.epub.book import Book, ContentDoc
from quire_convert.epub.validate import semantic_check

MODIFIED = "2025-02-03T04:05:06Z"
CH1 = "OEBPS/Text/ch1.xhtml"
CH2 = "OEBPS/Text/ch2.xhtml"


def test_load_reads_structure_and_metadata(sample_epub):
    book = Book.load(sample_epub)
    assert [d.href for d in book.docs] == [CH1, CH2]
    assert [d.id for d in book.docs] == ["ch1", "ch2"]
    assert set(book.assets) == {"OEBPS/Styles/style.css", "OEBPS/Images/fig.png"}
    meta = book.metadata
    assert meta.title == "The River"
    assert meta.subtitle == "A Story"
    assert meta.authors == ["Mara Rossi"]  # the editor is not an author
    assert meta.author_sort == "Rossi, Mara"
    assert meta.identifiers[0] == "urn:uuid:00000000-0000-4000-8000-000000000001"
    assert meta.identifiers[1:] == ["urn:isbn:9780000000002"]
    assert (meta.language, meta.publisher, meta.date) == ("en", "Small Press", "2024")
    assert (meta.series, meta.series_index, meta.subjects) == ("Waters", "2", ["Rivers"])
    assert meta.cover_href == "OEBPS/Images/fig.png"
    assert [(e.title, e.href, e.level) for e in book.toc] == [
        ("Chapter One", f"{CH1}#c1", 1),
        ("Chapter Two", CH2, 1),
    ]
    assert [(e.title, e.href, e.level) for e in book.toc[0].children] == [
        ("Section Two", f"{CH1}#s2", 2)
    ]
    assert book.page_list == [("1", f"{CH1}#c1"), ("2", f"{CH1}#s2")]
    assert [(m.type, m.href) for m in book.landmarks] == [("bodymatter", f"{CH1}#c1")]


def test_round_trip_keeps_model(sample_epub, tmp_path):
    book = Book.load(sample_epub)
    book.save(tmp_path / "a.epub", modified=MODIFIED)
    again = Book.load(tmp_path / "a.epub")
    assert again.metadata == book.metadata
    assert again.toc == book.toc
    assert again.page_list == book.page_list
    assert again.landmarks == book.landmarks
    assert [d.href for d in again.docs] == [d.href for d in book.docs]
    assert {h: a.data for h, a in again.assets.items()} == {
        h: a.data for h, a in book.assets.items()
    }


def test_save_is_byte_identical_and_well_ordered(sample_epub, tmp_path):
    book = Book.load(sample_epub)
    book.save(tmp_path / "a.epub", modified=MODIFIED)
    book.save(tmp_path / "b.epub", modified=MODIFIED)
    Book.load(tmp_path / "a.epub").save(tmp_path / "c.epub", modified=MODIFIED)
    first = (tmp_path / "a.epub").read_bytes()
    assert first == (tmp_path / "b.epub").read_bytes() == (tmp_path / "c.epub").read_bytes()
    with zipfile.ZipFile(tmp_path / "a.epub") as zf:
        infos = zf.infolist()
        assert infos[0].filename == "mimetype"
        assert infos[0].compress_type == zipfile.ZIP_STORED
        assert [i.filename for i in infos[1:]] == [
            "META-INF/container.xml",
            "OEBPS/content.opf",
            "OEBPS/nav.xhtml",
            "OEBPS/toc.ncx",
            CH1,
            CH2,
            "OEBPS/Images/fig.png",
            "OEBPS/Styles/style.css",
        ]
        assert {i.date_time for i in infos} == {(1980, 1, 1, 0, 0, 0)}
        opf = zf.read("OEBPS/content.opf").decode()
    assert 'unique-identifier="qc-id"' in opf
    assert MODIFIED in opf
    assert 'properties="cover-image"' in opf


def test_active_content_is_stripped(sample_epub, tmp_path):
    book = Book.load(sample_epub)
    book.save(tmp_path / "a.epub", modified=MODIFIED)
    assert len(book.stripped) == 4  # script, onclick, remote img, remote CSS import
    assert not any("example.com" in note for note in book.stripped)
    with zipfile.ZipFile(tmp_path / "a.epub") as zf:
        ch2 = zf.read(CH2).decode()
        css = zf.read("OEBPS/Styles/style.css").decode()
    assert "<script" not in ch2 and "onclick" not in ch2 and "example.com" not in ch2
    assert "Tail stays." in ch2
    assert "@import" not in css and "margin: 0" in css


def test_ensure_block_ids_is_deterministic_and_idempotent(sample_epub):
    book = Book.load(sample_epub)
    blocks = book.ensure_block_ids()
    snapshot = [etree.tostring(d.tree) for d in book.docs]
    assert book.ensure_block_ids() == blocks
    assert [etree.tostring(d.tree) for d in book.docs] == snapshot
    assert Book.load(sample_epub).ensure_block_ids() == blocks
    by_tag = [(b.tag, b.id) for b in blocks]
    assert by_tag[0] == ("h1", "c1")  # existing ids are kept
    assert by_tag[1] == ("p", "qc-b00000")
    texts = {b.text for b in blocks}
    assert "First paragraph1 of the river." in texts
    assert "Loose div text" in texts
    figure = next(b for b in blocks if b.tag == "figure")
    assert figure.text == ""  # the caption text belongs to the figcaption
    assert any(b.tag == "figcaption" and b.text == "Figure 1. Bridge." for b in blocks)
    assert not any(b.tag == "img" for b in blocks)  # inside a figure, so not standalone
    assert book.element(f"{CH1}#{blocks[1].id}")[1].tag.endswith("p")


def test_split_doc_moves_content_and_rewrites_links(sample_epub, tmp_path):
    book = Book.load(sample_epub)
    new_href = book.split_doc(CH1, "s2")
    assert new_href == "OEBPS/Text/ch1-qc1.xhtml"
    assert [d.href for d in book.docs] == [CH1, new_href, CH2]
    old, new = book.doc(CH1), book.doc(new_href)
    old_text = "".join(old.body.itertext())
    new_text = "".join(new.body.itertext())
    assert "Second paragraph." in old_text and "Section Two" not in old_text
    assert "Section Two" in new_text and "Loose div text" in new_text
    wrapper = new.body[0]
    assert wrapper.get("class") == "wrap" and wrapper[0].get("id") == "s2"
    # every way to reach a moved id now names the new document
    assert book.toc[0].children[0].href == f"{new_href}#s2"
    assert book.page_list[1] == ("2", f"{new_href}#s2")
    targets = {(d.href, t) for d, _, a, t in book.iter_links() if a == "href"}
    assert (CH2, f"{new_href}#s2") in targets
    assert (CH1, f"{new_href}#n1") in targets  # noteref stayed, its note moved
    assert (new_href, f"{CH1}#r1") in targets  # backlink moved, its ref stayed
    assert (new_href, f"{CH2}#target") in targets
    book.save(tmp_path / "split.epub", modified=MODIFIED)
    assert semantic_check(tmp_path / "split.epub") == []


def test_split_doc_before_first_element_is_a_no_op(sample_epub):
    book = Book.load(sample_epub)
    assert book.split_doc(CH1, "c1") == CH1
    assert len(book.docs) == 2


def test_rewrite_links_and_assets(sample_epub):
    book = Book.load(sample_epub)
    assert book.rewrite_links({f"{CH2}#target": f"{CH1}#c1"}) == 1
    _, el = book.element(f"{CH1}#s2")
    link = el.getnext().find("{http://www.w3.org/1999/xhtml}a")
    assert link.get("href") == "#c1"
    book.add_asset("OEBPS/Images/new.png", b"x", "image/png")
    book.set_cover("OEBPS/Images/new.png")
    assert book.metadata.cover_href == "OEBPS/Images/new.png"
    assert "cover-image" not in book.assets["OEBPS/Images/fig.png"].properties
    book.remove_asset("OEBPS/Images/new.png")
    assert book.metadata.cover_href is None
    assert isinstance(book.doc(CH2), ContentDoc)
