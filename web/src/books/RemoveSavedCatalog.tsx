import { useState } from 'react'
import { ask } from '../ask'
import { removeWithCopies, type SavedCatalog } from './saved'

type Props = {
  catalog: SavedCatalog
  /** The account's list: entries repeating this one's address are removed with it. */
  saved: SavedCatalog[]
  request: (path: string, init?: RequestInit) => Promise<Response>
  onRemoved: (catalog: SavedCatalog) => void
  t: (key: string) => string
  /** A short Remove button, named in full for screen readers, for a row that already shows the name. */
  compact?: boolean
}

/** Removes the open saved catalog from the account, after the same question the open catalogs list asks. */
export function RemoveSavedCatalog({ catalog, saved, request, onRemoved, t, compact }: Props) {
  const [busy, setBusy] = useState(false)
  const [failed, setFailed] = useState('')

  async function remove() {
    const sure = await ask(t('Remove {name}? It is also removed from your Quire account and your other devices. Downloaded books stay.').replace('{name}', catalog.name), {
      confirm: t('Remove'), cancel: t('Cancel'), destructive: true,
    })
    if (!sure) return
    setBusy(true)
    setFailed('')
    try {
      await removeWithCopies(request, catalog, saved)
      onRemoved(catalog)
    } catch (reason) {
      setFailed(reason instanceof Error ? reason.message : String(reason))
    } finally {
      setBusy(false)
    }
  }

  const named = t('Remove {name}').replace('{name}', catalog.name)
  return <p className="saved-catalog-remove">
    <button type="button" onClick={() => void remove()} disabled={busy} aria-label={compact && !busy ? named : undefined}>
      {busy ? t('Removing…') : compact ? t('Remove') : named}
    </button>
    {failed && <span role="alert">{t(failed)}</span>}
  </p>
}
