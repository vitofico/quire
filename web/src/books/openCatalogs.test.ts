import { describe, expect, it, vi } from 'vitest'
import { addOpenCatalog, addedEntry, openCatalogs, orderedFor, type OpenCatalog } from './openCatalogs'
import type { SavedCatalog } from './saved'

const catalog = (id: string, languages: string[], url = `https://${id}.example.org/opds`): OpenCatalog => ({
  id, name: id, url, languages, offers: 'public_domain', home: url, description: { en: id },
})
const saved = (name: string, host: string): SavedCatalog => ({
  id: name, name, kind: 'opds', host, has_login: false, needs_login: false, position: 0, updated_at: '2026-11-25T10:00:00Z',
})

describe('open catalogs', () => {
  it('lists only complete https entries described in each app language', () => {
    expect(openCatalogs.length).toBeGreaterThan(0)
    expect(new Set(openCatalogs.map(c => c.id)).size).toBe(openCatalogs.length)
    for (const c of openCatalogs) {
      expect(new URL(c.url).protocol).toBe('https:')
      expect(['public_domain', 'public_domain_us', 'open_licence']).toContain(c.offers)
      expect(Object.keys(c.description).sort()).toEqual(['de', 'en', 'es', 'fr', 'it'])
    }
  })

  it('puts catalogs in the reader’s language first and keeps the rest in order', () => {
    const list = [catalog('a', ['en']), catalog('b', ['fr']), catalog('c', ['en', 'fr']), catalog('d', ['de'])]
    expect(orderedFor(list, 'fr').map(c => c.id)).toEqual(['b', 'c', 'a', 'd'])
    expect(orderedFor(list, 'pt').map(c => c.id)).toEqual(['a', 'b', 'c', 'd'])
  })

  it('counts a catalog as added when its host and name are in the account', () => {
    const open = catalog('Shelf', ['en'], 'https://books.example.org/opds/')
    const held = saved('Shelf', 'https://books.example.org')
    expect(addedEntry([held], open)).toBe(held)
    expect(addedEntry([saved('Other', 'https://books.example.org')], open)).toBeUndefined()
    expect(addedEntry([saved('Shelf', 'https://elsewhere.example.org')], open)).toBeUndefined()
  })

  it('lists only language codes a browser can name', () => {
    for (const c of openCatalogs) expect(Intl.getCanonicalLocales(c.languages)).toEqual(c.languages)
  })

  const feed = `<feed xmlns="http://www.w3.org/2005/Atom"><title>Shelf</title>
    <entry><title>New</title><link type="application/atom+xml" href="/opds/new"/></entry></feed>`
  const entry = saved('Shelf', 'https://shelf.example.org')
  /** A Quire server answering each call by "METHOD path"; the rest are recorded too. */
  function server(answers: Record<string, () => Response>) {
    return vi.fn(async (path: string, init?: RequestInit) => {
      const key = `${init?.method ?? 'GET'} ${path.replace(/[0-9a-f-]{36}/, 'ID')}`
      const answer = answers[key]
      if (!answer) throw new Error('unexpected ' + key)
      return answer()
    })
  }
  const shelf = catalog('Shelf', ['en'], 'https://shelf.example.org/opds')

  it('saves the catalog without a login and opens it once through the server', async () => {
    const request = server({
      'PUT /catalogs/v1/catalogs/ID': () => Response.json(entry),
      'GET /catalogs/v1/catalogs/Shelf/fetch': () => new Response(feed),
    })
    await expect(addOpenCatalog(request, shelf)).resolves.toEqual(entry)
    const [path, init] = request.mock.calls[0]
    expect(path).toMatch(/^\/catalogs\/v1\/catalogs\/[0-9a-f-]{36}$/)
    expect(init?.method).toBe('PUT')
    expect(JSON.parse(String(init?.body))).toEqual({
      name: 'Shelf', kind: 'opds', url: 'https://shelf.example.org/opds', unique_url: true,
    })
    expect(request).toHaveBeenCalledTimes(2)
  })

  it('removes a catalog that does not open, and says why', async () => {
    const request = server({
      'PUT /catalogs/v1/catalogs/ID': () => Response.json(entry),
      'GET /catalogs/v1/catalogs/Shelf/fetch': () => Response.json({ detail: 'catalog_unreachable' }, { status: 502 }),
      'DELETE /catalogs/v1/catalogs/Shelf': () => new Response(null, { status: 204 }),
    })
    await expect(addOpenCatalog(request, shelf)).rejects.toThrow('Quire could not reach this catalog. Try again later.')
    expect(request.mock.calls.at(-1)?.[1]?.method).toBe('DELETE')
  })

  it('removes a catalog whose home is not a feed with anything in it', async () => {
    const request = server({
      'PUT /catalogs/v1/catalogs/ID': () => Response.json(entry),
      'GET /catalogs/v1/catalogs/Shelf/fetch': () => new Response('<feed xmlns="http://www.w3.org/2005/Atom"/>'),
      'DELETE /catalogs/v1/catalogs/Shelf': () => new Response(null, { status: 204 }),
    })
    await expect(addOpenCatalog(request, shelf)).rejects.toThrow('This catalog answered, but Quire found no books or sections in it.')
    expect(request.mock.calls.at(-1)?.[1]?.method).toBe('DELETE')
  })

  it('returns the entry the account already holds for the same address', async () => {
    const held = { ...entry, id: 'held', name: 'My shelf' }
    const request = server({
      'PUT /catalogs/v1/catalogs/ID': () => Response.json({ detail: 'catalog_exists', id: 'held' }, { status: 409 }),
      'GET /catalogs/v1/catalogs': () => Response.json({ catalogs: [held] }),
    })
    await expect(addOpenCatalog(request, shelf)).resolves.toEqual(held)
  })

  it('explains a full account, an unreachable server and any other refusal', async () => {
    const answer = (status: number, detail: string) => vi.fn(async () => Response.json({ detail }, { status }))
    await expect(addOpenCatalog(answer(409, 'catalog_limit_reached'), shelf))
      .rejects.toThrow('Your Quire account already holds as many catalogs as it can.')
    await expect(addOpenCatalog(answer(500, 'boom'), shelf))
      .rejects.toThrow('Quire could not add this catalog. Try again later.')
    await expect(addOpenCatalog(vi.fn(async () => { throw new TypeError('Failed to fetch') }), shelf))
      .rejects.toThrow('Quire could not reach the server. Try again.')
  })
})
