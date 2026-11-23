import { test, expect } from '@playwright/test'
import { execFileSync } from 'node:child_process'
import { tmpdir } from 'node:os'
import { mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { join } from 'node:path'
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
  await page.getByText('Storage', { exact: true }).click()
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
