import { affinityReason } from "./affinityText";
import { english, type Translate } from "./translation";
import { useCallback, useEffect, useRef, useState } from "react";
import { discoveryJson, insightBundle, jsonBody, type InsightMetadata } from "./api";
import { ReaderProfilePanel } from "./AiSettings";
import { CameraScan, supportsCameraScanning } from "./CameraScan";
import { Icon } from "../icons";
import { InsightPanel, type AiPanelProps } from "./Insights";
import { localLibraryStats, lookupIsbn, normalizeIsbn, readingStatus, seriesContinuation, type DiscoveryBook } from "./books";
import { themeLabel } from "./themes";

type DiscoveryPanelProps = AiPanelProps & {
  books: readonly DiscoveryBook[];
  syncAvailable?: boolean;
  onOpenBook?: (bookId: string) => void;
};

type ServerStats = {
  total_books: number;
  finished_count: number;
  in_progress_count: number;
  abandoned_count: number;
  top_authors: { name: string; count: number }[];
  top_themes: { theme: string; count: number }[];
  themes_caveat: string;
};

function statusText(status: string, t: Translate): string {
  return t(status === "in_progress" ? "In progress" : status);
}

type Affinity = {
  owned?: { in_library: boolean; reading_status: string } | null;
  score?: number | null;
  band: string;
  reasons: { message: string }[];
};

export function DiscoveryPanel({ books, onOpenBook, request, aiAvailable, syncAvailable = true, t = english }: DiscoveryPanelProps) {
  return (
    <section className="discovery-panel" aria-label={t("Discovery")}>
      <LibraryStats books={books} onOpenBook={onOpenBook} request={syncAvailable ? request : undefined} t={t} />
      <IsbnLookup books={books} onOpenBook={onOpenBook} request={request} aiAvailable={aiAvailable} syncAvailable={syncAvailable} t={t} />
      {syncAvailable && <ReaderProfilePanel request={request} aiAvailable={aiAvailable} t={t} />}
    </section>
  );
}

function LibraryStats({ books, onOpenBook, request, t = english }: DiscoveryPanelProps) {
  const [syncedStats, setSyncedStats] = useState<{ request: NonNullable<typeof request>; stats: ServerStats }>();
  const local = localLibraryStats(books);
  const synced = syncedStats?.request === request ? syncedStats?.stats : undefined;
  const stats = synced ?? local;
  const authors = synced?.top_authors.map(({ name, count }) => ({ author: name, count })) ?? local.top_authors;
  const continuation = seriesContinuation(books);

  useEffect(() => {
    if (!request) return;
    const controller = new AbortController();
    void discoveryJson<ServerStats>(request, "/library/v1/stats", { signal: controller.signal })
      .then((stats) => {
        if (!controller.signal.aborted) setSyncedStats({ request, stats });
      })
      .catch(() => undefined);
    return () => controller.abort();
  }, [request]);

  return (
    <section className="discovery-panel panel">
      <h3>{t("Library stats")}</h3>
      <p className="muted">{t(synced ? "Connected library" : "Library on this device")}</p>
      <dl className="discovery-stats">
        <div><dt>{t("Books")}</dt><dd>{stats.total_books}</dd></div>
        <div><dt>{t("Finished")}</dt><dd>{stats.finished_count}</dd></div>
        <div><dt>{t("In progress")}</dt><dd>{stats.in_progress_count}</dd></div>
        <div><dt>{t("Abandoned")}</dt><dd>{stats.abandoned_count}</dd></div>
      </dl>
      {authors.length > 0 && <details><summary>{t("Top authors")}</summary><ul>{authors.map(({ author, count }) => <li key={author}>{author} · {count}</li>)}</ul></details>}
      {!!synced?.top_themes.length && (
        <details>
          <summary>{t("Top themes")}</summary>
          <ul>{synced.top_themes.map(({ theme, count }) => <li key={theme}>{themeLabel(theme, t)} · {count}</li>)}</ul>
          <p>{t(synced.themes_caveat)}</p>
        </details>
      )}
      {continuation.length > 0 && (
        <div>
          <h4>{t("Continue a series")}</h4>
          <ul>{continuation.map((book) => (
            <li key={book.id}>
              {onOpenBook ? <button onClick={() => onOpenBook(book.id)}>{book.title}</button> : book.title}
              {" · "}{book.seriesName} #{book.seriesPosition}
            </li>
          ))}</ul>
        </div>
      )}
    </section>
  );
}

function IsbnLookup({ books, onOpenBook, request, aiAvailable, syncAvailable = true, t = english }: DiscoveryPanelProps) {
  const [input, setInput] = useState("");
  const [scanning, setScanning] = useState(false);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");
  const [metadata, setMetadata] = useState<InsightMetadata>();
  const [affinity, setAffinity] = useState<{ request: NonNullable<typeof request>; result?: Affinity }>();
  const operation = useRef<AbortController | undefined>(undefined);

  useEffect(() => () => {
    operation.current?.abort();
  }, [request, syncAvailable]);

  const lookup = useCallback(async (raw: string) => {
    const isbn = normalizeIsbn(raw);
    operation.current?.abort();
    setBusy(false);
    setError("");
    setMessage("");
    setMetadata(undefined);
    setAffinity(undefined);
    if (!isbn) {
      setError("Enter a valid ISBN-10 or ISBN-13.");
      return;
    }
    const controller = new AbortController();
    operation.current = controller;
    setBusy(true);
    try {
      const found = await lookupIsbn(isbn, controller.signal);
      if (controller.signal.aborted) return;
      if (!found) {
        setMessage("Open Library has no record for this ISBN.");
        return;
      }
      setMetadata(found);
      if (request && syncAvailable) {
        try {
          const result = await discoveryJson<Affinity>(request, "/library/v1/affinity", jsonBody("POST", {
            identity: { metadata_id: `isbn:${isbn}`, isbn },
            bundle: insightBundle(found),
          }, controller.signal));
          if (!controller.signal.aborted) setAffinity({ request, result });
        } catch {
          if (!controller.signal.aborted) setAffinity({ request });
        }
      }
    } catch {
      if (!controller.signal.aborted) setError("Open Library could not be reached. Check your connection and retry.");
    } finally {
      if (operation.current === controller) {
        operation.current = undefined;
        setBusy(false);
      }
    }
  }, [request, syncAvailable]);

  const scanned = useCallback((isbn: string) => {
    setInput(isbn);
    setScanning(false);
  }, []);

  const isbn = normalizeIsbn(metadata?.isbn);
  const owns = isbn ? books.find((book) => normalizeIsbn(book.isbn) === isbn) : undefined;
  const affinityResult = syncAvailable && affinity?.request === request ? affinity?.result : undefined;

  return (
    <section className="discovery-panel panel">
      <h3>{t("Look up an ISBN")}</h3>
      <p className="muted">{t("Lookup sends the ISBN to Open Library.")}</p>
      {syncAvailable && request && <p>{t("Your connected library checks ownership and reading affinity.")}</p>}
      {scanning && <CameraScan t={t} onScan={scanned} onClose={() => setScanning(false)} />}
      <form className="isbn-form" onSubmit={(event) => { event.preventDefault(); void lookup(input); }}>
        <label>{t("ISBN")}<input value={input} inputMode="numeric" onChange={(event) => setInput(event.target.value)} autoComplete="off" /></label>
        <div className="discovery-actions">
          <button className="primary" disabled={busy || !input.trim()}>{t("Look up on Open Library")}</button>
          {supportsCameraScanning() && !scanning && (
            <button type="button" onClick={() => setScanning(true)}><Icon name="scan" />{t("Scan barcode")}</button>
          )}
          {busy && <button type="button" onClick={() => operation.current?.abort()}>{t("Cancel lookup")}</button>}
        </div>
      </form>
      {busy && <p role="status">{t("Looking up edition…")}</p>}
      {message && <p role="status">{t(message)}</p>}
      {error && <p role="alert">{t(error)}</p>}
      {metadata && isbn && (
        <article>
          <img className="discovery-cover" src={`https://covers.openlibrary.org/b/isbn/${isbn}-M.jpg?default=false`} alt="" referrerPolicy="no-referrer" onError={(event) => { event.currentTarget.hidden = true; }} key={isbn} />
          <h4>{metadata.title}</h4>
          {metadata.author && <p>{metadata.author}</p>}
          {owns && (
            <p>
              {t("In your library")} · {statusText(readingStatus(owns), t)}
              {onOpenBook && <> · <button onClick={() => onOpenBook(owns.id)}>{t("Open book")}</button></>}
            </p>
          )}
          {!owns && affinityResult?.owned?.in_library && <p>{t("In your connected library")} · {statusText(affinityResult.owned.reading_status, t)}</p>}
          {affinityResult && (
            <div>
              <h4>{t("Reading affinity")}</h4>
              <p>{affinityResult.band === "unknown" ? t("More reading history is needed to estimate affinity.") : `${t(affinityResult.band)}${affinityResult.score != null ? ` · ${affinityResult.score}` : ""}`}</p>
              {!!affinityResult.reasons.length && <ul>{affinityResult.reasons.map((reason, index) => <li key={index}>{affinityReason(reason.message, t)}</li>)}</ul>}
            </div>
          )}
          {syncAvailable && affinity?.request === request && affinity && !affinityResult && <p>{t("Reading affinity is unavailable on this connection.")}</p>}
          <InsightPanel t={t} request={request} aiAvailable={aiAvailable} identity={{ metadata_id: `isbn:${isbn}`, isbn }} metadata={metadata} />
        </article>
      )}
    </section>
  );
}
