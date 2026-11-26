import { expect, test } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { build } from 'vite'
import { waitForSavedChapter } from './local-replica'

const androidSource = readFileSync(new URL('../../reader/src/main/assets/quire-structural-anchors.js', import.meta.url), 'utf8')
const href = 'OPS/chapter.xhtml'
const html = (body: string) => `<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Fixture</title></head><body>${body}</body></html>`
const repeated = html('<p>same <em>exact</em> sentence</p><p>same <em>exact</em> sentence</p><p>' + 'A longer paragraph. '.repeat(200) + '</p>')
const base = process.env.QUIRE_WEB_TEST_URL || 'http://127.0.0.1:4173/quire/'

let readerModules: Record<string, string>

test.beforeAll(async () => {
  readerModules = Object.fromEntries(await Promise.all(['publication', 'anchors'].map(async name => {
    const entry = fileURLToPath(new URL(`../src/reader/${name}.ts`, import.meta.url))
    const result = await build({
      configFile: false,
      logLevel: 'silent',
      build: { write: false, lib: { entry, formats: ['es'] } },
    })
    const bundle = (Array.isArray(result) ? result[0] : result)
    if (!('output' in bundle)) throw new Error('Expected an emitted reader module')
    const chunk = bundle.output.find(item => item.type === 'chunk')
    if (!chunk) throw new Error('Reader module is missing')
    return [name, chunk.code]
  })))
})

test.beforeEach(async ({ page }) => {
  await page.route(new URL('test-reader', base).href, route => route.fulfill({
    body: '<!doctype html><body></body>', contentType: 'text/html',
  }))
  for (const [name, body] of Object.entries(readerModules)) {
    await page.route(new URL(`test-reader-${name}.js`, base).href, route => route.fulfill({
      body, contentType: 'text/javascript',
    }))
  }
})

test('round-trips exact repeated XHTML ranges through the Android adapter', async ({ page }) => {
  await page.route(new URL(href, base).href, route => route.fulfill({ body: repeated, contentType: 'application/xhtml+xml' }))
  await page.goto(new URL('test-reader', base).href)
  const native = await page.evaluate(async ({ base, href, androidSource }) => {
    const frame = document.createElement('iframe')
    frame.src = new URL(href, base).href
    const ready = new Promise<void>(resolve => frame.addEventListener('load', () => resolve(), { once: true }))
    document.body.append(frame)
    await ready
    const doc = frame.contentDocument!
    const win = frame.contentWindow! as Window & Record<string, any>
    win.readium = { getCurrentSelection: () => ({text: {highlight: doc.getSelection()!.toString()}}), scrollToPosition: () => {} }
    win.eval(androidSource)
    doc.documentElement.style.setProperty('--USER__view', 'readium-scroll-on')
    const range = doc.createRange()
    const p = doc.querySelectorAll('p')[1]
    range.setStart(p.firstChild!, 0)
    range.setEnd(p.lastChild!, p.lastChild!.textContent!.length)
    doc.getSelection()!.addRange(range)
    return win.quireStructuralAnchors.capture(href)
  }, { base, href, androidSource })
  expect(native.anchor.start.path).toEqual([1, 0])
  const result = await page.evaluate(async ({ base, href, repeated, native }) => {
    const { chapterShell, mountChapter, readerSandbox } = await import(new URL('test-reader-publication.js', base).href) as typeof import('../src/reader/publication')
    const { captureRange, resolveRange } = await import(new URL('test-reader-anchors.js', base).href) as typeof import('../src/reader/anchors')
    const frame = document.createElement('iframe')
    frame.setAttribute('sandbox', readerSandbox)
    frame.srcdoc = chapterShell
    const ready = new Promise<void>(resolve => frame.addEventListener('load', () => resolve(), { once: true }))
    document.body.append(frame)
    await ready
    const doc = frame.contentDocument!
    const makeEpub = (source: string) => ({ text: () => source, bytes: () => undefined, mediaType: () => undefined }) as unknown as Parameters<typeof mountChapter>[1]
    mountChapter(doc, makeEpub(repeated), href)
    const androidLocator = { href, text: native.text, locations: { quireDomRange: JSON.stringify(native.anchor) } }
    const range = (await resolveRange(doc, androidLocator, native.text.highlight))!
    const web = await captureRange(doc, {href}, range, range.toString())
    const second = range.startContainer.parentElement === doc.querySelectorAll('p')[1]
    mountChapter(doc, makeEpub(repeated.replace('A longer', 'A revised')), href)
    const stale = await resolveRange(doc, androidLocator, native.text.highlight)
    const win = (document.querySelector('iframe')!).contentWindow! as Window & Record<string, any>
    return { web, second, stale: !!stale, androidAcceptsWeb: win.quireStructuralAnchors.visible(web).available }
  }, { base, href, repeated, native })
  expect(result.second).toBe(true)
  expect(result.stale).toBe(false)
  expect(result.androidAcceptsWeb).toBe(true)
  expect(JSON.parse(String(result.web?.locations?.quireDomRange))).toEqual(native.anchor)
})

test('renders local images without publication scripts or requests', async ({ page }) => {
  const leaks: string[] = []
  await page.route(/reader-leak(?:\.example)?\//, route => {
    leaks.push(route.request().url())
    return route.abort()
  })
  await page.goto(new URL('test-reader', base).href)
  const content = html(`<p>Safe to read</p><style>@import url('https://reader-leak.example/css');</style>
<script>parent.document.body.dataset.leak = "script"</script>
<meta http-equiv="refresh" content="0; url=/quire/reader-leak/redirect" />
<img src="../images/local.svg" onload="parent.document.body.dataset.leak = 'event'" />
<img src="https://reader-leak.example/image" srcset="https://reader-leak.example/srcset 1x" />
<iframe src="/quire/reader-leak/frame" srcdoc="&lt;script&gt;parent.document.body.dataset.leak = 'nested'&lt;/script&gt;"></iframe>
<form action="/quire/reader-leak/form"><input /></form>
<a href="https://reader-leak.example/link" ping="/quire/reader-leak/ping" target="_top">Outside</a>
<a href="javascript:parent.document.body.dataset.leak = 'javascript'">Script URL</a>`)
  const result = await page.evaluate(async ({ base, content, href }) => {
    const { chapterShell, mountChapter, readerSandbox } = await import(new URL('test-reader-publication.js', base).href) as typeof import('../src/reader/publication')
    const frame = document.createElement('iframe')
    frame.setAttribute('sandbox', readerSandbox)
    frame.srcdoc = chapterShell
    const ready = new Promise<void>(resolve => frame.addEventListener('load', () => resolve(), { once: true }))
    document.body.append(frame)
    await ready
    const doc = frame.contentDocument!
    const bytes = new TextEncoder().encode(`<svg xmlns="http://www.w3.org/2000/svg" width="5" height="5">
      <rect width="5" height="5"/><image href="https://reader-leak.example/svg-image" width="5" height="5"/>
      <script>parent.document.body.dataset.leak = 'svg'</script></svg>`)
    mountChapter(doc, { text: () => content, bytes: (path: string) => path === 'images/local.svg' ? bytes : undefined,
      mediaType: () => 'image/svg+xml' } as unknown as Parameters<typeof mountChapter>[1], href)
    const image = doc.querySelector('img')!
    await image.decode()
    const script = doc.createElement('script')
    script.textContent = "parent.document.body.dataset.leak = 'inline'"
    doc.body.append(script)
    script.remove()
    let parentClick = false
    doc.addEventListener('click', () => { parentClick = true }, { once: true })
    doc.querySelector('p')!.click()
    doc.querySelector('form')!.requestSubmit()
    await new Promise(resolve => setTimeout(resolve, 50))
    return {
      parentClick,
      loadedImage: image.naturalWidth,
      canExecute: document.body.dataset.leak !== undefined,
      embeds: doc.querySelectorAll('script, iframe, input, body meta, [ping], [target], [action]').length,
      scriptLink: doc.querySelectorAll('a[href]').length,
      location: doc.URL,
      chapterText: doc.querySelector('p')!.textContent,
      link: doc.querySelector('a')!.getAttribute('href'),
    }
  }, { base, content, href })
  expect(result).toEqual({ parentClick: true, loadedImage: 5, canExecute: false, embeds: 0, scriptLink: 1,
    location: 'about:srcdoc', chapterText: 'Safe to read', link: '#' })
  expect(leaks).toEqual([])
})

test('sizes a fixed page and applies package-local CSS and a bundled font', async ({page}) => {
  const contents = readFileSync(new URL('../../reader/src/main/assets/fonts/Literata.ttf', import.meta.url)).toString('base64')
  await page.goto(new URL('test-reader', base).href)
  const result = await page.evaluate(async ({ base, contents }) => {
    const { chapterShell, mountChapter, readerSandbox } = await import(new URL('test-reader-publication.js', base).href) as typeof import('../src/reader/publication')
    const frame = document.createElement('iframe')
    frame.setAttribute('sandbox', readerSandbox)
    frame.srcdoc = chapterShell
    frame.width = '300'
    frame.height = '400'
    const ready = new Promise<void>(resolve => frame.addEventListener('load', () => resolve(), { once: true }))
    document.body.append(frame)
    await ready
    const chapter = '<html xmlns="http://www.w3.org/1999/xhtml"><head><meta name="viewport" content="width=600,height=800"/><link rel="stylesheet" href="../styles/main.css"/></head><body><p>Fixed page</p></body></html>'
    const css = '@font-face {font-family: Fixture; src:url("../fonts/local.ttf");} p {position:absolute; top:200px; font-family: Fixture;}'
    const font = Uint8Array.from(atob(contents), c => c.charCodeAt(0))
    const doc = frame.contentDocument!
    mountChapter(doc, {
      text: (path: string) => path === 'OPS/styles/main.css' ? css : chapter,
      bytes: (path: string) => path === 'OPS/styles/main.css' ? new TextEncoder().encode(css)
        : path === 'OPS/fonts/local.ttf' ? font : undefined,
      mediaType: (path: string) => path.endsWith('.css') ? 'text/css' : 'font/ttf',
    } as unknown as Parameters<typeof mountChapter>[1], 'OPS/text/chapter.xhtml')
    const paragraph = doc.querySelector('p')!
    const layout = doc.defaultView!.getComputedStyle(paragraph)
    const faces = await doc.fonts.load('16px Fixture')
    return {position: layout.position, family: layout.fontFamily, top: paragraph.getBoundingClientRect().top,
      width: doc.body.getBoundingClientRect().width, loaded: faces[0]?.status}
  }, {base, contents})
  expect(result).toMatchObject({position: 'absolute', family: 'Fixture', width: 300, loaded: 'loaded'})
  expect(result.top).toBeGreaterThanOrEqual(100)
  expect(result.top).toBeLessThan(125)
})


test('preserves a private pause through chapter changes, reader resize and reload', async ({ page }) => {
  const { execFileSync } = await import('node:child_process')
  const { mkdtempSync, rmSync } = await import('node:fs')
  const { tmpdir } = await import('node:os')
  const { join } = await import('node:path')
  const temp = mkdtempSync(join(tmpdir(), 'quire-reader-'))
  try {
    const file = join(temp, 'fixture.epub')
    execFileSync('python3', ['../scripts/make-reader-pause-fixture.py', file])
    await page.goto(base)
    await expect(page.getByRole('button', {name: 'Import EPUB', exact: true}).first()).toBeEnabled()
    await page.locator('input[type=file]').setInputFiles(file)
    await page.getByRole('button', {name: 'Read', exact: true}).click()
  } finally {
    rmSync(temp, { recursive: true, force: true })
  }

  const chapter = page.frameLocator('.qr-frame')
  const marker = chapter.locator('#nested')
  // Pages turn sideways, so the passage starts somewhere on the page the reader shows.
  const isOnPage = () => marker.evaluate(element => {
    const rect = element.getClientRects()[0]
    return rect.left >= 0 && rect.right <= element.ownerDocument.documentElement.clientWidth
      && rect.top >= 0 && rect.bottom <= element.ownerDocument.documentElement.clientHeight
  })
  await page.getByRole('button', {name: 'Contents', exact: true}).click()
  await page.getByRole('button', {name: 'Nested section', exact: true}).click()
  await expect.poll(isOnPage).toBe(true)

  await page.getByRole('button', {name: 'Appearance', exact: true}).click()
  await page.getByLabel('Text size').fill('30')
  await page.getByRole('button', {name: 'Close panel'}).click()
  await expect.poll(isOnPage).toBe(true)

  await page.getByRole('button', {name: 'Pause & return'}).click()
  await page.getByLabel('Private note').fill('This stays on this device.')
  await page.getByRole('button', {name: 'Save current position'}).click()
  await expect(page.getByRole('button', {name: 'Return to saved passage'})).toBeVisible()
  await page.getByRole('button', {name: 'Contents', exact: true}).click()
  await page.getByRole('button', {name: 'Part two', exact: true}).click()
  await expect(chapter.getByRole('heading', {name: 'Part 2'})).toBeVisible()
  await waitForSavedChapter(page, 'EPUB/part2.xhtml')
  await page.reload()
  await expect(chapter.getByRole('heading', {name: 'Part 2'})).toBeVisible()
  await page.getByRole('button', {name: 'Pause & return'}).click()
  await expect(page.getByLabel('Private note')).toHaveValue('This stays on this device.')
  await page.getByRole('button', {name: 'Return to saved passage'}).click()
  await expect.poll(isOnPage).toBe(true)
})

test('verifies an APK-recorded repeated passage and refuses the changed edition', async ({ page }) => {
  const { execFileSync } = await import('node:child_process')
  const { mkdtempSync, rmSync } = await import('node:fs')
  const { tmpdir } = await import('node:os')
  const { join } = await import('node:path')
  const { unzipSync, strFromU8 } = await import('fflate')
  const apk = JSON.parse(readFileSync(new URL('./fixtures/android-v2026.11.22.346.json', import.meta.url), 'utf8'))
  const locator = JSON.parse(apk.highlight.locator)
  const temp = mkdtempSync(join(tmpdir(), 'quire-apk-anchor-'))
  let source: string
  try {
    const file = join(temp, 'fixture.epub')
    execFileSync('python3', ['../scripts/make-reader-pause-fixture.py', file])
    source = strFromU8(unzipSync(readFileSync(file))[locator.href])
  } finally {
    rmSync(temp, { recursive: true, force: true })
  }

  await page.goto(new URL('test-reader', base).href)
  const result = await page.evaluate(async ({ base, locator, source, quote, comment }) => {
    const { chapterShell, mountChapter, readerSandbox } = await import(new URL('test-reader-publication.js', base).href) as typeof import('../src/reader/publication')
    const { resolveRange } = await import(new URL('test-reader-anchors.js', base).href) as typeof import('../src/reader/anchors')
    const frame = document.createElement('iframe')
    frame.setAttribute('sandbox', readerSandbox)
    frame.srcdoc = chapterShell
    const ready = new Promise<void>(resolve => frame.addEventListener('load', () => resolve(), { once: true }))
    document.body.append(frame)
    await ready
    const doc = frame.contentDocument!
    const book = (text: string) => ({ text: () => text, bytes: () => undefined, mediaType: () => undefined }) as unknown as Parameters<typeof mountChapter>[1]
    mountChapter(doc, book(source), locator.href)
    const range = await resolveRange(doc, locator, quote)
    const paragraph = range?.startContainer.parentElement?.textContent
    const available = range?.toString()
    // Change nearby chapter text while retaining the same repeated quotation and path.
    mountChapter(doc, book(source.replace('quiet river', 'shallow river')), locator.href)
    const stale = await resolveRange(doc, locator, quote)
    const occurrences = [...doc.querySelectorAll('p')].filter(p => p.textContent?.includes(quote)).length
    return { available, paragraph, stale: !!stale, occurrences, stored: { quote, comment } }
  }, { base, locator, source, quote: apk.highlight.text, comment: apk.highlight.comment })
  expect(result.paragraph).toMatch(/^Marker 1-17\./)
  expect(result.available).toBe('Mira')
  expect(result.occurrences).toBe(60)
  expect(result.stale).toBe(false)
  expect(result.stored).toEqual({ quote: 'Mira', comment: 'Android note for Marker 1-17' })
})

test('turns one page per trackpad swipe, even when the next swipe starts during the inertia tail', async ({ page }) => {
  const { execFileSync } = await import('node:child_process')
  const { mkdtempSync, rmSync } = await import('node:fs')
  const { tmpdir } = await import('node:os')
  const { join } = await import('node:path')
  const temp = mkdtempSync(join(tmpdir(), 'quire-reader-'))
  try {
    const file = join(temp, 'fixture.epub')
    execFileSync('python3', ['../scripts/make-reader-pause-fixture.py', file])
    await page.goto(base)
    await expect(page.getByRole('button', {name: 'Import EPUB', exact: true}).first()).toBeEnabled()
    await page.locator('input[type=file]').setInputFiles(file)
    await page.getByRole('button', {name: 'Read', exact: true}).click()
  } finally {
    rmSync(temp, { recursive: true, force: true })
  }
  const frame = page.frameLocator('.qr-frame')
  await expect(frame.locator('p').first()).toBeVisible()
  const pageNumber = () => frame.locator('html').evaluate(root =>
    Math.round((root.ownerDocument.scrollingElement?.scrollLeft ?? 0) / root.clientWidth))
  const box = await page.locator('.qr-frame').boundingBox()
  await page.mouse.move(box!.x + box!.width / 2, box!.y + box!.height / 2)
  // A swipe left, its fading tail, then a second swipe before the tail has gone quiet.
  for (const delta of [8, 30, 60, 45, 25, 12, 6, 3, 2, 4, 15, 40, 60, 30, 10, 4]) await page.mouse.wheel(delta, 0)
  await expect.poll(pageNumber).toBe(2)
  for (const delta of [-10, -40, -60, -20, -5]) await page.mouse.wheel(delta, 0)
  await expect.poll(pageNumber).toBe(1)
})

test('in full screen a click on the right or left of the page turns it', async ({ page }) => {
  const { execFileSync } = await import('node:child_process')
  const { mkdtempSync, rmSync } = await import('node:fs')
  const { tmpdir } = await import('node:os')
  const { join } = await import('node:path')
  const temp = mkdtempSync(join(tmpdir(), 'quire-reader-'))
  try {
    const file = join(temp, 'fixture.epub')
    execFileSync('python3', ['../scripts/make-reader-pause-fixture.py', file])
    await page.goto(base)
    await expect(page.getByRole('button', {name: 'Import EPUB', exact: true}).first()).toBeEnabled()
    await page.locator('input[type=file]').setInputFiles(file)
    await page.getByRole('button', {name: 'Read', exact: true}).click()
  } finally {
    rmSync(temp, { recursive: true, force: true })
  }
  const frame = page.frameLocator('.qr-frame')
  await expect(frame.locator('p').first()).toBeVisible()
  const pageNumber = () => frame.locator('html').evaluate(root =>
    Math.round((root.ownerDocument.scrollingElement?.scrollLeft ?? 0) / root.clientWidth))
  const click = async (across: number) => {
    const box = (await page.locator('.qr-frame').boundingBox())!
    await page.mouse.click(box.x + box.width * across, box.y + box.height / 2)
  }
  // Outside full screen a click is only a click.
  await click(0.9)
  await page.waitForTimeout(300)
  expect(await pageNumber()).toBe(0)
  const fullscreen = page.getByRole('button', { name: 'Fullscreen', exact: true })
  test.skip(!await fullscreen.count(), 'this browser offers no full screen')
  await fullscreen.click()
  await expect.poll(() => page.evaluate(() => !!document.fullscreenElement)).toBe(true)
  await click(0.9)
  await expect.poll(pageNumber).toBe(1)
  await click(0.9)
  await expect.poll(pageNumber).toBe(2)
  await click(0.1)
  await expect.poll(pageNumber).toBe(1)
  // Two quick clicks turn two pages instead of selecting a word.
  const box = (await page.locator('.qr-frame').boundingBox())!
  await page.mouse.dblclick(box.x + box.width * 0.8, box.y + box.height / 2)
  await expect.poll(pageNumber).toBe(3)
  expect(await frame.locator('html').evaluate(root => root.ownerDocument.getSelection()?.toString())).toBe('')
  await click(0.1)
  await click(0.1)
  await expect.poll(pageNumber).toBe(1)
  // The middle is left alone.
  await click(0.5)
  await page.waitForTimeout(300)
  expect(await pageNumber()).toBe(1)
})
