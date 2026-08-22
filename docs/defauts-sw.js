/* Pierre tombale — la PWA « Suivi Atelier RATP Cap Saclay » a été retirée.
   ---------------------------------------------------------------------
   Supprimer les fichiers du dépôt ne désinstalle rien : les téléphones qui
   ont déjà installé l'app gardent son service worker enregistré sur tout
   /Ningbus/, avec une stratégie cache-first qui continuerait à servir
   l'ancienne app indéfiniment.

   Ce worker prend la place de l'ancien : il vide ses caches, se désinscrit
   et laisse la main au réseau. Une fois qu'il ne reste plus d'installation
   en circulation (quelques semaines), ce fichier peut être supprimé à son
   tour. */

self.addEventListener('install', function(){ self.skipWaiting(); });

self.addEventListener('activate', function(e){
  /* Le ménage des caches doit finir avant que l'activation soit considérée
     comme terminée : il porte uniquement sur les caches de cette app. */
  e.waitUntil(
    caches.keys().then(function(keys){
      return Promise.all(keys
        .filter(function(k){ return k.indexOf('ningbus-defauts-') === 0; })
        .map(function(k){ return caches.delete(k); }));
    }).then(function(){ return self.clients.claim(); })
  );

  /* La désinscription reste HORS de waitUntil : unregister() n'aboutit
     qu'une fois ce worker relâché, donc l'attendre ici bloque l'activation
     et le worker reste en place — exactement ce qu'on veut éviter. Les
     onglets ouverts sont rechargés pour repartir sans worker. */
  self.registration.unregister()
    .then(function(){ return self.clients.matchAll({ type: 'window' }); })
    .then(function(cs){ cs.forEach(function(c){ c.navigate(c.url).catch(function(){}); }); })
    .catch(function(){});
});

/* Aucune interception : tout passe au réseau. */
