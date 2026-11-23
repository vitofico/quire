import { SyncError, type DocumentIdentity, type Highlight, type LocalBook, type Progress, type SyncTransport } from './types'

export interface LibraryRow extends Omit<LocalBook, 'key'> {
  content_hash: string
  updated_at: string
  deleted_at: string | null
}

type LibraryWrite =
  | { key: string; deleted: true; identity_hash_version?: number }
  | { key: string; deleted: false; book: LocalBook; editionConflict?: boolean }

export type Mutation =
  | { kind: 'library'; value: LibraryWrite }
  | { kind: 'progress'; value: Progress }
  | { kind: 'highlight'; value: Highlight }

export type Queued = Mutation & { id: string; key: string; timestamp: string }

export function libraryEntry(write: LibraryWrite, timestamp: string) {
  if (write.deleted) {
    return {
      identity_hash: write.key,
      identity_hash_version: write.identity_hash_version ?? 1,
      status: 'deleted',
      last_seen_at: timestamp,
    }
  }
  const book = write.book
  return {
    identity_hash: book.key,
    identity_hash_version: book.identity_hash_version ?? 1,
    status: 'present',
    last_seen_at: timestamp,
    metadata_id: book.metadata_id,
    title: book.title,
    authors: book.authors ?? (book.author ? [book.author] : []),
    series_name: book.series_name,
    series_index: book.series_index,
    isbn: book.isbn,
    language: book.language,
    subjects: book.subjects,
    opds_href: book.opds_href,
  }
}

export interface ProgressPush {
  results: Array<{ document: DocumentIdentity; status: 'accepted' | 'stale'; server_client_updated_at: string }>
}
export interface ProgressPull { items: Progress[] }
export interface HighlightsPush { results: Array<{ item: Highlight }> }
export interface HighlightsPull { items: Highlight[]; next_cursor: string; has_more: boolean; supports_comments?: boolean }
export interface LibraryPull { items: LibraryRow[] }

export async function requestJson<T>(
  fetch: SyncTransport,
  path: string,
  signal: AbortSignal,
  item?: unknown,
  idempotencyKey?: string,
): Promise<T> {
  const response = await fetch(path, {
    signal,
    cache: 'no-store',
    ...(item !== undefined && {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...(idempotencyKey && { 'Idempotency-Key': idempotencyKey }) },
      body: JSON.stringify({ items: [item] }),
    }),
  })
  if (!response.ok) {
    const error = await response.json().catch(() => null)
    const detail: unknown = error?.detail
    const code = typeof detail === 'string' ? detail : (detail as { error?: unknown } | null)?.error
    throw new SyncError(response.status, typeof code === 'string' && /^[a-z_]+$/.test(code) ? code : undefined)
  }
  return response.json() as Promise<T>
}

export function compareTimestamps(a: string, b: string): number {
  const milliseconds = Date.parse(a) - Date.parse(b)
  if (milliseconds) return milliseconds
  // Python emits microseconds. Date.parse truncates both values to milliseconds.
  const fraction = (value: string) => /T\d\d:\d\d:\d\d\.(\d+)/.exec(value)?.[1].padEnd(9, '0') ?? '000000000'
  return fraction(a).localeCompare(fraction(b))
}

export function newerProgress(current: Progress | undefined, remote: Progress): Progress {
  return !current || compareTimestamps(remote.client_updated_at, current.client_updated_at) >= 0 ? remote : current
}
