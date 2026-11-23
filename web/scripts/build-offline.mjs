import { createHash } from "node:crypto";
import { relative } from "node:path";
import { fileURLToPath } from "node:url";
import { readFile, readdir, writeFile } from "node:fs/promises";

const dist = new URL("../dist/", import.meta.url);
const distPath = fileURLToPath(dist);
const files = (await readdir(dist, { recursive: true, withFileTypes: true }))
  .filter((entry) => entry.isFile())
  .map((entry) => relative(distPath, `${entry.parentPath}/${entry.name}`))
  .filter((file) => file.startsWith("assets/") || ["index.html", "manifest.webmanifest", "icon-192.png", "icon-512.png"].includes(file))
  .sort();

await writeFile(new URL("version.json", dist), JSON.stringify({ version: process.env.QUIRE_VERSION ?? "dev" }));
files.push("version.json");

const worker = await readFile(new URL("./service-worker.js", import.meta.url), "utf8");
const hash = createHash("sha256").update(worker);
for (const file of files) hash.update(file).update(await readFile(new URL(file, dist)));
await writeFile(new URL("sw.js", dist), worker
  .replace("__BUILD_HASH__", hash.digest("hex"))
  .replace("__PUBLIC_PATHS__", JSON.stringify(files.map((file) => `/quire/${file}`))));
