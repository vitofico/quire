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
    [t('Language'), book.language],
    ['ISBN', book.isbn],
    [
      t('File size'),
      book.sizeBytes
        ? new Intl.NumberFormat(document.documentElement.lang, {
            style: 'unit',
            unit: 'megabyte',
            maximumFractionDigits: 2,
          }).format(book.sizeBytes / 1_000_000)
        : null,
    ],
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
