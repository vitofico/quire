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

type Server = {
  /** What `/ai/v1/config` adds. */
  config?: Record<string, unknown>
  lookup?: (body: Record<string, unknown>) => unknown
  tidy?: (body: Record<string, unknown>) => unknown
  /** The status a drawn cover's image answers with. */
  coverStatus?: number
}

async function connect(page: Page, server: Server = {}) {
  const calls: { path: string; body?: Record<string, unknown> }[] = []
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
        return route.fulfill({ json: { configured: true, details_supported: true, providers: [], ...server.config } })
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
    const body = request.postDataJSON()
    if (pathname === '/details/v1/lookup') return route.fulfill({ json: server.lookup?.(body) ?? answer('Dune') })
    if (pathname === '/details/v1/tidy' && server.tidy) return route.fulfill({ json: server.tidy(body) })
    if (pathname === '/details/v1/tidy') {
      if (!tidyAllowed) {
        tidyAllowed = true
        return route.fulfill({ status: 429, headers: { 'Retry-After': '60' }, json: { detail: 'monthly_ai_quota_exhausted' } })
      }
      return route.fulfill({ json: answer('Dune, tidied again') })
    }
    if (server.coverStatus) return route.fulfill({ status: server.coverStatus, json: { detail: 'unavailable' } })
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

  await page.getByRole('button', { name: 'Use original' }).click()
  const improved = page.getByRole('button', { name: 'Use improved details' })
  await expect(improved).toBeVisible()
  await expect(title).toHaveText('dune_frank_herbert_(retail)')
  await expect(page.getByText('Generated cover')).toHaveCount(0)
  await expect(page.getByText('Tidied by AI')).toHaveCount(0)

  // The choice and the found details live in this browser: no second lookup.
  await page.reload()
  await expect(title).toHaveText('dune_frank_herbert_(retail)')
  await page.getByRole('button', { name: 'Use improved details' }).click()
  await expect(title).toHaveText('Dune')
  expect(calls).toHaveLength(2)

  const improve = page.getByRole('button', { name: 'Improve details' })
  await improve.click()
  await expect(page.getByRole('alert')).toHaveText('AI limit reached. Try again after the quota resets.')
  await expect(title).toHaveText('Dune')
  await improve.click()
  // Nothing is applied before the reader says so.
  const sheet = page.getByRole('dialog', { name: 'Improve details' })
  await expect(sheet.getByRole('checkbox', { name: 'Title' })).toBeChecked()
  await expect(sheet.getByRole('checkbox')).toHaveCount(1)
  await expect(title).toHaveText('Dune')
  await sheet.getByRole('button', { name: 'Apply' }).click()
  await expect(sheet).toHaveCount(0)
  await expect(page.getByText('Updated: title.')).toBeVisible()
  await expect(improve).toBeFocused()
  await expect(title).toHaveText('Dune, tidied again')
  // Same cover address: the stored image is kept, not fetched again.
  expect(calls.map(call => call.path)).toEqual(['/details/v1/lookup', coverPath, '/details/v1/tidy', '/details/v1/tidy'])

  await page.getByRole('link', { name: 'Back to library' }).click()
  const entry = page.getByRole('button', { name: /Dune, tidied again/ })
  await expect(entry.locator('img')).toBeVisible()
  // The tile says, quietly, that its cover was drawn and its title tidied.
  await expect(entry).toHaveAccessibleName(/Generated cover, Tidied by AI/)
})

test('book facts use even columns on desktop and stack on a narrow phone', async ({ page }) => {
  await connect(page, {
    lookup: () => {
      const details = answer('Dune')
      return { ...details, book: { ...details.book, publisher: 'Pearson Education and Professional Publishing', isbn: '9780136462989' } }
    },
  })
  await page.addInitScript(() => localStorage.setItem('quire-ui:language', 'it'))
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('/quire/')
  await page.locator('input[type=file]').setInputFiles(messyBook())

  const facts = page.locator('.book-detail .facts')
  const cells = facts.locator(':scope > div')
  await expect(facts.getByText('Prima edizione', { exact: true })).toBeVisible()
  await expect(facts.getByText('Da Open Library', { exact: true })).toBeVisible()
  await expect(cells).toHaveCount(5)
  const geometry = () => cells.evaluateAll(items => items.map(cell => {
    const box = cell.getBoundingClientRect()
    const label = cell.querySelector('dt')!.getBoundingClientRect()
    const value = cell.querySelector('dd')!.getBoundingClientRect()
    return { x: Math.round(box.left), y: Math.round(box.top), width: Math.round(box.width),
      stacked: label.bottom <= value.top && label.left === value.left,
      fits: [...cell.children].every(item => item.scrollWidth <= item.clientWidth + 1) }
  }))

  const desktop = await geometry()
  expect(new Set(desktop.map(cell => cell.width)).size).toBe(1)
  expect(new Set(desktop.slice(0, 4).map(cell => cell.y)).size).toBe(1)
  expect(desktop[4].x).toBe(desktop[0].x)
  expect(desktop[4].y).toBeGreaterThan(desktop[0].y)
  expect(desktop.every(cell => cell.stacked && cell.fits)).toBe(true)

  await page.setViewportSize({ width: 320, height: 844 })
  const phone = await geometry()
  expect(new Set(phone.map(cell => cell.x)).size).toBe(1)
  expect(phone.every(cell => cell.stacked && cell.fits)).toBe(true)
})

test('details labels and controls stay readable, focusable and inside a phone screen', async ({ page, browserName }) => {
  await connect(page)
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/quire/')
  await page.locator('input[type=file]').setInputFiles(messyBook())
  await expect(page.getByText('Generated cover')).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1)).toBe(true)

  const tidy = page.getByRole('button', { name: 'Improve details' })
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

function coveredBook() {
  const files = {
    'META-INF/container.xml': strToU8('<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="book.opf"/></rootfiles></container>'),
    'book.opf': strToU8('<package xmlns="http://www.idpf.org/2007/opf" version="3.0" xmlns:dc="http://purl.org/dc/elements/1.1/" unique-identifier="id"><metadata><dc:title>Dune</dc:title><dc:creator>Frank Herbert</dc:creator><dc:identifier id="id">urn:uuid:0b6c2e1a-7f3d-4c55-8e21-9a4f0c3b7d12</dc:identifier><dc:language>en</dc:language></metadata><manifest><item id="cover" href="cover.png" media-type="image/png" properties="cover-image"/><item id="one" href="one.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="one"/></spine></package>'),
    'cover.png': new Uint8Array(png),
    'one.xhtml': strToU8('<html xmlns="http://www.w3.org/1999/xhtml"><head><title>One</title></head><body><p>Arrakis.</p></body></html>'),
  }
  return { name: 'dune.epub', mimeType: 'application/epub+zip', buffer: Buffer.from(zipSync(files)) }
}

const blurb = 'A duke’s son inherits a desert planet. '.repeat(10).trim()
/** A book that has its own cover: the server keeps it unless the reader chooses another. */
function improvement(body: Record<string, unknown>, fields: Record<string, unknown> = {}) {
  const choice = body.cover_choice as string | undefined
  const drawn = choice === 'draw' || choice === 'paint'
  return {
    key,
    book: { ...(body.book as object), ...fields },
    origins: { title: 'file', authors: 'file', ...Object.fromEntries(Object.keys(fields).map(name => [name, 'ai'])), cover: drawn ? 'ai' : 'file' },
    cover: drawn ? { source: 'generated', url: coverPath, generated: true, painting: choice === 'paint' } : { source: 'file', generated: false },
    tidy: Object.keys(fields).length ? 'done' : 'not_needed', cached: false, openlibrary_url: null,
  }
}

test('the reader keeps only the changes they tick, and a chosen cover replaces the book\'s own until they pick the original', async ({ page }) => {
  const calls = await connect(page, {
    config: { cover_choice_supported: true },
    lookup: body => improvement(body),
    tidy: body => improvement(body, { description: blurb, series_name: 'Dune Chronicles', series_index: 1 }),
  })
  await page.goto('/quire/')
  await page.locator('input[type=file]').setInputFiles(coveredBook())
  const title = page.getByRole('heading', { level: 1 })
  await expect(title).toHaveText('Dune')
  await expect.poll(() => calls.filter(call => call.path === '/details/v1/lookup').length).toBe(1)
  const own = await page.locator('.detail-cover img').getAttribute('src')

  const improve = page.getByRole('button', { name: 'Improve details' })
  await improve.click()
  const sheet = page.getByRole('dialog', { name: 'Improve details' })
  await expect(sheet).toBeVisible()
  await expect.poll(() => page.evaluate(() => !!document.activeElement?.closest('dialog'))).toBe(true)
  // Only what changes is offered, ticked.
  await expect(sheet.getByRole('checkbox', { name: 'Title' })).toHaveCount(0)
  await expect(sheet.getByRole('checkbox', { name: 'Series' })).toBeChecked()
  await expect(sheet.getByRole('checkbox', { name: 'Description' })).toBeChecked()
  const more = sheet.getByRole('button', { name: 'Show more' })
  await more.click()
  await expect(sheet.getByRole('button', { name: 'Show less' })).toHaveAttribute('aria-expanded', 'true')
  await expect(sheet.getByRole('radio', { name: /A painted cover/ })).toHaveCount(0)
  await expect(sheet.getByRole('radio', { name: 'Keep this book’s cover' })).toBeChecked()

  await sheet.getByRole('radio', { name: 'Quire’s drawn cover' }).check()
  await expect(sheet.locator('.compare').last().getByText('Generated cover')).toBeVisible()
  await sheet.getByRole('checkbox', { name: 'Series' }).uncheck()
  await sheet.getByRole('button', { name: 'Apply' }).click()
  await expect(sheet).toHaveCount(0)
  await expect(page.getByRole('status')).toHaveText('Updated: description. New cover.')
  await expect(improve).toBeFocused()

  await expect(page.getByText(blurb)).toBeVisible()
  await expect(page.getByText('Dune Chronicles')).toHaveCount(0)
  await expect(page.locator('.detail-cover').getByText('Generated cover')).toBeVisible()
  await expect.poll(() => page.locator('.detail-cover img').getAttribute('src')).not.toBe(own)

  const tidyCall = calls.find(call => call.path === '/details/v1/tidy')!
  expect(tidyCall.body).not.toHaveProperty('cover_choice')
  expect(calls.filter(call => call.path === '/details/v1/lookup').at(-1)!.body).toMatchObject({ cover_choice: 'draw', cover: 'file' })

  // The original brings everything back, the cover too.
  await page.getByRole('button', { name: 'Use original' }).click()
  await expect(page.getByRole('button', { name: 'Use improved details' })).toBeVisible()
  await expect(page.getByText(blurb)).toHaveCount(0)
  await expect(page.locator('.detail-cover').getByText('Generated cover')).toHaveCount(0)
  await expect.poll(() => page.locator('.detail-cover img').count()).toBe(1)
})

test('a book that needs nothing says so and opens no preview', async ({ page }) => {
  const calls = await connect(page, { lookup: body => improvement(body), tidy: body => improvement(body) })
  await page.goto('/quire/')
  await page.locator('input[type=file]').setInputFiles(coveredBook())
  await page.getByRole('button', { name: 'Improve details' }).click()
  await expect(page.getByRole('status')).toHaveText('These details already look right.')
  await expect(page.getByRole('dialog')).toHaveCount(0)
  expect(calls.filter(call => call.path === '/details/v1/tidy')).toHaveLength(1)
})

test('a painted cover is asked for only on apply and the preview fits a phone', async ({ page }) => {
  const calls = await connect(page, {
    config: { cover_choice_supported: true, cover_painting_supported: true },
    lookup: body => improvement(body),
    tidy: body => improvement(body),
  })
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/quire/')
  await page.locator('input[type=file]').setInputFiles(coveredBook())
  const improve = page.getByRole('button', { name: 'Improve details' })
  await improve.click()
  const sheet = page.getByRole('dialog', { name: 'Improve details' })
  // Nothing to tick, but the cover can still be chosen.
  await expect(sheet.getByText('These details already look right.')).toBeVisible()
  await sheet.getByRole('radio', { name: /A painted cover/ }).check()
  await expect(sheet.getByText('Painting takes a while: the drawn cover shows until the painting arrives.')).toBeVisible()
  await expect(sheet.locator('.compare img')).toHaveCount(2)
  const box = await sheet.boundingBox()
  expect(box).toMatchObject({ x: 0, width: 390 })
  expect(await sheet.evaluate(element => element.scrollWidth <= element.clientWidth + 1)).toBe(true)
  const choices = () => calls.filter(call => call.path === '/details/v1/lookup').map(call => call.body?.cover_choice)
  expect(choices()).toEqual([undefined, 'draw'])
  await sheet.getByRole('button', { name: 'Apply' }).click()
  await expect(page.getByRole('status')).toHaveText('New cover. A painted cover is on its way.')
  expect(choices()).toEqual([undefined, 'draw', 'paint'])

  // Escape closes a preview without keeping anything, and focus comes back.
  await improve.click()
  await expect(sheet).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(sheet).toHaveCount(0)
  await expect(improve).toBeFocused()
})

test('a cover that fails to download does not hold back the text changes', async ({ page }) => {
  const calls = await connect(page, {
    config: { cover_choice_supported: true },
    lookup: body => improvement(body),
    tidy: body => improvement(body, { description: 'A desert planet.' }),
    coverStatus: 500,
  })
  await page.goto('/quire/')
  await page.locator('input[type=file]').setInputFiles(coveredBook())
  await expect.poll(() => calls.filter(call => call.path === '/details/v1/lookup').length).toBe(1)
  await page.getByRole('button', { name: 'Improve details' }).click()
  const sheet = page.getByRole('dialog', { name: 'Improve details' })
  await sheet.getByRole('radio', { name: 'Quire’s drawn cover' }).check()
  await expect(sheet.getByRole('alert')).toBeVisible()
  await sheet.getByRole('button', { name: 'Apply' }).click()
  await expect(sheet).toHaveCount(0)
  await expect(page.getByRole('status')).toHaveText('Updated: description.')
  await expect(page.getByText('A desert planet.')).toBeVisible()
  // The book's own cover stays, and the drawn one is not asked for again.
  await expect(page.locator('.detail-cover img')).toHaveCount(1)
  await expect(page.locator('.detail-cover').getByText('Generated cover')).toHaveCount(0)
  await page.reload()
  await expect(page.getByText('A desert planet.')).toBeVisible()
  expect(calls.filter(call => call.body?.cover_choice).map(call => call.body?.cover_choice)).toEqual(['draw'])
})
