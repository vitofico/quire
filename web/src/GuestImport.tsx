import { useState } from 'react'
import { openReplica, type Replica } from './data'

/** Local books move into an account only through this explicit action. */
export function GuestImport({
  destination,
  t,
}: {
  destination: Replica
  t: (s: string) => string
}) {
  const [books, setBooks] = useState<{ id: string; title: string }[]>([])
  const [selected, setSelected] = useState('')
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)
  const open = () => openReplica(JSON.stringify([location.origin, 'local', '']))
  async function load() {
    const guest = await open()
    try {
      const items = (await guest.snapshot()).books
        .filter((b) => b.hasDownload)
        .map((b) => ({ id: b.key, title: b.title }))
      setBooks(items)
      setSelected(items[0]?.id ?? '')
      if (!items.length) setMessage(t('No local downloads'))
    } finally {
      guest.close()
    }
  }
  async function copy() {
    setBusy(true)
    try {
      const guest = await open()
      try {
        const local = await guest.snapshot()
        const book = local.books.find((b) => b.key === selected)
        const blob = await guest.getEpub(selected)
        if (!book || !blob) throw new Error(t('No local downloads'))
        await destination.putBook(book, blob, {
          matchKey: selected,
          reading: {
            progress: local.progress.find(
              (p) => p.document.content_hash === selected,
            ),
            highlights: local.highlights.filter(
              (h) => h.document.content_hash === selected,
            ),
          },
        })
        setMessage(t('Copied to your account'))
      } finally {
        guest.close()
      }
    } catch (e) {
      setMessage(String(e))
    } finally {
      setBusy(false)
    }
  }
  return (
    <details
      onToggle={(e) => {
        if (e.currentTarget.open)
          void load().catch((e) => setMessage(String(e)))
      }}
    >
      <summary>{t('Copy a local book to this account')}</summary>
      {books.length > 0 && (
        <div className="actions">
          <select
            aria-label={t('Title')}
            value={selected}
            onChange={(e) => setSelected(e.target.value)}
          >
            {books.map((b) => (
              <option key={b.id} value={b.id}>
                {b.title}
              </option>
            ))}
          </select>
          <button disabled={busy} onClick={() => void copy()}>
            {t('Copy book and passages')}
          </button>
        </div>
      )}
      {message && <p role="status">{message}</p>}
    </details>
  )
}
