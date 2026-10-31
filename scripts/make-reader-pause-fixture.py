#!/usr/bin/env python3
"""Build a deterministic, original-text EPUB for manual reader pause acceptance."""
import sys
from pathlib import Path
from zipfile import ZIP_STORED, ZIP_DEFLATED, ZipFile, ZipInfo


def build(destination):
    chapters = []
    for chapter in range(1, 4):
        paragraphs = []
        for n in range(1, 61):
            anchor = ' id="nested"' if n == 31 else ''
            paragraphs.append(f'<p{anchor}>Marker {chapter}-{n:02d}. Mira walked beside the quiet river, '
                              'holding a letter she had chosen not to open. She wondered why the narrator '
                              'kept returning to the empty station. The water moved slowly under the bridge, '
                              'and she paused to consider the question before continuing her journey.</p>')
        chapters.append(f'<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Part {chapter}</title>'
                        f'</head><body><h1>Part {chapter}</h1>{"".join(paragraphs)}</body></html>')
    files = {
        'mimetype': 'application/epub+zip',
        'META-INF/container.xml': '<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="EPUB/package.opf" media-type="application/oebps-package+xml"/></rootfiles></container>',
        'EPUB/package.opf': '<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">urn:uuid:quire-reader-pause-fixture</dc:identifier><dc:title>Reader Pause Fixture</dc:title><dc:language>en</dc:language><meta property="dcterms:modified">2026-10-31T00:00:00Z</meta></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>' + ''.join(f'<item id="c{i}" href="part{i}.xhtml" media-type="application/xhtml+xml"/>' for i in range(1,4)) + '</manifest><spine>' + ''.join(f'<itemref idref="c{i}"/>' for i in range(1,4)) + '</spine></package>',
        'EPUB/nav.xhtml': '<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol><li><a href="part1.xhtml">Part one</a><ol><li><a href="part1.xhtml#nested">Nested section</a></li></ol></li><li><a href="part2.xhtml">Part two</a></li><li><a href="part3.xhtml">Part three</a></li></ol></nav></body></html>',
        **{f'EPUB/part{i}.xhtml': text for i,text in enumerate(chapters, 1)},
    }
    with ZipFile(destination, 'w') as archive:
        for name, text in files.items():
            info = ZipInfo(name, (2026, 10, 31, 0, 0, 0))
            info.compress_type = ZIP_STORED if name == 'mimetype' else ZIP_DEFLATED
            archive.writestr(info, text.encode())


if __name__ == '__main__':
    build(Path(sys.argv[1] if len(sys.argv) > 1 else '/tmp/reader-pause.epub'))
