import { resolveEpubHref, type Epub } from '../books/epub'
import { declarations, fixedPageSize, mountFixedPage, stylesheetRules } from './publicationStyles'
import { searchableText } from './search'

// WebKit disables parent-owned DOM listeners when the frame's scripting flag
// is sandboxed. Allow the listeners while the initial CSP denies every script
// source and inline handler before any publication node is mounted.
export const readerSandbox = 'allow-same-origin allow-scripts'
export const chapterShell = `<!doctype html><html><head><meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'none'; script-src-attr 'none'; img-src blob:; style-src 'unsafe-inline'; font-src blob:; media-src blob:; form-action 'none'; base-uri 'none'">
</head><body></body></html>`

const HTML = 'http://www.w3.org/1999/xhtml'
const XML = 'http://www.w3.org/XML/1998/namespace'
const SVG = 'http://www.w3.org/2000/svg'
const MATH = 'http://www.w3.org/1998/Math/MathML'

const inert = new Set(['script', 'style', 'noscript', 'template'])
const removed = new Set([
  'iframe', 'frame', 'frameset', 'object', 'embed', 'portal', 'base', 'link', 'meta',
  'input', 'textarea', 'button', 'select', 'foreignobject', 'animate', 'animatetransform',
  'animatemotion', 'set', 'discard', 'image',
])
const attributes = new Set([
  'id', 'class', 'dir', 'lang', 'title', 'role', 'alt', 'width', 'height', 'colspan', 'rowspan',
  'start', 'reversed', 'type', 'value', 'headers', 'scope', 'datetime', 'open',
  'viewBox', 'preserveAspectRatio', 'd', 'points', 'transform', 'x', 'y', 'cx', 'cy', 'r',
  'rx', 'ry', 'x1', 'x2', 'y1', 'y2', 'fill', 'stroke', 'stroke-width', 'opacity', 'fill-rule',
])

function parseChapter(source: string): Document {
  const cleaned = source.replace(/<!DOCTYPE\s+[^>[\]]*>/gi, '')
  if (/<!DOCTYPE|<!ENTITY/i.test(cleaned)) throw new Error('Unsupported chapter XML declarations')
  const document = new DOMParser().parseFromString(cleaned, 'application/xhtml+xml')
  if (!document.getElementsByTagName('parsererror').length && document.getElementsByTagNameNS(HTML, 'body').length) {
    return document
  }
  // Parse malformed-but-readable HTML in a detached template. Unlike a detached
  // HTML document, inert template content cannot begin subresource requests.
  const fallback = window.document.implementation.createHTMLDocument('')
  const template = fallback.createElement('template')
  template.innerHTML = cleaned
  fallback.body.replaceChildren(template.content)
  return fallback
}

function safeFragment(raw: string): string | undefined {
  try { return decodeURIComponent(raw) } catch { return undefined }
}

function safeLink(raw: string): string | undefined {
  try {
    const url = new URL(raw)
    if (!url.username && !url.password && (url.protocol === 'https:' || url.protocol === 'http:')) return url.href
  } catch { /* A relative URL is handled by the EPUB resolver. */ }
}

export function mountChapter(doc: Document, epub: Epub, href: string, loadAssets = true): () => void {
  const source = parseChapter(epub.text(href))
  const sourceBody = source.getElementsByTagNameNS(HTML, 'body')[0]
  const pageSize = fixedPageSize(source)
  const urls = new Map<string, string>()
  const imageTypes = new Set(['image/png', 'image/jpeg', 'image/gif', 'image/webp', 'image/avif', 'image/svg+xml', 'image/bmp'])

  function image(raw: string): string | undefined {
    const path = resolveEpubHref(href, raw)?.split('#')[0]
    if (!path) return
    const existing = urls.get(path)
    if (existing) return existing
    const type = epub.mediaType(path)
    if (!type || !imageTypes.has(type)) return
    const bytes = epub.bytes(path)
    if (!bytes) return
    const url = URL.createObjectURL(new Blob([new Uint8Array(bytes)], { type }))
    urls.set(path, url)
    return url
  }

  function copy(node: Node): Node | undefined {
    if (node.nodeType === 3 || node.nodeType === 4) return doc.importNode(node, false)
    if (node.nodeType !== 1) return
    const element = node as Element
    const ns = element.namespaceURI
    const tag = element.localName
    if (inert.has(tag.toLowerCase())) return
    const svgImage = ns === SVG && tag === 'image'
    if (!svgImage && removed.has(tag.toLowerCase())) return
    if (ns !== HTML && ns !== SVG && ns !== MATH) return
    const newElement = doc.createElementNS(ns, tag)
    for (const attr of Array.from(element.attributes)) {
      if (!attr.namespaceURI && (attributes.has(attr.name) || attr.name.startsWith('aria-'))
          && !/url\s*\(/i.test(attr.value)) newElement.setAttribute(attr.name, attr.value)
    }
    const rawStyle = element.getAttribute('style')
    if (loadAssets && rawStyle) {
      const scratch = doc.createElement('span').style
      scratch.cssText = rawStyle
      const safeStyle = declarations(scratch, !!pageSize)
      if (safeStyle) newElement.setAttribute('style', safeStyle)
    }
    const language = element.getAttributeNS(XML, 'lang')
    if (language) newElement.setAttribute('lang', language)
    if (tag === 'a') {
      const name = element.getAttribute('name')
      if (name) newElement.setAttribute('name', name)
      const target = element.getAttribute('href') ?? element.getAttributeNS('http://www.w3.org/1999/xlink', 'href')
      if (target) {
        const destination = target.startsWith('#') ? href + target : resolveEpubHref(href, target)
        const external = safeLink(target)
        if (destination || external) {
          newElement.setAttribute('data-quire-link', destination || external!)
          if (external && !destination) newElement.setAttribute('data-quire-external', '')
          // Native focus and link semantics, while the trusted parent handles navigation.
          newElement.setAttribute('href', '#')
        }
      }
    }
    if (loadAssets && ((ns === HTML && tag === 'img') || svgImage)) {
      const attr = svgImage ? 'href' : 'src'
      const raw = element.getAttribute(attr) ?? element.getAttributeNS('http://www.w3.org/1999/xlink', attr)
      const src = raw && image(raw)
      if (src) newElement.setAttribute(attr, src)
    }
    for (const child of Array.from(node.childNodes)) {
      const safeChild = copy(child)
      if (safeChild) newElement.append(safeChild)
    }
    return newElement
  }

  const body = copy(sourceBody) as HTMLElement
  const dir = sourceBody.getAttribute('dir') ?? source.documentElement.getAttribute('dir')
  if (dir === 'rtl' || dir === 'ltr') body.dir = dir
  const language = source.documentElement.getAttribute('lang') ?? source.documentElement.getAttributeNS(XML, 'lang')
  if (language && !body.lang) body.lang = language
  const stylesheet = doc.createElement('style')
  try {
    if (loadAssets) {
      if (typeof CSSStyleSheet.prototype.replaceSync === 'function') {
        stylesheet.textContent = stylesheetRules(source, epub, href, !!pageSize, urls)
      }
      doc.head.prepend(stylesheet)
    }
    doc.body.replaceWith(body)
    const releaseLayout = loadAssets && pageSize ? mountFixedPage(doc, pageSize) : undefined
    return () => {
      releaseLayout?.()
      stylesheet.remove()
      urls.forEach(url => URL.revokeObjectURL(url))
    }
  } catch (error) {
    stylesheet.remove()
    urls.forEach(url => URL.revokeObjectURL(url))
    throw error
  }
}

export function jumpFragment(doc: Document, fragment?: string) {
  if (!fragment) return false
  const id = safeFragment(fragment)
  const target = id && (doc.getElementById(id) ?? Array.from(doc.getElementsByTagName('a')).find(el => el.name === id))
  if (!target) return false
  target.scrollIntoView({block: 'start'})
  return true
}

export function chapterSearchText(epub: Epub, href: string): string {
  const body = parseChapter(epub.text(href)).getElementsByTagNameNS(HTML, 'body')[0]
  return body ? searchableText(body).text : ''
}
