// Generated worker constants describe only files produced by the web build.
const PREFIX = "quire-public-shell-";
const CACHE = PREFIX + "__BUILD_HASH__";
const PUBLIC_PATHS = __PUBLIC_PATHS__;
const PUBLIC = new Set(PUBLIC_PATHS);
const INDEX = "/quire/index.html";
// "/" when this host serves the reader at its root, otherwise "/quire/".
const HOME = new URL(self.registration.scope).pathname;

function cached(path) {
  return caches.open(CACHE).then((cache) => cache.match(path)).catch(() => undefined);
}

self.addEventListener("install", (event) => {
  event.waitUntil((async () => {
    const cache = await caches.open(CACHE);
    const responses = await Promise.all(PUBLIC_PATHS.map(async (path) => {
      const request = new Request(path, { credentials: "omit", cache: "reload", redirect: "error" });
      const response = await fetch(request);
      if (!response.ok || response.headers.get("X-Quire-Public-Asset") !== "1") {
        throw new Error(`Quire shell unavailable: ${path}`);
      }
      return [path, response];
    }));
    await Promise.all(responses.map(([path, response]) => cache.put(path, response)));
  })());
});

// Let the previous worker serve its own build until its clients close.
self.addEventListener("activate", (event) => {
  event.waitUntil((async () => {
    for (const key of await caches.keys()) {
      if (key.startsWith(PREFIX) && key !== CACHE) await caches.delete(key);
    }
    await self.clients.claim();
  })());
});

self.addEventListener("fetch", (event) => {
  const { request } = event;
  const url = new URL(request.url);
  if (request.method !== "GET" || url.origin !== self.location.origin ||
      !(url.pathname.startsWith("/quire/") || url.pathname === HOME) ||
      request.headers.has("Authorization")) return;

  if (request.mode === "navigate" &&
      url.pathname !== "/quire/assets" &&
      !url.pathname.startsWith("/quire/assets/") &&
      !/^\/quire\/v\d+(?:\/|$)/i.test(url.pathname) &&
      !url.pathname.split("/").some((segment) => segment.includes("."))) {
    event.respondWith((async () => {
      const shell = cached(INDEX);
      try {
        const response = await fetch(request);
        // Respect an operator disabling the Web mount while preserving this
        // worker's build for successful navigations until its clients close.
        if (!response.ok || response.headers.get("X-Quire-Public-Asset") !== "1") return response;
        return (await shell) ?? response;
      } catch {
        return (await shell) ?? Response.error();
      }
    })());
  } else if (!url.search && PUBLIC.has(url.pathname) && url.pathname.startsWith("/quire/assets/")) {
    event.respondWith((async () => {
      return (await cached(url.pathname)) ?? fetch(request);
    })());
  }
});
