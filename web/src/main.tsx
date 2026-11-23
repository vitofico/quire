import { createRoot } from 'react-dom/client'
import App from './App'
import './style.css'

createRoot(document.getElementById('root')!).render(<App />)
if (import.meta.env.PROD && 'serviceWorker' in navigator) {
  const changed = () => window.dispatchEvent(new Event('quire-offline-status'))
  window.addEventListener('load', () => {
    navigator.serviceWorker
      .register('/quire/sw.js', { scope: '/quire/' })
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
