/* Team Shuffler service worker: offline support.
   Bump VERSION when shipping changes to the app shell list below. */
const VERSION = 'shuffler-v1';
const SHELL = [
  './',
  './index.html',
  './manifest.json',
  './icons/icon-192.png',
  './icons/icon-512.png',
  './icons/icon-maskable-512.png',
  './icons/apple-touch-icon.png'
];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(VERSION).then(c => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys()
      .then(keys => Promise.all(keys.filter(k => k !== VERSION).map(k => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

function putInCache(req, res){
  if(res && (res.ok || res.type === 'opaque')){
    const copy = res.clone();
    caches.open(VERSION).then(c => c.put(req, copy));
  }
  return res;
}

self.addEventListener('fetch', e => {
  const req = e.request;
  if(req.method !== 'GET') return;
  const url = new URL(req.url);

  // Page loads: network first so updates show up, cached copy when offline.
  if(req.mode === 'navigate'){
    e.respondWith(
      fetch(req).then(res => putInCache('./index.html', res))
        .catch(() => caches.match('./index.html'))
    );
    return;
  }

  // Same-origin assets: cache first, fill cache on miss.
  if(url.origin === self.location.origin){
    e.respondWith(
      caches.match(req).then(hit => hit || fetch(req).then(res => putInCache(req, res)))
    );
  }
});
