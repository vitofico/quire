import { useState } from 'react'
import { addOpenCatalog, isAdded, openCatalogs, orderedFor, type OpenCatalog } from './openCatalogs'
import type { SavedCatalog } from './saved'

interface Props {
  language: string
  saved: SavedCatalog[]
  request: (path: string, init?: RequestInit) => Promise<Response>
  /** Called with the account's new entry once one is added. */
  onAdded: (catalog: SavedCatalog) => void
  t: (key: string) => string
}

/** The free catalogs Quire suggests, those in the reader's language first; one click adds one. */
export function OpenCatalogs({ language, saved, request, onAdded, t }: Props) {
  const [busy, setBusy] = useState('')
  const [failed, setFailed] = useState<{ id: string; message: string }>()
  const names = new Intl.DisplayNames([language], { type: 'language' })

  async function add(catalog: OpenCatalog) {
    setBusy(catalog.id)
    setFailed(undefined)
    try {
      onAdded(await addOpenCatalog(request, catalog))
    } catch (reason) {
      setFailed({ id: catalog.id, message: reason instanceof Error ? reason.message : String(reason) })
    } finally {
      setBusy('')
    }
  }

  return <section className="panel open-catalogs" aria-labelledby="open-catalogs-title">
    <h2 id="open-catalogs-title">{t('Open catalogs')}</h2>
    <p className="hint">
      {t('Free, legal books to start with. Adding one lets your Quire server contact that catalog’s site for you.')}
    </p>
    <ul>
      {orderedFor(openCatalogs, language).map(catalog => {
        const added = isAdded(saved, catalog)
        const facts = [
          ...catalog.languages.map(code => {
            const name = names.of(code) ?? code
            return name.charAt(0).toLocaleUpperCase(language) + name.slice(1)
          }),
          t(catalog.offers === 'open_licence' ? 'Open licence' : 'Public domain'),
        ]
        return <li key={catalog.id}>
          <div>
            <strong>{catalog.name}</strong>
            <span className="open-catalogs-facts">{facts.join(' · ')}</span>
            <span>{catalog.description[language] ?? catalog.description.en}</span>
            {failed?.id === catalog.id && <span role="alert">{t(failed.message)}</span>}
          </div>
          {added
            ? <span className="open-catalogs-added">{t('Added')}</span>
            : <button type="button" onClick={() => void add(catalog)} disabled={!!busy}
              aria-label={t('Add {name}').replace('{name}', catalog.name)}>
              {t(busy === catalog.id ? 'Adding…' : 'Add')}
            </button>}
        </li>
      })}
    </ul>
  </section>
}
