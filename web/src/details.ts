import {
  type DetailsAnswer, type DetailsFields, type BookDetails, type ShelfBook, bookDocument,
} from './data'
import {
  type DiscoveryRequest, type InsightIdentity, DiscoveryHttpError, catalogInsightIdentity, discoveryJson,
  insightIdentity, jsonBody,
} from './discovery/api'

/** The identity the insights use for a library book, so both share one server record. */
export function bookIdentity(book: ShelfBook): InsightIdentity {
  return insightIdentity({
    ...bookDocument(book),
    isbn: book.isbn,
    opds_href: book.opds_href ? catalogInsightIdentity(book.opds_href).opds_href : undefined,
  })
}

const text = (value: string | null | undefined, max: number) => value?.trim().slice(0, max) || null

/** The book's own fields, within the server's limits. */
function ownFields(book: ShelfBook): DetailsFields {
  const authors = book.authors?.length ? book.authors : book.author ? [book.author] : []
  const index = book.series_index
  return {
    title: book.title.trim().slice(0, 512),
    authors: authors.slice(0, 16).map((name) => name.slice(0, 256)),
    series_name: text(book.series_name, 256),
    series_index: index != null && index >= 0 && index <= 10_000 ? index : null,
    description: text(book.description, 8192),
    subjects: (book.subjects ?? []).slice(0, 64).map((subject) => subject.slice(0, 80)),
    language: text(book.language, 32),
    isbn: text(book.isbn, 64),
    year: null,
    publisher: text(book.publisher, 256),
  }
}

export function detailsBody(book: ShelfBook) {
  // A downloaded book's fields come from its file; one only on the shelf, from its catalog entry.
  const source = !book.hasDownload && book.opds_href ? 'catalog' : 'file'
  return {
    identity: bookIdentity(book),
    book: ownFields(book),
    source,
    cover: book.cover instanceof Blob ? source : null,
  }
}

/** A title like `dune_frank_herbert_(retail).epub` rather than one a person wrote. */
export function looksLikeFileName(title: string): boolean {
  return /_|\.(?:k?epub|mobi|azw3?|pdf|fb2)\b|[[(](?:retail|epub|ebook|mobi|pdf)[\])]/i.test(title) ||
    (/\p{Ll}/u.test(title) && !/\p{Lu}/u.test(title))
}

/** Whether this book should be looked up on its own, without the reader asking. */
export function wantsLookup(book: ShelfBook, now: number): boolean {
  // Tried once: an answer has no retry_at, a failure waits until its retry_at.
  const retryAt = book.details ? (book.details.retry_at ?? Infinity) : 0
  if (retryAt > now) return false
  return !(book.cover instanceof Blob) || !(book.authors?.length || book.author) ||
    !book.description || looksLikeFileName(book.title)
}

/** Fetches the cover the answer names: a drawn one from this server, or Open Library's. */
async function coverOf(request: DiscoveryRequest, answer: DetailsAnswer, signal?: AbortSignal) {
  const { url, source } = answer.cover
  if (!url || (source !== 'generated' && source !== 'openlibrary')) return undefined
  let response: Response
  if (source === 'generated') {
    if (!/^\/details\/v1\/covers\/[0-9a-f]{64}\?v=[0-9a-f]+$/.test(url)) return undefined
    response = await request(url, { signal })
  } else {
    const address = new URL(url)
    if (address.protocol !== 'https:' || address.hostname !== 'covers.openlibrary.org') return undefined
    response = await fetch(address, { signal, credentials: 'omit', referrerPolicy: 'no-referrer' })
  }
  // A cover whose address went stale, or that Open Library lacks, is simply absent.
  if (response.status === 404) return undefined
  if (!response.ok) {
    const wait = Number(response.headers.get('Retry-After'))
    throw new DiscoveryHttpError(response.status, null, wait > 0 ? wait : undefined)
  }
  const blob = await response.blob()
  return blob.type.startsWith('image/') ? blob : undefined
}

export type DetailsStore = {
  /** Keeps `cover` as the image behind `details.cover_url`; without `cover_url` the stored image goes. */
  setDetails(key: string, details: BookDetails, cover?: Blob): Promise<void>
  getPreference<T>(key: string): Promise<T | undefined>
  setPreference<T>(key: string, value: T): Promise<void>
}

type Turn = { tidies: number; tidying: number }
const turns = new WeakMap<DetailsStore, Map<string, Turn>>()
const day = 86_400_000
const waitOf = (error: unknown) => error instanceof DiscoveryHttpError && error.retryAfter ? error.retryAfter * 1000 : day
/** One that refused: 409, as when the reader is no longer opted in to AI. */
const refused = (error: unknown) => error instanceof DiscoveryHttpError && error.status === 409
/** An answer whose tidy was due but did not happen: asked again after a day. */
const unfinished = (answer: DetailsAnswer) => answer.tidy === 'quota_exceeded' || answer.tidy === 'failed'
/** Only the found cover is missing: a lookup then fetches the image, not the answer. */
const coverOnly = (book: ShelfBook) =>
  !!book.details?.answer && !unfinished(book.details.answer) && !book.details.answer.cover.painting
/** Asking again after a painting costs nothing: the server answers from the stored record. */
const free = (book: ShelfBook) => !!book.details?.answer && !unfinished(book.details.answer)
const hour = 3_600_000
/** How many times a book is asked again while its cover is being painted. */
export const paintingChecks = 4

/**
 * Asks the server for a book's details and keeps the answer and its cover in
 * this browser. `/tidy` always spends a generation; `/lookup` only when the
 * book calls for one. A tidy wins over a lookup of the same book: a lookup
 * that ends while a tidy runs, or after one started, is dropped, and so is a
 * tidy overtaken by a later one. Answers `undefined` when dropped.
 */
export async function fetchDetails(
  store: DetailsStore, request: DiscoveryRequest, book: ShelfBook,
  action: 'lookup' | 'tidy', signal?: AbortSignal,
): Promise<BookDetails | undefined> {
  const books = turns.get(store) ?? new Map<string, Turn>()
  turns.set(store, books)
  const turn = books.get(book.key) ?? { tidies: 0, tidying: 0 }
  books.set(book.key, turn)
  if (action === 'tidy') {
    turn.tidies++
    turn.tidying++
  }
  const mine = turn.tidies
  const stale = () => turn.tidies !== mine || (action === 'lookup' && turn.tidying > 0)
  const previous = book.details
  try {
    const answer = action === 'lookup' && coverOnly(book) ? previous!.answer! : await discoveryJson<DetailsAnswer>(
      request, `/details/v1/${action}`, jsonBody('POST', detailsBody(book), signal),
    )
    const attempted_at = Date.now()
    let retry_at = unfinished(answer) ? attempted_at + day : undefined
    // Painted artwork arrives under a new address: ask again in an hour, a few times at most.
    const painting_checks = answer.cover.painting ? (previous?.answer?.cover.painting ? previous.painting_checks ?? 0 : 0) + 1 : undefined
    if (painting_checks && painting_checks <= paintingChecks) retry_at = Math.min(retry_at ?? Infinity, attempted_at + hour)
    let cover: Blob | undefined
    let cover_url = answer.cover.url && previous?.cover_url === answer.cover.url ? answer.cover.url : undefined
    if (answer.cover.url && !cover_url) {
      try {
        cover = await coverOf(request, answer, signal)
        cover_url = cover && answer.cover.url
      } catch (error) {
        // Keep the answer and the image already shown; only the new image is tried again.
        cover_url = previous?.cover_url
        retry_at = Math.min(retry_at ?? Infinity, Date.now() + waitOf(error))
      }
    }
    if (stale()) return undefined
    // Asking for a tidy means wanting to see it, even after choosing the original.
    const details = { attempted_at, retry_at, answer, cover_url, painting_checks, original: action === 'tidy' ? false : previous?.original }
    await store.setDetails(book.key, details, cover)
    return details
  } catch (error) {
    // A lookup that failed waits before it is tried again; a tidy only reports.
    if (action === 'lookup' && !stale() && !refused(error)) {
      const attempted_at = Date.now()
      await store.setDetails(book.key, { ...previous, attempted_at, retry_at: attempted_at + waitOf(error) })
    }
    throw error
  } finally {
    if (action === 'tidy') {
      turn.tidying--
      const queue = queues.get(store)
      if (queue) pump(store, queue)
    }
  }
}

const concurrency = 2
/** Lookups the shelf may start on its own each day; an open book is not counted. */
export const dailyLookups = 25
const pausedKey = 'detailsPausedUntil'
const countKey = 'detailsLookupsToday'
type Count = { date: string; count: number }
type Queue = {
  request: DiscoveryRequest
  /** In order; an open book goes first. */
  waiting: Map<string, { book: ShelfBook; opened: boolean }>
  running: Set<string>
  pausedUntil: number
  today: Count
  loaded: Promise<void>
  timer?: ReturnType<typeof setTimeout>
  stopped?: boolean
}
const queues = new WeakMap<DetailsStore, Queue>()

function pause(store: DetailsStore, queue: Queue, until: number) {
  queue.pausedUntil = until
  store.setPreference(pausedKey, until).catch(() => undefined)
}

function pump(store: DetailsStore, queue: Queue) {
  clearTimeout(queue.timer)
  queue.timer = undefined
  // Offline, a lookup would fail and wait a day for nothing.
  if (queue.stopped || !queue.waiting.size || navigator.onLine === false) return
  const now = Date.now()
  if (now < queue.pausedUntil) {
    queue.timer = setTimeout(() => pump(store, queue), queue.pausedUntil - now)
    return
  }
  // A tidy the reader asked for goes ahead of the shelf.
  if ([...turns.get(store)?.values() ?? []].some((turn) => turn.tidying)) return
  const date = new Date().toDateString()
  if (queue.today.date !== date) queue.today = { date, count: 0 }
  for (const [key, { book, opened }] of queue.waiting) {
    if (queue.running.size >= concurrency) break
    const counted = !opened && !free(book)
    if (counted && queue.today.count >= dailyLookups) continue
    queue.waiting.delete(key)
    queue.running.add(key)
    if (counted) {
      queue.today = { date, count: queue.today.count + 1 }
      store.setPreference(countKey, queue.today).catch(() => undefined)
    }
    void fetchDetails(store, queue.request, book, 'lookup')
      .then((details) => {
        // The allowance is spent: try the shelf again tomorrow.
        if (details?.answer?.tidy === 'quota_exceeded') pause(store, queue, Date.now() + day)
      }, (error) => {
        if (refused(error)) {
          queue.stopped = true
          queue.waiting.clear()
        } else if (error instanceof DiscoveryHttpError && error.status === 429) pause(store, queue, Date.now() + waitOf(error))
      })
      .finally(() => {
        queue.running.delete(key)
        pump(store, queue)
      })
  }
}

/**
 * Looks up, two at a time, the books among `books` that lack a cover or
 * details: `opened` ones, the book on screen, first and without counting
 * against the day's allowance. A failure waits for the server's Retry-After,
 * or a day; a 429 or a spent allowance pauses the whole queue as long, even
 * across reloads, and a 409 stops it. Answers a function that empties the
 * queue; lookups already sent finish.
 */
export function fillDetails(store: DetailsStore, request: DiscoveryRequest, books: ShelfBook[], opened = false): () => void {
  let queue = queues.get(store)
  if (!queue) {
    const created: Queue = {
      request, waiting: new Map(), running: new Set(), pausedUntil: 0, today: { date: '', count: 0 }, loaded: Promise.resolve(),
    }
    created.loaded = Promise.all([store.getPreference<number>(pausedKey), store.getPreference<Count>(countKey)])
      .then(([until, today]) => {
        created.pausedUntil = Math.max(created.pausedUntil, until ?? 0)
        if (today) created.today = today
      }, () => undefined)
    queues.set(store, created)
    queue = created
  }
  if (queue.request !== request) {
    queue.request = request
    queue.stopped = false
  }
  const now = Date.now()
  for (const book of books) {
    const queued = queue.waiting.get(book.key)
    queue.waiting.delete(book.key)
    if (!wantsLookup(book, now) || queue.running.has(book.key)) continue
    const entry = { book, opened: opened || !!queued?.opened }
    if (opened) queue.waiting = new Map([[book.key, entry], ...queue.waiting])
    else queue.waiting.set(book.key, entry)
  }
  const current = queue
  void current.loaded.then(() => pump(store, current))
  return () => {
    current.waiting.clear()
    clearTimeout(current.timer)
    current.timer = undefined
  }
}

/** The found or drawn cover to show, when the book has none of its own and the reader did not choose the original. */
export function foundCover(book: ShelfBook): string | undefined {
  const record = book.details
  const url = record?.cover_url
  // `cover_url` names the image kept, which may be an earlier one while a newer one is not yet downloaded.
  return !(book.cover instanceof Blob) && url && !record.original && record.answer ? url : undefined
}

/** What to say about a value that is not the book's own; `null` when it is. */
export function originLabel(book: ShelfBook, field: keyof DetailsFields | 'cover', t: (key: string) => string): string | null {
  const record = book.details
  const source = record?.original ? undefined : record?.answer?.origins[field]
  // A value this client does not know is still not the book's own: label it as AI-made.
  return !source || source === 'file' || source === 'catalog' ? null
    : source === 'openlibrary' ? t('From Open Library')
      : t(source === 'ai_written' ? 'Written by AI' : 'Tidied by AI')
}

/** The book as the reader sees it: its tidied details and found cover, unless they chose the original. */
export function withDetails(book: ShelfBook): ShelfBook {
  const record = book.details
  const answer = record?.answer
  if (!answer || record.original) return book
  const found = answer.book
  return {
    ...book,
    title: found.title,
    authors: found.authors,
    series_name: found.series_name,
    series_index: found.series_index,
    description: found.description,
    subjects: found.subjects,
    language: found.language,
    isbn: found.isbn,
    publisher: found.publisher,
  }
}
