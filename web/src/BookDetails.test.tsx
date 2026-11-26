import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { BookDetails } from './BookDetails'
import type { DetailsAnswer, ShelfBook } from './data'

afterEach(cleanup)

function show(description: string) {
  const answer = {
    key: 'k', cached: false, tidy: 'done', cover: { source: null, generated: false },
    book: {
      title: 'Dune', authors: [], series_name: null, series_index: null, description: 'A desert planet.',
      subjects: [], language: null, isbn: null, year: null, publisher: null,
    },
    origins: { title: 'file', description },
  } as unknown as DetailsAnswer
  const book: ShelfBook = {
    key: 'b', title: 'Dune', hasDownload: true, synced: false, localOnly: true, details: { attempted_at: 0, answer },
  }
  render(<BookDetails book={book} t={(key) => key} actions={null} back={null} />)
  return screen.getByText('A desert planet.', { exact: false }).querySelector('.origin')?.textContent
}

describe('where a description came from', () => {
  it('follows the server: cleaned, written, or an origin this client does not know', () => {
    expect(show('ai')).toBe('Tidied by AI')
    cleanup()
    expect(show('ai_written')).toBe('Written by AI')
    cleanup()
    expect(show('a_future_origin')).toBe('Tidied by AI')
    cleanup()
    expect(show('openlibrary')).toBe('From Open Library')
    cleanup()
    expect(show('file')).toBeUndefined()
  })
})
