import { useCallback, useEffect, useRef, useState } from 'react'
import { type Highlight, type ShelfBook } from './data'
import type { DiscoveryRequest, DiscussLimits } from './discovery/api'
import { interpolate } from './discovery/translation'
import { Discussion } from './Discussion'
import { passagesOf } from './discuss'
import { Icon } from './icons'

type Props = {
  passages: Highlight[]
  books: ShelfBook[]
  t: (key: string) => string
  open: (book: ShelfBook, highlight: Highlight) => void
  report: (message: string) => void
  /** Set when the server can think through highlights and the reader opted in to AI. */
  discuss?: { request: DiscoveryRequest; limits: DiscussLimits }
}

/** One group per book, in the order each book first appears in the list. */
function byBook(list: Highlight[]) {
  const groups = new Map<string, Highlight[]>()
  for (const h of list) {
    const group = groups.get(h.document.content_hash)
    if (group) group.push(h)
    else groups.set(h.document.content_hash, [h])
  }
  return [...groups.values()]
}

export function Passages({ passages, books, t, open, report, discuss }: Props) {
  const [query, setQuery] = useState('')
  const [selecting, setSelecting] = useState(false)
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [discussing, setDiscussing] = useState<Highlight[]>()
  const opener = useRef<HTMLButtonElement>(null)
  const wasDiscussing = useRef(false)
  const stop = useCallback(() => setDiscussing(undefined), [])
  useEffect(() => {
    // Focus goes back to the button that opened the conversation.
    if (wasDiscussing.current && !discussing) opener.current?.focus()
    wasDiscussing.current = !!discussing
  }, [discussing])
  const max = discuss?.limits.max_highlights ?? 0
  const full = selected.size >= max
  const filtered = passages.filter((h) =>
    [h.text, h.comment, h.book_title, h.book_author]
      .join(' ')
      .toLocaleLowerCase()
      .includes(query.toLocaleLowerCase()),
  )
  const groups = byBook(filtered)
  const toggle = (id: string) =>
    setSelected((current) => {
      const next = new Set(current)
      if (!next.delete(id)) next.add(id)
      return next
    })
  const checking = !!discuss && selecting && !discussing
  const list = (
    <div>
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
          {discuss && !selecting && (
            <button onClick={() => setSelecting(true)}>{t('Select')}</button>
          )}
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
      {checking && (
        <div className="tools select-bar">
          <span role="status">
            {interpolate(t, '{count} selected', { count: selected.size })}
            {full && <><br />{interpolate(t, 'You can select up to {max} passages.', { max })}</>}
          </span>
          <button
            ref={opener}
            className="primary"
            disabled={!selected.size}
            // Numbered in the order shown, across books.
            onClick={() => setDiscussing(byBook(passages).flat().filter((h) => selected.has(h.id)))}
          >
            {t('Think it through')}
          </button>
          <button
            className="ghost"
            onClick={() => {
              setSelecting(false)
              setSelected(new Set())
            }}
          >
            {t('Cancel')}
          </button>
        </div>
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
                {checking && (
                  <label className="check">
                    <input
                      type="checkbox"
                      checked={selected.has(h.id)}
                      disabled={full && !selected.has(h.id)}
                      aria-describedby={`passage-${h.id}`}
                      onChange={() => toggle(h.id)}
                    />
                    {t('Select')}
                  </label>
                )}
                <blockquote id={`passage-${h.id}`}>{h.text}</blockquote>
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
    </div>
  )
  return (
    <div className={discussing ? 'discussing' : undefined}>
      {list}
      {discuss && discussing && (
        <Discussion
        request={discuss.request}
        limits={discuss.limits}
        passages={passagesOf(discussing, books)}
        t={t}
        close={stop}
        />
      )}
    </div>
  )
}
