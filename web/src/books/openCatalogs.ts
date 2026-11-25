import list from '../../../catalogs/open-catalogs.json'
import type { SavedCatalog } from './saved'

type ApiRequest = (path: string, init?: RequestInit) => Promise<Response>

/** A free, legal catalog Quire suggests. The list is shared with the Android app. */
export interface OpenCatalog {
  id: string
  name: string
  url: string
  /** BCP 47 codes of the books it offers. */
  languages: string[]
  offers: 'public_domain' | 'open_licence'
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
 * Whether the account already lists `catalog`. The list shows only names and
 * hosts, and several open catalogs share a host, so both must match.
 */
export function isAdded(saved: SavedCatalog[], catalog: OpenCatalog): boolean {
  const host = new URL(catalog.url).origin
  return saved.some(c => c.host === host && c.name === catalog.name)
}

const problems: Record<string, string> = {
  catalog_limit_reached: 'Your Quire account already holds as many catalogs as it can.',
}

/** Save `catalog` to the Quire account, as the Android app saves a typed address. */
export async function addOpenCatalog(request: ApiRequest, catalog: OpenCatalog): Promise<SavedCatalog> {
  const response = await request('/catalogs/v1/catalogs/' + crypto.randomUUID(), {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name: catalog.name, kind: 'opds', url: catalog.url }),
  })
  if (response.ok) return await response.json() as SavedCatalog
  const body = await response.json().catch(() => null) as { detail?: unknown } | null
  const detail = typeof body?.detail === 'string' ? body.detail : ''
  throw new Error(problems[detail] ?? 'Quire could not add this catalog. Try again later.')
}
