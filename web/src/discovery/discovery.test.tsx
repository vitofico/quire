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
    fireEvent.click(screen.getByRole("switch", { name: "Enable AI insights and reader profiles" }));
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

  it("clears an insight when the app language changes, as it was written in the old one", async () => {
    const request = mockAccount({ enabled: true });
    const panel = (lang: string) => <InsightPanel request={request} identity={sample} metadata={{ title: "Sample" }} t={translator(lang)} />;
    const { rerender } = render(panel("en"));
    fireEvent.click(await screen.findByRole("button", { name: "Get insight" }));
    await screen.findByText("An insightful introduction");
    rerender(panel("en"));
    expect(screen.getByText("An insightful introduction")).toBeTruthy();
    rerender(panel("it"));
    await waitFor(() => expect(screen.queryByText("An insightful introduction")).toBeNull());
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

  it("says when the saved profile is in another language than the reader's insights", async () => {
    const profile = (language?: string) => ({
      payload: {
        narrative: "A vivid reader", language, stats: { total_books: 1, finished_count: 1, in_progress_count: 0, abandoned_count: 0 },
        in_library_recommendations: [], discovery_recommendations: [], ai_suggested_recommendations: [],
      },
      generated_at: "2025-11-01T00:00:00Z", model_id: "test-model",
    });
    const request = vi.fn<DiscoveryRequest>(async (path) => {
      switch (path) {
        case "/ai/v1/config": return response({ configured: true, providers: [], progress_supported: true });
        case "/ai/v1/preferences": return response({ ai_enabled: true, style: { tone: "neutral", language: "it" }, provider_id: "default" });
        case "/ai/v1/profile": return response(profile());
        case "/ai/v1/profile/refresh": return response(profile("it"));
        default: throw new Error(`Unexpected request ${path}`);
      }
    });
    render(<ReaderProfilePanel request={request} />);
    await screen.findByText("A vivid reader");
    expect(request.mock.calls.some(([path]) => path === "/ai/v1/profile/refresh")).toBe(false);
    expect(screen.getByText("Written in English. Refresh to write it in Italian.")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Refresh profile" }));
    await waitFor(() => expect(screen.queryByText(/Written in/)).toBeNull());
  });

  it("requires confirmation for deleting a saved profile even when AI is opted out", async () => {
    const account = mockAccount();
    const request = vi.fn<DiscoveryRequest>(async (path, init) => {
      if (path !== "/ai/v1/profile") return account(path, init);
      if (init?.method === "DELETE") return new Response(null, { status: 204 });
      return response({
        payload: { narrative: "A saved profile", in_library_recommendations: [], discovery_recommendations: [], ai_suggested_recommendations: [] },
        generated_at: "2025-11-01T00:00:00Z", model_id: "test-model",
      });
    });
    render(<ReaderProfilePanel request={request} />);
    await screen.findByText("A saved profile");
    fireEvent.click(await screen.findByRole("button", { name: "Delete profile" }));
    expect(request.mock.calls.some(([path, init]) => path === "/ai/v1/profile" && init?.method === "DELETE")).toBe(false);
    fireEvent.click(screen.getByRole("button", { name: "Delete saved profile" }));
    await waitFor(() => expect(screen.getByText("Reader profile deleted.")).toBeTruthy());
  });
});

describe("AI settings saved as they change", () => {
  /**
   * An account that applies each preference save as it arrives, in order, but
   * answers only when the test says so: answers can come back in any order.
   * `refused` lists the saves (by arrival) the server turns down.
   */
  function slowAccount(refused: number[] = []) {
    let held = { ai_enabled: true, style, provider_id: "default" };
    const answers: Array<() => void> = [];
    const request = vi.fn<DiscoveryRequest>(async (path, init) => {
      if (path === "/ai/v1/config") return response({ configured: true, providers: [], progress_supported: false });
      if (path === "/ai/v1/preferences" && init?.method === "PUT") {
        const fail = refused.includes(answers.length);
        if (!fail) held = { ...held, ...JSON.parse(String(init.body)) };
        const answer = fail ? response({ detail: "unavailable" }, 503) : response(held);
        return new Promise<Response>((resolve) => answers.push(() => resolve(answer)));
      }
      if (path === "/ai/v1/preferences") return response(held);
      throw new Error(`Unexpected request ${path}`);
    });
    return { request, answers, held: () => held };
  }
  const tone = () => (screen.getByRole("combobox", { name: "Tone" }) as HTMLSelectElement).value;
  const settle = () => new Promise((resolve) => setTimeout(resolve, 20));

  async function twoChanges(request: DiscoveryRequest, answers: unknown[]) {
    const select = await screen.findByRole("combobox", { name: "Tone" });
    fireEvent.change(select, { target: { value: "casual" } });
    fireEvent.change(select, { target: { value: "scholarly" } });
    await waitFor(() => expect(answers).toHaveLength(2));
  }

  it("keeps showing the newest change when the older one is answered first", async () => {
    const { request, answers } = slowAccount();
    render(<AiSettingsPanel request={request} />);
    await twoChanges(request, answers);
    answers[0]();
    await settle();
    expect(tone()).toBe("scholarly");
    answers[1]();
    await settle();
    expect(tone()).toBe("scholarly");
  });

  it("keeps the account's shared settings on the newest change when answers come back out of order", async () => {
    const { request, answers, held } = slowAccount();
    const { unmount } = render(<AiSettingsPanel request={request} />);
    await twoChanges(request, answers);
    answers[1]();
    answers[0]();
    await settle();
    expect(held().style.tone).toBe("scholarly");
    expect(tone()).toBe("scholarly");
    // Another view of the same account reads the shared settings.
    unmount();
    request.mockImplementation(async () => new Promise<Response>(() => undefined));
    render(<AiSettingsPanel request={request} />);
    expect(tone()).toBe("scholarly");
  });

  it("goes back to what the account holds when the newest change is refused", async () => {
    const { request, answers } = slowAccount([1]);
    render(<AiSettingsPanel request={request} />);
    await twoChanges(request, answers);
    answers[0]();
    answers[1]();
    expect(await screen.findByRole("alert")).toBeTruthy();
    // The earlier change landed, so that is what shows, not the value before both.
    await waitFor(() => expect(tone()).toBe("casual"));
  });

  it("does not turn AI on for a provider the server no longer offers", async () => {
    const request = mockAccount({ provider: "removed" });
    render(<AiSettingsPanel request={request} />);
    const optIn = await screen.findByRole("switch", { name: "Enable AI insights and reader profiles" });
    expect((optIn as HTMLInputElement).disabled).toBe(true);
    expect(screen.getByText(/previously selected provider is no longer available/)).toBeTruthy();
  });
});
