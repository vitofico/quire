import { expect, test } from '@playwright/test'

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

  await page.goto('/quire/#/discover')
  const lookup = async () => {
    await page.getByLabel('ISBN', { exact: true }).fill(isbn)
    await page.getByRole('button', { name: 'Rechercher sur Open Library' }).click()
    await expect(page.getByRole('heading', { name: 'Test Edition' })).toBeVisible()
  }
  await lookup()
  await expect(page.getByText('Activez l’IA dans les réglages pour ouvrir les analyses.')).toBeVisible()
  expect(generationBodies).toEqual([])

  await page.getByRole('link', { name: 'Réglages', exact: true }).click()
  await page.getByRole('checkbox', { name: 'Activer les analyses par IA', exact: true }).check()
  await page.getByRole('button', { name: 'Enregistrer les réglages de l’IA' }).click()
  await expect(page.getByRole('button', { name: 'Enregistrer les réglages de l’IA' })).toBeDisabled()
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
