// @vitest-environment node
import 'fake-indexeddb/auto'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { EditionConflict, openReplica, type Highlight, type LocalBook, type Progress, type Replica, type SyncTransport } from './index'
import { compareTimestamps } from './wire'

const book = { key: 'abc123', metadata_id: '9780553293357', title: 'Fóundation', description: 'Local EPUB metadata' } satisfies LocalBook
const document = { content_hash: book.key, metadata_id: book.metadata_id, identity_hash_version: 1 }
const highlight: Highlight = {
  id: '14300000-0000-4000-8000-000000000001', document, locator: '  { "href": "café.xhtml" }\n',
  text: '“Café ☺ — 读书”', colour: 'YELLOW', book_title: book.title, created_at: 1000, updated_at: 1001, comment: 'Café',
}
const progress: Progress = { document, locator: '{"href":"café.xhtml","unknown":42}', percent: 0.57, client_updated_at: '2026-05-01T18:00:00.000Z' }
const remote = { ...book, content_hash: book.key, description: undefined, updated_at: '2026-06-01T00:00:00Z', deleted_at: null }

const replicas: Replica[] = []
let namespace: string
let lockTails: Map<string, Promise<unknown>>

beforeEach(() => {
  namespace = crypto.randomUUID()
  lockTails = new Map()
  vi.stubGlobal('navigator', {
    locks: { request: (name: string, { signal }: { signal: AbortSignal }, task: () => Promise<unknown>) => {
      const tail = lockTails.get(name) ?? Promise.resolve()
      const next = tail.catch(() => {}).then(() => { signal.throwIfAborted(); return task() })
      lockTails.set(name, next)
      return next
    } },
  })
})

afterEach(() => {
  for (const replica of replicas.splice(0)) replica.close()
  vi.unstubAllGlobals()
})

async function open(transport?: SyncTransport, scope = namespace, syncEnabled?: boolean) {
  const replica = await openReplica(scope, transport, { syncEnabled })
  replicas.push(replica)
  return replica
}

function emptyPull(path: string): Response {
  if (path.startsWith('/sync/v1/highlights?')) return Response.json({ items: [], next_cursor: '0', has_more: false })
  return Response.json({ items: [] })
}

describe('offline replica', () => {
  it('does not expose a new book or queue entry if the EPUB write fails', async () => {
    const original = IDBObjectStore.prototype.put
    const put = vi.spyOn(IDBObjectStore.prototype, 'put').mockImplementation(function (
      this: IDBObjectStore, value, key,
    ) {
      if (this.name === 'epubs') throw new DOMException('Quota exceeded', 'QuotaExceededError')
      return original.call(this, value, key)
    })
    try {
      const replica = await open(undefined, namespace, true)
      await expect(replica.putBook(book, new Blob(['EPUB']))).rejects.toMatchObject({ name: 'QuotaExceededError' })
      expect(await replica.snapshot()).toMatchObject({ books: [], pending: 0 })
      expect(await replica.getEpub(book.key)).toBeUndefined()
    } finally {
      put.mockRestore()
    }
  })

  it('keeps local EPUBs, annotations and preferences in their own account across reopen', async () => {
    const first = await open()
    await first.putBook(book, new Blob(['epub bytes']))
    await first.putProgress(progress)
    await first.putHighlight(highlight)
    await first.setBookmark(book.key, { note: 'private' })
    await first.setPreference('reader', 'paper')
    first.close()

    const same = await open()
    const other = await open(undefined, namespace + ':someone-else')
    expect(await same.snapshot()).toMatchObject({
      books: [{ key: book.key, hasDownload: true, localOnly: true }], progress: [progress], highlights: [highlight], pending: 0,
    })
    expect(await (await same.getEpub(book.key))?.text()).toBe('epub bytes')
    expect(await same.getPreference('reader')).toBe('paper')
    expect(await other.snapshot()).toMatchObject({ books: [], progress: [], highlights: [], pending: 0 })
    expect(await other.getBookmark(book.key)).toBeUndefined()
    await same.removeDownload(book.key)
    expect(await same.snapshot()).toMatchObject({ books: [{ hasDownload: false }], highlights: [highlight] })
    // The file goes; the pause bookmark and its note wait for the same file to come back.
    expect(await same.getBookmark(book.key)).toEqual({ note: 'private' })
    await same.removeBook(book.key)
    expect(await same.getBookmark(book.key)).toBeUndefined()
  })

  it('persists a timed-out outgoing edit and reconciles a stale reply through the full progress snapshot', async () => {
    let requestCount = 0
    const timestamps: string[] = []
    const winner = { ...progress, percent: 0.81, client_updated_at: '2026-05-01T18:00:00.000004Z', finished_at: '2026-05-01T18:00:00Z' }
    const api: SyncTransport = async (path, init) => {
      if (init?.method === 'POST') {
        timestamps.push(JSON.parse(String(init.body)).items[0].client_updated_at)
        if (++requestCount === 1) throw new TypeError('Offline after commit')
        return Response.json({ results: [{ document, status: 'stale', server_client_updated_at: winner.client_updated_at }] })
      }
      if (path.startsWith('/sync/v1/progress?')) return Response.json({ items: [winner] })
      return emptyPull(path)
    }
    const first = await open(api)
    await first.putProgress(progress)
    await expect(first.sync()).rejects.toThrow('Offline after commit')
    expect((await first.snapshot()).pending).toBe(1)
    first.close()
    const second = await open(api)
    await second.sync()
    expect(timestamps).toEqual([progress.client_updated_at, progress.client_updated_at])
    expect(await second.snapshot()).toMatchObject({ pending: 0, progress: [winner] })
    expect(compareTimestamps(progress.client_updated_at, winner.client_updated_at)).toBeLessThan(0)
  })

  it('queues edits in a remembered offline account with no live transport', async () => {
    const offline = await open(undefined, namespace, true)
    await offline.putBook(book, new Blob(['book']))
    await offline.putHighlight(highlight)
    await offline.setBookmark(book.key, 'private memory note')
    expect(await offline.snapshot()).toMatchObject({
      books: [{ key: book.key, localOnly: false, hasDownload: true }], highlights: [highlight], pending: 2,
    })
    offline.close()
    const api: SyncTransport = async (path, init) => {
      expect(String(init?.body ?? '')).not.toContain('private memory note')
      if (path === '/sync/v1/highlights') return Response.json({ results: [{ item: highlight }] })
      if (path === '/library/v1/sync') return Response.json({ processed: 1 })
      if (path.startsWith('/library/v1/items')) return Response.json({ items: [remote] })
      return emptyPull(path)
    }
    const online = await open(api)
    await online.sync()
    expect(await online.snapshot()).toMatchObject({
      books: [{ key: book.key, synced: true }], highlights: [highlight], pending: 0,
    })
  })

  it('acknowledges the exact sent highlight without discarding a second tab edit', async () => {
    let respond!: (response: Response) => void
    let notifySent!: () => void
    const sent = new Promise<void>((resolve) => { notifySent = resolve })
    const response = new Promise<Response>((resolve) => { respond = resolve })
    const api: SyncTransport = async (path, init) => {
      if (init?.method === 'POST') {
        notifySent()
        return response
      }
      return emptyPull(path)
    }
    const first = await open(api)
    const second = await open(api)
    await first.putHighlight(highlight)
    const sync = first.sync()
    await sent
    await second.putHighlight({ ...highlight, comment: null })
    respond(Response.json({ results: [{ status: 'accepted', item: highlight }] }))
    await sync
    expect(await first.snapshot()).toMatchObject({ pending: 1, highlights: [{ comment: null }] })
  })

  it('retains a comment when an old server acknowledges the quote without it', async () => {
    const { comment, ...legacy } = highlight
    const replica = await open(async (path, init) => init?.method === 'POST'
      ? Response.json({ results: [{ item: legacy }] }) : emptyPull(path))
    await replica.putHighlight(highlight)
    await expect(replica.sync()).rejects.toMatchObject({ code: 'highlight_comments_unsupported' })
    expect(await replica.snapshot()).toMatchObject({ pending: 1, highlights: [{ comment }] })
  })

  it('merges page cursors across a reset and retains unacknowledged edits in the rebuilt replica', async () => {
    let reset = false
    const seenCursors: string[] = []
    const extra = { ...highlight, id: '14300000-0000-4000-8000-000000000002' }
    const api: SyncTransport = async (path, init) => {
      if (init?.method === 'POST') return Response.json({ detail: 'highlight_write_unavailable' }, { status: 503 })
      if (!path.startsWith('/sync/v1/highlights?')) return emptyPull(path)
      const cursor = new URL(path, 'https://quire.test').searchParams.get('since')!
      seenCursors.push(cursor)
      if (!reset) return Response.json({ items: [highlight], next_cursor: '15', has_more: false })
      if (cursor === '15') return Response.json({ detail: 'highlight_cursor_reset_required' }, { status: 409 })
      if (cursor === '0') return Response.json({ items: [extra], next_cursor: '1', has_more: true })
      return Response.json({ items: [{ ...extra, deleted_at: 1001 }], next_cursor: '2', has_more: false })
    }
    const replica = await open(api)
    await replica.sync()
    reset = true
    const pending = { ...extra, id: '14300000-0000-4000-8000-000000000003', comment: 'Offline annotation' }
    await replica.putHighlight(pending)
    await expect(replica.sync()).rejects.toMatchObject({ status: 503 })
    expect(seenCursors).toEqual(['0', '15', '0', '1'])
    expect(await replica.snapshot()).toMatchObject({ pending: 1, highlights: [pending] })
  })

  it('mirrors a remote book, joins a matching offline copy, and handles conflicting editions and removal', async () => {
    let row: typeof remote | Record<string, unknown> = remote
    const writes: unknown[] = []
    const api: SyncTransport = async (path, init) => {
      if (path.startsWith('/library/v1/items')) return Response.json({ items: [row] })
      if (path === '/library/v1/sync') {
        const item = JSON.parse(String(init?.body)).items[0]
        writes.push(item)
        if (item.status === 'deleted') row = { ...remote, updated_at: '2026-06-02T00:00:00Z', deleted_at: '2026-06-02T00:00:00Z' }
        return Response.json({ processed: 1 })
      }
      return emptyPull(path)
    }
    const replica = await open(api)
    await replica.sync()
    expect((await replica.snapshot()).books).toMatchObject([{ key: book.key, hasDownload: false, synced: true }])
    await expect(replica.putBook({ ...book, key: 'different' }, new Blob(['bytes']))).rejects.toBeInstanceOf(EditionConflict)
    expect(await replica.getEpub('different')).toBeUndefined()
    await replica.putBook({ ...book, key: 'different' }, new Blob(['different']), { localOnly: true })
    await replica.putBook({ ...book, cover: new Blob(['cover']) }, new Blob(['bytes']), { matchKey: book.key })
    await replica.setBookmark(book.key, { note: 'do not upload' })
    await replica.sync()
    expect(writes[0]).toEqual({
      identity_hash: book.key, identity_hash_version: 1, status: 'present', last_seen_at: expect.any(String),
      title: book.title, metadata_id: book.metadata_id, authors: [],
    })
    await replica.removeBook(book.key)
    await replica.sync()
    expect(writes[1]).toMatchObject({ status: 'deleted', identity_hash: book.key })
    expect((await replica.snapshot()).books).toMatchObject([{ key: 'different', hasDownload: true, localOnly: true }])
  })

  it('keeps a catalog cover for a synced book until its own EPUB brings one', async () => {
    const api: SyncTransport = async (path) => path.startsWith('/library/v1/items') ? Response.json({ items: [remote] }) : emptyPull(path)
    const replica = await open(api)
    await replica.sync()
    await replica.setCatalogCover(book.key, new Blob(['catalog'], { type: 'image/jpeg' }))
    const shown = async () => (await (await open(api)).snapshot()).books[0].cover as Blob
    expect(await (await shown()).text()).toBe('catalog')
    await replica.putBook({ ...book, cover: new Blob(['epub'], { type: 'image/png' }) }, new Blob(['epub bytes']))
    expect(await (await shown()).text()).toBe('epub')
  })

  it('reuses the durable library idempotency key and request body after reopening', async () => {
    const attempts: Array<{ key: string | null; body: BodyInit | null | undefined }> = []
    const api: SyncTransport = async (path, init) => {
      if (path === '/library/v1/sync') {
        attempts.push({ key: new Headers(init?.headers).get('Idempotency-Key'), body: init?.body })
        if (attempts.length === 1) throw new TypeError('Lost response')
        return Response.json({ processed: 1 })
      }
      expect(new Headers(init?.headers).has('Idempotency-Key')).toBe(false)
      return emptyPull(path)
    }
    const first = await open(api)
    await first.putBook(book)
    await expect(first.sync()).rejects.toThrow('Lost response')
    first.close()
    const second = await open(api)
    await second.sync()
    expect(attempts[0].key).toMatch(/^[\da-f-]{36}$/)
    expect(attempts[0]).toEqual(attempts[1])
    expect((await second.snapshot()).pending).toBe(0)
  })

  it('replays library pages so concurrent offset shifts cannot permanently hide a book', async () => {
    const rows = Array.from({ length: 202 }, (_, i) => ({
      ...remote, content_hash: `hash-${i}`, metadata_id: null, title: `Title ${i}`,
    }))
    let shifted = false
    const api: SyncTransport = async (path) => {
      if (!path.startsWith('/library/v1/items')) return emptyPull(path)
      const offset = Number(new URL(path, 'https://quire.test').searchParams.get('offset'))
      const page = rows.slice(offset, offset + 200)
      if (!shifted && offset === 0) {
        shifted = true
        const moved = rows.shift()!
        rows.push({ ...moved, updated_at: '2026-06-02T00:00:00Z' })
      }
      return Response.json({ items: page })
    }
    const replica = await open(api)
    await replica.sync()
    expect((await replica.snapshot()).books).toHaveLength(201)
    await replica.sync()
    expect((await replica.snapshot()).books).toHaveLength(202)
  })

  it.each([false, true])('persists and recovers a rejected edition (reactivating tombstone: %s)', async reactivating => {
    const requests: string[] = []
    const other = { ...remote, content_hash: 'different-edition', deleted_at: reactivating ? remote.updated_at : null }
    let collided = false
    const api: SyncTransport = async (path, init) => {
      if (init?.method !== 'POST') {
        if (path.startsWith('/library/v1/items')) return Response.json({
          items: [...(reactivating ? [{ ...remote, metadata_id: null, deleted_at: remote.updated_at }] : []), ...(collided ? [other] : [])],
        })
        return emptyPull(path)
      }
      requests.push(path)
      collided = true
      return Response.json({ detail: {
        error: 'metadata_id_conflict', incoming_identity_hash: book.key, existing_identity_hash: other.content_hash,
      } }, { status: 409 })
    }
    if (reactivating) await (await open(api)).sync()
    const first = await open(undefined, namespace, true)
    await first.putBook(book, new Blob(['epub']))
    await first.putProgress(progress)
    await first.putHighlight(highlight)
    const connected = await open(api)
    await expect(connected.sync()).rejects.toMatchObject({
      status: 409, code: 'metadata_id_conflict', bookKey: book.key,
    })
    expect(requests).toEqual(['/library/v1/sync'])

    const reopened = await open(api)
    const rejected = await reopened.snapshot()
    expect(rejected.books.find(item => item.key === book.key)).toMatchObject({
      localOnly: false, hasDownload: true, editionConflict: true,
    })
    expect(rejected).toMatchObject({ progress: [progress], highlights: [highlight], pending: 3, readyToSync: false })
    await reopened.sync()
    expect(requests).toEqual(['/library/v1/sync'])

    await reopened.keepLocalEdition(book.key)
    const resolved = await reopened.snapshot()
    expect(resolved.books.find(item => item.key === book.key)).toMatchObject({
      localOnly: true, hasDownload: true, editionConflict: false,
    })
    expect(resolved.books.some(item => item.key === other.content_hash)).toBe(!reactivating)
    expect(resolved).toMatchObject({ progress: [progress], highlights: [highlight], pending: 0, readyToSync: false })
    await reopened.removeDownload(book.key)
    await reopened.putBook(book, new Blob(['epub']), { matchKey: book.key })
    expect(await reopened.snapshot()).toMatchObject({ progress: [progress], highlights: [highlight], pending: 0 })
    await reopened.sync()
    expect(requests).toEqual(['/library/v1/sync'])
  })

  it('stops after a rejected session and preserves the old account queue', async () => {
    const transport = vi.fn<SyncTransport>().mockResolvedValue(Response.json({ detail: 'account_mismatch' }, { status: 401 }))
    const first = await open(transport)
    await first.putHighlight(highlight)
    await first.putProgress(progress)
    await expect(first.sync()).rejects.toMatchObject({ status: 401 })
    expect(transport).toHaveBeenCalledTimes(1)
    expect((await first.snapshot()).pending).toBe(2)
    first.close()
    await expect(first.snapshot()).rejects.toThrow()
    const second = await open(undefined, 'replacement-' + namespace)
    expect(await second.snapshot()).toMatchObject({ highlights: [], pending: 0 })
  })
})

it('copies guest reading state atomically without replacing newer connected edits',async()=>{
  const connected=await open(undefined,namespace,true)
  await connected.putProgress({...progress,percent:0.9,client_updated_at:'2026-08-01T10:00:00Z'})
  await connected.putBook(book,new Blob(['epub']),{reading:{progress,highlights:[highlight]}})
  const snapshot=await connected.snapshot()
  expect(snapshot.progress[0].percent).toBe(0.9)
  expect(snapshot.highlights[0].id).toBe(highlight.id)
  expect(snapshot.pending).toBe(3)
})
