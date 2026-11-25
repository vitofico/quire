import { sha256 } from '@noble/hashes/sha2.js'
import { bytesToHex } from '@noble/hashes/utils.js'
import { DiscoveryHttpError, type DiscoveryRequest, discoveryJson, jsonBody } from './discovery/api'

/** `GET /convert/v1/capabilities`, the parts this client uses. */
export type ConvertCapabilities = { available: boolean; max_bytes: number; max_pages: number; daily_limit: number }

type Page = { page: number; label: string | null }
/** The server's job object, the parts this client uses. */
export type ConvertJob = {
  id: string
  state: 'created' | 'queued' | 'running' | 'ready' | 'review' | 'failed' | 'cancelled' | 'acknowledged' | 'expired'
  result: { bytes: number } | null
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
}

export type ConversionStore = {
  getPreference<T>(key: string): Promise<T | undefined>
  setPreference<T>(key: string, value: T): Promise<void>
}

export type ConversionDeps = {
  request: DiscoveryRequest
  store: ConversionStore
  /** Adds the EPUB through the library's own import; answers the book's key. */
  importEpub: (epub: Blob) => Promise<string>
  /** Whether the page is hidden: polling waits until it is shown. */
  hidden?: () => boolean
  onVisibility?: (listener: () => void) => () => void
}

export const pollInterval = 5_000
const storeKey = 'conversions'
const chunk = 4 << 20
const uploadBackoff = 30_000
/** States the client keeps asking the server about. */
const polled: Stage[] = ['preparing', 'uploading', 'waiting', 'queued', 'converting', 'importing']
const settled: Stage[] = ['done', 'review', 'failed']

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
  announcement: { id: string; stage: Stage } | undefined
  private files = new Map<string, File>()
  private busy = new Set<string>()
  private uploads = new Map<string, AbortController>()
  private listeners = new Set<() => void>()
  private timer: ReturnType<typeof setTimeout> | undefined
  private lastContact = -Infinity
  private ticking = false
  private disposed = false
  private stopVisibility?: () => void
  readonly loaded: Promise<void>

  constructor(private deps: ConversionDeps) {
    this.stopVisibility = deps.onVisibility?.(() => this.schedule())
    this.loaded = deps.store.getPreference<Conversion[]>(storeKey).then((saved) => {
      // Something chosen before the saved list arrived goes after it.
      if (Array.isArray(saved)) {
        // A reload during the checksum left nothing to send: the file is needed again. Only
        // a wait the server asked for outlasts the page; this page's own back-off starts over.
        const resumed = saved.map((item): Conversion => item.stage === 'preparing' && !item.sha256
          ? { ...item, stage: 'reupload' }
          : item.stage === 'waiting' ? item : { ...item, retryAt: undefined })
        this.items = [...resumed, ...this.items.filter((item) => !saved.some((s) => s.id === item.id))]
      }
      this.emit()
      this.schedule()
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
    for (const upload of this.uploads.values()) upload.abort()
  }

  /**
   * Adds `files` for the reader to confirm; over `maxBytes` they can only be
   * dismissed. A file that looks like one whose upload was cut off goes ahead
   * at once: the reader confirmed it already, and its checksum decides.
   */
  offer(files: File[], maxBytes: number) {
    const resumed: string[] = []
    for (const file of files) {
      const id = crypto.randomUUID()
      this.files.set(id, file)
      const tooLarge = file.size > maxBytes
      this.items = [...this.items, { id, name: file.name, bytes: file.size, stage: 'confirm', tooLarge }]
      if (!tooLarge && this.items.some((item) => item.stage === 'reupload' && item.name === file.name && item.bytes === file.size)) resumed.push(id)
    }
    this.emit()
    for (const id of resumed) void this.start(id)
  }

  /** Starts a confirmed conversion: checksum, then the job, then the upload. */
  async start(id: string) {
    const file = this.files.get(id)
    const item = this.find(id)
    if (!file || item?.stage !== 'confirm' || item.tooLarge || this.busy.has(id)) return
    this.busy.add(id)
    try {
      this.update(id, { stage: 'preparing' })
      const sum = await hashFile(file, () => !this.find(id))
      // The same PDF already on the list: that one carries on, with this file if it needs it.
      const same = this.items.find((other) =>
        other.id !== id && other.sha256 === sum && other.bytes === file.size && other.stage !== 'failed')
      if (same) {
        this.remove(id)
        if (same.stage === 'reupload') {
          this.files.set(same.id, file)
          await this.update(same.id, { stage: 'preparing', problem: undefined, retryAt: undefined })
          if (!this.busy.has(same.id)) await this.run(same.id, () => this.create(same.id))
        }
        this.announce(same.id)
        return
      }
      if (!this.find(id)) return
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
    this.uploads.get(id)?.abort()
    this.files.delete(id)
    this.remove(id)
    await this.save()
    if (item.job) await this.deps.request(`/convert/v1/jobs/${item.job}/cancel`, { method: 'POST' }).catch(() => undefined)
  }

  /** Takes a finished or failed conversion off the list. */
  async dismiss(id: string) {
    const item = this.find(id)
    if (!item) return
    if (item.unacknowledged) await this.acknowledge(id)
    this.files.delete(id)
    this.remove(id)
    await this.save()
  }

  /**
   * Tries a failed conversion again: an import that failed downloads the same
   * result again; anything else starts a new job, with the file if this page
   * still has it.
   */
  async retry(id: string) {
    const item = this.find(id)
    if (!item || item.stage !== 'failed' || !item.retryable) return
    if (item.problem === 'import_failed' && item.job) {
      await this.update(id, { stage: 'importing', problem: undefined, retryable: undefined })
      await this.run(id, () => this.refresh(id))
      return
    }
    const fresh: Conversion = { id: crypto.randomUUID(), name: item.name, bytes: item.bytes, sha256: item.sha256, stage: 'preparing' }
    const file = this.files.get(id)
    this.files.delete(id)
    this.items = this.items.map((other) => other.id === id ? fresh : other)
    if (!file || !fresh.sha256) {
      await this.update(fresh.id, { stage: 'reupload' })
      return
    }
    this.files.set(fresh.id, file)
    await this.save()
    this.emit()
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
    if (item) this.announcement = { id, stage: item.stage }
  }

  private save() {
    return this.deps.store.setPreference(storeKey, this.items.filter((item) => item.stage !== 'confirm'))
  }

  private remove(id: string) {
    this.items = this.items.filter((item) => item.id !== id)
    this.emit()
  }

  private async update(id: string, patch: Partial<Conversion>) {
    const before = this.find(id)
    if (!before) return
    this.items = this.items.map((item) => item.id === id ? { ...item, ...patch } : item)
    if (patch.stage && patch.stage !== before.stage) this.announce(id)
    this.emit()
    if (this.find(id)?.stage !== 'confirm') await this.save()
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

  /** `POST /convert/v1/jobs`, also to pick a job up again: the same key answers the same job. */
  private async create(id: string) {
    const item = this.find(id)
    if (!item?.sha256) return
    const job = await this.call<ConvertJob>('/convert/v1/jobs', jsonBody('POST', {
      idempotency_key: item.id, sha256: item.sha256, bytes: item.bytes, mode: 'local',
    }))
    if (!this.find(id)) {
      // Cancelled while the job was being created.
      await this.deps.request(`/convert/v1/jobs/${job.id}/cancel`, { method: 'POST' }).catch(() => undefined)
      return
    }
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
        this.files.delete(id)
        this.remove(id)
        return this.save()
      case 'expired':
        return this.fail(id, 'expired', true)
      case 'acknowledged':
        // Taken already: by this page before a reload, or the result is gone.
        if (item.book) return this.update(id, { unacknowledged: false })
        return this.fail(id, 'result_gone', true)
    }
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
        // The file changed after its checksum: it has to be chosen again.
        this.files.delete(id)
        await this.update(id, { stage: 'reupload', problem: detail })
      } else if (detail === 'upload_timed_out' || !(error instanceof DiscoveryHttpError) || error.status >= 500) {
        await this.update(id, { stage: 'uploading', problem: detail ?? 'network', retryAt: Date.now() + uploadBackoff })
      } else {
        await this.trouble(id, error)
        // A refused upload leaves a job waiting for a PDF that will not come.
        await this.deps.request(`/convert/v1/jobs/${item.job}/cancel`, { method: 'POST' }).catch(() => undefined)
      }
    } finally {
      this.uploads.delete(id)
      this.schedule()
    }
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
    const response = await this.deps.request(`/convert/v1/jobs/${job.id}/epub`)
    this.lastContact = Date.now()
    if (!response.ok) {
      const body = await response.json().catch(() => null) as { detail?: unknown } | null
      throw new DiscoveryHttpError(response.status, body?.detail, Number(response.headers.get('Retry-After')) || undefined)
    }
    const epub = await response.blob()
    if (job.result && epub.size !== job.result.bytes) throw new Error('The download was cut short.')
    let book: string
    try {
      book = await this.deps.importEpub(epub.type === 'application/epub+zip' ? epub : new Blob([epub], { type: 'application/epub+zip' }))
    } catch {
      return this.fail(id, 'import_failed', true)
    }
    this.files.delete(id)
    await this.update(id, { stage: review ? 'review' : 'done', ...outcome, book, unacknowledged: true })
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
    if (this.find(id)?.unacknowledged) await this.update(id, { unacknowledged: false })
  }

  private async fail(id: string, problem: string, retryable: boolean) {
    await this.update(id, { stage: 'failed', problem, retryable, detail: null })
  }

  /** What an error means for the conversion: wait and try again, or stop. */
  private async trouble(id: string, error: unknown) {
    if ((error as Error)?.name === 'AbortError') return
    const item = this.find(id)
    if (!item) return
    if (!(error instanceof DiscoveryHttpError) || (error.status >= 500 && error.status !== 503)) {
      // Offline or a passing fault: the same step is tried on the next poll.
      return this.update(id, { problem: 'network', retryAt: Date.now() + pollInterval })
    }
    const detail = detailOf(error) ?? ''
    if (error.status === 429 || error.status === 503) {
      return this.update(id, { stage: 'waiting', problem: detail || 'converter_unavailable',
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
