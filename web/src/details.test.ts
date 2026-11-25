// @vitest-environment node
import 'fake-indexeddb/auto'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { openReplica, type BookDetails, type DetailsAnswer, type Replica, type ShelfBook } from './data'
import { bookIdentity, detailsBody, fetchDetails, fillDetails, looksLikeFileName, wantsLookup, withDetails } from './details'

const messy: ShelfBook = {
  key: 'hash-1', content_hash: 'hash-1', metadata_id: 'urn:uuid:dune', title: 'dune_frank_herbert_(retail)',
  authors: ['Herbert, Frank'], isbn: '9780441013593', opds_href: 'https://catalog.test/book/1',
  hasDownload: true, synced: true, localOnly: false,
}
const complete: ShelfBook = {
  ...messy, key: 'hash-2', content_hash: 'hash-2', title: 'Dune', description: 'Spice.', cover: new Blob(['jpg'], { type: 'image/jpeg' }),
}

function answer(overrides: Partial<DetailsAnswer> = {}): DetailsAnswer {
  return {
    key: 'k'.repeat(64),
    book: {
      title: 'Dune', authors: ['Frank Herbert'], series_name: null, series_index: null, description: 'A desert planet.',
      subjects: ['Science fiction'], language: null, isbn: '9780441013593', year: 1965, publisher: null,
    },
    origins: { title: 'ai', authors: 'ai', description: 'ai', subjects: 'openlibrary', isbn: 'file', year: 'openlibrary', cover: 'ai' },
    cover: { source: 'generated', url: `/details/v1/covers/${'k'.repeat(64)}?v=e1`, generated: true },
    tidy: 'done', cached: false, openlibrary_url: 'https://openlibrary.org/works/OL893415W',
    ...overrides,
  }
}

const png = () => new Response(new Blob(['png'], { type: 'image/png' }))

function memoryStore() {
  const saved = new Map<string, BookDetails>()
  return { saved, setDetails: vi.fn(async (key: string, value: BookDetails) => { saved.set(key, value) }) }
}

const replicas: Replica[] = []
afterEach(() => {
  for (const replica of replicas.splice(0)) replica.close()
  vi.unstubAllGlobals()
})

describe('details client', () => {
  it('sends the insights identity and the book own fields within the server limits', () => {
    const body = detailsBody({ ...messy, description: ' ', subjects: Array.from({ length: 70 }, (_, i) => `s${i}`), series_index: -1 })
    expect(body.identity).toEqual(bookIdentity(messy))
    expect(JSON.parse(JSON.stringify(body.identity))).toEqual({
      metadata_id: 'urn:uuid:dune', content_hash: 'hash-1', identity_hash_version: 1, isbn: '9780441013593',
      opds_href: expect.stringMatching(/^opds-href:[0-9a-f]{64}$/),
    })
    expect(body.book).toMatchObject({ title: messy.title, authors: ['Herbert, Frank'], description: null, series_index: null, year: null })
    expect(body.book.subjects).toHaveLength(64)
    expect(body).toMatchObject({ source: 'file', cover: null })
    expect(detailsBody({ ...complete, hasDownload: false })).toMatchObject({ source: 'catalog', cover: 'catalog' })
  })

  it('looks a book up, keeps the drawn cover fetched through the account, and never fetches elsewhere', async () => {
    const store = memoryStore()
    const request = vi.fn(async (path: string, init?: RequestInit) => {
      if (path === '/details/v1/lookup') {
        expect(JSON.parse(String(init?.body))).toEqual(JSON.parse(JSON.stringify(detailsBody(messy))))
        return Response.json(answer())
      }
      return png()
    })
    const details = (await fetchDetails(store, request, messy, 'lookup'))!
    expect(request.mock.calls.map(([path]) => path)).toEqual(['/details/v1/lookup', `/details/v1/covers/${'k'.repeat(64)}?v=e1`])
    expect(details.answer?.tidy).toBe('done')
    expect(await details.cover?.text()).toBe('png')
    expect(store.saved.get('hash-1')).toBe(details)

    const foreign = vi.fn(async (path: string) => path.startsWith('/details/v1/lookup')
      ? Response.json(answer({ cover: { source: 'generated', url: 'https://elsewhere.test/cover.png', generated: true } }))
      : png())
    vi.stubGlobal('fetch', vi.fn())
    expect((await fetchDetails(store, foreign, messy, 'lookup'))!.cover).toBeUndefined()
    expect(foreign).toHaveBeenCalledTimes(1)
    const lookalike = vi.fn(async () => Response.json(answer({ cover: { source: 'openlibrary', url: 'https://covers.openlibrary.org.evil.test/b.jpg', generated: false } })))
    expect((await fetchDetails(store, lookalike, messy, 'lookup'))!.cover).toBeUndefined()
    expect(fetch).not.toHaveBeenCalled()
  })

  it('downloads an Open Library cover without credentials and reuses a cover it already holds', async () => {
    const store = memoryStore()
    const url = 'https://covers.openlibrary.org/b/id/12345-L.jpg'
    const found = answer({ cover: { source: 'openlibrary', url, generated: false } })
    const download = vi.fn(async () => new Response(new Blob(['jpg'], { type: 'image/jpeg' })))
    vi.stubGlobal('fetch', download)
    const request = vi.fn(async () => Response.json(found))
    const first = await fetchDetails(store, request, messy, 'lookup')
    expect(download).toHaveBeenCalledWith(new URL(url), expect.objectContaining({ credentials: 'omit', referrerPolicy: 'no-referrer' }))
    await fetchDetails(store, request, { ...messy, details: first }, 'tidy')
    expect(download).toHaveBeenCalledTimes(1)
  })

  it('a tidy on request shows the tidied details again after the reader chose the original', async () => {
    const store = memoryStore()
    const request = vi.fn(async (path: string) => path === '/details/v1/tidy' ? Response.json(answer()) : png())
    const details = (await fetchDetails(store, request, { ...messy, details: { attempted_at: 1, answer: answer(), original: true } }, 'tidy'))!
    expect(details.original).toBe(false)
  })
})

describe('a lookup and a tidy of the same book', () => {
  function gate() {
    let open!: () => void
    return { wait: new Promise<void>((resolve) => { open = resolve }), open: () => open() }
  }

  it('the tidy wins: a lookup ending after a tidy started is dropped, its failure too', async () => {
    const store = memoryStore()
    const slow = gate()
    const request = vi.fn(async (path: string) => {
      if (path === '/details/v1/lookup') {
        await slow.wait
        return Response.json(answer({ book: { ...answer().book, title: 'Looked up' }, cover: { source: null, generated: false } }))
      }
      return Response.json(answer({ book: { ...answer().book, title: 'Tidied' }, cover: { source: null, generated: false } }))
    })
    const lookup = fetchDetails(store, request, messy, 'lookup')
    expect((await fetchDetails(store, request, messy, 'tidy'))?.answer?.book.title).toBe('Tidied')
    slow.open()
    expect(await lookup).toBeUndefined()
    expect(store.saved.get('hash-1')?.answer?.book.title).toBe('Tidied')

    const failing = gate()
    const broken = vi.fn(async (path: string) => {
      if (path === '/details/v1/tidy') return Response.json(answer({ cover: { source: null, generated: false } }))
      await failing.wait
      return Response.json({ detail: 'busy' }, { status: 503 })
    })
    const late = fetchDetails(store, broken, { ...messy, key: 'hash-9' }, 'lookup')
    await fetchDetails(store, broken, { ...messy, key: 'hash-9' }, 'tidy')
    failing.open()
    await expect(late).rejects.toMatchObject({ status: 503 })
    expect(store.saved.get('hash-9')?.retry_at).toBeUndefined()
  })

  it('a lookup started while a tidy runs is dropped, and a later tidy overtakes an earlier one', async () => {
    const store = memoryStore()
    const first = gate()
    let tidies = 0
    const request = vi.fn(async (path: string) => {
      if (path === '/details/v1/tidy' && ++tidies === 1) {
        await first.wait
        return Response.json(answer({ book: { ...answer().book, title: 'First tidy' }, cover: { source: null, generated: false } }))
      }
      return Response.json(answer({ book: { ...answer().book, title: path }, cover: { source: null, generated: false } }))
    })
    const earlier = fetchDetails(store, request, messy, 'tidy')
    expect(await fetchDetails(store, request, messy, 'lookup')).toBeUndefined()
    expect((await fetchDetails(store, request, messy, 'tidy'))?.answer?.book.title).toBe('/details/v1/tidy')
    first.open()
    expect(await earlier).toBeUndefined()
    expect(store.saved.get('hash-1')?.answer?.book.title).toBe('/details/v1/tidy')
  })

  it('stores an answer whose fields differ from those sent, as the reader\'s record from another device', async () => {
    const store = memoryStore()
    const theirs = answer({ book: { ...answer().book, title: 'Dune (from the phone)', isbn: '9780340960196' }, cached: true, cover: { source: null, generated: false } })
    await fetchDetails(store, vi.fn(async () => Response.json(theirs)), messy, 'lookup')
    expect(store.saved.get('hash-1')?.answer).toEqual(theirs)
    expect(withDetails({ ...messy, details: store.saved.get('hash-1') })).toMatchObject({ title: 'Dune (from the phone)', isbn: '9780340960196' })
  })
})

describe('when details are looked up', () => {
  it('recognises titles that are file names', () => {
    for (const title of ['dune_frank_herbert', 'Dune.epub', 'Dune (retail)', 'Dune [EPUB]', 'dune', 'дюна']) {
      expect(looksLikeFileName(title), title).toBe(true)
    }
    for (const title of ['Dune', 'The Left Hand of Darkness', '三体', 'Дюна']) expect(looksLikeFileName(title), title).toBe(false)
  })

  it('looks up books that lack something, once, and again only after the wait', () => {
    expect(wantsLookup(complete, 0)).toBe(false)
    expect(wantsLookup({ ...complete, cover: null }, 0)).toBe(true)
    expect(wantsLookup({ ...complete, description: null }, 0)).toBe(true)
    expect(wantsLookup({ ...complete, authors: [] }, 0)).toBe(true)
    expect(wantsLookup({ ...complete, title: 'dune_(retail)' }, 0)).toBe(true)
    for (const tidy of ['quota_exceeded', 'failed', 'not_needed'] as const) {
      expect(wantsLookup({ ...messy, details: { attempted_at: 0, answer: answer({ tidy }) } }, 10 ** 13)).toBe(false)
    }
    const failed = { ...messy, details: { attempted_at: 0, retry_at: 1000 } }
    expect(wantsLookup(failed, 999)).toBe(false)
    expect(wantsLookup(failed, 1000)).toBe(true)
  })

  it('runs two lookups at a time and remembers each attempt', async () => {
    const store = memoryStore()
    let active = 0, peak = 0
    const request = vi.fn(async (path: string) => {
      if (!path.startsWith('/details/v1/lookup')) return png()
      active++
      peak = Math.max(peak, active)
      await new Promise((resolve) => setTimeout(resolve, 5))
      active--
      return Response.json(answer({ cover: { source: null, generated: false } }))
    })
    const books = Array.from({ length: 5 }, (_, i) => ({ ...messy, key: `b${i}`, content_hash: `b${i}` }))
    fillDetails(store, request, books)
    fillDetails(store, request, books)
    await vi.waitFor(() => expect(store.saved.size).toBe(5))
    expect(peak).toBe(2)
    expect(request).toHaveBeenCalledTimes(5)
  })

  it('a 429 pauses the queue for Retry-After and the book waits as long', async () => {
    const store = memoryStore()
    const request = vi.fn(async () => Response.json({ detail: 'details_rate_limited' }, { status: 429, headers: { 'Retry-After': '30' } }))
    const books = Array.from({ length: 4 }, (_, i) => ({ ...messy, key: `r${i}`, content_hash: `r${i}` }))
    fillDetails(store, request, books)
    await vi.waitFor(() => expect(store.saved.size).toBe(2))
    const { attempted_at, retry_at } = store.saved.get('r0')!
    expect(retry_at! - attempted_at).toBe(30_000)
    expect(request).toHaveBeenCalledTimes(2)
    fillDetails(store, request, books)
    expect(request).toHaveBeenCalledTimes(2)

    const other = memoryStore()
    const offline = vi.fn(async () => { throw new TypeError('Failed to fetch') })
    fillDetails(other, offline, [messy])
    await vi.waitFor(() => expect(other.saved.get('hash-1')).toBeDefined())
    const wait = other.saved.get('hash-1')!
    expect(wait.retry_at! - wait.attempted_at).toBe(86_400_000)
  })
})

describe('stored details', () => {
  it('keep the answer and cover offline, drop them with the book, and never replace its own cover', async () => {
    const replica = await openReplica(crypto.randomUUID())
    replicas.push(replica)
    await replica.putBook({ key: 'own', title: 'dune_(retail)', cover: new Blob(['own'], { type: 'image/jpeg' }) })
    await replica.putBook({ key: 'bare', title: 'dune_(retail)' })
    const drawn = new Blob(['png'], { type: 'image/png' })
    await replica.setDetails('own', { attempted_at: 1, answer: answer(), cover: drawn })
    await replica.setDetails('bare', { attempted_at: 1, answer: answer(), cover: drawn })
    const books = (await replica.snapshot()).books
    const own = books.find((book) => book.key === 'own')!
    const bare = books.find((book) => book.key === 'bare')!
    expect(bare.details?.answer?.key).toBe('k'.repeat(64))
    expect(await bare.details?.cover?.text()).toBe('png')
    expect(withDetails(bare)).toMatchObject({ title: 'Dune', authors: ['Frank Herbert'], description: 'A desert planet.' })
    expect(await (withDetails(bare).cover as Blob).text()).toBe('png')
    expect(await (withDetails(own).cover as Blob).text()).toBe('own')
    expect(withDetails({ ...bare, details: { ...bare.details!, original: true } })).toMatchObject({ title: 'dune_(retail)', cover: undefined })

    await replica.removeBook('bare')
    await replica.putBook({ key: 'bare', title: 'dune_(retail)' })
    expect((await replica.snapshot()).books.find((book) => book.key === 'bare')?.details).toBeUndefined()
  })
})
