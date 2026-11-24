import { useEffect, useMemo, useState } from 'react'
import { type ReplicaSnapshot, type ShelfBook, type Progress } from './data'
export const authors = (book: ShelfBook) =>
  book.authors?.join(', ') || book.author || ''
export function findProgress(book: ShelfBook, rows: Progress[]) {
  return (
    rows.find((row) => row.document.content_hash === book.key) ??
    (!book.localOnly && book.metadata_id
      ? rows.find((row) => row.document.metadata_id === book.metadata_id)
      : undefined)
  )
}
// A book without artwork gets a steady colour of its own, so a shelf of them is not one block.
const tint = (title: string) =>
  `hsl(${[...title].reduce((sum, char) => sum + char.charCodeAt(0), 0) * 47 % 360} 24% 33%)`
export function Cover({ book }: { book: ShelfBook }) {
  const [url, setUrl] = useState('')
  useEffect(() => {
    if (!(book.cover instanceof Blob)) {
      setUrl('')
      return
    }
    const source = URL.createObjectURL(book.cover)
    setUrl(source)
    return () => URL.revokeObjectURL(source)
  }, [book.cover])
  return (
    <span
      className={'cover' + (url ? ' with-image' : '')}
      style={url ? undefined : { background: tint(book.title) }}
    >
      {url ? <img src={url} alt="" /> : book.title}
    </span>
  )
}
function Meter({ percent }: { percent: number }) {
  const label = Math.round(percent * 100) + '%'
  return (
    <span className="meter">
      <span>
        <i style={{ width: label }} />
      </span>
      <small>{label}</small>
    </span>
  )
}
type Props = {
  ready: boolean
  data: ReplicaSnapshot
  t: (s: string) => string
  choose: () => void
  details: (id: string) => void
  read: (id: string) => void
}
export function Shelf({ ready, data, t, choose, details, read }: Props) {
  const [search, setSearch] = useState(''),
    [filter, setFilter] = useState('All books'),
    [sort, setSort] = useState('Recently read'),
    [compact, setCompact] = useState(false)
  const rows = useMemo(
    () =>
      data.books.map((book) => ({
        book,
        p: findProgress(book, data.progress),
      })),
    [data],
  )
  const recent = rows
    .filter(({ p }) => p && !p.finished_at && !p.abandoned_at && p.percent > 0)
    .sort((a, b) =>
      b.p!.client_updated_at.localeCompare(a.p!.client_updated_at),
    )
    .slice(0, 3)
  const conflicts = data.books.filter(book => book.editionConflict)
  const matches = rows
    .filter(({ book, p }) => {
      if (
        (filter === 'Downloaded' && !book.hasDownload) ||
        (filter === 'Finished' && !p?.finished_at) ||
        (filter === 'Abandoned' && !p?.abandoned_at) ||
        (filter === 'In progress' &&
          (!p || p.finished_at || p.abandoned_at || !p.percent))
      )
        return false
      return [book.title, authors(book), book.series_name, book.isbn]
        .join(' ')
        .toLocaleLowerCase()
        .includes(search.toLocaleLowerCase())
    })
    .sort(
      (a, b) =>
        (sort === 'Author'
          ? authors(a.book).localeCompare(authors(b.book))
          : sort === 'Recently read'
            ? (b.p?.client_updated_at ?? '').localeCompare(
                a.p?.client_updated_at ?? '',
              )
            : 0) || a.book.title.localeCompare(b.book.title),
    )
  return (
    <>
      <div className="heading">
        <h1>{t('Your library')}</h1>
        <button className="primary" disabled={!ready} onClick={choose}>
          {t('Import EPUB')}
        </button>
      </div>
      {conflicts.length > 0 && (
        <section className="notice" aria-label={t('Resolve edition conflicts')}>
          <strong>{t('Resolve edition conflicts')}</strong>
          <ul>
            {conflicts.map(book => (
              <li key={book.key}><a href={'#/book/' + encodeURIComponent(book.key)}>{book.title}</a></li>
            ))}
          </ul>
        </section>
      )}
      {recent.length > 0 && (
        <section aria-label={t('Continue reading')}>
          <h2 className="eyebrow">{t('Continue reading')}</h2>
          <div className="keep">
            {recent.map(({ book, p }) => (
              <button
                className="keep-card"
                key={book.key}
                onClick={() =>
                  book.hasDownload ? read(book.key) : details(book.key)
                }
              >
                <Cover book={book} />
                <span>
                  <strong>{book.title}</strong>
                  <small>{authors(book)}</small>
                  <Meter percent={p!.percent} />
                </span>
              </button>
            ))}
          </div>
        </section>
      )}
      {!ready ? (
        <p aria-live="polite">{t('Opening library…')}</p>
      ) : !rows.length ? (
        <section className="empty">
          <h2>{t('Add your first book')}</h2>
          <p>{t('Drop an EPUB here or choose a file.')}</p>
          <button onClick={choose}>{t('Choose a file')}</button>
        </section>
      ) : (
        <>
          <div className="tools">
            <input
              type="search"
              aria-label={t('Search books')}
              placeholder={t('Search books')}
              value={search}
              onChange={(e) => setSearch(e.target.value)}
            />
            <select
              aria-label={t('All books')}
              value={filter}
              onChange={(e) => setFilter(e.target.value)}
            >
              {[
                'All books',
                'Downloaded',
                'In progress',
                'Finished',
                'Abandoned',
              ].map((s) => (
                <option key={s} value={s}>
                  {t(s)}
                </option>
              ))}
            </select>
            <select
              aria-label={t('Recently read')}
              value={sort}
              onChange={(e) => setSort(e.target.value)}
            >
              {['Recently read', 'Title', 'Author'].map((s) => (
                <option key={s} value={s}>
                  {t(s)}
                </option>
              ))}
            </select>
            <button onClick={() => setCompact(!compact)}>
              {t(compact ? 'Cover view' : 'Compact list')}
            </button>
          </div>
          <div className={'library-grid' + (compact ? ' compact' : '')}>
            {matches.map(({ book, p }) => (
              <button
                className="book-entry"
                key={book.key}
                onClick={() => details(book.key)}
              >
                <Cover book={book} />
                <span className="who">
                  <strong>{book.title}</strong>
                  <small>{authors(book)}</small>
                </span>
                {p?.finished_at ? (
                  <small>{t('Finished')}</small>
                ) : (
                  !!p?.percent && <Meter percent={p.percent} />
                )}
              </button>
            ))}
          </div>
          {!matches.length && <p>{t('No matching books')}</p>}
        </>
      )}
    </>
  )
}
