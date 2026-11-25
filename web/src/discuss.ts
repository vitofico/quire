import type { Highlight, ShelfBook } from './data'
import type { DiscussLimits } from './discovery/api'

/** The server's limits, for a server that says the feature is on but sends none. */
export const defaultLimits: DiscussLimits = {
  max_highlights: 20,
  max_text_chars: 2000,
  max_note_chars: 1000,
  max_title_chars: 500,
  max_authors: 10,
  max_author_chars: 200,
  max_question_chars: 1000,
  max_answer_chars: 4000,
  max_questions: 6,
  max_total_chars: 60000,
  questions_per_minute: 10,
}

/** A selected highlight as the conversation shows and sends it, numbered from 1. */
export type Passage = { n: number; text: string; note: string | null; title: string; authors: string[] }
export type Exchange = { question: string; answer: string; cited: number[] }

// The server counts Unicode code points, not UTF-16 units.
const length = (value: string) => Array.from(value).length
const clip = (value: string, max: number) => (length(value) > max ? Array.from(value).slice(0, max).join('') : value)

export function passagesOf(highlights: Highlight[], books: ShelfBook[]): Passage[] {
  return highlights.map((h, index) => {
    const book = books.find((b) => b.key === h.document.content_hash)
    const authors = book?.authors?.length ? book.authors : [h.book_author ?? book?.author].filter((a): a is string => !!a)
    return { n: index + 1, text: h.text, note: h.comment?.trim() || null, title: h.book_title || book?.title || '', authors }
  })
}

/**
 * The request for the next question, within the server's limits: long passages,
 * notes, titles and authors are clipped, and `clipped` says whether any passage
 * text was. `tooLarge` when everything together is still over the server's total.
 */
export function discussBody(
  passages: Passage[], exchanges: Exchange[], question: string, includeNotes: boolean, limits: DiscussLimits,
) {
  let clipped = false
  const highlights = passages.map((p) => {
    const text = clip(p.text, limits.max_text_chars)
    if (text !== p.text) clipped = true
    const note = includeNotes && p.note ? clip(p.note, limits.max_note_chars) : undefined
    return {
      n: p.n, text, ...(note ? { note } : {}),
      title: p.title ? clip(p.title, limits.max_title_chars) : null,
      authors: p.authors.slice(0, limits.max_authors).map((a) => clip(a, limits.max_author_chars)),
    }
  })
  const messages = [
    ...exchanges.flatMap((e) => [
      { role: 'reader' as const, content: e.question },
      { role: 'assistant' as const, content: e.answer },
    ]),
    { role: 'reader' as const, content: question.trim() },
  ]
  const total =
    highlights.reduce((sum, h) => sum + length(h.text) + length(h.note ?? '') + length(h.title ?? '') +
      h.authors.reduce((s, a) => s + length(a), 0), 0) +
    messages.reduce((sum, m) => sum + length(m.content), 0)
  return { body: { highlights, messages }, clipped, tooLarge: total > limits.max_total_chars }
}

const excerpt = (text: string) => (length(text) > 300 ? Array.from(text).slice(0, 300).join('').trimEnd() + '…' : text)

/** The plain-text note that leaves Quire: question, answer, then the passages it used. */
export function shareNote(exchange: Exchange, passages: Passage[], heading: string) {
  const cited = exchange.cited.length ? exchange.cited.flatMap((n) => passages.filter((p) => p.n === n)) : passages
  const lines = cited.map((p, index) => {
    const source = [p.title, p.authors.join(', ')].filter(Boolean).join(', ')
    return `${index + 1}. "${excerpt(p.text)}"${source ? ` (${source})` : ''}`
  })
  return [exchange.question, '', exchange.answer, '', heading, ...lines].join('\n')
}
