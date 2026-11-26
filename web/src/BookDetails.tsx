import type { ReactNode } from 'react'
import type { DetailsFields, ShelfBook, Progress } from './data'
import { Cover, type LoadCover, authors } from './Shelf'
import { foundCover, originLabel, withDetails } from './details'
type Props = {
  book: ShelfBook
  loadCover?: LoadCover
  progress?: Progress
  t: (key: string) => string
  actions: ReactNode
  back: ReactNode
  conflict?: ReactNode
  /** Controls for the found and tidied details. */
  tidy?: ReactNode
}
function languageName(code?: string | null) {
  if (!code) return null
  try {
    return (
      new Intl.DisplayNames(document.documentElement.lang, {
        type: 'language',
      }).of(code) ?? code
    )
  } catch {
    return code
  }
}
/** Open Library's own page for the book, and no other address. */
function libraryLink(value?: string | null) {
  try {
    const url = new URL(value ?? '')
    const host = url.hostname
    return url.protocol === 'https:' && (host === 'openlibrary.org' || host.endsWith('.openlibrary.org')) ? url.href : undefined
  } catch {
    return undefined
  }
}
function fileSize(bytes: number) {
  const unit = bytes < 1_000_000 ? 'kilobyte' : 'megabyte'
  return new Intl.NumberFormat(document.documentElement.lang, {
    style: 'unit',
    unit,
    maximumFractionDigits: unit === 'kilobyte' ? 0 : 1,
  }).format(bytes / (unit === 'kilobyte' ? 1_000 : 1_000_000))
}
export function BookDetails({
  book,
  progress,
  t,
  actions,
  back,
  conflict,
  tidy,
  loadCover,
}: Props) {
  const own = book
  book = withDetails(own)
  const answer = own.details?.original ? undefined : own.details?.answer
  // Say where a value came from when it is not the book's own.
  const origin = (field: keyof DetailsFields | 'cover') => {
    const label = originLabel(own, field, t)
    return label && <small className="origin">{label}</small>
  }
  const openLibrary = libraryLink(answer?.openlibrary_url)
  const rows: [string, ReactNode, ReactNode?][] = [
    [
      t('Series'),
      [book.series_name, book.series_index]
        .filter((x) => x != null)
        .join(' · '),
      origin('series_name') || origin('series_index'),
    ],
    [t('First published'), answer?.book.year, origin('year')],
    [t('Publisher'), book.publisher, origin('publisher')],
    [t('Language'), languageName(book.language), origin('language')],
    ['ISBN', book.isbn, origin('isbn')],
    [t('File size'), book.sizeBytes ? fileSize(book.sizeBytes) : null],
    [
      t('Progress'),
      progress
        ? new Intl.NumberFormat(document.documentElement.lang, {
            style: 'percent',
          }).format(progress.percent)
        : null,
    ],
  ]
  const facts = rows.filter((row) => row[1])
  return (
    <>
      {back}
      <article className="book-detail">
        <div className="book-intro">
          <div className="detail-cover">
            <Cover book={book} load={loadCover} />
            {foundCover(own) &&
              (answer?.cover.generated ? <small className="origin">{t('Generated cover')}</small> : origin('cover'))}
          </div>
          <div>
            <h1>{book.title}</h1>
            {origin('title')}
            {authors(book) && <p className="byline">{authors(book)} {origin('authors')}</p>}
            {progress && progress.percent > 0 && !progress.finished_at && (
              <p className="muted">
                {t('{percent} read').replace(
                  '{percent}',
                  new Intl.NumberFormat(document.documentElement.lang, { style: 'percent' }).format(progress.percent),
                )}
              </p>
            )}
            {actions}
          </div>
        </div>
        {facts.length > 0 && (
          <dl className="facts">
            {facts.map(([label, value, source]) => (
              <div key={label}>
                <dt>{label}</dt>
                <dd>{value} {source}</dd>
              </div>
            ))}
          </dl>
        )}
        {book.description && (
          <p className="description">
            {book.description} {origin('description')}
          </p>
        )}
        {!!book.subjects?.length && (
          <ul className="subjects">
            {book.subjects.map((subject) => (
              <li key={subject}>{subject}</li>
            ))}
          </ul>
        )}
        {!!book.subjects?.length && origin('subjects')}
        {openLibrary && (
          <p className="hint">
            <a href={openLibrary} target="_blank" rel="noreferrer">
              {t('View on Open Library')}
            </a>
          </p>
        )}
        {tidy}
        {conflict}
      </article>
    </>
  )
}
