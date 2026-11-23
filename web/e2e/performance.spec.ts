import { expect, test } from '@playwright/test'
import { crc32, deflateSync } from 'node:zlib'
import { strToU8, zipSync } from 'fflate'

test.beforeEach(async ({ context }) => {
  await context.route('**/quire/v1/bootstrap', route => route.fulfill({ json: {
    version: 1, sync: false, ai: false, aiAvailable: false, login: 'none',
    password: false, oidc: false, catalogUrl: null, accountManagement: null,
  } }))
})

test.use({ serviceWorkers: 'block' })

const bookCount = 1500
const passageCount = 5000
const budgets = { coldShelfMs: 1500, shelfSearchMs: 500, passageSearchMs: 700, scrollP95Ms: 55, firstChapterMs: 3500, epubHeapDeltaMiB: 150 }

test('cold shelf, long-shelf scrolling and search on a 1,500-book, 5,000-passage replica', async ({ page: seed, context, browserName }) => {
  test.skip(browserName !== 'chromium', 'Desktop Chromium provides the reference timing environment.')
  await seed.goto('/quire/')
  await expect(seed.getByRole('button', { name: 'Import EPUB' })).toBeEnabled()
  await seed.evaluate(async ({ bookCount, passageCount }) => {
    const db = await new Promise<IDBDatabase>((resolve, reject) => {
      const request = indexedDB.open(`quire-web:${JSON.stringify([location.origin, 'local', ''])}`, 1)
      request.onsuccess = () => resolve(request.result)
      request.onerror = () => reject(request.error)
    })
    const tx = db.transaction(['books', 'localProgress', 'localHighlights'], 'readwrite')
    const completed = new Promise<void>((resolve, reject) => {
      tx.oncomplete = () => resolve()
      tx.onabort = () => reject(tx.error)
    })
    for (let index = 0; index < bookCount; index++) {
      const key = index.toString(16).padStart(64, '0')
      const title = `Bench volume ${String(index).padStart(4,'0')}`
      const document = { content_hash: key }
      tx.objectStore('books').put({ book: { key, content_hash: key, title, authors: [`Writer ${index % 105}`] }, localOnly: false }, key)
      if (index % 2 === 0) tx.objectStore('localProgress').put({ document, percent: .2, locator: '{}', client_updated_at: '2026-01-01T00:00:00Z' }, key)
    }
    for (let index = 0; index < passageCount; index++) {
      const id = String(index)
      const content_hash = (index % bookCount).toString(16).padStart(64, '0')
      tx.objectStore('localHighlights').put({
        id, document: { content_hash }, locator: '{}', colour: 'YELLOW',
        text: `A representative passage for full-text search. ${'With a line or two of excerpted prose. '.repeat(4)}`,
        book_title: `Bench volume ${index % bookCount}`, book_author: 'Writer',
        created_at: index, updated_at: index,
        comment: index === passageCount - 1 ? 'singleton-search-note' : `Reading note ${index}`,
      }, id)
    }
    await completed
    db.close()
  }, { bookCount, passageCount })

  const page = await context.newPage()
  await seed.close()
  const cdp = await context.newCDPSession(page)
  await cdp.send('Network.enable')
  await cdp.send('Network.setCacheDisabled', { cacheDisabled: true })
  await page.goto('/quire/')
  await expect(page.locator('.book-entry')).toHaveCount(bookCount)
  const coldShelfMs = await page.evaluate(() => performance.now())

  const scrollP95Ms = await page.evaluate(async () => {
    const samples: number[] = []
    let previous = await new Promise<number>(requestAnimationFrame)
    for (let i = 0; i < 30; i++) {
      window.scrollBy(0, window.innerHeight * 0.75)
      const next = await new Promise<number>(requestAnimationFrame)
      samples.push(next - previous)
      previous = next
    }
    window.scrollTo(0, 0)
    return samples.sort((a, b) => a - b)[Math.ceil(samples.length * .95) - 1]
  })

  const search = page.getByRole('searchbox', { name: 'Search books' })
  const shelfSearchStart = await page.evaluate(() => performance.now())
  await search.fill('Bench volume 1499')
  await expect(page.locator('.book-entry')).toHaveCount(1)
  const shelfSearchMs = await page.evaluate(start => performance.now() - start, shelfSearchStart)

  await page.goto('/quire/#/highlights')
  const passages = page.getByRole('searchbox', { name: 'Search passages or comments' })
  await expect(page.locator('.quote')).toHaveCount(passageCount)
  const passageSearchStart = await page.evaluate(() => performance.now())
  await passages.fill('singleton-search-note')
  await expect(page.locator('.quote')).toHaveCount(1)
  const passageSearchMs = await page.evaluate(start => performance.now() - start, passageSearchStart)

  const metrics = { coldShelfMs, shelfSearchMs, passageSearchMs, scrollP95Ms }
  console.log('Quire shelf benchmark', JSON.stringify(metrics))
  for (const [name, value] of Object.entries(metrics)) expect(value, name).toBeLessThan(budgets[name as keyof typeof budgets])
})

function image() {
  const dimension = 1024
  const scanlines = Buffer.alloc((dimension + 1) * dimension)
  let state = 0x1234abcd
  for (let y = 0; y < dimension; y++) {
    for (let x = 1; x <= dimension; x++) {
      state ^= state << 13
      state ^= state >>> 17
      state ^= state << 5
      scanlines[y * (dimension + 1) + x] = state & 0xff
    }
  }
  function chunk(type: string, bytes: Buffer) {
    const length = Buffer.alloc(4)
    length.writeUInt32BE(bytes.length)
    const data = Buffer.concat([Buffer.from(type), bytes])
    const checksum = Buffer.alloc(4)
    checksum.writeUInt32BE(crc32(data))
    return Buffer.concat([length, data, checksum])
  }
  const layout = Buffer.alloc(13)
  layout.writeUInt32BE(dimension)
  layout.writeUInt32BE(dimension, 4)
  layout[8] = 8
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', layout),
    chunk('IDAT', deflateSync(scanlines)), chunk('IEND', Buffer.alloc(0))])
}

function largeEpub() {
  const cover = image()
  const parts: Record<string, Uint8Array> = {
    'META-INF/container.xml': strToU8('<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="book.opf"/></rootfiles></container>'),
  }
  const manifest: string[] = []
  const spine: string[] = []
  for (let index = 0; index < 32; index++) {
    parts[`page${index}.png`] = cover
    parts[`chapter${index}.xhtml`] = strToU8(`<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Bench chapter ${index + 1}</title></head><body><h1>Bench chapter ${index + 1}</h1><img src="page${index}.png" alt="Scanned test page"/></body></html>`)
    manifest.push(`<item id="i${index}" href="page${index}.png" media-type="image/png"/><item id="c${index}" href="chapter${index}.xhtml" media-type="application/xhtml+xml"/>`)
    spine.push(`<itemref idref="c${index}"/>`)
  }
  parts['book.opf'] = strToU8(`<package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" unique-identifier="id"><metadata><dc:title>Large benchmark edition</dc:title><dc:identifier id="id">urn:uuid:51812586-c727-4e8e-889f-8d9a575f8ae2</dc:identifier></metadata><manifest>${manifest.join('')}</manifest><spine>${spine.join('')}</spine></package>`)
  return Buffer.from(zipSync(parts, { level: 0 }))
}

test('opens the first illustrated chapter of a 32 MiB EPUB within time and heap budgets', async ({ context, page, browserName }) => {
  test.skip(browserName !== 'chromium', 'The reference memory check uses Chromium DevTools.')
  const epub = largeEpub()
  expect(epub.byteLength).toBeGreaterThan(32 * 1024 * 1024)
  await page.goto('/quire/')
  await page.locator('input[type=file]').setInputFiles({ name: 'large-benchmark.epub', mimeType: 'application/epub+zip', buffer: epub })
  await expect(page.getByRole('heading', { name: 'Large benchmark edition' })).toBeVisible()
  const session = await context.newCDPSession(page)
  const used = async () => {
    await session.send('HeapProfiler.collectGarbage')
    const heap = await session.send('Runtime.getHeapUsage') as { usedSize: number; backingStorageSize: number }
    return heap.usedSize + heap.backingStorageSize
  }
  const baseline = await used()
  const start = await page.evaluate(() => performance.now())
  await page.getByRole('button', { name: 'Read', exact: true }).click()
  const chapter = page.frameLocator('.qr-frame')
  await expect(chapter.getByRole('heading', { name: 'Bench chapter 1' })).toBeVisible()
  await chapter.getByRole('img', { name: 'Scanned test page' }).evaluate(image => (image as HTMLImageElement).decode())
  const firstChapterMs = await page.evaluate(value => performance.now() - value, start)
  const epubHeapDeltaMiB = (await used() - baseline) / (1024 * 1024)
  console.log('Quire EPUB benchmark', JSON.stringify({ epubMiB: epub.length / (1024 * 1024), firstChapterMs, epubHeapDeltaMiB }))
  expect(firstChapterMs).toBeLessThan(budgets.firstChapterMs)
  expect(epubHeapDeltaMiB).toBeLessThan(budgets.epubHeapDeltaMiB)
})
