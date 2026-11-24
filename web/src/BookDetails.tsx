import type { ReactNode } from 'react'
import type { ShelfBook, Progress } from './data'
import { Cover, authors } from './Shelf'
type Props = {
  book: ShelfBook
  progress?: Progress
  t: (key: string) => string
  actions: ReactNode
  storage: ReactNode
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
  storage,
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
      <a href="#/library">{t('Back to library')}</a>
      <article className="book-detail">
        <div className="book-intro">
          <div className="detail-cover">
            <Cover book={book} />
          </div>
          <div>
            <h1>{book.title}</h1>
            {authors(book) && <p>{authors(book)}</p>}
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
        {book.description && <p>{book.description}</p>}
        {!!book.subjects?.length && <p>{book.subjects.join(' · ')}</p>}
        {conflict}
        {storage}
      </article>
    </>
  )
}
