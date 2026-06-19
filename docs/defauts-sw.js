/* Service Worker — Suivi Atelier RATP Cap Saclay
   Stratégie : cache-first pour les assets statiques.
   Les données métier sont dans IndexedDB → l'app fonctionne 100 % hors-ligne. */

const CACHE = 'ningbus-defauts-v2';
const ASSETS = [
  '/Ningbus/defauts.html',
  '/Ningbus/logo-ratpcap.png',
  '/Ningbus/defauts-manifest.json',
  '/Ningbus/icons/defauts-192.png',
  '/Ningbus/icons/defauts-512.png',
];

self.addEventListener('install', e => {
  e.waitUntil(
    caches.open(CACHE)
      .then(c => c.addAll(ASSETS))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys().then(keys =>
      Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k)))
    ).then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', e => {
  if (e.request.method !== 'GET') return;
  e.respondWith(
    caches.match(e.request).then(cached => cached || fetch(e.request))
  );
});
