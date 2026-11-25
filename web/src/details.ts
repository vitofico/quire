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
    if (!url.startsWith('/details/v1/covers/')) return undefined
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

export type DetailsStore = { setDetails(key: string, details: BookDetails): Promise<void> }

type Turn = { tidies: number; tidying: number }
const turns = new WeakMap<DetailsStore, Map<string, Turn>>()
const dayInSeconds = 86_400
const waitOf = (error: unknown) => (error instanceof DiscoveryHttpError && error.retryAfter) || dayInSeconds

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
  try {
    const answer = await discoveryJson<DetailsAnswer>(
      request, `/details/v1/${action}`, jsonBody('POST', detailsBody(book), signal),
    )
    const previous = book.details
    const kept = previous?.answer?.cover.url === answer.cover.url ? previous?.cover : undefined
    const cover = kept ?? await coverOf(request, answer, signal)
    if (stale()) return undefined
    // Asking for a tidy means wanting to see it, even after choosing the original.
    const details = { attempted_at: Date.now(), answer, cover, original: action === 'tidy' ? false : previous?.original }
    await store.setDetails(book.key, details)
    return details
  } catch (error) {
    // A lookup that failed waits before it is tried again; a tidy only reports.
    if (action === 'lookup' && !stale()) {
      const attempted_at = Date.now()
      await store.setDetails(book.key, { ...book.details, attempted_at, retry_at: attempted_at + waitOf(error) * 1000 })
    }
    throw error
  } finally {
    if (action === 'tidy') turn.tidying--
  }
}

const concurrency = 2
type Queue = { waiting: Map<string, ShelfBook>; running: Set<string>; pausedUntil: number }
const queues = new WeakMap<DetailsStore, Queue>()

/**
 * Looks up, two at a time, the books among `books` that lack a cover or
 * details. Each book is tried once; a failure waits for the server's
 * Retry-After, or a day, and a 429 pauses the whole queue until then.
 */
export function fillDetails(store: DetailsStore, request: DiscoveryRequest, books: ShelfBook[]): void {
  const queue = queues.get(store) ?? { waiting: new Map(), running: new Set(), pausedUntil: 0 }
  queues.set(store, queue)
  const now = Date.now()
  for (const book of books) {
    if (!wantsLookup(book, now)) queue.waiting.delete(book.key)
    else if (!queue.running.has(book.key)) queue.waiting.set(book.key, book)
  }
  // Offline, a lookup would fail and wait a day for nothing.
  while (queue.running.size < concurrency && queue.waiting.size && navigator.onLine !== false && now >= queue.pausedUntil) {
    const [key, book] = queue.waiting.entries().next().value!
    queue.waiting.delete(key)
    queue.running.add(key)
    void fetchDetails(store, request, book, 'lookup')
      .catch((error) => {
        if (error instanceof DiscoveryHttpError && error.status === 429) {
          queue.pausedUntil = Date.now() + waitOf(error) * 1000
        }
      })
      .finally(() => {
        queue.running.delete(key)
        fillDetails(store, request, [])
      })
  }
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
    // A found or drawn cover never replaces the book's own.
    cover: book.cover instanceof Blob ? book.cover : (record.cover ?? book.cover),
  }
}
