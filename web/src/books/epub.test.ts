import { Blob as NodeBlob, Buffer } from 'node:buffer'
import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { zipSync } from 'fflate'
import { describe, expect, it } from 'vitest'
import { normalizeMetadataId, openEpub, resolveEpubHref, sampledContentHash } from './epub'

const fixtureDirectory = resolve('../core/identity/src/test/resources/identity')
const sampleOpf = readFileSync(resolve(fixtureDirectory, 'sample.opf'), 'utf8')
const identityFixtures: { cases: { in: string; out: string | null }[] } =
  JSON.parse(readFileSync(resolve(fixtureDirectory, 'fixtures.json'), 'utf8'))

const container = '<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OPS/book.opf"/></rootfiles></container>'
const chapter = '<html xmlns="http://www.w3.org/1999/xhtml"><body><p id="target">A <em>small</em> book.</p></body></html>'
const nav = '<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body><nav epub:type="toc"><ol><li><a href="Text/A%20B.xhtml#target">Chapter</a></li></ol></nav></body></html>'

function fixture(opf = sampleOpf, chapterText = chapter, extra: Record<string, string> = {}, items = '') {
  const manifest = '<manifest><item id="a" href="Text/A%20B.xhtml" media-type="application/xhtml+xml"/><item id="b" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>' + items + '</manifest><spine><itemref idref="a"/></spine>'
  const entries = {
    'mimetype': 'application/epub+zip',
    'META-INF/container.xml': container,
    'OPS/book.opf': opf.replace('</package>', manifest + '</package>'),
    'OPS/Text/A B.xhtml': chapterText,
    'OPS/nav.xhtml': nav,
    ...extra,
  }
  const bytes = zipSync(Object.fromEntries(Object.entries(entries).map(([key, value]) => [key, new Uint8Array(Buffer.from(value, 'utf8'))])))
  return { bytes, blob: new NodeBlob([new Uint8Array(bytes)]) as unknown as Blob }
}

function referenceHash(data: Uint8Array): string {
  const md5 = createHash('md5')
  const step = Math.max(1, Math.floor(data.length / 1024))
  for (let offset = 0; offset < Math.min(data.length, step * 1024); offset += step) {
    md5.update(data.subarray(offset, offset + 64))
  }
  return md5.digest('hex')
}

describe('EPUB identity', () => {
  it.each(identityFixtures.cases)('normalizes $in using the shared identity fixture', ({ in: input, out }) => {
    expect(normalizeMetadataId(input)).toBe(out)
  })

  it.each([64, 2 * 1024 * 1024])('matches Kotlin sampled MD5 for %i bytes', size => {
    const data = Uint8Array.from({ length: size }, (_, i) => i % 251)
    expect(sampledContentHash(data)).toBe(referenceHash(data))
  })

  it('reads the shared OPF fixture and uses compressed EPUB bytes for edition identity', async () => {
    const original = fixture()
    const book = await openEpub(original.blob)
    const changed = await openEpub(fixture(sampleOpf, chapter.replace('small', 'different')).blob)
    expect(book.identity).toEqual({ metadata_id: '42', content_hash: referenceHash(original.bytes), identity_hash_version: 1 })
    expect(changed.identity.metadata_id).toBe(book.identity.metadata_id)
    expect(changed.identity.content_hash).not.toBe(book.identity.content_hash)
    expect(book.metadata.title).toBe('Sample')
    expect(book.text(book.spine[0].href)).toEqual(chapter)
    expect(book.toc).toEqual([{ title: 'Chapter', href: 'OPS/Text/A%20B.xhtml#target', children: [] }])
  })

  it('keeps the first nonblank identifier even when it normalizes to null', async () => {
    const opf = sampleOpf.replace('>42</dc:identifier>', '>uuid:---</dc:identifier>')
    const result = await openEpub(fixture(opf).blob)
    expect(result.identity.metadata_id).toBeNull()
  })
})

describe('EPUB resource boundaries', () => {
  it('resolves relative paths and fragments without crossing the archive root', () => {
    const base = 'OPS/Text/chapter.xhtml'
    expect(resolveEpubHref(base, '../Images/cover one.png')).toBe('OPS/Images/cover%20one.png')
    expect(resolveEpubHref(base, '#target')).toBe(base + '#target')
    for (const href of ['https://other.example/img', '//other.example/image', 'x%2fy', '../../../x', 'x\\y', '%zz']) {
      expect(resolveEpubHref(base, href)).toBeUndefined()
    }
  })

  it('rejects packages with malformed paths or encrypted content', async () => {
    await expect(openEpub(fixture(sampleOpf, chapter, { '../outside.xhtml': chapter }).blob)).rejects.toThrow('path')
    const encryption = '<encryption><EncryptionMethod Algorithm="urn:unknown"/></encryption>'
    await expect(openEpub(fixture(sampleOpf, chapter, { 'META-INF/encryption.xml': encryption }).blob)).rejects.toThrow('encrypted')
  })
})

describe('EPUB cover', () => {
  const coverPage = (body: string) => ({ 'OPS/Text/cover.xhtml': `<html xmlns="http://www.w3.org/1999/xhtml"><body>${body}</body></html>`, 'OPS/Images/cover.jpg': 'JPEG' })
  const book = (metadata: string, manifest: string, guide = '', extra = coverPage('<img src="../Images/cover.jpg"/>')) =>
    fixture(sampleOpf.replace('</metadata>', metadata + '</metadata>').replace('</package>', guide + '</package>'), chapter, extra, manifest)
  const image = '<item id="art" href="Images/cover.jpg" media-type="image/jpeg"/>'
  const page = '<item id="page" href="Text/cover.xhtml" media-type="application/xhtml+xml"/>'
  const guide = '<guide><reference type="cover" href="Text/cover.xhtml"/></guide>'

  it.each([
    ['a cover meta naming the image href', '<meta name="cover" content="Images/cover.jpg"/>', image, ''],
    ['a cover meta naming the cover page', '<meta name="cover" content="page"/>', image + page, ''],
    ['a guide cover page with an img', '', image + page, guide],
    ['an image whose id mentions the cover', '', '<item id="cover-image" href="Images/cover.jpg" media-type="image/jpeg"/>', ''],
  ])('finds %s', async (_, metadata, manifest, guideElement) => {
    expect((await openEpub(book(metadata, manifest, guideElement).blob)).metadata.coverHref).toBe('OPS/Images/cover.jpg')
  })

  it('finds an SVG-wrapped guide cover', async () => {
    const svg = '<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink"><image xlink:href="../Images/cover.jpg"/></svg>'
    expect((await openEpub(book('', image + page, guide, coverPage(svg)).blob)).metadata.coverHref).toBe('OPS/Images/cover.jpg')
  })
})
