import { useState } from 'react'
import { RemoveSavedCatalog } from '../books/RemoveSavedCatalog'
import { distinctSaved, movedOrder, reorderSavedCatalogs, type SavedCatalog } from '../books/saved'
import type { Translate } from './Settings'

type ApiRequest = (path: string, init?: RequestInit) => Promise<Response>

/** The catalogs kept in the Quire account, in the order every device shows them: move or remove one. */
export function SavedCatalogs({ saved, request, onChange, onRemoved, t }: {
  saved: SavedCatalog[]
  request: ApiRequest
  onChange: (saved: SavedCatalog[]) => void
  onRemoved: (catalog: SavedCatalog) => void
  t: Translate
}) {
  const [busy, setBusy] = useState(false),
    [failed, setFailed] = useState('')
  const shown = distinctSaved([...saved].sort((a, b) => a.position - b.position), false)

  async function move(catalog: SavedCatalog, by: -1 | 1) {
    setBusy(true)
    setFailed('')
    try {
      onChange(await reorderSavedCatalogs(request, movedOrder(saved, catalog.id, by)))
    } catch (reason) {
      setFailed(t(reason instanceof Error ? reason.message : String(reason)))
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <p className="hint">
        {t('Catalogs you add here or in the Quire app on Android are kept in your Quire account and appear on all your devices.')}
      </p>
      {shown.length ? (
        <ul className="catalog-rows">
          {shown.map((catalog, index) => (
            <li key={catalog.id} className="setting">
              <div className="setting-text">
                <span className="setting-label">{catalog.name}</span>
                <p className="hint">{catalog.host.replace(/^https?:\/\//, '')}</p>
              </div>
              <div className="setting-control actions">
                <button type="button" className="ghost" disabled={busy || index === 0}
                  onClick={() => void move(catalog, -1)}>
                  <span aria-hidden="true">↑</span>
                  <span className="visually-hidden">{t('Move {name} up').replace('{name}', catalog.name)}</span>
                </button>
                <button type="button" className="ghost" disabled={busy || index === shown.length - 1}
                  onClick={() => void move(catalog, 1)}>
                  <span aria-hidden="true">↓</span>
                  <span className="visually-hidden">{t('Move {name} down').replace('{name}', catalog.name)}</span>
                </button>
                <RemoveSavedCatalog catalog={catalog} saved={saved} request={request} onRemoved={onRemoved} t={t} compact />
              </div>
            </li>
          ))}
        </ul>
      ) : (
        <p>{t('No catalogs are saved to your account yet.')}</p>
      )}
      {failed && <p role="alert">{failed}</p>}
      <p className="actions"><a className="button" href="#/catalog">{t('Add or browse catalogs')}</a></p>
    </>
  )
}
