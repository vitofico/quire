import { resolveEpubHref, type Epub } from '../books/epub'

const textProperties = new Set([
  'text-align', 'text-indent', 'text-transform', 'text-decoration-line', 'white-space',
  'font-family', 'font-weight', 'font-style', 'font-variant', 'vertical-align', 'letter-spacing',
  'direction', 'list-style-type', 'page-break-before', 'break-before', 'border-collapse',
  'margin', 'margin-top', 'margin-bottom', 'margin-left', 'margin-right',
])
const fixedProperties = new Set([
  'position', 'top', 'right', 'bottom', 'left', 'width', 'height', 'font-size', 'line-height',
  'color', 'background-color', 'transform', 'transform-origin',
])

export function declarations(style: CSSStyleDeclaration, fixed: boolean): string {
  return Array.from(style).filter(key => textProperties.has(key) || (fixed && fixedProperties.has(key)))
    .map(key => `${key}:${style.getPropertyValue(key)}`).join(';')
}

const fontTypes = new Set([
  'font/otf', 'font/ttf', 'font/woff', 'font/woff2', 'application/font-woff',
  'application/vnd.ms-opentype', 'application/x-font-ttf', 'application/x-font-truetype',
])
const cssLimit = 1024 * 1024
const fontLimit = 12 * 1024 * 1024

export function stylesheetRules(source: Document, epub: Epub, href: string, fixed: boolean, urls: Map<string, string>) {
  const rules: string[] = []
  let remaining = cssLimit
  function use(css: string, base: string) {
    if (!css || css.length > remaining) return
    remaining -= css.length
    const sheet = new CSSStyleSheet()
    sheet.replaceSync(css)
    for (const rule of Array.from(sheet.cssRules)) {
      if (rule.type === CSSRule.STYLE_RULE) {
        const style = rule as CSSStyleRule
        const values = declarations(style.style, fixed)
        if (values) rules.push(`${style.selectorText}{${values}}`)
      }
      if (rule.type === CSSRule.FONT_FACE_RULE) {
        const { style } = rule as CSSFontFaceRule
        const family = style.getPropertyValue('font-family')
        const declared = style.getPropertyValue('src')
        // Pick a single package-local font. The parsed CSSOM serializes URLs
        // in quoted form. Unsupported formats and original URL strings are discarded.
        for (const match of declared.matchAll(/url\("([^"\\]+)"\)/g)) {
          const path = resolveEpubHref(base, match[1])?.split('#')[0]
          const mediaType = path && epub.mediaType(path)
          if (!family || !path || !mediaType || !fontTypes.has(mediaType)) continue
          let blob = urls.get(path)
          if (!blob) {
            const bytes = epub.bytes(path)
            if (!bytes || bytes.length > fontLimit) continue
            blob = URL.createObjectURL(new Blob([new Uint8Array(bytes)], {type: mediaType}))
            urls.set(path, blob)
          }
          const descriptors = ['font-style', 'font-weight', 'font-stretch'].map(key => {
            const value = style.getPropertyValue(key)
            return value ? `${key}:${value};` : ''
          }).join('')
          rules.push(`@font-face{font-family:${family};src:url("${blob}");font-display:swap;${descriptors}}`)
          break
        }
      }
    }
  }
  for (const element of source.querySelectorAll('style, link[rel~="stylesheet"]')) {
    if (element.localName === 'style') use(element.textContent ?? '', href)
    else {
      const target = resolveEpubHref(href, element.getAttribute('href') ?? '')
      if (target && epub.mediaType(target) === 'text/css') {
        const bytes = epub.bytes(target)
        if (bytes && bytes.length <= remaining) use(epub.text(target), target)
      }
    }
  }
  return rules.join('\n')
}

export function fixedPageSize(source: Document): {width: number; height: number} | undefined {
  const viewport = source.querySelector('meta[name="viewport"]')?.getAttribute('content') ?? ''
  const width = Number(viewport.match(/\bwidth\s*=\s*(\d+)/i)?.[1])
  const height = Number(viewport.match(/\bheight\s*=\s*(\d+)/i)?.[1])
  if (width >= 64 && width <= 10000 && height >= 64 && height <= 10000) return {width, height}
}

export function mountFixedPage(doc: Document, size: {width: number; height: number}) {
  const fit = () => {
    const availableWidth = doc.documentElement.clientWidth
    const availableHeight = doc.documentElement.clientHeight
    const ratio = Math.min(1, availableWidth / size.width, availableHeight / size.height)
    const body = doc.body.style
    body.setProperty('zoom', String(ratio || 1), 'important')
    body.setProperty('width', `${size.width}px`, 'important')
    body.setProperty('height', `${size.height}px`, 'important')
    body.setProperty('max-width', 'none', 'important')
    body.setProperty('padding', '0', 'important')
    body.setProperty('margin', 'auto', 'important')
    body.setProperty('position', 'relative', 'important')
  }
  fit()
  doc.defaultView?.addEventListener('resize', fit)
  return () => doc.defaultView?.removeEventListener('resize', fit)
}
