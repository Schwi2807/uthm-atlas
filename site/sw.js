// UTHM Atlas service worker: precache everything (shell, bundle, PMTiles, glyphs, photos) so the map works
// offline after one visit. Range requests (PMTiles) are answered from the cached file with 206 slices.
// ASSETS and VERSION are rewritten by `atlas build` (engine/atlas/sitefiles.py): don't edit them by hand.
// PREFIX is unique on the shared schwi2807.github.io origin, so this never touches another app's caches.
const PREFIX = 'uthm-atlas-';
const VERSION = PREFIX + '6b8b55438e03'; // <version>
const ASSETS = [
  "./",
  "css/app.css",
  "data/campus.bundle",
  "data/campus.pmtiles",
  "data/suggestions.json",
  "data/version.json",
  "fonts/overpass-latin-400-normal.woff2",
  "fonts/overpass-latin-600-normal.woff2",
  "fonts/overpass-latin-800-normal.woff2",
  "icons/icon-180.png",
  "icons/icon-192.png",
  "icons/icon-512.png",
  "icons/icon.svg",
  "icons/mark.svg",
  "index.html",
  "js/app.js",
  "js/bundle.js",
  "js/i18n.js",
  "js/icons.js",
  "js/map.js",
  "js/msgpack.js",
  "js/search.js",
  "js/sun.js",
  "manifest.webmanifest",
  "map/fonts/Noto Sans Italic/0-255.pbf",
  "map/fonts/Noto Sans Italic/1536-1791.pbf",
  "map/fonts/Noto Sans Italic/256-511.pbf",
  "map/fonts/Noto Sans Italic/8192-8447.pbf",
  "map/fonts/Noto Sans Medium/0-255.pbf",
  "map/fonts/Noto Sans Medium/1536-1791.pbf",
  "map/fonts/Noto Sans Medium/256-511.pbf",
  "map/fonts/Noto Sans Medium/8192-8447.pbf",
  "map/fonts/Noto Sans Regular/0-255.pbf",
  "map/fonts/Noto Sans Regular/1536-1791.pbf",
  "map/fonts/Noto Sans Regular/256-511.pbf",
  "map/fonts/Noto Sans Regular/8192-8447.pbf",
  "map/sprites/dark.json",
  "map/sprites/dark.png",
  "map/sprites/dark@2x.json",
  "map/sprites/dark@2x.png",
  "map/sprites/light.json",
  "map/sprites/light.png",
  "map/sprites/light@2x.json",
  "map/sprites/light@2x.png",
  "vendor/maplibre-gl-shared.mjs",
  "vendor/maplibre-gl-worker.mjs",
  "vendor/maplibre-gl.css",
  "vendor/maplibre-gl.mjs",
  "vendor/pmtiles.js",
  "vendor/protomaps-basemaps.mjs",
]; // </assets>

const progress = { done: 0, total: ASSETS.length };

async function broadcast(msg) {
  for (const c of await self.clients.matchAll({ includeUncontrolled: true })) c.postMessage(msg);
}

self.addEventListener('install', (e) => {
  e.waitUntil((async () => {
    const cache = await caches.open(VERSION);
    progress.done = 0;
    let i = 0;
    // a few at a time: fast, but kind to slow phones
    const next = async () => {
      while (i < ASSETS.length) {
        const url = ASSETS[i++];
        const hit = await cache.match(url);
        if (!hit) {
          const res = await fetch(new Request(url, { cache: 'reload' }));
          if (!res.ok) throw new Error(`precache ${url}: HTTP ${res.status}`);
          await cache.put(url, res);
        }
        progress.done++;
        if (progress.done % 8 === 0 || progress.done === ASSETS.length) broadcast({ type: 'progress', ...progress });
      }
    };
    await Promise.all([next(), next(), next(), next()]);
    // first install: take over right away. Updates wait for the page's "Reload" (see app.js).
    if (!self.registration.active) await self.skipWaiting();
  })());
});

self.addEventListener('activate', (e) => {
  e.waitUntil((async () => {
    for (const k of await caches.keys()) if (k.startsWith(PREFIX) && k !== VERSION) await caches.delete(k);
    await self.clients.claim();
    progress.done = progress.total;
    broadcast({ type: 'progress', ...progress });
  })());
});

self.addEventListener('message', async (e) => {
  const m = e.data || {};
  if (m.type === 'skip') self.skipWaiting();
  if (m.type === 'status') {
    const cache = await caches.open(VERSION);
    const keys = new Set((await cache.keys()).map((r) => new URL(r.url).href));
    const done = ASSETS.filter((u) => keys.has(new URL(u, self.registration.scope).href)).length;
    e.source?.postMessage({ type: 'progress', done, total: ASSETS.length });
  }
});

const bodies = new Map(); // url -> ArrayBuffer, so range slicing doesn't re-read the cache every tile

async function rangeResponse(req, cached) {
  const url = req.url.split('#')[0];
  let buf = bodies.get(url);
  if (!buf) { buf = await cached.arrayBuffer(); bodies.set(url, buf); }
  const m = /bytes=(\d*)-(\d*)/.exec(req.headers.get('range') || '');
  const size = buf.byteLength;
  let start = 0, end = size - 1;
  if (m) {
    if (m[1] === '' && m[2] !== '') { start = Math.max(0, size - +m[2]); }
    else { start = +m[1]; if (m[2] !== '') end = Math.min(+m[2], size - 1); }
  }
  if (start >= size || start > end) return new Response(null, { status: 416, headers: { 'Content-Range': `bytes */${size}` } });
  return new Response(buf.slice(start, end + 1), {
    status: 206,
    headers: {
      'Content-Type': cached.headers.get('Content-Type') || 'application/octet-stream',
      'Content-Range': `bytes ${start}-${end}/${size}`,
      'Content-Length': String(end - start + 1),
      'Accept-Ranges': 'bytes',
    },
  });
}

self.addEventListener('fetch', (e) => {
  const req = e.request;
  const url = new URL(req.url);
  if (req.method !== 'GET' || url.origin !== self.location.origin) return;
  e.respondWith((async () => {
    const cache = await caches.open(VERSION);
    const key = url.pathname.endsWith('/') ? new Request(url.href.split('#')[0]) : req;
    const hit = await cache.match(key, { ignoreSearch: true });
    if (hit && req.headers.has('range')) return rangeResponse(req, hit);
    if (hit) return hit;
    try {
      const res = await fetch(req);
      // keep extra files the precache list missed (e.g. a glyph range added later), but never partial bodies
      if (res.ok && res.status === 200 && res.type === 'basic') cache.put(req, res.clone());
      return res;
    } catch (err) {
      if (req.mode === 'navigate') return (await cache.match('./')) || (await cache.match('index.html'));
      throw err;
    }
  })());
});
