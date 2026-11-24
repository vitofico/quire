import { afterEach, describe, expect, it, vi } from 'vitest'
import { makeCatalogClient, parseOpdsFeed } from './opds'

const base = 'https://catalog.example/books/opds'
const source = `<feed xmlns="http://www.w3.org/2005/Atom" xmlns:dc="http://purl.org/dc/elements/1.1/">
  <title>My Books</title>
  <link rel="next" href="?page=2" />
  <link rel="search" type="application/opensearchdescription+xml" href="search.xml"/>
  <entry><id>subjects</id><title>Subjects</title><link rel="subsection" type="application/atom+xml;profile=opds-catalog" href="subjects"/></entry>
  <entry xml:base="https://cdn.example/library/">
    <id>42</id><title>One Book</title><author><name>Example Author</name></author>
    <dc:identifier>urn:isbn:9780141036144</dc:identifier>
    <summary type="html">&lt;p&gt;One short book&lt;/p&gt;</summary>
    <link rel="http://opds-spec.org/acquisition/open-access" type="application/epub+zip" href="42.epub" />
    <link rel="http://opds-spec.org/image" href="covers/42.jpg" />
  </entry>
  <entry><id>bad</id><title>Bad link</title><link rel="http://opds-spec.org/acquisition" type="application/epub+zip" href="https://name:secret@cdn.example/hide.epub"/></entry>
</feed>`

afterEach(() => vi.unstubAllGlobals())

describe('OPDS catalogs', () => {
  it('resolves nested feed bases, acquisitions, cover links and pagination', () => {
    const feed = parseOpdsFeed(source, base)
    expect(feed.next).toBe(base + '?page=2')
    expect(feed.navigation).toEqual([{ title: 'Subjects', href: 'https://catalog.example/books/subjects' }])
    expect(feed.publications).toHaveLength(1)
    expect(feed.publications[0]).toMatchObject({
      title: 'One Book',
      authors: ['Example Author'],
      epubHref: 'https://cdn.example/library/42.epub',
      coverHref: 'https://cdn.example/library/covers/42.jpg',
      description: 'One short book',
      metadataId: 'urn:isbn:9780141036144',
    })
  })

  it('finds the newest-books feed by OPDS relation or Calibre-Web path', () => {
    const entry = (rel: string, href: string) =>
      `<entry><title>${href}</title><link ${rel} type="application/atom+xml;profile=opds-catalog" href="${href}"/></entry>`
    const feed = (...entries: string[]) => parseOpdsFeed(`<feed xmlns="http://www.w3.org/2005/Atom">${entries.join('')}</feed>`, base)
    expect(feed(entry('', '/opds/books'), entry('', '/opds/new')).latest).toBe('https://catalog.example/opds/new')
    expect(feed(entry('', '/opds/new'), entry('rel="http://opds-spec.org/sort/new"', '/recent')).latest)
      .toBe('https://catalog.example/recent')
    expect(feed(entry('', '/opds/newsletters')).latest).toBeNull()
  })

  it('binds authorization to the configured catalog origin', async () => {
    const zip = new Uint8Array([80, 75, 3, 4, 0, 0])
    const mockFetch = vi.fn(async (href: string, _init?: RequestInit) =>
      new Response(href === base ? source : zip))
    vi.stubGlobal('fetch', mockFetch)
    const client = makeCatalogClient({ url: base, authorization: 'Basic demo' })
    const feed = await client.feed()
    await client.acquire(feed.publications[0].epubHref)
    expect(new Headers(mockFetch.mock.calls[0][1]?.headers).get('Authorization')).toBe('Basic demo')
    expect(mockFetch.mock.calls[0][1]?.redirect).toBe('error')
    expect(new Headers(mockFetch.mock.calls[1][1]?.headers).has('Authorization')).toBe(false)
    expect(mockFetch.mock.calls[1][1]?.credentials).toBe('omit')
  })

  it('uses the separately authenticated catalog request only for its selected origin', async () => {
    const catalogRequest = vi.fn(async (_url: string, _init?: RequestInit) => new Response(source))
    const mockFetch = vi.fn(async (_url: string, _init?: RequestInit) =>
      new Response(new Uint8Array([80, 75, 3, 4, 0, 0])))
    vi.stubGlobal('fetch', mockFetch)
    const client = makeCatalogClient({ url: base, request: catalogRequest })
    const feed = await client.feed()
    await client.acquire(feed.publications[0].epubHref)
    expect(catalogRequest).toHaveBeenCalledTimes(1)
    expect(catalogRequest.mock.calls[0][0]).toBe(base)
    expect(catalogRequest.mock.calls[0][1]?.redirect).toBe('error')
    expect(mockFetch.mock.calls[0][0]).toBe('https://cdn.example/library/42.epub')
    expect(mockFetch.mock.calls[0][1]?.credentials).toBe('omit')
  })

  it('does not request an external cover from a private catalog', async () => {
    const catalogRequest = vi.fn(async (_url: string) => new Response(source))
    const mockFetch = vi.fn(async (_url: string) => new Response('cover', { headers: { 'Content-Type': 'image/jpeg' } }))
    vi.stubGlobal('fetch', mockFetch)
    const client = makeCatalogClient({ url: base, request: catalogRequest })
    const feed = await client.feed()
    const cover = feed.publications[0].coverHref!
    await expect(client.cover(cover)).rejects.toThrow('cover is unavailable')
    expect(catalogRequest).toHaveBeenCalledTimes(1)
    expect(mockFetch).not.toHaveBeenCalled()
    await expect(makeCatalogClient({ url: base }).cover(cover)).resolves.toMatchObject({ type: 'image/jpeg' })
    expect(mockFetch).toHaveBeenCalledTimes(1)
  })

  it('resolves OpenSearch descriptions and URL-encodes user input', async () => {
    const description = '<OpenSearchDescription xmlns="http://a9.com/-/spec/opensearch/1.1/"><Url type="application/atom+xml" template="find?q={searchTerms}&amp;page={startPage?}"/></OpenSearchDescription>'
    const mockFetch = vi.fn(async (href: string) => new Response(href.endsWith('search.xml') ? description : source))
    vi.stubGlobal('fetch', mockFetch)
    const client = makeCatalogClient({ url: base })
    const feed = await client.feed()
    await client.search(feed.search!, 'a + b')
    expect(mockFetch.mock.calls.map(([url]) => url)).toEqual([
      base, 'https://catalog.example/books/search.xml', 'https://catalog.example/books/find?q=a%20%2B%20b&page=',
    ])
  })

  it('rejects non-EPUB responses and external XML declarations', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('<html>Authentication required</html>')))
    const client = makeCatalogClient({ url: base })
    await expect(client.acquire(base)).rejects.toThrow('did not return an EPUB')
    expect(() => parseOpdsFeed('<!DOCTYPE feed SYSTEM "https://other.example/external">' + source, base)).toThrow('XML')
  })
})
