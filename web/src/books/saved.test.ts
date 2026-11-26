import { afterEach, describe, expect, it, vi } from 'vitest'
import { makeCatalogClient } from './opds'
import { distinctSaved, listSavedCatalogs, removeSavedCatalog, savedCatalogHome, savedCatalogRequest, shownEntry, type SavedCatalog } from './saved'

const catalog: SavedCatalog = {
  id: '6f1c',
  name: 'Home library',
  kind: 'opds',
  host: 'http://books.example.org:8080',
  has_login: true,
  needs_login: false,
  position: 0,
  updated_at: '2026-11-25T10:00:00Z',
}
const endpoint = '/catalogs/v1/catalogs/6f1c/fetch'
const feed = `<feed xmlns="http://www.w3.org/2005/Atom">
  <entry><title>New</title><link type="application/atom+xml;profile=opds-catalog" href="new"/></entry>
  <entry><id>1</id><title>A Book</title>
    <link rel="http://opds-spec.org/acquisition" type="application/epub+zip" href="/download/1.epub"/>
  </entry>
</feed>`

afterEach(() => vi.unstubAllGlobals())

function server(answer: (path: string) => Response) {
  return vi.fn(async (path: string, _init?: RequestInit) => answer(path))
}

describe('saved catalogs', () => {
  it('shows each catalog once: not a repeated address, nor the server’s own catalog already shown', () => {
    const own = { ...catalog, id: 'own', server_catalog: true }
    const again = { ...catalog, id: 'again', server_catalog: true, duplicate_of: 'own' }
    const kavita = { ...catalog, id: 'kavita' }
    const twin = { ...catalog, id: 'twin', duplicate_of: 'kavita' }
    const older = { ...catalog, id: 'older' } // From a server that does not mark them.
    const list = [own, again, kavita, twin, older]
    expect(distinctSaved(list, true).map(c => c.id)).toEqual(['kavita', 'older'])
    expect(distinctSaved(list, false).map(c => c.id)).toEqual(['own', 'kavita', 'older'])
    // A choice remembered for a hidden entry lands on the one shown for it.
    expect(shownEntry(list, 'again', true)).toBe('')
    expect(shownEntry(list, 'own', true)).toBe('')
    expect(shownEntry(list, 'again', false)).toBe('own')
    expect(shownEntry(list, 'twin', true)).toBe('kavita')
    expect(shownEntry(list, 'kavita', true)).toBe('kavita')
    expect(shownEntry(list, 'gone', true)).toBe('gone')
  })

  it('counts a catalog another device already removed as removed', async () => {
    const answer = (status: number) => vi.fn(async () => new Response(null, { status }))
    await expect(removeSavedCatalog(answer(204), 'a')).resolves.toBeUndefined()
    await expect(removeSavedCatalog(answer(404), 'a')).resolves.toBeUndefined()
    await expect(removeSavedCatalog(answer(500), 'a')).rejects.toThrow('Quire could not remove this catalog.')
  })

  it('browses through the server, which answers with the address it fetched', async () => {
    const request = server(path => path.includes('download')
      ? new Response(new Uint8Array([80, 75, 3, 4]))
      : new Response(feed, { headers: { 'Content-Location': 'http://books.example.org:8080/opds/' } }))
    vi.stubGlobal('fetch', vi.fn())
    const client = makeCatalogClient({ url: savedCatalogHome(catalog), request: savedCatalogRequest(request, catalog) })

    const home = await client.feed()
    // Relative links resolve against the real catalog address, plain http included.
    expect(home.navigation[0].href).toBe('http://books.example.org:8080/opds/new')
    expect(home.publications[0].epubHref).toBe('http://books.example.org:8080/download/1.epub')

    await client.feed(home.navigation[0].href)
    await client.acquire(home.publications[0].epubHref)
    expect(request.mock.calls.map(call => call[0])).toEqual([
      endpoint,
      endpoint + '?url=' + encodeURIComponent('http://books.example.org:8080/opds/new'),
      endpoint + '?url=' + encodeURIComponent('http://books.example.org:8080/download/1.epub'),
    ])
    // Only Accept goes to the server; the browser itself never contacts the catalog.
    expect([...new Headers(request.mock.calls[0][1]?.headers).keys()]).toEqual(['accept'])
    expect(fetch).not.toHaveBeenCalled()
  })

  it('explains what the server refused', async () => {
    const failing = (status: number, body: unknown) =>
      makeCatalogClient({
        url: savedCatalogHome(catalog),
        request: savedCatalogRequest(server(() => Response.json(body, { status })), catalog),
      }).feed()
    await expect(failing(502, { detail: 'catalog_login_rejected' })).rejects
      .toThrow('This catalog’s login no longer works. Update it in the Quire app on Android.')
    await expect(failing(502, { detail: 'catalog_unreachable' })).rejects
      .toThrow('Quire could not reach this catalog. Try again later.')
    await expect(failing(409, { detail: 'needs_login' })).rejects
      .toThrow('Enter this catalog’s login again in the Quire app on Android.')
    await expect(failing(404, { detail: 'Not Found' })).rejects
      .toThrow('This Quire server does not open saved catalogs in the browser.')
    await expect(failing(502, { detail: 'catalog_error', status: 500 })).rejects
      .toThrow('Catalog returned HTTP 500')
    await expect(failing(500, null)).rejects.toThrow('Catalog returned HTTP 500')
  })

  it('keeps under the server’s limit of fetches in flight', async () => {
    const answers: Array<() => void> = []
    const request = vi.fn((_path: string, _init?: RequestInit) =>
      new Promise<Response>(resolve => answers.push(() => resolve(new Response(feed)))))
    const relay = savedCatalogRequest(request, catalog)
    const home = savedCatalogHome(catalog)
    const stopped = new AbortController()
    const pending = [1, 2, 3, 4].map(() => relay(home))
    const abandoned = relay(home, { signal: stopped.signal })
    await Promise.resolve()
    expect(request).toHaveBeenCalledTimes(3)
    stopped.abort()
    await expect(abandoned).rejects.toBeDefined()
    answers.shift()?.()
    await pending[0]
    await vi.waitFor(() => expect(request).toHaveBeenCalledTimes(4))
    while (answers.length) answers.shift()?.()
    await Promise.all(pending)
    expect(request).toHaveBeenCalledTimes(4)
  })

  it('lists none when the server keeps no list', async () => {
    expect(await listSavedCatalogs(server(() => new Response(null, { status: 404 })))).toEqual([])
    expect(await listSavedCatalogs(server(() => Response.json({ catalogs: [catalog] })))).toEqual([catalog])
  })
})
