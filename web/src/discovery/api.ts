import { sha256 } from "@noble/hashes/sha2.js";
import { bytesToHex, utf8ToBytes } from "@noble/hashes/utils.js";
import { normalizeIsbn } from "./books";
import { interpolate, type Translate } from "./translation";

export type DiscoveryRequest = (path: string, init?: RequestInit) => Promise<Response>;

export type InsightIdentity = {
  metadata_id?: string | null;
  content_hash?: string | null;
  identity_hash_version?: number;
  opds_href?: string | null;
  opds_dc_id?: string | null;
  calibre_book_id?: string | null;
  isbn?: string | null;
};

export type InsightMetadata = {
  title: string;
  author?: string | null;
  language?: string | null;
  isbn?: string | null;
  publisher?: string | null;
  publish_date?: string | null;
  subjects?: readonly string[] | null;
  description?: string | null;
  series_name?: string | null;
  series_position?: number | null;
};

type Citation = { kind: string; title: string; url?: string | null; snippet: string };

export type Insight = {
  payload: {
    intro?: string;
    author?: { bio?: string; notable_works?: string[] };
    series?: { name: string; position?: number | null; context?: string };
    analysis?: string;
    content_warnings?: string[];
    themes?: string[];
    theme_analysis?: Record<string, string>;
    craft_notes?: string;
    comparative_anchors?: { book: string; author: string; similar_in: string; different_in?: string }[];
    distinctive_take?: string;
    curiosities?: string[];
    discussion_prompts?: string[];
    confidence?: string;
  };
  sources: Citation[];
  model_id: string;
  prompt_version: string;
  generated_at: string;
};

export type Recommendation = {
  title: string;
  author: string;
  rationale: string;
  source_url?: string | null;
  owned_state?: string;
  sources?: Citation[] | null;
};

export type Profile = {
  payload: {
    narrative?: string | null;
    stats: {
      total_books: number;
      finished_count: number;
      in_progress_count: number;
      abandoned_count: number;
    };
    in_library_recommendations: Recommendation[];
    discovery_recommendations: Recommendation[];
    ai_suggested_recommendations: Recommendation[];
  };
  generated_at: string;
  model_id: string;
};

export type Preferences = {
  ai_enabled: boolean;
  style: { language: string; tone: string };
  provider_id: string;
};

export type AiConfig = {
  configured: boolean;
  progress_supported?: boolean;
  providers: { id: string; label: string; base_url_host: string; model_id: string }[];
  sources_enabled: string[];
  daily_budget: number;
  regen_daily_limit: number;
};

export class DiscoveryHttpError extends Error {
  constructor(readonly status: number, readonly detail: unknown) {
    super(`Discovery request failed (${status})`);
  }
}

export async function discoveryJson<T>(
  request: DiscoveryRequest,
  path: string,
  init?: RequestInit,
): Promise<T> {
  const response = await request(path, init);
  if (!response.ok) {
    const data = await response.json().catch(() => null) as { detail?: unknown } | null;
    throw new DiscoveryHttpError(response.status, data?.detail);
  }
  return (response.status === 204 ? undefined : await response.json()) as T;
}

export function jsonBody(method: "POST" | "PUT", data: unknown, signal?: AbortSignal): RequestInit {
  return { method, headers: { "Content-Type": "application/json" }, body: JSON.stringify(data), signal };
}

export type RequestMessage = { message: string; reset?: string };

export function translateRequestMessage(t: Translate, error: RequestMessage): string {
  if (!error.reset) return t(error.message);
  return interpolate(t, error.message, { date: new Date(error.reset).toLocaleString(document.documentElement.lang || undefined) });
}

export function requestMessage(error: unknown): RequestMessage {
  if (error instanceof DiscoveryHttpError) {
    const { status, detail } = error;
    if (status === 401 || status === 403) return { message: "This feature needs a supported account and AI permission." };
    if (status === 429) {
      const reset = (detail as { resets_at?: string } | null)?.resets_at;
      return reset
        ? { message: "AI limit reached. Resets {date}.", reset }
        : { message: "AI limit reached. Try again after the quota resets." };
    }
    if (status === 409 && detail === "ai_provider_unavailable") return { message: "The selected provider was removed. Choose another provider in Settings." };
    if (status === 502 || status === 503 || status === 504) {
      return { message: "The AI provider is unavailable. Check the provider in Settings and try again later." };
    }
    if (status === 404) return { message: "This feature is unavailable on the connected server." };
    if (status === 422) return { message: "The server could not use this edition or these preferences." };
  }
  return { message: "The request could not be completed. Check your connection." };
}

export function isHttpStatus(error: unknown, status: number): boolean {
  return error instanceof DiscoveryHttpError && error.status === status;
}

export function publicLink(value: string | null | undefined): string | undefined {
  if (!value) return undefined;
  try {
    const url = new URL(value);
    if ((url.protocol === "https:" || url.protocol === "http:") && !url.username && !url.password) {
      return url.href;
    }
  } catch {
    return undefined;
  }
  return undefined;
}

export function insightIdentity(identity: InsightIdentity): InsightIdentity {
  return {
    metadata_id: identity.metadata_id,
    content_hash: identity.content_hash,
    identity_hash_version: identity.identity_hash_version,
    opds_href: identity.opds_href,
    opds_dc_id: identity.opds_dc_id,
    calibre_book_id: identity.calibre_book_id,
    isbn: identity.isbn,
  };
}

export function catalogInsightIdentity(href: string, id?: string | null, isbn?: string | null): InsightIdentity {
  const key = `opds-href:${bytesToHex(sha256(utf8ToBytes(href)))}`;
  return {
    metadata_id: key,
    opds_href: key,
    opds_dc_id: id,
    isbn,
  };
}

export function insightBundle(metadata: InsightMetadata): InsightMetadata {
  return {
    title: metadata.title.trim().slice(0, 512),
    author: metadata.author?.slice(0, 512),
    language: metadata.language?.slice(0, 32),
    isbn: metadata.isbn?.slice(0, 64),
    publisher: metadata.publisher?.slice(0, 256),
    publish_date: metadata.publish_date?.slice(0, 32),
    subjects: metadata.subjects?.slice(0, 64).map((subject) => subject.slice(0, 80)) ?? [],
    description: metadata.description?.slice(0, 4096),
    series_name: metadata.series_name?.slice(0, 256),
    series_position: metadata.series_position == null
      ? undefined
      : Math.min(10_000, Math.max(0, Math.trunc(metadata.series_position))),
  };
}

export function promoteInsight(
  request: DiscoveryRequest,
  from: InsightIdentity,
  to: InsightIdentity,
  style: Preferences["style"],
): Promise<{ promoted: boolean; already_promoted: boolean } | undefined> {
  return discoveryJson(request, "/ai/v1/insights/promote", jsonBody("POST", {
    from: insightIdentity(from), to: insightIdentity(to), tone: style.tone, language: style.language,
  }));
}

type Preview = { identity: InsightIdentity; style: Preferences["style"]; promoting?: Promise<void> };
const previewByAccount = new WeakMap<DiscoveryRequest, Map<string, Preview>>();
function previewKeys(identity: InsightIdentity): string[] {
  const isbn = normalizeIsbn(identity.isbn);
  return [identity.opds_href && `href:${identity.opds_href}`, isbn && `isbn:${isbn}`]
    .filter((key): key is string => Boolean(key));
}

export function rememberPreview(
  request: DiscoveryRequest, identity: InsightIdentity, style: Preferences["style"],
): void {
  if (identity.content_hash) return;
  const previews = previewByAccount.get(request) ?? new Map<string, Preview>();
  const preview = { identity, style };
  for (const key of previewKeys(identity)) previews.set(key, preview);
  previewByAccount.set(request, previews);
}

export async function promotePreview(
  request: DiscoveryRequest, to: InsightIdentity,
): Promise<void> {
  const previews = previewByAccount.get(request);
  const preview = previewKeys(to).map((key) => previews?.get(key)).find(Boolean);
  if (!preview || !to.content_hash) return;
  if (preview.promoting) return preview.promoting;
  const pending = promoteInsight(request, preview.identity, to, preview.style).then((result) => {
    if (result?.promoted || result?.already_promoted) {
      for (const key of previewKeys(preview.identity)) {
        if (previews?.get(key) === preview) previews.delete(key);
      }
    }
  }).finally(() => { preview.promoting = undefined; });
  preview.promoting = pending;
  return pending;
}
