import { useState } from 'react'
import { type Highlight, type ShelfBook } from './data'

type Props = {
  passages: Highlight[]
  books: ShelfBook[]
  t: (key: string) => string
  open: (book: ShelfBook, highlight: Highlight) => void
  report: (message: string) => void
}
export function Passages({ passages, books, t, open, report }: Props) {
  const [query, setQuery] = useState('')
  const filtered = passages.filter((h) =>
    [h.text, h.comment, h.book_title, h.book_author]
      .join(' ')
      .toLocaleLowerCase()
      .includes(query.toLocaleLowerCase()),
  )
  return (
    <>
      <h1>{t('Highlights')}</h1>
      <input
        type="search"
        aria-label={t('Search passages or comments')}
        placeholder={t('Search passages or comments')}
        value={query}
        onChange={(e) => setQuery(e.target.value)}
      />
      {filtered.map((h) => {
        const book = books.find((b) => b.key === h.document.content_hash)
        const quote =
          h.text +
          ' — ' +
          [h.book_title, h.book_author].filter(Boolean).join(', ')
        return (
          <article className="quote" key={h.id}>
            <small>
              {[h.book_title, h.book_author].filter(Boolean).join(' · ')}
            </small>
            <blockquote>{h.text}</blockquote>
            {h.comment && <p>{h.comment}</p>}
            <div className="actions">
              <button
                onClick={async () => {
                  try {
                    await navigator.clipboard.writeText(quote)
                  } catch (e) {
                    report(String(e))
                  }
                }}
              >
                {t('Copy as quote')}
              </button>
              {book && (
                <button onClick={() => open(book, h)}>
                  {t(book.hasDownload ? 'Open passage' : 'Open your copy')}
                </button>
              )}
            </div>
          </article>
        )
      })}
      {!filtered.length && <p>{t('No saved passages')}</p>}
    </>
  )
}
