import { md5 } from '@noble/hashes/legacy.js'
import { unzip } from 'fflate'

const MAX_FILE = 128 * 1024 * 1024
const MAX_EXPANDED = 256 * 1024 * 1024
const CONTAINER_NAMESPACE = 'urn:oasis:names:tc:opendocument:xmlns:container'
const DC_NAMESPACE = 'http://purl.org/dc/elements/1.1/'
const EPUB_NAMESPACE = 'http://www.idpf.org/2007/ops'

export interface EpubIdentity {
  metadata_id: string | null
  content_hash: string
  identity_hash_version: 1
}

export interface EpubMetadata {
  title: string
  authors: string[]
  series: string | null
  seriesIndex: number | null
  language: string | null
  subjects: string[]
  isbn: string | null
  publisher?: string | null
  description: string | null
  coverHref: string | null
}

export interface SpineItem {
  href: string
  mediaType: string
}

export interface TocItem {
  title: string
  href: string
  children: TocItem[]
}

export interface Epub {
  identity: EpubIdentity
  metadata: EpubMetadata
  spine: SpineItem[]
  toc: TocItem[]
  bytes(href: string): Uint8Array | undefined
  text(href: string): string
  mediaType(href: string): string | undefined
}

export function normalizeMetadataId(raw: string | null): string | null {
  return raw?.trim().toLowerCase()
    .replace(/^urn:/, '')
    .replace(/^(isbn|uuid|calibre|mobi-asin|asin|doi|url)[:\t\r\n\f\v ]+/, '')
    .replace(/[\t\r\n\f\v -]/g, '') || null
}

export function sampledContentHash(data: Uint8Array): string {
  const hash = md5.create()
  const step = Math.max(1, Math.floor(data.length / 1024))
  for (let i = 0; i < 1024 && i * step < data.length; i++) {
    hash.update(data.subarray(i * step, i * step + 64))
  }
  return Array.from(hash.digest(), byte => byte.toString(16).padStart(2, '0')).join('')
}

function encodePath(path: string): string {
  return path.split('/').map(encodeURIComponent).join('/')
}

export function resolveEpubHref(base: string, href: string): string | undefined {
  const candidate = href.trim()
  if (!candidate || /^[a-z][a-z\d+.-]*:|^\/\//i.test(candidate) || /[\\\u0000-\u001F\u007F]/.test(candidate)) return
  const fragmentIndex = candidate.indexOf('#')
  const reference = fragmentIndex < 0 ? candidate : candidate.slice(0, fragmentIndex)
  const fragment = fragmentIndex < 0 ? '' : candidate.slice(fragmentIndex)
  const path = reference.split('?')[0]
  if (!path) return base ? base.split(/[?#]/)[0] + fragment : undefined
  const segments = path.startsWith('/') ? [] : base.split(/[?#]/)[0].split('/').slice(0, -1)
  try {
    for (const raw of path.split('/')) {
      const part = decodeURIComponent(raw)
      if (/[\/\\\u0000-\u001F\u007F]/.test(part)) return
      if (!part || part === '.') continue
      if (part === '..') {
        if (!segments.length) return
        segments.pop()
      } else segments.push(encodeURIComponent(part))
    }
  } catch {
    return
  }
  return segments.length ? segments.join('/') + fragment : undefined
}

function decodeText(bytes: Uint8Array): string {
  if (bytes[0] === 0xff && bytes[1] === 0xfe) return new TextDecoder('utf-16le').decode(bytes)
  if (bytes[0] === 0xfe && bytes[1] === 0xff) return new TextDecoder('utf-16be').decode(bytes)
  return new TextDecoder().decode(bytes)
}

function xml(source: string, file: string): Document {
  // A legacy external doctype declaration is harmless after removal. Reject internal subsets.
  const cleaned = source.replace(/<!DOCTYPE\s+[^>[\]]*>/gi, '')
  if (/<!DOCTYPE|<!ENTITY/i.test(cleaned)) throw new Error(`Unsupported XML declarations in ${file}`)
  const document = new DOMParser().parseFromString(cleaned, 'application/xml')
  if (document.getElementsByTagNameNS('*', 'parsererror').length) throw new Error(`Invalid XML in ${file}`)
  return document
}

function elements(node: Document | Element, name: string): Element[] {
  return Array.from(node.getElementsByTagNameNS('*', name))
}

function children(node: Element | undefined, name: string): Element[] {
  return Array.from(node?.children ?? []).filter(element => element.localName === name)
}

function value(element: Element | undefined): string | null {
  return element?.textContent?.trim() || null
}

function files(data: Uint8Array): Promise<Record<string, Uint8Array>> {
  return new Promise((resolve, reject) => {
    let expanded = 0
    let count = 0
    const paths = new Set<string>()
    unzip(data, {
      filter(file) {
        expanded += file.originalSize
        if (expanded > MAX_EXPANDED || ++count > 20_000) throw new Error('EPUB archive is too large')
        const path = file.name
        if (path === '__proto__' || path.startsWith('/') || /[\\\u0000-\u001F\u007F]/.test(path) ||
            path.split('/').some(part => part === '.' || part === '..') || paths.has(path)) {
          throw new Error('EPUB contains an invalid archive path')
        }
        paths.add(path)
        return !path.endsWith('/')
      },
    }, (error, result) => error ? reject(error) : resolve(result))
  })
}

type ManifestItem = SpineItem & { properties: string[] }

function readToc(nav: Document, path: string): TocItem[] {
  const navs = elements(nav, 'nav')
  const toc = navs.find(element => element.getAttributeNS(EPUB_NAMESPACE, 'type')?.split(/\s+/).includes('toc'))
    ?? navs.find(element => element.getAttribute('role')?.split(/\s+/).includes('doc-toc'))
    ?? navs[0]
  const visit = (list: Element | undefined, depth: number): TocItem[] => depth > 32 ? [] :
    children(list, 'li').flatMap(item => {
      const link = children(item, 'a')[0]
      const href = resolveEpubHref(path, link?.getAttribute('href') ?? '')
      const nested = visit(children(item, 'ol')[0], depth + 1)
      return href ? [{ title: value(link) ?? href, href, children: nested }] : nested
    })
  return visit(children(toc, 'ol')[0], 0)
}

function readNcx(ncx: Document, path: string): TocItem[] {
  const visit = (list: Element | undefined, depth: number): TocItem[] => depth > 32 ? [] :
    children(list, 'navPoint').flatMap(item => {
      const href = resolveEpubHref(path, children(item, 'content')[0]?.getAttribute('src') ?? '')
      const nested = visit(item, depth + 1)
      return href ? [{ title: value(children(item, 'navLabel')[0]) ?? href, href, children: nested }] : nested
    })
  return visit(elements(ncx, 'navMap')[0], 0)
}

export async function openEpub(blob: Blob): Promise<Epub> {
  if (blob.size < 22 || blob.size > MAX_FILE) throw new Error('Invalid or oversized EPUB')
  const raw = new Uint8Array(await blob.arrayBuffer())
  const entries = await files(raw)
  const bytes = (href: string): Uint8Array | undefined => {
    const path = decodeURIComponent(href.split(/[?#]/)[0])
    return Object.hasOwn(entries, path) ? entries[path] : undefined
  }
  const text = (href: string): string => {
    const data = bytes(href)
    if (!data) throw new Error(`Missing EPUB resource ${href}`)
    return decodeText(data)
  }

  const container = xml(text('META-INF/container.xml'), 'META-INF/container.xml')
  const rootfile = container.getElementsByTagNameNS(CONTAINER_NAMESPACE, 'rootfile')[0]
  const opfPath = rootfile?.getAttribute('full-path')
  if (!opfPath) throw new Error('Missing EPUB package')
  const packageHref = encodePath(opfPath)
  const opf = xml(text(packageHref), packageHref)
  const root = opf.documentElement
  if (root.localName !== 'package') throw new Error('Invalid EPUB package')
  const metadataElement = children(root, 'metadata')[0]
  const items = new Map<string, ManifestItem>()
  const types = new Map<string, string>()
  for (const item of children(children(root, 'manifest')[0], 'item')) {
    const id = item.getAttribute('id')
    const href = resolveEpubHref(packageHref, item.getAttribute('href') ?? '')
    const mediaType = item.getAttribute('media-type')
    if (!id || !href || !mediaType || !bytes(href)) continue
    items.set(id, { href, mediaType, properties: (item.getAttribute('properties') ?? '').split(/\s+/) })
    types.set(href.split('#')[0], mediaType)
  }
  const spineElement = children(root, 'spine')[0]
  const spine = children(spineElement, 'itemref').map(ref => {
    const item = items.get(ref.getAttribute('idref') ?? '')
    if (!item || !['application/xhtml+xml', 'text/html'].includes(item.mediaType)) {
      throw new Error('Unsupported or missing EPUB spine resource')
    }
    return { href: item.href, mediaType: item.mediaType }
  })
  if (!spine.length) throw new Error('Missing EPUB spine')

  if (bytes('META-INF/encryption.xml')) {
    const encryption = xml(text('META-INF/encryption.xml'), 'META-INF/encryption.xml')
    for (const method of elements(encryption, 'EncryptionMethod')) {
      if (!['http://www.idpf.org/2008/embedding', 'http://ns.adobe.com/pdf/enc#RC'].includes(method.getAttribute('Algorithm') ?? '')) {
        throw new Error('This EPUB is encrypted')
      }
    }
  }

  const dc = (name: string) => Array.from(metadataElement?.getElementsByTagNameNS(DC_NAMESPACE, name) ?? [])
  const dcValues = (name: string) => dc(name).flatMap(entry => value(entry) ?? [])
  const identifiers = Array.from(opf.getElementsByTagNameNS(DC_NAMESPACE, 'identifier'))
  const metadataId = normalizeMetadataId(identifiers.map(value).find(Boolean) ?? null)
  const meta = children(metadataElement, 'meta')
  const named = (name: string) => meta.find(entry => entry.getAttribute('name') === name)?.getAttribute('content')?.trim() || null
  const property = (name: string, refines?: string) => value(meta.find(entry =>
    entry.getAttribute('property') === name && (refines === undefined || entry.getAttribute('refines') === refines)))
  const collection = meta.find(entry => entry.getAttribute('property') === 'belongs-to-collection' &&
    property('collection-type', '#' + entry.getAttribute('id')) === 'series')
  const seriesRef = collection?.id ? '#' + collection.id : undefined
  const index = named('calibre:series_index') ?? (seriesRef ? property('group-position', seriesRef) : null)
  const isbn = dcValues('identifier').map(id => id.replace(/^(urn:)?isbn[:\s]+/i, '').replace(/[\s-]/g, ''))
    .find(id => /^(?:\d{9}[\dXx]|97[89]\d{10})$/.test(id))
  const byHref = new Map(Array.from(items.values(), item => [item.href.split('#')[0], item]))
  const manifestItem = (href: string | null | undefined) =>
    href ? byHref.get(resolveEpubHref(packageHref, href)?.split('#')[0] ?? '') : undefined
  // Older EPUBs name the cover through <meta name="cover">, the <guide>, or a cover page that wraps the image.
  const coverImage = (item: ManifestItem | undefined): ManifestItem | undefined => {
    if (!item || item.mediaType.startsWith('image/')) return item
    if (!['application/xhtml+xml', 'text/html'].includes(item.mediaType)) return
    try {
      const page = xml(text(item.href), item.href)
      const source = elements(page, 'img')[0]?.getAttribute('src') ?? elements(page, 'image')
        .map(image => image.getAttributeNS('http://www.w3.org/1999/xlink', 'href') ?? image.getAttribute('href')).find(Boolean)
      const target = source ? byHref.get(resolveEpubHref(item.href, source)?.split('#')[0] ?? '') : undefined
      return target?.mediaType.startsWith('image/') ? target : undefined
    } catch { return }
  }
  const declaredCover = named('cover')
  const guideCover = children(children(root, 'guide')[0], 'reference')
    .find(reference => reference.getAttribute('type')?.toLowerCase() === 'cover')?.getAttribute('href')
  const cover = Array.from(items.values()).find(item => item.properties.includes('cover-image'))
    ?? coverImage(items.get(declaredCover ?? '') ?? manifestItem(declaredCover))
    ?? coverImage(manifestItem(guideCover))
    ?? Array.from(items).find(([id, item]) => item.mediaType.startsWith('image/') &&
      /cover/i.test(id + ' ' + item.href.split('/').pop()))?.[1]
  const metadata: EpubMetadata = {
    title: dcValues('title')[0] ?? 'Untitled',
    authors: dcValues('creator'),
    series: named('calibre:series') ?? value(collection),
    seriesIndex: index !== null && Number.isFinite(Number(index)) ? Number(index) : null,
    language: dcValues('language')[0] ?? null,
    subjects: dcValues('subject'),
    isbn: isbn?.toUpperCase() ?? null,
    publisher: dcValues('publisher')[0] ?? null,
    description: dcValues('description')[0] ?? null,
    coverHref: cover?.mediaType.startsWith('image/') ? cover.href : null,
  }

  const nav = Array.from(items.values()).find(item => item.properties.includes('nav'))
  const ncx = items.get(spineElement?.getAttribute('toc') ?? '')
  let toc: TocItem[] = []
  for (const item of [nav, ncx]) {
    if (!item) continue
    try {
      const document = xml(text(item.href), item.href)
      toc = item === nav ? readToc(document, item.href) : readNcx(document, item.href)
      if (toc.length) break
    } catch {
      // A broken optional navigation document does not prevent reading the spine.
    }
  }
  return {
    identity: { metadata_id: metadataId, content_hash: sampledContentHash(raw), identity_hash_version: 1 },
    metadata,
    spine,
    toc,
    bytes,
    text,
    mediaType: href => types.get(href.split(/[?#]/)[0]),
  }
}
