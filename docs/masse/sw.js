/* I&N RUN Masse — service worker
   -----------------------------------------------------------------------
   Change la version à chaque modification d'index.html : c'est ce numéro qui
   déclenche l'installation d'un nouveau worker, donc le bandeau « Recharger ».
   ----------------------------------------------------------------------- */
var VERSION = 'v16';
var SHELL   = 'inrun-masse-shell-' + VERSION;   /* l'app elle-même */
var FONTS   = 'inrun-masse-fonts-' + VERSION;   /* Google Fonts */

/* Tout est relatif : l'app fonctionne à la racine comme dans un sous-dossier. */
var PRECACHE = [
  './',
  './index.html',
  './manifest.json',
  './icon.svg',
  './icon-maskable.svg',
  './icon-180.png',
  './icon-192.png',
  './icon-512.png',
  './icon-maskable-512.png',
  /* icônes, cartes musculaires et fonds : sans eux l'app hors ligne
     afficherait des emplacements vides */
  './img/ic-push.webp',
  './img/ic-pull.webp',
  './img/ic-legs.webp',
  './img/ic-upper.webp',
  './img/ic-core.webp',
  './img/ic-today.webp',
  './img/ic-semaine.webp',
  './img/ic-progres.webp',
  './img/ic-moi.webp',
  './img/ic-swim.webp',
  './img/ic-cardio.webp',
  './img/ic-rest.webp',
  './img/ic-fatigue.webp',
  './img/ic-normal.webp',
  './img/ic-forme.webp',
  './img/ic-coach.webp',
  './img/mus-push.webp',
  './img/mus-pull.webp',
  './img/mus-legs.webp',
  './img/mus-upper.webp',
  './img/mus-core.webp',
  './img/fond-texture.webp',
  './img/fond-salle.webp'
];

var FONT_HOSTS = ['fonts.googleapis.com', 'fonts.gstatic.com'];

/* ---------- installation ---------- */
self.addEventListener('install', function(e){
  e.waitUntil(
    caches.open(SHELL).then(function(c){
      /* addAll échoue en bloc si un seul fichier manque : on met en cache
         fichier par fichier pour qu'une icône absente ne casse pas l'offline. */
      return Promise.all(PRECACHE.map(function(u){
        return c.add(new Request(u, {cache:'reload'})).catch(function(){});
      }));
    })
  );
  /* pas de skipWaiting automatique : l'utilisateur décide via le bandeau,
     jamais en pleine séance. */
});

/* ---------- activation : ménage des anciennes versions ---------- */
self.addEventListener('activate', function(e){
  e.waitUntil(
    caches.keys().then(function(keys){
      return Promise.all(keys.map(function(k){
        if(k.indexOf('inrun-masse-') === 0 && k !== SHELL && k !== FONTS) return caches.delete(k);
      }));
    }).then(function(){
      if(self.registration.navigationPreload) return self.registration.navigationPreload.disable();
    }).then(function(){
      return self.clients.claim();
    })
  );
});

/* ---------- le bandeau « Recharger » demande la main ---------- */
self.addEventListener('message', function(e){
  if(e.data && e.data.type === 'SKIP_WAITING') self.skipWaiting();
});

/* ---------- stratégies ---------- */
function fromCache(req, cacheName){
  return caches.open(cacheName).then(function(c){
    return c.match(req, {ignoreSearch:false});
  });
}

/* réseau d'abord, cache en secours : la page reste fraîche en ligne
   et s'ouvre quand même à la salle sans réseau. */
function networkFirst(e, cacheName, fallback){
  return fetch(e.request).then(function(res){
    if(res && (res.ok || res.type === 'opaque')){
      var copy = res.clone();
      caches.open(cacheName).then(function(c){ c.put(e.request, copy); }).catch(function(){});
    }
    return res;
  }).catch(function(){
    return fromCache(e.request, cacheName).then(function(hit){
      if(hit) return hit;
      if(fallback) return caches.match(fallback);
      return Response.error();
    });
  });
}

/* cache d'abord, mise à jour en arrière-plan : instantané hors ligne. */
function staleWhileRevalidate(e, cacheName){
  return caches.open(cacheName).then(function(c){
    return c.match(e.request).then(function(hit){
      var net = fetch(e.request).then(function(res){
        if(res && (res.ok || res.type === 'opaque')) c.put(e.request, res.clone());
        return res;
      }).catch(function(){ return hit || Response.error(); });
      return hit || net;
    });
  });
}

self.addEventListener('fetch', function(e){
  var req = e.request;
  if(req.method !== 'GET') return;

  var url;
  try{ url = new URL(req.url); }catch(err){ return; }
  if(url.protocol !== 'http:' && url.protocol !== 'https:') return;

  /* navigation (ouverture de l'app, F5) */
  if(req.mode === 'navigate'){
    e.respondWith(networkFirst(e, SHELL, './index.html'));
    return;
  }

  /* polices Google : indispensables au rendu, jamais critiques */
  if(FONT_HOSTS.indexOf(url.hostname) >= 0){
    e.respondWith(staleWhileRevalidate(e, FONTS));
    return;
  }

  /* le reste du site (icônes, manifest…) */
  if(url.origin === self.location.origin){
    e.respondWith(staleWhileRevalidate(e, SHELL));
  }
  /* tout ce qui est externe (YouTube…) passe au réseau sans interception */
});
