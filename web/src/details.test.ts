// @vitest-environment node
import 'fake-indexeddb/auto'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { openReplica, type BookDetails, type DetailsAnswer, type Replica, type ShelfBook } from './data'
import { bookIdentity, dailyLookups, paintingChecks, detailsBody, fetchDetails, fillDetails, foundCover, looksLikeFileName, originLabel, proposeDetails, wantsLookup, withDetails } from './details'
import { DiscoveryHttpError, requestMessage } from './discovery/api'

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
    cover: { source: 'generated', url: `/details/v1/covers/${'c'.repeat(64)}?v=e1`, generated: true },
    tidy: 'done', cached: false, openlibrary_url: 'https://openlibrary.org/works/OL893415W',
    ...overrides,
  }
}

const png = () => new Response(new Blob(['png'], { type: 'image/png' }))

function memoryStore(prefs = new Map<string, unknown>()) {
  const saved = new Map<string, BookDetails>(), covers = new Map<string, Blob>()
  return {
    saved, covers, prefs,
    setDetails: vi.fn(async (key: string, value: BookDetails, cover?: Blob) => {
      saved.set(key, value)
      if (cover) covers.set(key, cover)
    }),
    getPreference: async <T>(key: string) => prefs.get(key) as T | undefined,
    setPreference: async <T>(key: string, value: T) => { prefs.set(key, value) },
  }
}

const noCover = { source: null, generated: false } as const
const shelf = (prefix: string, length: number) =>
  Array.from({ length }, (_, i) => ({ ...messy, key: `${prefix}${i}`, content_hash: `${prefix}${i}` }))
const settle = () => new Promise((resolve) => setTimeout(resolve, 30))
function gate() {
  let open!: () => void
  return { wait: new Promise<void>((resolve) => { open = resolve }), open: () => open() }
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
    const details = (await fetchDetails(store, request, messy))!
    expect(request.mock.calls.map(([path]) => path)).toEqual(['/details/v1/lookup', `/details/v1/covers/${'c'.repeat(64)}?v=e1`])
    expect(details.answer?.tidy).toBe('done')
    expect(details.cover_url).toBe(answer().cover.url)
    expect(await store.covers.get('hash-1')?.text()).toBe('png')
    expect(store.saved.get('hash-1')).toBe(details)

    const foreign = vi.fn(async (path: string) => path.startsWith('/details/v1/lookup')
      ? Response.json(answer({ cover: { source: 'generated', url: 'https://elsewhere.test/cover.png', generated: true } }))
      : png())
    vi.stubGlobal('fetch', vi.fn())
    expect((await fetchDetails(store, foreign, messy))!.cover_url).toBeUndefined()
    expect(foreign).toHaveBeenCalledTimes(1)
    const escaping = vi.fn(async (path: string) => path.startsWith('/details/v1/lookup')
      ? Response.json(answer({ cover: { source: 'generated', url: `/details/v1/covers/${'c'.repeat(64)}/../../../sync/v1/pull?v=e1`, generated: true } }))
      : png())
    expect((await fetchDetails(store, escaping, messy))!.cover_url).toBeUndefined()
    expect(escaping).toHaveBeenCalledTimes(1)
    const lookalike = vi.fn(async () => Response.json(answer({ cover: { source: 'openlibrary', url: 'https://covers.openlibrary.org.evil.test/b.jpg', generated: false } })))
    expect((await fetchDetails(store, lookalike, messy))!.cover_url).toBeUndefined()
    expect(fetch).not.toHaveBeenCalled()
  })

  it('downloads an Open Library cover without credentials and reuses a cover it already holds', async () => {
    const store = memoryStore()
    const url = 'https://covers.openlibrary.org/b/id/12345-L.jpg'
    const found = answer({ cover: { source: 'openlibrary', url, generated: false } })
    const download = vi.fn(async () => new Response(new Blob(['jpg'], { type: 'image/jpeg' })))
    vi.stubGlobal('fetch', download)
    const request = vi.fn(async () => Response.json(found))
    const first = await fetchDetails(store, request, messy)
    expect(download).toHaveBeenCalledWith(new URL(url), expect.objectContaining({ credentials: 'omit', referrerPolicy: 'no-referrer' }))
    const proposal = await proposeDetails(store, request, { ...messy, details: first })
    expect((await proposal.cover('keep')).kept).toBe(true)
    await proposal.apply(['title'], 'keep', await proposal.cover('keep'))
    expect(download).toHaveBeenCalledTimes(1)
  })

  it('keeps the answer when its cover fails to download, and later fetches only the cover', async () => {
    const store = memoryStore()
    let covers = 0
    const request = vi.fn(async (path: string) => {
      if (path === '/details/v1/lookup') return Response.json(answer())
      return ++covers === 1 ? new Response(null, { status: 503 }) : png()
    })
    const first = (await fetchDetails(store, request, messy))!
    expect(first).toMatchObject({ answer: answer(), cover_url: undefined })
    expect(first.retry_at! - first.attempted_at).toBeCloseTo(86_400_000, -3)
    expect(wantsLookup({ ...messy, details: first }, first.retry_at!)).toBe(true)
    const second = (await fetchDetails(store, request, { ...messy, details: first }))!
    expect(request.mock.calls.map(([path]) => path).filter((path) => path === '/details/v1/lookup')).toHaveLength(1)
    expect(second).toMatchObject({ cover_url: answer().cover.url, retry_at: undefined })
    expect(await store.covers.get('hash-1')?.text()).toBe('png')
  })

  it('says a busy server is busy, not that the allowance is spent', () => {
    expect(requestMessage(new DiscoveryHttpError(429, 'details_rate_limited', 30)).message).toBe('Busy right now. Try again in a minute.')
    expect(requestMessage(new DiscoveryHttpError(429, 'monthly_ai_quota_exhausted')).message).toBe('AI limit reached. Try again after the quota resets.')
  })

  it('a tidy on request shows the tidied details again after the reader chose the original', async () => {
    const store = memoryStore()
    const request = vi.fn(async (path: string) => path === '/details/v1/tidy' ? Response.json(answer()) : png())
    const proposal = await proposeDetails(store, request, { ...messy, details: { attempted_at: 1, answer: answer(), original: true } })
    const details = (await proposal.apply(['title'], 'keep', await proposal.cover('keep')))!
    expect(details.original).toBe(false)
  })
})

describe('a lookup and a tidy of the same book', () => {
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
    const lookup = fetchDetails(store, request, messy)
    const proposal = await proposeDetails(store, request, messy)
    expect((await proposal.apply(['title'], 'keep', await proposal.cover('keep')))?.answer?.book.title).toBe('Tidied')
    slow.open()
    expect(await lookup).toBeUndefined()
    expect(store.saved.get('hash-1')?.answer?.book.title).toBe('Tidied')

    const failing = gate()
    const broken = vi.fn(async (path: string) => {
      if (path === '/details/v1/tidy') return Response.json(answer({ cover: { source: null, generated: false } }))
      await failing.wait
      return Response.json({ detail: 'busy' }, { status: 503 })
    })
    const late = fetchDetails(store, broken, { ...messy, key: 'hash-9' })
    const tidy = await proposeDetails(store, broken, { ...messy, key: 'hash-9' })
    await tidy.apply(['title'], 'keep', null)
    failing.open()
    await expect(late).rejects.toMatchObject({ status: 503 })
    expect(store.saved.get('hash-9')?.retry_at).toBeUndefined()
  })

  it('a lookup started while a tidy runs or its preview is open is dropped, and a later tidy overtakes an earlier one', async () => {
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
    const earlier = proposeDetails(store, request, messy)
    expect(await fetchDetails(store, request, messy)).toBeUndefined()
    const later = await proposeDetails(store, request, messy)
    // The preview is open: the shelf's lookup is dropped while the reader decides.
    expect(await fetchDetails(store, request, messy)).toBeUndefined()
    expect((await later.apply(['title'], 'keep', null))?.answer?.book.title).toBe('/details/v1/tidy')
    first.open()
    const overtaken = await earlier
    expect(await overtaken.apply(['title'], 'keep', null)).toBeUndefined()
    expect(store.saved.get('hash-1')?.answer?.book.title).toBe('/details/v1/tidy')
    // Once both previews ended, a lookup is kept again.
    expect(await fetchDetails(store, request, messy)).toBeDefined()
  })

  it('stores an answer whose fields differ from those sent, as the reader\'s record from another device', async () => {
    const store = memoryStore()
    const theirs = answer({ book: { ...answer().book, title: 'Dune (from the phone)', isbn: '9780340960196' }, cached: true, cover: { source: null, generated: false } })
    await fetchDetails(store, vi.fn(async () => Response.json(theirs)), messy)
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
    expect(wantsLookup({ ...messy, details: { attempted_at: 0, answer: answer({ tidy: 'not_needed' }) } }, 10 ** 13)).toBe(false)
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
    await settle()
    expect(request).toHaveBeenCalledTimes(2)

    const other = memoryStore()
    const offline = vi.fn(async () => { throw new TypeError('Failed to fetch') })
    fillDetails(other, offline, [messy])
    await vi.waitFor(() => expect(other.saved.get('hash-1')).toBeDefined())
    const wait = other.saved.get('hash-1')!
    expect(wait.retry_at! - wait.attempted_at).toBeCloseTo(86_400_000, -3)
  })
})

describe('the shelf pass', () => {
  it('starts again by itself when a 429 pause ends, and a reload honours the pause', async () => {
    const store = memoryStore()
    let calls = 0
    const request = vi.fn(async () => ++calls <= 2
      ? Response.json({ detail: 'details_rate_limited' }, { status: 429, headers: { 'Retry-After': '1' } })
      : Response.json(answer({ cover: noCover })))
    fillDetails(store, request, shelf('p', 3))
    await vi.waitFor(() => expect(store.saved.size).toBe(2))
    expect(store.prefs.get('detailsPausedUntil')).toBeGreaterThan(Date.now())

    const reloaded = memoryStore(store.prefs)
    const after = vi.fn(async () => Response.json(answer({ cover: noCover })))
    fillDetails(reloaded, after, shelf('q', 1))
    await settle()
    expect(request).toHaveBeenCalledTimes(2)
    expect(after).not.toHaveBeenCalled()

    await vi.waitFor(() => expect(store.saved.get('p2')?.answer).toBeDefined(), { timeout: 3000 })
    await vi.waitFor(() => expect(reloaded.saved.get('q0')?.answer).toBeDefined(), { timeout: 3000 })
  })

  it('stops when cancelled, and a 409 stops it without marking any book', async () => {
    const store = memoryStore()
    const slow = gate()
    const request = vi.fn(async () => {
      await slow.wait
      return Response.json(answer({ cover: noCover }))
    })
    const stop = fillDetails(store, request, shelf('c', 5))
    await vi.waitFor(() => expect(request).toHaveBeenCalledTimes(2))
    stop()
    slow.open()
    await vi.waitFor(() => expect(store.saved.size).toBe(2))
    await settle()
    expect(request).toHaveBeenCalledTimes(2)

    const other = memoryStore()
    const refusing = vi.fn(async () => Response.json({ detail: 'ai_not_opted_in' }, { status: 409 }))
    fillDetails(other, refusing, shelf('n', 5))
    await vi.waitFor(() => expect(refusing).toHaveBeenCalledTimes(2))
    await settle()
    fillDetails(other, refusing, shelf('n', 5))
    await settle()
    expect(refusing).toHaveBeenCalledTimes(2)
    expect(other.setDetails).not.toHaveBeenCalled()
  })

  it('asks an unfinished tidy again after a day, and a spent allowance pauses the pass for the day', async () => {
    for (const tidy of ['quota_exceeded', 'failed'] as const) {
      const details = (await fetchDetails(memoryStore(), vi.fn(async () => Response.json(answer({ tidy, cover: noCover }))), messy))!
      expect(details.retry_at! - details.attempted_at, tidy).toBeCloseTo(86_400_000, -3)
    }
    const done = await fetchDetails(memoryStore(), vi.fn(async () => Response.json(answer({ tidy: 'not_needed', cover: noCover }))), messy)
    expect(done?.retry_at).toBeUndefined()

    const store = memoryStore()
    const spent = vi.fn(async () => Response.json(answer({ tidy: 'quota_exceeded', cover: noCover })))
    fillDetails(store, spent, shelf('s', 4))
    await vi.waitFor(() => expect(store.saved.size).toBe(2))
    await settle()
    expect(spent).toHaveBeenCalledTimes(2)
    expect(store.prefs.get('detailsPausedUntil')).toBeGreaterThan(Date.now() + 86_000_000)
  })

  it(`looks up at most ${dailyLookups} books a day on its own, not counting the open book`, async () => {
    const store = memoryStore()
    const request = vi.fn(async () => Response.json(answer({ cover: noCover })))
    const books = shelf('d', 30)
    fillDetails(store, request, books)
    await vi.waitFor(() => expect(store.saved.size).toBe(25))
    await settle()
    expect(request).toHaveBeenCalledTimes(25)
    expect(store.prefs.get('detailsLookupsToday')).toEqual({ date: new Date().toDateString(), count: 25 })

    fillDetails(store, request, [books[29]], true)
    await vi.waitFor(() => expect(store.saved.has('d29')).toBe(true))

    const reloaded = memoryStore(store.prefs)
    fillDetails(reloaded, request, books.slice(25, 27))
    await settle()
    expect(request).toHaveBeenCalledTimes(26)
    const nextDay = memoryStore(new Map([['detailsLookupsToday', { date: 'Mon Jan 01 2001', count: 25 }]]))
    fillDetails(nextDay, request, books.slice(25, 27))
    await vi.waitFor(() => expect(request).toHaveBeenCalledTimes(28))
  })
})

describe('a cover being painted', () => {
  const drawn = answer().cover
  const painted = { ...drawn, url: `/details/v1/covers/${'c'.repeat(64)}?v=f2`, painted: true }

  it('is looked up again in an hour, a real lookup, and the new artwork replaces the drawn cover', async () => {
    const store = memoryStore()
    let lookups = 0
    const request = vi.fn(async (path: string) => {
      if (path === '/details/v1/lookup') return Response.json(answer({ cover: ++lookups === 1 ? { ...drawn, painting: true } : painted }))
      return path === painted.url ? new Response(new Blob(['jpg'], { type: 'image/jpeg' })) : png()
    })
    const first = (await fetchDetails(store, request, messy))!
    expect(first.retry_at! - first.attempted_at).toBeCloseTo(3_600_000, -3)
    expect(wantsLookup({ ...messy, details: first }, first.retry_at! - 1)).toBe(false)
    expect(wantsLookup({ ...messy, details: first }, first.retry_at!)).toBe(true)

    const second = (await fetchDetails(store, request, { ...messy, details: first }))!
    expect(lookups).toBe(2)
    expect(second).toMatchObject({ cover_url: painted.url, retry_at: undefined })
    expect(await store.covers.get('hash-1')?.text()).toBe('jpg')
    expect(foundCover({ ...messy, details: second })).toBe(painted.url)
  })

  it('keeps showing the drawn cover until the painted one downloads', async () => {
    const store = memoryStore()
    let downloads = 0
    const request = vi.fn(async (path: string) => {
      if (path === '/details/v1/lookup') return Response.json(answer({ cover: painted }))
      return ++downloads === 1 ? new Response(null, { status: 503 }) : new Response(new Blob(['jpg'], { type: 'image/jpeg' }))
    })
    const drawnRecord = { attempted_at: 0, answer: answer({ cover: { ...drawn, painting: true } }), cover_url: drawn.url! }
    const failed = (await fetchDetails(store, request, { ...messy, details: drawnRecord }))!
    expect(failed).toMatchObject({ cover_url: drawn.url, answer: { cover: painted } })
    expect(failed.retry_at).toBeDefined()
    expect(store.covers.has('hash-1')).toBe(false)
    expect(foundCover({ ...messy, details: failed })).toBe(drawn.url)

    const fetched = (await fetchDetails(store, request, { ...messy, details: failed }))!
    expect(fetched.cover_url).toBe(painted.url)
    expect(await store.covers.get('hash-1')?.text()).toBe('jpg')
  })

  it(`stops asking after ${paintingChecks} answers that it is still painting`, async () => {
    const store = memoryStore()
    const request = vi.fn(async (path: string) =>
      path === '/details/v1/lookup' ? Response.json(answer({ cover: { ...drawn, painting: true } })) : png())
    let details: BookDetails | undefined
    for (let check = 1; check <= paintingChecks; check++) {
      details = await fetchDetails(store, request, { ...messy, details })
      expect(details?.retry_at, `check ${check}`).toBeDefined()
    }
    details = await fetchDetails(store, request, { ...messy, details })
    expect(details?.retry_at).toBeUndefined()
    expect(request.mock.calls.filter(([path]) => path === '/details/v1/lookup')).toHaveLength(paintingChecks + 1)
  })

  it('asking again does not count against the shelf\'s daily lookups', async () => {
    const store = memoryStore(new Map([['detailsLookupsToday', { date: new Date().toDateString(), count: dailyLookups }]]))
    const request = vi.fn(async () => Response.json(answer({ cover: noCover })))
    const waiting = { attempted_at: 0, retry_at: 1, answer: answer({ cover: { ...drawn, painting: true } }), painting_checks: 1 }
    fillDetails(store, request, [{ ...messy, details: waiting }, { ...messy, key: 'new', content_hash: 'new' }])
    await vi.waitFor(() => expect(store.saved.has('hash-1')).toBe(true))
    await settle()
    expect(request).toHaveBeenCalledTimes(1)
    expect(store.saved.has('new')).toBe(false)
  })
})

describe('stored details', () => {
  it('keep the answer and cover offline, drop them with the book, and never replace its own cover', async () => {
    const replica = await openReplica(crypto.randomUUID())
    replicas.push(replica)
    await replica.putBook({ key: 'own', title: 'dune_(retail)', cover: new Blob(['own'], { type: 'image/jpeg' }) })
    await replica.putBook({ key: 'bare', title: 'dune_(retail)' })
    const drawn = new Blob(['png'], { type: 'image/png' })
    const record = { attempted_at: 1, answer: answer(), cover_url: answer().cover.url! }
    await replica.setDetails('own', record, drawn)
    await replica.setDetails('bare', record, drawn)
    const books = (await replica.snapshot()).books
    const own = books.find((book) => book.key === 'own')!
    const bare = books.find((book) => book.key === 'bare')!
    expect(bare.details).toEqual(record)
    expect(await (await replica.getFoundCover('bare'))?.text()).toBe('png')
    expect(withDetails(bare)).toMatchObject({ title: 'Dune', authors: ['Frank Herbert'], description: 'A desert planet.', cover: undefined })
    expect(foundCover(bare)).toBe(record.cover_url)
    expect(foundCover(own)).toBeUndefined()
    expect(foundCover({ ...bare, details: { ...record, original: true } })).toBeUndefined()
    expect(withDetails({ ...bare, details: { ...record, original: true } })).toMatchObject({ title: 'dune_(retail)' })

    // Choosing the original keeps the image; a record without a cover drops it.
    await replica.setDetails('bare', { ...record, original: true })
    expect(await replica.getFoundCover('bare')).toBeDefined()
    await replica.setDetails('own', { attempted_at: 2 })
    expect(await replica.getFoundCover('own')).toBeUndefined()

    await replica.removeBook('bare')
    await replica.putBook({ key: 'bare', title: 'dune_(retail)' })
    expect((await replica.snapshot()).books.find((book) => book.key === 'bare')?.details).toBeUndefined()
    expect(await replica.getFoundCover('bare')).toBeUndefined()
  })
})

describe('improving details on request', () => {
  const t = (key: string) => key
  const drawnUrl = `/details/v1/covers/${'c'.repeat(64)}?v=e1`
  /** What the reader sees of a book: its details as shown and its found cover. */
  const seen = (details?: BookDetails) => {
    const { details: _, ...shown } = withDetails({ ...messy, details })
    return { ...shown, cover: foundCover({ ...messy, details }) }
  }

  it('keeps only the accepted improvements, and a field the book changed since shows the book\'s value', async () => {
    const store = memoryStore()
    const proposed = answer({
      book: { ...answer().book, title: 'Dune', description: 'A desert planet.' },
      origins: { title: 'ai', authors: 'ai', description: 'ai_written' },
      cover: noCover,
    })
    const request = vi.fn(async () => Response.json(proposed))
    const proposal = await proposeDetails(store, request, messy)
    const details = (await proposal.apply(['title', 'description'], 'keep', await proposal.cover('keep')))!
    expect(details.basis).toEqual(detailsBody(messy).book)
    const book = { ...messy, details }
    expect(withDetails(book)).toMatchObject({ title: 'Dune', authors: ['Herbert, Frank'], description: 'A desert planet.' })
    expect(originLabel(book, 'title', t)).toBe('Tidied by AI')
    expect(originLabel(book, 'authors', t)).toBeNull()

    // The catalog renamed the book: its own title shows, not the stored answer's.
    const renamed = { ...book, title: 'Dune (Deluxe Edition)' }
    expect(withDetails(renamed)).toMatchObject({ title: 'Dune (Deluxe Edition)', description: 'A desert planet.' })
    expect(originLabel(renamed, 'title', t)).toBeNull()
  })

  it('a chosen cover replaces the book\'s own until the reader picks the original, and later lookups ask for it again', async () => {
    const store = memoryStore()
    const bodies: { path: string; body: Record<string, unknown> }[] = []
    const request = vi.fn(async (path: string, init?: RequestInit) => {
      if (init?.body) bodies.push({ path, body: JSON.parse(String(init.body)) })
      if (path === '/details/v1/tidy') return Response.json(answer({ cover: { source: 'file', generated: false }, origins: { cover: 'file' } }))
      if (path === '/details/v1/lookup') return Response.json(answer({ cover: { source: 'generated', url: drawnUrl, generated: true } }))
      return png()
    })
    const proposal = await proposeDetails(store, request, complete)
    expect(bodies[0]).toEqual({ path: '/details/v1/tidy', body: expect.not.objectContaining({ cover_choice: expect.anything() }) })
    const drawn = await proposal.cover('draw')
    expect(bodies[1]).toMatchObject({ path: '/details/v1/lookup', body: { cover_choice: 'draw', cover: 'file' } })
    const details = (await proposal.apply(['title'], 'draw', drawn))!
    expect(details).toMatchObject({ cover_choice: 'draw', cover_url: drawnUrl, answer: { cover: { source: 'generated' }, origins: { cover: 'ai' } } })
    expect(await store.covers.get('hash-2')?.text()).toBe('png')
    const book = { ...complete, details }
    expect(foundCover(book)).toBe(drawnUrl)
    expect(foundCover({ ...book, details: { ...details, original: true } })).toBeUndefined()
    expect(detailsBody(book)).toMatchObject({ cover_choice: 'draw' })
  })

  it('a painting is asked for only on apply, and checked again in an hour', async () => {
    const store = memoryStore()
    const choices: unknown[] = []
    const request = vi.fn(async (path: string, init?: RequestInit) => {
      const body = init?.body ? JSON.parse(String(init.body)) : undefined
      if (body) choices.push(body.cover_choice)
      if (path === '/details/v1/tidy') return Response.json(answer({ cover: { source: 'file', generated: false } }))
      if (path === '/details/v1/lookup') {
        return Response.json(answer({ cover: { source: 'generated', url: drawnUrl, generated: true, painting: body.cover_choice === 'paint' } }))
      }
      return png()
    })
    const proposal = await proposeDetails(store, request, complete)
    const preview = await proposal.cover('paint')
    expect(choices).toEqual([undefined, 'draw'])
    const details = (await proposal.apply([], 'paint', preview))!
    expect(choices).toEqual([undefined, 'draw', 'paint'])
    // The drawn image is not downloaded twice.
    expect(request.mock.calls.filter(([path]) => path === drawnUrl)).toHaveLength(1)
    expect(details).toMatchObject({ cover_choice: 'paint', painting_checks: 1, cover_url: drawnUrl, accepted: [] })
    expect(details.retry_at! - details.attempted_at).toBeCloseTo(3_600_000, -3)
    expect(withDetails({ ...complete, details })).toMatchObject({ title: 'Dune', description: 'Spice.' })
  })

  it('a server that stopped painting keeps the drawn cover and does not stop the shelf', async () => {
    const store = memoryStore()
    const request = vi.fn(async (_path: string, init?: RequestInit) => JSON.parse(String(init?.body)).cover_choice === 'paint'
      ? Response.json({ detail: 'cover_art_unavailable' }, { status: 409 })
      : Response.json({ detail: 'busy' }, { status: 503 }))
    const waiting: BookDetails = {
      attempted_at: 0, retry_at: 1, answer: answer({ cover: { source: 'generated', url: drawnUrl, generated: true, painting: true } }),
      cover_url: drawnUrl, painting_checks: 1, cover_choice: 'paint',
    }
    fillDetails(store, request, [{ ...complete, details: waiting }, { ...messy, key: 'next', content_hash: 'next' }])
    await vi.waitFor(() => expect(store.saved.get('next')?.retry_at).toBeDefined())
    expect(store.saved.get('hash-2')).toMatchObject({ cover_choice: 'draw', cover_url: drawnUrl, retry_at: undefined })
  })

  it('closing a preview keeps nothing and lets the shelf go on', async () => {
    const store = memoryStore()
    const request = vi.fn(async () => Response.json(answer({ cover: noCover })))
    const proposal = await proposeDetails(store, request, messy)
    fillDetails(store, request, [{ ...messy, key: 'other', content_hash: 'other' }])
    await settle()
    expect(store.saved.size).toBe(0)
    proposal.close()
    await vi.waitFor(() => expect(store.saved.has('other')).toBe(true))
    // Only the rejection is remembered, and a book with no answer yet is still looked up.
    const rejected = store.saved.get('hash-1')!
    expect(rejected).toMatchObject({ declined: 'k'.repeat(64) })
    expect(rejected.answer).toBeUndefined()
    expect(wantsLookup({ ...messy, details: rejected }, Date.now())).toBe(true)
  })

  it('a cover left unticked keeps the cover shown now, the book\'s own or the one found before', async () => {
    const found: BookDetails = { attempted_at: 0, answer: answer(), cover_url: drawnUrl }
    for (const book of [complete, { ...complete, details: found }, { ...messy, details: found }]) {
      const store = memoryStore()
      const request = vi.fn(async () => Response.json(answer({ cover: { source: 'generated', url: `${drawnUrl}2`, generated: true } })))
      const details = (await (await proposeDetails(store, request, book)).apply(['title'], 'keep', null))!
      expect(foundCover({ ...book, details })).toBe(book.cover ? undefined : drawnUrl)
      expect(withDetails({ ...book, details }).title).toBe('Dune')
    }
  })

  it('a rejected record served again by a later lookup shows none of its improvements', async () => {
    const store = memoryStore()
    const request = vi.fn(async (path: string) => Response.json(answer({ cover: noCover, cached: path.endsWith('lookup') })))
    const proposal = await proposeDetails(store, request, messy)
    proposal.close()
    await vi.waitFor(() => expect(store.saved.get('hash-1')?.declined).toBeDefined())
    const details = (await fetchDetails(store, request, { ...messy, details: store.saved.get('hash-1') }))!
    expect(details.accepted).toEqual([])
    expect(withDetails({ ...messy, details })).toMatchObject({ title: messy.title, authors: messy.authors })
    expect(originLabel({ ...messy, details }, 'title', t)).toBeNull()
  })

  it('cancelling a tidy that returned the record already shown keeps every detail, now and on the next lookup', async () => {
    const store = memoryStore()
    const shown: BookDetails = { attempted_at: 0, answer: answer(), cover_url: drawnUrl, basis: detailsBody(messy).book }
    const book = { ...messy, details: shown }
    const before = seen(shown)
    const request = vi.fn(async () => Response.json(answer({ cached: true })))
    const proposal = await proposeDetails(store, request, book)
    proposal.close()
    await settle()
    const after = store.saved.get('hash-1') ?? shown
    expect(seen(after)).toEqual(before)
    // A re-check served the same record, as a missing cover or a painting would ask for.
    const details = (await fetchDetails(store, request, { ...messy, details: after }))!
    expect(seen(details)).toEqual(before)
  })

  it('a tidy closed with nothing to change records no rejection', async () => {
    const store = memoryStore()
    const book = { ...messy, details: { attempted_at: 0, answer: answer({ key: 'a'.repeat(64) }), cover_url: drawnUrl } }
    const proposal = await proposeDetails(store, vi.fn(async () => Response.json(answer())), book)
    proposal.close(false)
    await settle()
    expect(store.saved.get('hash-1')?.declined).toBeUndefined()
  })

  it('a rejected record served by a later lookup, as a quota retry is, keeps the details shown before and takes only its cover', async () => {
    const store = memoryStore()
    const earlier = answer({ key: 'a'.repeat(64), book: { ...answer().book, title: 'Dune (found)' }, cover: noCover, origins: { title: 'openlibrary' }, tidy: 'quota_exceeded' })
    const book = { ...messy, details: { attempted_at: 0, answer: earlier, accepted: ['title' as const], basis: detailsBody(messy).book, declined: 'k'.repeat(64) } }
    const before = seen(book.details)
    const request = vi.fn(async (path: string) => path === '/details/v1/lookup' ? Response.json(answer({ cached: true })) : png())
    const details = (await fetchDetails(store, request, book))!
    expect(seen(details)).toEqual({ ...before, cover: drawnUrl })
    expect(details).toMatchObject({ accepted: ['title'], retry_at: undefined, answer: { tidy: 'done' } })
    expect(foundCover({ ...messy, details })).toBe(drawnUrl)
    expect(originLabel({ ...messy, details }, 'cover', t)).toBe('Tidied by AI')
  })

  it('a lookup queued before the preview opened neither overwrites what the reader applied nor brings back what they rejected', async () => {
    for (const reader of ['applies', 'rejects'] as const) {
      const store = memoryStore()
      const held: (() => void)[] = []
      const request = vi.fn(async (path: string) => {
        if (path !== '/details/v1/lookup') return Response.json(answer({ cover: noCover }))
        // A busy shelf: the first two lookups wait, so the open book stays queued.
        if (held.length < 2) await new Promise<void>((resolve) => held.push(resolve))
        return Response.json(answer({ cover: noCover, cached: true }))
      })
      fillDetails(store, request, shelf('o', 2))
      await vi.waitFor(() => expect(held).toHaveLength(2))
      fillDetails(store, request, [messy], true)
      await settle()
      const proposal = await proposeDetails(store, request, messy)
      held.forEach((open) => open())
      if (reader === 'applies') await proposal.apply([], 'keep', await proposal.cover('keep'))
      else proposal.close()
      await vi.waitFor(() => expect(store.saved.has('o1')).toBe(true))
      await settle()
      const after = store.saved.get('hash-1')!
      expect(after.answer && after.accepted).toEqual(reader === 'applies' ? [] : undefined)
      expect(withDetails({ ...messy, details: after }).title).toBe(messy.title)
      expect(request.mock.calls.filter(([path]) => path === '/details/v1/lookup')).toHaveLength(2)
    }
  })

  it('a record served again keeps the fields it was asked for, so a catalog edit still shows', async () => {
    const store = memoryStore()
    const first = (await fetchDetails(store, vi.fn(async () => Response.json(answer({ cover: noCover }))), messy))!
    const renamed = { ...messy, title: 'Dune (Deluxe Edition)', details: first }
    const again = (await fetchDetails(store, vi.fn(async () => Response.json(answer({ cover: noCover, cached: true }))), renamed))!
    expect(again.basis).toEqual(first.basis)
    expect(withDetails({ ...renamed, details: again }).title).toBe('Dune (Deluxe Edition)')
  })

  it('a server that takes no cover choice gets the choice once, then never again', async () => {
    const store = memoryStore()
    const request = vi.fn(async () => Response.json({ detail: [{ msg: 'extra' }] }, { status: 422 }))
    // A painting being checked again: a real lookup, with the choice.
    const painting = answer({ cover: { source: 'generated', url: drawnUrl, generated: true, painting: true } })
    const chosen: BookDetails = { attempted_at: 0, retry_at: 1, answer: painting, cover_url: drawnUrl, painting_checks: 1, cover_choice: 'paint' }
    await expect(fetchDetails(store, request, { ...complete, details: chosen })).rejects.toThrow()
    const after = store.saved.get('hash-2')!
    expect(after).toMatchObject({ cover_url: drawnUrl, retry_at: undefined })
    expect(after.cover_choice).toBeUndefined()
    expect(detailsBody({ ...complete, details: after })).not.toHaveProperty('cover_choice')
  })
})
