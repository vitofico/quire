import { useCallback, useEffect, useRef, useState } from 'react'
import {
  openReplica,
  type Replica,
  type ReplicaSnapshot,
  type SyncTransport,
} from './data'
export const empty: ReplicaSnapshot = {
  books: [],
  progress: [],
  highlights: [],
  pending: 0,
  readyToSync: false,
}
export function useReplica(
  namespace: string,
  request: SyncTransport | undefined,
  report: (message: string) => void,
  syncEnabled: boolean,
) {
  const [replica, setReplica] = useState<Replica | null>(null)
  const [snapshot, setSnapshot] = useState(empty)
  const [busy, setBusy] = useState(false)
  const failures = useRef(0)
  const running = useRef<Promise<void> | null>(null)
  useEffect(() => {
    let closed = false
    let db: Replica | undefined,
      unsubscribe: (() => void) | undefined,
      serial = 0
    setReplica(null)
    setSnapshot(empty)
    openReplica(namespace, request, { syncEnabled })
      .then(async (value) => {
        if (closed) {
          value.close()
          return
        }
        db = value
        const refresh = async () => {
          const revision = ++serial
          const result = await value.snapshot()
          if (!closed && serial === revision) setSnapshot(result)
        }
        unsubscribe = value.subscribe(() => {
          void refresh().catch((e) => {
            if (!closed) report(String(e))
          })
        })
        await refresh()
        if (!closed) setReplica(value)
      })
      .catch((e) => {
        if (!closed) report(String(e))
      })
    return () => {
      closed = true
      unsubscribe?.()
      db?.close()
    }
  }, [namespace, request, report, syncEnabled])
  const sync = useCallback(async () => {
    if (!replica || !request || !navigator.onLine) return
    if (!running.current) {
      setBusy(true)
      running.current = replica
        .sync()
        .then(
          () => {
            failures.current = 0
          },
          (error) => {
            failures.current++
            throw error
          },
        )
        .finally(() => {
          running.current = null
          setBusy(false)
        })
    }
    return running.current
  }, [replica, request])
  useEffect(() => {
    if (!replica || !request) return
    const run = () => {
      void sync().catch((e) => report(String(e)))
    }
    run()
    const visible = () => {
      if (document.visibilityState === 'visible') run()
    }
    window.addEventListener('online', run)
    document.addEventListener('visibilitychange', visible)
    return () => {
      window.removeEventListener('online', run)
      document.removeEventListener('visibilitychange', visible)
    }
  }, [sync, request, replica, report])
  useEffect(() => {
    if (!snapshot.readyToSync || !request || busy || !navigator.onLine) return
    const id = setTimeout(
      () => {
        void sync().catch((e) => report(String(e)))
      },
      Math.min(60000, 1100 * 2 ** Math.min(6, failures.current)),
    )
    return () => clearTimeout(id)
  }, [busy, snapshot.readyToSync, sync, request, report])
  return { replica, snapshot, sync, busy }
}
