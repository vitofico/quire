import { expect, test, type Page } from '@playwright/test'
import { execFileSync } from 'node:child_process'
import { mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { sampledContentHash } from '../src/books/epub'

test.use({ actionTimeout: 10_000, navigationTimeout: 15_000 })

const catalogUrl = 'https://books.example.org/opds'
const feed = `<?xml version="1.0" encoding="utf-8"?>
<feed xmlns="http://www.w3.org/2005/Atom">
  <id>urn:calibre</id><title>Calibre library</title><updated>2026-11-25T10:00:00Z</updated>
  <entry><id>urn:book</id><title>A Calibre book</title><updated>2026-11-25T10:00:00Z</updated>
    <link rel="http://opds-spec.org/acquisition" type="application/epub+zip" href="/opds/download/7/epub/"/>
  </entry>
</feed>`
const item = (id: string, name: string, host: string, extra: object = {}) => ({
  id, name, kind: 'calibre', host, has_login: true, needs_login: false, position: 0,
  updated_at: '2026-11-25T10:00:00Z', server_catalog: false, duplicate_of: null, ...extra,
})

/**
 * A Quire account on a server that offers its Calibre catalog, which the reader's
 * Android app also saved to the account, with its login, twice over.
 */
type Options = {
  books?: object[]
  /** The saved copies of the server's catalog, as the list shows them. */
  copy?: object
  again?: object
  /** How the server answers a fetch through the copy, when it fails. */
  fetchFails?: string
  /** The list never answers. */
  stalls?: boolean
}

async function connect(page: Page, { books = [], copy = {}, again = {}, fetchFails, stalls }: Options = {}) {
  const fetches: string[] = []
  const direct: string[] = []
  const deletes: string[] = []
  await page.addInitScript(() => { Reflect.deleteProperty(Navigator.prototype, 'serviceWorker') })
  await page.route('**/quire/v1/bootstrap', route => route.fulfill({ json: {
    version: 1, sync: books.length > 0, ai: false, aiAvailable: false, login: 'native', password: true,
    oidc: false, catalogUrl, accountManagement: null, catalogSync: true, catalogProxy: true,
  } }))
  await page.route('**/auth/v1/me', route => route.fulfill({ json: {
    version: 1, user: { id: 'reader', backend: 'native' }, csrf_token: 'test',
    capabilities: { progress_metadata_sync: books.length > 0, ai_enabled: false },
  } }))
  await page.route('**/library/v1/**', route => route.fulfill({ json: new URL(route.request().url()).pathname === '/library/v1/items'
    ? { items: books } : { processed: 1 } }))
  await page.route('**/sync/v1/**', route => route.fulfill({ json: { items: [], next_cursor: '0', has_more: false } }))
  await page.route('**/convert/v1/**', route => route.fulfill({ status: 404, json: { detail: 'Not Found' } }))
  await page.route('**/details/v1/**', route => route.fulfill({ status: 404, json: { detail: 'Not Found' } }))
  // The browser reaches the catalog itself only with a login entered by hand.
  await page.route('https://books.example.org/**', route => {
    const request = route.request()
    const cors = {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Headers': 'Authorization, Accept',
    }
    if (request.method() === 'OPTIONS') return route.fulfill({ status: 204, headers: cors })
    direct.push(request.url())
    return request.headers().authorization === 'Basic ' + btoa('reader:by-hand')
      ? route.fulfill({ headers: cors, contentType: 'application/atom+xml', body: feed.replace('Calibre library', 'Signed in by hand') })
      : route.fulfill({ status: 401, headers: cors })
  })
  await page.route('**/catalogs/v1/**', route => {
    const url = new URL(route.request().url())
    if (route.request().method() === 'DELETE') {
      deletes.push(url.pathname.split('/').pop()!)
      return route.fulfill({ status: 204 })
    }
    if (url.pathname.endsWith('/fetch')) {
      fetches.push(url.pathname + url.search)
      if (fetchFails) return route.fulfill({ status: 502, json: { detail: fetchFails } })
      const target = url.searchParams.get('url') ?? ''
      if (target.includes('/download/')) {
        return route.fulfill({ contentType: 'application/epub+zip', body: epub })
      }
      return route.fulfill({ contentType: 'application/atom+xml', body: feed })
    }
    if (stalls) return
    return route.fulfill({ json: { catalogs: [
      item('own', 'books.example.org', 'https://books.example.org', { server_catalog: true, ...copy }),
      item('own-again', 'books.example.org', 'https://books.example.org', { server_catalog: true, duplicate_of: 'own', position: 1, ...again }),
      item('kavita', 'books.example.org', 'https://books.example.org', { kind: 'opds', position: 2 }),
      item('textos', 'Textos', 'https://textos.example', { kind: 'opds', has_login: false, position: 3 }),
      item('textos-again', 'Textos', 'https://textos.example', { kind: 'opds', has_login: false, position: 4, duplicate_of: 'textos' }),
    ] } })
  })
  return { fetches, direct, deletes }
}

const epub = fixture()

function fixture() {
  const dir = mkdtempSync(join(tmpdir(), 'quire-test-'))
  const filename = join(dir, 'fixture.epub')
  execFileSync('python3', ['../scripts/make-reader-pause-fixture.py', filename])
  const buffer = readFileSync(filename)
  rmSync(dir, { recursive: true })
  return buffer
}

test('the server’s catalog saved to the account shows once and opens without a catalog login', async ({ page }) => {
  const server = await connect(page)
  await page.goto('/quire/#/catalog')
  await expect(page.getByRole('heading', { name: 'Calibre library' })).toBeVisible()
  await expect(page.getByText('A Calibre book')).toBeVisible()
  const chips = page.getByRole('group', { name: 'Catalogs' }).getByRole('button')
  // Another catalog on the same host is told apart by the path of the server's own.
  await expect(chips).toHaveText(['books.example.org/opds', 'books.example.org', 'Textos'])
  await expect(chips.first()).toHaveAttribute('aria-pressed', 'true')
  await expect(page.getByLabel('Catalog username')).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Catalog sign-in' })).toHaveCount(0)
  await expect(page.getByText('Sign in to browse this catalog.')).toHaveCount(0)
  // The server's catalog is not offered for removal: other devices reach it through that copy.
  await expect(page.getByRole('button', { name: /^Remove / })).toHaveCount(0)
  expect(server.fetches.every(path => path.startsWith('/catalogs/v1/catalogs/own/fetch'))).toBe(true)
  expect(server.direct).toEqual([])
})

test('a library book from the server’s catalog downloads through the saved copy', async ({ page }) => {
  const key = sampledContentHash(new Uint8Array(epub))
  const book = {
    content_hash: key, title: 'Reader Pause Fixture', authors: ['Quire'],
    opds_href: 'https://books.example.org/opds/download/7/epub/', catalog_id: null,
    updated_at: '2026-11-25T10:00:00Z', deleted_at: null,
  }
  const server = await connect(page, { books: [book] })
  await page.goto('/quire/#/book/' + encodeURIComponent(key))
  await page.getByRole('button', { name: 'Download from catalog' }).click()
  await expect.poll(() => server.fetches.some(path => path.includes(encodeURIComponent('/opds/download/7/epub/')))).toBe(true)
  await expect(page.getByRole('button', { name: 'Read', exact: true })).toBeVisible()
  await expect(page.getByText('The catalog requires valid credentials')).toHaveCount(0)
  expect(server.fetches.every(path => path.startsWith('/catalogs/v1/catalogs/own/fetch'))).toBe(true)
  expect(server.direct).toEqual([])
})

test('a saved copy without a login is not used: the catalog asks for one as before', async ({ page }) => {
  const server = await connect(page, { copy: { has_login: false }, again: { has_login: false } })
  await page.goto('/quire/#/catalog')
  await expect(page.getByText('Sign in to browse this catalog.')).toBeVisible()
  await expect(page.getByLabel('Catalog username')).toBeVisible()
  expect(server.fetches).toEqual([])
})

test('the first saved copy with a login is the one used', async ({ page }) => {
  const server = await connect(page, { copy: { has_login: false } })
  await page.goto('/quire/#/catalog')
  await expect(page.getByRole('heading', { name: 'Calibre library' })).toBeVisible()
  expect(server.fetches.every(path => path.startsWith('/catalogs/v1/catalogs/own-again/fetch'))).toBe(true)
  await expect(page.getByLabel('Catalog username')).toHaveCount(0)
})

for (const [detail, message] of [
  ['catalog_login_rejected', 'This catalog’s login no longer works. Update it in the Quire app on Android.'],
  ['catalog_unreachable', 'Quire could not reach this catalog. Try again later.'],
]) {
  test(`a saved copy that fails (${detail}) offers the sign-in, which then wins`, async ({ page }) => {
    const server = await connect(page, { fetchFails: detail })
    await page.goto('/quire/#/catalog')
    await expect(page.getByRole('alert')).toHaveText(message)
    await page.getByRole('button', { name: 'Catalog sign-in' }).click()
    await page.getByLabel('Catalog username').fill('reader')
    await page.getByLabel('Catalog password').fill('by-hand')
    await page.getByRole('button', { name: 'Catalog sign-in' }).last().click()
    await expect(page.getByRole('heading', { name: 'Signed in by hand' })).toBeVisible()
    // Signed in here, the catalog stays on that login instead of switching back to the copy.
    const proxied = server.fetches.length
    await page.getByRole('button', { name: 'A Calibre book' }).click()
    await expect(page.getByRole('button', { name: 'Download EPUB' })).toBeVisible()
    await page.getByRole('link', { name: 'Library', exact: true }).click()
    await page.getByRole('link', { name: 'Catalog', exact: true }).click()
    await expect(page.getByRole('heading', { name: 'Signed in by hand' })).toBeVisible()
    expect(server.fetches.length).toBe(proxied)
    await expect(page.getByRole('button', { name: 'Sign out of catalog' })).toBeVisible()
  })
}

test('a list that does not answer is given up on and the catalog opens as before', async ({ page }) => {
  const server = await connect(page, { stalls: true })
  await page.goto('/quire/#/catalog')
  await expect(page.getByRole('status').filter({ hasText: 'Loading catalog…' })).toBeVisible()
  // After the list's deadline, the browser asks the catalog itself, which wants a login.
  await expect(page.getByText('Sign in to browse this catalog.')).toBeVisible({ timeout: 15_000 })
  expect(server.direct.length).toBeGreaterThan(0)
})

test('removing a saved catalog removes its repeated entries too', async ({ page }) => {
  const server = await connect(page)
  await page.goto('/quire/#/catalog')
  await page.getByRole('group', { name: 'Catalogs' }).getByRole('button', { name: 'Textos' }).click()
  await page.getByRole('button', { name: 'Remove Textos' }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'Remove' }).click()
  await expect(page.getByRole('status').filter({ hasText: 'Textos removed.' })).toBeVisible()
  expect(server.deletes.sort()).toEqual(['textos', 'textos-again'])
})
