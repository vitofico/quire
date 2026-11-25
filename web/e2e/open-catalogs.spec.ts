import { expect, test, type Page } from '@playwright/test'

test.use({ actionTimeout: 10_000, navigationTimeout: 15_000 })

const feed = `<?xml version="1.0" encoding="utf-8"?>
<feed xmlns="http://www.w3.org/2005/Atom">
  <id>urn:open</id><title>Livres libres</title><updated>2026-11-25T10:00:00Z</updated>
  <entry><id>urn:book</id><title>Le Tour du monde</title><updated>2026-11-25T10:00:00Z</updated>
    <link rel="http://opds-spec.org/acquisition" type="application/epub+zip" href="/book/1.epub"/>
  </entry>
</feed>`

/** A signed-in Quire server that keeps the account's catalogs and fetches them for the page. */
async function connect(page: Page) {
  const saved: object[] = []
  const puts: { path: string; body: { name: string; kind: string; url: string } }[] = []
  const fetches: string[] = []
  await page.addInitScript(() => { Reflect.deleteProperty(Navigator.prototype, 'serviceWorker') })
  await page.route('**/quire/v1/bootstrap', route => route.fulfill({ json: {
    version: 1, sync: false, ai: false, aiAvailable: false, login: 'native', password: true,
    oidc: false, catalogUrl: null, accountManagement: null, catalogSync: true, catalogProxy: true,
  } }))
  await page.route('**/auth/v1/me', route => route.fulfill({ json: {
    version: 1, user: { id: 'reader', backend: 'native' }, csrf_token: 'test',
    capabilities: { progress_metadata_sync: false, ai_enabled: false },
  } }))
  await page.route('**/convert/v1/**', route => route.fulfill({ status: 404, json: { detail: 'Not Found' } }))
  await page.route('**/catalogs/v1/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (path.endsWith('/fetch')) {
      fetches.push(request.url())
      return route.fulfill({ contentType: 'application/atom+xml', body: feed })
    }
    if (request.method() === 'PUT') {
      expect(request.headers()['x-csrf-token']).toBe('test')
      const body = request.postDataJSON()
      puts.push({ path, body })
      const item = {
        id: path.split('/').pop(), name: body.name, kind: body.kind, host: new URL(body.url).origin,
        has_login: false, needs_login: false, position: saved.length, updated_at: '2026-11-25T10:00:00Z',
      }
      saved.push(item)
      return route.fulfill({ json: item })
    }
    return route.fulfill({ json: { catalogs: saved } })
  })
  return { puts, fetches }
}

test('an open catalog is added in one click and opens through the server', async ({ page }) => {
  const server = await connect(page)
  await page.goto('/quire/#/catalog')
  const open = page.getByRole('region', { name: 'Open catalogs' })
  await expect(open).toContainText('Adding one lets your Quire server contact that catalog’s site for you.')
  const romande = open.getByRole('listitem').filter({ hasText: 'Bibliothèque numérique romande' })
  await expect(romande).toContainText('French')
  await expect(romande).toContainText('Public domain')

  await romande.getByRole('button', { name: 'Add Bibliothèque numérique romande' }).click()
  await expect(romande).toContainText('Added')
  await expect(romande.getByRole('button')).toHaveCount(0)
  expect(server.puts).toHaveLength(1)
  expect(server.puts[0].body).toEqual({
    name: 'Bibliothèque numérique romande', kind: 'opds', url: 'https://ebooks-bnr.com/opds/',
  })

  // The new catalog opens at once, fetched by the server, not the browser.
  await expect(page.getByRole('heading', { name: 'Livres libres' })).toBeVisible()
  await expect(page.getByText('Le Tour du monde')).toBeVisible()
  expect(server.fetches.some(url => url.includes(server.puts[0].path))).toBe(true)
})

test('the reader’s language comes first', async ({ page }) => {
  await connect(page)
  await page.addInitScript(() => localStorage.setItem('quire-ui:language', 'fr'))
  await page.goto('/quire/#/catalog')
  const items = page.locator('.open-catalogs li')
  await expect(items.first()).toContainText('Français')
})
