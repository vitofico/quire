// @vitest-environment node
import { describe, expect, it } from 'vitest'
import type { Highlight, ShelfBook } from './data'
import { DiscoveryHttpError, requestMessage } from './discovery/api'
import { type Passage, defaultLimits, discussBody, passagesOf, shareNote } from './discuss'

const highlight = (id: string, hash: string, text: string, extra: Partial<Highlight> = {}): Highlight => ({
  id, document: { content_hash: hash }, locator: '{}', text, colour: 'YELLOW', book_title: '',
  created_at: 0, updated_at: 0, ...extra,
})
const dune = { key: 'd', title: 'Dune', authors: ['Frank Herbert'] } as ShelfBook
const passage = (n: number, text: string, note: string | null = null): Passage =>
  ({ n, text, note, title: 'Dune', authors: ['Frank Herbert'] })

describe('passagesOf', () => {
  it('numbers the picked highlights in order and fills title and authors from the shelf', () => {
    const list = passagesOf([
      highlight('1', 'd', 'Fear is the mind-killer.', { comment: '  Stoics  ' }),
      highlight('2', 'x', 'All happy families.', { book_title: 'Anna Karenina', book_author: 'Leo Tolstoy', comment: ' ' }),
    ], [dune])
    expect(list).toEqual([
      { n: 1, text: 'Fear is the mind-killer.', note: 'Stoics', title: 'Dune', authors: ['Frank Herbert'] },
      { n: 2, text: 'All happy families.', note: null, title: 'Anna Karenina', authors: ['Leo Tolstoy'] },
    ])
  })
})

describe('discussBody', () => {
  it('sends the passages, every turn so far and the new question', () => {
    const { body, clipped, tooLarge } = discussBody(
      [passage(1, 'Fear.', 'Stoics'), passage(2, 'Spice.')],
      [{ question: 'What connects these?', answer: 'Control.', cited: [1] }],
      '  And the spice? ', true, defaultLimits,
    )
    expect(body).toEqual({
      highlights: [
        { n: 1, text: 'Fear.', note: 'Stoics', title: 'Dune', authors: ['Frank Herbert'] },
        { n: 2, text: 'Spice.', title: 'Dune', authors: ['Frank Herbert'] },
      ],
      messages: [
        { role: 'reader', content: 'What connects these?' },
        { role: 'assistant', content: 'Control.' },
        { role: 'reader', content: 'And the spice?' },
      ],
    })
    expect(clipped).toBe(false)
    expect(tooLarge).toBe(false)
  })

  it('leaves the notes out when the reader says so', () => {
    const { body } = discussBody([passage(1, 'Fear.', 'Stoics')], [], 'Why?', false, defaultLimits)
    expect(body.highlights[0]).not.toHaveProperty('note')
  })

  it('clips long text by code point, never splitting a character, and says so', () => {
    const limits = { ...defaultLimits, max_text_chars: 3, max_note_chars: 2, max_authors: 1, max_author_chars: 4 }
    const { body, clipped } = discussBody(
      [{ n: 1, text: '😀😀😀😀', note: 'abc', title: 'Dune', authors: ['Frank Herbert', 'Brian'] }], [], 'Q', true, limits,
    )
    expect(body.highlights[0]).toMatchObject({ text: '😀😀😀', note: 'ab', authors: ['Fran'] })
    expect(clipped).toBe(true)
  })

  it('flags a conversation over the total the server allows', () => {
    const limits = { ...defaultLimits, max_total_chars: 40 }
    expect(discussBody([passage(1, 'x'.repeat(10))], [], 'Why?', true, limits).tooLarge).toBe(false)
    expect(discussBody([passage(1, 'x'.repeat(20))], [], 'Why?', true, limits).tooLarge).toBe(true)
  })
})

describe('shareNote', () => {
  it('puts the question, the answer and the cited passages with their books in plain text', () => {
    const long = 'a'.repeat(320)
    const note = shareNote(
      { question: 'What connects these?', answer: 'Both are about control.\n\nOne more line.', cited: [2] },
      [passage(1, 'Fear.'), { ...passage(2, long), title: 'The Black Swan', authors: ['Nassim Nicholas Taleb'] }],
      'Passages',
    )
    expect(note).toBe(
      'What connects these?\n\nBoth are about control.\n\nOne more line.\n\nPassages\n' +
      `1. "${'a'.repeat(300)}…" (The Black Swan, Nassim Nicholas Taleb)`,
    )
  })

  it('lists every passage when the answer cites none', () => {
    const note = shareNote({ question: 'Q', answer: 'A', cited: [] }, [passage(1, 'One'), passage(2, 'Two')], 'Passages')
    expect(note.split('\n').slice(-2)).toEqual(['1. "One" (Dune, Frank Herbert)', '2. "Two" (Dune, Frank Herbert)'])
  })
})

describe('discuss errors', () => {
  const message = (status: number, detail: unknown) => requestMessage(new DiscoveryHttpError(status, detail)).message
  it('names each refusal in plain words', () => {
    expect(message(503, 'ai_disabled')).toBe('AI is unavailable on this server.')
    expect(message(409, 'ai_not_opted_in')).toBe('Turn on AI in Settings to use this.')
    expect(message(409, 'ai_provider_unavailable')).toMatch(/provider was removed/)
    expect(message(422, 'ai_provider_unknown')).toMatch(/provider was removed/)
    expect(message(429, 'discuss_rate_limited')).toBe('Busy right now. Try again in a minute.')
    expect(message(429, 'monthly_ai_quota_exhausted')).toBe('AI limit reached. Try again after the quota resets.')
    expect(message(429, { used: 200, limit: 200, resets_at: '2026-11-27T00:00:00Z' })).toBe('AI limit reached. Resets {date}.')
    expect(message(422, { code: 'too_large', field: 'request', limit: 60000 })).toMatch(/too long together/)
    expect(message(422, { code: 'too_long', field: 'highlights[0].text', limit: 2000 })).toBe('The server could not use these passages or this question.')
  })
})
