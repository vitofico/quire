const ATOM = 'http://www.w3.org/2005/Atom'
const DC = 'http://purl.org/dc/elements/1.1/'
const DC_TERMS = 'http://purl.org/dc/terms/'
const ACQUISITION = 'http://opds-spec.org/acquisition'
const MAX_FEED_SIZE = 8 * 1024 * 1024
const MAX_BOOK_SIZE = 128 * 1024 * 1024
const MAX_COVER_SIZE = 8 * 1024 * 1024
const EPUB_TYPE = 'application/epub+zip'

export interface OpdsPublication {
  id: string
  title: string
  authors: string[]
  description: string | null
  epubHref: string
  coverHref: string | null
  detailHref: string | null
  metadataId: string | null
  calibreBookId: string | null
}

export interface OpdsLink {
  title: string
  href: string
}

export interface OpdsSearch {
  href: string
  base: string
}

export interface OpdsFeed {
  url: string
  title: string
  navigation: OpdsLink[]
  publications: OpdsPublication[]
  next: string | null
  previous: string | null
  search: OpdsSearch | null
  /** The newest-books feed linked from this one, if any. */
  latest: string | null
}

interface CatalogOptions {
  url: string
  authorization?: string
  request?: (url: string, init?: RequestInit) => Promise<Response>
}

function safeUrl(href: string, base: string): URL | null {
  if (!href.trim()) return null
  try {
    const url = new URL(href, base)
    if (url.username || url.password) return null
    if (url.protocol === 'https:') return url
    if (url.protocol === 'http:' && url.origin === new URL(base).origin &&
        typeof location !== 'undefined' && url.origin === location.origin) return url
  } catch {
    return null
  }
  return null
}

function xmlDocument(source: string): Document {
  if (/<!DOCTYPE|<!ENTITY/i.test(source)) throw new Error('Unsupported catalog XML declarations')
  const document = new DOMParser().parseFromString(source, 'application/xml')
  if (document.getElementsByTagNameNS('*', 'parsererror').length) throw new Error('Invalid catalog XML')
  return document
}

function children(node: Element, name: string): Element[] {
  return Array.from(node.children).filter(child => child.localName === name &&
    (child.namespaceURI === ATOM || child.namespaceURI === null))
}

function field(node: Element, name: string): string | null {
  return children(node, name)[0]?.textContent?.trim() || null
}

function baseOf(node: Element, feedUrl: string): string {
  const bases: string[] = []
  for (let element: Element | null = node; element; element = element.parentElement) {
    const base = element.getAttributeNS('http://www.w3.org/XML/1998/namespace', 'base')
    if (base) bases.unshift(base)
  }
  return bases.reduce((base, href) => new URL(href, base).href, feedUrl)
}

function mediaType(link: Element): string {
  return (link.getAttribute('type') ?? '').split(';')[0].trim().toLowerCase()
}

function rel(link: Element): string {
  return link.getAttribute('rel') ?? 'alternate'
}

function hrefOf(link: Element | undefined, base: string): string | null {
  const href = link?.getAttribute('href')
  if (!link || !href) return null
  try {
    return safeUrl(new URL(href, baseOf(link, base)).href, base)?.href ?? null
  } catch {
    return null
  }
}

export function parseOpdsFeed(source: string, url: string): OpdsFeed {
  const root = xmlDocument(source).documentElement
  if (!['feed', 'entry'].includes(root.localName) || (root.namespaceURI && root.namespaceURI !== ATOM)) {
    throw new Error('Expected an OPDS Atom feed')
  }
  const links = children(root, 'link')
  const searchLink = links.find(link => rel(link) === 'search' &&
    (mediaType(link) === 'application/opensearchdescription+xml' ||
      link.getAttribute('href')?.includes('{searchTerms}')))
  const template = searchLink?.getAttribute('href')
  let search: OpdsSearch | null = null
  if (searchLink && template) {
    try { search = { href: template, base: baseOf(searchLink, url) } } catch { /* Invalid xml:base. */ }
  }
  const feed: OpdsFeed = {
    url,
    title: field(root, 'title') ?? '',
    navigation: [],
    publications: [],
    next: hrefOf(links.find(link => rel(link) === 'next'), url),
    previous: hrefOf(links.find(link => ['previous', 'prev'].includes(rel(link))), url),
    search,
    latest: null,
  }
  const byDownload = new Map<string, OpdsPublication>()
  for (const entry of root.localName === 'entry' ? [root] : children(root, 'entry')) {
    const title = field(entry, 'title') ?? 'Untitled'
    const entryLinks = children(entry, 'link')
    const acquisition = entryLinks.find(link => [EPUB_TYPE, 'application/epub'].includes(mediaType(link)) &&
      [ACQUISITION, ACQUISITION + '/open-access'].includes(rel(link)))
    const epubHref = hrefOf(acquisition, url)
    if (!epubHref) {
      const navigation = entryLinks.find(link => mediaType(link) === 'application/atom+xml' &&
        (['subsection', 'alternate', 'related'].includes(rel(link)) ||
          /^http:\/\/opds-spec\.org\/(sort\/|featured|recommended|shelf|subscriptions)/.test(rel(link))))
      const href = hrefOf(navigation, url)
      if (!href) continue
      feed.navigation.push({ title, href })
      // OPDS marks it with sort/new; Calibre-Web and calibre's server only use the /new path.
      if (rel(navigation!) === 'http://opds-spec.org/sort/new' ||
          (!feed.latest && /\/new\/?$/.test(new URL(href).pathname))) feed.latest = href
      continue
    }
    const covers = ['http://opds-spec.org/image/thumbnail', 'http://opds-spec.org/thumbnail',
      'http://opds-spec.org/image', 'http://opds-spec.org/cover']
    const cover = covers.map(relation => entryLinks.find(link => rel(link) === relation)).find(Boolean)
    const detail = entryLinks.find(link => ['alternate', 'related'].includes(rel(link)) &&
      ['application/atom+xml', 'text/html'].includes(mediaType(link)))
    const description = field(entry, 'summary') ?? field(entry, 'content')
    byDownload.delete(epubHref)
    byDownload.set(epubHref, {
      title,
      id: field(entry, 'id') ?? epubHref,
      authors: children(entry, 'author').flatMap(author => field(author, 'name') ?? []),
      description: description?.replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim() || null,
      epubHref,
      coverHref: hrefOf(cover, url),
      detailHref: hrefOf(detail, url),
      metadataId: [DC_TERMS, DC].flatMap(namespace => Array.from(entry.getElementsByTagNameNS(namespace, 'identifier')))
        .map(identifier => identifier.textContent?.trim()).find(Boolean) ?? null,
      calibreBookId: new URL(epubHref).pathname.match(/\/opds\/download\/(\d+)\/[^/]+\/?$/)?.[1] ?? null,
    })
  }
  feed.publications = Array.from(byDownload.values())
  return feed
}

async function limitedBody(response: Response, limit: number): Promise<Blob> {
  if (Number(response.headers.get('content-length')) > limit) {
    await response.body?.cancel()
    throw new Error('Catalog response is too large')
  }
  const reader = response.body?.getReader()
  if (!reader) throw new Error('The catalog returned an empty response')
  const pieces: BlobPart[] = []
  let size = 0
  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      size += value.byteLength
      if (size > limit) {
        await reader.cancel()
        throw new Error('Catalog response is too large')
      }
      pieces.push(value.slice())
    }
  } finally {
    reader.releaseLock()
  }
  return new Blob(pieces, { type: response.headers.get('content-type') ?? '' })
}

function searchHref(link: OpdsSearch, query: string): string {
  return new URL(link.href.replace(/\{searchTerms\??\}/g, encodeURIComponent(query))
    .replace(/\{[^{}]+\?\}/g, ''), link.base).href
}

export function makeCatalogClient(options: CatalogOptions) {
  const base = safeUrl(options.url, typeof location === 'undefined' ? options.url : location.href)
  if (!base) throw new Error('Enter a secure catalog URL')
  const root = base.href
  const origin = base.origin

  async function get(href: string, accept: string, signal?: AbortSignal): Promise<Response> {
    const url = safeUrl(href, root)
    if (!url) throw new Error('Invalid catalog link')
    const headers = new Headers({ Accept: accept })
    const catalogRequest = url.origin === origin ? options.request : undefined
    const authorization = url.origin === origin && !catalogRequest ? options.authorization : undefined
    if (authorization) headers.set('Authorization', authorization)
    let response: Response
    try {
      response = await (catalogRequest ?? fetch)(url.href, {
        headers,
        signal,
        cache: 'no-store',
        credentials: 'omit',
        redirect: catalogRequest || authorization ? 'error' : 'follow',
        referrerPolicy: 'no-referrer',
      })
    } catch (error) {
      if (signal?.aborted) throw error
      throw new Error('The browser could not reach this catalog. Check its address and browser access settings, or import an EPUB file.')
    }
    if (response.status === 401 || response.status === 403) {
      await response.body?.cancel()
      throw new Error('The catalog requires valid credentials')
    }
    if (!response.ok) {
      await response.body?.cancel()
      throw new Error(`Catalog returned HTTP ${response.status}`)
    }
    return response
  }

  async function feed(href = root, signal?: AbortSignal): Promise<OpdsFeed> {
    const response = await get(href, 'application/atom+xml, application/xml, text/xml', signal)
    return parseOpdsFeed(await (await limitedBody(response, MAX_FEED_SIZE)).text(), response.url || href)
  }

  return {
    feed,
    async acquire(href: string, signal?: AbortSignal): Promise<Blob> {
      const response = await get(href, EPUB_TYPE, signal)
      const blob = await limitedBody(response, MAX_BOOK_SIZE)
      const zip = new Uint8Array(await blob.slice(0, 4).arrayBuffer())
      if (zip[0] !== 80 || zip[1] !== 75 || zip[2] !== 3 || zip[3] !== 4) throw new Error('The catalog did not return an EPUB')
      return blob.slice(0, blob.size, EPUB_TYPE)
    },
    async cover(href: string, signal?: AbortSignal): Promise<Blob> {
      const coverUrl = safeUrl(href, root)
      if (!coverUrl || ((options.request || options.authorization) && coverUrl.origin !== origin)) {
        throw new Error('This catalog cover is unavailable')
      }
      const response = await get(coverUrl.href, 'image/avif, image/webp, image/png, image/jpeg, image/gif', signal)
      const blob = await limitedBody(response, MAX_COVER_SIZE)
      if (!/^image\/(avif|webp|png|jpeg|gif)$/i.test(blob.type)) throw new Error('Unsupported catalog cover')
      return blob
    },
    async search(link: OpdsSearch, query: string, signal?: AbortSignal): Promise<OpdsFeed> {
      if (link.href.includes('{searchTerms')) return feed(searchHref(link, query), signal)
      const response = await get(new URL(link.href, link.base).href, 'application/opensearchdescription+xml', signal)
      const document = xmlDocument(await (await limitedBody(response, MAX_FEED_SIZE)).text())
      const urls = Array.from(document.getElementsByTagNameNS('*', 'Url'))
      const template = urls.find(node => mediaType(node) === 'application/atom+xml' &&
        node.getAttribute('template')?.includes('{searchTerms'))?.getAttribute('template')
      if (!template) throw new Error('This catalog does not provide an Atom search template')
      return feed(searchHref({ href: template, base: response.url || link.base }, query), signal)
    },
  }
}
