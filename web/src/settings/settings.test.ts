import { describe, expect, it } from 'vitest'
import { readThemeChoice, resolveTheme } from './theme'
import { settingsLabels } from './labels'
import { removableDownloads } from './Storage'

const from = (values: Record<string, string>) => (key: string) => values[key] ?? null

describe('app theme', () => {
  it('starts on System, with Paper for light and Ink for dark', () => {
    expect(readThemeChoice(from({}))).toEqual({ mode: 'system', light: 'paper', dark: 'ink' })
  })

  it('keeps the one theme the earlier picker stored', () => {
    expect(readThemeChoice(from({ theme: 'Sage' }))).toEqual({ mode: 'light', light: 'sage', dark: 'ink' })
    expect(readThemeChoice(from({ theme: 'Midnight' }))).toEqual({ mode: 'dark', light: 'paper', dark: 'midnight' })
    expect(readThemeChoice(from({ theme: 'System' }))).toEqual({ mode: 'system', light: 'paper', dark: 'ink' })
  })

  it('prefers the stored choice, and ignores values it does not know', () => {
    expect(readThemeChoice(from({ theme: 'Ink', 'theme-mode': 'system', 'theme-light': 'sage', 'theme-dark': 'midnight' })))
      .toEqual({ mode: 'system', light: 'sage', dark: 'midnight' })
    expect(readThemeChoice(from({ 'theme-mode': 'sepia', 'theme-light': 'ink', 'theme-dark': 'paper' })))
      .toEqual({ mode: 'system', light: 'paper', dark: 'ink' })
  })

  it('in System follows the device with the chosen light and dark theme', () => {
    const choice = { mode: 'system', light: 'sage', dark: 'midnight' } as const
    expect(resolveTheme(choice, false)).toBe('sage')
    expect(resolveTheme(choice, true)).toBe('midnight')
    expect(resolveTheme({ ...choice, mode: 'light' }, true)).toBe('sage')
    expect(resolveTheme({ ...choice, mode: 'dark' }, false)).toBe('midnight')
  })
})

describe('settings text', () => {
  it('has every string in Spanish, Italian, French and German, without long dashes', () => {
    for (const [english, translations] of Object.entries(settingsLabels)) {
      expect(translations, english).toHaveLength(4)
      for (const text of [english, ...translations]) {
        expect(text.trim(), english).not.toBe('')
        expect(text, english).not.toContain('—')
      }
      for (const placeholder of english.match(/\{\w+\}/g) ?? []) {
        for (const text of translations) expect(text, english).toContain(placeholder)
      }
    }
  })
})

describe('removing all downloads', () => {
  it('clears only files Quire can fetch again from a catalog', () => {
    const book = (key: string, synced: boolean, localOnly: boolean, opds_href?: string, hasDownload = true) =>
      ({ key, synced, localOnly, opds_href, hasDownload })
    const catalog = 'https://books.example.org/opds/download/7/epub/'
    const books = [
      book('from a catalog', true, false, catalog),
      book('added from a file, details synced', true, false),
      book('imported here, never uploaded', false, true),
      book('waiting to upload', false, false, catalog),
      book('kept in this browser beside the account edition', true, true, catalog),
      book('not downloaded', true, false, catalog, false),
    ]
    expect(removableDownloads(books).map((b) => b.key)).toEqual(['from a catalog'])
  })
})
