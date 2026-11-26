import { describe, expect, it } from 'vitest'
import type { BookDetails, DetailsAnswer, ShelfBook } from './data'
import { acceptedOf, changesOf, differsFromOriginal, initialChoice, worthPreview } from './TidyDetails'

const own = new Blob(['jpg'], { type: 'image/jpeg' })
const book: ShelfBook = {
  key: 'b', content_hash: 'b', title: 'dune_(retail)', authors: ['Herbert, Frank'], description: 'Spice.',
  hasDownload: true, synced: true, localOnly: false, cover: own,
}
function answer(overrides: Partial<DetailsAnswer['book']> = {}, cover: DetailsAnswer['cover'] = { source: 'file', generated: false }): DetailsAnswer {
  return {
    key: 'k'.repeat(64),
    book: {
      title: book.title, authors: book.authors!, series_name: null, series_index: null, description: 'Spice.',
      subjects: [], language: null, isbn: null, year: null, publisher: null, ...overrides,
    },
    origins: { title: 'ai' }, cover, tidy: 'done', cached: false,
  }
}
const withRecord = (details: Partial<BookDetails>): ShelfBook => ({ ...book, details: { attempted_at: 0, ...details } })

describe('the improve details preview', () => {
  it('lists only the fields whose proposed value differs from what is shown', () => {
    expect(changesOf(book, answer({ title: 'Dune', series_name: 'Dune', series_index: 1 }))).toEqual(['title', 'series'])
    // Already shown from an earlier answer: not a change.
    const shown = withRecord({ answer: answer({ title: 'Dune' }) })
    expect(changesOf(shown, answer({ title: 'Dune' }))).toEqual([])
  })

  it('keeps the unchanged fields and the ticked changes', () => {
    expect(acceptedOf(['title', 'description'], new Set(['description']))).toEqual(['authors', 'series', 'description'])
    expect(acceptedOf([], new Set())).toEqual(['title', 'authors', 'series', 'description'])
  })

  it('starts on the reader\'s last cover choice when the server still offers it', () => {
    expect(initialChoice(book)).toBe('keep')
    expect(initialChoice(withRecord({ cover_choice: 'draw' }), false)).toBe('keep')
    expect(initialChoice(withRecord({ cover_choice: 'draw' }), true)).toBe('draw')
    expect(initialChoice(withRecord({ cover_choice: 'paint' }), true, false)).toBe('draw')
    expect(initialChoice(withRecord({ cover_choice: 'paint' }), true, true)).toBe('paint')
    expect(initialChoice({ ...withRecord({ cover_choice: 'find' }), cover: undefined }, true)).toBe('keep')
    expect(initialChoice(withRecord({ cover_choice: 'find' }), true)).toBe('find')
  })

  it('opens no preview when nothing would change and no cover can be chosen', () => {
    expect(worthPreview(book, answer())).toBe(false)
    expect(worthPreview(book, answer(), true)).toBe(true)
    expect(worthPreview(book, answer({ title: 'Dune' }))).toBe(true)
    const noOwn = { ...book, cover: undefined }
    expect(worthPreview(noOwn, answer({}, { source: 'openlibrary', url: 'https://covers.openlibrary.org/b/id/1-L.jpg', generated: false }))).toBe(true)
  })

  it('offers the original only when it would change what is shown', () => {
    expect(differsFromOriginal(book)).toBe(false)
    // Every improvement rejected and the own cover kept: nothing to switch back from.
    expect(differsFromOriginal(withRecord({ answer: answer({ title: 'Dune' }), accepted: [] }))).toBe(false)
    expect(differsFromOriginal(withRecord({ answer: answer({ title: 'Dune' }) }))).toBe(true)
    expect(differsFromOriginal(withRecord({ answer: answer({ title: 'Dune' }), original: true }))).toBe(true)
    expect(differsFromOriginal(withRecord({ answer: answer(), cover_choice: 'draw', cover_url: '/details/v1/covers/x?v=1' }))).toBe(true)
  })
})
