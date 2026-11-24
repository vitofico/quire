import pytest
from lxml import etree

from quire_convert.epub.book import _own_text
from quire_convert.epub.inline import insert_at, locate, merge, split_at, text_of

NS = "http://www.w3.org/1999/xhtml"
EPUB = "http://www.idpf.org/2007/ops"


def body(inner: str) -> etree._Element:
    root = etree.fromstring(
        f'<html xmlns="{NS}" xmlns:epub="{EPUB}"><body>{inner}</body></html>'.encode()
    )
    return root[0]


def p_of(inner: str) -> etree._Element:
    return body(f"<p>{inner}</p>")[0]


def xml(el: etree._Element) -> str:
    out = etree.tostring(el, encoding="unicode", with_tail=False)
    return out.replace(f' xmlns="{NS}"', "").replace(f' xmlns:epub="{EPUB}"', "")


def page_break(label: str) -> etree._Element:
    return etree.Element(
        f"{{{NS}}}span",
        {
            "id": f"qc-page-{label}",
            f"{{{EPUB}}}type": "pagebreak",
            "role": "doc-pagebreak",
            "aria-label": label,
        },
    )


CASES = [
    "plain text",
    "  lead <em>em  <b>bold</b> tail</em>\n trail  ",
    "a<sup>1</sup>b<!-- c -->d<script>x</script>e",
    "caf&#233; &amp; <span lang='it'>più</span>",
    "<em></em>",
    "",
    "x<br/>y",
]


@pytest.mark.parametrize("inner", CASES)
def test_text_of_matches_block_text(inner):
    el = p_of(inner)
    assert text_of(el) == _own_text(el)


def test_text_of_excludes_nested_blocks_but_keeps_their_tails():
    li = body("<ul><li>one <p>nested</p> two</li></ul>")[0][0]
    assert text_of(li) == _own_text(li) == "one two"


def test_locate_prefers_the_earliest_equivalent_point():
    el = p_of("foo <em>bar</em>baz")
    assert locate(el, 0) == (el, "text", 0)
    assert locate(el, 4) == (el, "text", 4)  # before <em>, not inside it
    em = el[0]
    assert locate(el, 5) == (em, "text", 1)
    assert locate(el, 7) == (em, "text", 3)  # end of "bar"
    assert locate(el, 10) == (em, "tail", 3)  # end of the text
    with pytest.raises(IndexError):
        locate(el, 11)


def test_locate_maps_collapsed_whitespace():
    el = p_of("\n  one   <i>two</i>  ")
    assert text_of(el) == "one two"
    assert locate(el, 0) == (el, "text", 3)
    assert locate(el, 3) == (el, "text", 6)  # the collapsed space
    assert locate(el, 4) == (el, "text", 9)  # before <i>


def test_insert_page_break_mid_paragraph():
    el = p_of("one <em>two three</em> four")
    insert_at(el, text_of(el).index("three"), page_break("5"))
    assert xml(el) == (
        '<p>one <em>two <span id="qc-page-5" epub:type="pagebreak" role="doc-pagebreak" '
        'aria-label="5"/>three</em> four</p>'
    )
    assert text_of(el) == "one two three four"


def test_insert_in_tail_and_at_ends():
    el = p_of("a <b>b</b> c")
    insert_at(el, text_of(el).index("c"), etree.Element(f"{{{NS}}}span", id="x"))
    assert xml(el) == '<p>a <b>b</b> <span id="x"/>c</p>'
    insert_at(el, len(text_of(el)), etree.Element(f"{{{NS}}}span", id="end"))
    assert xml(el).endswith('c<span id="end"/></p>')
    insert_at(el, 0, etree.Element(f"{{{NS}}}span", id="start"))
    assert xml(el).startswith('<p><span id="start"/>a ')


def test_insert_into_empty_block():
    el = p_of("")
    insert_at(el, 0, etree.Element(f"{{{NS}}}span", id="x"))
    assert xml(el) == '<p><span id="x"/></p>'


def test_split_clones_the_inline_chain():
    root = body('<p id="p1" class="c">aa <em lang="it">bb <b>cc dd</b> ee</em> ff</p>\n<p>z</p>')
    el = root[0]
    new = split_at(el, text_of(el).index("dd"))
    assert xml(el) == '<p id="p1" class="c">aa <em lang="it">bb <b>cc </b></em></p>'
    assert xml(new) == '<p class="c"><em lang="it"><b>dd</b> ee</em> ff</p>'
    assert new.getprevious() is el
    assert new.tail == "\n" and el.tail is None
    assert [text_of(e) for e in root] == ["aa bb cc", "dd ee ff", "z"]


def test_split_in_a_tail_and_entities():
    el = p_of("caf&#233; <a href='#n1'>1</a> &amp; more")
    new = split_at(el, text_of(el).index("&"))
    assert xml(el) == '<p>café <a href="#n1">1</a> </p>'
    assert xml(new) == "<p>&amp; more</p>"


def test_split_at_wrapper_boundary_drops_empty_wrappers():
    el = p_of("<em>bar</em>baz")
    new = split_at(el, 3)
    assert xml(el) == "<p><em>bar</em></p>"
    assert xml(new) == "<p>baz</p>"


def test_split_ids_follow_their_element():
    el = p_of('one <a id="r1" href="#n">two</a> <span id="s2">three</span>')
    new = split_at(el, text_of(el).index("wo"))
    assert xml(el) == '<p>one <a id="r1" href="#n">t</a></p>'
    assert xml(new) == '<p><a href="#n">wo</a> <span id="s2">three</span></p>'


def test_split_empty_halves():
    el = p_of("<i>only</i>")
    new = split_at(el, 0)
    assert xml(el) == "<p/>" and xml(new) == "<p><i>only</i></p>"
    el = p_of("<i>only</i>")
    new = split_at(el, 4)
    assert xml(el) == "<p><i>only</i></p>" and xml(new) == "<p/>"


def test_merge_keeps_markup_and_ids():
    root = body('<p id="a">conti-</p>\n<p id="page_2"><em>nued</em> on</p>\n<p>next</p>')
    a, b = root[0], root[1]
    merge(a, b)
    assert xml(a) == '<p id="a">conti-<span id="page_2"/><em>nued</em> on</p>'
    assert len(root) == 2 and a.tail == "\n\n"
    assert text_of(a) == "conti-nued on"


def test_merge_with_separator_after_children():
    root = body("<p>one <b>two</b></p><p>three</p>")
    merge(root[0], root[1], sep=" ")
    assert xml(root[0]) == "<p>one <b>two</b> three</p>"
    with pytest.raises(ValueError):
        merge(root, root[0])
