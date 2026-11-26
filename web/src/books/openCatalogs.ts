import list from '../../../catalogs/open-catalogs.json'
import { parseOpdsFeed } from './opds'
import { listSavedCatalogs, savedCatalogHome, savedCatalogRequest, type SavedCatalog } from './saved'

type ApiRequest = (path: string, init?: RequestInit) => Promise<Response>

/** A free, legal catalog Quire suggests. The list is shared with the Android app. */
export interface OpenCatalog {
  id: string
  name: string
  url: string
  /** BCP 47 codes of the books it offers. */
  languages: string[]
  offers: 'public_domain' | 'public_domain_us' | 'open_licence'
  home: string
  /** One line per app language; English is always there. */
  description: Record<string, string>
}

export const openCatalogs = list.catalogs as OpenCatalog[]

/** Those offering books in `language` first, the rest in the list's own order. */
export function orderedFor(catalogs: OpenCatalog[], language: string): OpenCatalog[] {
  return [...catalogs].sort((a, b) => Number(!a.languages.includes(language)) - Number(!b.languages.includes(language)))
}

/**
 * The account's entry for `catalog`, if it holds one. The list shows only names
 * and hosts, and a reader's own catalog can share a host, so both must match.
 */
export function addedEntry(saved: SavedCatalog[], catalog: OpenCatalog): SavedCatalog | undefined {
  const host = new URL(catalog.url).origin
  return saved.find(c => c.host === host && c.name === catalog.name)
}

const problems: Record<string, string> = {
  catalog_limit_reached: 'Your Quire account already holds as many catalogs as it can.',
}
const unreachable = 'Quire could not reach the server. Try again.'
const notACatalog = 'This catalog answered, but Quire found no books or sections in it.'

/**
 * Save `catalog` to the Quire account and open its home once through the server, as the Android
 * app tests a catalog before keeping it. One that does not open is removed again, so nothing is
 * left saved, and the error says why. One the account already holds comes back as it is.
 */
export async function addOpenCatalog(request: ApiRequest, catalog: OpenCatalog): Promise<SavedCatalog> {
  try {
    const response = await request('/catalogs/v1/catalogs/' + crypto.randomUUID(), {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: catalog.name, kind: 'opds', url: catalog.url, unique_url: true }),
    })
    const body = response.ok ? null : await response.json().catch(() => null) as { detail?: unknown; id?: unknown } | null
    if (body?.detail === 'catalog_exists') {
      const held = (await listSavedCatalogs(request)).find(c => c.id === body.id)
      if (held) return held
    }
    if (!response.ok) {
      const detail = typeof body?.detail === 'string' ? body.detail : ''
      throw new Error(problems[detail] ?? 'Quire could not add this catalog. Try again later.')
    }
    const saved = await response.json() as SavedCatalog
    try {
      await opens(request, saved)
    } catch (reason) {
      await request('/catalogs/v1/catalogs/' + encodeURIComponent(saved.id), { method: 'DELETE' }).catch(() => undefined)
      throw reason
    }
    return saved
  } catch (reason) {
    throw reason instanceof TypeError ? new Error(unreachable) : reason
  }
}

/** Fetch the saved catalog's home through the server and check it is a feed with something in it. */
async function opens(request: ApiRequest, saved: SavedCatalog) {
  const home = savedCatalogHome(saved)
  const response = await savedCatalogRequest(request, saved)(home, {
    headers: { Accept: 'application/atom+xml, application/xml;q=0.9, */*;q=0.8' },
  })
  let feed
  try {
    feed = parseOpdsFeed(await response.text(), response.url || home, new URL(home).origin)
  } catch {
    throw new Error(notACatalog)
  }
  if (!feed.navigation.length && !feed.publications.length) throw new Error(notACatalog)
}
