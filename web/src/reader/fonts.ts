import normal from '../../../reader/src/main/assets/fonts/Literata.ttf?url'
import italic from '../../../reader/src/main/assets/fonts/Literata-Italic.ttf?url'

export async function loadReaderFonts(signal: AbortSignal) {
  const loaded = await Promise.all([normal, italic].map(async path => {
    const response = await fetch(path, { signal, credentials: 'omit' })
    if (!response.ok) throw new Error('Reader font unavailable')
    return response.blob()
  }))
  signal.throwIfAborted()
  const urls = loaded.map(blob => URL.createObjectURL(blob))
  return {
    css: urls.map((url, index) => `@font-face {
      font-family: 'Quire Literata'; src: url("${url}") format('truetype');
      font-style: ${index ? 'italic' : 'normal'}; font-weight: 200 900; font-display: swap;
    }`).join('\n'),
    release: () => urls.forEach(url => URL.revokeObjectURL(url)),
  }
}
