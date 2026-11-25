import { clearStorage, openStorage, result, transaction, type Store } from './storage'
import { EditionConflict, SyncError, type Highlight, type LocalBook, type Progress, type ReplicaSnapshot, type ShelfBook, type SyncTransport } from './types'
import {
  compareTimestamps, libraryEntry, newerProgress, requestJson,
  type HighlightsPull, type HighlightsPush, type LibraryPull, type LibraryRow,
  type Mutation, type ProgressPull, type ProgressPush, type Queued,
} from './wire'

export * from './types'

type StoredBlob = { bytes: ArrayBuffer; type: string }
type StoredBook = Omit<LocalBook, 'cover'> & { cover?: LocalBook['cover'] | StoredBlob }
type LocalRecord = { book: StoredBook; localOnly: boolean }

async function encodeBlob(blob: Blob): Promise<StoredBlob> {
  return { bytes: await blob.arrayBuffer(), type: blob.type }
}

function decodeBlob(blob: StoredBlob | Blob): Blob {
  return blob instanceof Blob ? blob : new Blob([blob.bytes], { type: blob.type })
}

function decodeBook(book: StoredBook): LocalBook {
  const { cover, ...metadata } = book
  return { ...metadata, cover: cover && typeof cover === 'object' ? decodeBlob(cover) : cover }
}
type ImportedReading = { progress?: Progress; highlights: Highlight[] }
type BookOptions = { localOnly?: boolean; matchKey?: string; reading?: ImportedReading }
type Listener = () => void
type GetStore = (name: Store) => IDBObjectStore
const epoch = '1970-01-01T00:00:00.000Z'
const libraryPageSize = 200
const highlightCursor = 'cursor:highlights'

function blockedEdition(entry: Queued | undefined): boolean {
  return Boolean(entry?.kind === 'library' && !entry.value.deleted && entry.value.editionConflict)
}

export async function openReplica(
  namespace: string, transport?: SyncTransport, options: { syncEnabled?: boolean } = {},
): Promise<Replica> {
  if (!namespace) throw new Error('Storage namespace is required')
  return new Replica(namespace, await openStorage(namespace), transport, options.syncEnabled ?? Boolean(transport))
}

export class Replica {
  private readonly listeners = new Set<Listener>()
  private readonly channel?: BroadcastChannel
  private readonly controller = new AbortController()
  private readonly lockName: string
  private running?: Promise<void>

  constructor(
    namespace: string,
    private readonly db: IDBDatabase,
    private readonly transport: SyncTransport | undefined,
    private readonly syncEnabled: boolean,
  ) {
    this.lockName = `quire-web:sync:${namespace}`
    if (typeof BroadcastChannel !== 'undefined') {
      this.channel = new BroadcastChannel(`quire-web:data:${namespace}`)
      this.channel.onmessage = this.notify
    }
    db.onversionchange = () => this.close()
  }

  private assertActive() {
    this.controller.signal.throwIfAborted()
  }

  private read<T>(names: Store[], action: (store: GetStore) => Promise<T>): Promise<T> {
    this.assertActive()
    return transaction(this.db, names, 'readonly', action)
  }

  private async write<T>(names: Store[], action: (store: GetStore) => Promise<T>): Promise<T> {
    this.assertActive()
    const value = await transaction(this.db, names, 'readwrite', action)
    if (!this.controller.signal.aborted) {
      this.notify()
      this.channel?.postMessage(null)
    }
    return value
  }

  private notify = () => {
    for (const listener of this.listeners) listener()
  }

  subscribe(listener: Listener): () => void {
    this.listeners.add(listener)
    return () => { this.listeners.delete(listener) }
  }

  close(): void {
    this.controller.abort()
    this.listeners.clear()
    this.channel?.close()
    this.db.close()
  }

  async clear(options: { discardPending?: boolean } = {}): Promise<void> {
    this.assertActive()
    await this.exclusive(async () => {
      await clearStorage(this.db, options.discardPending ?? true)
      this.notify()
      this.channel?.postMessage(null)
    })
  }

  private async exclusive(task: () => Promise<void>): Promise<void> {
    const locks = globalThis.navigator?.locks
    if (!locks) {
      if (!this.syncEnabled) return task()
      throw new Error('Cross-tab sync requires the Web Locks API')
    }
    await locks.request(this.lockName, { signal: this.controller.signal }, task)
  }

  async snapshot(): Promise<ReplicaSnapshot> {
    const data = await this.read(
      ['books', 'epubs', 'library', 'progress', 'highlights', 'localProgress', 'localHighlights', 'queue', 'settings'],
      async (store) => {
        const catalogCovers = IDBKeyRange.bound('cover:', 'cover:\uffff')
        const [coverKeys, coverBlobs, local, downloads, library, progress, highlights, localProgress, localHighlights, queue] = await Promise.all([
          result(store('settings').getAllKeys(catalogCovers)),
          result<StoredBlob[]>(store('settings').getAll(catalogCovers)),
          result<LocalRecord[]>(store('books').getAll()),
          result(store('epubs').getAllKeys()),
          result<LibraryRow[]>(store('library').getAll()),
          result<Progress[]>(store('progress').getAll()),
          result<Highlight[]>(store('highlights').getAll()),
          result<Progress[]>(store('localProgress').getAll()),
          result<Highlight[]>(store('localHighlights').getAll()),
          result<Queued[]>(store('queue').getAll()),
        ])
        const covers = new Map(coverKeys.map((key, index) => [String(key).slice('cover:'.length), coverBlobs[index]]))
        return { covers, local, downloads, library, progress, highlights, localProgress, localHighlights, queue }
      },
    )
    this.assertActive()
    const downloads = new Set(data.downloads)
    const library = new Map(data.library.map((row) => [row.content_hash, row]))
    const books = new Map<string, ShelfBook>()
    for (const row of library.values()) {
      if (!row.deleted_at) books.set(row.content_hash, {
        ...row, key: row.content_hash, hasDownload: downloads.has(row.content_hash), synced: true, localOnly: false,
      })
    }
    const pending = new Map(data.queue.filter((entry) => entry.kind === 'library').map((entry) => [entry.value.key, entry.value]))
    for (const { book: stored, localOnly } of data.local) {
      const book = decodeBook(stored)
      const remote = books.get(book.key)
      const mutation = pending.get(book.key)
      if (mutation?.deleted || (!localOnly && !mutation && library.get(book.key)?.deleted_at)) continue
      books.set(book.key, {
        ...remote, ...book,
        opds_href: book.opds_href ?? remote?.opds_href,
        catalog_id: book.catalog_id !== undefined ? book.catalog_id : remote?.catalog_id,
        hasDownload: downloads.has(book.key),
        synced: Boolean(remote),
        localOnly,
        editionConflict: !localOnly && mutation?.deleted === false && mutation.editionConflict === true,
      })
    }
    const blockedKeys = new Set(data.queue.filter(blockedEdition).map(entry => entry.key))
    const progress = new Map([...data.progress, ...data.localProgress].map((row) => [row.document.content_hash, row]))
    const highlights = new Map([...data.highlights, ...data.localHighlights].map((row) => [row.id, row]))
    for (const entry of data.queue) {
      if (entry.kind === 'library' && entry.value.deleted) books.delete(entry.value.key)
      if (entry.kind === 'progress') {
        const { value } = entry
        const key = value.document.content_hash
        const current = progress.get(key)
        if (!current || compareTimestamps(value.client_updated_at, current.client_updated_at) >= 0) progress.set(key, value)
      }
      if (entry.kind === 'highlight') highlights.set(entry.value.id, entry.value)
    }
    for (const [key, cover] of data.covers) {
      const book = books.get(key)
      if (book && !(book.cover instanceof Blob)) book.cover = decodeBlob(cover)
    }
    return {
      books: [...books.values()],
      progress: [...progress.values()],
      highlights: [...highlights.values()].filter((row) => row.deleted_at == null),
      pending: data.queue.length,
      readyToSync: data.queue.some(entry => !blockedKeys.has(
        entry.kind === 'library' ? entry.key : `library:${entry.value.document.content_hash}`,
      )),
    }
  }

  async putBook(book: LocalBook, epub?: Blob, options: BookOptions = {}): Promise<void> {
    if (book.key !== (book.content_hash ?? book.key) || (options.matchKey && options.matchKey !== book.key)) {
      throw new Error('The EPUB identity does not match this library entry')
    }
    if (epub && !epub.size) throw new Error('The EPUB is empty')
    // WebKit cannot persist some imported File/Blob handles directly.
    // Materialize bytes before opening a transaction, then atomically store
    // the complete EPUB and its metadata.
    const [storedEpub, storedCover] = await Promise.all([
      epub && encodeBlob(epub),
      book.cover instanceof Blob ? encodeBlob(book.cover) : book.cover,
    ])
    await this.write(['books', 'epubs', 'library', 'queue', 'localProgress', 'localHighlights', 'progress', 'highlights'], async (store) => {
      const priorBook: LocalRecord | undefined = await result(store('books').get(book.key))
      const localOnly = options.localOnly ?? (priorBook?.localOnly || !this.syncEnabled)
      if (!localOnly && book.metadata_id) {
        const [local, remote] = await Promise.all([
          result<LocalRecord[]>(store('books').getAll()),
          result<LibraryRow[]>(store('library').getAll()),
        ])
        const otherEdition = (candidate: StoredBook) => candidate.key !== book.key && candidate.metadata_id === book.metadata_id
        const existing = local.find((row) => !row.localOnly && otherEdition(row.book))?.book ??
          remote.map((row) => ({ ...row, key: row.content_hash })).find(otherEdition)
        if (existing) throw new EditionConflict(decodeBook(existing))
      }
      await result(store('books').put({ book: { ...book, cover: storedCover }, localOnly } satisfies LocalRecord, book.key))
      if (storedEpub) await result(store('epubs').put(storedEpub, book.key))
      if (localOnly) {
        await this.restoreLocalReading(store, book.key)
      } else {
        // Covers and descriptions are local enrichment and never enter the outbox.
        const { cover: _cover, description: _description, ...mirror } = book
        const prior: Queued | undefined = await result(store('queue').get(`library:${book.key}`))
        const editionConflict = blockedEdition(prior) && prior?.kind === 'library' &&
          !prior.value.deleted && prior.value.book.metadata_id === book.metadata_id
        await this.enqueue(store, { kind: 'library', value: { key: book.key, book: mirror, deleted: false, editionConflict } }, book.key)
        if (options.reading) await this.importReading(store, book, options.reading)
      }
    })
  }

  keepLocalEdition(key: string): Promise<void> {
    return this.write(['books', 'queue', 'localProgress', 'localHighlights'], async (store) => {
      const record: LocalRecord | undefined = await result(store('books').get(key))
      if (!record) throw new Error('This edition is unavailable on this device')
      await result(store('books').put({ ...record, localOnly: true }, key))
      await this.restoreLocalReading(store, key)
    })
  }

  private async restoreLocalReading(store: GetStore, key: string): Promise<void> {
    await result(store('queue').delete(`library:${key}`))
    for (const entry of await result<Queued[]>(store('queue').getAll())) {
      if (entry.kind === 'library' || entry.value.document.content_hash !== key) continue
      if (entry.kind === 'progress') {
        const current: Progress | undefined = await result(store('localProgress').get(key))
        await result(store('localProgress').put(newerProgress(current, entry.value), key))
      } else {
        const current: Highlight | undefined = await result(store('localHighlights').get(entry.value.id))
        if (!current || entry.value.updated_at >= current.updated_at) {
          await result(store('localHighlights').put(entry.value, entry.value.id))
        }
      }
      await result(store('queue').delete(entry.key))
    }
  }

  private async importReading(store: GetStore, book: LocalBook, reading: ImportedReading): Promise<void> {
    const document = { content_hash: book.key, metadata_id: book.metadata_id, identity_hash_version: book.identity_hash_version ?? 1 }
    const { progress } = reading
    if (progress && progress.document.content_hash === book.key) {
      const saved: Progress | undefined = await result(store('progress').get(book.key))
      const queued: Queued | undefined = await result(store('queue').get(`progress:${book.key}`))
      const latest = queued?.kind === 'progress' ? newerProgress(saved, queued.value) : saved
      if (!latest || compareTimestamps(progress.client_updated_at, latest.client_updated_at) > 0) {
        await this.enqueue(store, { kind: 'progress', value: { ...progress, document } }, book.key)
      }
    }
    for (const highlight of reading.highlights) {
      if (highlight.document.content_hash !== book.key || highlight.deleted_at != null) continue
      const saved: Highlight | undefined = await result(store('highlights').get(highlight.id))
      const queued: Queued | undefined = await result(store('queue').get(`highlight:${highlight.id}`))
      const latest = Math.max(saved?.updated_at ?? -1, queued?.kind === 'highlight' ? queued.value.updated_at : -1)
      if (latest < highlight.updated_at) await this.enqueue(store, {kind: 'highlight', value: {...highlight, document}}, highlight.id)
    }
  }

  async getEpub(key: string): Promise<Blob | undefined> {
    const stored = await this.read(['epubs'], (store) => result<StoredBlob | Blob | undefined>(store('epubs').get(key)))
    this.assertActive()
    return stored && decodeBlob(stored)
  }

  removeDownload(key: string): Promise<void> {
    return this.write(['epubs','settings'], async (store) => {
      await Promise.all([result(store('epubs').delete(key)), result(store('settings').delete(`bookmark:${key}`))])
    })
  }

  async removeBook(key: string): Promise<void> {
    await this.write(['books', 'epubs', 'library', 'queue', 'settings'], async (store) => {
      const [local, remote] = await Promise.all([
        result<LocalRecord | undefined>(store('books').get(key)),
        result<LibraryRow | undefined>(store('library').get(key)),
      ])
      if (this.syncEnabled && (!local?.localOnly || remote)) {
        const version = Math.max(local?.book.identity_hash_version ?? 1, remote?.identity_hash_version ?? 1)
        await this.enqueue(store, { kind: 'library', value: { key, deleted: true, identity_hash_version: version } }, key)
      }
      await Promise.all([
        result(store('books').delete(key)), result(store('epubs').delete(key)),
        result(store('settings').delete(`bookmark:${key}`)),
      ])
    })
  }

  putProgress(progress: Progress): Promise<void> {
    const key = progress.document.content_hash
    return this.write(['books', 'localProgress', 'progress', 'queue'], async (store) => {
      const local: LocalRecord | undefined = await result(store('books').get(key))
      const queued: Queued | undefined = await result(store('queue').get(`progress:${key}`))
      const destination = !this.syncEnabled || local?.localOnly ? 'localProgress' : 'progress'
      const current: Progress | undefined = queued?.kind === 'progress' ? queued.value : await result(store(destination).get(key))
      // A second explicit change in the same millisecond must still win the server comparison.
      const previous = current?.client_updated_at
      const value = previous && compareTimestamps(progress.client_updated_at, previous) <= 0
        ? { ...progress, client_updated_at: new Date(Date.parse(previous) + 1).toISOString() }
        : progress
      if (destination === 'localProgress') await result(store(destination).put(value, key))
      else await this.enqueue(store, { kind: 'progress', value }, key)
    })
  }

  putHighlight(highlight: Highlight): Promise<void> {
    return this.write(['books', 'localHighlights', 'highlights', 'queue'], async (store) => {
      const local: LocalRecord | undefined = await result(store('books').get(highlight.document.content_hash))
      const queued: Queued | undefined = await result(store('queue').get(`highlight:${highlight.id}`))
      const destination = !this.syncEnabled || local?.localOnly ? 'localHighlights' : 'highlights'
      const current: Highlight | undefined = queued?.kind === 'highlight' ? queued.value : await result(store(destination).get(highlight.id))
      const value = current ? {
        ...current, ...highlight, updated_at: Math.max(highlight.updated_at, current.updated_at + 1),
      } : highlight
      if (destination === 'localHighlights') await result(store(destination).put(value, highlight.id))
      else await this.enqueue(store, { kind: 'highlight', value }, highlight.id)
    })
  }

  private async enqueue(store: GetStore, mutation: Mutation, key: string): Promise<void> {
    const queueKey = `${mutation.kind}:${key}`
    const entry = { ...mutation, key: queueKey, id: crypto.randomUUID(), timestamp: new Date().toISOString() }
    await result(store('queue').put(entry, queueKey))
  }

  getPreference<T>(key: string): Promise<T | undefined> {
    return this.read(['settings'], (store) => result(store('settings').get(`preference:${key}`)))
  }

  setPreference<T>(key: string, value: T): Promise<void> {
    return this.setSetting(`preference:${key}`, value)
  }

  /** A catalog's cover for a book whose EPUB is not in this browser. Local only, like every cover. */
  async setCatalogCover(key: string, cover: Blob): Promise<void> {
    return this.setSetting(`cover:${key}`, await encodeBlob(cover))
  }

  getBookmark<T>(key: string): Promise<T | undefined> {
    return this.read(['settings'], (store) => result(store('settings').get(`bookmark:${key}`)))
  }

  setBookmark<T>(key: string, value: T | null): Promise<void> {
    return this.setSetting(`bookmark:${key}`, value)
  }

  private setSetting(key: string, value: unknown): Promise<void> {
    return this.write(['settings'], async (store) => {
      if (value == null) await result(store('settings').delete(key))
      else await result(store('settings').put(value, key))
    })
  }

  sync(): Promise<void> {
    this.assertActive()
    const transport = this.transport
    if (!transport || !this.syncEnabled) return Promise.resolve()
    this.running ??= this.exclusive(() => this.syncNow(transport)).finally(() => { this.running = undefined })
    return this.running
  }

  private async syncNow(transport: SyncTransport): Promise<void> {
    const send = <T>(path: string, item?: unknown, idempotencyKey?: string) =>
      requestJson<T>(transport, path, this.controller.signal, item, idempotencyKey)
    const queue = await this.read(['queue'], (store) => result<Queued[]>(store('queue').getAll()))
    let failure: unknown
    const attempt = async (action: () => Promise<void>) => {
      try {
        this.assertActive()
        await action()
      } catch (error) {
        this.assertActive()
        if (error instanceof SyncError && [401, 403].includes(error.status)) throw error
        failure ??= error
      }
    }
    const pushedProgress: Array<{ entry: Extract<Queued, { kind: 'progress' }>; ack: ProgressPush['results'][number] }> = []
    const pushedLibrary = new Map<string, string>()
    // A progress push resolves metadata_id ahead of content_hash. Send the
    // library writes first so a rejected edition cannot overwrite the position
    // of the edition the server already knows.
    const ordered = [...queue.filter((entry) => entry.kind === 'library'), ...queue.filter((entry) => entry.kind !== 'library')]
    for (const entry of ordered) {
      await attempt(async () => {
        // A newer local edit can supersede an entry before this tab sends it.
        const current = await this.read(['queue'], (store) => result<Queued | undefined>(store('queue').get(entry.key)))
        if (current?.id !== entry.id || blockedEdition(current)) return
        if (entry.kind !== 'library') {
          const key = entry.value.document.content_hash
          const library = await this.read(['queue'], (store) => result<Queued | undefined>(store('queue').get(`library:${key}`)))
          if (library?.kind === 'library' && !library.value.deleted && pushedLibrary.get(library.key) !== library.id) return
        }
        if (entry.kind === 'progress') {
          const response = await send<ProgressPush>('/sync/v1/progress', entry.value)
          const ack = response.results[0]
          if (response.results.length !== 1 || !ack?.document) throw new Error('Unexpected progress acknowledgement')
          pushedProgress.push({ entry, ack })
        } else if (entry.kind === 'highlight') {
          const response = await send<HighlightsPush>('/sync/v1/highlights', entry.value)
          const winner = response.results[0]?.item
          if (winner?.id !== entry.value.id) throw new Error('Unexpected highlight acknowledgement')
          if (entry.value.comment !== undefined && !Object.hasOwn(winner, 'comment')) {
            throw new SyncError(409, 'highlight_comments_unsupported')
          }
          await this.write(['highlights', 'queue'], async (store) => {
            await result(store('highlights').put(winner, winner.id))
            await this.acknowledge(store, entry)
          })
        } else {
          try {
            await send('/library/v1/sync', libraryEntry(entry.value, entry.timestamp), entry.id)
          } catch (error) {
            if (entry.value.deleted || !(error instanceof SyncError) ||
                error.status !== 409 || error.code !== 'metadata_id_conflict') throw error
            await this.write(['queue'], async (store) => {
              const latest: Queued | undefined = await result(store('queue').get(entry.key))
              if (latest?.id === entry.id && latest.kind === 'library' && !latest.value.deleted) {
                await result(store('queue').put({ ...latest, value: { ...latest.value, editionConflict: true } }, latest.key))
              }
            })
            throw new SyncError(error.status, error.code, entry.value.key)
          }
          pushedLibrary.set(entry.key, entry.id)
        }
      })
    }

    await attempt(async () => {
      const response = await send<ProgressPull>(`/sync/v1/progress?since=${epoch}`)
      await this.write(['progress', 'queue'], async (store) => {
        for (const row of response.items) {
          const key = row.document.content_hash
          const current = await result<Progress | undefined>(store('progress').get(key))
          await result(store('progress').put(newerProgress(current, row), key))
        }
        const byHash = new Map(response.items.map((row) => [row.document.content_hash, row]))
        for (const { entry, ack } of pushedProgress) {
          const winner = byHash.get(ack.document.content_hash)
          if (winner && compareTimestamps(winner.client_updated_at, ack.server_client_updated_at) >= 0) {
            await this.acknowledge(store, entry)
          }
        }
      })
    })

    await attempt(async () => {
      // Scan from a fixed lower bound, including tombstones. Concurrent changes may
      // shift offset pages. Replaying the entire scan on each run avoids missed rows
      // becoming permanent, and missing rows never imply deletion.
      for (let offset = 0; ; offset += libraryPageSize) {
        const page = await send<LibraryPull>(`/library/v1/items?since=${epoch}&limit=${libraryPageSize}&offset=${offset}`)
        await this.write(['library'], async (store) => {
          for (const row of page.items) {
            const key = row.content_hash
            const current = await result<LibraryRow | undefined>(store('library').get(key))
            if (!current || compareTimestamps(row.updated_at, current.updated_at) >= 0) {
              await result(store('library').put(row, key))
            }
          }
        })
        if (page.items.length < libraryPageSize) break
      }
      await this.write(['queue'], async (store) => {
        for (const entry of queue) {
          if (pushedLibrary.get(entry.key) === entry.id) await this.acknowledge(store, entry)
        }
      })
    })

    await attempt(async () => {
      let cursor = await this.read(['settings'], (store) => result<string | undefined>(store('settings').get(highlightCursor))) ?? '0'
      let reset = false
      for (;;) {
        let page: HighlightsPull
        try {
          page = await send<HighlightsPull>(`/sync/v1/highlights?since=${cursor}&limit=500`)
        } catch (error) {
          if (reset || cursor === '0' || !(error instanceof SyncError) || error.code !== 'highlight_cursor_reset_required') throw error
          cursor = '0'
          reset = true
          continue
        }
        if (!/^\d{1,19}$/.test(page.next_cursor) || (page.has_more && BigInt(page.next_cursor) <= BigInt(cursor))) {
          throw new Error('Highlight cursor did not advance')
        }
        await this.write(['highlights', 'settings'], async (store) => {
          if (reset && cursor === '0') await result(store('highlights').clear())
          for (const row of page.items) await result(store('highlights').put(row, row.id))
          await result(store('settings').put(page.next_cursor, highlightCursor))
        })
        if (!page.has_more) break
        cursor = page.next_cursor
      }
    })

    if (failure) throw failure
  }

  private async acknowledge(store: GetStore, entry: Queued): Promise<void> {
    const current = await result<Queued | undefined>(store('queue').get(entry.key))
    if (current?.id === entry.id) await result(store('queue').delete(entry.key))
  }
}
