import type { ReactNode } from 'react'
import type { ShelfBook, Progress } from './data'
import { Cover, authors } from './Shelf'
type Props = {
  book: ShelfBook
  progress?: Progress
  t: (key: string) => string
  actions: ReactNode
  back: ReactNode
  conflict?: ReactNode
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
}: Props) {
  const facts = [
    [
      t('Series'),
      [book.series_name, book.series_index]
        .filter((x) => x != null)
        .join(' · '),
    ],
    [t('Publisher'), book.publisher],
    [t('Language'), languageName(book.language)],
    ['ISBN', book.isbn],
    [t('File size'), book.sizeBytes ? fileSize(book.sizeBytes) : null],
    [
      t('Progress'),
      progress
        ? new Intl.NumberFormat(document.documentElement.lang, {
            style: 'percent',
          }).format(progress.percent)
        : null,
    ],
  ].filter((row) => row[1])
  return (
    <>
      {back}
      <article className="book-detail">
        <div className="book-intro">
          <div className="detail-cover">
            <Cover book={book} />
          </div>
          <div>
            <h1>{book.title}</h1>
            {authors(book) && <p className="byline">{authors(book)}</p>}
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
            {facts.map(([label, value]) => (
              <div key={label}>
                <dt>{label}</dt>
                <dd>{value}</dd>
              </div>
            ))}
          </dl>
        )}
        {book.description && <p className="description">{book.description}</p>}
        {!!book.subjects?.length && (
          <ul className="subjects">
            {book.subjects.map((subject) => (
              <li key={subject}>{subject}</li>
            ))}
          </ul>
        )}
        {conflict}
      </article>
    </>
  )
}
