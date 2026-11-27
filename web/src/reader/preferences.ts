export type Palette = 'paper' | 'sage' | 'ink' | 'midnight'
export type Font = 'serif' | 'sans' | 'mono' | 'publisher'

export interface ReaderPreferences {
  palette: Palette
  font: Font
  size: number
  spacing: number
  margin: number
  layout: keyof typeof pageLayouts
}

export const pageLayouts = {auto: 'Automatic', single: 'Single page'} as const
export const defaults: ReaderPreferences = {palette: 'paper', font: 'serif', size: 19, spacing: 1.65, margin: 32, layout: 'auto'}
export const palettes: Record<Palette, {background: string; text: string; link: string}> = {
  paper: {background: '#f9f5ea', text: '#302820', link: '#72502a'},
  sage: {background: '#e8ecde', text: '#233528', link: '#2d5a4b'},
  ink: {background: '#242420', text: '#e6dfd1', link: '#d8b98b'},
  midnight: {background: '#161c2b', text: '#dce6f5', link: '#a1c7e8'},
}
export const fonts: Record<Font, string> = {
  serif: '"Quire Literata", Georgia, serif',
  sans: 'system-ui, sans-serif',
  mono: 'ui-monospace, monospace',
  publisher: 'Georgia, serif',
}

/** Until a reader picks a palette, the page follows the app's light or dark theme. */
export function shellPalette(): Palette {
  const theme = document.documentElement.dataset.theme
  return theme && Object.hasOwn(palettes, theme) ? theme as Palette : defaults.palette
}

export function sanitizePreferences(value: Partial<ReaderPreferences> | undefined): ReaderPreferences {
  return {
    palette: value?.palette && Object.hasOwn(palettes, value.palette) ? value.palette : shellPalette(),
    font: value?.font && Object.hasOwn(fonts, value.font) ? value.font : defaults.font,
    size: bounded(value?.size, 14, 36, defaults.size),
    spacing: bounded(value?.spacing, 1.2, 2.4, defaults.spacing),
    margin: bounded(value?.margin, 12, 64, defaults.margin),
    layout: value?.layout === 'single' ? 'single' : defaults.layout,
  }
}

function bounded(value: unknown, min: number, max: number, fallback: number) {
  return typeof value === 'number' && Number.isFinite(value) && value >= min && value <= max ? value : fallback
}

export interface PauseBookmark { locator: string; passage: string; note: string }

export function chapterStyles(prefs: ReaderPreferences) {
  const palette = palettes[prefs.palette]
  return `
    html { background: ${palette.background}; color: ${palette.text}; overflow-wrap: break-word; overflow-anchor: none; }
    /* Pages flow sideways. The reader advances by one window, including in spreads. */
    :root:not([data-quire-fixed]) { --qr-column: calc(100vh - 2 * min(${prefs.margin}px, 5vh));
      height: 100vh; box-sizing: border-box; overflow: hidden; padding: min(${prefs.margin}px, 5vh) 0;
      column-count: 1; column-gap: 0; column-fill: auto; direction: ltr; }
    ${prefs.layout === 'auto' ? `
      @media (min-width: 75rem) {
        :root:not([data-quire-fixed]) { column-count: 2; }
        :root:not([data-quire-fixed]):has(body:dir(rtl)) { direction: rtl; }
        /* An empty, unfragmentable page lets an odd last spread align,
           including in engines that ignore forced column breaks. */
        :root:not([data-quire-fixed]) body::after {
          content: ''; display: block; block-size: var(--qr-column); overflow: hidden;
        }
      }` : ''}
    body { font: ${prefs.size}px/${prefs.spacing} ${fonts[prefs.font]}; max-width: 42rem;
      padding: 0 min(${prefs.margin}px, 8vw); margin: 0 auto; box-sizing: content-box; }
    ${prefs.font !== 'publisher' ? `body :where(p, span, div, h1, h2, h3, h4, h5, h6, blockquote, li, table, td, em, strong, a) { font-family: ${fonts[prefs.font]} !important; }` : ''}
    img, svg, video { max-width: 100%; max-height: var(--qr-column, calc(100vh - 3rem)); break-inside: avoid; }
    img { object-fit: contain; }
    pre { overflow: auto; }
    a { color: ${palette.link}; }
    :focus-visible { outline: 2px solid currentColor; outline-offset: 3px; }
    table { max-width: 100%; }
    ::highlight(quire-yellow) { background: #f2d1689c; }
    ::highlight(quire-green) { background: #88c796ab; }
    ::highlight(quire-pink) { background: #e99cb5ab; }
    ::highlight(quire-active) { background: #d88940b3; text-decoration: underline; }
    ::highlight(quire-yellow), ::highlight(quire-green), ::highlight(quire-pink), ::highlight(quire-active) { color: #1d1a14; }
  `
}
