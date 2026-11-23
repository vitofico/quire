import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  catalogInsightIdentity, type DiscoveryRequest, insightBundle, insightIdentity, promotePreview, rememberPreview,
} from "./api";
import { translator } from "../language";
import { DiscoveryPanel } from "./DiscoveryPanel";
import { AiSettingsPanel, ReaderProfilePanel } from "./AiSettings";
import { InsightPanel } from "./Insights";
import { normalizeIsbn, seriesContinuation } from "./books";

const sample = { metadata_id: "book-123", content_hash: "hash-123" };
const style = { tone: "neutral", language: "auto" };
const result = {
  payload: { intro: "An insightful introduction" },
  sources: [{ kind: "openlibrary", title: "Open Library record", url: "https://openlibrary.org/works/OL123W", snippet: "A reference" }],
  generated_at: "2025-11-01T00:00:00Z",
  model_id: "test-model",
  prompt_version: "3",
};

function response(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

function mockAccount({ enabled = false, configured = true, provider = "default", quota = false } = {}) {
  let preferences = { ai_enabled: enabled, style, provider_id: provider };
  return vi.fn<DiscoveryRequest>(async (path, init) => {
    switch (path) {
      case "/ai/v1/config": return response({ configured, providers: [], progress_supported: true });
      case "/ai/v1/preferences":
        if (init?.method === "PUT") preferences = { ...preferences, ...JSON.parse(String(init.body)) };
        return response(preferences);
      case "/ai/v1/insights/get": return response({ detail: "not_cached" }, 404);
      case "/ai/v1/insights/lookup": return quota ? response({ detail: { used: 2, limit: 2 } }, 429) : response(result);
      default: throw new Error(`Unexpected request ${path}`);
    }
  });
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("discovery", () => {
  it("normalizes valid ISBNs and continues the first unread volume after the latest finished book", () => {
    expect(normalizeIsbn("0-8044-2957-X")).toBe("9780804429573");
    expect(normalizeIsbn("9780804429572")).toBeUndefined();
    expect(normalizeIsbn("0000000000000")).toBeUndefined();
    expect(seriesContinuation([
      { id: "2", title: "Second", seriesName: "Example", seriesPosition: 2, status: "finished" },
      { id: "4", title: "Fourth", seriesName: "Example", seriesPosition: 4 },
      { id: "3", title: "Third", seriesName: "example", seriesPosition: 3 },
      { id: "1", title: "First", seriesName: "Example", seriesPosition: 1 },
    ]).map(({ id }) => id)).toEqual(["3"]);
  });

  it("looks up ISBNs without AI or camera, checks deterministic affinity, and displays local ownership", async () => {
    const openLibrary = vi.fn(async () => response({ "ISBN:9780804429573": { title: "Sample", authors: [{ name: "Author" }] } }));
    vi.stubGlobal("fetch", openLibrary);
    const request = vi.fn<DiscoveryRequest>(async (path) => path === "/library/v1/affinity"
      ? response({ band: "high", score: 95, reasons: [{ message: "You read this author." }] })
      : response(null, 404));
    const open = vi.fn();
    render(<DiscoveryPanel books={[{ id: "owned", title: "Sample", isbn: "080442957X" }]} request={request} aiAvailable={false} onOpenBook={open} />);
    expect(screen.queryByRole("button", { name: "Scan barcode" })).toBeNull();
    fireEvent.change(screen.getByLabelText("ISBN"), { target: { value: "0-8044-2957-X" } });
    fireEvent.click(screen.getByRole("button", { name: "Look up on Open Library" }));
    await screen.findByText("You read this author.");
    fireEvent.click(screen.getByRole("button", { name: "Open book" }));
    expect(open).toHaveBeenCalledWith("owned");
    expect(openLibrary).toHaveBeenCalledWith(expect.any(URL), expect.objectContaining({ credentials: "omit" }));
    expect(request.mock.calls.map(([path]) => path)).toEqual(["/library/v1/stats", "/library/v1/affinity"]);
  });

  it.each([
    ["es", "Buscar un ISBN", "Estadísticas de lectura"],
    ["it", "Cerca un ISBN", "Statistiche della biblioteca"],
    ["fr", "Rechercher un ISBN", "Statistiques de la bibliothèque"],
    ["de", "ISBN nachschlagen", "Bibliotheksstatistik"],
  ])("renders discovery headings in %s in local mode", (locale, isbn, stats) => {
    render(<DiscoveryPanel books={[]} t={translator(locale)} />);
    expect(screen.getByRole("heading", { name: isbn })).toBeTruthy();
    expect(screen.getByRole("heading", { name: stats })).toBeTruthy();
  });

  it("only sends allowlisted metadata and identity values", () => {
    const metadata = { title: "Book", author: "Writer", private_notes: "private", blob: "EPUB" };
    const identity = { ...sample, highlight: "private" };
    expect(JSON.parse(JSON.stringify(insightBundle(metadata)))).toEqual({ title: "Book", author: "Writer", subjects: [] });
    expect(JSON.parse(JSON.stringify(insightIdentity(identity)))).toEqual(sample);
  });

  it("looks up a preview and promotes the same edition without generating it again", async () => {
    const request = vi.fn<DiscoveryRequest>(async () => response({ promoted: true, already_promoted: false }));
    const catalog = catalogInsightIdentity("https://catalog.example/books/123.epub", "catalog-123", "9780804429573");
    expect(catalog.opds_href).toBe(catalog.metadata_id);
    expect(catalog.metadata_id).toMatch(/^opds-href:[a-f0-9]{64}$/);
    rememberPreview(request, catalog, style);
    await promotePreview(request, { ...sample, isbn: "080442957X" });
    await promotePreview(request, { ...sample, opds_href: catalog.opds_href });
    expect(request).toHaveBeenCalledTimes(1);
    const [path, init] = request.mock.calls[0];
    expect(path).toBe("/ai/v1/insights/promote");
    expect(JSON.parse(String(init?.body))).toEqual({ from: catalog, to: { ...sample, isbn: "080442957X" }, ...style });
  });
});

describe("AI panels", () => {
  it("does not generate before explicit enablement and a generation action", async () => {
    const request = mockAccount();
    render(<><AiSettingsPanel request={request} /><InsightPanel request={request} identity={sample} metadata={{ title: "Sample" }} /></>);
    await screen.findByText("Enable AI in Settings to open insights.");
    expect(request).toHaveBeenCalledTimes(2);
    fireEvent.click(screen.getByRole("checkbox", { name: "Enable AI insights and reader profiles" }));
    fireEvent.click(screen.getByRole("button", { name: "Save AI settings" }));
    const generate = await screen.findByRole("button", { name: "Get insight" });
    expect(request.mock.calls.some(([path]) => path === "/ai/v1/insights/lookup")).toBe(false);
    fireEvent.click(generate);
    expect(await screen.findByText("An insightful introduction")).toBeTruthy();
    fireEvent.click(screen.getByText("Sources (1)"));
    expect(screen.getByRole("link", { name: "Open Library record" }).getAttribute("href")).toBe("https://openlibrary.org/works/OL123W");
    expect(request.mock.calls.filter(([path]) => path.startsWith("/ai/v1/insights/")).map(([path]) => path))
      .toEqual(["/ai/v1/insights/get", "/ai/v1/insights/lookup"]);
  });

  it("handles disabled server, removed provider, and budget exhaustion", async () => {
    const disabled = mockAccount({ configured: false });
    const removed = mockAccount({ enabled: true, provider: "removed" });
    const noQuota = mockAccount({ enabled: true, quota: true });
    const { rerender } = render(<InsightPanel request={disabled} identity={sample} metadata={{ title: "Sample" }} />);
    await screen.findByText("AI is unavailable on this server.");
    expect(disabled).toHaveBeenCalledTimes(1);
    rerender(<InsightPanel request={removed} identity={sample} metadata={{ title: "Sample" }} />);
    await screen.findByText("Select an available AI provider in Settings.");
    expect(screen.queryByRole("button", { name: "Get insight" })).toBeNull();
    rerender(<InsightPanel request={noQuota} identity={sample} metadata={{ title: "Sample" }} />);
    fireEvent.click(await screen.findByRole("button", { name: "Get insight" }));
    await screen.findByText("AI limit reached. Try again after the quota resets.");
    expect(noQuota.mock.calls.filter(([path]) => path === "/ai/v1/insights/lookup")).toHaveLength(1);
  });

  it("can stop waiting for a paid request without aborting it or starting a duplicate", async () => {
    const account = mockAccount({ enabled: true });
    let finish: ((value: Response) => void) | undefined;
    const request = vi.fn<DiscoveryRequest>((path, init) => {
      if (path === "/ai/v1/insights/lookup") {
        return new Promise<Response>((resolve) => { finish = resolve; });
      }
      if (path === "/ai/v1/insights/get" && finish) return Promise.resolve(response(result));
      return account(path, init);
    });
    render(<InsightPanel request={request} identity={sample} metadata={{ title: "Sample" }} />);
    fireEvent.click(await screen.findByRole("button", { name: "Get insight" }));
    await waitFor(() => expect(finish).toBeDefined());
    fireEvent.click(screen.getByRole("button", { name: "Stop waiting" }));
    const lookup = request.mock.calls.find(([path]) => path === "/ai/v1/insights/lookup");
    expect(lookup?.[1]?.signal?.aborted).toBe(false);
    expect((screen.getByRole("button", { name: "Get insight" }) as HTMLButtonElement).disabled).toBe(true);
    finish?.(response(result));
    fireEvent.click(screen.getByRole("button", { name: "Check saved insight" }));
    await screen.findByText("An insightful introduction");
    expect(request.mock.calls.filter(([path]) => path === "/ai/v1/insights/lookup")).toHaveLength(1);
  });

  it("requires confirmation for deleting a saved profile even when AI is opted out", async () => {
    const account = mockAccount();
    const request = vi.fn<DiscoveryRequest>((path, init) => path === "/ai/v1/profile" && init?.method === "DELETE"
      ? Promise.resolve(new Response(null, { status: 204 }))
      : account(path, init));
    render(<ReaderProfilePanel request={request} />);
    fireEvent.click(await screen.findByRole("button", { name: "Delete profile" }));
    expect(request.mock.calls.some(([path]) => path === "/ai/v1/profile")).toBe(false);
    fireEvent.click(screen.getByRole("button", { name: "Delete saved profile" }));
    await waitFor(() => expect(screen.getByText("Reader profile deleted.")).toBeTruthy());
  });
});
