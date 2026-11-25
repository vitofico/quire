export interface DocumentIdentity {
  metadata_id?: string | null
  content_hash: string
  identity_hash_version?: number
}

export interface Progress {
  document: DocumentIdentity
  locator: string
  percent: number
  client_updated_at: string
  finished_at?: string | null
  abandoned_at?: string | null
}

export interface Highlight {
  id: string
  document: DocumentIdentity
  locator: string
  text: string
  colour: 'YELLOW' | 'GREEN' | 'PINK'
  book_title: string
  book_author?: string | null
  created_at: number
  updated_at: number
  deleted_at?: number | null
  comment?: string | null
}

export interface LocalBook {
  key: string
  title: string
  author?: string | null
  authors?: string[]
  metadata_id?: string | null
  content_hash?: string
  identity_hash_version?: number
  series_name?: string | null
  series_index?: number | null
  language?: string | null
  subjects?: string[]
  isbn?: string | null
  opds_href?: string | null
  /** The saved catalog `opds_href` belongs to; null for the server's own catalog. */
  catalog_id?: string | null
  sizeBytes?: number
  publisher?: string | null
  description?: string | null
  cover?: Blob | string | null
}

/** `ai` rewrote the book's own value; `ai_written` wrote one where none existed. */
export type DetailsOrigin = 'file' | 'catalog' | 'openlibrary' | 'ai' | 'ai_written'

export interface DetailsFields {
  title: string
  authors: string[]
  series_name: string | null
  series_index: number | null
  description: string | null
  subjects: string[]
  language: string | null
  isbn: string | null
  year: number | null
  publisher: string | null
}

/** The server's answer from `/details/v1/lookup` or `/details/v1/tidy`. */
export interface DetailsAnswer {
  key: string
  book: DetailsFields
  origins: Partial<Record<keyof DetailsFields | 'cover', DetailsOrigin>>
  cover: { source: 'file' | 'catalog' | 'openlibrary' | 'generated' | null; url?: string | null; generated: boolean }
  tidy: 'done' | 'not_needed' | 'quota_exceeded' | 'failed'
  cached: boolean
  openlibrary_url?: string | null
}

/** Found and tidied details of one book, kept in this browser only. */
export interface BookDetails {
  /** When the last lookup was tried, in ms. */
  attempted_at: number
  /** A failed lookup, an unfinished tidy or a cover not yet downloaded is tried again only after this, in ms. */
  retry_at?: number
  answer?: DetailsAnswer
  /** The `answer.cover.url` whose image is kept in this browser, so it shows offline. */
  cover_url?: string
  /** The reader chose to see the book's own details instead. */
  original?: boolean
}

export interface ShelfBook extends LocalBook {
  hasDownload: boolean
  synced: boolean
  localOnly: boolean
  editionConflict?: boolean
  details?: BookDetails
}

export interface ReplicaSnapshot {
  books: ShelfBook[]
  progress: Progress[]
  highlights: Highlight[]
  pending: number
  readyToSync: boolean
}

export type SyncTransport = (path: string, init?: RequestInit) => Promise<Response>

export class EditionConflict extends Error {
  constructor(readonly existing: LocalBook) {
    super('An edition with this identifier already exists')
    this.name = 'EditionConflict'
  }
}

export class SyncError extends Error {
  constructor(readonly status: number, readonly code?: string, readonly bookKey?: string) {
    super(code ? `Sync failed (${status}, ${code})` : `Sync failed (${status})`)
    this.name = 'SyncError'
  }
}

export function bookDocument(book: LocalBook & { localOnly?: boolean }): DocumentIdentity {
  return {
    metadata_id: book.localOnly === true ? null : book.metadata_id,
    content_hash: book.content_hash ?? book.key,
    identity_hash_version: book.identity_hash_version ?? 1,
  }
}
