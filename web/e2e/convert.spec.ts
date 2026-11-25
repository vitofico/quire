import { expect, test, type Page } from '@playwright/test'
import { createHash } from 'node:crypto'
import { strToU8, zipSync } from 'fflate'

test.use({ actionTimeout: 10_000, navigationTimeout: 15_000 })
// The client polls every five seconds; a conversion takes a few polls.
const poll = { timeout: 15_000 }

const pdf = (text = 'Field notes', name = 'Field notes.pdf') => ({
  name, mimeType: 'application/pdf', buffer: Buffer.from(`%PDF-1.7\n${text}\n%%EOF\n`),
})

function convertedBook() {
  const files = {
    'mimetype': strToU8('application/epub+zip'),
    'META-INF/container.xml': strToU8('<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="book.opf"/></rootfiles></container>'),
    'book.opf': strToU8('<package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" unique-identifier="id"><metadata><dc:title>Field Notes</dc:title><dc:creator>A. Walker</dc:creator><dc:identifier id="id">urn:uuid:5a0d2b8e-1c4f-4e7a-9b11-3f0c2d6e8a91</dc:identifier><dc:language>en</dc:language></metadata><manifest><item id="one" href="one.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="one"/></spine></package>'),
    'one.xhtml': strToU8('<html xmlns="http://www.w3.org/1999/xhtml"><head><title>One</title></head><body><p>Notes.</p></body></html>'),
  }
  return Buffer.from(zipSync(files))
}

type State = 'created' | 'queued' | 'running' | 'ready' | 'review' | 'failed' | 'cancelled' | 'acknowledged' | 'expired'
type Job = { id: string; key: string; sha256: string; bytes: number; state: State; failure?: object; review?: object; next: State[]; hold?: boolean }
type Options = { capability?: boolean; available?: boolean; maxBytes?: number; script?: (job: Job) => State[]; holdUpload?: boolean }

/** A mocked Quire server whose /convert/v1 moves each job one step along `script` per poll. */
async function connect(page: Page, options: Options = {}) {
  const { capability = true, available = true, maxBytes = 104_857_600, script = () => ['running', 'ready'] } = options
  const jobs = new Map<string, Job>()
  const calls: string[] = []
  const keys: string[] = []
  const epub = convertedBook()
  await page.addInitScript(() => { Reflect.deleteProperty(Navigator.prototype, 'serviceWorker') })
  await page.route('**/quire/v1/bootstrap', route => route.fulfill({ json: {
    version: 1, sync: false, ai: false, aiAvailable: false, login: 'native', password: true,
    oidc: false, catalogUrl: null, accountManagement: null,
  } }))
  await page.route('**/auth/v1/me', route => route.fulfill({ json: {
    version: 1, user: { id: 'reader', backend: 'native' }, csrf_token: 'test',
    capabilities: { progress_metadata_sync: false, ai_enabled: false, pdf_conversion: capability },
  } }))
  const view = (job: Job) => ({
    id: job.id, state: job.state, mode: 'local', attempt: 1,
    created_at: '2026-11-25T10:00:00+00:00', updated_at: '2026-11-25T10:00:00+00:00', expires_at: '2026-11-26T10:00:00+00:00',
    source: { sha256: job.sha256, bytes: job.bytes }, engine_revision: 'quire-convert 0.1.0',
    result: ['ready', 'review'].includes(job.state) ? { sha256: 'e'.repeat(64), bytes: epub.length, rendition_id: 'urn:uuid:x', issue_counts: {} } : null,
    review: job.state === 'review' ? job.review : null,
    failure: job.state === 'failed' ? job.failure : null,
  })
  await page.route('**/convert/v1/**', async route => {
    const request = route.request()
    const method = request.method()
    const path = new URL(request.url()).pathname
    calls.push(`${method} ${path}`)
    expect(request.headers()['x-quire-account']).toBe(Buffer.from('reader').toString('hex'))
    if (method !== 'GET') expect(request.headers()['x-csrf-token']).toBe('test')
    if (!capability) return route.fulfill({ status: 404, json: { detail: 'Not Found' } })
    if (path === '/convert/v1/capabilities') return route.fulfill({ json: {
      available, engine_revision: 'quire-convert 0.1.0', accepted_media_types: ['application/pdf'], max_bytes: maxBytes,
      max_pages: 1000, time_limit_s: 1200, max_active_jobs: 2, daily_limit: 10, retention_hours: 24,
      modes: [{ id: 'local', sends_content_off_server: false }],
    } })
    if (path === '/convert/v1/jobs' && method === 'POST') {
      const body = request.postDataJSON()
      keys.push(body.idempotency_key)
      const existing = [...jobs.values()].find(job => job.key === body.idempotency_key)
      if (existing) return route.fulfill({ json: view(existing) })
      // Decided now: a browser may hand the upload to the route only after the test moved on.
      const job: Job = { id: `job-${jobs.size + 1}`, key: body.idempotency_key, sha256: body.sha256, bytes: body.bytes, state: 'created', next: [], hold: options.holdUpload }
      job.next = script(job)
      jobs.set(job.id, job)
      return route.fulfill({ status: 201, json: view(job) })
    }
    const [, , , , id, action] = path.split('/')
    const job = jobs.get(id)
    if (!job) return route.fulfill({ status: 404, json: { detail: 'job_not_found' } })
    if (action === 'source') {
      if (job.hold) {
        // The page goes away before an answer comes; the next upload of this job arrives.
        job.hold = false
        return
      }
      expect(request.headers()['content-type']).toBe('application/pdf')
      // WebKit does not show Playwright a File body; Chromium does, and it must be the PDF chosen.
      const sent = request.postDataBuffer()
      if (sent) expect(createHash('sha256').update(sent).digest('hex')).toBe(job.sha256)
      job.state = 'queued'
      return route.fulfill({ status: 202, json: view(job) })
    }
    if (action === 'epub') {
      if (!['ready', 'review'].includes(job.state)) return route.fulfill({ status: 409, json: { detail: 'not_ready' } })
      return route.fulfill({ contentType: 'application/epub+zip', body: epub })
    }
    if (action === 'acknowledge') job.state = 'acknowledged'
    else if (action === 'cancel') job.state = 'cancelled'
    else if (job.state !== 'created' && job.next.length) job.state = job.next.shift()!
    return route.fulfill({ json: view(job) })
  })
  return { jobs, calls, keys }
}

const list = (page: Page) => page.getByRole('region', { name: 'PDF conversions' })

test('a PDF becomes a book: confirmed, uploaded, converted, imported once, and kept across a reload', async ({ page }) => {
  const server = await connect(page, { script: job => {
    job.review = { reason: '<b>1 page</b> needs review', affected_pages: [{ page: 12, label: 'xii' }], issues: [] }
    return ['running', 'review']
  } })
  await page.goto('/quire/')
  await expect(page.getByRole('button', { name: 'Import EPUB or PDF' })).toBeEnabled()
  await expect(page.locator('input[type=file]')).toHaveAttribute('accept', /\.pdf/)
  await page.locator('input[type=file]').setInputFiles(pdf())

  const item = list(page).getByRole('listitem').filter({ hasText: 'Field notes.pdf' })
  await expect(item).toContainText('Converted on your server, nothing is sent elsewhere.')
  await expect(item).toContainText('1 KB')
  expect(server.calls.filter(call => call.startsWith('POST'))).toEqual([])
  await item.getByRole('button', { name: 'Convert to EPUB' }).click()
  await expect(item).toContainText(/Waiting to convert|Converting/)

  // The same PDF again is the same conversion, not a second one.
  await page.locator('input[type=file]').setInputFiles(pdf())
  await list(page).getByRole('button', { name: 'Convert to EPUB' }).click()
  await expect(list(page).getByRole('listitem').filter({ hasText: 'Field notes.pdf' })).toHaveCount(1)

  await expect(item).toContainText('Added to your library. Some pages need a look.', poll)
  await expect(item.getByRole('list', { name: 'Pages to check' })).toHaveText('Page 12 (xii)')
  // Worker text is shown as text, never as markup.
  await expect(item).toContainText('<b>1 page</b> needs review')
  await expect(item.locator('b')).toHaveCount(0)
  await expect(page.getByRole('button', { name: /Field Notes/ })).toBeVisible()
  await expect(page.getByRole('status').filter({ hasText: 'Field notes.pdf: Added to your library.' })).toBeAttached()
  expect(server.calls.filter(call => call === 'POST /convert/v1/jobs')).toHaveLength(1)
  expect(server.calls.slice(-2)).toEqual(['GET /convert/v1/jobs/job-1/epub', 'POST /convert/v1/jobs/job-1/acknowledge'])
  expect(new Set(server.keys).size).toBe(1)

  await page.reload()
  await expect(item).toContainText('Some pages need a look.')
  await expect(page.getByRole('button', { name: /Field Notes/ })).toHaveCount(1)
  await item.getByRole('link', { name: 'Open book' }).click()
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Field Notes')
  await page.goBack()
  await list(page).getByRole('button', { name: 'Dismiss' }).click()
  await expect(list(page)).toHaveCount(0)
  expect(server.calls.filter(call => call.endsWith('/epub'))).toHaveLength(1)
})

test('failures explain themselves and offer a retry only when the server says it may work', async ({ page }) => {
  const server = await connect(page, {
    maxBytes: 2_000,
    script: job => {
      job.failure = job.bytes === pdf('locked').buffer.length
        ? { code: 'encrypted', retryable: false, detail: null }
        : { code: 'conversion_failed', retryable: true, detail: '<i>ocr</i> stage' }
      return job.id === 'job-3' ? ['running', 'ready'] : ['failed']
    },
  })
  await page.goto('/quire/')
  await page.locator('input[type=file]').setInputFiles([pdf('locked', 'Locked.pdf'), pdf('scan', 'Scan.pdf'), pdf('x'.repeat(3_000), 'Huge.pdf')])
  const huge = list(page).getByRole('listitem').filter({ hasText: 'Huge.pdf' })
  await expect(huge).toContainText('This PDF is 3 KB. This server converts PDFs up to 2 KB.')
  await expect(huge.getByRole('button', { name: 'Convert to EPUB' })).toHaveCount(0)
  await huge.getByRole('button', { name: 'Dismiss' }).click()
  await expect(huge).toHaveCount(0)

  const locked = list(page).getByRole('listitem').filter({ hasText: 'Locked.pdf' })
  const scan = list(page).getByRole('listitem').filter({ hasText: 'Scan.pdf' })
  await locked.getByRole('button', { name: 'Convert to EPUB' }).click()
  await scan.getByRole('button', { name: 'Convert to EPUB' }).click()
  await expect(locked).toContainText('This PDF is encrypted or password-protected, so it cannot be converted.', poll)
  await expect(locked.getByRole('button', { name: 'Try again' })).toHaveCount(0)
  await expect(scan).toContainText('The conversion did not finish.', poll)
  await expect(scan).toContainText('<i>ocr</i> stage')
  // Both reasons are kept in this browser; the server's copies go.
  expect(server.calls).toEqual(expect.arrayContaining(['POST /convert/v1/jobs/job-1/acknowledge', 'POST /convert/v1/jobs/job-2/acknowledge']))

  await scan.getByRole('button', { name: 'Try again' }).click()
  await expect(scan).toContainText('Added to your library.', poll)
  expect(new Set(server.keys).size).toBe(3)
})

test('a reload during the upload asks for the file again and finishes the same job', async ({ page }) => {
  const server = await connect(page, { holdUpload: true })
  await page.goto('/quire/')
  await page.locator('input[type=file]').setInputFiles(pdf())
  await list(page).getByRole('button', { name: 'Convert to EPUB' }).click()
  const item = list(page).getByRole('listitem').filter({ hasText: 'Field notes.pdf' })
  await expect(item).toContainText('Uploading…')

  await page.reload()
  await expect(item).toContainText('Choose the file again to finish the upload.')
  const chooser = page.waitForEvent('filechooser')
  await item.getByRole('button', { name: 'Choose the file again' }).click()
  // Confirmed before the reload: the same file carries on without asking again.
  await (await chooser).setFiles(pdf())
  await expect(list(page).getByRole('listitem')).toHaveCount(1)
  await expect(item).toContainText('Added to your library.', poll)
  expect(server.jobs.size).toBe(1)
  expect(new Set(server.keys).size).toBe(1)
})

test('a cancelled conversion stops on the server and leaves the list', async ({ page }) => {
  const server = await connect(page, { script: () => ['running', 'running', 'running', 'ready'] })
  await page.goto('/quire/')
  await page.locator('input[type=file]').setInputFiles(pdf())
  await list(page).getByRole('button', { name: 'Convert to EPUB' }).click()
  await expect(list(page)).toContainText(/Waiting to convert|Converting/)
  await list(page).getByRole('button', { name: 'Cancel' }).click()
  await expect(list(page)).toHaveCount(0)
  await expect.poll(() => server.calls.at(-1)).toBe('POST /convert/v1/jobs/job-1/cancel')
  await page.reload()
  await expect(page.getByRole('button', { name: 'Import EPUB or PDF' })).toBeEnabled()
  await expect(list(page)).toHaveCount(0)
})

test('without conversion on the server, choosing a PDF says why', async ({ page }) => {
  await connect(page, { capability: false })
  await page.goto('/quire/')
  await expect(page.getByRole('button', { name: 'Import EPUB', exact: true })).toBeEnabled()
  await expect(page.locator('input[type=file]')).toHaveAttribute('accept', '.epub,application/epub+zip')
  await page.locator('input[type=file]').setInputFiles(pdf())
  await expect(page.getByRole('status').filter({ hasText: 'This server does not convert PDFs. Add an EPUB instead.' })).toBeVisible()
})

test('with the converter stopped, choosing a PDF says so', async ({ page }) => {
  await connect(page, { available: false })
  await page.goto('/quire/')
  await page.locator('input[type=file]').setInputFiles(pdf())
  await expect(page.getByRole('status').filter({ hasText: 'The PDF converter on this server is not running right now. Try again later.' })).toBeVisible()
  await expect(list(page)).toHaveCount(0)
})

test('the conversions list stays readable, focusable and inside a phone screen', async ({ page, browserName }) => {
  await connect(page, { script: job => {
    job.failure = { code: 'timed_out', retryable: false, detail: null }
    return ['failed']
  } })
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/quire/')
  await page.locator('input[type=file]').setInputFiles([pdf('a', 'A very long file name for a scanned field notebook from 1974, volume two.pdf'), pdf('b', 'Second.pdf')])
  await list(page).getByRole('button', { name: 'Convert to EPUB' }).first().click()
  await expect(list(page)).toContainText('The conversion took too long and was stopped.', poll)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1)).toBe(true)

  // Every control is a named button the keyboard reaches with a visible focus ring.
  const convert = list(page).getByRole('button', { name: 'Convert to EPUB' })
  await convert.focus()
  await page.keyboard.press(browserName === 'webkit' && process.platform === 'darwin' ? 'Alt+Tab' : 'Tab')
  const cancel = list(page).getByRole('listitem').filter({ hasText: 'Second.pdf' }).getByRole('button', { name: 'Cancel' })
  await expect(cancel).toBeFocused()
  expect(await cancel.evaluate(button => getComputedStyle(button).outlineStyle)).not.toBe('none')
  await expect(convert).toHaveAccessibleDescription('Second.pdf')

  for (const theme of ['paper', 'sage', 'ink', 'midnight']) {
    const ratios = await list(page).locator('p:not(.visually-hidden), small, strong, .eyebrow').evaluateAll((labels, name) => {
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
    expect(ratios.length).toBeGreaterThan(4)
    for (const ratio of ratios) expect(ratio, theme).toBeGreaterThanOrEqual(4.5)
  }
})
