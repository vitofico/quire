import { useState } from 'react'
import type { ShelfBook } from './data'
import { type DetailsStore, fetchDetails } from './details'
import { type DiscoveryRequest, translateRequestMessage } from './discovery/api'
import { useAiOperation } from './discovery/aiOperation'

type Props = {
  book: ShelfBook
  store: DetailsStore | null
  /** Set when this server tidies details and the reader opted in to AI. */
  request?: DiscoveryRequest
  t: (key: string) => string
}

/** "Tidy details" on request, and the switch between the tidied details and the book's own. */
export function TidyDetails({ book, store, request, t }: Props) {
  const { run, busy, error } = useAiOperation(request, `details:${book.key}`)
  const [done, setDone] = useState(false)
  const record = book.details
  const changed = Object.values(record?.answer?.origins ?? {}).some(
    (origin) => origin !== 'file' && origin !== 'catalog',
  )
  if (!store || (!request && !changed)) return null
  return (
    <section className="tidy">
      {request && <p className="hint">{t('Tidying sends this book’s details to your configured AI service.')}</p>}
      <div className="actions">
        {request && (
          <button
            disabled={busy}
            onClick={() => {
              setDone(false)
              // A tidy spends a generation: leaving the page does not abort it.
              void run((signal) => fetchDetails(store, request, book, 'tidy', signal), () => setDone(true), {})
            }}
          >
            {t('Tidy details')}
          </button>
        )}
        {record && changed && (
          <button
            disabled={busy}
            aria-pressed={record.original === true}
            onClick={() => void store.setDetails(book.key, { ...record, original: !record.original }).catch(() => undefined)}
          >
            {t('Use original')}
          </button>
        )}
      </div>
      {busy && <p role="status">{t('Tidying details…')}</p>}
      {done && <p role="status">{t('Details tidied.')}</p>}
      {error && <p role="alert">{translateRequestMessage(t, error)}</p>}
    </section>
  )
}
