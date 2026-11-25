import {
  Component,
  lazy,
  Suspense,
  useCallback,
  useEffect,
  useMemo,
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
  type Replica,
  type ShelfBook,
} from './data'
import { openEpub, type Epub } from './books/epub'
import { makeCatalogClient } from './books/opds'
import {
  listSavedCatalogs,
  needsLogin,
  savedCatalogHome,
  savedCatalogRequest,
  type SavedCatalog,
} from './books/saved'
import { OpenCatalogs } from './books/OpenCatalogsPanel'
import { OfflineSettings } from './OfflineSettings'
import { BookDetails } from './BookDetails'
import { Passages } from './Passages'
import { GuestImport } from './GuestImport'
import { authors, findProgress, Shelf } from './Shelf'
import { useReplica } from './useReplica'
import noticesUrl from './legal/notices.txt?url'
import { translator, languages } from './language'
import { catalogInsightIdentity, promotePreview } from './discovery/api'
import { missingProvider, useAiState } from './discovery/aiState'
import { bookIdentity, fillDetails } from './details'
import { TidyDetails } from './TidyDetails'
import { ConversionList, useConversions } from './Conversions'
import { isPdf } from './convert'
import { allowsBasic, allowsNative } from './account/useAccount'
import { Welcome } from './Welcome'
import { Icon, type IconName } from './icons'
import { Mark } from './Mark'
import { Menu } from './Menu'
import { ask } from './ask'
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
const sectionIcons: Record<string, IconName> = {
  Library: 'library',
  Catalog: 'catalog',
  Highlights: 'highlights',
  Discover: 'discover',
  Settings: 'settings',
}
const themes = ['System', 'Paper', 'Sage', 'Ink', 'Midnight']
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
/** The server's own catalog, named for its chip. */
function catalogLabel(url: string, t: (key: string) => string) {
  try {
    const parsed = new URL(url, location.origin)
    return parsed.origin === location.origin ? t('This server') : parsed.host
  } catch {
    return t('Catalog')
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
function usePreferences() {
  const [lang, setLang] = useOption(
    'language',
    navigator.language.split('-')[0] in languages
      ? navigator.language.split('-')[0]
      : 'en',
  )
  const [theme, setTheme] = useOption('theme', 'System'),
    [art, setArt] = useOption('art', 'on')
  useEffect(() => {
    document.documentElement.lang = lang
  }, [lang])
  useEffect(() => {
    const media = matchMedia('(prefers-color-scheme: dark)')
    const update = () => {
      const root = document.documentElement
      root.dataset.theme =
        theme === 'System' ? (media.matches ? 'ink' : 'paper') : theme.toLowerCase()
      // Browser chrome follows the page, not the system scheme.
      document
        .querySelector('meta[name=theme-color]')
        ?.setAttribute('content', getComputedStyle(root).getPropertyValue('--paper').trim())
    }
    update()
    media.addEventListener('change', update)
    return () => media.removeEventListener('change', update)
  }, [theme])
  return { lang, setLang, theme, setTheme, art, setArt, t: translator(lang) }
}
type Preferences = ReturnType<typeof usePreferences>
export default function App() {
  const account = useAccount()
  const prefs = usePreferences()
  const [guest, setGuest] = useOption('guest', '')
  const { active, bootstrap, status } = account
  const signInOffered = allowsBasic(bootstrap) || allowsNative(bootstrap)
  const guestAllowed = bootstrap?.guestReading !== false
  // Wait for the server's answer so a signed-in reader never sees the front door flash by.
  if (status === 'loading' && active.mode !== 'connected')
    return <div className="splash" aria-busy="true" />
  if (signInOffered && active.mode !== 'connected' && !(guestAllowed && guest === 'yes'))
    return (
      <ErrorBoundary>
        <Welcome
          account={account}
          t={prefs.t}
          readAsGuest={guestAllowed ? () => setGuest('yes') : undefined}
        />
      </ErrorBoundary>
    )
  return (
    <ErrorBoundary>
      <Workspace
        key={active.namespace}
        account={account}
        prefs={prefs}
        signIn={signInOffered ? () => setGuest('') : undefined}
      />
    </ErrorBoundary>
  )
}
function Workspace({
  account,
  prefs,
  signIn,
}: {
  account: AccountState
  prefs: Preferences
  signIn?: () => void
}) {
  const { active, bootstrap } = account
  const [route, setRoute] = useState(getRoute),
    [notice, setNotice] = useState(''),
    [working, setWorking] = useState(false)
  const previousRoute = useRef(route)
  const main = useRef<HTMLElement>(null)
  const { lang, setLang, theme, setTheme, art, setArt, t } = prefs
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
  // Catalogs saved to the Quire account. The browser cannot reach them
  // itself, so it browses them only when the server fetches for it.
  const savedListed =
    active.mode === 'connected' &&
    active.identity?.backend === 'native' &&
    bootstrap?.catalogSync === true &&
    !!active.request
  const savedBrowsable = savedListed && bootstrap?.catalogProxy === true
  const [saved, setSaved] = useState<SavedCatalog[]>([]),
    [chosenCatalog, setChosenCatalog] = useState('')
  const viewingCatalog = route.view === 'catalog'
  useEffect(() => {
    if (!savedListed || !active.request) {
      setSaved([])
      return
    }
    // Asked again each time the catalog view opens, so a catalog added on a
    // phone shows up. A failed refresh keeps the list already shown.
    const stop = new AbortController()
    listSavedCatalogs(active.request, stop.signal).then(setSaved, () => undefined)
    return () => stop.abort()
  }, [savedListed, active.request, viewingCatalog])
  const activeSaved = savedBrowsable
    ? (saved.find((c) => c.id === chosenCatalog) ?? (catalogUrl ? undefined : saved[0]))
    : undefined
  const savedRequest = useMemo(
    () =>
      activeSaved && active.request
        ? savedCatalogRequest(active.request, activeSaved)
        : undefined,
    // A refreshed list is new objects: only a different catalog needs a new client.
    [active.request, activeSaved?.id, activeSaved?.host],
  )
  // On a phone the chips scroll sideways: keep the chosen one in sight.
  const switcherRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    switcherRef.current
      ?.querySelector('[aria-pressed="true"]')
      ?.scrollIntoView({ block: 'nearest', inline: 'nearest' })
  }, [activeSaved?.id, viewingCatalog])
  const chooseCatalog = (id: string) => {
    setChosenCatalog(id)
    replica?.setPreference('savedCatalog', id).catch(() => undefined)
  }
  /** The catalog to download `b` from again: the saved one it came from, or the server's own. */
  const catalogFor = (b: LocalBook) => {
    if (!b.opds_href) return
    try {
      if (b.catalog_id) {
        const c = saved.find((item) => item.id === b.catalog_id)
        return c && savedBrowsable && !c.needs_login && active.request
          ? makeCatalogClient({
              url: savedCatalogHome(c),
              request: savedCatalogRequest(active.request, c),
            })
          : undefined
      }
      return catalogUrl
        ? makeCatalogClient({ url: catalogUrl, request: active.catalogRequest })
        : undefined
    } catch {
      return undefined
    }
  }
  const [drag, setDrag] = useState(false)
  // Books synced from another device carry no cover. Ask the catalog once per visit and keep the answer.
  const coverless = useRef(new Set<string>())
  const [catalogChecked, setCatalogChecked] = useState(0)
  const awaitsCatalogCover = (b: ShelfBook) =>
    !!catalogUrl &&
    !!b.opds_href &&
    !b.catalog_id &&
    !(b.cover instanceof Blob) &&
    !coverless.current.has(b.key)
  useEffect(() => {
    const missing = snapshot.books.filter(awaitsCatalogCover)
    if (!replica || !missing.length) return
    const stop = new AbortController()
    void (async () => {
      const catalog = makeCatalogClient({ url: catalogUrl, request: active.catalogRequest })
      for (const b of missing) {
        try {
          const cover = await catalog.coverOf(b.title, b.opds_href!, stop.signal)
          if (stop.signal.aborted) return
          coverless.current.add(b.key)
          if (cover) await replica.setCatalogCover(b.key, cover)
        } catch {
          // Catalog offline or not signed in: try again when that changes.
          return
        }
      }
      // Books the catalog has no cover for can now be looked up.
      setCatalogChecked((n) => n + 1)
    })()
    return () => stop.abort()
  }, [replica, snapshot.books, catalogUrl, active.catalogRequest])
  const file = useRef<HTMLInputElement>(null),
    target = useRef<string | undefined>(undefined),
    alive = useRef(true)
  const book = snapshot.books.find((b) => b.key === route.id)
  const current = book && findProgress(book, snapshot.progress)
  const editionConflict = book?.editionConflict
  const aiAvailable = bootstrap?.aiAvailable === true && !!active.request
  // Finding and tidying details needs the server's feature and the reader's AI opt-in.
  const { settings: ai } = useAiState(
    active.request,
    aiAvailable && active.capabilities?.book_details !== false,
  )
  const detailsRequest =
    ai?.config.configured &&
    ai.config.details_supported &&
    ai.preferences.ai_enabled &&
    !missingProvider(ai.config, ai.preferences)
      ? active.request
      : undefined
  const detailsFor =
    route.view === 'book' ? (book ? [book] : []) : route.view === 'library' ? snapshot.books : []
  useEffect(() => {
    if (!replica || !detailsRequest) return
    // Wait for a catalog's own cover before asking for one to be found or drawn.
    const fill = () =>
      fillDetails(replica, detailsRequest, detailsFor.filter((b) => !awaitsCatalogCover(b)), route.view === 'book')
    const stop = fill()
    window.addEventListener('online', fill)
    return () => {
      stop()
      window.removeEventListener('online', fill)
    }
  }, [replica, detailsRequest, snapshot.books, route.view, route.id, catalogChecked])
  const loadCover = useCallback(
    async (key: string) => replica?.getFoundCover(key),
    [replica],
  )
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
  const section =
    route.view === 'book' || route.view === 'read'
      ? 'Library'
      : (sections.find((name) => name.toLowerCase() === route.view) ??
        'Library')
  const pageTitle =
    (route.view === 'book' || route.view === 'read') && book
      ? book.title
      : t(section)
  useEffect(() => {
    document.title = 'Quire · ' + pageTitle
  }, [pageTitle])
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
    let live = true
    if (replica)
      replica
        .getPreference<string>('catalog')
        .then((v) => {
          if (live && v) setCatalogOverride(v)
        })
        .catch((e) => report(String(e)))
    replica
      ?.getPreference<string>('savedCatalog')
      .then((v) => {
        if (live && typeof v === 'string') setChosenCatalog(v)
      })
      .catch(() => undefined)
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
  // A book dropped in while the browser is still opening its storage waits for it.
  const awaitingStorage = useRef<((replica: Replica) => void)[]>([])
  useEffect(() => {
    if (replica) awaitingStorage.current.splice(0).forEach((resume) => resume(replica))
  }, [replica])
  const storage = () =>
    replica ?? new Promise<Replica>((resume) => awaitingStorage.current.push(resume))
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
    catalogCover?: Blob,
    catalogId: string | null = null,
    /** On another edition with this identifier: ask, keep this copy in this browser, or refuse. */
    conflict: 'ask' | 'keep' | 'refuse' = 'ask',
  ) {
    const replica = await storage()
    const parsed = await openEpub(blob),
      m = parsed.metadata
    const cover = m.coverHref && parsed.bytes(m.coverHref)
    const known = snapshot.books.find((b) => b.key === parsed.identity.content_hash)
    const fetched = publicAcquisition(acquisition)
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
      opds_href: fetched ?? known?.opds_href,
      catalog_id: fetched ? catalogId : known?.catalog_id,
      cover: cover
        ? new Blob([new Uint8Array(cover)], {
            type: parsed.mediaType(m.coverHref!) ?? 'image/jpeg',
          })
        : catalogCover,
    }
    try {
      await replica.putBook(record, blob, { matchKey: match })
    } catch (e) {
      if (
        !(e instanceof EditionConflict) ||
        match ||
        conflict === 'refuse' ||
        (conflict === 'ask' && !(await ask(
          t('Another edition has this identifier. Keep this copy only in this browser?'),
          { confirm: t('Keep in this browser'), cancel: t('Cancel') },
        )))
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
  const importLatest = useRef(imported)
  importLatest.current = imported
  const conversions = useConversions({
    namespace: active.namespace,
    store: replica,
    request: active.mode === 'connected' ? active.request : undefined,
    capability: active.capabilities?.pdf_conversion,
    // A conversion finishes in the background: it never asks, the list offers the choice.
    importEpub: (epub, keepLocal) =>
      importLatest.current(epub, null, undefined, undefined, null, keepLocal ? 'keep' : 'refuse'),
  })
  async function importFiles(files: File[], match?: string) {
    const pdfs = files.filter(isPdf), epubs = files.filter((picked) => !isPdf(picked))
    if (pdfs.length && match) {
      report('Choose an EPUB to replace this edition.')
    } else if (pdfs.length) {
      // A PDF becomes an EPUB on the server first: the reader confirms it in the conversions list.
      const refusal = await conversions.choose(pdfs, await storage())
      if (refusal) report(refusal)
      // The list is on the shelf.
      else if (!epubs.length && alive.current) go('library')
    }
    let key = ''
    for (const picked of epubs) {
      key = await imported(picked, null, match)
    }
    if (alive.current && key)
      go(
        epubs.length === 1 ? 'book' : 'library',
        epubs.length === 1 ? key : undefined,
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
    const catalog = catalogFor(b)
    if (!catalog || !b.opds_href)
      throw new Error('Choose a catalog before downloading this book.')
    const blob = await catalog.acquire(b.opds_href)
    await imported(blob, b.opds_href, b.key, undefined, b.catalog_id ?? null)
  }
  const storeHighlight = async (h: Highlight) => {
    if (!replica) throw new Error('Storage is unavailable.')
    await replica.putHighlight(h)
  }
  const connected = active.mode === 'connected'
  const accountChip = connected ? (
    <a className="account-chip" href="#/settings/account">
      <Icon name="cloud" />
      <span>
        <strong>
          {active.identity?.id} · {active.identity?.backend === 'native' ? 'Quire' : 'Calibre-Web'}
        </strong>
        <small>
          {active.offline
            ? t('Offline')
            : !syncEnabled
              ? t('Signed in')
              : busy
                ? t('Syncing…')
                : snapshot.pending
                  ? t(snapshot.pending === 1 ? '{count} pending change.' : '{count} pending changes.').replace(
                      '{count}',
                      String(snapshot.pending),
                    )
                  : t('Up to date')}
        </small>
      </span>
    </a>
  ) : (
    <div className="account-chip">
      <Icon name="device" />
      <span>
        <strong>{t('On this device only')}</strong>
        {signIn ? (
          <button type="button" className="link" onClick={signIn}>
            {t('Sign in to sync')}
          </button>
        ) : (
          <small>{t('Books stay in this browser.')}</small>
        )}
      </span>
    </div>
  )
  const nav = (
    <header className="rail">
      <a className="brand" href="#/library">
        <Mark />
        <span>Quire</span>
      </a>
      <nav aria-label="Quire">
        {sections.map((name) => (
          <a
            key={name}
            href={href(name)}
            aria-current={section === name ? 'page' : undefined}
          >
            <Icon name={sectionIcons[name]} />
            <span>{t(name)}</span>
          </a>
        ))}
      </nav>
      {accountChip}
    </header>
  )
  const back = (
    <a className="back" href="#/library">
      <Icon name="back" />
      {t('Back to library')}
    </a>
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
          loadCover={loadCover}
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
          back={back}
          tidy={<TidyDetails key={book.key} book={book} store={replica} request={detailsRequest} t={t} />}
          actions={
            <div className="actions">
              {book.hasDownload ? (
                <button className="primary large" onClick={() => read(book.key)}>
                  {t('Read')}
                </button>
              ) : (
                <>
                  {catalogFor(book) && (
                    <button
                      className="primary large"
                      disabled={working}
                      onClick={() => void run(() => download(book))}
                    >
                      {t('Download from catalog')}
                    </button>
                  )}
                  <button
                    className={catalogFor(book) ? undefined : 'primary large'}
                    onClick={() => choose(book.key)}
                  >
                    {t('Open your copy')}
                  </button>
                </>
              )}
              {current?.finished_at || current?.abandoned_at ? (
                <button onClick={() => void run(() => saveStatus(book, ''))}>
                  {t('Resume reading')}
                </button>
              ) : (
                <button
                  onClick={() => void run(() => saveStatus(book, 'finished'))}
                >
                  {t('Mark finished')}
                </button>
              )}
              <Menu label={t('More actions')}>
                {!current?.finished_at && !current?.abandoned_at && (
                  <button onClick={() => void run(() => saveStatus(book, 'abandoned'))}>
                    {t('Mark abandoned')}
                  </button>
                )}
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
                  className="danger"
                  onClick={() =>
                    void run(async () => {
                      if (
                        !(await ask(
                          t('Remove “{title}” and its reading data from this library?').replace(
                            '{title}',
                            book.title,
                          ),
                          { confirm: t('Remove from library'), cancel: t('Cancel'), destructive: true },
                        ))
                      )
                        return
                      await replica?.removeBook(book.key)
                      go('library')
                    })
                  }
                >
                  {t('Remove from library')}
                </button>
              </Menu>
            </div>
          }
        />

        {aiAvailable && (
          <Insights
            t={t}
            request={active.request}
            aiAvailable={aiAvailable}
            identity={bookIdentity(book)}
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
        {back}
        <p>
          {replica && !book
            ? t('This book is unavailable in this library.')
            : t('Opening book…')}
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
    const source = (
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
            setCatalogDraft('')
            chooseCatalog('')
          })
        }}
      >
        <input
          aria-label={t('Catalog URL')}
          value={catalogDraft}
          placeholder={catalogUrl || 'https://books.example.org/opds'}
          onChange={(e) => setCatalogDraft(e.target.value)}
        />
        <button disabled={!catalogDraft.trim()}>{t('Open catalog')}</button>
      </form>
    )
    const choices = [
      ...(catalogUrl ? [{ id: '', name: catalogLabel(catalogUrl, t) }] : []),
      ...(savedBrowsable ? saved : []),
    ]
    const switcher = choices.length > 1 && (
      <div className="chips" role="group" aria-label={t('Catalogs')} ref={switcherRef}>
        {choices.map((c) => (
          <button
            key={c.id}
            className="chip"
            aria-pressed={(activeSaved?.id ?? '') === c.id}
            onClick={() => chooseCatalog(c.id)}
          >
            {c.name}
          </button>
        ))}
      </div>
    )
    const savedHint =
      savedListed &&
      (!saved.length ? (
        <p className="hint">{t('Catalogs you add in the Quire app on Android appear here.')}</p>
      ) : !savedBrowsable ? (
        <p className="hint">
          {t('Your saved catalogs open in the Quire app on Android. This server does not open them in the browser.')}
        </p>
      ) : null)
    const open = savedBrowsable && active.request && (
      <OpenCatalogs
        language={lang}
        saved={saved}
        request={active.request}
        t={t}
        onAdded={(c) => {
          setSaved((list) => [...list, c])
          chooseCatalog(c.id)
        }}
      />
    )
    const otherSource = !active.catalogUrl && (
      <details className="catalog-source">
        <summary>{t('Use a different catalog')}</summary>
        {source}
      </details>
    )
    content = activeSaved ? (
      <>
        {activeSaved.needs_login ? (
          <section className="catalog">
            <h1>{activeSaved.name}</h1>
            {switcher}
            <p role="alert">{t(needsLogin)}</p>
          </section>
        ) : (
          <Catalog
            key={'saved:' + activeSaved.id + ':' + activeSaved.updated_at}
            url={savedCatalogHome(activeSaved)}
            request={savedRequest}
            apiRequest={active.request}
            aiAvailable={aiAvailable}
            t={t}
            onImport={() => choose()}
            onAcquire={(blob, p, cover) =>
              imported(blob, p.epubHref, undefined, cover, activeSaved.id)
            }
          >
            {switcher}
          </Catalog>
        )}
        {otherSource}
        {open}
      </>
    ) : catalogUrl ? (
      <>
        <Catalog
          key={catalogUrl + ':' + (active.catalogRevision ?? 0)}
          url={catalogUrl}
          request={active.catalogRequest}
          apiRequest={active.request}
          aiAvailable={aiAvailable}
          t={t}
          onImport={() => choose()}
          onAcquire={(blob, p, cover) =>
            imported(blob, p.epubHref, undefined, cover)
          }
          signInCatalog={account.signInCatalog}
          signOutCatalog={account.signOutCatalog}
          catalogAuthenticated={active.catalogAuthenticated}
        >
          {switcher}
        </Catalog>
        {otherSource}
        {savedHint}
        {open}
      </>
    ) : (
      <>
        <h1>{t('Catalog')}</h1>
        <section className="panel narrow">
          <h2>{t('Connect a catalog')}</h2>
          <p className="hint">
            {t('Enter the address of an OPDS catalog, such as Calibre-Web’s /opds.')}
          </p>
          {source}
        </section>
        {savedHint}
        {open}
      </>
    )
  } else if (route.view === 'discover') {
    content = (
      <>
        <h1>{t('Discover')}</h1>
        {!connected && bootstrap?.aiAvailable && signIn && (
          <p className="notice">
            {t('Sign in to get reading insights and check books against your library.')}{' '}
            <button type="button" className="link" onClick={signIn}>
              {t('Sign in')}
            </button>
          </p>
        )}
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
    const tabs = ['Appearance', 'Account', 'Storage', ...(aiAvailable ? ['Insights'] : []), 'About']
    const tab = tabs.find((name) => name.toLowerCase() === route.id) ?? 'Appearance'
    content = (
      <div className="narrow">
        <h1>{t('Settings')}</h1>
        <nav className="tabs" aria-label={t('Settings sections')}>
          {tabs.map((name) => (
            <a
              key={name}
              href={href('Settings', name.toLowerCase())}
              aria-current={tab === name ? 'page' : undefined}
            >
              {t(name)}
            </a>
          ))}
        </nav>
        {tab === 'Appearance' && (
          <section className="panel">
            <fieldset className="swatches">
              <legend>{t('Theme')}</legend>
              {themes.map((name) => (
                <label key={name} className="swatch" data-swatch={name.toLowerCase()}>
                  <input
                    type="radio"
                    name="theme"
                    value={name}
                    checked={theme === name}
                    onChange={() => setTheme(name)}
                  />
                  <span className="swatch-preview" aria-hidden="true">
                    <i />
                    <i />
                  </span>
                  {t(name)}
                </label>
              ))}
            </fieldset>
            <label className="field">
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
            <label className="check">
              <input
                type="checkbox"
                checked={art === 'on'}
                onChange={(e) => setArt(e.target.checked ? 'on' : 'off')}
              />
              {t('Artwork')}
            </label>
          </section>
        )}
        {tab === 'Account' && (
          <div className="panel">
            <AccountSettings
              account={account}
              pending={snapshot.pending}
              sync={sync}
              clear={clearNamespace}
              t={t}
            />
            {transport && (
              <p className="actions">
                <button disabled={busy} onClick={() => void run(sync)}>
                  {t(busy ? 'Syncing…' : 'Sync now')}
                </button>
                <span className="muted">
                  {snapshot.pending} {t('pending')}
                </span>
              </p>
            )}
            {active.mode === 'connected' && syncEnabled && replica && (
              <GuestImport destination={replica} t={t} />
            )}
          </div>
        )}
        {tab === 'Storage' && (
          <OfflineSettings
            t={t}
            downloads={snapshot.books.filter((book) => book.hasDownload).length}
          />
        )}
        {tab === 'Insights' && aiAvailable && (
          <AiSettings t={t} request={active.request} aiAvailable={aiAvailable} />
        )}
        {tab === 'About' && (
          <section className="panel">
            <h2>{t('What’s new')}</h2>
            <p>
              {t(
                'Read EPUBs, keep passages and continue from another device with Quire Web.',
              )}
            </p>
            <p className="actions">
              <a href={noticesUrl}>{t('Open-source licenses')}</a>
              <a href="https://github.com/vitofico/quire">{t('Source code')}</a>
            </p>
          </section>
        )}
      </div>
    )
  } else
    content = (
      <>
        {active.mode !== 'connected' && account.retained.length > 0 && (
          <p className="notice">
            {t('Your library for {account} is locked on this device.').replace(
              '{account}',
              account.retained.map(({ identity }) => identity.id).join(', '),
            )}{' '}
            <a href="#/settings">{t('Sign in again')}</a>
          </p>
        )}
        <Shelf
          ready={!!replica && account.status !== 'loading'}
          local={!connected}
          art={art === 'on'}
          t={t}
          data={snapshot}
          loadCover={loadCover}
          choose={() => choose()}
          read={read}
          details={details}
          pdf={conversions.offered}
          conversions={
            <ConversionList
              items={conversions.items}
              queue={conversions.queue}
              limits={conversions.limits}
              pick={() => choose()}
              t={t}
            />
          }
        />
      </>
    )
  return (
    <div
      className={
        route.view === 'read' ? '' : 'shell' + (art === 'on' ? ' artwork' : '')
      }
    >
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
            {t(notice.replace(/^Error: /, ''))}
          </p>
        )}
        <input
          ref={file}
          type="file"
          disabled={!replica || account.status === 'loading'}
          hidden
          accept={'.epub,application/epub+zip' + (conversions.offered ? ',.pdf,application/pdf' : '')}
          multiple
          onChange={(e) => {
            const files = Array.from(e.target.files ?? [])
            e.target.value = ''
            if (files.length) void run(() => importFiles(files, target.current))
          }}
        />
        <Suspense
          fallback={
            <p aria-busy="true" className="muted">
              {t('Loading…')}
            </p>
          }
        >{content}</Suspense>
      </main>
    </div>
  )
}
