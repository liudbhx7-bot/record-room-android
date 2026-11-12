const prefix = 'recordroom:' + self.registration.scope + ':';
const cache = prefix + '29267eed02f193c2';
const files = ["assets/catalog.json", "assets/disc-smoke-lilac-v1.png", "assets/forest-1.wav", "assets/forest-2.wav", "assets/forest-3.wav", "assets/forest-v2.png", "assets/icon-256.png", "assets/icon-512.png", "assets/needle.wav", "assets/night-1.wav", "assets/night-2.wav", "assets/night-3.wav", "assets/night-v2.png", "assets/sun-1.wav", "assets/sun-2.wav", "assets/sun-3.wav", "assets/sun-v2.png", "assets/tonearm-graphite-v1.png", "index.html", "manifest.webmanifest", "player.css", "player.js", "vendor/jsmediatags-3.9.7.min.js", "vendor/jsmediatags-license.txt"];
self.addEventListener('install', event => event.waitUntil((async () => {
  const store = await caches.open(cache);
  await store.addAll(files.map(file => new Request(new URL(file, self.registration.scope), {cache:'reload'})));
  await self.skipWaiting();
})()));
self.addEventListener('activate', event => event.waitUntil((async () => {
  for (const key of await caches.keys()) if (key.startsWith(prefix) && key !== cache) await caches.delete(key);
  await self.clients.claim();
})()));
self.addEventListener('fetch', event => {
  if (event.request.method !== 'GET' || !event.request.url.startsWith(self.registration.scope)) return;
  event.respondWith((async () => {
    const store = await caches.open(cache);
    const key = event.request.mode === 'navigate' ? new URL('index.html', self.registration.scope).href : event.request.url;
    const saved = await store.match(key);
    if (!saved) return fetch(event.request);
    const range = event.request.headers.get('range');
    if (!range) return saved;
    const bytes = await saved.blob();
    const m = /^bytes=(\d*)-(\d*)$/.exec(range);
    if (!m || !(m[1] || m[2])) return new Response(null, {status:416,headers:{'Content-Range':'bytes */'+bytes.size}});
    const start = m[1] ? Number(m[1]) : Math.max(0,bytes.size-Number(m[2]));
    const end = m[1] && m[2] ? Math.min(bytes.size-1,Number(m[2])) : bytes.size-1;
    if (start > end || start >= bytes.size) return new Response(null,{status:416,headers:{'Content-Range':'bytes */'+bytes.size}});
    return new Response(bytes.slice(start,end+1),{status:206,headers:{
      'Content-Type': saved.headers.get('Content-Type') || bytes.type,
      'Content-Length': String(end-start+1), 'Content-Range': 'bytes '+start+'-'+end+'/'+bytes.size, 'Accept-Ranges':'bytes'
    }});
  })());
});
