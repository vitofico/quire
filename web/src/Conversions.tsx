import { useCallback, useEffect, useRef, useState } from 'react'
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

const inFlight = (item: Conversion) => ['preparing', 'uploading', 'reupload', 'waiting', 'queued', 'converting'].includes(item.stage)

type Options = {
  store: ConversionStore | null
  request?: DiscoveryRequest
  /** The session's `pdf_conversion`; unknown for Calibre-Web logins, so the server is asked. */
  capability?: boolean
  importEpub: (epub: Blob) => Promise<string>
}

/** The reader's conversions and the server's limits, for the shelf. */
export function useConversions({ store, request, capability, importEpub }: Options) {
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
    let live = true
    convertCapabilities(latest.current.request!).then((value) => { if (live) setLimits(value ?? null) }, () => undefined)
    return () => { live = false }
  }, [asks])

  const ensure = useCallback((target: ConversionStore) => {
    if (!queue.current) {
      const made = new Conversions({
        store: target,
        request: (path, init) => latest.current.request!(path, init),
        importEpub: (epub) => latest.current.importEpub(epub),
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
  }, [])

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
export function ConversionList({ items, queue, limits, pick, t }: ListProps) {
  if (!items.length || !queue) return null
  const converterDown = limits?.available === false
  const news = queue.announcement && items.find((item) => item.id === queue.announcement!.id)
  return (
    <section className="conversions" aria-labelledby="conversions-title">
      <h2 className="eyebrow" id="conversions-title">{t('PDF conversions')}</h2>
      <p role="status" className="visually-hidden">
        {news && `${news.name}: ${stageText(news, t, converterDown)}`}
      </p>
      <ul>
        {items.map((item) => {
          const name = `conversion-${item.id}`
          const busy = item.stage === 'preparing' || item.stage === 'uploading' || item.stage === 'converting' || item.stage === 'importing'
          return (
            <li key={item.id} className={'conversion ' + item.stage} aria-labelledby={name} aria-busy={busy || undefined}>
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
                  <button className="primary" aria-describedby={name} onClick={() => void queue.start(item.id)}>
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
                  <button aria-describedby={name} onClick={() => void queue.retry(item.id)}>{t('Try again')}</button>
                )}
                {(item.stage === 'confirm' || inFlight(item)) && (
                  <button aria-describedby={name} onClick={() => void queue.cancel(item.id)}>
                    {t(item.stage === 'confirm' && item.tooLarge ? 'Dismiss' : 'Cancel')}
                  </button>
                )}
                {(item.stage === 'done' || item.stage === 'review' || item.stage === 'failed') && (
                  <button aria-describedby={name} onClick={() => void queue.dismiss(item.id)}>{t('Dismiss')}</button>
                )}
              </div>
            </li>
          )
        })}
      </ul>
    </section>
  )
}
