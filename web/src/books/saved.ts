import { CatalogProblem, type CatalogRequest } from './opds'

type ApiRequest = (path: string, init?: RequestInit) => Promise<Response>

/** A catalog saved to the Quire account, as the server lists it: no address, no login. */
export interface SavedCatalog {
  id: string
  name: string
  kind: 'calibre' | 'opds'
  /** Scheme, host and port only. */
  host: string
  has_login: boolean
  needs_login: boolean
  position: number
  updated_at: string
}

/** The account's saved catalogs; none when the server keeps no list. */
export async function listSavedCatalogs(request: ApiRequest, signal?: AbortSignal): Promise<SavedCatalog[]> {
  const response = await request('/catalogs/v1/catalogs', { signal })
  if (response.status === 404) return []
  if (!response.ok) throw new Error('Could not load your saved catalogs.')
  const body = await response.json() as { catalogs?: SavedCatalog[] }
  return Array.isArray(body.catalogs) ? body.catalogs : []
}

/** Remove a saved catalog from the account, and so from the reader's other devices. */
export async function removeSavedCatalog(request: ApiRequest, id: string): Promise<void> {
  let response
  try {
    response = await request('/catalogs/v1/catalogs/' + encodeURIComponent(id), { method: 'DELETE' })
  } catch {
    throw new Error('Quire could not reach the server. Try again.')
  }
  // Already gone, removed on another device: what the reader asked for.
  if (!response.ok && response.status !== 404) throw new Error('Quire could not remove this catalog. Try again later.')
}

/**
 * Where browsing a saved catalog starts. The list gives only the catalog's
 * host, so this stands in for the saved address and the server fetches the
 * real one. The page then sees the catalog's paths, never its login.
 * A feed link to the catalog server's bare root lands on the catalog home too.
 */
export function savedCatalogHome(catalog: SavedCatalog): string {
  return new URL('/', catalog.host).href
}

/** Shown for a saved catalog whose login this server cannot read any more. */
export const needsLogin = 'Enter this catalog’s login again in the Quire app on Android.'

const problems: Record<string, string> = {
  catalog_login_rejected: 'This catalog’s login no longer works. Update it in the Quire app on Android.',
  needs_login: needsLogin,
  catalog_unreachable: 'Quire could not reach this catalog. Try again later.',
  catalog_redirect_refused: 'This catalog sent Quire to another site, so it stopped there.',
  catalog_too_large: 'This catalog sent more than Quire passes on.',
  catalog_compressed: 'This catalog sent a compressed answer, which Quire does not pass on.',
  rate_limited: 'Too many catalog requests. Wait a minute and try again.',
  too_many_fetches: 'Quire is busy with other catalog requests. Try again in a moment.',
  'Not Found': 'This Quire server does not open saved catalogs in the browser.',
  not_found: 'This catalog is no longer on your account.',
}

// The server runs at most 4 fetches at once for an account and refuses more,
// while a catalog page asks for every cover together: the rest wait here.
const MAX_IN_FLIGHT = 3
let inFlight = 0
const waiting: Array<() => void> = []

function slot(signal?: AbortSignal): Promise<void> {
  if (signal?.aborted) return Promise.reject(signal.reason)
  if (inFlight < MAX_IN_FLIGHT) {
    inFlight++
    return Promise.resolve()
  }
  return new Promise((resolve, reject) => {
    const start = () => {
      signal?.removeEventListener('abort', stop)
      inFlight++
      resolve()
    }
    const stop = () => {
      waiting.splice(waiting.indexOf(start), 1)
      reject(signal?.reason)
    }
    waiting.push(start)
    signal?.addEventListener('abort', stop, { once: true })
  })
}

function done() {
  inFlight--
  waiting.shift()?.()
}

/** A `request` for `makeCatalogClient` that has the Quire server fetch the catalog, with its saved login. */
export function savedCatalogRequest(request: ApiRequest, catalog: SavedCatalog): CatalogRequest {
  const home = savedCatalogHome(catalog)
  const endpoint = `/catalogs/v1/catalogs/${encodeURIComponent(catalog.id)}/fetch`
  return async (url, init) => {
    // Only what the catalog needs to answer; the account request adds the rest.
    const accept = new Headers(init?.headers).get('Accept')
    await slot(init?.signal ?? undefined)
    let response: Response
    try {
      response = await request(url === home ? endpoint : endpoint + '?url=' + encodeURIComponent(url), {
        headers: accept ? { Accept: accept } : undefined,
        signal: init?.signal,
      })
    } finally {
      done()
    }
    if (response.ok) {
      // The address that answered, after redirects: the feed's relative links resolve against it.
      const answered = response.headers.get('Content-Location')
      if (answered) Object.defineProperty(response, 'url', { value: answered })
      return response
    }
    const body = await response.json().catch(() => null) as { detail?: unknown; status?: unknown } | null
    const detail = typeof body?.detail === 'string' ? body.detail : ''
    if (detail === 'catalog_error' && typeof body?.status === 'number') {
      throw new CatalogProblem(`Catalog returned HTTP ${body.status}`)
    }
    throw new CatalogProblem(problems[detail] ?? `Catalog returned HTTP ${response.status}`)
  }
}
