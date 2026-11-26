import { expect, test, type Page } from '@playwright/test'
import { strToU8, zipSync } from 'fflate'

test.use({ actionTimeout: 10_000, navigationTimeout: 15_000 })

type Catalog = { id: string; name: string; kind: string; host: string; has_login: boolean; needs_login: boolean; position: number; updated_at: string }
type Preferences = { ai_enabled: boolean; provider_id: string; style: { tone: string; language: string } }

const catalog = (id: string, name: string, host: string, position: number): Catalog => ({
  id, name, kind: 'opds', host, has_login: false, needs_login: false, position, updated_at: '2026-11-25T10:00:00Z',
})

/** A signed-in Quire account with sync, AI and saved catalogs, so every settings section shows. */
async function signedIn(page: Page, options: { failPreferences?: boolean } = {}) {
  const server = {
    preferences: { ai_enabled: false, provider_id: 'default', style: { tone: 'neutral', language: 'auto' } } as Preferences,
    puts: [] as Preferences[],
    orders: [] as string[][],
    deletes: [] as string[],
    catalogs: [
      catalog('6c1f6a4e-0000-4000-8000-000000000001', 'Project Gutenberg', 'https://gutenberg.example.org', 0),
      catalog('6c1f6a4e-0000-4000-8000-000000000002', 'Bibliothèque nationale numérique des livres libres', 'https://livres.example.fr', 1),
      catalog('6c1f6a4e-0000-4000-8000-000000000003', 'Home shelf', 'https://books.example.org', 2),
    ],
  }
  await page.addInitScript(() => { Reflect.deleteProperty(Navigator.prototype, 'serviceWorker') })
  await page.route('**/quire/v1/bootstrap', route => route.fulfill({ json: {
    version: 1, sync: true, ai: true, aiAvailable: true, login: 'native', password: true, oidc: false,
    catalogUrl: null, accountManagement: null, catalogSync: true, catalogProxy: true,
  } }))
  await page.route('**/auth/v1/me', route => route.fulfill({ json: {
    version: 1, user: { id: 'reader@example.test', backend: 'native' }, csrf_token: 'test',
    capabilities: { progress_metadata_sync: true, ai_enabled: true },
  } }))
  await page.route('**/convert/v1/**', route => route.fulfill({ status: 404, json: { detail: 'Not Found' } }))
  await page.route('**/library/v1/**', route => route.fulfill({ json: { items: [] } }))
  await page.route('**/sync/v1/**', route => route.fulfill({ json: { items: [], next_cursor: '0', has_more: false } }))
  await page.route('**/ai/v1/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (path === '/ai/v1/config') return route.fulfill({ json: {
      configured: true, progress_supported: true, details_supported: false, sources_enabled: [], daily_budget: 10, regen_daily_limit: 2,
      providers: [
        { id: 'default', label: 'Default', base_url_host: 'ai.example.org', model_id: 'model-a' },
        { id: 'fast', label: 'Fast', base_url_host: 'fast.example.org', model_id: 'model-b' },
        { id: 'careful', label: 'Careful', base_url_host: 'careful.example.org', model_id: 'model-c' },
      ],
    } })
    if (path === '/ai/v1/preferences' && request.method() === 'PUT') {
      const body = request.postDataJSON()
      server.puts.push(body)
      if (options.failPreferences) return route.fulfill({ status: 503, json: { detail: 'unavailable' } })
      server.preferences = {
        ai_enabled: body.ai_enabled ?? server.preferences.ai_enabled,
        provider_id: body.provider_id ?? server.preferences.provider_id,
        style: body.style ?? server.preferences.style,
      }
      return route.fulfill({ json: server.preferences })
    }
    if (path === '/ai/v1/preferences') return route.fulfill({ json: server.preferences })
    return route.fulfill({ status: 404, json: { detail: 'not_cached' } })
  })
  await page.route('**/catalogs/v1/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const list = () => route.fulfill({ json: { catalogs: [...server.catalogs].sort((a, b) => a.position - b.position) } })
    if (path === '/catalogs/v1/order') {
      const { ids } = request.postDataJSON() as { ids: string[] }
      server.orders.push(ids)
      server.catalogs.forEach(c => { c.position = ids.indexOf(c.id) })
      return list()
    }
    if (request.method() === 'DELETE') {
      const id = path.split('/').pop()!
      server.deletes.push(id)
      server.catalogs = server.catalogs.filter(c => c.id !== id)
      return route.fulfill({ status: 204 })
    }
    return list()
  })
  return server
}

function book() {
  const files = {
    'META-INF/container.xml': strToU8('<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="book.opf"/></rootfiles></container>'),
    'book.opf': strToU8('<package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" unique-identifier="id"><metadata><dc:title>Settings Fixture</dc:title><dc:identifier id="id">urn:uuid:5b7d0a52-23f4-4f0e-9a5f-0c1d2e3f4a5b</dc:identifier><dc:language>en</dc:language></metadata><manifest><item id="one" href="one.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="one"/></spine></package>'),
    'one.xhtml': strToU8('<html xmlns="http://www.w3.org/1999/xhtml"><head><title>One</title></head><body><p>Settings fixture text.</p></body></html>'),
  }
  return { name: 'settings.epub', mimeType: 'application/epub+zip', buffer: Buffer.from(zipSync(files)) }
}

const theme = (page: Page) => page.evaluate(() => document.documentElement.dataset.theme)

test('a theme picked in settings applies at once, survives a reload, and System follows the device', async ({ page }) => {
  await page.emulateMedia({ colorScheme: 'light' })
  await page.goto('/quire/#/settings')
  const mode = page.getByRole('group', { name: 'Mode' })
  const dark = page.getByRole('group', { name: 'Dark theme' })
  const light = page.getByRole('group', { name: 'Light theme' })
  await expect(mode.getByRole('radio', { name: 'System' })).toBeChecked()
  expect(await theme(page)).toBe('paper')

  await mode.getByRole('radio', { name: 'Dark' }).check()
  expect(await theme(page)).toBe('ink')
  await expect(light.getByRole('radio', { name: 'Sage' })).toBeDisabled()
  await dark.getByRole('radio', { name: 'Midnight' }).check()
  expect(await theme(page)).toBe('midnight')
  await page.reload()
  await expect(page.getByRole('group', { name: 'Dark theme' }).getByRole('radio', { name: 'Midnight' })).toBeChecked()
  expect(await theme(page)).toBe('midnight')
  await expect(page.locator('meta[name=theme-color]')).toHaveAttribute('content', '#191f28')

  // System uses the chosen light and dark theme, and follows the device while the page is open.
  await page.getByRole('group', { name: 'Mode' }).getByRole('radio', { name: 'System' }).check()
  await page.getByRole('group', { name: 'Light theme' }).getByRole('radio', { name: 'Sage' }).check()
  expect(await theme(page)).toBe('sage')
  await page.emulateMedia({ colorScheme: 'dark' })
  await expect.poll(() => theme(page)).toBe('midnight')
  await page.reload()
  expect(await theme(page)).toBe('midnight')
  await page.emulateMedia({ colorScheme: 'light' })
  await expect.poll(() => theme(page)).toBe('sage')
})

test('a theme kept by the earlier single picker carries over', async ({ page }) => {
  await page.addInitScript(() => { if (!sessionStorage.getItem('seeded')) { localStorage.setItem('quire-ui:theme', 'Midnight'); sessionStorage.setItem('seeded', '1') } })
  await page.goto('/quire/#/settings')
  expect(await theme(page)).toBe('midnight')
  await expect(page.getByRole('group', { name: 'Mode' }).getByRole('radio', { name: 'Dark' })).toBeChecked()
  await expect(page.getByRole('group', { name: 'Dark theme' }).getByRole('radio', { name: 'Midnight' })).toBeChecked()
})

test('the background choice says what it is and persists', async ({ page }) => {
  await page.goto('/quire/#/settings')
  const background = page.getByRole('group', { name: 'Background' })
  await expect(background).toHaveAccessibleDescription(/Reading pages stay unchanged/)
  await expect(page.locator('.shell')).toHaveClass(/artwork/)
  await background.getByRole('radio', { name: 'Plain' }).check()
  await expect(page.locator('.shell')).not.toHaveClass(/artwork/)
  await page.reload()
  await expect(page.getByRole('group', { name: 'Background' }).getByRole('radio', { name: 'Plain' })).toBeChecked()
  await expect(page.locator('.shell')).not.toHaveClass(/artwork/)
})

const languages = ['en', 'es', 'it', 'fr', 'de']
for (const width of [320, 1280]) {
  test(`no settings text overflows at ${width} px in any language`, async ({ page }) => {
    await signedIn(page)
    await page.setViewportSize({ width, height: 900 })
    for (const lang of languages) {
      await page.evaluate(code => localStorage.setItem('quire-ui:language', code), lang).catch(() => undefined)
      await page.goto('/quire/#/settings')
      await page.evaluate(code => localStorage.setItem('quire-ui:language', code), lang)
      await page.reload()
      // Every section with its content, AI and catalogs included.
      await expect(page.locator('#settings-ai select')).toHaveCount(3)
      await expect(page.locator('#settings-catalogs li')).toHaveCount(3)
      await expect(page.locator('#settings-reading input[type=range]')).toHaveCount(3)
      for (const scheme of ['paper', 'midnight']) {
        await page.evaluate(name => { document.documentElement.dataset.theme = name }, scheme)
        const overflow = await page.evaluate(() => {
          // A visually hidden name is clipped on purpose.
          const clipped = [...document.querySelectorAll<HTMLElement>('.settings *:not(.visually-hidden)')]
            .filter(element => element.getClientRects().length && element.scrollWidth > element.clientWidth + 1 && element.clientWidth > 0)
            .map(element => `${element.tagName.toLowerCase()}.${element.className}: ${element.textContent?.slice(0, 40)}`)
          const root = document.documentElement
          // Each control's box stays inside its panel.
          const outside = [...document.querySelectorAll<HTMLElement>('.settings .panel *:not(.visually-hidden)')].filter(element => {
            const panel = element.closest('.panel')!.getBoundingClientRect()
            const box = element.getBoundingClientRect()
            return box.width > 0 && (box.right > panel.right + 1 || box.left < panel.left - 1)
          }).map(element => `${element.tagName.toLowerCase()}.${element.className}: ${element.textContent?.slice(0, 40)}`)
          return { clipped, outside, page: root.scrollWidth - root.clientWidth }
        })
        expect(overflow, `${lang} ${scheme}`).toEqual({ clipped: [], outside: [], page: 0 })
      }
    }
  })
}

test('AI preferences save as they change, from the same account state, and a refused change is undone', async ({ page }) => {
  const server = await signedIn(page)
  // The AI tab's old address still leads to it.
  await page.goto('/quire/#/settings/insights')
  const ai = page.getByRole('region', { name: 'AI features' })
  await expect(ai).toBeFocused()
  const optIn = ai.getByRole('switch', { name: 'Enable AI insights and reader profiles' })
  await expect(optIn).toHaveAccessibleDescription(/configured AI provider/)
  await optIn.check()
  await expect.poll(() => server.puts.length).toBe(1)
  await ai.getByRole('combobox', { name: 'Tone' }).selectOption('scholarly')
  await ai.getByRole('combobox', { name: 'Insight language' }).selectOption('it')
  await ai.getByRole('combobox', { name: 'Provider' }).selectOption('careful')
  await expect.poll(() => server.preferences).toEqual({
    ai_enabled: true, provider_id: 'careful', style: { tone: 'scholarly', language: 'it' },
  })
  expect(server.puts.at(-1)).toMatchObject({ provider_id: 'careful' })
  await page.reload()
  const reloaded = page.getByRole('region', { name: 'AI features' })
  await expect(reloaded.getByRole('switch', { name: 'Enable AI insights and reader profiles' })).toBeChecked()
  await expect(reloaded.getByRole('combobox', { name: 'Tone' })).toHaveValue('scholarly')
  await expect(reloaded.getByRole('combobox', { name: 'Insight language' })).toHaveValue('it')
  await expect(reloaded.getByRole('combobox', { name: 'Provider' })).toHaveValue('careful')
})

test('an AI preference the server refuses goes back and says so', async ({ page }) => {
  await signedIn(page, { failPreferences: true })
  await page.goto('/quire/#/settings/ai')
  const ai = page.getByRole('region', { name: 'AI features' })
  await ai.getByRole('combobox', { name: 'Tone' }).selectOption('casual')
  await expect(ai.getByRole('alert')).toContainText('the previous setting is kept')
  await expect(ai.getByRole('combobox', { name: 'Tone' })).toHaveValue('neutral')
})

test('reading defaults set in settings are the ones the reader opens with', async ({ page }) => {
  await page.goto('/quire/')
  await page.locator('input[type=file]').setInputFiles(book())
  await expect(page.getByRole('heading', { name: 'Settings Fixture' })).toBeVisible()
  await page.goto('/quire/#/settings/reading')
  const reading = page.getByRole('region', { name: 'Reading' })
  await reading.getByRole('combobox', { name: 'Font' }).selectOption('sans')
  await reading.getByRole('combobox', { name: 'Palette' }).selectOption('sage')
  await reading.getByRole('slider', { name: 'Text size' }).fill('24')
  await expect(reading.locator('output').first()).toHaveText('24 px')
  await page.reload()
  await expect(page.getByRole('region', { name: 'Reading' }).getByRole('combobox', { name: 'Font' })).toHaveValue('sans')
  await expect(page.getByRole('region', { name: 'Reading' }).getByRole('slider', { name: 'Text size' })).toHaveValue('24')
  await page.goto('/quire/#/library')
  await page.getByRole('button', { name: /Settings Fixture/ }).first().click()
  await page.getByRole('button', { name: 'Read', exact: true }).click()
  await page.getByRole('button', { name: 'Appearance', exact: true }).click()
  await expect(page.getByLabel('Font')).toHaveValue('sans')
  await expect(page.getByLabel('Palette')).toHaveValue('sage')
  await expect(page.getByLabel('Text size')).toHaveValue('24')
})

test('saved catalogs reorder and remove from settings through the account', async ({ page }) => {
  const server = await signedIn(page)
  await page.goto('/quire/#/settings/catalogs')
  const catalogs = page.getByRole('region', { name: 'Catalogs' })
  await expect(catalogs.getByRole('listitem')).toHaveCount(3)
  await catalogs.getByRole('button', { name: 'Move Home shelf up' }).click()
  await expect.poll(() => server.orders).toEqual([[
    '6c1f6a4e-0000-4000-8000-000000000001', '6c1f6a4e-0000-4000-8000-000000000003', '6c1f6a4e-0000-4000-8000-000000000002',
  ]])
  await expect(catalogs.getByRole('listitem').nth(1)).toContainText('Home shelf')
  await catalogs.getByRole('button', { name: 'Remove Project Gutenberg' }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'Remove' }).click()
  await expect(catalogs.getByRole('listitem')).toHaveCount(2)
  expect(server.deletes).toEqual(['6c1f6a4e-0000-4000-8000-000000000001'])
  await page.reload()
  await expect(page.getByRole('region', { name: 'Catalogs' }).getByRole('listitem').first()).toContainText('Home shelf')
})

test('downloads can all be removed from storage settings, keeping the books', async ({ page }) => {
  await page.goto('/quire/')
  await page.locator('input[type=file]').setInputFiles(book())
  await expect(page.getByRole('heading', { name: 'Settings Fixture' })).toBeVisible()
  await page.goto('/quire/#/settings/storage')
  const storage = page.getByRole('region', { name: 'Storage and offline' })
  await expect(storage.getByRole('definition').nth(1)).toHaveText('1')
  await storage.getByRole('button', { name: 'Remove all', exact: true }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'Remove all' }).click()
  await expect(storage.getByRole('definition').nth(1)).toHaveText('0')
  await page.goto('/quire/#/library')
  await expect(page.getByRole('button', { name: /Settings Fixture/ }).first()).toBeVisible()
})
