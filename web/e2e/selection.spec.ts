import { expect, test, type Page } from '@playwright/test'
import { strToU8, zipSync } from 'fflate'

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
const links = '<p><a id="outside" href="https://example.org/river">The river survey</a> and <a id="inside" href="#p40">the ferry</a>.</p>'
const chapter = `<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Text</title></head><body>${links}${paragraphs}</body></html>`

function fixture() {
  return Buffer.from(zipSync({
    mimetype: [strToU8('application/epub+zip'), { level: 0 }],
    'META-INF/container.xml': strToU8(container),
    'OPS/book.opf': strToU8(opf),
    'OPS/text.xhtml': strToU8(chapter),
  }))
}

async function open(page: Page) {
  await page.goto('/quire/')
  await page.locator('input[type=file]').first().setInputFiles({ name: 'selection.epub', mimeType: 'application/epub+zip', buffer: fixture() })
  await expect(page.getByRole('heading', { name: title })).toBeVisible()
  await page.getByRole('button', { name: 'Read', exact: true }).click()
  const iframe = page.locator(`iframe[title="${title}"]`)
  const chapterFrame = iframe.contentFrame()
  await expect(chapterFrame.locator('#p0')).toBeVisible()
  const body = chapterFrame.locator('body')
  // Where the reader is: the page shown and the size of the frame its columns are cut to.
  const where = async () => ({
    ...await body.evaluate(element => {
      const doc = element.ownerDocument
      return { page: doc.scrollingElement!.scrollLeft, height: doc.documentElement.clientHeight }
    }),
    frame: await iframe.boundingBox(),
  })
  return { iframe, chapterFrame, body, where }
}

for (const width of [390, 1280]) {
  test.describe(`at ${width} px`, () => {
    test.use({ viewport: { width, height: 844 } })

    for (const which of ['first', 'last'] as const) {
      test(`selecting the ${which} passage on a page leaves the page still and the passage uncovered`, async ({ page }) => {
        const { iframe, chapterFrame, body, where } = await open(page)
        await page.getByRole('button', { name: 'Next', exact: true }).click()
        await page.getByRole('button', { name: 'Next', exact: true }).click()
        await expect.poll(() => body.evaluate(element => element.ownerDocument.scrollingElement!.scrollLeft)).toBeGreaterThan(0)

        // A passage wholly on this page, where a page that lost height would push the last one onto the next.
        const id = await body.evaluate((element, which) => {
          const doc = element.ownerDocument
          const width = doc.documentElement.clientWidth
          const height = doc.documentElement.clientHeight
          const shown = Array.from(doc.querySelectorAll('p[id]')).filter(p => {
            const rect = p.getBoundingClientRect()
            return rect.left >= 0 && rect.right <= width && rect.top >= 0 && rect.bottom <= height
          })
          return shown[which === 'first' ? 0 : shown.length - 1].id
        }, which)
        const passage = chapterFrame.locator(`#${id}`)
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

        const bar = page.getByRole('group', { name: 'Selected text' })
        await expect(bar.getByRole('button', { name: 'Save highlight' })).toBeVisible()
        expect(await where()).toEqual(before)
        const text = await passage.textContent()
        await expect.poll(() => passage.evaluate(element => element.ownerDocument.getSelection()?.toString().trim())).toBe(text)
        await expect(passage).toBeInViewport()

        // The bar keeps clear of the selected text and of the touch handles drawn just past its ends.
        const handle = 24
        const selection = await passage.evaluate(element => {
          const rect = element.ownerDocument.getSelection()!.getRangeAt(0).getBoundingClientRect()
          return { top: rect.top, bottom: rect.bottom }
        })
        const covered = async () => {
          const drawn = (await page.locator('.qr-overlay').boundingBox())!
          return drawn.y < box.y + selection.bottom + handle && drawn.y + drawn.height > box.y + selection.top - handle
        }
        expect(await covered()).toBe(false)

        // A notice joins the bar over the page: the page keeps its size and the selection stays clear.
        await bar.getByRole('button', { name: 'Copy quote' }).click()
        await expect(page.getByRole('status').filter({ hasText: /quote/i })).toBeVisible()
        expect(await where()).toEqual(before)
        expect(await covered()).toBe(false)

        // Reachable from the keyboard: stepping back from the page controls lands in the bar.
        await page.getByRole('button', { name: 'Previous', exact: true }).focus()
        await page.keyboard.press('Shift+Tab')
        await expect(bar.getByRole('textbox', { name: 'Comment' })).toBeFocused()
      })
    }

    test('notices and the way back from a link leave the page its size', async ({ page }) => {
      const { chapterFrame, where } = await open(page)
      const before = await where()

      await chapterFrame.locator('#outside').click()
      await expect(page.getByRole('link', { name: /Open external link/ })).toBeVisible()
      expect(await where()).toEqual(before)
      await page.getByRole('button', { name: 'Cancel' }).click()
      await expect(page.getByRole('link', { name: /Open external link/ })).toBeHidden()

      await chapterFrame.locator('#inside').click()
      await expect(page.getByRole('button', { name: 'Back to previous position' })).toBeVisible()
      await expect(chapterFrame.locator('#p40')).toBeInViewport()
      const followed = await where()
      expect(followed.height).toBe(before.height)
      expect(followed.frame).toEqual(before.frame)
      await page.waitForTimeout(300)
      expect(await where()).toEqual(followed)
    })
  })
}
