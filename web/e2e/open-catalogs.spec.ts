import { expect, test, type Page } from '@playwright/test'

test.use({ actionTimeout: 10_000, navigationTimeout: 15_000 })

const feed = `<?xml version="1.0" encoding="utf-8"?>
<feed xmlns="http://www.w3.org/2005/Atom">
  <id>urn:open</id><title>Livres libres</title><updated>2026-11-25T10:00:00Z</updated>
  <entry><id>urn:book</id><title>Le Tour du monde</title><updated>2026-11-25T10:00:00Z</updated>
    <link rel="http://opds-spec.org/acquisition" type="application/epub+zip" href="/book/1.epub"/>
  </entry>
</feed>`

type Item = { id: string; name: string; kind: string; host: string; has_login: boolean; needs_login: boolean; position: number; updated_at: string }
type Options = { held?: { item: Item; url: string }; failing?: boolean; feed?: string }

/** A signed-in Quire server that keeps the account's catalogs and fetches them for the page. */
async function connect(page: Page, options: Options = {}) {
  const saved: { item: Item; url: string }[] = options.held ? [options.held] : []
  const puts: { path: string; body: { name: string; kind: string; url: string; unique_url?: boolean } }[] = []
  const deletes: string[] = []
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
    const url = new URL(request.url())
    const path = url.pathname
    const id = path.split('/').filter(Boolean)[3]
    if (path.endsWith('/fetch')) {
      fetches.push(request.url())
      if (url.searchParams.get('url')?.endsWith('/cover.png'))
        return route.fulfill({ contentType: 'image/png', body: Buffer.from(
          'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==', 'base64',
        ) })
      return options.failing
        ? route.fulfill({ status: 502, json: { detail: 'catalog_unreachable' } })
        : route.fulfill({ contentType: 'application/atom+xml', body: options.feed ?? feed })
    }
    if (request.method() === 'DELETE') {
      deletes.push(id)
      saved.splice(saved.findIndex(entry => entry.item.id === id), 1)
      return route.fulfill({ status: 204 })
    }
    if (request.method() === 'PUT') {
      expect(request.headers()['x-csrf-token']).toBe('test')
      const body = request.postDataJSON()
      puts.push({ path, body })
      const same = saved.find(entry => entry.url === body.url)
      if (same && body.unique_url) return route.fulfill({ status: 409, json: { detail: 'catalog_exists', id: same.item.id } })
      const item = {
        id, name: body.name, kind: body.kind, host: new URL(body.url).origin,
        has_login: false, needs_login: false, position: saved.length, updated_at: '2026-11-25T10:00:00Z',
      }
      saved.push({ item, url: body.url })
      return route.fulfill({ json: item })
    }
    return route.fulfill({ json: { catalogs: saved.map(entry => entry.item) } })
  })
  return { puts, deletes, fetches }
}

const libresName = 'Ebooks libres et gratuits'

test('catalog covers line up even when their titles and authors have different lengths', async ({ page }) => {
  const title = 'A much longer title that needs more space than its neighbour in the catalog grid'
  const authors = 'First Contributor, Second Contributor, Third Contributor, Fourth Contributor'
  const held = {
    item: { id: 'shelf', name: 'Shelf', kind: 'opds', host: 'https://shelf.example', has_login: false, needs_login: false, position: 0, updated_at: '2026-11-25T10:00:00Z' },
    url: 'https://shelf.example/opds/',
  }
  await connect(page, { held, feed: feed.replace('</feed>', `
    <entry><id>urn:long</id><title>${title}</title><updated>2026-11-25T10:00:00Z</updated>
      ${authors.split(', ').map(name => `<author><name>${name}</name></author>`).join('')}
      <link rel="http://opds-spec.org/image/thumbnail" type="image/png" href="https://shelf.example/cover.png"/>
      <link rel="http://opds-spec.org/acquisition" type="application/epub+zip" href="/book/2.epub"/>
    </entry>
  </feed>`) })
  await page.setViewportSize({ width: 2520, height: 1000 })
  await page.goto('/quire/#/catalog')
  const cards = page.locator('.catalog-book')
  await expect(cards).toHaveCount(2)
  const image = cards.last().locator('img')
  await expect.poll(() => image.evaluate(element => (element as HTMLImageElement).naturalWidth)).toBeGreaterThan(0)
  const covers = await cards.locator('.catalog-cover').evaluateAll(elements => elements.map(element => {
    const { top, width, height } = element.getBoundingClientRect()
    return { top, width, height }
  }))
  expect(covers[0]).toEqual(covers[1])
  await cards.last().click()
  await expect(page.getByRole('heading', { name: title, exact: true })).toBeVisible()
  await expect(page.getByText(authors, { exact: true })).toBeVisible()
})

test('an open catalog is checked, added in one click and opens through the server', async ({ page }) => {
  const server = await connect(page)
  await page.goto('/quire/#/catalog')
  const open = page.getByRole('region', { name: 'Open catalogs' })
  await expect(open).toContainText('Adding one lets your Quire server contact that catalog’s site for you')
  const libres = open.getByRole('listitem').filter({ hasText: libresName })
  await expect(libres).toContainText('French')
  await expect(libres).toContainText('Public domain')
  await expect(libres.getByRole('link', { name: 'ebooksgratuits.com' })).toHaveAttribute('href', 'https://www.ebooksgratuits.com/')
  // Project Gutenberg leads the list, public domain in the US only.
  await expect(open.getByRole('listitem').first()).toContainText('Project Gutenberg')
  await expect(open.getByRole('listitem').first()).toContainText('Public domain in the US')

  await libres.getByRole('button', { name: 'Add ' + libresName }).click()
  // The new catalog opens at once, fetched by the server, not the browser, and takes the focus.
  const heading = page.getByRole('heading', { name: 'Livres libres' })
  await expect(heading).toBeFocused()
  await expect(page.getByText('Le Tour du monde')).toBeVisible()
  await expect(page.getByRole('status').filter({ hasText: libresName + ' added.' })).toBeVisible()
  expect(server.puts).toHaveLength(1)
  expect(server.puts[0].body).toEqual({ name: libresName, kind: 'opds', url: 'https://www.ebooksgratuits.com/opds/', unique_url: true })
  expect(server.fetches.some(url => url.includes(server.puts[0].path))).toBe(true)
  expect(server.deletes).toEqual([])

  // While browsing, the list waits behind the other ways to add a catalog, marked added.
  await expect(page.getByRole('region', { name: 'Open catalogs' })).toBeHidden()
  await page.getByText('Use a different catalog').click()
  const listed = page.getByRole('region', { name: 'Open catalogs' }).getByRole('listitem').filter({ hasText: libresName })
  await expect(listed).toContainText('Added')
  await expect(listed.getByRole('button', { name: 'Add ' + libresName })).toHaveCount(0)

  // It can be removed again: from the account, so from the reader's other devices too.
  await listed.getByRole('button', { name: 'Remove ' + libresName }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'Remove' }).click()
  await expect(page.getByRole('status').filter({ hasText: libresName + ' removed.' })).toBeVisible()
  expect(server.deletes).toEqual([server.puts[0].path.split('/').pop()])
  await page.reload()
  const again = page.getByRole('region', { name: 'Open catalogs' }).getByRole('listitem').filter({ hasText: libresName })
  await expect(again.getByRole('button', { name: 'Add ' + libresName })).toBeEnabled()
})

test('a catalog that does not open is removed again and the reason shown', async ({ page }) => {
  const server = await connect(page, { failing: true })
  await page.goto('/quire/#/catalog')
  const libres = page.getByRole('region', { name: 'Open catalogs' }).getByRole('listitem').filter({ hasText: libresName })
  await libres.getByRole('button', { name: 'Add ' + libresName }).click()
  await expect(libres.getByRole('alert')).toHaveText('Quire could not reach this catalog. Try again later.')
  await expect(libres.getByRole('button', { name: 'Add ' + libresName })).toBeEnabled()
  expect(server.deletes).toHaveLength(1)
  expect(server.deletes[0]).toBe(server.puts[0].path.split('/').pop())
  await expect(page.getByRole('heading', { name: 'Connect a catalog' })).toBeVisible()
})

test('an address the account already holds is opened, not saved twice', async ({ page }) => {
  const held = {
    item: { id: 'held', name: 'ELG', kind: 'opds', host: 'https://www.ebooksgratuits.com', has_login: false, needs_login: false, position: 0, updated_at: '2026-11-25T10:00:00Z' },
    url: 'https://www.ebooksgratuits.com/opds/',
  }
  const server = await connect(page, { held })
  await page.goto('/quire/#/catalog')
  await page.getByText('Use a different catalog').click()
  const libres = page.getByRole('region', { name: 'Open catalogs' }).getByRole('listitem').filter({ hasText: libresName })
  await libres.getByRole('button', { name: 'Add ' + libresName }).click()
  await expect(page.getByRole('status').filter({ hasText: 'ELG added.' })).toBeVisible()
  await expect(libres).toContainText('Added')
  expect(server.puts).toHaveLength(1)
  expect(server.deletes).toEqual([])
  expect(server.fetches.every(url => url.includes('/held/'))).toBe(true)
})

test('a saved catalog that is not in the open list can be removed where it opens', async ({ page }) => {
  const held = {
    item: { id: 'shelf', name: 'Old shelf', kind: 'opds', host: 'https://shelf.example', has_login: false, needs_login: false, position: 0, updated_at: '2026-11-25T10:00:00Z' },
    url: 'https://shelf.example/opds/',
  }
  const server = await connect(page, { held })
  await page.goto('/quire/#/catalog')
  await expect(page.getByRole('heading', { name: 'Livres libres' })).toBeVisible()
  await page.getByRole('button', { name: 'Remove Old shelf' }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'Remove' }).click()
  await expect(page.getByRole('status').filter({ hasText: 'Old shelf removed.' })).toBeVisible()
  expect(server.deletes).toEqual(['shelf'])
  await page.reload()
  await expect(page.getByRole('heading', { name: 'Connect a catalog' })).toBeVisible()
})

test('the reader’s language comes first', async ({ page }) => {
  await connect(page)
  await page.addInitScript(() => localStorage.setItem('quire-ui:language', 'fr'))
  await page.goto('/quire/#/catalog')
  const items = page.locator('.open-catalogs li')
  await expect(items.first()).toContainText('Français')
})
