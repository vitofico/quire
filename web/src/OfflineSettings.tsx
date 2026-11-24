import { useEffect, useState } from 'react'

export function OfflineSettings({
  t,
  downloads,
}: {
  t: (s: string) => string
  downloads: number
}) {
  const [ready, setReady] = useState(false),
    [update, setUpdate] = useState(false)
  const [bytes, setBytes] = useState<number>(),
    [persistent, setPersistent] = useState(false)
  const [message, setMessage] = useState('')
  useEffect(() => {
    let mounted = true
    const worker = navigator.serviceWorker
    async function refresh() {
      const registration = await worker?.getRegistration('/quire/')
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
  return (
    <section className="panel">
      <h2>{t('Storage')}</h2>
      <p>{t(ready ? 'Available offline' : 'Offline app cache unavailable')}</p>
      <p>
        {t('Downloaded books')}: {downloads}
        {bytes != null &&
          ' · ' +
            t('Storage used') +
            ': ' +
            Math.ceil(bytes / (1024 * 1024)) +
            ' MB'}
      </p>
      {typeof navigator.storage?.persist === 'function' && !persistent && (
        <button
          onClick={async () => {
            try {
              const keep = await navigator.storage.persist()
              setPersistent(keep)
              setMessage(
                t(
                  keep
                    ? 'Downloads protected'
                    : 'Storage is managed by your browser',
                ),
              )
            } catch {
              setMessage(t('Storage is managed by your browser'))
            }
          }}
        >
          {t('Keep downloads on this device')}
        </button>
      )}
      {message && <p role="status">{message}</p>}
      {update && (
        <p>
          {t('An update is ready. Close all Quire tabs and reopen to use it.')}
        </p>
      )}
    </section>
  )
}
