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
  // One section per book, in the order each book first appears in the list.
  const byBook = new Map<string, Highlight[]>()
  for (const h of filtered) {
    const group = byBook.get(h.document.content_hash)
    if (group) group.push(h)
    else byBook.set(h.document.content_hash, [h])
  }
  const groups = [...byBook.values()]
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
      {groups.map((group) => {
        const first = group[0]
        const book = books.find((b) => b.key === first.document.content_hash)
        const source = [first.book_title, first.book_author].filter(Boolean).join(', ')
        return (
          <section className="quote-group" key={first.document.content_hash}>
            <h2>
              {first.book_title || book?.title}
              {first.book_author && <small>{first.book_author}</small>}
            </h2>
            {group.map((h) => (
              <article className="quote" key={h.id}>
                <blockquote>{h.text}</blockquote>
                {h.comment && <p>{h.comment}</p>}
                <div className="actions">
                  <button
                    className="ghost"
                    onClick={async () => {
                      try {
                        await navigator.clipboard.writeText(h.text + ' — ' + source)
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
            ))}
          </section>
        )
      })}
      {passages.length > 0 && !filtered.length && (
        <p className="muted">{t('No matching passages')}</p>
      )}
    </>
  )
}
