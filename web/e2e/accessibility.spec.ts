import { expect, test } from '@playwright/test'
import { strToU8, zipSync } from 'fflate'
import { waitForSavedChapter } from './local-replica'

test.beforeEach(async ({ page }) => {
  await page.route('**/quire/v1/bootstrap', route => route.fulfill({ json: {
    version: 1, sync: false, ai: false, aiAvailable: false, login: 'none',
    password: false, oidc: false, catalogUrl: null, accountManagement: null,
  } }))
})

function fixture() {
  const files = {
    'META-INF/container.xml': strToU8('<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="book.opf"/></rootfiles></container>'),
    'book.opf': strToU8('<package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" unique-identifier="id"><metadata><dc:title>Keyboard Reader</dc:title><dc:identifier id="id">urn:uuid:e32b31c4-8f11-4761-a09d-ed5ca5704429</dc:identifier><dc:language>ar</dc:language></metadata><manifest><item id="first" href="one.xhtml" media-type="application/xhtml+xml"/><item id="second" href="two.xhtml" media-type="application/xhtml+xml"/></manifest><spine page-progression-direction="rtl"><itemref idref="first"/><itemref idref="second"/></spine></package>'),
    'one.xhtml': strToU8('<html xmlns="http://www.w3.org/1999/xhtml" dir="rtl" lang="ar"><head><title>One</title></head><body><p id="selectable">مرحبا بك في الاختبار</p><a href="two.xhtml">الفصل الثاني</a></body></html>'),
    'two.xhtml': strToU8('<html xmlns="http://www.w3.org/1999/xhtml" dir="rtl" lang="ar"><head><title>Two</title></head><body><h1>الفصل التالي</h1></body></html>'),
  }
  return { name: 'keyboard.epub', mimeType: 'application/epub+zip', buffer: Buffer.from(zipSync(files)) }
}

test('keyboard import, frame and panel focus, annotation controls, RTL and offline restart', async ({ page, context, browserName }) => {
  await page.goto('/quire/')
  const importButton = page.getByRole('button', { name: 'Import EPUB' })
  await expect(importButton).toBeEnabled()
  const tab = browserName === 'webkit' && process.platform === 'darwin' ? 'Alt+Tab' : 'Tab'
  await page.keyboard.press(tab)
  await expect(page.getByRole('link', { name: 'Skip to content' })).toBeFocused()
  await page.keyboard.press('Enter')
  await expect(page.getByRole('main')).toBeFocused()
  await page.keyboard.press(tab)
  await expect(page.getByRole('button', { name: 'Import EPUB' })).toBeFocused()
  const filePicker = page.waitForEvent('filechooser')
  await page.keyboard.press('Enter')
  await (await filePicker).setFiles(fixture())
  await expect(page.getByRole('heading', { name: 'Keyboard Reader' })).toBeVisible()
  await expect(page.getByRole('main')).toBeFocused()
  await page.keyboard.press(tab)
  await expect(page.getByRole('link', { name: 'Back to library' })).toBeFocused()
  await page.keyboard.press(tab)
  await expect(page.getByRole('button', { name: 'Read', exact: true })).toBeFocused()
  await page.keyboard.press('Enter')
  const frame = page.frameLocator('.qr-frame')
  const paragraph = frame.locator('#selectable')
  await expect(paragraph).toBeVisible()
  await expect(page.getByRole('region', { name: 'Reader' })).toBeFocused()
  await page.keyboard.press(tab)
  await expect(page.getByRole('button', { name: 'Close book', exact: true })).toBeFocused()
  await page.keyboard.press(tab)
  const contents = page.getByRole('button', { name: 'Contents', exact: true })
  await expect(contents).toBeFocused()
  await page.keyboard.press('Enter')
  await expect(page.getByRole('heading', { name: 'Contents', exact: true })).toBeFocused()
  await page.keyboard.press(tab)
  await expect(page.getByRole('button', { name: 'Close panel' })).toBeFocused()
  await page.keyboard.press('Escape')
  await expect(contents).toBeFocused()
  await page.keyboard.press(tab)
  await page.keyboard.press('Enter')
  await expect(page.getByRole('searchbox', { name: 'Find in book' })).toBeFocused()
  await page.keyboard.press('Escape')
  await expect(page.getByRole('button', { name: 'Find', exact: true })).toBeFocused()

  // Establish a chapter selection independently of platform-specific caret browsing.
  await paragraph.evaluate(element => {
    const doc = element.ownerDocument
    const range = doc.createRange()
    range.selectNodeContents(element)
    doc.getSelection()!.addRange(range)
    doc.dispatchEvent(new Event('selectionchange'))
  })
  await expect(page.getByRole('button', { name: 'Save highlight' })).toBeVisible()
  await page.getByRole('textbox', { name: 'Comment' }).focus()
  await page.keyboard.type('Keyboard note')
  await page.keyboard.press(`Shift+${tab}`)
  await expect(page.getByRole('button', { name: 'Dismiss', exact: true })).toBeFocused()
  await page.keyboard.press(`Shift+${tab}`)
  await page.keyboard.press(`Shift+${tab}`)
  await expect(page.getByRole('button', { name: 'Save highlight' })).toBeFocused()
  await page.keyboard.press('Enter')
  await expect(page.getByRole('button', { name: 'Save highlight' })).toHaveCount(0)
  await expect(page.locator('.qr-frame')).toBeFocused()

  // The frame bridge must leave a modified arrow available to the browser's caret.
  const accepted = await paragraph.evaluate(element => element.dispatchEvent(new KeyboardEvent('keydown', {
    key: 'ArrowLeft', shiftKey: true, bubbles: true, cancelable: true,
  })))
  expect(accepted).toBe(true)
  await paragraph.evaluate(element => element.dispatchEvent(new KeyboardEvent('keydown', {
    key: 'ArrowLeft', bubbles: true, cancelable: true,
  })))
  await expect(frame.getByRole('heading', { name: 'الفصل التالي' })).toBeVisible()

  await expect.poll(() => page.evaluate(() => Boolean(navigator.serviceWorker.controller))).toBe(true)
  await waitForSavedChapter(page, 'two.xhtml')
  await context.addCookies([{ name: 'quire-test-offline', value: '1', url: page.url() }])
  expect(await page.evaluate(async () => {
    try {
      await fetch('/quire/v1/network-probe')
      return true
    } catch {
      return false
    }
  })).toBe(false)
  await page.reload()
  await expect(frame.getByRole('heading', { name: 'الفصل التالي' })).toBeVisible()
  await page.getByRole('button', { name: 'Close book', exact: true }).focus()
  await page.keyboard.press('Enter')
  await expect(page.getByRole('heading', { name: 'Keyboard Reader' })).toBeVisible()
  await expect(page.getByRole('main')).toBeFocused()
  await page.goto('/quire/#/highlights')
  await expect(page.getByText('Keyboard note')).toBeVisible()
  await context.clearCookies({ name: 'quire-test-offline' })
})

test('shell and reader stay reachable at 320 CSS px and 200% layout zoom', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 740 })
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await page.goto('/quire/')
  const fits = () => page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1)
  expect(await fits()).toBe(true)
  await page.getByRole('link', { name: 'Settings', exact: true }).click()
  await expect(page.getByRole('combobox', { name: 'Language', exact: true })).toBeVisible()
  expect(await fits()).toBe(true)
  await page.getByRole('link', { name: 'Discover', exact: true }).click()
  await expect(page.getByRole('textbox', { name: 'ISBN' })).toBeVisible()
  expect(await fits()).toBe(true)
  await page.locator('input[type=file]').setInputFiles(fixture())
  await page.getByRole('button', { name: 'Read', exact: true }).click()
  await expect(page.frameLocator('.qr-frame').locator('#selectable')).toBeVisible()
  expect(await fits()).toBe(true)
  await page.getByRole('button', { name: 'Appearance', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Close panel' })).toBeInViewport()
  await page.getByRole('button', { name: 'Close panel' }).click()

  await page.setViewportSize({ width: 1280, height: 1000 })
  await page.evaluate(() => { document.documentElement.style.zoom = '2' })
  await expect(page.getByRole('button', { name: 'Next', exact: true })).toBeVisible()
  expect(await fits()).toBe(true)
  const frameWidth = await page.locator('.qr-frame').evaluate(frame => frame.getBoundingClientRect().width)
  expect(frameWidth).toBeGreaterThan(300)
  const reducedMotion = await page.evaluate(() => ({
    preference: matchMedia('(prefers-reduced-motion: reduce)').matches,
    scroll: getComputedStyle(document.documentElement).scrollBehavior,
  }))
  expect(reducedMotion).toEqual({ preference: true, scroll: 'auto' })
})

test('interactive outlines, shell text and reader palettes meet contrast thresholds', async ({ page }) => {
  await page.goto('/quire/#/settings')
  const contrast = ({ fg, bg }: {fg: string; bg: string}) => {
    const linear = (value: number) => {
      const channel = value / 255
      return channel <= .04045 ? channel / 12.92 : ((channel + .055) / 1.055) ** 2.4
    }
    function luminance(value: string) {
      const channels = value.startsWith('#') ? value.slice(1).match(/../g)!.map(part => parseInt(part, 16))
        : value.match(/[0-9.]+/g)!.slice(0, 3).map(Number)
      return .2126 * linear(channels[0]) + .7152 * linear(channels[1]) + .0722 * linear(channels[2])
    }
    const levels = [luminance(fg), luminance(bg)].sort((a, b) => b - a)
    return (levels[0] + .05) / (levels[1] + .05)
  }

  const shellColors = await page.evaluate(() => ['paper', 'sage', 'ink', 'midnight'].map(theme => {
    document.documentElement.dataset.theme = theme
    const styles = getComputedStyle(document.documentElement)
    const token = (name: string) => styles.getPropertyValue(`--${name}`).trim()
    return { theme, paper: token('paper'), card: token('card'), ink: token('ink'),
      accent: token('accent'), muted: token('muted'), line: token('control-line') }
  }))
  for (const { theme, paper, card, ink, muted, accent, line } of shellColors) {
    for (const bg of [paper, card]) {
      for (const fg of [ink, muted, accent]) expect(contrast({ fg, bg }), theme).toBeGreaterThanOrEqual(4.5)
      expect(contrast({ fg: line, bg }), theme).toBeGreaterThanOrEqual(3)
    }
  }

  await page.locator('input[type=file]').setInputFiles(fixture())
  await page.getByRole('button', { name: 'Read', exact: true }).click()
  const chapter = page.frameLocator('.qr-frame')
  const link = chapter.getByRole('link', { name: 'الفصل الثاني' })
  await expect(link).toBeVisible()
  await page.getByRole('button', { name: 'Appearance', exact: true }).click()
  for (const palette of ['paper', 'sage', 'ink', 'midnight']) {
    await page.getByLabel('Palette').selectOption(palette)
    const colors = await chapter.locator('body').evaluate(body => {
      const win = body.ownerDocument.defaultView!
      const bodyStyle = win.getComputedStyle(body)
      return {
        bg: win.getComputedStyle(body.ownerDocument.documentElement).backgroundColor,
        ink: bodyStyle.color,
        link: win.getComputedStyle(body.querySelector('a')!).color,
      }
    })
    expect(contrast({ fg: colors.ink, bg: colors.bg }), palette).toBeGreaterThanOrEqual(4.5)
    expect(contrast({ fg: colors.link, bg: colors.bg }), palette).toBeGreaterThanOrEqual(4.5)
  }
})

test('submits the connected account form using only keyboard controls after focusing the email field', async ({ page, browserName }) => {
  await page.addInitScript(() => { Reflect.deleteProperty(Navigator.prototype, 'serviceWorker') })
  let signedIn = false
  await page.route('**/quire/v1/bootstrap', route => route.fulfill({ json: {
    version: 1, sync: false, ai: false, aiAvailable: false, login: 'native',
    password: true, oidc: false, catalogUrl: null, accountManagement: null,
  } }))
  await page.route('**/auth/v1/me', route => signedIn
    ? route.fulfill({ json: { version: 1, user: { id: 'keyboard@example.test', backend: 'native' }, csrf_token: 'fixture' } })
    : route.fulfill({ status: 401 }))
  await page.route('**/auth/v1/browser/login', route => {
    expect(route.request().postDataJSON()).toEqual({
      email: 'keyboard@example.test', password: 'test-password', destination: 'quire', remember_browser: false,
    })
    signedIn = true
    return route.fulfill({ json: { account_id: 'keyboard@example.test' } })
  })

  await page.goto('/quire/#/settings')
  const email = page.getByRole('textbox', { name: 'Email' })
  await expect(email).toBeEnabled()
  await email.focus()
  await page.keyboard.type('keyboard@example.test')
  await page.keyboard.press(browserName === 'webkit' && process.platform === 'darwin' ? 'Alt+Tab' : 'Tab')
  await expect(page.getByLabel('Password', { exact: true })).toBeFocused()
  await page.keyboard.type('test-password')
  await page.keyboard.press('Enter')
  await expect(page.getByText('keyboard@example.test · Quire')).toBeVisible()
})
