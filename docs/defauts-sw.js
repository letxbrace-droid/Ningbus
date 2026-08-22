/* Service Worker — Suivi Atelier RATP Cap Saclay
   Stratégie : cache-first pour les assets statiques.
   Les données métier sont dans IndexedDB → l'app fonctionne 100 % hors-ligne. */

const CACHE = 'ningbus-defauts-v3';
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

/* Le ménage ne porte que sur les caches de cette app : letxbrace-droid.github.io
   héberge aussi la PWA I&N RUN Masse (/Ningbus/masse/) et le stockage des caches
   est commun à tout le domaine. Sans ce filtre, chaque activation de ce worker
   supprimait les caches de l'autre app et lui faisait perdre son mode hors ligne. */
const PREFIXE = 'ningbus-defauts-';

self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys().then(keys =>
      Promise.all(keys
        .filter(k => k.startsWith(PREFIXE) && k !== CACHE)
        .map(k => caches.delete(k)))
    ).then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', e => {
  if (e.request.method !== 'GET') return;
  /* caches.match() interroge tous les caches du domaine, y compris ceux de
     l'autre app : on ne lit que le nôtre. */
  e.respondWith(
    caches.open(CACHE)
      .then(c => c.match(e.request))
      .then(cached => cached || fetch(e.request))
  );
});
