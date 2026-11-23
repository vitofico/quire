import { mkdtemp, writeFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { expect, test } from '@playwright/test'
import { strToU8, zipSync } from 'fflate'
import { waitForSavedChapter } from './local-replica'

const title = 'Interoperability Trial'
const sentence = 'The same sentence has a repeated ending.'
const comment = 'A note about the second occurrence.'
const followup = 'The second chapter starts here.'

const container = '<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OPS/trial.opf"/></rootfiles></container>'
const opf = `<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>${title}</dc:title>
  <dc:identifier id="book-id">urn:uuid:ed728def-3c77-4132-a0c9-4db03e086c07</dc:identifier></metadata>
  <manifest>
    <item id="first" href="first.xhtml" media-type="application/xhtml+xml"/>
    <item id="next" href="next.xhtml" media-type="application/xhtml+xml"/>
    <item id="cover" href="cover.png" media-type="image/png" properties="cover-image"/>
  </manifest><spine><itemref idref="first"/><itemref idref="next"/></spine>
</package>`
const chapterOne = `<html xmlns="http://www.w3.org/1999/xhtml"><head><title>First chapter</title>
  <style>body { background-image: url('/quire-unsafe/style.png'); }</style>
  <link rel="stylesheet" href="/quire-unsafe/stylesheet.css"/>
  <script>top.document.documentElement.setAttribute('data-publication-ran', 'true');</script>
  </head><body onload="top.document.documentElement.setAttribute('data-publication-ran', 'true')">
    <h1>First chapter</h1>
    <p id="first">${sentence}</p>
    <p id="second">The <em>same sentence</em> has a repeated ending.</p>
    <img alt="Local illustration" src="cover.png"/>
    <img alt="Publication tracker" src="/quire-unsafe/tracker.png"/>
    <iframe src="/quire-unsafe/embedded.html"></iframe>
    <form action="/quire-unsafe/form"><button>Submit</button></form>
    <a href="next.xhtml#opening">Open second chapter</a>
  </body></html>`
const chapterTwo = `<html xmlns="http://www.w3.org/1999/xhtml"><body><p id="opening">${followup}</p></body></html>`
const pixel = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/h/wAAAAASUVORK5CYII='

function fixture() {
  return Buffer.from(zipSync({
    mimetype: [strToU8('application/epub+zip'), { level: 0 }],
    'META-INF/container.xml': strToU8(container),
    'OPS/trial.opf': strToU8(opf),
    'OPS/first.xhtml': strToU8(chapterOne),
    'OPS/next.xhtml': strToU8(chapterTwo),
    'OPS/cover.png': Buffer.from(pixel, 'base64'),
  }))
}

test('imports, selects across inline markup, reopens the passage and isolates chapter resources', async ({ page, browserName }) => {
  test.skip(browserName === 'chromium', 'This test qualifies WebKit and Firefox.')
  const external: string[] = []
  await page.route('**/quire-unsafe/**', async route => {
    external.push(route.request().url())
    await route.abort()
  })

  await page.goto('/quire/')
  const importButton = page.getByRole('button', { name: 'Import EPUB', exact: true }).first()
  await expect(importButton).toBeEnabled()
  const directory = await mkdtemp(join(tmpdir(), 'quire-interoperability-'))
  try {
    const path = join(directory, 'trial.epub')
    await writeFile(path, fixture())
    const chooser = page.waitForEvent('filechooser')
    await importButton.click()
    await (await chooser).setFiles(path)
    await expect(page.getByRole('heading', { name: title })).toBeVisible()
  } finally {
    await rm(directory, { recursive: true, force: true })
  }
  await page.getByRole('button', { name: 'Read', exact: true }).click()

  const iframe = page.locator(`iframe[title="${title}"]`)
  await expect(iframe).toHaveAttribute('sandbox', 'allow-same-origin allow-scripts')
  await expect(iframe).toHaveAttribute('referrerpolicy', 'no-referrer')
  const chapter = iframe.contentFrame()
  await expect(chapter.locator('meta[http-equiv="Content-Security-Policy"]')).toHaveAttribute('content', /(?:^|;\s*)script-src 'none'(?:;|$)/)
  await expect(chapter.getByRole('heading', { name: 'First chapter' })).toBeVisible()
  await expect(chapter.locator('#second em')).toHaveText('same sentence')
  await expect(chapter.getByRole('img', { name: 'Local illustration' })).toHaveAttribute('src', /^blob:/)
  await expect(chapter.getByRole('img', { name: 'Publication tracker' })).not.toHaveAttribute('src')
  await expect(chapter.locator('script, link, iframe, button')).toHaveCount(0)
  await expect(page.locator('html')).not.toHaveAttribute('data-publication-ran')
  expect(external).toEqual([])

  const passage = chapter.locator('#second')
  const frameBox = (await iframe.boundingBox())!
  const edges = await passage.evaluate(element => {
    const doc = element.ownerDocument
    const start = doc.createRange()
    start.setStart(element.firstChild!, 0)
    start.setEnd(element.firstChild!, 1)
    const end = doc.createRange()
    const last = element.lastChild!
    const length = last.textContent!.length
    end.setStart(last, length - 1)
    end.setEnd(last, length)
    const left = start.getBoundingClientRect()
    const right = end.getBoundingClientRect()
    return { x1: left.left + 1, y1: left.top + left.height / 2, x2: right.right - 1, y2: right.top + right.height / 2 }
  })
  await page.mouse.move(frameBox.x + edges.x1, frameBox.y + edges.y1)
  await page.mouse.down()
  await page.mouse.move(frameBox.x + edges.x2, frameBox.y + edges.y2, { steps: 12 })
  await page.mouse.up()
  await expect.poll(() => passage.evaluate(element => {
    const selection = element.ownerDocument.getSelection()
    return { quote: selection?.toString(), rangeCount: selection?.rangeCount, collapsed: selection?.isCollapsed }
  })).toEqual({ quote: sentence, rangeCount: 1, collapsed: false })
  await expect(page.getByRole('button', { name: 'Save highlight' })).toBeVisible()
  await page.getByRole('textbox', { name: 'Comment' }).fill(comment)
  await page.getByRole('button', { name: 'Save highlight' }).click()
  await expect(page.getByRole('button', { name: 'Save highlight' })).toHaveCount(0)

  await chapter.getByRole('link', { name: 'Open second chapter' }).click()
  await expect(chapter.getByText(followup)).toBeVisible()
  await waitForSavedChapter(page, 'OPS/next.xhtml')
  await page.reload()
  await expect(chapter.getByText(followup)).toBeVisible()

  await page.getByRole('button', { name: 'Library', exact: true }).click()
  await page.getByRole('link', { name: 'Highlights', exact: true }).click()
  await expect(page.getByText(sentence, { exact: true })).toBeVisible()
  await expect(page.getByText(comment, { exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Open passage' }).click()
  await expect(chapter.locator('#second')).toHaveText(sentence)
  await expect(chapter.getByRole('heading', { name: 'First chapter' })).toBeVisible()
  await expect.poll(() => chapter.locator('#second').evaluate(element => {
    const doc = element.ownerDocument
    const registry = (doc.defaultView as unknown as { CSS?: { highlights?: Map<string, Set<Range>> } })?.CSS?.highlights
    const range = Array.from(registry?.get('quire-active') ?? [])[0]
    return range ? {
      quote: range.toString(),
      startsInSecond: element.contains(range.startContainer),
      endsInSecond: element.contains(range.endContainer),
    } : null
  })).toEqual({ quote: sentence, startsInSecond: true, endsInSecond: true })
})

test('recovers an offline edition rejected by the server from the shelf after reloading', async ({ page, context }) => {
  await page.addInitScript(() => { Reflect.deleteProperty(Navigator.prototype, 'serviceWorker') })
  const user = { id: 'native:conflict-test', backend: 'native' }
  let signedIn = false
  const writes: string[] = []
  await page.route('**/quire/v1/bootstrap', route => route.fulfill({ json: {
    version: 1, sync: true, ai: false, aiAvailable: false, login: 'native', password: true, oidc: false,
  } }))
  await page.route('**/auth/v1/me', route => signedIn
    ? route.fulfill({ json: { version: 1, user, csrf_token: 'fixture' } })
    : route.fulfill({ status: 401 }))
  await page.route('**/auth/v1/browser/login', route => {
    signedIn = true
    return route.fulfill({ json: { account_id: user.id } })
  })
  await page.route('**/library/v1/**', route => {
    const request = route.request()
    if (request.method() !== 'POST') return route.fulfill({ json: { items: [] } })
    writes.push('library')
    return route.fulfill({ status: 409, json: { detail: { error: 'metadata_id_conflict' } } })
  })
  await page.route('**/sync/v1/**', route => {
    if (route.request().method() === 'POST') writes.push('reading')
    return route.fulfill({ json: { items: [], next_cursor: '0', has_more: false } })
  })

  await page.goto('/quire/#/settings')
  await page.getByRole('textbox', { name: 'Email' }).fill('conflict@example.test')
  await page.getByLabel('Password', { exact: true }).fill('test-password')
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  await expect(page.getByText(/conflict-test · Quire/)).toBeVisible()
  await page.getByRole('link', { name: 'Library', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Import EPUB', exact: true })).toBeEnabled()

  await context.setOffline(true)
  await expect.poll(() => page.evaluate(() => navigator.onLine)).toBe(false)
  await page.locator('input[type=file]').setInputFiles({
    name: 'edition.epub', mimeType: 'application/epub+zip', buffer: fixture(),
  })
  await expect(page.getByRole('heading', { name: title })).toBeVisible()
  await page.getByRole('button', { name: 'Mark finished', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Resume reading', exact: true })).toBeVisible()
  expect(writes).toEqual([])

  await page.getByRole('link', { name: 'Back to library' }).click()
  await context.setOffline(false)
  const conflict = page.getByRole('region', { name: 'Resolve edition conflicts' })
  await expect(conflict).toBeVisible()
  expect(writes).toEqual(['library'])
  await page.reload()
  await conflict.getByRole('link', { name: title }).click()
  await page.getByRole('button', { name: 'Keep this edition on this device' }).click()
  await expect(page.getByRole('button', { name: 'Keep this edition on this device' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Resume reading', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Read', exact: true })).toBeEnabled()
  await page.getByRole('link', { name: 'Back to library' }).click()
  await expect(conflict).toHaveCount(0)
  expect(writes).toEqual(['library'])
})
