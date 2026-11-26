import { useState } from 'react'
import { ask } from '../ask'
import { removeSavedCatalog, type SavedCatalog } from './saved'

type Props = {
  catalog: SavedCatalog
  request: (path: string, init?: RequestInit) => Promise<Response>
  onRemoved: (catalog: SavedCatalog) => void
  t: (key: string) => string
}

/** Removes the open saved catalog from the account, after the same question the open catalogs list asks. */
export function RemoveSavedCatalog({ catalog, request, onRemoved, t }: Props) {
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
      await removeSavedCatalog(request, catalog.id)
      onRemoved(catalog)
    } catch (reason) {
      setFailed(reason instanceof Error ? reason.message : String(reason))
    } finally {
      setBusy(false)
    }
  }

  return <p className="saved-catalog-remove">
    <button type="button" onClick={() => void remove()} disabled={busy}>
      {busy ? t('Removing…') : t('Remove {name}').replace('{name}', catalog.name)}
    </button>
    {failed && <span role="alert">{t(failed)}</span>}
  </p>
}
