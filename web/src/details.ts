import {
  type CoverChoice, type DetailsAnswer, type DetailsFields, type BookDetails, type ShelfBook, type TidiedField, bookDocument,
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

/** The body of a lookup or a tidy; `cover_choice` goes only when it is not the default, which every server takes. */
export function detailsBody(book: ShelfBook, choice = book.details?.cover_choice) {
  // A downloaded book's fields come from its file; one only on the shelf, from its catalog entry.
  const source = !book.hasDownload && book.opds_href ? 'catalog' : 'file'
  return {
    identity: bookIdentity(book),
    book: ownFields(book),
    source,
    cover: book.cover instanceof Blob ? source : null,
    ...(choice && choice !== 'keep' ? { cover_choice: choice } : {}),
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
  // A cover the reader chose is checked again even when the book lacks nothing.
  return !!book.details?.cover_choice || !(book.cover instanceof Blob) || !(book.authors?.length || book.author) ||
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
/** A painted cover was asked for and this server no longer paints. */
const noPainting = (error: unknown) => error instanceof DiscoveryHttpError && error.detail === 'cover_art_unavailable'
/** A cover choice sent to a server that takes none, which rejects the body. */
const noChoice = (error: unknown, body: { cover_choice?: CoverChoice }) =>
  !!body.cover_choice && error instanceof DiscoveryHttpError && error.status === 422
/** One that refused: 409, as when the reader is no longer opted in to AI. */
const refused = (error: unknown) => error instanceof DiscoveryHttpError && error.status === 409 && !noPainting(error)
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

function turnOf(store: DetailsStore, key: string): Turn {
  const books = turns.get(store) ?? new Map<string, Turn>()
  turns.set(store, books)
  const turn = books.get(key) ?? { tidies: 0, tidying: 0 }
  books.set(key, turn)
  return turn
}

/** What a reader chose, kept across later answers for the same book. */
const choices = (record?: BookDetails) => record
  ? { original: record.original, accepted: record.accepted, cover_choice: record.cover_choice, declined: record.declined }
  : {}

/**
 * Looks a book up and keeps the answer and its cover in this browser.
 * `/lookup` spends a generation only when the book calls for one. A tidy the
 * reader asked for wins: a lookup that ends while a tidy or its preview is
 * open, or after one started, is dropped; `since` is the tidy count when the
 * lookup was queued, so one queued before a tidy is dropped too. Answers
 * `undefined` when dropped.
 */
export async function fetchDetails(
  store: DetailsStore, request: DiscoveryRequest, book: ShelfBook, signal?: AbortSignal, since?: number,
): Promise<BookDetails | undefined> {
  const turn = turnOf(store, book.key)
  const mine = since ?? turn.tidies
  const stale = () => turn.tidies !== mine || turn.tidying > 0
  const previous = book.details
  const body = detailsBody(book)
  try {
    const answer = coverOnly(book) ? previous!.answer! : await discoveryJson<DetailsAnswer>(
      request, '/details/v1/lookup', jsonBody('POST', body, signal),
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
    // The same record again keeps the fields it was asked for, so a field the book changed since still shows.
    const again = coverOnly(book) || (answer.cached && answer.key === previous?.answer?.key)
    const basis = again ? previous?.basis : body.book
    // A record the reader rejected shows none of its improvements.
    const declined = answer.key === previous?.declined ? { accepted: [] } : {}
    const details = { ...choices(previous), attempted_at, retry_at, answer, cover_url, painting_checks, basis, ...declined }
    await store.setDetails(book.key, details, cover)
    return details
  } catch (error) {
    if (stale()) throw error
    const attempted_at = Date.now()
    // The server stopped painting: keep the drawn cover already shown and ask for no more paintings.
    if (noPainting(error)) await store.setDetails(book.key, { ...previous, attempted_at, retry_at: undefined, cover_choice: 'draw' })
    // The server takes no cover choice: keep what is shown and stop sending one.
    else if (noChoice(error, body)) await store.setDetails(book.key, { ...previous, attempted_at, retry_at: undefined, cover_choice: undefined })
    // A lookup that failed waits before it is tried again.
    else if (!refused(error)) await store.setDetails(book.key, { ...previous, attempted_at, retry_at: attempted_at + waitOf(error) })
    throw error
  }
}

/** A proposed cover: the answer that names it and, when it is new to this browser, its image. */
export type CoverPreview = {
  answer: DetailsAnswer
  image?: Blob
  /** The image is the one this browser already keeps for the book. */
  kept?: boolean
}

/** A tidy waiting for the reader to accept or reject what it proposes. */
export type Proposal = {
  answer: DetailsAnswer
  /** The cover a choice gives, from the record the tidy stored, so it costs no generation. */
  cover(choice: CoverChoice, signal?: AbortSignal): Promise<CoverPreview>
  /**
   * Keeps the accepted improvements and the chosen cover, `null` for the
   * cover shown now; a painted cover is asked for only here. Answers
   * `undefined` when a later tidy of the same book overtook this one.
   */
  apply(accepted: TidiedField[], choice: CoverChoice, cover: CoverPreview | null): Promise<BookDetails | undefined>
  /** Ends the preview, rejecting the answer: a later lookup served the same record shows none of it. */
  close(): void
}

/**
 * Asks for a tidy, `/tidy`, which spends a generation, and holds its answer
 * for the reader to preview. While it is open the shelf's lookups of the
 * book are dropped and the shelf pass waits; `apply` or `close` ends it.
 */
export async function proposeDetails(
  store: DetailsStore, request: DiscoveryRequest, book: ShelfBook, signal?: AbortSignal,
): Promise<Proposal> {
  const turn = turnOf(store, book.key)
  turn.tidies++
  turn.tidying++
  const mine = turn.tidies
  // A lookup of the book already queued would answer for the fields before the reader's choice.
  queues.get(store)?.waiting.delete(book.key)
  let open = true
  const end = () => {
    if (!open) return false
    open = false
    turn.tidying--
    const queue = queues.get(store)
    if (queue) pump(store, queue)
    return true
  }
  const previous = book.details
  const preview = async (answer: DetailsAnswer, signal?: AbortSignal): Promise<CoverPreview> => {
    const url = answer.cover.url
    if (!url) return { answer }
    if (url === previous?.cover_url) return { answer, kept: true }
    return { answer, image: await coverOf(request, answer, signal) }
  }
  const lookup = (choice: CoverChoice, signal?: AbortSignal) =>
    discoveryJson<DetailsAnswer>(request, '/details/v1/lookup', jsonBody('POST', detailsBody(book, choice), signal))
  let answer: DetailsAnswer, basis: DetailsFields
  try {
    const body = detailsBody(book, 'keep')
    basis = body.book
    answer = await discoveryJson<DetailsAnswer>(request, '/details/v1/tidy', jsonBody('POST', body, signal))
  } catch (error) {
    end()
    throw error
  }
  const kept = answer
  // The tidy moved the reader's pointer to this record: remember it was rejected.
  const close = () => {
    if (!end() || turn.tidies !== mine) return
    const declined: BookDetails = previous ? { ...previous, declined: kept.key } : { attempted_at: Date.now(), retry_at: 0, declined: kept.key }
    void store.setDetails(book.key, declined).catch(() => undefined)
  }
  return {
    answer, close,
    // The tidy itself answered for `keep`; a painting is shown as the drawn cover it will be painted on.
    cover: async (choice, signal) => preview(choice === 'keep' ? kept : await lookup(choice === 'paint' ? 'draw' : choice, signal), signal),
    async apply(accepted, choice, cover) {
      try {
        if (!open || turn.tidies !== mine) return undefined
        let chosen = cover ?? (previous?.answer ? { answer: previous.answer, kept: !!previous.cover_url } : { answer: { ...kept, cover: { source: null, generated: false } } })
        if (choice === 'paint') {
          const painted = await lookup('paint')
          chosen = painted.cover.url === chosen.answer.cover.url ? { ...chosen, answer: painted } : await preview(painted)
        }
        if (turn.tidies !== mine) return undefined
        const attempted_at = Date.now()
        const url = chosen.answer.cover.url ?? undefined
        const cover_url = url && (chosen.image || chosen.kept) ? url : undefined
        const origins = { ...kept.origins, cover: chosen.answer.origins.cover }
        if (!origins.cover) delete origins.cover
        const merged = { ...kept, cover: chosen.answer.cover, origins }
        const painting_checks = merged.cover.painting ? 1 : undefined
        // A cover not yet downloaded, or still being painted, is asked for again later.
        const retry_at = painting_checks || (url && !cover_url) ? attempted_at + hour : undefined
        const details: BookDetails = {
          attempted_at, retry_at, answer: merged, cover_url, painting_checks, basis, accepted, original: false,
          cover_choice: choice === 'keep' ? undefined : choice,
        }
        await store.setDetails(book.key, details, chosen.image)
        return details
      } finally {
        end()
      }
    },
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
  /** `since`: the book's tidy count when it was queued. */
  waiting: Map<string, { book: ShelfBook; opened: boolean; since: number }>
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
  for (const [key, { book, opened, since }] of queue.waiting) {
    if (queue.running.size >= concurrency) break
    const counted = !opened && !free(book)
    if (counted && queue.today.count >= dailyLookups) continue
    queue.waiting.delete(key)
    queue.running.add(key)
    if (counted) {
      queue.today = { date, count: queue.today.count + 1 }
      store.setPreference(countKey, queue.today).catch(() => undefined)
    }
    void fetchDetails(store, queue.request, book, undefined, since)
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
    const entry = { book, opened: opened || !!queued?.opened, since: turnOf(store, book.key).tidies }
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

/**
 * The found or drawn cover to show: when the book has none of its own, or
 * the reader chose another, and did not choose the original.
 */
export function foundCover(book: ShelfBook): string | undefined {
  const record = book.details
  const url = record?.cover_url
  const chosen = !!record?.cover_choice && record.cover_choice !== 'keep'
  // `cover_url` names the image kept, which may be an earlier one while a newer one is not yet downloaded.
  return (chosen || !(book.cover instanceof Blob)) && url && !record.original && record.answer ? url : undefined
}

const groups: Partial<Record<keyof DetailsFields, TidiedField>> = {
  title: 'title', authors: 'authors', series_name: 'series', series_index: 'series', description: 'description',
}
const same = (a: unknown, b: unknown) => JSON.stringify(a ?? null) === JSON.stringify(b ?? null)

/** Whether the answer's value of `field` is shown: the reader did not reject it and the book has not changed it since. */
function shown(book: ShelfBook, field: keyof DetailsFields): boolean {
  const record = book.details
  if (!record?.answer || record.original) return false
  const group = groups[field]
  if (group && record.accepted && !record.accepted.includes(group)) return false
  return !record.basis || same(record.basis[field], ownFields(book)[field])
}

/** What to say about a value that is not the book's own; `null` when it is. */
export function originLabel(book: ShelfBook, field: keyof DetailsFields | 'cover', t: (key: string) => string): string | null {
  const record = book.details
  const source = field !== 'cover' && !shown(book, field) ? undefined : record?.original ? undefined : record?.answer?.origins[field]
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
  const pick = <K extends keyof DetailsFields>(field: K, own: ShelfBook[K & keyof ShelfBook]) => shown(book, field) ? found[field] : own
  return {
    ...book,
    title: pick('title', book.title),
    authors: pick('authors', book.authors),
    series_name: pick('series_name', book.series_name),
    series_index: pick('series_index', book.series_index),
    description: pick('description', book.description),
    subjects: pick('subjects', book.subjects),
    language: pick('language', book.language),
    isbn: pick('isbn', book.isbn),
    publisher: pick('publisher', book.publisher),
  }
}
