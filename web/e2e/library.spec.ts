import { test, expect } from '@playwright/test'
import { execFileSync } from 'node:child_process'
import { tmpdir } from 'node:os'
import { mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const readerFixture = fileURLToPath(new URL('../../app/src/test/resources/reader-pause.epub', import.meta.url))

test('the library keeps its status filter, sort order and view after navigation and reload', async ({ page }) => {
  await page.goto('/quire/')
  await expect(page.getByRole('button', { name: 'Import EPUB', exact: true })).toBeEnabled()
  await page.locator('input[type=file]').setInputFiles(readerFixture)
  await page.getByRole('button', { name: 'Mark finished', exact: true }).click()
  await page.getByRole('link', { name: 'Back to library' }).click()

  const filters = page.getByRole('group', { name: 'Show', exact: true })
  const finished = filters.getByRole('button', { name: 'Finished', exact: true })
  const inProgress = filters.getByRole('button', { name: 'In progress', exact: true })
  const grid = page.locator('.library-grid')
  const entry = grid.getByRole('button', { name: /Reader Pause Fixture/ })
  const sort = page.getByRole('combobox', { name: 'Sort by' })
  const compact = page.getByRole('button', { name: 'Compact list', exact: true })
  const covers = page.getByRole('button', { name: 'Cover view', exact: true })
  await finished.click()
  await sort.selectOption('Author')
  await compact.click()
  await entry.click()
  await page.getByRole('link', { name: 'Back to library' }).click()
  await expect(finished).toHaveAttribute('aria-pressed', 'true')
  await expect(sort).toHaveValue('Author')
  await expect(compact).toHaveAttribute('aria-pressed', 'true')
  await expect(entry).toBeVisible()

  await inProgress.click()
  await expect(entry).toHaveCount(0)
  await page.getByRole('link', { name: 'Settings', exact: true }).click()
  await page.getByRole('link', { name: 'Library', exact: true }).click()
  await expect(inProgress).toHaveAttribute('aria-pressed', 'true')
  await page.reload()
  await expect(inProgress).toHaveAttribute('aria-pressed', 'true')
  await expect(sort).toHaveValue('Author')
  await expect(compact).toHaveAttribute('aria-pressed', 'true')
  await expect(grid).toHaveClass(/\bcompact\b/)
  await expect(entry).toHaveCount(0)

  await filters.getByRole('button', { name: 'All books', exact: true }).click()
  await sort.selectOption('Title')
  await covers.click()
  await page.reload()
  await expect(entry).toBeVisible()
  await expect(sort).toHaveValue('Title')
  await expect(covers).toHaveAttribute('aria-pressed', 'true')
  await expect(grid).toHaveClass('library-grid')
})

test('wide libraries gain cover columns and other pages center beside the rail', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('/quire/')
  await expect(page.getByRole('button', { name: 'Import EPUB', exact: true })).toBeEnabled()
  await page.locator('input[type=file]').setInputFiles(readerFixture)
  await page.getByRole('link', { name: 'Back to library' }).click()

  const grid = page.locator('.library-grid')
  const columns = () => grid.evaluate(element => getComputedStyle(element).gridTemplateColumns.split(' ').length)
  const laptopColumns = await columns()
  await page.setViewportSize({ width: 2520, height: 1000 })
  expect(await columns()).toBeGreaterThan(laptopColumns)

  const main = page.getByRole('main')
  const geometry = () => main.evaluate(element => {
    const pageBox = element.getBoundingClientRect()
    const railRight = document.querySelector('.rail')!.getBoundingClientRect().right
    return {
      width: pageBox.width,
      gutterDifference: Math.abs(pageBox.left - railRight - (document.documentElement.clientWidth - pageBox.right)),
    }
  })
  const wide = await geometry()
  expect(wide.width).toBeGreaterThan(1600)
  expect(wide.gutterDifference).toBeLessThan(2)

  for (const name of ['Catalog', 'Highlights', 'Discover', 'Settings']) {
    await page.getByRole('link', { name, exact: true }).click()
    await expect(page.getByRole('heading', { name, exact: true })).toBeVisible()
    const destination = await geometry()
    expect(destination.gutterDifference).toBeLessThan(2)
    if (name === 'Settings') expect(destination.width).toBeLessThan(1000)
  }
})

test('import from disk, read, change language, remove the download, and reopen a matching EPUB', async ({
  page,
}) => {
  const dir = mkdtempSync(join(tmpdir(), 'quire-test-'))
  const filename = join(dir, 'fixture.epub')
  execFileSync('python3', ['../scripts/make-reader-pause-fixture.py', filename])
  const buffer = readFileSync(filename)
  rmSync(dir, { recursive: true })
  const attached = {
    name: 'fixture.epub',
    mimeType: 'application/epub+zip',
    buffer,
  }
  await page.goto('/quire/')
  await expect(page.getByRole('button', { name: 'Import EPUB' })).toBeEnabled()
  await expect(
    page.getByRole('button', { name: 'Import EPUB', exact: true }).first(),
  ).toBeEnabled()
  await page.locator('input[type=file]').setInputFiles(attached)
  await expect(
    page.getByRole('heading', { name: 'Reader Pause Fixture' }),
  ).toBeVisible()
  await page.getByRole('button', { name: 'Read', exact: true }).click()
  await expect(
    page.frameLocator('iframe').getByText('Marker 1-01.', { exact: false }),
  ).toBeVisible()
  await page.reload()
  await expect(
    page.frameLocator('iframe').getByText('Marker 1-01.', { exact: false }),
  ).toBeVisible()
  await page.goto('/quire/#/library')
  await page
    .getByRole('button', { name: /Reader Pause Fixture/ })
    .first()
    .click()
  await page.getByRole('button', { name: 'More actions' }).click()
  await page.getByRole('button', { name: 'Remove download' }).click()
  await expect(
    page.getByRole('button', { name: 'Open your copy' }),
  ).toBeVisible()
  const chooser = page.waitForEvent('filechooser')
  await page.getByRole('button', { name: 'Open your copy' }).click()
  await (await chooser).setFiles(attached)
  await expect(
    page.getByRole('button', { name: 'Read', exact: true }),
  ).toBeVisible()
  await page.getByRole('link', { name: 'Settings', exact: true }).click()
  await page
    .getByRole('combobox', { name: 'Language', exact: true })
    .selectOption('it')
  await page.getByRole('link', { name: 'Biblioteca' }).click()
  await expect(
    page.getByRole('heading', { name: 'La tua biblioteca' }),
  ).toBeVisible()
})
