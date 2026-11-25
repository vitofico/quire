// @vitest-environment node
import { createHash } from 'node:crypto'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { type Conversion, type ConvertJob, Conversions, hashFile, pollInterval } from './convert'

const pdf = (text = 'one', name = 'Field notes.pdf') =>
  new File([`%PDF-1.7\n${text}\n%%EOF`], name, { type: 'application/pdf' })
const sha = async (file: Blob) => createHash('sha256').update(Buffer.from(await file.arrayBuffer())).digest('hex')

type Reply = { status: number; detail?: unknown; headers?: Record<string, string> }
type Call = { method: string; path: string; body?: unknown }

/** A small /convert/v1 that keeps jobs by idempotency key and lets a test move them along. */
function fakeServer() {
  const jobs = new Map<string, ConvertJob & { key: string; sha256: string; bytes: number }>()
  const calls: Call[] = []
  /** One-off answers, by "METHOD /path-ending": the next call there gets the first one. */
  const replies = new Map<string, Reply[]>()
  const epub = new Blob(['PK epub'], { type: 'application/epub+zip' })
  const result = { bytes: epub.size, sha256: createHash('sha256').update('PK epub').digest('hex') }
  let serial = 0
  const json = (body: unknown, status = 200, headers: Record<string, string> = {}) =>
    new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...headers } })
  const view = (job: ConvertJob) => ({ ...job })
  const request = vi.fn(async (path: string, init?: RequestInit) => {
    const method = (init?.method ?? 'GET').toUpperCase()
    const body = typeof init?.body === 'string' ? JSON.parse(init.body) : init?.body
    calls.push({ method, path, body })
    const ending = path.replace(/^\/convert\/v1\/jobs\/[^/]+/, '')
    const queued = replies.get(`${method} ${ending || '/{id}'}`)?.shift() ?? replies.get(`${method} ${path}`)?.shift()
    if (queued) return json({ detail: queued.detail }, queued.status, queued.headers)
    if (method === 'POST' && path === '/convert/v1/jobs') {
      const existing = [...jobs.values()].find((job) => job.key === body.idempotency_key)
      if (existing) return json(view(existing))
      const job = {
        id: `job-${++serial}`, key: body.idempotency_key, sha256: body.sha256, bytes: body.bytes,
        state: 'created' as const, result: null, review: null, failure: null,
      }
      jobs.set(job.id, job)
      return json(view(job), 201)
    }
    const id = path.split('/')[4]
    const job = jobs.get(id)
    if (!job) return json({ detail: 'job_not_found' }, 404)
    if (ending === '/source') {
      if (job.state !== 'created') return json({ detail: 'not_accepting_source' }, 409)
      job.state = 'queued'
      return json(view(job), 202)
    }
    if (ending === '/epub') return new Response(epub, { headers: { 'Content-Type': 'application/epub+zip' } })
    if (ending === '/acknowledge') job.state = 'acknowledged'
    if (ending === '/cancel') job.state = 'cancelled'
    return json(view(job))
  })
  return {
    jobs, calls, request, epub, result,
    reply(route: string, ...answers: Reply[]) { replies.set(route, [...replies.get(route) ?? [], ...answers]) },
    move(id: string, state: ConvertJob['state'], extra: Partial<ConvertJob> = {}) {
      Object.assign(jobs.get(id)!, { state, result: ['ready', 'review'].includes(state) ? result : null, ...extra })
    },
    paths: (method?: string) => calls.filter((call) => !method || call.method === method).map((call) => `${call.method} ${call.path}`),
  }
}

function memoryStore(saved?: Conversion[]) {
  const prefs = new Map<string, unknown>(saved ? [['conversions', saved]] : [])
  return {
    prefs,
    saved: () => (prefs.get('conversions') ?? []) as Conversion[],
    getPreference: async <T>(key: string) => structuredClone(prefs.get(key)) as T | undefined,
    setPreference: vi.fn(async <T>(key: string, value: T) => { prefs.set(key, structuredClone(value)) }),
  }
}

/** Lets promises and real I/O settle without moving the fake clock. */
async function until(check: () => boolean, rounds = 200) {
  for (let i = 0; i < rounds && !check(); i++) await new Promise((resolve) => setImmediate(resolve))
  expect(check()).toBe(true)
}
const later = (ms: number) => vi.advanceTimersByTimeAsync(ms)

let open: Conversions[] = []
function make(server: ReturnType<typeof fakeServer>, store = memoryStore(), extra: Partial<ConstructorParameters<typeof Conversions>[0]> = {}) {
  const imports: Blob[] = []
  const importEpub = vi.fn(async (blob: Blob, _keepLocal: boolean) => { imports.push(blob); return 'book-1' })
  const queue = new Conversions({ request: server.request, store, importEpub, ...extra })
  open.push(queue)
  return { queue, store, importEpub, imports }
}
const stage = (queue: Conversions, index = 0) => queue.items[index]?.stage

beforeEach(() => { vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] }) })
afterEach(() => {
  for (const queue of open.splice(0)) queue.dispose()
  vi.useRealTimers()
})

async function started(server: ReturnType<typeof fakeServer>, file = pdf(), store = memoryStore()) {
  const made = make(server, store)
  await made.queue.loaded
  made.queue.offer([file], 100 << 20)
  await made.queue.start(made.queue.items.at(-1)!.id)
  await until(() => stage(made.queue) === 'queued')
  return made
}

describe('checksum', () => {
  it('hashes in slices and matches the whole file', async () => {
    const big = new Blob([new Uint8Array(9 << 20).map((_, i) => i % 251)])
    expect(await hashFile(big)).toBe(await sha(big))
    expect(await hashFile(new Blob([]))).toBe(await sha(new Blob([])))
  })
})

describe('conversions', () => {
  it('converts a PDF: saves before each step, streams the File, imports, then acknowledges', async () => {
    const server = fakeServer()
    const store = memoryStore()
    const file = pdf()
    const { queue, importEpub } = make(server, store)
    await queue.loaded
    queue.offer([file], 100 << 20)
    expect(queue.items[0]).toMatchObject({ stage: 'confirm', name: 'Field notes.pdf', bytes: file.size, tooLarge: false })
    expect(store.saved()).toEqual([])

    // The key and checksum are saved before the job is created, the job id before the upload.
    const original = server.request.getMockImplementation()!
    const checksum = await sha(file)
    server.request.mockImplementation(async (path, init) => {
      if (path === '/convert/v1/jobs') {
        expect(store.saved()[0]).toMatchObject({ id: queue.items[0].id, sha256: checksum, stage: 'preparing' })
        expect(store.saved()[0].job).toBeUndefined()
      }
      if (init?.method === 'PUT') expect(store.saved()[0].job).toBe('job-1')
      return original(path, init)
    })
    await queue.start(queue.items[0].id)
    await until(() => stage(queue) === 'queued')
    const [create, upload] = server.calls
    expect(create).toEqual({ method: 'POST', path: '/convert/v1/jobs', body: {
      idempotency_key: queue.items[0].id, sha256: await sha(file), bytes: file.size, mode: 'local',
    } })
    expect(upload.path).toBe('/convert/v1/jobs/job-1/source')
    expect(upload.body).toBe(file)
    const put = server.request.mock.calls[1][1]!
    expect(new Headers(put.headers).get('Content-Type')).toBe('application/pdf')
    expect(store.saved()[0]).toMatchObject({ job: 'job-1', stage: 'queued' })

    server.move('job-1', 'running')
    await later(pollInterval)
    await until(() => stage(queue) === 'converting')
    server.move('job-1', 'ready')
    await later(pollInterval)
    await until(() => stage(queue) === 'done' && !queue.items[0].unacknowledged)
    expect(importEpub).toHaveBeenCalledTimes(1)
    expect(server.paths()).toEqual([
      'POST /convert/v1/jobs', 'PUT /convert/v1/jobs/job-1/source', 'GET /convert/v1/jobs/job-1', 'GET /convert/v1/jobs/job-1',
      'GET /convert/v1/jobs/job-1/epub', 'POST /convert/v1/jobs/job-1/acknowledge',
    ])
    expect(store.saved()[0]).toMatchObject({ stage: 'done', book: 'book-1', unacknowledged: false })
    // Nothing is in flight: no timer keeps polling.
    expect(vi.getTimerCount()).toBe(0)
  })

  it('imports a book that needs a look and keeps which pages, as plain data', async () => {
    const server = fakeServer()
    const { queue } = await started(server)
    server.move('job-1', 'review', { review: { reason: '2 pages need review', affected_pages: [{ page: 12, label: 'x' }, { page: 40, label: null }] } })
    await later(pollInterval)
    await until(() => stage(queue) === 'review' && !queue.items[0].unacknowledged)
    expect(queue.items[0]).toMatchObject({ book: 'book-1', reason: '2 pages need review', pages: [{ page: 12, label: 'x' }, { page: 40, label: null }] })
    expect(server.jobs.get('job-1')!.state).toBe('acknowledged')
  })

  it('acknowledges only after the import, and a retry downloads the same result again', async () => {
    const server = fakeServer()
    const { queue, importEpub } = await started(server)
    importEpub.mockRejectedValueOnce(new Error('Not an EPUB'))
    server.move('job-1', 'ready')
    await later(pollInterval)
    await until(() => stage(queue) === 'failed')
    expect(queue.items[0]).toMatchObject({ problem: 'import_failed', retryable: true })
    expect(server.paths('POST')).not.toContain('POST /convert/v1/jobs/job-1/acknowledge')
    await queue.retry(queue.items[0].id)
    await until(() => stage(queue) === 'done' && !queue.items[0].unacknowledged)
    expect(importEpub).toHaveBeenCalledTimes(2)
    expect(server.paths().filter((path) => path.endsWith('/epub'))).toHaveLength(2)
    expect(server.paths().at(-1)).toBe('POST /convert/v1/jobs/job-1/acknowledge')
  })

  it('the same PDF chosen twice is one conversion, and one book', async () => {
    const server = fakeServer()
    const file = pdf()
    const { queue, importEpub } = await started(server, file)
    queue.offer([new File([file], file.name, { type: file.type })], 100 << 20)
    await queue.start(queue.items[1].id)
    await until(() => queue.items.length === 1)
    expect(server.paths('POST')).toEqual(['POST /convert/v1/jobs'])
    server.move('job-1', 'ready')
    await later(pollInterval)
    await until(() => stage(queue) === 'done')
    expect(importEpub).toHaveBeenCalledTimes(1)
  })

  it('cancels a job in flight and stops polling it', async () => {
    const server = fakeServer()
    const { queue, store } = await started(server)
    await queue.cancel(queue.items[0].id)
    expect(queue.items).toEqual([])
    expect(store.saved()).toEqual([])
    expect(server.paths().at(-1)).toBe('POST /convert/v1/jobs/job-1/cancel')
    const count = server.calls.length
    await later(pollInterval * 4)
    expect(server.calls).toHaveLength(count)
  })

  it('aborts an upload under way when cancelled', async () => {
    const server = fakeServer()
    let signal: AbortSignal | undefined
    const { queue } = make(server)
    await queue.loaded
    const original = server.request.getMockImplementation()!
    server.request.mockImplementation(async (path, init) => {
      if (init?.method === 'PUT') {
        signal = init.signal ?? undefined
        return new Promise<Response>(() => undefined)
      }
      return original(path, init)
    })
    queue.offer([pdf()], 100 << 20)
    void queue.start(queue.items[0].id)
    await until(() => stage(queue) === 'uploading' && !!signal)
    await queue.cancel(queue.items[0].id)
    expect(signal!.aborted).toBe(true)
    expect(server.paths().at(-1)).toBe('POST /convert/v1/jobs/job-1/cancel')
  })

  it('never starts a PDF over the size limit', async () => {
    const server = fakeServer()
    const { queue } = make(server)
    await queue.loaded
    queue.offer([pdf()], 4)
    expect(queue.items[0].tooLarge).toBe(true)
    await queue.start(queue.items[0].id)
    expect(server.calls).toEqual([])
  })

  it('polls at most every five seconds, only while shown, and not at all when nothing is active', async () => {
    const server = fakeServer()
    let hidden = false
    let wake: () => void = () => undefined
    const store = memoryStore()
    const { queue } = make(server, store, { hidden: () => hidden, onVisibility: (listener) => { wake = listener; return () => undefined } })
    await queue.loaded
    expect(vi.getTimerCount()).toBe(0)
    queue.offer([pdf()], 100 << 20)
    await queue.start(queue.items[0].id)
    await until(() => stage(queue) === 'queued')
    const polls = () => server.paths('GET').length
    await later(pollInterval - 1_000)
    expect(polls()).toBe(0)
    await later(1_500)
    expect(polls()).toBe(1)
    await later(pollInterval * 6)
    expect(polls()).toBeLessThanOrEqual(7)

    hidden = true
    await later(pollInterval)
    const before = polls()
    await later(pollInterval * 10)
    expect(polls()).toBe(before)
    expect(vi.getTimerCount()).toBe(0)
    // Shown again after a long while: one poll now, the next one five seconds on.
    hidden = false
    wake()
    await later(0)
    expect(polls()).toBe(before + 1)
    await later(pollInterval - 1_000)
    expect(polls()).toBe(before + 1)
    await later(1_500)
    expect(polls()).toBe(before + 2)
  })
})

describe('resuming after a reload', () => {
  const saved = (patch: Partial<Conversion>): Conversion =>
    ({ id: 'key-0001', name: 'Field notes.pdf', bytes: pdf().size, stage: 'queued', ...patch })

  it('a reload during the checksum asks for the file again', async () => {
    const server = fakeServer()
    const { queue } = make(server, memoryStore([saved({ stage: 'preparing' })]))
    await queue.loaded
    expect(stage(queue)).toBe('reupload')
    await later(pollInterval * 2)
    expect(server.calls).toEqual([])
  })

  it('a job created before the reload is picked up with the same key, and the file chosen again goes to it', async () => {
    const server = fakeServer()
    const file = pdf()
    const store = memoryStore([saved({ stage: 'preparing', sha256: await sha(file) })])
    const { queue } = make(server, store)
    await queue.loaded
    await later(0)
    await until(() => stage(queue) === 'reupload')
    expect(server.calls[0].body).toMatchObject({ idempotency_key: 'key-0001' })
    expect(queue.items[0].job).toBe('job-1')
    // No upload without the file, and no polling while waiting for it.
    await later(pollInterval * 3)
    expect(server.paths()).toEqual(['POST /convert/v1/jobs'])

    // Confirmed before the reload: the same file goes ahead without asking again.
    queue.offer([file], 100 << 20)
    await until(() => queue.items.length === 1 && stage(queue) === 'queued')
    expect(queue.items[0].id).toBe('key-0001')
    expect(server.paths()).toEqual(['POST /convert/v1/jobs', 'GET /convert/v1/jobs/job-1', 'PUT /convert/v1/jobs/job-1/source'])
  })

  it('an upload cut off by the reload: needs the file if it did not arrive, carries on if it did', async () => {
    const server = fakeServer()
    server.jobs.set('job-7', { id: 'job-7', key: 'key-0001', sha256: 'x', bytes: 1, state: 'created', result: null, review: null, failure: null })
    server.jobs.set('job-8', { id: 'job-8', key: 'key-0002', sha256: 'y', bytes: 1, state: 'queued', result: null, review: null, failure: null })
    const { queue } = make(server, memoryStore([
      saved({ stage: 'uploading', job: 'job-7', sha256: 'x' }), saved({ id: 'key-0002', stage: 'uploading', job: 'job-8', sha256: 'y' }),
    ]))
    await queue.loaded
    await later(0)
    await until(() => stage(queue, 0) === 'reupload' && stage(queue, 1) === 'queued')
  })

  it('a queued or converting job keeps polling and is imported when ready', async () => {
    const server = fakeServer()
    server.jobs.set('job-7', { id: 'job-7', key: 'key-0001', sha256: 'x', bytes: 1, state: 'running', result: null, review: null, failure: null })
    const { queue, importEpub } = make(server, memoryStore([saved({ stage: 'queued', job: 'job-7' })]))
    await queue.loaded
    await later(0)
    await until(() => stage(queue) === 'converting')
    server.move('job-7', 'ready')
    await later(pollInterval)
    await until(() => stage(queue) === 'done' && !queue.items[0].unacknowledged)
    expect(importEpub).toHaveBeenCalledTimes(1)
  })

  it('an import interrupted by the reload downloads again; one that finished is only acknowledged', async () => {
    const server = fakeServer()
    for (const [id, key] of [['job-7', 'key-0001'], ['job-8', 'key-0002']]) {
      server.jobs.set(id, { id, key, sha256: 'x', bytes: 1, state: 'ready', result: server.result, review: null, failure: null })
    }
    const { queue, importEpub } = make(server, memoryStore([
      saved({ stage: 'importing', job: 'job-7' }),
      saved({ id: 'key-0002', stage: 'done', job: 'job-8', book: 'book-1', unacknowledged: true }),
    ]))
    await queue.loaded
    await later(0)
    await until(() => queue.items.every((item) => item.stage === 'done' && !item.unacknowledged))
    expect(importEpub).toHaveBeenCalledTimes(1)
    expect(server.paths().filter((path) => path.endsWith('/epub'))).toEqual(['GET /convert/v1/jobs/job-7/epub'])
    expect(server.paths('POST').sort()).toEqual(['POST /convert/v1/jobs/job-7/acknowledge', 'POST /convert/v1/jobs/job-8/acknowledge'])
  })

  it('a job purged or expired meanwhile is reported, with a retry', async () => {
    const server = fakeServer()
    server.jobs.set('job-8', { id: 'job-8', key: 'key-0002', sha256: 'y', bytes: 1, state: 'expired', result: null, review: null, failure: null })
    const { queue } = make(server, memoryStore([saved({ job: 'job-gone' }), saved({ id: 'key-0002', job: 'job-8' })]))
    await queue.loaded
    await later(0)
    await until(() => stage(queue, 0) === 'failed' && stage(queue, 1) === 'failed')
    expect(queue.items.map((item) => [item.problem, item.retryable])).toEqual([['job_not_found', true], ['expired', true]])
  })
})

describe('failures', () => {
  const codes: [string, boolean][] = [
    ['encrypted', false], ['too_many_pages', false], ['unreadable_pdf', false], ['unsupported', false],
    ['conversion_failed', true], ['timed_out', false], ['worker_error', true], ['worker_lost', true], ['invalid_result', true],
  ]
  it.each(codes)('a job that failed with %s keeps the reason, acknowledges, and offers a retry only when retryable', async (code, retryable) => {
    const server = fakeServer()
    const file = pdf()
    const { queue } = await started(server, file)
    server.move('job-1', 'failed', { failure: { code, retryable, detail: '<b>stage</b>' } })
    await later(pollInterval)
    await until(() => stage(queue) === 'failed' && !queue.items[0].unacknowledged)
    expect(queue.items[0]).toMatchObject({ problem: code, retryable, detail: '<b>stage</b>' })
    expect(server.jobs.get('job-1')!.state).toBe('acknowledged')
    await queue.retry(queue.items[0].id)
    if (!retryable) {
      expect(server.paths('POST').filter((path) => path === 'POST /convert/v1/jobs')).toHaveLength(1)
      return
    }
    // A new job with a new key, sent with the file this page still has.
    await until(() => stage(queue) === 'queued')
    const creates = server.calls.filter((call) => call.path === '/convert/v1/jobs')
    expect(creates).toHaveLength(2)
    expect((creates[1].body as { idempotency_key: string }).idempotency_key).not.toBe((creates[0].body as { idempotency_key: string }).idempotency_key)
    expect(server.calls.at(-1)!.body).toBe(file)
  })

  const refusals: [string, Reply, string, boolean][] = [
    ['403', { status: 403, detail: 'hosting_subscription_required' }, 'hosting_subscription_required', false],
    ['409', { status: 409, detail: 'idempotency_conflict' }, 'idempotency_conflict', true],
    ['413', { status: 413, detail: 'source_too_large' }, 'source_too_large', false],
    ['422', { status: 422, detail: [{ type: 'string_pattern_mismatch', loc: ['body', 'sha256'], msg: 'x' }] }, 'http_422', false],
    ['404', { status: 404, detail: 'Not Found' }, 'not_offered', false],
  ]
  it.each(refusals)('creating a job refused with %s stops with that reason', async (_, reply, problem, retryable) => {
    const server = fakeServer()
    server.reply('POST /convert/v1/jobs', reply)
    const { queue } = make(server)
    await queue.loaded
    queue.offer([pdf()], 100 << 20)
    await queue.start(queue.items[0].id)
    expect(queue.items[0]).toMatchObject({ stage: 'failed', problem, retryable })
  })

  it.each([
    [429, 'too_many_active_jobs', '60'], [429, 'daily_limit_reached', '3600'], [503, 'converter_unavailable', '60'],
  ])('a %i %s waits for Retry-After, then tries the same key again', async (status, detail, after) => {
    const server = fakeServer()
    server.reply('POST /convert/v1/jobs', { status, detail, headers: { 'Retry-After': after } })
    const { queue } = make(server)
    await queue.loaded
    queue.offer([pdf()], 100 << 20)
    await queue.start(queue.items[0].id)
    expect(queue.items[0]).toMatchObject({ stage: 'waiting', problem: detail })
    await later(Number(after) * 1000 / 2)
    expect(server.paths('POST')).toHaveLength(1)
    await later(Number(after) * 1000)
    await until(() => stage(queue) === 'queued')
    const keys = server.calls.filter((call) => call.path === '/convert/v1/jobs').map((call) => (call.body as { idempotency_key: string }).idempotency_key)
    expect(keys).toEqual([queue.items[0].id, queue.items[0].id])
  })

  it('an upload that stalled (408) or went to a server fault is sent again later', async () => {
    const server = fakeServer()
    server.reply('PUT /source', { status: 408, detail: 'upload_timed_out' }, { status: 502 })
    const { queue } = make(server)
    await queue.loaded
    queue.offer([pdf()], 100 << 20)
    await queue.start(queue.items[0].id)
    await until(() => queue.items[0].problem === 'upload_timed_out')
    expect(stage(queue)).toBe('uploading')
    // Thirty seconds, then a minute.
    await later(30_000)
    await until(() => server.paths('PUT').length === 2 && queue.items[0].attempts === 2)
    await later(59_000)
    expect(server.paths('PUT')).toHaveLength(2)
    await later(1_000)
    await until(() => stage(queue) === 'queued')
    expect(server.paths('PUT')).toHaveLength(3)
  })

  it('a retried upload that already arrived (409 not_accepting_source) reads the job instead', async () => {
    const server = fakeServer()
    server.reply('PUT /source', { status: 409, detail: 'not_accepting_source' })
    const { queue } = make(server)
    await queue.loaded
    queue.offer([pdf()], 100 << 20)
    const original = server.request.getMockImplementation()!
    server.request.mockImplementation(async (path, init) => {
      // The first upload's bytes landed; only its answer was lost.
      if (init?.method === 'PUT') server.move('job-1', 'queued')
      return original(path, init)
    })
    await queue.start(queue.items[0].id)
    await until(() => stage(queue) === 'queued')
    expect(server.paths()).toEqual(['POST /convert/v1/jobs', 'PUT /convert/v1/jobs/job-1/source', 'GET /convert/v1/jobs/job-1'])
  })

  it('an upload still arriving from earlier (409 upload_in_progress) waits, then is sent again', async () => {
    const server = fakeServer()
    server.reply('PUT /source', { status: 409, detail: 'upload_in_progress' })
    const { queue } = make(server)
    await queue.loaded
    queue.offer([pdf()], 100 << 20)
    await queue.start(queue.items[0].id)
    await until(() => stage(queue) === 'waiting')
    await later(60_000)
    await until(() => stage(queue) === 'queued')
    expect(server.paths('PUT')).toHaveLength(2)
  })

  it('a file that changed under its checksum (422) asks for the file again', async () => {
    const server = fakeServer()
    server.reply('PUT /source', { status: 422, detail: 'checksum_mismatch' })
    const { queue } = make(server)
    await queue.loaded
    queue.offer([pdf()], 100 << 20)
    await queue.start(queue.items[0].id)
    await until(() => stage(queue) === 'reupload')
    expect(queue.items[0].problem).toBe('checksum_mismatch')
  })

  it.each([
    [413, 'request body exceeds 4 bytes', 'request body exceeds 4 bytes'], [415, 'unsupported_media_type', 'unsupported_media_type'], [422, 'not_a_pdf', 'not_a_pdf'],
  ])('an upload refused with %i stops and cancels the job left waiting', async (status, detail, problem) => {
    const server = fakeServer()
    server.reply('PUT /source', { status, detail })
    const { queue } = make(server)
    await queue.loaded
    queue.offer([pdf()], 100 << 20)
    await queue.start(queue.items[0].id)
    await until(() => stage(queue) === 'failed' && server.paths().at(-1) === 'POST /convert/v1/jobs/job-1/cancel')
    expect(queue.items[0]).toMatchObject({ problem, retryable: false })
  })

  it('a result gone before the download (410) is reported, with a retry', async () => {
    const server = fakeServer()
    const { queue } = await started(server)
    server.move('job-1', 'ready')
    server.reply('GET /epub', { status: 410, detail: 'result_gone' })
    await later(pollInterval)
    await until(() => stage(queue) === 'failed')
    expect(queue.items[0]).toMatchObject({ problem: 'result_gone', retryable: true })
  })

  it('offline, the same step is tried on a later poll', async () => {
    const server = fakeServer()
    const { queue } = await started(server)
    const original = server.request.getMockImplementation()!
    server.request.mockImplementation(async () => { throw new TypeError('Failed to fetch') })
    await later(pollInterval)
    await until(() => queue.items[0].problem === 'network')
    expect(stage(queue)).toBe('queued')
    server.request.mockImplementation(original)
    server.move('job-1', 'running')
    await later(pollInterval * 2)
    await until(() => stage(queue) === 'converting' && !queue.items[0].problem)
  })
})

describe('choosing the file again', () => {
  const saved = (patch: Partial<Conversion>): Conversion =>
    ({ id: 'key-0001', name: 'Field notes.pdf', bytes: pdf().size, stage: 'reupload', ...patch })

  it('a file that cannot be read asks for it again, not for the network', async () => {
    const server = fakeServer()
    const { queue } = make(server)
    await queue.loaded
    const file = pdf()
    vi.spyOn(file, 'slice').mockImplementation(() => ({ arrayBuffer: () => Promise.reject(new DOMException('gone', 'NotReadableError')) }) as unknown as Blob)
    queue.offer([file], 100 << 20)
    await queue.start(queue.items[0].id)
    expect(queue.items[0]).toMatchObject({ stage: 'reupload', problem: 'file_unreadable' })
    expect(server.calls).toEqual([])
    // Chosen again, readable this time: the same row carries on.
    queue.offer([pdf()], 100 << 20)
    await until(() => stage(queue) === 'queued')
    expect(queue.items).toHaveLength(1)
  })

  it('a row with no checksum takes the file chosen again and creates its job', async () => {
    const server = fakeServer()
    const store = memoryStore([saved({ stage: 'preparing' })])
    const { queue } = make(server, store)
    await queue.loaded
    expect(stage(queue)).toBe('reupload')
    const file = pdf()
    queue.offer([file], 100 << 20)
    await until(() => stage(queue) === 'queued')
    expect(queue.items).toHaveLength(1)
    expect(queue.items[0]).toMatchObject({ id: 'key-0001', sha256: await sha(file) })
    expect(server.calls[0].body).toMatchObject({ idempotency_key: 'key-0001' })
  })

  it('a different file under the same name cancels the old job and replaces the row', async () => {
    const server = fakeServer()
    server.jobs.set('job-7', { id: 'job-7', key: 'key-0001', sha256: 'old', bytes: 1, state: 'created', result: null, review: null, failure: null })
    const store = memoryStore([saved({ job: 'job-7', sha256: 'old' })])
    const { queue } = make(server, store)
    await queue.loaded
    queue.offer([pdf()], 100 << 20)
    await until(() => stage(queue) === 'queued')
    expect(queue.items).toHaveLength(1)
    expect(queue.items[0].id).not.toBe('key-0001')
    expect(server.jobs.get('job-7')!.state).toBe('cancelled')
    expect(store.saved().map((item) => item.id)).toEqual([queue.items[0].id])
  })

  it('a file that changed under its checksum frees its job, and the file chosen again goes to the same place', async () => {
    const server = fakeServer()
    server.reply('PUT /source', { status: 422, detail: 'size_mismatch' })
    const { queue, store } = make(server)
    await queue.loaded
    queue.offer([pdf()], 100 << 20)
    const first = queue.items[0].id
    await queue.start(first)
    await until(() => stage(queue) === 'reupload' && server.jobs.get('job-1')!.state === 'cancelled')
    expect(queue.items[0]).toMatchObject({ problem: 'size_mismatch' })
    expect(queue.items[0].id).not.toBe(first)
    // The file as it is now: longer, same name.
    queue.offer([pdf('one, edited')], 100 << 20)
    await until(() => stage(queue) === 'queued')
    expect(queue.items).toHaveLength(1)
    expect(server.paths('POST').filter((path) => path === 'POST /convert/v1/jobs')).toHaveLength(2)
    expect(store.saved()).toHaveLength(1)
  })
})

describe('backing off', () => {
  it('an upload that keeps failing waits longer each time, then stops with a retry', async () => {
    const server = fakeServer()
    server.reply('PUT /source', ...Array.from({ length: 5 }, () => ({ status: 502 })))
    const { queue } = make(server)
    await queue.loaded
    queue.offer([pdf()], 100 << 20)
    await queue.start(queue.items[0].id)
    for (const [wait, count] of [[30_000, 2], [60_000, 3], [120_000, 4], [240_000, 5]]) {
      await later(wait - 1_000)
      expect(server.paths('PUT')).toHaveLength(count - 1)
      await later(1_000)
      await until(() => server.paths('PUT').length === count && (!!queue.items[0].retryAt || stage(queue) === 'failed'))
    }
    await until(() => stage(queue) === 'failed')
    expect(queue.items[0]).toMatchObject({ problem: 'upload_failed', retryable: true })
    await later(600_000)
    expect(server.paths('PUT')).toHaveLength(5)
    // The retry sends the same job's upload again.
    await queue.retry(queue.items[0].id)
    await until(() => stage(queue) === 'queued')
    expect(server.paths('PUT')).toHaveLength(6)
    expect(server.paths('POST').filter((path) => path === 'POST /convert/v1/jobs')).toHaveLength(1)
  })

  it('a download that does not match the result is fetched again later, not every poll', async () => {
    const server = fakeServer()
    const { queue, importEpub } = await started(server)
    server.move('job-1', 'ready', { result: { bytes: server.epub.size, sha256: '0'.repeat(64) } })
    await later(pollInterval)
    await until(() => queue.items[0].attempts === 1)
    expect(stage(queue)).toBe('importing')
    await later(20_000)
    expect(server.paths().filter((path) => path.endsWith('/epub'))).toHaveLength(1)
    for (let attempt = 2; attempt <= 5; attempt++) {
      await later(600_000)
      await until(() => server.paths().filter((path) => path.endsWith('/epub')).length === attempt)
    }
    await until(() => stage(queue) === 'failed')
    expect(queue.items[0]).toMatchObject({ problem: 'download_failed', retryable: true })
    expect(server.paths().filter((path) => path.endsWith('/epub'))).toHaveLength(5)
    expect(importEpub).not.toHaveBeenCalled()
  })

  it('a 503 without converter_unavailable is a passing fault, not a stopped converter', async () => {
    const server = fakeServer()
    const { queue } = await started(server)
    server.reply('GET /{id}', { status: 503 })
    await later(pollInterval)
    await until(() => queue.items[0].problem === 'network')
    expect(stage(queue)).toBe('queued')
  })
})

describe('finishing', () => {
  it('another edition with the same identifier never asks: the list offers to keep it in this browser', async () => {
    const server = fakeServer()
    const { queue, importEpub } = await started(server)
    importEpub.mockRejectedValueOnce(Object.assign(new Error('conflict'), { name: 'EditionConflict' }))
    server.move('job-1', 'ready')
    await later(pollInterval)
    await until(() => stage(queue) === 'failed')
    expect(queue.items[0]).toMatchObject({ problem: 'edition_conflict', retryable: true })
    expect(server.jobs.get('job-1')!.state).toBe('ready')
    await queue.retry(queue.items[0].id)
    await until(() => stage(queue) === 'done' && !queue.items[0].unacknowledged)
    expect(importEpub.mock.calls.map((call) => call[1])).toEqual([false, true])
  })

  it('cancelling a job that finished meanwhile acknowledges it', async () => {
    const server = fakeServer()
    const { queue } = await started(server)
    server.reply('POST /cancel', { status: 409, detail: 'not_cancellable' })
    await queue.cancel(queue.items[0].id)
    expect(server.paths('POST').slice(-2)).toEqual(['POST /convert/v1/jobs/job-1/cancel', 'POST /convert/v1/jobs/job-1/acknowledge'])
  })

  it('dismissed offline, a result stays saved until the server is told', async () => {
    const server = fakeServer()
    const { queue, store } = await started(server)
    const original = server.request.getMockImplementation()!
    server.request.mockImplementation(async (path, init) => {
      if (path.endsWith('/acknowledge')) throw new TypeError('Failed to fetch')
      return original(path, init)
    })
    server.move('job-1', 'ready')
    await later(pollInterval)
    await until(() => stage(queue) === 'done')
    await queue.dismiss(queue.items[0].id)
    expect(queue.items[0]).toMatchObject({ dismissed: true, unacknowledged: true })
    expect(store.saved()).toHaveLength(1)
    server.request.mockImplementation(original)
    await later(pollInterval)
    await until(() => queue.items.length === 0)
    expect(store.saved()).toEqual([])
    expect(server.jobs.get('job-1')!.state).toBe('acknowledged')
  })
})

describe('several tabs', () => {
  let serial = 0
  const tabs = (shared: boolean) => {
    const server = fakeServer()
    const store = memoryStore()
    const name = `test-conversions-${++serial}`
    const tab = () => make(server, store, shared
      ? { name, locks: navigator.locks, channel: new BroadcastChannel(name) }
      : {}).queue
    return { server, store, tab }
  }

  it('one tab drives a conversion; another shows it, keeps its own, and takes over when the first closes', async () => {
    const { server, store, tab } = tabs(true)
    const a = tab(), b = tab()
    await Promise.all([a.loaded, b.loaded])
    a.offer([pdf('one', 'One.pdf')], 100 << 20)
    await a.start(a.items[0].id)
    await until(() => stage(a) === 'queued' && stage(b) === 'queued')
    b.offer([pdf('two', 'Two.pdf')], 100 << 20)
    await b.start(b.items.at(-1)!.id)
    await until(() => store.saved().length === 2 && a.items.length === 2 && b.items.every((item) => item.stage === 'queued'))
    const polled = () => server.paths('GET').sort()
    await later(pollInterval)
    await until(() => polled().length === 2)
    // Each job is polled once a round, by the tab that drives it.
    expect(polled()).toEqual(['GET /convert/v1/jobs/job-1', 'GET /convert/v1/jobs/job-2'])

    server.move('job-1', 'running')
    await later(pollInterval)
    await until(() => stage(b, 0) === 'converting')
    a.dispose()
    server.move('job-1', 'ready')
    await later(pollInterval)
    await until(() => stage(b, 0) === 'done' && !b.items[0].unacknowledged)
    expect(store.saved().map((item) => item.stage)).toEqual(['done', 'queued'])
  })

  it('a tab cancels a conversion another drives: that tab stops it too', async () => {
    const { server, store, tab } = tabs(true)
    const a = tab(), b = tab()
    await Promise.all([a.loaded, b.loaded])
    a.offer([pdf()], 100 << 20)
    await a.start(a.items[0].id)
    await until(() => stage(b) === 'queued')
    await b.cancel(b.items[0].id)
    await until(() => a.items.length === 0 && store.saved().length === 0)
    const count = server.calls.length
    await later(pollInterval * 3)
    expect(server.calls).toHaveLength(count)
  })

  it('without Web Locks, each save keeps what another tab saved', async () => {
    const { server, store, tab } = tabs(false)
    const a = tab(), b = tab()
    await Promise.all([a.loaded, b.loaded])
    // Both loaded the empty list; each saves over a fresh read.
    a.offer([pdf('one', 'One.pdf')], 100 << 20)
    await a.start(a.items[0].id)
    b.offer([pdf('two', 'Two.pdf')], 100 << 20)
    await b.start(b.items[0].id)
    await until(() => store.saved().length === 2)
    server.move('job-1', 'running')
    await later(pollInterval)
    await until(() => store.saved().find((item) => item.name === 'One.pdf')?.stage === 'converting')
    expect(store.saved().map((item) => item.name).sort()).toEqual(['One.pdf', 'Two.pdf'])
  })
})
