import { expect, test, type Page } from '@playwright/test'
import { strToU8, zipSync } from 'fflate'

test.use({ actionTimeout: 10_000, navigationTimeout: 15_000 })

const key = 'a'.repeat(64)
const coverPath = `/details/v1/covers/${key}?v=e1`
// The smallest valid PNG: one opaque pixel.
const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==', 'base64')

function messyBook() {
  const files = {
    'META-INF/container.xml': strToU8('<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="book.opf"/></rootfiles></container>'),
    'book.opf': strToU8('<package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" unique-identifier="id"><metadata><dc:title>dune_frank_herbert_(retail)</dc:title><dc:creator>Herbert, Frank</dc:creator><dc:identifier id="id">urn:uuid:6d1a3f4e-2b1c-4d7e-9a55-0c2f61b8e901</dc:identifier><dc:language>en</dc:language></metadata><manifest><item id="one" href="one.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="one"/></spine></package>'),
    'one.xhtml': strToU8('<html xmlns="http://www.w3.org/1999/xhtml"><head><title>One</title></head><body><p>Arrakis.</p></body></html>'),
  }
  return { name: 'dune_frank_herbert_(retail).epub', mimeType: 'application/epub+zip', buffer: Buffer.from(zipSync(files)) }
}

function answer(title: string) {
  return {
    key,
    book: {
      title, authors: ['Frank Herbert'], series_name: null, series_index: null,
      description: 'A duke’s son inherits a desert planet.', subjects: ['Science fiction'],
      language: 'en', isbn: null, year: 1965, publisher: null,
    },
    origins: { title: 'ai', authors: 'ai', description: 'ai_written', subjects: 'openlibrary', language: 'file', year: 'openlibrary', cover: 'ai' },
    cover: { source: 'generated', url: coverPath, generated: true },
    tidy: 'done', cached: false, openlibrary_url: 'https://openlibrary.org/works/OL893415W',
  }
}

async function connect(page: Page) {
  const calls: { path: string; body?: unknown }[] = []
  let tidyAllowed = false
  await page.addInitScript(() => { Reflect.deleteProperty(Navigator.prototype, 'serviceWorker') })
  await page.route('**/quire/v1/bootstrap', route => route.fulfill({ json: {
    version: 1, sync: false, ai: true, aiAvailable: true, login: 'native', password: true,
    oidc: false, catalogUrl: null, accountManagement: null,
  } }))
  await page.route('**/auth/v1/me', route => route.fulfill({ json: {
    version: 1, user: { id: 'reader', backend: 'native' }, csrf_token: 'test',
    capabilities: { progress_metadata_sync: false, ai_enabled: true, book_details: true },
  } }))
  await page.route('**/ai/v1/**', route => {
    switch (new URL(route.request().url()).pathname) {
      case '/ai/v1/config':
        return route.fulfill({ json: { configured: true, details_supported: true, providers: [] } })
      case '/ai/v1/preferences':
        return route.fulfill({ json: { ai_enabled: true, provider_id: 'default', style: { tone: 'neutral', language: 'auto' } } })
      default:
        return route.fulfill({ status: 404 })
    }
  })
  await page.route('**/details/v1/**', route => {
    const request = route.request()
    const { pathname, search } = new URL(request.url())
    calls.push({ path: pathname + search, body: request.postDataJSON() ?? undefined })
    expect(request.headers()['x-csrf-token']).toBe('test')
    if (pathname === '/details/v1/lookup') return route.fulfill({ json: answer('Dune') })
    if (pathname === '/details/v1/tidy') {
      if (!tidyAllowed) {
        tidyAllowed = true
        return route.fulfill({ status: 429, headers: { 'Retry-After': '60' }, json: { detail: 'monthly_ai_quota_exhausted' } })
      }
      return route.fulfill({ json: answer('Dune, tidied again') })
    }
    return route.fulfill({ contentType: 'image/png', headers: { 'X-Quire-Cover': 'generated' }, body: png })
  })
  return calls
}

test('a messy book gets found details once, labelled, kept offline, and the reader can tidy or keep the original', async ({ page }) => {
  const calls = await connect(page)
  await page.goto('/quire/')
  await page.locator('input[type=file]').setInputFiles(messyBook())

  const title = page.getByRole('heading', { level: 1 })
  await expect(title).toHaveText('Dune')
  await expect(page.getByText('Frank Herbert')).toBeVisible()
  await expect(page.getByText('Generated cover')).toBeVisible()
  await expect(page.locator('.detail-cover img')).toBeVisible()
  await expect(page.getByText('Written by AI')).toBeVisible()
  await expect(page.getByText('Tidied by AI')).toHaveCount(2)
  await expect(page.getByText('From Open Library')).toHaveCount(2)
  await expect(page.getByText('1965')).toBeVisible()
  await expect(page.getByRole('link', { name: 'View on Open Library' })).toHaveAttribute('href', 'https://openlibrary.org/works/OL893415W')
  expect(calls.map(call => call.path)).toEqual(['/details/v1/lookup', coverPath])
  expect(calls[0].body).toMatchObject({
    // Without sync the book stays in this browser, so, as for insights, only its content hash names it.
    identity: { metadata_id: null, content_hash: expect.any(String) },
    book: { title: 'dune_frank_herbert_(retail)', authors: ['Herbert, Frank'], description: null, year: null },
    source: 'file', cover: null,
  })

  const original = page.getByRole('button', { name: 'Use original' })
  await original.click()
  await expect(original).toHaveAttribute('aria-pressed', 'true')
  await expect(title).toHaveText('dune_frank_herbert_(retail)')
  await expect(page.getByText('Generated cover')).toHaveCount(0)
  await expect(page.getByText('Tidied by AI')).toHaveCount(0)

  // The choice and the found details live in this browser: no second lookup.
  await page.reload()
  await expect(title).toHaveText('dune_frank_herbert_(retail)')
  await page.getByRole('button', { name: 'Use original' }).click()
  await expect(title).toHaveText('Dune')
  expect(calls).toHaveLength(2)

  const tidy = page.getByRole('button', { name: 'Tidy details' })
  await tidy.click()
  await expect(page.getByRole('alert')).toHaveText('AI limit reached. Try again after the quota resets.')
  await expect(title).toHaveText('Dune')
  await tidy.click()
  await expect(page.getByText('Details tidied.')).toBeVisible()
  await expect(title).toHaveText('Dune, tidied again')
  // Same cover address: the stored image is kept, not fetched again.
  expect(calls.map(call => call.path)).toEqual(['/details/v1/lookup', coverPath, '/details/v1/tidy', '/details/v1/tidy'])

  await page.getByRole('link', { name: 'Back to library' }).click()
  const entry = page.getByRole('button', { name: /Dune, tidied again/ })
  await expect(entry.locator('img')).toBeVisible()
  // The tile says, quietly, that its cover was drawn and its title tidied.
  await expect(entry).toHaveAccessibleName(/Generated cover, Tidied by AI/)
})

test('details labels and controls stay readable, focusable and inside a phone screen', async ({ page, browserName }) => {
  await connect(page)
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/quire/')
  await page.locator('input[type=file]').setInputFiles(messyBook())
  await expect(page.getByText('Generated cover')).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1)).toBe(true)

  const tidy = page.getByRole('button', { name: 'Tidy details' })
  await tidy.focus()
  await page.keyboard.press(browserName === 'webkit' && process.platform === 'darwin' ? 'Alt+Tab' : 'Tab')
  const original = page.getByRole('button', { name: 'Use original' })
  await expect(original).toBeFocused()
  expect(await original.evaluate(button => getComputedStyle(button).outlineStyle)).not.toBe('none')

  for (const theme of ['paper', 'sage', 'ink', 'midnight']) {
    const ratios = await page.locator('.origin').evaluateAll((labels, name) => {
      document.documentElement.dataset.theme = name
      const channels = (value: string) => value.match(/[0-9.]+/g)!.slice(0, 3).map(Number)
      const luminance = (value: string) => {
        const [r, g, b] = channels(value).map(part => {
          const c = part / 255
          return c <= .04045 ? c / 12.92 : ((c + .055) / 1.055) ** 2.4
        })
        return .2126 * r + .7152 * g + .0722 * b
      }
      const background = (element: Element | null): string => {
        for (; element; element = element.parentElement) {
          const color = getComputedStyle(element).backgroundColor
          if (!/rgba\(.*, 0\)|transparent/.test(color)) return color
        }
        return 'rgb(255, 255, 255)'
      }
      return labels.map(label => {
        const levels = [luminance(getComputedStyle(label).color), luminance(background(label))].sort((a, b) => b - a)
        return (levels[0] + .05) / (levels[1] + .05)
      })
    }, theme)
    expect(ratios.length).toBeGreaterThan(0)
    for (const ratio of ratios) expect(ratio, theme).toBeGreaterThanOrEqual(4.5)
  }
})
