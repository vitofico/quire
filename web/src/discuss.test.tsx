import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Highlight, ShelfBook } from './data'
import { type DiscoveryRequest, DiscoveryHttpError, requestMessage } from './discovery/api'
import { Passages } from './Passages'
import type { DiscussLimits } from './discovery/api'
import { type Passage, discussBody, passagesOf, shareNote } from './discuss'

const defaultLimits: DiscussLimits = {
  max_highlights: 20, max_text_chars: 2000, max_note_chars: 1000, max_title_chars: 500, max_authors: 10,
  max_author_chars: 200, max_question_chars: 1000, max_answer_chars: 4000, max_questions: 6,
  max_total_chars: 60000, questions_per_minute: 10,
}

const highlight = (id: string, hash: string, text: string, extra: Partial<Highlight> = {}): Highlight => ({
  id, document: { content_hash: hash }, locator: '{}', text, colour: 'YELLOW', book_title: '',
  created_at: 0, updated_at: 0, ...extra,
})
const dune = { key: 'd', title: 'Dune', authors: ['Frank Herbert'] } as ShelfBook
const passage = (n: number, text: string, note: string | null = null): Passage =>
  ({ n, text, note, title: 'Dune', authors: ['Frank Herbert'] })

describe('passagesOf', () => {
  it('numbers the picked highlights in order, preferring their own title and author to the shelf', () => {
    const list = passagesOf([
      highlight('1', 'd', 'Fear is the mind-killer.', { comment: '  Stoics  ', book_title: ' ', book_author: '' }),
      highlight('3', 'd', 'The spice must flow.', { book_title: 'Dune Messiah', book_author: 'F. Herbert' }),
      highlight('2', 'x', 'All happy families.', { book_title: 'Anna Karenina', book_author: 'Leo Tolstoy', comment: ' ' }),
    ], [dune])
    expect(list).toEqual([
      { n: 1, text: 'Fear is the mind-killer.', note: 'Stoics', title: 'Dune', authors: ['Frank Herbert'] },
      { n: 2, text: 'The spice must flow.', note: null, title: 'Dune Messiah', authors: ['F. Herbert'] },
      { n: 3, text: 'All happy families.', note: null, title: 'Anna Karenina', authors: ['Leo Tolstoy'] },
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
  })
})

describe('the Highlights selection and the conversation panel', () => {
  beforeEach(() => {
    // jsdom has no dialog behaviour.
    HTMLDialogElement.prototype.show = HTMLDialogElement.prototype.showModal = function () { this.open = true }
    HTMLDialogElement.prototype.close = function () { this.open = false }
    window.matchMedia = ((query: string) => ({ matches: false, media: query, addEventListener() {}, removeEventListener() {} })) as unknown as typeof window.matchMedia
  })
  afterEach(cleanup)

  const json = (body: unknown, status = 200) =>
    new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
  const three = [
    highlight('h1', 'd', 'Fear is the mind-killer.', { book_title: 'Dune', comment: 'Stoics' }),
    highlight('h2', 'd', '   ', { book_title: 'Dune' }),
    highlight('h3', 'x', 'All happy families are alike.', { book_title: 'Anna Karenina' }),
  ]
  const t = (key: string) => key

  function page(request: DiscoveryRequest, passages = three, limits = defaultLimits) {
    const props = { books: [dune], t, open: () => {}, report: () => {} }
    const view = render(<Passages {...props} passages={passages} discuss={{ request, limits }} />)
    return {
      rerender: (next: Highlight[], on = true) =>
        view.rerender(<Passages {...props} passages={next} discuss={on ? { request, limits } : undefined} />),
    }
  }

  it('counts only highlights that still exist, names each box by its passage and skips blank ones', () => {
    const { rerender } = page(vi.fn())
    fireEvent.click(screen.getByRole('button', { name: 'Select' }))
    expect(screen.getAllByRole('checkbox')).toHaveLength(2)
    fireEvent.click(screen.getByRole('checkbox', { name: 'Select: Fear is the mind-killer.' }))
    fireEvent.click(screen.getByRole('checkbox', { name: 'Select: All happy families are alike.' }))
    expect(screen.getByRole('status').textContent).toContain('Selected: 2')
    rerender([three[1], three[2]])
    expect(screen.getByRole('status').textContent).toContain('Selected: 1')
    rerender([three[1]])
    expect(screen.getByRole('status').textContent).toContain('Selected: 0')
    expect((screen.getByRole('button', { name: 'Think it through' }) as HTMLButtonElement).disabled).toBe(true)
  })

  function open(request: DiscoveryRequest, limits = defaultLimits) {
    const view = page(request, three, limits)
    fireEvent.click(screen.getByRole('button', { name: 'Select' }))
    fireEvent.click(screen.getByRole('checkbox', { name: 'Select: Fear is the mind-killer.' }))
    fireEvent.click(screen.getByRole('button', { name: 'Think it through' }))
    return view
  }

  it('closes the panel cleanly when AI settings go away', () => {
    const { rerender } = open(vi.fn())
    expect(document.querySelector('.discussing .discuss')).not.toBeNull()
    rerender(three, false)
    expect(document.querySelector('.discuss')).toBeNull()
    expect(document.querySelector('.discussing')).toBeNull()
  })

  it('says when passages and questions are too long together, before or after asking', async () => {
    const request = vi.fn<DiscoveryRequest>(async () => json({ detail: { code: 'too_large', field: 'request', limit: 60000 } }, 422))
    open(request, { ...defaultLimits, max_total_chars: 20 })
    fireEvent.click(screen.getByRole('button', { name: 'What connects these?' }))
    expect(screen.getByRole('alert').textContent).toMatch(/too long together/)
    expect(request).not.toHaveBeenCalled()
    cleanup()
    open(request)
    fireEvent.change(screen.getByRole('textbox', { name: 'Your question' }), { target: { value: 'Why?' } })
    fireEvent.click(screen.getByRole('button', { name: 'Ask' }))
    expect((screen.getByRole('textbox', { name: 'Your question' }) as HTMLTextAreaElement).value).toBe('')
    await waitFor(() => expect(screen.getByRole('alert').textContent).toMatch(/too long together/))
    expect((screen.getByRole('textbox', { name: 'Your question' }) as HTMLTextAreaElement).value).toBe('Why?')
  })

  it('gives a body the server could not read a plain message', async () => {
    open(vi.fn<DiscoveryRequest>(async () => json({ detail: [{ loc: ['body'], msg: 'bad' }] }, 422)))
    fireEvent.click(screen.getByRole('button', { name: 'What connects these?' }))
    await waitFor(() => expect(screen.getByRole('alert').textContent).toContain('The server could not use these passages or this question.'))
  })

  it('stops at the question limit and starts again with the box focused', async () => {
    open(vi.fn<DiscoveryRequest>(async () => json({ answer: 'Fear.', cited: [1] })), { ...defaultLimits, max_questions: 1 })
    fireEvent.click(screen.getByRole('button', { name: 'What connects these?' }))
    await screen.findByText('Fear.')
    expect(screen.queryByRole('textbox')).toBeNull()
    // The notes choice holds for the whole conversation.
    expect(screen.getByRole('checkbox', { name: 'Include my notes' }).hasAttribute('disabled')).toBe(true)
    fireEvent.click(screen.getByRole('button', { name: 'Start again' }))
    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('textbox', { name: 'Your question' })))
    expect(screen.queryByText('Fear.')).toBeNull()
    expect(screen.getByRole('checkbox', { name: 'Include my notes' }).hasAttribute('disabled')).toBe(false)
  })

  it('shows no late answer after Cancel or closing while it was asked', async () => {
    let answer!: (r: Response) => void
    // Like fetch: a cancelled request rejects, even if its answer arrives later.
    const request = vi.fn<DiscoveryRequest>((_path, init) => new Promise((resolve, reject) => {
      answer = resolve
      init?.signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')))
    }))
    open(request)
    fireEvent.click(screen.getByRole('button', { name: 'What connects these?' }))
    expect(screen.getByText('Thinking…')).toBeDefined()
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }))
    answer(json({ answer: 'Too late.', cited: [] }))
    await waitFor(() => expect(screen.queryByText('Thinking…')).toBeNull())
    await new Promise((r) => setTimeout(r, 20))
    expect(screen.queryByText('Too late.')).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: 'Where do they disagree?' }))
    fireEvent.click(screen.getByRole('button', { name: 'Close' }))
    answer(json({ answer: 'Also too late.', cited: [] }))
    fireEvent.click(screen.getByRole('button', { name: 'Think it through' }))
    await new Promise((r) => setTimeout(r, 20))
    expect(screen.queryByText('Also too late.')).toBeNull()
    expect(screen.getByRole('button', { name: 'What connects these?' })).toBeDefined()
  })
})
