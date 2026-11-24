import { createRoot } from 'react-dom/client'
import App from './App'
import './style.css'

createRoot(document.getElementById('root')!).render(<App />)
if (import.meta.env.PROD && 'serviceWorker' in navigator) {
  const changed = () => window.dispatchEvent(new Event('quire-offline-status'))
  // The reader lives at `/` on a host of its own, and under `/quire/` on a shared one.
  const scope = location.pathname === '/' ? '/' : '/quire/'
  window.addEventListener('load', () => {
    if (scope === '/')
      // The worker from before this host moved the reader to `/` controls nothing now.
      void navigator.serviceWorker.getRegistrations().then((all) =>
        all
          .filter((old) => new URL(old.scope).pathname === '/quire/')
          .forEach((old) => void old.unregister()),
      )
    navigator.serviceWorker
      .register('/quire/sw.js', { scope })
      .then((registration) => {
        const track = () =>
          registration.installing?.addEventListener('statechange', changed)
        track()
        registration.addEventListener('updatefound', track)
        changed()
      })
      .catch(changed)
  })
}
