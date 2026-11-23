import { useEffect, useRef, useState, type FormEvent } from 'react'
import { InsightPanel } from '../discovery/Insights'
import { catalogInsightIdentity } from '../discovery/api'
import './catalog.css'
import { makeCatalogClient, type OpdsFeed, type OpdsPublication, type OpdsSearch } from './opds'

type Client = ReturnType<typeof makeCatalogClient>
type Request = (url: string, init?: RequestInit) => Promise<Response>

export interface CatalogProps {
  url: string
  authorization?: string
  request?: Request
  onAcquire: (blob: Blob, publication: OpdsPublication) => Promise<void>
  onImport?: () => void
  t?: (key: string) => string
  apiRequest?: Request
  aiAvailable?: boolean
  catalogAuthenticated?: boolean
  signInCatalog?: (username: string, password: string, url?: string) => Promise<void>
  signOutCatalog?: () => void
}

type Destination = { href: string } | { search: OpdsSearch; query: string }

function Cover({ client, publication }: { client: Client; publication: OpdsPublication }) {
  const [source, setSource] = useState<string>()
  const href = publication.coverHref
  useEffect(() => {
    setSource(undefined)
    if (!href) return
    const request = new AbortController()
    let objectUrl: string | undefined
    void client.cover(href, request.signal).then(blob => {
      if (request.signal.aborted) return
      objectUrl = URL.createObjectURL(blob)
      setSource(objectUrl)
    }).catch(() => undefined)
    return () => {
      request.abort()
      if (objectUrl) URL.revokeObjectURL(objectUrl)
    }
  }, [client, href])

  return source
    ? <img className="catalog-cover" src={source} alt="" />
    : <div className="catalog-cover catalog-cover-fallback" aria-hidden="true">{publication.title.slice(0, 1)}</div>
}

export function Catalog({
  url, authorization, request, onAcquire, onImport, t = key => key, apiRequest, aiAvailable,
  catalogAuthenticated, signInCatalog, signOutCatalog,
}: CatalogProps) {
  const [feed, setFeed] = useState<OpdsFeed>()
  const [client, setClient] = useState<Client>()
  const [trail, setTrail] = useState<OpdsFeed[]>([])
  const [searchLink, setSearchLink] = useState<OpdsSearch>()
  const [query, setQuery] = useState('')
  const [sort, setSort] = useState('catalog')
  const [selected, setSelected] = useState<OpdsPublication>()
  const [acquired, setAcquired] = useState<string>()
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const [downloading, setDownloading] = useState(false)
  const [showCredentials, setShowCredentials] = useState(false)
  const [authenticating, setAuthenticating] = useState(false)
  const navigation = useRef<AbortController | null>(null)
  const acquisition = useRef<AbortController | null>(null)

  async function load(active: Client, destination: Destination, history: OpdsFeed[]) {
    navigation.current?.abort()
    const operation = new AbortController()
    navigation.current = operation
    setLoading(true)
    setError('')
    setSelected(undefined)
    try {
      const result = 'href' in destination
        ? await active.feed(destination.href, operation.signal)
        : await active.search(destination.search, destination.query, operation.signal)
      if (operation.signal.aborted) return
      setFeed(result)
      setTrail(history)
      if (result.search) setSearchLink(result.search)
    } catch (reason) {
      if (!operation.signal.aborted) setError(reason instanceof Error ? reason.message : String(reason))
    } finally {
      if (!operation.signal.aborted) setLoading(false)
    }
  }

  useEffect(() => {
    setFeed(undefined)
    setClient(undefined)
    setTrail([])
    setSearchLink(undefined)
    setQuery('')
    setSelected(undefined)
    setAcquired(undefined)
    setDownloading(false)
    setError('')
    setLoading(false)
    try {
      const active = makeCatalogClient({ url, authorization, request })
      setClient(active)
      void load(active, { href: url }, [])
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : String(reason))
    }
    return () => {
      navigation.current?.abort()
      acquisition.current?.abort()
    }
  }, [url, authorization, request])

  function navigate(href: string) {
    if (client && feed) void load(client, { href }, [...trail, feed])
  }

  function back() {
    const prior = trail.at(-1)
    if (!prior) return
    setSelected(undefined)
    setFeed(prior)
    setTrail(trail.slice(0, -1))
    setError('')
  }

  function search(event: FormEvent) {
    event.preventDefault()
    if (client && feed && searchLink && query.trim()) {
      void load(client, { search: searchLink, query: query.trim() }, [...trail, feed])
    }
  }

  async function connect(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!signInCatalog || authenticating) return
    const form = event.currentTarget
    const data = new FormData(form)
    setAuthenticating(true)
    setError('')
    try {
      await signInCatalog(String(data.get('catalogUsername') ?? ''), String(data.get('catalogPassword') ?? ''), url)
      form.reset()
      setShowCredentials(false)
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : String(reason))
    } finally {
      setAuthenticating(false)
    }
  }

  async function acquire(publication: OpdsPublication) {
    if (!client || acquisition.current) return
    const operation = new AbortController()
    acquisition.current = operation
    setDownloading(true)
    setError('')
    try {
      const blob = await client.acquire(publication.epubHref, operation.signal)
      if (operation.signal.aborted) return
      await onAcquire(blob, publication)
      if (!operation.signal.aborted) setAcquired(publication.epubHref)
    } catch (reason) {
      if (!operation.signal.aborted) setError(reason instanceof Error ? reason.message : String(reason))
    } finally {
      if (acquisition.current === operation) acquisition.current = null
      if (!operation.signal.aborted) setDownloading(false)
    }
  }

  const publications = [...(feed?.publications ?? [])]
  if (sort === 'title') publications.sort((a, b) => a.title.localeCompare(b.title))
  if (sort === 'author') publications.sort((a, b) =>
    (a.authors[0] ?? '').localeCompare(b.authors[0] ?? '') || a.title.localeCompare(b.title))

  return <section className="catalog">
    <header className="catalog-header">
      <h1>{feed?.title || t('Catalog')}</h1>
      <div className="catalog-actions">
        {trail.length > 0 && <button type="button" onClick={back} disabled={loading || downloading}>{t('Back')}</button>}
        {catalogAuthenticated && signOutCatalog &&
          <button type="button" onClick={signOutCatalog} disabled={downloading}>{t('Sign out of catalog')}</button>}
        {!catalogAuthenticated && signInCatalog &&
          <button type="button" onClick={() => setShowCredentials(open => !open)} aria-expanded={showCredentials}>
            {t('Catalog sign-in')}
          </button>}
        {onImport && <button type="button" onClick={onImport}>{t('Import EPUB')}</button>}
      </div>
    </header>
    {showCredentials && signInCatalog && <form className="catalog-credentials" onSubmit={connect}>
      <label>{t('Catalog username')} <input name="catalogUsername" autoComplete="username" required /></label>
      <label>{t('Catalog password')} <input name="catalogPassword" type="password" autoComplete="current-password" required /></label>
      <button type="submit" disabled={authenticating}>{t(authenticating ? 'Signing in…' : 'Catalog sign-in')}</button>
    </form>}
    {error && <p role="alert">{t(error)}</p>}
    {loading && <p role="status">{t('Loading catalog…')}</p>}
    {!loading && error && !feed && client &&
      <button type="button" onClick={() => void load(client, { href: url }, [])}>{t('Retry')}</button>}
    {searchLink && <form className="catalog-actions" onSubmit={search}>
      <input type="search" aria-label={t('Search catalog')} placeholder={t('Search catalog')} value={query}
        onChange={event => setQuery(event.target.value)} />
      <button type="submit" disabled={loading || downloading || !query.trim()}>{t('Search')}</button>
    </form>}
    {client && selected && <article className="catalog-details" aria-label={selected.title}>
      <button type="button" onClick={() => setSelected(undefined)} disabled={downloading}>{t('Close details')}</button>
      <Cover client={client} publication={selected} />
      <h2>{selected.title}</h2>
      {selected.authors.length > 0 && <p>{selected.authors.join(', ')}</p>}
      {selected.description && <p>{selected.description}</p>}
      <button type="button" onClick={() => void acquire(selected)} disabled={downloading || acquired === selected.epubHref}>
        {t(downloading ? 'Downloading…' : acquired === selected.epubHref ? 'Download complete' : 'Download EPUB')}
      </button>
      {aiAvailable && apiRequest && <InsightPanel t={t} request={apiRequest} aiAvailable
        identity={{ ...catalogInsightIdentity(selected.epubHref, selected.metadataId), calibre_book_id: selected.calibreBookId }}
        metadata={{ title: selected.title, author: selected.authors.join(', '), description: selected.description }} />}
    </article>}
    {!selected && feed && <>
      {feed.navigation.length > 0 && <ul className="catalog-navigation">
        {feed.navigation.map(link => <li key={link.href}>
          <button type="button" disabled={loading} onClick={() => navigate(link.href)}>{link.title}</button>
        </li>)}
      </ul>}
      {publications.length > 0 && <label>{t('Sort by')} <select value={sort} onChange={event => setSort(event.target.value)}>
        <option value="catalog">{t('Catalog order')}</option>
        <option value="title">{t('Title')}</option>
        <option value="author">{t('Author')}</option>
      </select></label>}
      {client && publications.length > 0 && <ul className="catalog-publications">
        {publications.map(book => <li key={book.epubHref}>
          <button type="button" className="catalog-book" onClick={() => setSelected(book)} disabled={loading}>
            <Cover client={client} publication={book} />
            <strong>{book.title}</strong>
            <span>{book.authors.join(', ')}</span>
          </button>
        </li>)}
      </ul>}
      {!feed.navigation.length && !publications.length && <p>{t('No EPUB books in this feed.')}</p>}
      {(feed.previous || feed.next) && <nav className="catalog-actions" aria-label={t('Catalog pages')}>
        {feed.previous && <button type="button" onClick={() => navigate(feed.previous!)} disabled={loading}>{t('Previous page')}</button>}
        {feed.next && <button type="button" onClick={() => navigate(feed.next!)} disabled={loading}>{t('Next page')}</button>}
      </nav>}
    </>}
  </section>
}

export default Catalog
