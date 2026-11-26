import { useState } from 'react'
import { ask } from '../ask'
import { addOpenCatalog, addedEntry, openCatalogs, orderedFor, type OpenCatalog } from './openCatalogs'
import { removeWithCopies, type SavedCatalog } from './saved'

interface Props {
  language: string
  saved: SavedCatalog[]
  request: (path: string, init?: RequestInit) => Promise<Response>
  /** Called with the account's entry once one is added, or found already there. */
  onAdded: (catalog: SavedCatalog) => void
  /** Called with the account's entry once it is removed. */
  onRemoved: (catalog: SavedCatalog) => void
  t: (key: string) => string
}

const offerLabels: Record<OpenCatalog['offers'], string> = {
  public_domain: 'Public domain',
  public_domain_us: 'Public domain in the US',
  open_licence: 'Open licence',
}

/** The free catalogs Quire suggests, those in the reader's language first; one click adds or removes one. */
export function OpenCatalogs({ language, saved, request, onAdded, onRemoved, t }: Props) {
  const [busy, setBusy] = useState('')
  const [failed, setFailed] = useState<{ id: string; message: string }>()
  // The account entry each one became here: marked added by its id, whatever its name or host.
  const [entries, setEntries] = useState<Record<string, string>>({})
  const names = new Intl.DisplayNames([language], { type: 'language' })
  const languageName = (code: string) => {
    let name = code
    try { name = names.of(code) ?? code } catch { /* A malformed code shows as it is. */ }
    return name.charAt(0).toLocaleUpperCase(language) + name.slice(1)
  }

  async function add(catalog: OpenCatalog) {
    setBusy(catalog.id)
    setFailed(undefined)
    try {
      const entry = await addOpenCatalog(request, catalog)
      setEntries(known => ({ ...known, [catalog.id]: entry.id }))
      onAdded(entry)
    } catch (reason) {
      setFailed({ id: catalog.id, message: reason instanceof Error ? reason.message : String(reason) })
    } finally {
      setBusy('')
    }
  }

  async function remove(catalog: OpenCatalog, entry: SavedCatalog) {
    const sure = await ask(t('Remove {name}? It is also removed from your Quire account and your other devices. Downloaded books stay.').replace('{name}', entry.name), {
      confirm: t('Remove'), cancel: t('Cancel'), destructive: true,
    })
    if (!sure) return
    setBusy(catalog.id)
    setFailed(undefined)
    try {
      await removeWithCopies(request, entry, saved)
      onRemoved(entry)
    } catch (reason) {
      setFailed({ id: catalog.id, message: reason instanceof Error ? reason.message : String(reason) })
    } finally {
      setBusy('')
    }
  }

  return <section className="panel open-catalogs" aria-labelledby="open-catalogs-title">
    <h2 id="open-catalogs-title">{t('Open catalogs')}</h2>
    <p className="hint">
      {t('Free, legal books to start with. Adding one lets your Quire server contact that catalog’s site for you, and it shows on your phones too.')}
    </p>
    <ul>
      {orderedFor(openCatalogs, language).map(catalog => {
        const added = saved.find(c => c.id === entries[catalog.id]) ?? addedEntry(saved, catalog)
        const facts = [...catalog.languages.map(languageName), t(offerLabels[catalog.offers] ?? 'Public domain')]
        const adding = busy === catalog.id
        return <li key={catalog.id}>
          <div>
            <strong>{catalog.name}</strong>
            <span className="open-catalogs-facts">{facts.join(' · ')}</span>
            <span>{catalog.description[language] ?? catalog.description.en}</span>
            <a href={catalog.home} target="_blank" rel="noopener noreferrer">
              {new URL(catalog.home).host.replace(/^www\./, '')}
            </a>
            {failed?.id === catalog.id && <span role="alert">{t(failed.message)}</span>}
          </div>
          {added
            ? <div className="open-catalogs-added">
              <span>{t('Added')}</span>
              <button type="button" onClick={() => void remove(catalog, added)} disabled={!!busy}
                aria-label={busy === catalog.id ? undefined : t('Remove {name}').replace('{name}', catalog.name)}>
                {t(busy === catalog.id ? 'Removing…' : 'Remove')}
              </button>
            </div>
            : <button type="button" onClick={() => void add(catalog)} disabled={!!busy}
              // While adding, the button's own text is what a screen reader hears.
              aria-label={adding ? undefined : t('Add {name}').replace('{name}', catalog.name)}>
              {t(adding ? 'Adding…' : 'Add')}
            </button>}
        </li>
      })}
    </ul>
  </section>
}
