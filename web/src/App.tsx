import {
  Component,
  lazy,
  Suspense,
  useCallback,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from 'react'
import { useAccount, AccountSettings, type AccountState } from './account'
import {
  EditionConflict,
  openReplica,
  bookDocument,
  type Highlight,
  type LocalBook,
  type ShelfBook,
} from './data'
import { openEpub, type Epub } from './books/epub'
import { makeCatalogClient } from './books/opds'
import { OfflineSettings } from './OfflineSettings'
import { BookDetails } from './BookDetails'
import { Passages } from './Passages'
import { GuestImport } from './GuestImport'
import { authors, findProgress, Shelf } from './Shelf'
import { useReplica } from './useReplica'
import noticesUrl from './legal/notices.txt?url'
import { translator, languages } from './language'
import { catalogInsightIdentity, promotePreview } from './discovery/api'
const Reader = lazy(() => import('./reader/Reader'))
const Catalog = lazy(() => import('./books/Catalog'))
const Discovery = lazy(() =>
  import('./discovery').then((m) => ({ default: m.DiscoveryPanel })),
)
const AiSettings = lazy(() =>
  import('./discovery').then((m) => ({ default: m.AiSettingsPanel })),
)
const Insights = lazy(() =>
  import('./discovery').then((m) => ({ default: m.InsightPanel })),
)
const sections = ['Library', 'Catalog', 'Highlights', 'Discover', 'Settings']
const href = (section: string, id?: string) =>
  '#/' + section.toLowerCase() + (id ? '/' + encodeURIComponent(id) : '')
const go = (section: string, id?: string) => {
  location.hash = href(section, id)
}
function publicAcquisition(href?: string | null) {
  if (!href) return undefined
  try {
    const u = new URL(href, location.origin)
    return !u.username &&
      !u.password &&
      !u.search &&
      !u.hash &&
      ['https:', 'http:'].includes(u.protocol)
      ? href
      : undefined
  } catch {
    return undefined
  }
}
function getRoute() {
  const [view = 'library', encoded = ''] = location.hash
    .replace(/^#\/?/, '')
    .split('/')
  let id = ''
  try {
    id = decodeURIComponent(encoded)
  } catch {
    /* invalid URL */
  }
  return { view: view || 'library', id }
}
function preference(key: string, fallback: string) {
  try {
    return localStorage.getItem('quire-ui:' + key) ?? fallback
  } catch {
    return fallback
  }
}
function useOption(key: string, initial: string) {
  const [value, set] = useState(() => preference(key, initial))
  return [
    value,
    (next: string) => {
      set(next)
      try {
        localStorage.setItem('quire-ui:' + key, next)
      } catch {
        /* private mode */
      }
    },
  ] as const
}
class ErrorBoundary extends Component<
  { children: ReactNode },
  { message: string }
> {
  state = { message: '' }
  static getDerivedStateFromError(error: Error) {
    return { message: error.message }
  }
  render() {
    return this.state.message ? (
      <div className="notice" role="alert">
        {this.state.message}{' '}
        <a href="#/library" onClick={() => this.setState({ message: '' })}>
          Back to library
        </a>
      </div>
    ) : (
      this.props.children
    )
  }
}
export default function App() {
  const account = useAccount()
  return (
    <ErrorBoundary>
      <Workspace key={account.active.namespace} account={account} />
    </ErrorBoundary>
  )
}
function Workspace({ account }: { account: AccountState }) {
  const { active, bootstrap } = account
  const [route, setRoute] = useState(getRoute),
    [notice, setNotice] = useState(''),
    [working, setWorking] = useState(false)
  const previousRoute = useRef(route)
  const main = useRef<HTMLElement>(null)
  const [lang, setLang] = useOption(
    'language',
    navigator.language.split('-')[0] in languages
      ? navigator.language.split('-')[0]
      : 'en',
  )
  const [theme, setTheme] = useOption('theme', 'System'),
    [art, setArt] = useOption('art', 'on')
  const t = translator(lang)
  const report = useCallback((s: string) => setNotice(s), [])
  const syncEnabled =
    active.mode === 'connected' &&
    (active.capabilities?.progress_metadata_sync ?? bootstrap?.sync ?? false)
  const transport = syncEnabled ? active.request : undefined
  const { replica, snapshot, sync, busy } = useReplica(
    active.namespace,
    transport,
    report,
    syncEnabled,
  )
  const [epub, setEpub] = useState<Epub>(),
    [jump, setJump] = useState<Highlight>()
  const [catalogOverride, setCatalogOverride] = useState(''),
    [catalogDraft, setCatalogDraft] = useState('')
  const catalogUrl =
    active.catalogUrl || catalogOverride || bootstrap?.catalogUrl || ''
  const [drag, setDrag] = useState(false)
  const file = useRef<HTMLInputElement>(null),
    target = useRef<string | undefined>(undefined),
    alive = useRef(true)
  const book = snapshot.books.find((b) => b.key === route.id)
  const current = book && findProgress(book, snapshot.progress)
  const editionConflict = book?.editionConflict
  const aiAvailable = bootstrap?.aiAvailable === true && !!active.request
  const passages = snapshot.highlights.filter((h) => !h.deleted_at)
  useEffect(() => {
    alive.current = true
    return () => {
      alive.current = false
    }
  }, [])
  useEffect(() => {
    const onHash = () => {
      setRoute(getRoute())
      setNotice('')
    }
    window.addEventListener('hashchange', onHash)
    return () => window.removeEventListener('hashchange', onHash)
  }, [])
  useEffect(() => {
    document.documentElement.lang = lang
  }, [lang])
  useEffect(() => {
    const previous = previousRoute.current
    previousRoute.current = route
    if (
      (previous.view !== route.view || previous.id !== route.id) &&
      route.view !== 'read'
    )
      main.current?.focus()
  }, [route])
  useEffect(() => {
    const media = matchMedia('(prefers-color-scheme: dark)')
    const update = () =>
      (document.documentElement.dataset.theme =
        theme === 'System'
          ? media.matches
            ? 'ink'
            : 'paper'
          : theme.toLowerCase())
    update()
    media.addEventListener('change', update)
    return () => media.removeEventListener('change', update)
  }, [theme])
  useEffect(() => {
    let live = true
    if (replica)
      replica
        .getPreference<string>('catalog')
        .then((v) => {
          if (live && v) setCatalogOverride(v)
        })
        .catch((e) => report(String(e)))
    return () => {
      live = false
    }
  }, [replica, report])
  useEffect(() => {
    let live = true
    setEpub(undefined)
    if (route.view === 'read' && replica && route.id) {
      replica
        .getEpub(route.id)
        .then((blob) => {
          if (!blob) throw new Error('Open your copy to read this book.')
          return openEpub(blob)
        })
        .then((parsed) => {
          if (live) setEpub(parsed)
        })
        .catch((e) => {
          if (live) report(String(e))
        })
    }
    return () => {
      live = false
    }
  }, [route.view, route.id, replica, report])
  const run = async (fn: () => Promise<unknown>) => {
    setWorking(true)
    setNotice('')
    try {
      await fn()
    } catch (e) {
      if (alive.current) report(e instanceof Error ? e.message : String(e))
    } finally {
      if (alive.current) setWorking(false)
    }
  }
  function choose(match?: string) {
    if (!replica) {
      report('Storage is unavailable.')
      return
    }
    target.current = match
    if (file.current) {
      file.current.multiple = !match
      file.current.click()
    }
  }
  async function imported(
    blob: Blob,
    acquisition?: string | null,
    match?: string,
  ) {
    if (!replica) throw new Error('Storage is unavailable.')
    const parsed = await openEpub(blob),
      m = parsed.metadata
    const cover = m.coverHref && parsed.bytes(m.coverHref)
    const record: LocalBook = {
      ...parsed.identity,
      key: parsed.identity.content_hash,
      title: m.title,
      authors: m.authors,
      language: m.language,
      isbn: m.isbn,
      series_name: m.series,
      series_index: m.seriesIndex,
      subjects: m.subjects,
      description: m.description,
      publisher: m.publisher,
      sizeBytes: blob.size,
      opds_href:
        publicAcquisition(acquisition) ??
        snapshot.books.find((b) => b.key === parsed.identity.content_hash)
          ?.opds_href,
      cover: cover
        ? new Blob([new Uint8Array(cover)], {
            type: parsed.mediaType(m.coverHref!) ?? 'image/jpeg',
          })
        : undefined,
    }
    try {
      await replica.putBook(record, blob, { matchKey: match })
    } catch (e) {
      if (
        !(e instanceof EditionConflict) ||
        match ||
        !confirm(
          'Another edition has this identifier. Keep this copy only in this browser?',
        )
      )
        throw e
      await replica.putBook(record, blob, { localOnly: true })
    }
    if (aiAvailable && active.request && syncEnabled)
      void sync()
        .then(() =>
          promotePreview(active.request!, {
            ...parsed.identity,
            isbn: m.isbn,
            ...(publicAcquisition(acquisition)
              ? { opds_href: catalogInsightIdentity(acquisition!).opds_href }
              : {}),
          }),
        )
        .catch(() => {})
    return record.key
  }
  async function importFiles(files: File[], match?: string) {
    let key = ''
    for (const picked of files) {
      key = await imported(picked, null, match)
    }
    if (alive.current && key)
      go(
        files.length === 1 ? 'book' : 'library',
        files.length === 1 ? key : undefined,
      )
  }
  async function clearNamespace(ns: string, discardPending = false) {
    const store = await openReplica(ns)
    try {
      await store.clear({ discardPending })
    } finally {
      store.close()
    }
  }
  const saveStatus = async (b: ShelfBook, status: string) => {
    const p = findProgress(b, snapshot.progress),
      now = new Date().toISOString()
    await replica?.putProgress({
      document: bookDocument(b),
      locator: p?.locator ?? '',
      percent: p?.percent ?? 0,
      client_updated_at: now,
      finished_at: status === 'finished' ? now : null,
      abandoned_at: status === 'abandoned' ? now : null,
    })
  }
  const details = (id: string) => go('book', id)
  const read = (id: string, h?: Highlight) => {
    setJump(h)
    go('read', id)
  }
  const download = async (b: ShelfBook) => {
    if (!catalogUrl || !b.opds_href)
      throw new Error('Choose a catalog before downloading this book.')
    const blob = await makeCatalogClient({
      url: catalogUrl,
      request: active.catalogRequest,
    }).acquire(b.opds_href)
    await imported(blob, b.opds_href, b.key)
  }
  const storeHighlight = async (h: Highlight) => {
    if (!replica) throw new Error('Storage is unavailable.')
    await replica.putHighlight(h)
  }
  const nav = (
    <header className="top">
      <a className="wordmark" href="#/library">
        Quire
      </a>
      <nav aria-label="Quire">
        {sections.map((name) => (
          <a
            key={name}
            href={href(name)}
            aria-current={
              route.view === name.toLowerCase() ? 'page' : undefined
            }
          >
            {t(name)}
          </a>
        ))}
      </nav>
    </header>
  )
  let content: ReactNode
  if (
    route.view === 'read' &&
    book &&
    epub &&
    epub.identity.content_hash === book.key &&
    replica
  ) {
    const hs = passages.filter((h) => h.document.content_hash === book.key)
    content = (
      <Reader
        key={book.key}
        epub={epub}
        readerStorage={replica}
        locale={lang}
        bookId={book.key}
        title={book.title}
        author={authors(book)}
        locator={current?.locator}
        highlights={hs}
        jumpTo={jump}
        onBack={() => details(book.key)}
        onProgress={(locator, percent) => {
          const now = new Date().toISOString()
          return replica
            .putProgress({
              ...current,
              document: bookDocument(book),
              locator,
              percent,
              finished_at:
                current?.finished_at ??
                (percent >= 0.98 && !current?.abandoned_at ? now : null),
              client_updated_at: now,
            })
        }}
        onCreateHighlight={async (input) => {
          const now = Date.now()
          await storeHighlight({
            ...input,
            document: bookDocument(book),
            id: crypto.randomUUID(),
            book_title: book.title,
            book_author: authors(book),
            created_at: now,
            updated_at: now,
          })
        }}
        onEditHighlight={async (id, patch) => {
          const h = hs.find((h) => h.id === id)
          if (h)
            await storeHighlight({
              ...h,
              ...patch,
              updated_at: Math.max(Date.now(), h.updated_at + 1),
            })
        }}
        onDeleteHighlight={async (id) => {
          const h = hs.find((h) => h.id === id)
          if (h) {
            const now = Math.max(Date.now(), h.updated_at + 1)
            await storeHighlight({ ...h, deleted_at: now, updated_at: now })
          }
        }}
      />
    )
  } else if (route.view === 'book' && book) {
    content = (
      <>
        <BookDetails
          book={book}
          progress={current}
          t={t}
          conflict={
            editionConflict && (
              <p role="status">
                {t('Another edition is using this identifier on the server.')}{' '}
                <button
                  disabled={working || !replica}
                  onClick={() =>
                    void run(async () => {
                      await replica?.keepLocalEdition(book.key)
                    })
                  }
                >
                  {t('Keep this edition on this device')}
                </button>
              </p>
            )
          }
          actions={
            <div className="actions">
              {book.hasDownload ? (
                <button className="primary" onClick={() => read(book.key)}>
                  {t('Read')}
                </button>
              ) : (
                <>
                  {book.opds_href && catalogUrl && (
                    <button
                      disabled={working}
                      onClick={() => void run(() => download(book))}
                    >
                      {t('Download from catalog')}
                    </button>
                  )}
                  <button onClick={() => choose(book.key)}>
                    {t('Open your copy')}
                  </button>
                </>
              )}
              {current?.finished_at || current?.abandoned_at ? (
                <button onClick={() => void run(() => saveStatus(book, ''))}>
                  {t('Resume reading')}
                </button>
              ) : (
                <>
                  <button
                    onClick={() => void run(() => saveStatus(book, 'finished'))}
                  >
                    {t('Mark finished')}
                  </button>
                  <button
                    onClick={() =>
                      void run(() => saveStatus(book, 'abandoned'))
                    }
                  >
                    {t('Mark abandoned')}
                  </button>
                </>
              )}
            </div>
          }
          storage={
            <details>
              <summary>{t('Storage')}</summary>
              <div className="actions">
                {book.hasDownload && (
                  <button
                    onClick={() =>
                      void run(async () => {
                        await replica?.removeDownload(book.key)
                      })
                    }
                  >
                    {t('Remove download')}
                  </button>
                )}
                <button
                  onClick={() => {
                    if (confirm(t('Remove from library') + '?'))
                      void run(async () => {
                        await replica?.removeBook(book.key)
                        go('library')
                      })
                  }}
                >
                  {t('Remove from library')}
                </button>
              </div>
            </details>
          }
        />

        {aiAvailable && (
          <Insights
            t={t}
            request={active.request}
            aiAvailable={aiAvailable}
            identity={{
              ...bookDocument(book),
              isbn: book.isbn,
              opds_href: book.opds_href
                ? catalogInsightIdentity(book.opds_href).opds_href
                : undefined,
            }}
            metadata={{
              ...book,
              author: authors(book),
              series_position: book.series_index,
            }}
          />
        )}
      </>
    )
  } else if (route.view === 'book' || route.view === 'read') {
    content = (
      <section>
        <a href="#/library">{t('Back to library')}</a>
        <p>
          {replica && !book
            ? 'This book is unavailable in this library.'
            : 'Opening book…'}
        </p>
        {book && !epub && notice && (
          <button onClick={() => choose(book.key)}>
            {t('Open your copy')}
          </button>
        )}
      </section>
    )
  } else if (route.view === 'highlights') {
    content = (
      <Passages
        t={t}
        report={report}
        books={snapshot.books}
        passages={passages}
        open={(book, h) =>
          book.hasDownload ? read(book.key, h) : details(book.key)
        }
      />
    )
  } else if (route.view === 'catalog') {
    content = (
      <>
        <h1>{t('Catalog')}</h1>
        <form
          className="tools"
          onSubmit={(e) => {
            e.preventDefault()
            void run(async () => {
              const v = new URL(catalogDraft || catalogUrl, location.origin)
              if (
                v.username ||
                v.password ||
                v.searchParams.has('token') ||
                !['https:', 'http:'].includes(v.protocol)
              )
                throw new Error('Use a catalog URL without credentials.')
              await replica?.setPreference('catalog', v.href)
              setCatalogOverride(v.href)
            })
          }}
        >
          <input
            aria-label={t('Catalog URL')}
            disabled={!!active.catalogUrl}
            value={catalogDraft}
            placeholder={catalogUrl || 'https://books.example.org/opds'}
            onChange={(e) => setCatalogDraft(e.target.value)}
          />
          <button disabled={!!active.catalogUrl}>{t('Open catalog')}</button>
        </form>
        {catalogUrl && (
          <Catalog
            key={catalogUrl + ':' + (active.catalogRevision ?? 0)}
            url={catalogUrl}
            request={active.catalogRequest}
            apiRequest={active.request}
            aiAvailable={aiAvailable}
            t={t}
            onImport={() => choose()}
            onAcquire={async (blob, p) => {
              await imported(blob, p.epubHref)
              setNotice(p.title + ' · ' + t('Downloaded'))
            }}
            signInCatalog={account.signInCatalog}
            signOutCatalog={account.signOutCatalog}
            catalogAuthenticated={active.catalogAuthenticated}
          />
        )}
      </>
    )
  } else if (route.view === 'discover') {
    content = (
      <>
        <h1>{t('Discover')}</h1>
        <Discovery
          t={t}
          syncAvailable={syncEnabled}
          request={active.request}
          aiAvailable={aiAvailable}
          onOpenBook={details}
          books={snapshot.books.map((b) => {
            const p = findProgress(b, snapshot.progress)
            return {
              id: b.key,
              title: b.title,
              author: authors(b),
              isbn: b.isbn,
              seriesName: b.series_name,
              seriesPosition: b.series_index,
              percent: p?.percent,
              status: p?.finished_at
                ? 'finished'
                : p?.abandoned_at
                  ? 'abandoned'
                  : undefined,
            }
          })}
        />
      </>
    )
  } else if (route.view === 'settings') {
    content = (
      <>
        <h1>{t('Settings')}</h1>
        <div className="settings-grid">
          <section className="panel">
            <h2>{t('Appearance')}</h2>
            <label>
              {t('Appearance')}
              <select value={theme} onChange={(e) => setTheme(e.target.value)}>
                {['System', 'Paper', 'Sage', 'Ink', 'Midnight'].map((s) => (
                  <option key={s} value={s}>
                    {t(s)}
                  </option>
                ))}
              </select>
            </label>
            <label>
              <input
                type="checkbox"
                checked={art === 'on'}
                onChange={(e) => setArt(e.target.checked ? 'on' : 'off')}
              />{' '}
              {t('Artwork')}
            </label>
            <label>
              {t('Language')}
              <select
                aria-label={t('Language')}
                value={lang}
                onChange={(e) => setLang(e.target.value)}
              >
                {Object.entries(languages).map(([key, v]) => (
                  <option key={key} value={key}>
                    {v}
                  </option>
                ))}
              </select>
            </label>
          </section>
          <OfflineSettings
            t={t}
            downloads={snapshot.books.filter((book) => book.hasDownload).length}
          />
          <AccountSettings
            account={account}
            pending={snapshot.pending}
            sync={sync}
            clear={clearNamespace}
            t={t}
          />
          {active.mode === 'connected' && syncEnabled && replica && (
            <GuestImport destination={replica} t={t} />
          )}
          {transport && (
            <p>
              {snapshot.pending} pending ·{' '}
              <button disabled={busy} onClick={() => void run(sync)}>
                {t(busy ? 'Syncing…' : 'Sync now')}
              </button>
            </p>
          )}
          {aiAvailable && (
            <AiSettings
              t={t}
              request={active.request}
              aiAvailable={aiAvailable}
            />
          )}
          <details>
            <summary>{t('What’s new')}</summary>
            <p>
              {t(
                'Read EPUBs, keep passages and continue from another device with Quire Web.',
              )}
            </p>
          </details>
          <a href={noticesUrl}>{t('Open-source licenses')}</a>
          <a href="https://github.com/vitofico/quire">{t('Source code')}</a>
        </div>
      </>
    )
  } else
    content = (
      <Shelf
        ready={!!replica && account.status !== 'loading'}
        t={t}
        data={snapshot}
        choose={() => choose()}
        read={read}
        details={details}
      />
    )
  return (
    <div className={art === 'on' ? 'artwork' : ''}>
      <a
        className="skip"
        href="#content"
        onClick={(e) => {
          e.preventDefault()
          main.current?.focus()
        }}
      >
        {t('Skip to content')}
      </a>
      {route.view !== 'read' && nav}
      <main
        className={
          (route.view === 'read' ? '' : 'workspace ') +
          (drag ? 'drop-zone' : '')
        }
        id="content"
        ref={main}
        tabIndex={-1}
        onDragOver={(e) => {
          if (e.dataTransfer.types.includes('Files')) {
            e.preventDefault()
            setDrag(true)
          }
        }}
        onDragLeave={() => setDrag(false)}
        onDrop={(e) => {
          e.preventDefault()
          setDrag(false)
          if (
            replica &&
            account.status !== 'loading' &&
            e.dataTransfer.files.length
          )
            void run(() => importFiles(Array.from(e.dataTransfer.files)))
        }}
      >
        {notice && (
          <p role="status" className="notice">
            {notice}
          </p>
        )}
        <input
          ref={file}
          type="file"
          disabled={!replica || account.status === 'loading'}
          hidden
          accept=".epub,application/epub+zip"
          multiple
          onChange={(e) => {
            const files = Array.from(e.target.files ?? [])
            e.target.value = ''
            if (files.length) void run(() => importFiles(files, target.current))
          }}
        />
        <Suspense fallback={<p aria-busy="true">Quire…</p>}>{content}</Suspense>
      </main>
    </div>
  )
}
