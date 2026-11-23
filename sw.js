const scope = self.registration.scope;
const prefix = 'recordroom:' + scope + ':';
const shell = prefix + 'f8e0b8497707c9d4';
const media = prefix + 'media:38aeff5ff6842f21';
const files = ["assets/catalog.json", "assets/disc-smoke-lilac-v1.png", "assets/forest-v2.webp", "assets/icon-256.png", "assets/icon-512.png", "assets/night-v2.webp", "assets/sun-v2.webp", "assets/tonearm-graphite-v1.png", "index.html", "manifest.webmanifest", "player.css", "player.js", "vendor/jsmediatags-3.9.7.min.js", "vendor/jsmediatags-license.txt"];
const demos = new Map(Object.entries({"assets/forest-1.wav": "0c61bdc79b5a3af36d1ff30341927c01e8dfd6598fe721610506941fef4409ea", "assets/forest-2.wav": "72d48eef114f9a2124e534407d601fe1f36a039457421bbc4c67ee948d2773c5", "assets/forest-3.wav": "37a93dc81d2c74e95f74a68c581e4a7ed09092f661f6e74f0e700bd609a6fa01", "assets/night-1.wav": "6458c7aafd996087f15c0d219998b679921ce8986a9278280264c52465686216", "assets/night-2.wav": "a2bd3341fcbcb3acbc2d8d25d7223947064fc3e83fb1f3bd98d382885cbff5cb", "assets/night-3.wav": "c0a374e657fb6e2d3b51c5e9821cf7265f286952afe2ed1fa21ed1b66b18a213", "assets/sun-1.wav": "2a98d9827a9f6a29dad0c0e3a770d548c696c43980f75112cee584dd595b8713", "assets/sun-2.wav": "b77d3cca4bb316428b0385aa89dcdbebde564c400893747a8dc96e73637da571", "assets/sun-3.wav": "a5d74f9d81cbe592d733f74b1461c5ceaba4f5a974e99d8ffc79e114ea3f35ab"}).map(([file,hash]) => [new URL(file, scope).href,hash]));
const inFlight = new Map();

self.addEventListener('install', event => event.waitUntil((async () => {
  const store = await caches.open(shell);
  await store.addAll(files.map(file => new Request(new URL(file, scope), {cache:'reload'})));
  await self.skipWaiting();
})()));
self.addEventListener('activate', event => event.waitUntil((async () => {
  for (const key of await caches.keys()) {
    if (!key.startsWith(prefix) || key === shell || key === media) continue;
    // Older versions stored all recordings with the shell. Keep already
    // downloaded, identical media offline across the upgrade without refetching.
    const old = await caches.open(key), retained = await caches.open(media);
    for (const [url, expected] of demos) {
      const prior = await old.match(url);
      if (!prior || prior.status !== 200 || await retained.match(url)) continue;
      try {
        const digest = await crypto.subtle.digest('SHA-256',await prior.clone().arrayBuffer());
        const hex = Array.from(new Uint8Array(digest), b=>b.toString(16).padStart(2,'0')).join('');
        if(hex === expected)await retained.put(url,prior);
      } catch { /* Storage pressure must not block player upgrades. */ }
    }
    await caches.delete(key);
  }
  await self.clients.claim();
})()));

async function recording(url) {
  let store;
  try {
    store = await caches.open(media);
    const saved = await store.match(url);
    if (saved) return saved;
  } catch { /* A full or disabled cache must not block online playback. */ }
  if (!inFlight.has(url)) {
    const loading = (async () => {
      // Cache only complete recordings. Ranged media requests often contain
      // just a few bytes (especially the first probe on Safari).
      const response = await fetch(url);
      if (response.status === 200 && store) {
        try { await store.put(url, response.clone()); } catch { /* Playback still works. */ }
      }
      return response;
    })();
    inFlight.set(url, loading);
  }
  const pending = inFlight.get(url);
  try { return (await pending).clone(); }
  finally { if(inFlight.get(url) === pending) inFlight.delete(url); }
}

self.addEventListener('message', event => {
  const data = event.data;
  if(data?.type === 'cache-demo' && demos.has(data.url))event.waitUntil(recording(data.url).catch(()=>{}));
});

async function ranged(response, range) {
  if (!range || response.status !== 200) return response;
  const bytes = await response.blob();
  const m = /^bytes=(\d*)-(\d*)$/.exec(range);
  const invalid = () => new Response(null,{status:416,headers:{'Content-Range':'bytes */'+bytes.size}});
  if (!m || !(m[1] || m[2])) return invalid();
  const start = m[1] ? Number(m[1]) : Math.max(0,bytes.size-Number(m[2]));
  const end = m[1] && m[2] ? Math.min(bytes.size-1,Number(m[2])) : bytes.size-1;
  if (start > end || start >= bytes.size) return invalid();
  return new Response(bytes.slice(start,end+1),{status:206,headers:{
    'Content-Type': response.headers.get('Content-Type') || bytes.type,
    'Content-Length': String(end-start+1), 'Content-Range': 'bytes '+start+'-'+end+'/'+bytes.size, 'Accept-Ranges':'bytes'
  }});
}

self.addEventListener('fetch', event => {
  const req = event.request;
  if (req.method !== 'GET' || !req.url.startsWith(scope)) return;
  event.respondWith((async () => {
    if(demos.has(req.url)) return ranged(await recording(req.url), req.headers.get('range'));
    const store = await caches.open(shell);
    const key = req.mode === 'navigate' ? new URL('index.html',scope).href : req.url;
    const saved = await store.match(key);
    return saved ? ranged(saved, req.headers.get('range')) : fetch(req);
  })());
});
