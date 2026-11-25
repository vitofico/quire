import { expect, test } from '@playwright/test'
import { strToU8, zipSync } from 'fflate'

test.use({ viewport: { width: 412, height: 839 } })

const title = 'Selection Trial'
const container = '<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OPS/book.opf"/></rootfiles></container>'
const opf = `<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>${title}</dc:title>
  <dc:identifier id="id">urn:uuid:5d0c3a52-6f0e-4b8e-9a51-2f1d8c7e4a10</dc:identifier></metadata>
  <manifest><item id="text" href="text.xhtml" media-type="application/xhtml+xml"/></manifest>
  <spine><itemref idref="text"/></spine>
</package>`
const paragraphs = Array.from({ length: 60 }, (_, i) =>
  `<p id="p${i}">Paragraph ${i}: the river cut a new channel through the reeds, and the post stood alone.</p>`).join('')
const chapter = `<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Text</title></head><body>${paragraphs}</body></html>`

function fixture() {
  return Buffer.from(zipSync({
    mimetype: [strToU8('application/epub+zip'), { level: 0 }],
    'META-INF/container.xml': strToU8(container),
    'OPS/book.opf': strToU8(opf),
    'OPS/text.xhtml': strToU8(chapter),
  }))
}

test('selecting the last passage on a phone page leaves the page and the passage where they are', async ({ page }) => {
  await page.goto('/quire/')
  await page.locator('input[type=file]').first().setInputFiles({ name: 'selection.epub', mimeType: 'application/epub+zip', buffer: fixture() })
  await expect(page.getByRole('heading', { name: title })).toBeVisible()
  await page.getByRole('button', { name: 'Read', exact: true }).click()
  const iframe = page.locator(`iframe[title="${title}"]`)
  const chapterFrame = iframe.contentFrame()
  await expect(chapterFrame.locator('#p0')).toBeVisible()
  await page.getByRole('button', { name: 'Next', exact: true }).click()
  await page.getByRole('button', { name: 'Next', exact: true }).click()

  const body = chapterFrame.locator('body')
  await expect.poll(() => body.evaluate(element => element.ownerDocument.scrollingElement!.scrollLeft)).toBeGreaterThan(0)
  // The last paragraph wholly on this page, where a page that lost height would push it onto the next.
  const id = await body.evaluate(element => {
    const doc = element.ownerDocument
    const width = doc.documentElement.clientWidth
    const height = doc.documentElement.clientHeight
    const shown = Array.from(doc.querySelectorAll('p')).filter(p => {
      const rect = p.getBoundingClientRect()
      return rect.left >= 0 && rect.right <= width && rect.top >= 0 && rect.bottom <= height
    })
    return shown[shown.length - 1].id
  })
  const passage = chapterFrame.locator(`#${id}`)
  const where = () => body.evaluate(element => {
    const doc = element.ownerDocument
    return { page: doc.scrollingElement!.scrollLeft, height: doc.documentElement.clientHeight }
  })
  const before = await where()

  const box = (await iframe.boundingBox())!
  const edges = await passage.evaluate(element => {
    const range = element.ownerDocument.createRange()
    range.selectNodeContents(element)
    const rects = Array.from(range.getClientRects()).filter(rect => rect.width)
    const first = rects[0]
    const last = rects[rects.length - 1]
    return { x1: first.left + 1, y1: first.top + first.height / 2, x2: last.right - 1, y2: last.top + last.height / 2 }
  })
  await page.mouse.move(box.x + edges.x1, box.y + edges.y1)
  await page.mouse.down()
  await page.mouse.move(box.x + edges.x2, box.y + edges.y2, { steps: 12 })
  await page.mouse.up()

  await expect(page.getByRole('button', { name: 'Save highlight' })).toBeVisible()
  expect(await where()).toEqual(before)
  const text = await passage.textContent()
  await expect.poll(() => passage.evaluate(element => element.ownerDocument.getSelection()?.toString().trim())).toBe(text)
  await expect(passage).toBeInViewport()
})
