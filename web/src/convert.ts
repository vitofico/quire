import { sha256 } from '@noble/hashes/sha2.js'
import { bytesToHex } from '@noble/hashes/utils.js'
import { DiscoveryHttpError, type DiscoveryRequest, discoveryJson, jsonBody } from './discovery/api'

/** `GET /convert/v1/capabilities`, the parts this client uses. */
export type ConvertCapabilities = { available: boolean; max_bytes: number; max_pages: number }

type Page = { page: number; label: string | null }
/** The server's job object, the parts this client uses. */
export type ConvertJob = {
  id: string
  state: 'created' | 'queued' | 'running' | 'ready' | 'review' | 'failed' | 'cancelled' | 'acknowledged' | 'expired'
  result: { bytes: number; sha256: string } | null
  review: { reason: string | null; affected_pages: Page[] } | null
  failure: { code: string; retryable: boolean; detail: string | null } | null
}

export type Stage =
  /** Chosen, waiting for the reader to start it. Never saved. */
  | 'confirm'
  /** Checksum being computed, or the job being created. */
  | 'preparing'
  | 'uploading'
  /** The upload needs the file again: the page was reloaded before it finished. */
  | 'reupload'
  /** The server asked to wait (`retryAt`); tried again then. */
  | 'waiting'
  | 'queued'
  | 'converting'
  /** Converted: being downloaded and added to the library. */
  | 'importing'
  | 'done'
  /** Converted and added; some pages need a look. */
  | 'review'
  | 'failed'

export type Conversion = {
  /** Also the idempotency key. */
  id: string
  name: string
  bytes: number
  sha256?: string
  job?: string
  stage: Stage
  /** Why it failed or waits: a failure code, a server refusal or `network`. */
  problem?: string
  retryable?: boolean
  /** Text from the worker: plain text only. */
  detail?: string | null
  reason?: string | null
  pages?: Page[]
  /** The imported book's key. */
  book?: string
  /** Imported, but the server has not yet been told. */
  unacknowledged?: boolean
  retryAt?: number
  /** Over the server's size limit: shown, never started. */
  tooLarge?: boolean
  /** Uploads or downloads that failed in a row; each waits longer. */
  attempts?: number
  /** Dismissed while offline: hidden, and taken off once the server is told. */
  dismissed?: boolean
  /** The reader chose to keep this copy in this browser only: another edition has its identifier. */
  keepLocal?: boolean
}

export type ConversionStore = {
  getPreference<T>(key: string): Promise<T | undefined>
  setPreference<T>(key: string, value: T): Promise<void>
}

export type ConversionDeps = {
  request: DiscoveryRequest
  store: ConversionStore
  /**
   * Adds the EPUB through the library's own import and answers the book's key.
   * It never asks anything: another edition with the same identifier rejects
   * with an `EditionConflict`, unless `keepLocal`.
   */
  importEpub: (epub: Blob, keepLocal: boolean) => Promise<string>
  /** Whether the page is hidden: polling waits until it is shown. */
  hidden?: () => boolean
  onVisibility?: (listener: () => void) => () => void
  /**
   * Shared by the account's tabs: each conversion is driven by one tab, under a
   * Web Lock named after it, and the others show it. Without Web Locks every
   * tab drives what it sees, and each save merges by id.
   */
  name?: string
  locks?: LockManager
  /** Pinged after each save, so the other tabs read the list again. */
  channel?: BroadcastChannel
}

export const pollInterval = 5_000
const storeKey = 'conversions'
const chunk = 4 << 20
const uploadBackoff = 30_000
/** Waits 30 s, 1, 2 and 4 minutes between failed transfers, then stops. */
const backoff = (attempts: number) => Math.min(uploadBackoff * 2 ** (attempts - 1), 600_000)
const maxAttempts = 5
/** States the client keeps asking the server about. */
const polled: Stage[] = ['preparing', 'uploading', 'waiting', 'queued', 'converting', 'importing']
const settled: Stage[] = ['done', 'review', 'failed']
/** Failures that leave the job as it was: a retry picks it up again. */
const resumable = ['upload_failed', 'download_failed', 'import_failed', 'edition_conflict']

export const isPdf = (file: File) => file.type === 'application/pdf' || /\.pdf$/i.test(file.name)

/**
 * The SHA-256 of `file`, read 4 MiB at a time: a 100 MiB PDF never sits in
 * memory whole, only one slice of it.
 */
export async function hashFile(file: Blob, aborted: () => boolean = () => false): Promise<string> {
  const hash = sha256.create()
  for (let at = 0; at < file.size; at += chunk) {
    if (aborted()) throw new DOMException('Cancelled', 'AbortError')
    hash.update(new Uint8Array(await file.slice(at, at + chunk).arrayBuffer()))
  }
  return bytesToHex(hash.digest())
}

const detailOf = (error: unknown) =>
  error instanceof DiscoveryHttpError && typeof error.detail === 'string' ? error.detail : undefined

/** `GET /convert/v1/capabilities`; `undefined` when this server does not convert. */
export async function convertCapabilities(request: DiscoveryRequest): Promise<ConvertCapabilities | undefined> {
  try {
    return await discoveryJson<ConvertCapabilities>(request, '/convert/v1/capabilities')
  } catch (error) {
    if (error instanceof DiscoveryHttpError && error.status === 404) return undefined
    throw error
  }
}

/**
 * The reader's PDF conversions, kept in the account's settings store so they
 * survive a reload: each is saved before the job is created, and again with
 * the job id before the upload starts. One timer polls the server, never more
 * often than every five seconds, and only while the page is shown and
 * something is in flight. A converted book is downloaded, added through the
 * library's import and only then acknowledged.
 */
export class Conversions {
  items: Conversion[] = []
  /** The newest change worth announcing. */
  announcement: { id: string; stage: Stage; problem?: string } | undefined
  private files = new Map<string, File>()
  private busy = new Set<string>()
  private uploads = new Map<string, AbortController>()
  private listeners = new Set<() => void>()
  /** The conversions this tab drives; the others are only shown. */
  private owned = new Set<string>()
  private wanted = new Set<string>()
  private releases = new Map<string, () => void>()
  private timer: ReturnType<typeof setTimeout> | undefined
  private lastContact = -Infinity
  private ticking = false
  private disposed = false
  private stopVisibility?: () => void
  readonly loaded: Promise<void>

  constructor(private deps: ConversionDeps) {
    this.stopVisibility = deps.onVisibility?.(() => this.schedule())
    deps.channel?.addEventListener('message', () => void this.reload())
    this.loaded = this.read().then((saved) => {
      // Something chosen before the saved list arrived goes after it.
      this.items = [...saved, ...this.items.filter((item) => !saved.some((s) => s.id === item.id))]
      this.emit()
      for (const item of saved) void this.claim(item.id)
    }, () => undefined)
  }

  subscribe(listener: () => void) {
    this.listeners.add(listener)
    return () => { this.listeners.delete(listener) }
  }

  dispose() {
    this.disposed = true
    clearTimeout(this.timer)
    this.stopVisibility?.()
    this.deps.channel?.close()
    for (const upload of this.uploads.values()) upload.abort()
    for (const release of this.releases.values()) release()
  }

  /**
   * Adds `files` for the reader to confirm; over `maxBytes` they can only be
   * dismissed. A file chosen again for a conversion that needs it goes there
   * at once: the reader confirmed it already, and its checksum decides.
   */
  offer(files: File[], maxBytes: number) {
    for (const file of files) {
      const needed = file.size <= maxBytes && this.items.find((item) => item.stage === 'reupload' &&
        !this.files.has(item.id) && item.name === file.name && (item.bytes === file.size || !item.sha256))
      if (needed) {
        this.files.set(needed.id, file)
        void this.resume(needed.id)
        continue
      }
      const id = crypto.randomUUID()
      this.files.set(id, file)
      this.items = [...this.items, { id, name: file.name, bytes: file.size, stage: 'confirm', tooLarge: file.size > maxBytes }]
    }
    this.emit()
  }

  /** Starts a confirmed conversion: checksum, then the job, then the upload. */
  async start(id: string) {
    const file = this.files.get(id)
    const item = this.find(id)
    if (!file || item?.stage !== 'confirm' || item.tooLarge || this.busy.has(id)) return
    this.busy.add(id)
    try {
      await this.claim(id, true)
      await this.update(id, { stage: 'preparing' })
      const sum = await this.checksum(id, file)
      if (!sum || !this.find(id)) return
      // The same PDF already on the list: that one carries on, with this file if it needs it.
      const same = this.items.find((other) =>
        other.id !== id && other.sha256 === sum && other.bytes === file.size && other.stage !== 'failed')
      if (same) {
        await this.drop(id)
        if (same.stage === 'reupload' && !this.files.has(same.id)) {
          this.files.set(same.id, file)
          void this.resume(same.id, sum)
        } else this.announce(same.id)
        return
      }
      await this.update(id, { sha256: sum })
      await this.create(id)
    } catch (error) {
      if (this.find(id)) await this.trouble(id, error)
    } finally {
      this.busy.delete(id)
      this.schedule()
    }
  }

  /** Stops a conversion in flight (or forgets one not yet started) and takes it off the list. */
  async cancel(id: string) {
    const item = this.find(id)
    if (!item) return
    await this.claim(id, true)
    this.uploads.get(id)?.abort()
    await this.drop(id)
    if (item.job) await this.stop(item.job)
  }

  /** Takes a finished or failed conversion off the list, once the server knows it is kept. */
  async dismiss(id: string) {
    const item = this.find(id)
    if (!item) return
    await this.claim(id, true)
    // A job this page gave up on: its slot or its result goes too.
    if (item.job && resumable.includes(item.problem ?? '')) void this.stop(item.job)
    if (!item.unacknowledged) return this.drop(id)
    await this.update(id, { dismissed: true })
    await this.acknowledge(id)
  }

  /**
   * Tries a failed conversion again: a transfer or an import that failed
   * picks the same job up; anything else starts a new job, with the file if
   * this page still has it.
   */
  async retry(id: string) {
    const item = this.find(id)
    if (!item || item.stage !== 'failed' || !item.retryable) return
    await this.claim(id, true)
    if (item.job && resumable.includes(item.problem ?? '')) {
      await this.update(id, {
        stage: item.problem === 'upload_failed' ? 'uploading' : 'importing', problem: undefined, retryable: undefined,
        attempts: undefined, keepLocal: item.problem === 'edition_conflict' || item.keepLocal,
      })
      return this.run(id, () => this.refresh(id))
    }
    const file = this.files.get(id)
    const fresh: Conversion = { id: crypto.randomUUID(), name: item.name, bytes: item.bytes, sha256: item.sha256, stage: 'preparing' }
    if (!file || !fresh.sha256) return this.replace(id, { ...fresh, stage: 'reupload' })
    this.files.set(fresh.id, file)
    await this.replace(id, fresh)
    await this.run(fresh.id, () => this.create(fresh.id))
  }

  private find(id: string) {
    return this.items.find((item) => item.id === id)
  }

  private emit() {
    for (const listener of this.listeners) listener()
  }

  private announce(id: string) {
    const item = this.find(id)
    if (item) this.announcement = { id, stage: item.stage, problem: item.problem }
  }

  private async read() {
    const saved = await this.deps.store.getPreference<Conversion[]>(storeKey)
    return Array.isArray(saved) ? saved : []
  }

  /** This tab's view: its own conversions as it has them, the others as saved. */
  private merge(saved: Conversion[]) {
    const theirs = new Map(saved.filter((item) => !this.owned.has(item.id)).map((item) => [item.id, item]))
    const kept = this.items.flatMap((item) => this.owned.has(item.id) || item.stage === 'confirm' ? [item]
      : theirs.has(item.id) ? [theirs.get(item.id)!] : [])
    return [...kept, ...[...theirs.values()].filter((item) => !kept.some((other) => other.id === item.id))]
  }

  /** Writes this tab's conversions over a fresh read, so another tab's are kept. */
  private async save() {
    const write = async () => {
      this.items = this.merge(await this.read())
      await this.deps.store.setPreference(storeKey, this.items.filter((item) => item.stage !== 'confirm'))
    }
    const { locks, name } = this.deps
    await (locks && name ? locks.request(name, write) : write())
    this.deps.channel?.postMessage('saved')
    this.emit()
  }

  /** Another tab saved: show its conversions, and wait to drive the new ones should it close. */
  private async reload() {
    const saved = await this.read()
    if (this.disposed) return
    this.items = this.merge(saved)
    this.emit()
    for (const item of saved) void this.claim(item.id)
  }

  /**
   * Drives `id` in this tab: once no other tab does, or at once with `now`
   * (the reader acts on it here, or it is new). A tab that loses it to
   * another stops its upload and waits to drive it again.
   */
  private claim(id: string, now = false): Promise<void> {
    const { locks, name } = this.deps
    if (!locks || !name) {
      if (!this.owned.has(id)) {
        this.owned.add(id)
        if (!now) void this.takeOver(id)
      }
      return Promise.resolve()
    }
    if (this.owned.has(id) || (this.wanted.has(id) && !now)) return Promise.resolve()
    if (!now) this.wanted.add(id)
    return new Promise<void>((granted) => {
      let held = false
      locks.request(`${name}:${id}`, { steal: now }, async () => {
        if (!now) this.wanted.delete(id)
        // Gone while this tab waited: the tab that drove it took it off.
        const saved = now ? undefined : (await this.read()).find((item) => item.id === id)
        if (this.disposed || !this.find(id) || (!now && !saved)) {
          if (!now && !saved && !this.disposed) this.items = this.items.filter((item) => item.id !== id)
          return
        }
        held = true
        this.owned.add(id)
        if (saved) {
          this.items = this.items.map((item) => item.id === id ? saved : item)
          await this.takeOver(id)
        }
        granted()
        this.schedule()
        return new Promise<void>((release) => this.releases.set(id, release))
      }).catch(() => undefined).then(() => {
        granted()
        if (!held) return
        this.owned.delete(id)
        this.releases.delete(id)
        this.uploads.get(id)?.abort()
        if (!this.disposed && this.find(id)) void this.claim(id)
      })
    })
  }

  /** Picks up a conversion another page left: only a wait the server asked for outlasts it. */
  private async takeOver(id: string) {
    const item = this.find(id)
    // A reload during the checksum left nothing to send: the file is needed again.
    if (item?.stage === 'preparing' && !item.sha256) await this.update(id, { stage: 'reupload' })
    else if (item?.retryAt && item.stage !== 'waiting') await this.update(id, { retryAt: undefined })
    this.schedule()
  }

  /** Takes a conversion off the list, then lets it go. */
  private async drop(id: string) {
    this.files.delete(id)
    this.items = this.items.filter((item) => item.id !== id)
    this.emit()
    await this.save()
    this.releases.get(id)?.()
  }

  /** Puts `fresh` (a new key) where `id` was. */
  private async replace(id: string, fresh: Conversion) {
    this.files.delete(id)
    this.items = this.items.map((item) => item.id === id ? fresh : item)
    this.announce(fresh.id)
    await this.claim(fresh.id, true)
    await this.save()
    this.releases.get(id)?.()
  }

  private async update(id: string, patch: Partial<Conversion>) {
    const before = this.find(id)
    if (!before) return
    this.items = this.items.map((item) => item.id === id ? { ...item, ...patch } : item)
    const after = this.find(id)!
    if (after.stage !== before.stage || (after.problem && after.problem !== before.problem)) this.announce(id)
    this.emit()
    if (after.stage !== 'confirm') await this.save()
  }

  private async run(id: string, action: () => Promise<void>) {
    if (this.busy.has(id)) return
    this.busy.add(id)
    try {
      await action()
    } catch (error) {
      if (this.find(id)) await this.trouble(id, error)
    } finally {
      this.busy.delete(id)
      this.schedule()
    }
  }

  private async call<T>(path: string, init?: RequestInit): Promise<T> {
    try {
      return await discoveryJson<T>(this.deps.request, path, init)
    } finally {
      this.lastContact = Date.now()
    }
  }

  /** The file's checksum; a file that cannot be read is asked for again. */
  private async checksum(id: string, file: File) {
    try {
      return await hashFile(file, () => !this.find(id))
    } catch (error) {
      if ((error as Error)?.name === 'AbortError' || !this.find(id)) return
      this.files.delete(id)
      await this.update(id, { stage: 'reupload', problem: 'file_unreadable' })
    }
  }

  /** Sends a file chosen again to the conversion that needs it; a different file starts afresh. */
  private async resume(id: string, known?: string) {
    const file = this.files.get(id)
    if (!file || this.busy.has(id)) return
    this.busy.add(id)
    let current = id
    try {
      await this.claim(id, true)
      await this.update(id, { stage: 'preparing', problem: undefined, retryAt: undefined })
      const sum = known ?? await this.checksum(id, file)
      const item = this.find(id)
      if (!sum || !item) return
      if (item.sha256 && item.sha256 !== sum) {
        // Not the file the job was made for: that job goes, and this one gets its own key.
        if (item.job) void this.stop(item.job)
        current = crypto.randomUUID()
        this.busy.add(current)
        this.files.set(current, file)
        await this.replace(id, { id: current, name: file.name, bytes: file.size, sha256: sum, stage: 'preparing' })
      } else await this.update(id, { sha256: sum, bytes: file.size })
      await this.refresh(current)
    } catch (error) {
      if (this.find(current)) await this.trouble(current, error)
    } finally {
      this.busy.delete(id)
      this.busy.delete(current)
      this.schedule()
    }
  }

  /** Cancels a job; one that finished meanwhile is acknowledged, so its result goes too. */
  private async stop(job: string) {
    try {
      await this.call(`/convert/v1/jobs/${job}/cancel`, { method: 'POST' })
    } catch (error) {
      if (detailOf(error) === 'not_cancellable') {
        await this.call(`/convert/v1/jobs/${job}/acknowledge`, { method: 'POST' }).catch(() => undefined)
      }
    }
  }

  /** `POST /convert/v1/jobs`, also to pick a job up again: the same key answers the same job. */
  private async create(id: string) {
    const item = this.find(id)
    if (!item?.sha256) return
    const job = await this.call<ConvertJob>('/convert/v1/jobs', jsonBody('POST', {
      idempotency_key: item.id, sha256: item.sha256, bytes: item.bytes, mode: 'local',
    }))
    // Cancelled while the job was being created.
    if (!this.find(id)) return this.stop(job.id)
    await this.update(id, { job: job.id, problem: undefined, retryAt: undefined })
    await this.adopt(id, job)
  }

  private async refresh(id: string) {
    const item = this.find(id)
    if (!item?.job) return this.create(id)
    await this.adopt(id, await this.call<ConvertJob>(`/convert/v1/jobs/${item.job}`))
  }

  /** Moves the conversion to where the server's job is. */
  private async adopt(id: string, job: ConvertJob) {
    const item = this.find(id)
    if (!item) return
    switch (job.state) {
      case 'created':
        // The upload never arrived: send it again if this page has the file.
        if (this.uploads.has(id)) return
        if (!this.files.has(id)) return this.update(id, { stage: 'reupload', problem: undefined })
        if (item.retryAt && item.retryAt > Date.now()) return
        void this.upload(id)
        return
      case 'queued':
        return this.update(id, { stage: 'queued', problem: undefined })
      case 'running':
        return this.update(id, { stage: 'converting', problem: undefined })
      case 'ready':
      case 'review':
        return this.receive(id, job)
      case 'failed':
        await this.update(id, {
          stage: 'failed', problem: job.failure?.code ?? 'conversion_failed',
          retryable: job.failure?.retryable ?? false, detail: job.failure?.detail ?? null, unacknowledged: true,
        })
        // The reason is kept here; the server's copy can go.
        return this.acknowledge(id)
      case 'cancelled':
        return this.drop(id)
      case 'expired':
        return this.fail(id, 'expired', true)
      case 'acknowledged':
        // Taken already: by this page before a reload, or the result is gone.
        if (item.book) return this.update(id, { unacknowledged: false })
        return this.fail(id, 'result_gone', true)
    }
  }

  /** Counts a failed transfer: tried again later, each time later, until it stops as `failure`. */
  private async again(id: string, problem: string, failure: string) {
    const attempts = (this.find(id)?.attempts ?? 0) + 1
    if (attempts >= maxAttempts) {
      return this.update(id, { stage: 'failed', problem: failure, retryable: true, attempts: undefined, retryAt: undefined })
    }
    await this.update(id, { problem, attempts, retryAt: Date.now() + backoff(attempts) })
  }

  private async upload(id: string) {
    const item = this.find(id), file = this.files.get(id)
    if (!item?.job || !file || this.uploads.has(id)) return
    const controller = new AbortController()
    this.uploads.set(id, controller)
    try {
      await this.update(id, { stage: 'uploading', problem: undefined, retryAt: undefined })
      // The File itself is the body: the browser streams it from disk.
      const job = await this.call<ConvertJob>(`/convert/v1/jobs/${item.job}/source`, {
        method: 'PUT', headers: { 'Content-Type': 'application/pdf' }, body: file, signal: controller.signal,
      })
      await this.update(id, { attempts: undefined })
      await this.adopt(id, job)
    } catch (error) {
      if (controller.signal.aborted || !this.find(id)) return
      const detail = detailOf(error)
      if (detail === 'not_accepting_source') {
        // The upload arrived; only its answer was lost.
        await this.refresh(id).catch((cause) => this.trouble(id, cause))
      } else if (detail === 'upload_in_progress') {
        await this.update(id, { stage: 'waiting', problem: detail, retryAt: Date.now() + uploadBackoff })
      } else if (detail === 'size_mismatch' || detail === 'checksum_mismatch') {
        // The file changed after its checksum: its job goes, and the file has to be chosen again.
        void this.stop(item.job)
        await this.replace(id, { id: crypto.randomUUID(), name: item.name, bytes: item.bytes, stage: 'reupload', problem: detail })
      } else if (detail === 'upload_timed_out' || !(error instanceof DiscoveryHttpError) || error.status >= 500) {
        await this.again(id, detail ?? 'network', 'upload_failed')
      } else {
        await this.trouble(id, error)
        // A refused upload leaves a job waiting for a PDF that will not come.
        await this.stop(item.job)
      }
    } finally {
      this.uploads.delete(id)
      this.schedule()
    }
  }

  /** The EPUB, checked against the job's size and checksum. */
  private async download(job: ConvertJob) {
    const response = await this.deps.request(`/convert/v1/jobs/${job.id}/epub`)
    this.lastContact = Date.now()
    if (!response.ok) {
      const body = await response.json().catch(() => null) as { detail?: unknown } | null
      throw new DiscoveryHttpError(response.status, body?.detail, Number(response.headers.get('Retry-After')) || undefined)
    }
    const epub = await response.blob()
    if (job.result && (epub.size !== job.result.bytes || await hashFile(epub) !== job.result.sha256)) {
      throw new Error('The download does not match the converted book.')
    }
    return epub.type === 'application/epub+zip' ? epub : new Blob([epub], { type: 'application/epub+zip' })
  }

  /** Downloads the EPUB, adds it to the library, then acknowledges it. */
  private async receive(id: string, job: ConvertJob) {
    const review = job.state === 'review' ? job.review : null
    const outcome: Partial<Conversion> = {
      reason: review?.reason ?? null, pages: review?.affected_pages ?? undefined,
    }
    const item = this.find(id)
    if (item?.book) {
      // Added before a reload: only the acknowledgement is left.
      await this.update(id, { stage: review ? 'review' : 'done', ...outcome, unacknowledged: true })
      return this.acknowledge(id)
    }
    await this.update(id, { stage: 'importing', problem: undefined })
    let epub: Blob
    try {
      epub = await this.download(job)
    } catch (error) {
      if (error instanceof DiscoveryHttpError && error.status < 500) throw error
      return this.again(id, 'network', 'download_failed')
    }
    let book: string
    try {
      book = await this.deps.importEpub(epub, !!item?.keepLocal)
    } catch (error) {
      // Never a question in the middle of reading: the reader decides from the list.
      return this.fail(id, (error as Error)?.name === 'EditionConflict' ? 'edition_conflict' : 'import_failed', true)
    }
    this.files.delete(id)
    await this.update(id, { stage: review ? 'review' : 'done', ...outcome, book, attempts: undefined, unacknowledged: true })
    await this.acknowledge(id)
  }

  /** Tells the server the result is kept; offline, the next poll tells it. */
  private async acknowledge(id: string) {
    const item = this.find(id)
    if (!item?.job) return
    try {
      await this.call(`/convert/v1/jobs/${item.job}/acknowledge`, { method: 'POST' })
    } catch (error) {
      // Any refusal means there is nothing left to acknowledge.
      if (!(error instanceof DiscoveryHttpError) || error.status >= 500) return
    }
    const after = this.find(id)
    if (after?.dismissed) return this.drop(id)
    if (after?.unacknowledged) await this.update(id, { unacknowledged: false })
  }

  private async fail(id: string, problem: string, retryable: boolean) {
    await this.update(id, { stage: 'failed', problem, retryable, detail: null, retryAt: undefined })
  }

  /** What an error means for the conversion: wait and try again, or stop. */
  private async trouble(id: string, error: unknown) {
    if ((error as Error)?.name === 'AbortError') return
    const item = this.find(id)
    if (!item) return
    const detail = detailOf(error) ?? ''
    // Only the converter's own 503 means it is not running; any other is a passing fault.
    if (!(error instanceof DiscoveryHttpError) || (error.status >= 500 && detail !== 'converter_unavailable')) {
      // Offline or a passing fault: the same step is tried on the next poll.
      return this.update(id, { problem: 'network', retryAt: Date.now() + pollInterval })
    }
    if (error.status === 429 || error.status === 503) {
      return this.update(id, { stage: 'waiting', problem: detail || 'too_many_active_jobs',
        retryAt: Date.now() + (error.retryAfter ?? 60) * 1000 })
    }
    if (error.status === 404) {
      return this.fail(id, detail === 'job_not_found' ? 'job_not_found' : 'not_offered', detail === 'job_not_found')
    }
    if (error.status === 410) return this.fail(id, 'result_gone', true)
    const retryable = detail === 'idempotency_conflict'
    return this.fail(id, detail || `http_${error.status}`, retryable)
  }

  private due(item: Conversion) {
    if (!this.owned.has(item.id)) return false
    if (item.unacknowledged) return true
    return polled.includes(item.stage) && !(item.stage === 'preparing' && !item.sha256)
  }

  /** One poll: every conversion in flight takes its next step. */
  private async tick() {
    if (this.disposed || this.ticking) return
    this.ticking = true
    this.timer = undefined
    const now = Date.now()
    try {
      await Promise.all(this.items.filter((item) =>
        this.due(item) && !this.busy.has(item.id) && !this.uploads.has(item.id) && !(item.retryAt && item.retryAt > now),
      ).map((item) => this.run(item.id, async () => {
        if (settled.includes(item.stage)) return this.acknowledge(item.id)
        if (item.stage === 'waiting' && item.retryAt) await this.update(item.id, { retryAt: undefined })
        return item.job ? this.refresh(item.id) : this.create(item.id)
      })))
    } finally {
      this.ticking = false
      this.schedule()
    }
  }

  /** Sets the one timer for the next poll, or none when nothing waits or the page is hidden. */
  private schedule() {
    clearTimeout(this.timer)
    this.timer = undefined
    if (this.disposed || this.ticking || this.deps.hidden?.()) return
    const waiting = this.items.filter((item) => this.due(item) && !this.uploads.has(item.id) && !this.busy.has(item.id))
    if (!waiting.length) return
    const soonest = Math.min(...waiting.map((item) => item.retryAt ?? 0))
    const wake = Math.max(this.lastContact + pollInterval, soonest)
    this.timer = setTimeout(() => void this.tick(), Math.max(0, wake - Date.now()))
  }
}
