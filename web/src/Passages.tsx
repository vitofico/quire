import { useState } from 'react'
import { type Highlight, type ShelfBook } from './data'
import { Icon } from './icons'

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
      {passages.length > 0 ? (
        <div className="tools">
          <label className="search">
            <Icon name="search" />
            <input
              type="search"
              aria-label={t('Search passages or comments')}
              placeholder={t('Search passages or comments')}
              value={query}
              onChange={(e) => setQuery(e.target.value)}
            />
          </label>
        </div>
      ) : (
        <section className="empty">
          <span className="empty-icon">
            <Icon name="highlights" />
          </span>
          <h2>{t('No saved passages')}</h2>
          <p>{t('Select text while reading and choose Save highlight. Your passages and notes collect here.')}</p>
        </section>
      )}
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
                className="ghost"
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
                <button className="ghost" onClick={() => open(book, h)}>
                  {t(book.hasDownload ? 'Open passage' : 'Open your copy')}
                </button>
              )}
            </div>
          </article>
        )
      })}
      {passages.length > 0 && !filtered.length && (
        <p className="muted">{t('No matching passages')}</p>
      )}
    </>
  )
}
