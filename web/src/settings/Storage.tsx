import { useEffect, useState } from 'react'
import { ask } from '../ask'
import { Setting, type Translate } from './Settings'

/** Whether the app works offline, what this browser holds, and keeping or clearing the downloads. */
export function StorageSettings({ t, downloads, removeDownloads }: {
  t: Translate
  downloads: number
  removeDownloads: () => Promise<void>
}) {
  const [ready, setReady] = useState(false),
    [update, setUpdate] = useState(false)
  const [bytes, setBytes] = useState<number>(),
    [persistent, setPersistent] = useState(false)
  const [message, setMessage] = useState(''),
    [removing, setRemoving] = useState(false)
  useEffect(() => {
    let mounted = true
    const worker = navigator.serviceWorker
    async function refresh() {
      const registration = await worker?.getRegistration()
      if (mounted) {
        setReady(!!registration?.active)
        setUpdate(!!registration?.waiting)
      }
      const estimate = await navigator.storage?.estimate?.()
      const keep = await navigator.storage?.persisted?.()
      if (mounted) {
        setBytes(estimate?.usage)
        setPersistent(keep ?? false)
      }
    }
    const read = () => {
      void refresh().catch(() => {})
    }
    read()
    window.addEventListener('quire-offline-status', read)
    worker?.addEventListener('controllerchange', read)
    return () => {
      mounted = false
      window.removeEventListener('quire-offline-status', read)
      worker?.removeEventListener('controllerchange', read)
    }
  }, [downloads])
  const canPersist = typeof navigator.storage?.persist === 'function'
  return (
    <>
      <dl className="facts">
        <div><dt>{t('Offline use')}</dt><dd>{t(ready ? 'Available offline' : 'Offline app cache unavailable')}</dd></div>
        <div><dt>{t('Downloaded books')}</dt><dd>{downloads}</dd></div>
        {bytes != null && <div><dt>{t('Storage used')}</dt><dd>{Math.ceil(bytes / (1024 * 1024)) + ' MB'}</dd></div>}
      </dl>
      {update && <p className="notice">{t('An update is ready. Close all Quire tabs and reopen to use it.')}</p>}
      {canPersist && (
        <Setting
          action
          label={t('Keep downloads on this device')}
          description={t('Asks your browser not to clear downloaded books when it runs short of space.')}
        >
          {({ id, describedBy }) => persistent ? (
            <p className="setting-value" id={id}>{t('Downloads protected')}</p>
          ) : (
            <button
              id={id}
              type="button"
              aria-describedby={describedBy}
              onClick={async () => {
                try {
                  const keep = await navigator.storage.persist()
                  setPersistent(keep)
                  setMessage(keep ? '' : t('Storage is managed by your browser'))
                } catch {
                  setMessage(t('Storage is managed by your browser'))
                }
              }}
            >
              {t('Protect downloads')}
            </button>
          )}
        </Setting>
      )}
      {downloads > 0 && (
        <Setting
          action
          label={t('Remove all downloaded books')}
          description={t('Frees space in this browser. Your library, reading progress and passages stay.')}
        >
          {({ id, describedBy }) => (
            <button
              id={id}
              type="button"
              className="danger"
              aria-describedby={describedBy}
              disabled={removing}
              onClick={async () => {
                const sure = await ask(t('Remove all {count} downloaded books from this browser?').replace('{count}', String(downloads)), {
                  confirm: t('Remove all'), cancel: t('Cancel'), destructive: true,
                })
                if (!sure) return
                setRemoving(true)
                setMessage('')
                try {
                  await removeDownloads()
                } catch {
                  setMessage(t('Quire could not remove every download. Try again.'))
                } finally {
                  setRemoving(false)
                }
              }}
            >
              {t(removing ? 'Removing…' : 'Remove all')}
            </button>
          )}
        </Setting>
      )}
      {message && <p role="status">{message}</p>}
    </>
  )
}
