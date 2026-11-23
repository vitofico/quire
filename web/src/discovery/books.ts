import type { InsightMetadata } from "./api";

export type DiscoveryBook = {
  id: string;
  title: string;
  author?: string | null;
  isbn?: string | null;
  status?: string | null;
  percent?: number | null;
  seriesName?: string | null;
  seriesPosition?: number | null;
};

export function normalizeIsbn(raw?: string | null): string | undefined {
  const value = raw?.replace(/[\s-]/g, "").toUpperCase() ?? "";
  const sum13 = (digits: string) => Array.from(digits).reduce((sum, char, i) => sum + Number(char) * (i % 2 ? 3 : 1), 0);
  if (/^97[89]\d{10}$/.test(value) && sum13(value) % 10 === 0) return value;
  if (!/^\d{9}[\dX]$/.test(value)) return;
  const sum10 = Array.from(value).reduce((sum, char, i) => sum + (char === "X" ? 10 : Number(char)) * (10 - i), 0);
  if (sum10 % 11 !== 0) return;
  const prefix = `978${value.slice(0, 9)}`;
  return `${prefix}${(10 - sum13(prefix) % 10) % 10}`;
}

export function readingStatus(book: DiscoveryBook): string {
  return book.status ?? ((book.percent ?? 0) > 0 ? "in_progress" : "unread");
}

export function seriesContinuation(books: readonly DiscoveryBook[]): DiscoveryBook[] {
  const series = new Map<string, DiscoveryBook[]>();
  for (const book of books) {
    const name = book.seriesName?.trim().toLowerCase();
    if (!name || book.seriesPosition == null || !Number.isFinite(book.seriesPosition)) continue;
    const group = series.get(name) ?? [];
    group.push(book);
    series.set(name, group);
  }
  const next: DiscoveryBook[] = [];
  for (const group of series.values()) {
    const completed = group.filter((book) => readingStatus(book) === "finished");
    if (!completed.length) continue;
    const latest = Math.max(...completed.map((book) => book.seriesPosition!));
    const candidates = group.filter((book) => book.seriesPosition! > latest && ["unread", "in_progress"].includes(readingStatus(book)));
    candidates.sort((a, b) => a.seriesPosition! - b.seriesPosition! || a.title.localeCompare(b.title));
    if (candidates[0]) next.push(candidates[0]);
  }
  return next.sort((a, b) => a.seriesName!.localeCompare(b.seriesName!));
}

export function localLibraryStats(books: readonly DiscoveryBook[]) {
  const statuses = books.map(readingStatus);
  const authors = new Map<string, number>();
  for (const book of books) {
    const author = book.author?.trim();
    if (author) authors.set(author, (authors.get(author) ?? 0) + 1);
  }
  return {
    total_books: books.length,
    finished_count: statuses.filter((status) => status === "finished").length,
    in_progress_count: statuses.filter((status) => status === "in_progress").length,
    abandoned_count: statuses.filter((status) => status === "abandoned").length,
    top_authors: [...authors].map(([author, count]) => ({ author, count }))
      .sort((a, b) => b.count - a.count || a.author.localeCompare(b.author)).slice(0, 5),
  };
}

type OpenLibraryRecord = {
  title?: string;
  authors?: { name: string }[];
  subjects?: { name: string }[];
  publishers?: { name: string }[];
  publish_date?: string;
};

export async function lookupIsbn(isbn: string, signal: AbortSignal): Promise<InsightMetadata | undefined> {
  const key = `ISBN:${isbn}`;
  const url = new URL("https://openlibrary.org/api/books.json");
  url.search = new URLSearchParams({ bibkeys: key, jscmd: "data" }).toString();
  const response = await fetch(url, { signal, credentials: "omit", referrerPolicy: "no-referrer", headers: { Accept: "application/json" } });
  if (!response.ok) throw new Error("Open Library is unavailable.");
  const records = await response.json() as Record<string, OpenLibraryRecord>;
  const record = records[key];
  if (!record?.title) return undefined;
  return {
    title: record.title,
    author: record.authors?.map(({ name }) => name).join(", "),
    isbn,
    subjects: record.subjects?.map(({ name }) => name),
    publisher: record.publishers?.[0]?.name,
    publish_date: record.publish_date,
  };
}
