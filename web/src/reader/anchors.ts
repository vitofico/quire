export interface Locator {
  href: string
  type?: string
  title?: string
  locations?: {
    progression?: number
    totalProgression?: number
    cssSelector?: unknown
    fragments?: unknown
    quireDomRange?: unknown
    [key: string]: unknown
  }
  text?: { highlight?: string; before?: string; after?: string }
}

interface Endpoint { path: number[]; nodeType: number; offset: number }
interface Anchor {
  version: number
  href: string
  documentHash: string
  start: Endpoint
  end: Endpoint
  quote: string
  rangeText: string
}

export function parseLocator(raw?: string | null): Locator | undefined {
  if (!raw) return
  try {
    const locator = JSON.parse(raw)
    if (typeof locator?.href === 'string' && locator.href) return locator
  } catch { /* Older clients can supply an opaque locator. */ }
}

function ignored(node: Node): boolean {
  if (node.nodeType !== 1) return false
  const element = node as Element
  return ['SCRIPT', 'STYLE', 'NOSCRIPT'].includes(element.tagName.toUpperCase())
    || element.id === 'readium-virtual-page'
    || (element.hasAttribute('data-group') && element.id.startsWith('r2-decoration-'))
}

function characterData(node: Node): node is Text {
  return node.nodeType === 3 || node.nodeType === 4
}

function children(node: Node): Node[] {
  return Array.from(node.childNodes).filter(child =>
    characterData(child) || (child.nodeType === 1 && !ignored(child)))
}

type ContentTree = [number, string] | [1, string | null, string, ContentTree[]]

function tree(node: Node): ContentTree {
  if (characterData(node)) return [node.nodeType, node.data]
  const element = node as Element
  return [1, element.namespaceURI, element.localName, children(element).map(tree)]
}

const hashes = new WeakMap<HTMLElement, Promise<string>>()

export function documentHash(doc: Document): Promise<string> {
  // The renderer attaches a new immutable publication body for every chapter.
  let hash = hashes.get(doc.body)
  if (!hash) {
    hash = crypto.subtle.digest('SHA-256', new TextEncoder().encode(JSON.stringify(tree(doc.body))))
      .then(bytes => Array.from(new Uint8Array(bytes), byte => byte.toString(16).padStart(2, '0')).join(''))
    hashes.set(doc.body, hash)
  }
  return hash
}

function endpoint(body: HTMLElement, node: Node, offset: number): Endpoint {
  const path: number[] = []
  for (let current = node; current !== body;) {
    const parent = current.parentNode
    if (!parent) throw new Error('Missing parent')
    const index = children(parent).indexOf(current)
    if (index < 0) throw new Error('Unsupported endpoint')
    path.unshift(index)
    current = parent
  }
  const contentOffset = node.nodeType === 1
    ? Array.from(node.childNodes).slice(0, offset).filter(child => characterData(child) || (child.nodeType === 1 && !ignored(child))).length
    : offset
  return { path, nodeType: node.nodeType, offset: contentOffset }
}

function resolvePoint(body: HTMLElement, point: Endpoint): [Node, number] {
  if (!Array.isArray(point?.path) || !Number.isSafeInteger(point.offset) || point.offset < 0) throw new Error('Invalid endpoint')
  let node: Node = body
  for (const index of point.path) {
    const child = children(node)[index]
    if (!Number.isSafeInteger(index) || index < 0 || !child) throw new Error('Invalid path')
    node = child
  }
  const nodes = children(node)
  if (node.nodeType !== point.nodeType || point.offset > (characterData(node) ? node.length : nodes.length)) {
    throw new Error('Invalid offset')
  }
  if (characterData(node)) return [node, point.offset]
  const all = Array.from(node.childNodes) as Node[]
  if (point.offset < nodes.length) return [node, all.indexOf(nodes[point.offset])]
  return [node, nodes.length ? all.indexOf(nodes[nodes.length - 1]) + 1 : 0]
}

function structuralText(range: Range): string {
  const parts: string[] = []
  function visit(node: Node) {
    if (ignored(node)) return
    if (characterData(node)) parts.push(node.data)
    else node.childNodes.forEach(visit)
  }
  visit(range.cloneContents())
  return parts.join('')
}

export function readAnchor(locator?: Locator): Anchor | undefined {
  try {
    const raw = locator?.locations?.quireDomRange
    const anchor = typeof raw === 'string' ? JSON.parse(raw) : raw as Anchor | undefined
    if (anchor?.version === 3 && anchor.href === locator?.href
        && typeof anchor.quote === 'string' && anchor.quote.trim()
        && typeof anchor.rangeText === 'string') return anchor
  } catch { /* Invalid structural locations are unavailable. */ }
}

export async function resolveRange(doc: Document, locator: Locator, expectedQuote?: string): Promise<Range | undefined> {
  const anchor = readAnchor(locator)
  if (!anchor || (expectedQuote !== undefined && expectedQuote !== anchor.quote)) return
  try {
    const body = doc.body
    if (anchor.documentHash !== await documentHash(doc) || doc.body !== body) return
    const range = doc.createRange()
    range.setStart(...resolvePoint(body, anchor.start))
    range.setEnd(...resolvePoint(body, anchor.end))
    if (!range.collapsed && structuralText(range) === anchor.rangeText) return range
  } catch { /* Avoid guessing among repeated passages or changed editions. */ }
}

function elementSelector(element: Element, body: HTMLElement): string {
  const path: string[] = []
  for (let current = element; current !== body; current = current.parentElement!) {
    const siblings = Array.from(current.parentElement!.children).filter(sibling => sibling.localName === current.localName)
    path.unshift(CSS.escape(current.localName) + ':nth-of-type(' + (siblings.indexOf(current) + 1) + ')')
  }
  return ['body', ...path].join(' > ')
}

export async function captureRange(doc: Document, locator: Locator, range: Range, quote: string, viewport = false): Promise<Locator | undefined> {
  if (range.collapsed || !quote.trim()) return
  try {
    const body = doc.body
    const start = endpoint(body, range.startContainer, range.startOffset)
    const end = endpoint(body, range.endContainer, range.endOffset)
    const rangeText = structuralText(range)
    const startElement = range.startContainer.nodeType === 1 ? range.startContainer as Element : range.startContainer.parentElement!
    const cssSelector = viewport ? elementSelector(startElement, body) : undefined
    let text: Locator['text'] = { highlight: quote }
    if (viewport) {
      const preceding = doc.createRange()
      preceding.selectNodeContents(startElement)
      preceding.setEnd(range.startContainer, range.startOffset)
      const start = preceding.toString().length
      const characters = startElement.textContent ?? ''
      const finish = Math.min(characters.length, start + 32)
      text = { highlight: characters.slice(start, finish),
        before: characters.slice(Math.max(0, start - 80), start),
        after: characters.slice(finish, finish + 80) }
    }
    const documentHashValue = await documentHash(doc)
    if (body !== doc.body) return
    const anchor: Anchor = { version: 3, href: locator.href, documentHash: documentHashValue, start, end, quote, rangeText }
    const captured = {
      ...locator,
      locations: { ...locator.locations, ...(cssSelector ? { cssSelector } : {}), quireDomRange: JSON.stringify(anchor) },
      text,
    }
    if (!viewport && !await resolveRange(doc, captured)) return
    return captured
  } catch { /* The selection may include content outside the publication. */ }
}

export function chapterProgress(doc: Document): number {
  const scroller = doc.scrollingElement
  if (!scroller) return 0
  return scroller.scrollTop / Math.max(1, scroller.scrollHeight - scroller.clientHeight)
}

export function displayRange(doc: Document, range: Range) {
  const rect = Array.from(range.getClientRects()).find(item => item.width && item.height)
  if (rect && doc.scrollingElement) doc.scrollingElement.scrollTop += rect.top - 28
}

export function viewportRange(doc: Document): Range | undefined {
  const width = doc.documentElement.clientWidth
  const height = doc.documentElement.clientHeight
  const body = doc.body
  const rightToLeft = doc.defaultView?.getComputedStyle(body).direction === 'rtl'
  const padding = parseFloat(doc.defaultView?.getComputedStyle(body).paddingLeft ?? '0')
  const xs = rightToLeft ? [width - padding - 4, width / 2, padding + 4] : [padding + 4, width / 2, width - padding - 4]
  const caretAt = (doc as Document & {
    caretPositionFromPoint?(x: number, y: number): {offsetNode: Node; offset: number} | null
  }).caretPositionFromPoint
  for (let y = 2; y < height; y += 13) {
    for (const x of xs) {
      const position = caretAt?.call(doc, x, y)
      const caret = position ? undefined : doc.caretRangeFromPoint?.(x, y)
      const node = position?.offsetNode ?? caret?.startContainer
      if (!node || !body.contains(node) || !characterData(node)) continue
      const offset = position?.offset ?? caret!.startOffset
      for (let i = Math.max(0, offset - 1); i < Math.min(node.length, offset + 8); i++) {
        const point = node.data.codePointAt(i)!
        if (point >= 0xdc00 && point <= 0xdfff) continue
        const range = doc.createRange()
        range.setStart(node, i)
        range.setEnd(node, i + (point > 0xffff ? 2 : 1))
        const rect = range.getBoundingClientRect()
        if (range.toString().trim() && rect.width && rect.height && rect.bottom > 0 && rect.top < height) return range
      }
    }
  }
}
