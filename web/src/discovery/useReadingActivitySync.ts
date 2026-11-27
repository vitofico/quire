import { useEffect, useRef, useState } from 'react'
import type { ReadingSession, Replica } from '../data'
import { discoveryJson, isHttpStatus, jsonBody, type DiscoveryRequest } from './api'

const path = '/library/v1/reading-activity'
const preference = 'sync-reading-activity'

type Activity = {
  id: string
  book_key: string
  title: string
  started_at: number
  utc_offset_seconds: number
  active_seconds: number
  words?: number | null
}
type Cutoff = {cleared_before: number}
type Page = Cutoff & {sessions: Activity[]; next: string | null}
type Snapshot = {enabled: boolean; cutoff: number; sessions: ReadingSession[]; failed?: boolean}
const empty: Snapshot = {enabled: false, cutoff: 0, sessions: []}

function fromActivity(row: Activity): ReadingSession {
  return {
    id: row.id, bookId: row.book_key, title: row.title, startedAt: row.started_at,
    utcOffsetMinutes: row.utc_offset_seconds / 60, seconds: row.active_seconds, words: row.words ?? undefined,
  }
}

function toActivity(row: ReadingSession): Activity {
  return {
    id: row.id, book_key: row.bookId, title: row.title.slice(0, 512), started_at: row.startedAt,
    utc_offset_seconds: Math.round(row.utcOffsetMinutes * 60), active_seconds: row.seconds,
    words: row.words === undefined ? undefined : Math.round(row.words),
  }
}

function newer(local: ReadingSession, remote?: ReadingSession) {
  return !remote || local.seconds > remote.seconds
    || (local.seconds === remote.seconds && (local.words ?? -1) > (remote.words ?? -1))
}

export function mergeReadingActivity(local: ReadingSession[], remote: ReadingSession[], cutoff: number): ReadingSession[] {
  const merged = new Map(remote.map(row => [row.id, row]))
  for (const row of local) {
    if (row.startedAt > cutoff && newer(row, merged.get(row.id))) merged.set(row.id, row)
  }
  return [...merged.values()]
}

async function pull(request: DiscoveryRequest, signal: AbortSignal) {
  const remote = new Map<string, ReadingSession>()
  let cursor: string | null = null
  let cutoff = 0
  do {
    const page: Page = await discoveryJson(request, cursor ? `${path}?after=${encodeURIComponent(cursor)}` : path, {signal})
    if (cursor && page.cleared_before !== cutoff) throw new Error('Reading activity changed during sync')
    cutoff = page.cleared_before
    for (const row of page.sessions) remote.set(row.id, fromActivity(row))
    cursor = page.next
  } while (cursor)
  return {remote, cutoff}
}

export function useReadingActivitySync(storage: Replica | null | undefined, request?: DiscoveryRequest) {
  const [result, setResult] = useState<{storage: Replica; request: DiscoveryRequest; value: Snapshot}>()
  const [busy, setBusy] = useState(false)
  const [revision, setRevision] = useState(0)
  const operation = useRef<AbortController | undefined>(undefined)
  const refresh = () => setRevision(value => value + 1)
  const snapshot = result && result.storage === storage && result.request === request ? result.value : undefined

  useEffect(() => {
    const replica = storage
    const transport = request
    if (!replica || !transport) return
    const controller = new AbortController()
    const {signal} = controller
    let running = false
    const update = (get: (current: Snapshot) => Snapshot) => {
      if (!signal.aborted) setResult(previous => ({
        storage, request, value: get(previous?.storage === storage && previous.request === request ? previous.value : empty),
      }))
    }

    async function sync() {
      if (running || signal.aborted || operation.current || document.hidden) return
      running = true
      try {
        const enabled = await replica.getPreference<boolean>(preference) === true
        if (!enabled) {
          update(() => empty)
          return
        }
        update(previous => ({...previous, enabled}))
        const {remote, cutoff} = await pull(transport, signal)
        signal.throwIfAborted()
        await replica.applyReadingActivityReset(cutoff)
        update(() => ({enabled, cutoff, sessions: [...remote.values()]}))
        const [, local] = await replica.readingStatistics()
        const pending = local.filter(row => row.startedAt > cutoff && newer(row, remote.get(row.id)))
        for (let index = 0; index < pending.length; index += 100) {
          if (await replica.getPreference<boolean>(preference) !== true) return
          await discoveryJson<void>(transport, path, {
            ...jsonBody('PUT', {cleared_before: cutoff, sessions: pending.slice(index, index + 100).map(toActivity)}, signal),
          })
        }
      } catch {
        update(previous => ({...previous, failed: true}))
      } finally {
        running = false
      }
    }

    void sync()
    const timer = window.setInterval(sync, 60_000)
    window.addEventListener('online', sync)
    window.addEventListener('hashchange', sync)
    document.addEventListener('visibilitychange', sync)
    return () => {
      controller.abort()
      window.clearInterval(timer)
      window.removeEventListener('online', sync)
      window.removeEventListener('hashchange', sync)
      document.removeEventListener('visibilitychange', sync)
    }
  }, [storage, request, revision, busy])

  useEffect(() => () => { operation.current?.abort() }, [storage, request])

  async function change(action: (storage: Replica, request: DiscoveryRequest, signal: AbortSignal) => Promise<void>) {
    if (!storage || !request || operation.current) return
    const controller = new AbortController()
    operation.current = controller
    setBusy(true)
    try {
      await action(storage, request, controller.signal)
    } finally {
      operation.current = undefined
      setBusy(false)
    }
  }

  return {
    available: Boolean(storage && request), snapshot, busy, refresh,
    setEnabled: (enabled: boolean) => change(async (storage, request, signal) => {
      if (enabled) {
        // Verify this server can sync before storing consent on this device.
        await discoveryJson<Page>(request, path, {signal})
      }
      signal.throwIfAborted()
      await storage.setPreference(preference, enabled)
      setResult(({storage, request, value: {...empty, enabled}}))
    }),
    clear: () => change(async (storage, request, signal) => {
      const {cleared_before: cutoff} = await discoveryJson<Cutoff>(request, path, {method: 'DELETE', signal})
      signal.throwIfAborted()
      await storage.applyReadingActivityReset(cutoff)
      setResult({storage, request, value: {enabled: true, cutoff, sessions: []}})
    }),
  }
}

export type ReadingActivitySync = ReturnType<typeof useReadingActivitySync>
