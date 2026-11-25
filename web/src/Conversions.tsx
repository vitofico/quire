import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { type Conversion, type ConversionStore, type ConvertCapabilities, Conversions, convertCapabilities } from './convert'
import type { DiscoveryRequest } from './discovery/api'

type T = (key: string) => string

/** Shown, translated, for each reason a conversion failed or waits. */
const problems: Record<string, string> = {
  encrypted: 'This PDF is encrypted or password-protected, so it cannot be converted.',
  too_many_pages: 'This PDF has more pages than this server converts.',
  unreadable_pdf: 'This file could not be read as a PDF.',
  unsupported: 'This kind of PDF cannot be converted yet.',
  conversion_failed: 'The conversion did not finish.',
  timed_out: 'The conversion took too long and was stopped.',
  worker_error: 'The converter stopped unexpectedly.',
  worker_lost: 'The converter stopped before finishing, twice.',
  invalid_result: 'The converted book did not pass the server’s checks, so nothing was kept.',
  hosting_subscription_required: 'Your plan on this server does not include converting PDFs.',
  idempotency_conflict: 'The server mixed this PDF up with another one.',
  source_too_large: 'This PDF is larger than this server converts.',
  unsupported_media_type: 'The server did not accept this file as a PDF.',
  not_a_pdf: 'This file is not a PDF.',
  size_mismatch: 'The file changed while it was being sent. Choose it again.',
  checksum_mismatch: 'The file changed while it was being sent. Choose it again.',
  upload_timed_out: 'The upload stalled. Quire sends it again shortly.',
  upload_in_progress: 'An earlier upload of this PDF is still arriving. Quire checks again shortly.',
  too_many_active_jobs: 'Other conversions are under way. This one starts when one finishes.',
  daily_limit_reached: 'You reached today’s conversion limit. This one starts at {time}.',
  converter_unavailable: 'The converter is not running right now. Quire tries again in a minute.',
  result_gone: 'The converted book is no longer on the server.',
  expired: 'This conversion expired before it finished.',
  job_not_found: 'This conversion is no longer on the server.',
  not_offered: 'This server no longer converts PDFs.',
  import_failed: 'The converted book could not be added to your library.',
  network: 'Quire could not reach the server. It tries again shortly.',
  file_unreadable: 'Quire could not read this file. Choose it again.',
  upload_failed: 'The upload kept failing. Try again when the connection is steady.',
  download_failed: 'The converted book could not be downloaded. Try again when the connection is steady.',
  edition_conflict: 'Another edition in your library has this identifier. You can keep this copy only in this browser.',
}
const fallback = 'The server refused this conversion.'

function fileSize(bytes: number) {
  const mb = bytes / 1_048_576
  return mb >= 1 ? `${new Intl.NumberFormat(undefined, { maximumFractionDigits: 1 }).format(mb)} MB`
    : `${Math.max(1, Math.round(bytes / 1024))} KB`
}

function problemText(item: Conversion, t: T) {
  const known = item.problem && problems[item.problem]
  const text = t(known || fallback)
  return item.problem === 'daily_limit_reached' && item.retryAt
    ? text.replace('{time}', new Date(item.retryAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }))
    : text
}

/** What the stage means, in a sentence. */
function stageText(item: Conversion, t: T, converterDown: boolean) {
  switch (item.stage) {
    case 'confirm': return t('Converted on your server, nothing is sent elsewhere.')
    case 'preparing': return t('Preparing…')
    case 'uploading': return t('Uploading…')
    case 'reupload': return t('Choose the file again to finish the upload.')
    case 'waiting': return problemText(item, t)
    case 'queued': return t(converterDown ? 'Waiting for the converter to start.' : 'Waiting to convert.')
    case 'converting': return t('Converting…')
    case 'importing': return t('Adding to your library…')
    case 'done': return t('Added to your library.')
    case 'review': return t('Added to your library. Some pages need a look.')
    case 'failed': return problemText(item, t)
  }
}

/** The stage and, while it carries on, what is in the way: what the live region says. */
function newsText(item: Conversion, t: T, converterDown: boolean) {
  const hint = item.problem && inFlight(item) && item.stage !== 'waiting' ? ` ${problemText(item, t)}` : ''
  return `${item.name}: ${stageText(item, t, converterDown)}${hint}`
}

const inFlight = (item: Conversion) => ['preparing', 'uploading', 'reupload', 'waiting', 'queued', 'converting'].includes(item.stage)

type Options = {
  /** The account's namespace: its tabs share one list. */
  namespace: string
  store: ConversionStore | null
  request?: DiscoveryRequest
  /** The session's `pdf_conversion`; unknown for Calibre-Web logins, so the server is asked. */
  capability?: boolean
  importEpub: (epub: Blob, keepLocal: boolean) => Promise<string>
}

/** The reader's conversions and the server's limits, for the shelf. */
export function useConversions({ namespace, store, request, capability, importEpub }: Options) {
  const [items, setItems] = useState<Conversion[]>([])
  const [limits, setLimits] = useState<ConvertCapabilities | null>()
  const queue = useRef<Conversions | undefined>(undefined)
  const latest = useRef({ request, importEpub })
  latest.current = { request, importEpub }
  const asks = !!request && capability !== false

  useEffect(() => {
    if (!asks) {
      setLimits(null)
      return
    }
    // Asked again when the page comes back online or into view, until it answers.
    let live = true, known = false
    const check = () => {
      if (known || document.visibilityState === 'hidden') return
      convertCapabilities(latest.current.request!).then((value) => {
        if (!live) return
        known = true
        setLimits(value ?? null)
      }, () => undefined)
    }
    check()
    window.addEventListener('online', check)
    document.addEventListener('visibilitychange', check)
    return () => {
      live = false
      window.removeEventListener('online', check)
      document.removeEventListener('visibilitychange', check)
    }
  }, [asks])

  const ensure = useCallback((target: ConversionStore) => {
    if (!queue.current) {
      const name = `quire-conversions:${namespace}`
      const made = new Conversions({
        store: target,
        request: (path, init) => latest.current.request!(path, init),
        importEpub: (epub, keepLocal) => latest.current.importEpub(epub, keepLocal),
        name,
        locks: navigator.locks,
        channel: typeof BroadcastChannel === 'function' ? new BroadcastChannel(name) : undefined,
        hidden: () => document.visibilityState === 'hidden',
        onVisibility: (listener) => {
          document.addEventListener('visibilitychange', listener)
          return () => document.removeEventListener('visibilitychange', listener)
        },
      })
      made.subscribe(() => setItems(made.items))
      queue.current = made
    }
    return queue.current
  }, [namespace])

  // Conversions saved before a reload carry on once the library is open.
  useEffect(() => {
    if (store && asks) ensure(store)
  }, [store, asks, ensure])
  useEffect(() => () => {
    queue.current?.dispose()
    queue.current = undefined
  }, [])

  /** Offers `files` for converting; answers why not, when this server cannot. */
  const choose = useCallback(async (files: File[], target: ConversionStore): Promise<string | undefined> => {
    if (!latest.current.request) return 'Sign in to a Quire server that converts PDFs to add a PDF.'
    if (!asks) return 'This server does not convert PDFs. Add an EPUB instead.'
    let fresh: ConvertCapabilities | undefined
    try {
      fresh = await convertCapabilities(latest.current.request)
    } catch {
      return 'Quire could not reach the server. Try again.'
    }
    setLimits(fresh ?? null)
    if (!fresh) return 'This server does not convert PDFs. Add an EPUB instead.'
    if (!fresh.available) return 'The PDF converter on this server is not running right now. Try again later.'
    ensure(target).offer(files, fresh.max_bytes)
  }, [asks, ensure])

  return { items, limits, offered: !!limits, choose, queue: queue.current }
}

type ListProps = {
  items: Conversion[]
  queue?: Conversions
  limits?: ConvertCapabilities | null
  /** Opens the file picker, for a PDF needed again. */
  pick: () => void
  t: T
}

/** The conversions in progress or just finished, next to the shelf's add control. */
export function ConversionList({ items: all, queue, limits, pick, t }: ListProps) {
  const section = useRef<HTMLElement>(null)
  /** The row whose button was used: focus stays with it, or the next row, should the button go. */
  const acted = useRef<{ id: string; index: number } | null>(null)
  const items = all.filter((item) => !item.dismissed)
  useLayoutEffect(() => {
    const last = acted.current, root = section.current
    if (!last || !root) return
    const active = document.activeElement
    if (active && active !== document.body) {
      // Still on the button, or the reader moved on.
      if (!root.contains(active)) acted.current = null
      return
    }
    acted.current = null
    const rows = [...root.querySelectorAll<HTMLElement>('li.conversion')]
    const target = rows.find((row) => row.dataset.id === last.id) ?? rows[Math.min(last.index, rows.length - 1)]
      ?? root.closest<HTMLElement>('main')
    target?.focus()
  })
  if (!queue && !limits) return null
  const converterDown = limits?.available === false
  const news = queue?.announcement && items.find((item) => item.id === queue.announcement!.id)
  const act = (item: Conversion, action: (id: string) => Promise<void>) => () => {
    acted.current = { id: item.id, index: items.indexOf(item) }
    void action.call(queue, item.id)
  }
  return (
    <section className="conversions" aria-labelledby={items.length ? 'conversions-title' : undefined} ref={section}>
      {/* Mounted before the first conversion, so its first change is heard. */}
      <p role="status" className="visually-hidden">{news && newsText(news, t, converterDown)}</p>
      {!!items.length && queue && <>
        <h2 className="eyebrow" id="conversions-title">{t('PDF conversions')}</h2>
        <ul>
          {items.map((item) => {
            const name = `conversion-${item.id}`
            const busy = item.stage === 'preparing' || item.stage === 'uploading' || item.stage === 'converting' || item.stage === 'importing'
            return (
              <li key={item.id} className={'conversion ' + item.stage} aria-labelledby={name} aria-busy={busy || undefined}
                tabIndex={-1} data-id={item.id}>
                <div className="conversion-head">
                  <strong id={name}>{item.name}</strong>
                  <small className="muted">{fileSize(item.bytes)}</small>
                </div>
                {item.stage === 'confirm' && item.tooLarge ? (
                  <p>
                    {t('This PDF is {size}. This server converts PDFs up to {max}.')
                      .replace('{size}', fileSize(item.bytes)).replace('{max}', fileSize(limits?.max_bytes ?? 0))}
                  </p>
                ) : (
                  <p>{stageText(item, t, converterDown)}</p>
                )}
                {item.stage === 'confirm' && !item.tooLarge && limits && (
                  <p className="hint">
                    {t('This server converts PDFs of up to {pages} pages.').replace('{pages}', String(limits.max_pages))}
                  </p>
                )}
                {item.problem && inFlight(item) && item.stage !== 'waiting' && <p className="hint">{problemText(item, t)}</p>}
                {item.detail && <p className="hint conversion-detail">{item.detail}</p>}
                {item.stage === 'review' && (
                  <>
                    {item.reason && <p className="hint conversion-detail">{item.reason}</p>}
                    {!!item.pages?.length && (
                      <ul className="pages" aria-label={t('Pages to check')}>
                        {item.pages.map((page) => (
                          <li key={page.page}>
                            {t('Page {page}').replace('{page}', String(page.page))}
                            {page.label && page.label !== String(page.page) ? ` (${page.label})` : ''}
                          </li>
                        ))}
                      </ul>
                    )}
                  </>
                )}
                <div className="actions">
                  {item.stage === 'confirm' && !item.tooLarge && (
                    <button className="primary" aria-describedby={name} onClick={act(item, queue.start)}>
                      {t('Convert to EPUB')}
                    </button>
                  )}
                  {item.stage === 'reupload' && (
                    <button className="primary" aria-describedby={name} onClick={pick}>{t('Choose the file again')}</button>
                  )}
                  {(item.stage === 'done' || item.stage === 'review') && item.book && (
                    <a className="button" href={'#/book/' + encodeURIComponent(item.book)} aria-describedby={name}>{t('Open book')}</a>
                  )}
                  {item.stage === 'failed' && item.retryable && (
                    <button aria-describedby={name} onClick={act(item, queue.retry)}>
                      {t(item.problem === 'edition_conflict' ? 'Keep in this browser' : 'Try again')}
                    </button>
                  )}
                  {(item.stage === 'confirm' || inFlight(item)) && (
                    <button aria-describedby={name} onClick={act(item, queue.cancel)}>
                      {t(item.stage === 'confirm' && item.tooLarge ? 'Dismiss' : 'Cancel')}
                    </button>
                  )}
                  {(item.stage === 'done' || item.stage === 'review' || item.stage === 'failed') && (
                    <button aria-describedby={name} onClick={act(item, queue.dismiss)}>{t('Dismiss')}</button>
                  )}
                </div>
              </li>
            )
          })}
        </ul>
      </>}
    </section>
  )
}
