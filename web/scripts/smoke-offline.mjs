import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { chromium, expect } from "@playwright/test";

const origin = process.argv[2] ?? "http://localhost:8000";
const fixture = fileURLToPath(new URL("../../reader/src/test/resources/highlights/selection.epub", import.meta.url));
const profile = await mkdtemp(join(tmpdir(), "quire-offline-"));
const bootstrap = () => fetch("/quire/v1/bootstrap").then((response) => response.ok, () => false);

let context;
try {
  context = await chromium.launchPersistentContext(profile);
  let page = context.pages()[0];
  await page.goto(`${origin}/quire/`);
  await page.waitForFunction(() => Boolean(navigator.serviceWorker.controller));
  assert.equal(await page.evaluate(bootstrap), true);
  const cacheEntries = await page.evaluate(async () => {
    const names = (await caches.keys()).filter((name) => name.startsWith("quire-public-shell-"));
    if (names.length !== 1) return [];
    return (await (await caches.open(names[0])).keys()).map((key) => new URL(key.url).pathname);
  });
  assert(cacheEntries.includes("/quire/index.html"));
  assert(cacheEntries.some((path) => path.startsWith("/quire/assets/")));
  assert(cacheEntries.every((path) => path.startsWith("/quire/") && !path.startsWith("/quire/v1/")));

  // The default server offers sign-in, so a new visitor starts on the welcome page.
  await page.getByRole("button", { name: "Read on this device without an account" }).click();
  // The guest shelf opens its browser store after the click; import stays disabled until then.
  await expect(page.locator('input[type="file"]')).toBeEnabled();
  await page.locator('input[type="file"]').setInputFiles(fixture);
  await page.getByRole("button", { name: "Read", exact: true }).click();
  await expect(page.frameLocator("iframe").getByRole("heading", { name: "Selection laboratory" })).toBeVisible();
  const readerPage = page.url();
  await context.close();

  context = await chromium.launchPersistentContext(profile);
  await context.setOffline(true);
  page = context.pages()[0];
  const response = await page.goto(readerPage);
  assert(response?.ok() && response.fromServiceWorker());
  assert.equal(await page.evaluate(bootstrap), false);
  const chapter = page.frameLocator("iframe");
  await expect(chapter.getByRole("heading", { name: "Selection laboratory" })).toBeVisible();
  await page.getByRole("button", { name: "Contents", exact: true }).click();
  await page.getByRole("button", { name: "Second chapter", exact: true }).click();
  await expect(chapter.getByRole("heading", { name: "Second chapter" })).toBeVisible();
  console.log("An offline Chromium restart reopened the EPUB and changed chapters without caching the private API.");
} finally {
  await context?.close();
  await rm(profile, { recursive: true, force: true });
}
