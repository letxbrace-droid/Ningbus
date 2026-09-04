# Communication : API et protocoles

Le style d'API n'est pas un goût : chacun optimise une chose et en abandonne
une autre. Choisis d'après le client et le couplage souhaité.

## Sommaire

- [Choisir un style](#choisir-un-style)
- [REST](#rest)
- [GraphQL](#graphql)
- [gRPC](#grpc)
- [Temps réel : polling, SSE, WebSocket](#temps-réel--polling-sse-websocket)
- [Webhooks](#webhooks)
- [Synchrone ou asynchrone](#synchrone-ou-asynchrone)
- [Pagination](#pagination)
- [Idempotence](#idempotence)
- [Erreurs](#erreurs)
- [Versionnage](#versionnage)
- [HTTP : ce qui compte en conception](#http--ce-qui-compte-en-conception)

## Choisir un style

| Style | Fort pour | Faible pour |
|---|---|---|
| REST | API publique, cache HTTP, lisibilité, outillage universel | sur-récupération, allers-retours multiples |
| GraphQL | clients variés qui veulent des formes de données différentes | cache, complexité serveur, requêtes coûteuses |
| gRPC | service à service, faible latence, contrat typé, flux | navigateur (nécessite grpc-web), lisibilité |
| WebSocket | bidirectionnel temps réel | état à maintenir, montée en charge |
| Webhook | notifier un tiers d'un évènement | fiabilité, sécurité, à concevoir soi-même |

Règle simple qui couvre l'essentiel : **REST vers l'extérieur, gRPC entre
services internes, GraphQL quand plusieurs clients hétérogènes demandent des
projections différentes des mêmes données.**

## REST

Des ressources nommées par des URL, des verbes HTTP qui portent le sens :

```
GET    /utilisateurs/42/commandes?statut=payee&limit=20
POST   /commandes
PATCH  /commandes/7
DELETE /commandes/7
```

Propriétés à respecter, parce que tout l'écosystème (caches, proxys, clients)
les suppose :

- `GET`, `HEAD` : **sûrs** (aucun effet de bord) et donc cacheables.
- `PUT`, `DELETE` : **idempotents** — rejouer donne le même état final.
- `POST` : ni l'un ni l'autre. C'est celui qui a besoin d'une clé
  d'idempotence (voir plus bas).

Un `GET` qui modifie l'état est le bug le plus coûteux de cette liste : un
préchargeur de navigateur ou un robot d'indexation finira par le déclencher.

## GraphQL

Un point d'entrée unique, le client décrit la forme voulue :

```graphql
query { utilisateur(id: 42) { nom, commandes(dernieres: 5) { total, date } } }
```

Ce que ça résout : la sur-récupération et les allers-retours multiples de
REST quand les clients ont des besoins divergents (mobile frugal, web
complet).

Ce que ça coûte, et qu'il faut prévoir dès le départ :

- **Le problème N+1** : un résolveur naïf déclenche une requête par élément.
  La réponse standard est un *dataloader* qui regroupe les accès par lot.
- **Le cache HTTP disparaît** (tout est `POST` sur une URL unique) : il faut
  cacher au niveau des résolveurs, ou utiliser les requêtes persistées.
- **Le coût des requêtes est ouvert** : impose une profondeur maximale et un
  budget de complexité, sinon une seule requête peut mettre le service à
  genoux.

## gRPC

Contrat en Protocol Buffers, binaire, HTTP/2, génération de code dans tous
les langages, flux dans les deux sens.

```protobuf
service Commandes {
  rpc Obtenir(ObtenirReq) returns (Commande);
  rpc Suivre(SuivreReq) returns (stream Evenement);
}
```

Excellent entre services internes : compact, rapide, typé, et le contrat
partagé évite les désaccords de format. Peu adapté à une API publique
(débogage moins direct, pas de cache HTTP, support navigateur indirect).

Compatibilité protobuf : **on ajoute des champs avec un nouveau numéro, on ne
réutilise ni ne renumérote jamais un champ retiré** (marque-le `reserved`).
C'est la seule règle qui garantit que anciens et nouveaux binaires se
comprennent.

## Temps réel : polling, SSE, WebSocket

| Technique | Sens | Coût | Quand |
|---|---|---|---|
| Polling court | client → serveur, répété | requêtes à vide | mises à jour rares, simplicité |
| Long polling | idem, requête maintenue ouverte | connexions retenues | repli quand WebSocket est bloqué |
| SSE | serveur → client, unidirectionnel | connexion HTTP maintenue | flux d'évènements, notifications, reconnexion automatique intégrée |
| WebSocket | bidirectionnel | état serveur par connexion | chat, jeu, édition collaborative |

**SSE est sous-utilisé** : quand le flux ne va que du serveur vers le client
— notifications, progression d'une tâche, réponses en flux d'un modèle — il
donne l'essentiel du WebSocket sur du HTTP ordinaire, avec la reconnexion et
le `Last-Event-ID` fournis par le navigateur.

Passer les WebSocket à l'échelle demande de rendre les serveurs sans état
autant que possible et de diffuser les messages entre instances (Redis
pub/sub, ou un bus), puisque deux clients d'une même conversation ne sont pas
sur la même machine.

## Webhooks

Ton service appelle une URL fournie par le tiers quand un évènement survient.
C'est l'inverse du polling, et c'est ce qu'il faut fournir aux intégrateurs.
À concevoir avec :

- **Signature** du corps (HMAC avec un secret partagé) et **horodatage**,
  pour que le destinataire vérifie l'origine et refuse les rejeux.
- **Reprises avec recul exponentiel** et une fenêtre bornée, puis abandon
  avec possibilité de rejeu manuel.
- **Au moins une livraison** : le destinataire doit être idempotent, donc
  envoie un identifiant d'évènement stable.
- **Ordre non garanti** : mets un numéro de séquence ou un horodatage dans la
  charge utile.

Côté réception d'un webhook, réponds `200` immédiatement et traite en
asynchrone : sinon ton temps de traitement devient le temps d'attente de
l'émetteur, et il te coupera.

## Synchrone ou asynchrone

Passe en asynchrone dès qu'un traitement est **long**, **faillible** ou **non
nécessaire à la réponse**. Le motif habituel :

```
POST /exports  → 202 Accepted, { id, statut: "en_cours" }
GET  /exports/{id} → { statut: "termine", url }   (ou webhook, ou SSE)
```

Ce que l'asynchrone t'oblige à ajouter en échange : un état à suivre, une
notification de fin, une gestion des échecs visibles par l'utilisateur. Ne le
fais pas gratuitement.

## Pagination

- **Offset / limit** : simple, mais lent sur les grands décalages (la base
  doit compter les lignes sautées) et **incohérent** si des données sont
  insérées entre deux pages — un élément peut apparaître deux fois ou jamais.
- **Curseur** (clé du dernier élément, opaque) : stable et efficace quel que
  soit le rang. C'est le bon défaut pour toute liste qui bouge.

```
GET /evenements?limit=50&apres=eyJpZCI6MTIzfQ
→ { items: [...], curseur_suivant: "eyJpZCI6MTczfQ" }
```

Borne toujours `limit` côté serveur : un client qui demande 100 000 éléments
ne doit pas pouvoir l'obtenir.

## Idempotence

Toute écriture réseau sera rejouée un jour — reprise du client, redélivraison
d'une file, double-clic, timeout suivi d'un retry alors que le serveur avait
réussi. La parade standard :

```
POST /paiements
Idempotency-Key: 9f2c-…
```

Le serveur stocke la clé avec le résultat. Même clé rejouée → il **renvoie la
réponse initiale** sans réexécuter. La clé est fournie par le client, et
conservée assez longtemps (24 h est courant) pour couvrir toutes les reprises
plausibles.

## Erreurs

Utilise les codes HTTP pour leur sens, et un corps structuré pour le détail :

| Code | Sens |
|---|---|
| 400 | requête malformée |
| 401 | non authentifié |
| 403 | authentifié mais non autorisé |
| 404 | ressource inexistante |
| 409 | conflit (version périmée, doublon) |
| 422 | syntaxe correcte, sémantique invalide |
| 429 | limite de débit atteinte (+ `Retry-After`) |
| 500 | erreur du serveur |
| 503 | indisponible temporairement (+ `Retry-After`) |

Distingue bien `4xx` (le client doit changer sa requête — inutile de
retenter) de `5xx` (retenter a du sens). Un service qui renvoie `500` pour
une saisie invalide déclenche des reprises inutiles chez tous ses clients.

Le corps doit être exploitable par un programme : un code d'erreur stable,
un message lisible, et le champ fautif s'il y en a un.

## Versionnage

Distingue les changements **compatibles** (ajouter un champ optionnel, une
valeur d'énumération tolérée, un endpoint) des **cassants** (retirer ou
renommer un champ, changer un type, resserrer une validation).

Pour les cassants : version dans le chemin (`/v2/…`) — explicite et facile à
router — ou dans un en-tête si tu veux garder des URL stables. Annonce une
date de fin de vie, mesure l'usage de l'ancienne version, et ne coupe qu'après
l'avoir vue tomber à zéro.

Côté client, la règle qui évite la moitié des ruptures : **ignorer les champs
inconnus** plutôt que d'échouer dessus.

## HTTP : ce qui compte en conception

- **HTTP/1.1** : une requête à la fois par connexion, d'où le *head-of-line
  blocking* et l'habitude d'ouvrir 6 connexions par domaine.
- **HTTP/2** : multiplexage sur une connexion, en-têtes compressés, *server
  push* (largement abandonné). Reste sensible au blocage de tête de ligne au
  niveau TCP.
- **HTTP/3 (QUIC sur UDP)** : supprime ce blocage, établissement de connexion
  plus rapide, migration de connexion (changement de réseau sans coupure) —
  gain net sur mobile.

Cache HTTP : `Cache-Control` décide (`max-age`, `no-store`, `private`,
`stale-while-revalidate`), `ETag` et `If-None-Match` permettent le `304` qui
évite de retransmettre. Bien réglés, ils retirent une part énorme du trafic
avant même d'arriver au CDN.
