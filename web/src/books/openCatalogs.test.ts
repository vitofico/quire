import { describe, expect, it, vi } from 'vitest'
import { addOpenCatalog, isAdded, openCatalogs, orderedFor, type OpenCatalog } from './openCatalogs'
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
      expect(['public_domain', 'open_licence']).toContain(c.offers)
      expect(Object.keys(c.description).sort()).toEqual(['de', 'en', 'es', 'fr', 'it'])
    }
  })

  it('puts catalogs in the reader’s language first and keeps the rest in order', () => {
    const list = [catalog('a', ['en']), catalog('b', ['fr']), catalog('c', ['en', 'fr']), catalog('d', ['de'])]
    expect(orderedFor(list, 'fr').map(c => c.id)).toEqual(['b', 'c', 'a', 'd'])
    expect(orderedFor(list, 'pt').map(c => c.id)).toEqual(['a', 'b', 'c', 'd'])
  })

  it('counts a catalog as added when its host and name are in the account', () => {
    const open = catalog('Shelf', ['en'], 'https://books.example.org/opds/?q=l.de')
    expect(isAdded([saved('Shelf', 'https://books.example.org')], open)).toBe(true)
    expect(isAdded([saved('Other', 'https://books.example.org')], open)).toBe(false)
    expect(isAdded([saved('Shelf', 'https://elsewhere.example.org')], open)).toBe(false)
  })

  it('saves the catalog to the account without a login', async () => {
    const entry = saved('Shelf', 'https://shelf.example.org')
    const request = vi.fn(async (_path: string, _init?: RequestInit) => Response.json(entry))
    await expect(addOpenCatalog(request, catalog('Shelf', ['en'], 'https://shelf.example.org/opds'))).resolves.toEqual(entry)
    const [path, init] = request.mock.calls[0]
    expect(path).toMatch(/^\/catalogs\/v1\/catalogs\/[0-9a-f-]{36}$/)
    expect(init?.method).toBe('PUT')
    expect(JSON.parse(String(init?.body))).toEqual({ name: 'Shelf', kind: 'opds', url: 'https://shelf.example.org/opds' })
  })

  it('explains a full account and any other refusal', async () => {
    const answer = (status: number, detail: string) => vi.fn(async () => Response.json({ detail }, { status }))
    await expect(addOpenCatalog(answer(409, 'catalog_limit_reached'), catalog('a', ['en'])))
      .rejects.toThrow('Your Quire account already holds as many catalogs as it can.')
    await expect(addOpenCatalog(answer(500, 'boom'), catalog('a', ['en'])))
      .rejects.toThrow('Quire could not add this catalog. Try again later.')
  })
})
