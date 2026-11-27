import { expect, test } from '@playwright/test'
import { fileURLToPath } from 'node:url'

test.use({ actionTimeout: 10_000, navigationTimeout: 15_000 })

test('Discover shows browser reading activity, even with AI off', async ({ page }) => {
  await page.clock.install()
  await page.route('**/quire/v1/bootstrap', route => route.fulfill({ json: {
    version: 1, sync: false, ai: false, aiAvailable: false, login: 'none',
  } }))
  await page.setViewportSize({ width: 320, height: 844 })
  await page.goto('/quire/')
  await expect(page.getByRole('button', { name: 'Import EPUB', exact: true })).toBeEnabled()
  await page.locator('input[type=file]').setInputFiles(fileURLToPath(new URL('../../app/src/test/resources/reader-pause.epub', import.meta.url)))
  await page.getByRole('button', { name: 'Read', exact: true }).click()
  await expect(page.frameLocator('.qr-frame').getByRole('heading', { name: 'Part 1' })).toBeVisible()
  await expect.poll(() => page.evaluate(async () => {
    const request = indexedDB.open(`quire-web:${JSON.stringify([location.origin, 'local', ''])}`)
    const db = await new Promise<IDBDatabase>((resolve, reject) => {
      request.onsuccess = () => resolve(request.result)
      request.onerror = () => reject(request.error)
    })
    try {
      const setting = db.transaction('settings').objectStore('settings').get('preference:reading-recording')
      return await new Promise<boolean>((resolve, reject) => {
        setting.onsuccess = () => resolve(!!setting.result?.generation)
        setting.onerror = () => reject(setting.error)
      })
    } finally {
      db.close()
    }
  })).toBe(true)
  await page.clock.fastForward(65_000)
  await page.getByRole('button', { name: 'Close book', exact: true }).click()
  await page.getByRole('link', { name: 'Discover', exact: true }).click()

  const stats = page.getByRole('region', { name: 'Reading statistics', exact: true })
  await expect(stats.locator('.reading-metrics > div').filter({ hasText: 'Total time' }).locator('dd')).toHaveText('1 min')
  await stats.getByRole('combobox', { name: 'Activity period' }).selectOption('month')
  await expect(stats.locator('.reading-chart-bars').first().locator(':scope > span')).toHaveCount(30)
  await expect(stats.getByText('By day of the week', { exact: true })).toBeVisible()
  await expect(stats.getByText('Reader Pause Fixture', { exact: true })).toBeVisible()
  expect(await stats.evaluate(element => element.scrollWidth <= element.clientWidth + 1)).toBe(true)

  await stats.getByRole('button', { name: 'Clear statistics', exact: true }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'Clear statistics', exact: true }).click()
  await expect(stats.getByText('Open a book and read for at least 30 seconds to start seeing your activity.')).toBeVisible()
  const recording = stats.getByRole('checkbox', { name: 'Record reading statistics' })
  await recording.uncheck()
  await expect(recording).toBeEnabled()
  await page.reload()
  await expect(recording).not.toBeChecked()
})

test('Discover syncs existing phone and browser sessions only after opt-in', async ({ page }) => {
  await page.clock.install()
  await page.addInitScript(() => Reflect.deleteProperty(Navigator.prototype, 'serviceWorker'))
  await page.route('**/quire/v1/bootstrap', route => route.fulfill({ json: {
    version: 1, sync: true, ai: false, aiAvailable: false, login: 'native', password: true,
  } }))
  await page.route('**/auth/v1/me', route => route.fulfill({ json: {
    version: 1, user: { id: 'reader', backend: 'native' }, csrf_token: 'test',
    capabilities: { progress_metadata_sync: true },
  } }))
  await page.route('**/library/v1/**', route => route.fulfill({ status: 404 }))
  await page.route('**/sync/v1/**', route => route.fulfill({ status: 404 }))
  const phone = {
    id: 'android-history', book_key: 'android-book', title: 'Read on Android',
    started_at: Date.now() - 86_400_000, utc_offset_seconds: 3600, active_seconds: 120, words: 500,
  }
  const history = [phone]
  let cutoff = 0
  const methods: string[] = []
  await page.route('**/library/v1/reading-activity', async route => {
    const method = route.request().method()
    methods.push(method)
    if (method === 'PUT') {
      history.push(...route.request().postDataJSON().sessions)
      await route.fulfill({ status: 204 })
    } else if (method === 'DELETE') {
      cutoff = Date.now() + 120_000
      history.length = 0
      await route.fulfill({ json: { cleared_before: cutoff } })
    } else {
      await route.fulfill({ json: { cleared_before: cutoff, sessions: history, next: null } })
    }
  })
  await page.goto('/quire/')
  await expect(page.getByRole('button', { name: 'Import EPUB', exact: true })).toBeEnabled()
  await page.locator('input[type=file]').setInputFiles(fileURLToPath(new URL('../../app/src/test/resources/reader-pause.epub', import.meta.url)))
  await page.getByRole('button', { name: 'Read', exact: true }).click()
  await expect(page.frameLocator('.qr-frame').getByRole('heading', { name: 'Part 1' })).toBeVisible()
  await page.clock.runFor(200)
  await page.clock.fastForward(65_000)
  await page.getByRole('button', { name: 'Close book', exact: true }).click()
  await page.getByRole('link', { name: 'Discover', exact: true }).click()

  const stats = page.getByRole('region', { name: 'Reading statistics', exact: true })
  const sync = stats.getByRole('checkbox', { name: 'Sync reading activity' })
  await expect(stats.getByText('Reader Pause Fixture', { exact: true })).toBeVisible()
  await expect(sync).toBeEnabled()
  await expect(sync).not.toBeChecked()
  expect(methods).toEqual([])

  await sync.click()
  await expect(sync).toBeChecked()
  await expect(stats.getByText(phone.title)).toBeVisible()
  await expect.poll(() => history.length).toBe(2)
  expect(history[1].title).toBe('Reader Pause Fixture')
  await page.reload()
  await expect(sync).toBeChecked()
  await expect(stats.getByText(phone.title)).toBeVisible()
  expect(history).toHaveLength(2)

  await stats.getByRole('button', { name: 'Clear statistics', exact: true }).click()
  const confirmation = page.getByRole('dialog')
  await expect(confirmation.getByText(/from your Quire account/)).toBeVisible()
  await confirmation.getByRole('button', { name: 'Clear statistics', exact: true }).click()
  await expect(stats.getByText(phone.title)).toHaveCount(0)
  await expect(stats.getByText('Reader Pause Fixture', { exact: true })).toHaveCount(0)
  expect(history).toHaveLength(0)
})

test('the Italian reader profile keeps its disclosure and actions in a narrow card', async ({ page }) => {
  await page.addInitScript(() => {
    Reflect.deleteProperty(Navigator.prototype, 'serviceWorker')
    localStorage.setItem('quire-ui:language', 'it')
  })
  await page.route('**/quire/v1/bootstrap', route => route.fulfill({ json: {
    version: 1, sync: true, ai: true, aiAvailable: true, login: 'native', password: true,
  } }))
  await page.route('**/auth/v1/me', route => route.fulfill({ json: {
    version: 1, user: { id: 'reader', backend: 'native' }, csrf_token: 'test',
    capabilities: { progress_metadata_sync: true, ai_enabled: true },
  } }))
  await page.route('**/ai/v1/config', route => route.fulfill({ json: { configured: true, progress_supported: true, providers: [] } }))
  await page.route('**/ai/v1/preferences', route => route.fulfill({ json: {
    ai_enabled: true, provider_id: 'default', style: { tone: 'neutral', language: 'auto' },
  } }))
  await page.route('**/ai/v1/profile', route => route.fulfill({ json: {
    payload: { narrative: 'Un profilo già salvato', language: 'it', in_library_recommendations: [], discovery_recommendations: [], ai_suggested_recommendations: [] },
    generated_at: '2025-11-01T00:00:00Z', model_id: 'test-model',
  } }))
  await page.route('**/library/v1/**', route => route.fulfill({ status: 404 }))
  await page.route('**/sync/v1/**', route => route.fulfill({ status: 404 }))
  await page.setViewportSize({ width: 320, height: 844 })
  await page.goto('/quire/#/discover')

  const panel = page.locator('.reader-profile')
  await expect(panel.getByText('Un profilo già salvato')).toBeVisible()
  await expect(panel.getByRole('button', { name: 'Aggiorna profilo' }))
    .toHaveAccessibleDescription(/statistiche della biblioteca connessa/)
  await panel.getByRole('button', { name: 'Elimina profilo', exact: true }).click()
  await expect(panel.getByRole('button', { name: 'Mantieni profilo' })).toBeVisible()
  expect(await panel.evaluate(element => element.scrollWidth <= element.clientWidth + 1)).toBe(true)
})

test('French discovery in AI-only mode needs opt-in and sends only ISBN metadata', async ({ page }) => {
  const isbn = '9780804429573'
  let optedIn = false
  const libraryRequests: string[] = []
  const generationBodies: unknown[] = []
  await page.addInitScript(() => {
    // WebKit's worker network can bypass Playwright routes, so disable it for this API-mock test.
    Reflect.deleteProperty(Navigator.prototype, 'serviceWorker')
    localStorage.setItem('quire-ui:language', 'fr')
  })
  await page.route('**/quire/v1/bootstrap', route => route.fulfill({ json: {
    version: 1, sync: false, ai: true, aiAvailable: true, login: 'native', password: true,
    oidc: false, catalogUrl: null, accountManagement: null,
  } }))
  await page.route('**/auth/v1/me', route => route.fulfill({ json: {
    version: 1, user: { id: 'reader', backend: 'native' }, csrf_token: 'test',
    capabilities: { progress_metadata_sync: false, ai_enabled: true },
  } }))
  await page.route('**/library/v1/**', route => {
    libraryRequests.push(route.request().url())
    return route.fulfill({ status: 404 })
  })
  await page.route('**/ai/v1/**', route => {
    const request = route.request()
    switch (new URL(request.url()).pathname) {
      case '/ai/v1/config':
        return route.fulfill({ json: { configured: true, progress_supported: false, providers: [] } })
      case '/ai/v1/preferences':
        if (request.method() === 'PUT') optedIn = request.postDataJSON().ai_enabled
        return route.fulfill({ json: { ai_enabled: optedIn, provider_id: 'default', style: { tone: 'neutral', language: 'auto' } } })
      case '/ai/v1/insights/get':
        return route.fulfill({ status: 404, json: { detail: 'not_cached' } })
      case '/ai/v1/insights/lookup':
        generationBodies.push(request.postDataJSON())
        return route.fulfill({ status: 429, json: { detail: { used: 1, limit: 1 } } })
      default:
        return route.fulfill({ status: 404 })
    }
  })
  await page.route('https://openlibrary.org/api/books.json?*', route => route.fulfill({
    headers: { 'access-control-allow-origin': '*' },
    json: { [`ISBN:${isbn}`]: { title: 'Test Edition', authors: [{ name: 'Test Author' }], notes: 'Private notes' } },
  }))
  await page.route('https://covers.openlibrary.org/**', route => route.fulfill({ status: 404 }))

  await page.goto('/quire/#/settings/insights')
  const optIn = page.getByRole('switch', { name: 'Activer les analyses par IA', exact: true })
  await expect(optIn).toBeVisible()
  await page.getByRole('link', { name: 'Découvrir', exact: true }).click()
  const lookup = async () => {
    await page.getByLabel('ISBN', { exact: true }).fill(isbn)
    await page.getByRole('button', { name: 'Rechercher sur Open Library' }).click()
    await expect(page.getByRole('heading', { name: 'Test Edition' })).toBeVisible()
  }
  await lookup()
  await expect(page.getByText('Activez l’IA dans les réglages pour ouvrir les analyses.')).toBeVisible()
  expect(generationBodies).toEqual([])

  await page.getByRole('link', { name: 'Réglages', exact: true }).click()
  const [saved] = await Promise.all([
    page.waitForResponse(response => new URL(response.url()).pathname === '/ai/v1/preferences' && response.request().method() === 'PUT', { timeout: 10_000 }),
    optIn.check(),
  ])
  expect(saved.ok()).toBe(true)
  expect(await saved.json()).toMatchObject({ ai_enabled: true })
  await page.getByRole('link', { name: 'Découvrir', exact: true }).click()
  await lookup()
  await expect(page.getByRole('heading', { name: 'Profil de lecture' })).toHaveCount(0)
  await page.getByRole('button', { name: 'Obtenir une analyse' }).click()
  await expect(page.getByText('Quota d’IA atteint. Réessayez après sa réinitialisation.')).toBeVisible()
  expect(generationBodies).toEqual([{
    identity: { metadata_id: `isbn:${isbn}`, isbn },
    bundle: { title: 'Test Edition', author: 'Test Author', isbn, subjects: [] },
  }])
  expect(libraryRequests).toEqual([])
})
