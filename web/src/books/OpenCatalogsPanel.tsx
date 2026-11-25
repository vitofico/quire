import { useState } from 'react'
import { addOpenCatalog, isAdded, openCatalogs, orderedFor, type OpenCatalog } from './openCatalogs'
import type { SavedCatalog } from './saved'

interface Props {
  language: string
  saved: SavedCatalog[]
  request: (path: string, init?: RequestInit) => Promise<Response>
  /** Called with the account's entry once one is added, or found already there. */
  onAdded: (catalog: SavedCatalog) => void
  t: (key: string) => string
}

const offerLabels: Record<OpenCatalog['offers'], string> = {
  public_domain: 'Public domain',
  public_domain_us: 'Public domain in the US',
  open_licence: 'Open licence',
}

/** The free catalogs Quire suggests, those in the reader's language first; one click adds one. */
export function OpenCatalogs({ language, saved, request, onAdded, t }: Props) {
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

  return <section className="panel open-catalogs" aria-labelledby="open-catalogs-title">
    <h2 id="open-catalogs-title">{t('Open catalogs')}</h2>
    <p className="hint">
      {t('Free, legal books to start with. Adding one lets your Quire server contact that catalog’s site for you, and it shows on your phones too.')}
    </p>
    <ul>
      {orderedFor(openCatalogs, language).map(catalog => {
        const added = saved.some(c => c.id === entries[catalog.id]) || isAdded(saved, catalog)
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
            ? <span className="open-catalogs-added">{t('Added')}</span>
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
