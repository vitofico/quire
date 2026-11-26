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
async function connect(page: Page, books: object[] = []) {
  const fetches: string[] = []
  const direct: string[] = []
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
  // The browser has no login for the catalog itself.
  await page.route('https://books.example.org/**', route => {
    direct.push(route.request().url())
    return route.fulfill({ status: 401 })
  })
  await page.route('**/catalogs/v1/**', route => {
    const url = new URL(route.request().url())
    if (url.pathname.endsWith('/fetch')) {
      fetches.push(url.pathname + url.search)
      const target = url.searchParams.get('url') ?? ''
      if (target.includes('/download/')) {
        return route.fulfill({ contentType: 'application/epub+zip', body: epub })
      }
      return route.fulfill({ contentType: 'application/atom+xml', body: feed })
    }
    return route.fulfill({ json: { catalogs: [
      item('own', 'books.example.org', 'https://books.example.org', { server_catalog: true }),
      item('own-again', 'books.example.org', 'https://books.example.org', { server_catalog: true, duplicate_of: 'own', position: 1 }),
      item('kavita', 'books.example.org', 'https://books.example.org', { kind: 'opds', position: 2 }),
      item('textos', 'Textos', 'https://textos.example', { kind: 'opds', has_login: false, position: 3 }),
    ] } })
  })
  return { fetches, direct }
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
  const server = await connect(page, [book])
  await page.goto('/quire/#/book/' + encodeURIComponent(key))
  await page.getByRole('button', { name: 'Download from catalog' }).click()
  await expect.poll(() => server.fetches.some(path => path.includes(encodeURIComponent('/opds/download/7/epub/')))).toBe(true)
  await expect(page.getByRole('button', { name: 'Read', exact: true })).toBeVisible()
  await expect(page.getByText('The catalog requires valid credentials')).toHaveCount(0)
  expect(server.fetches.every(path => path.startsWith('/catalogs/v1/catalogs/own/fetch'))).toBe(true)
  expect(server.direct).toEqual([])
})
