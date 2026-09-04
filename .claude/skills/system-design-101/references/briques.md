# Les briques

Chaque composant résout un problème précis et en crée un autre. Cette fiche
donne, pour chacun : à quoi il sert, quand il devient nécessaire, ce qu'il
coûte. Le « quand » est le plus important — c'est ce qui évite les
architectures décoratives.

## Sommaire

- [DNS et anycast](#dns-et-anycast)
- [CDN](#cdn)
- [Load balancer](#load-balancer)
- [Passerelle d'API](#passerelle-dapi)
- [Limiteur de débit](#limiteur-de-débit)
- [Services sans état](#services-sans-état)
- [Cache](#cache)
- [Files de messages](#files-de-messages)
- [Workers et traitements planifiés](#workers-et-traitements-planifiés)
- [Recherche](#recherche)
- [Stockage d'objets](#stockage-dobjets)
- [Coupe-circuit et reprises](#coupe-circuit-et-reprises)
- [Observabilité](#observabilité)
- [Monolithe ou microservices](#monolithe-ou-microservices)

## DNS et anycast

Traduit un nom en adresse, et c'est aussi le premier étage d'équilibrage
(round-robin DNS, routage géographique). Attention : le TTL fait que tout
changement met des minutes à se propager — le DNS n'est pas un mécanisme de
basculement rapide. Pour du basculement en secondes, il faut une IP
flottante ou de l'anycast.

## CDN

Réplique le contenu près des utilisateurs. **Nécessaire quand** la bande
passante ou la latence géographique domine : fichiers statiques, images,
vidéo, et jusqu'aux réponses d'API cacheables. Le calcul de `chiffres.md`
(QPS × taille de réponse) suffit à le justifier.

Coût : l'invalidation. Tout ce qui est mis en cache au bord doit avoir une
stratégie de péremption — versionner l'URL des ressources (`app.3f9a1.js`)
est plus fiable que purger.

## Load balancer

Répartit le trafic et retire les instances malades. Deux niveaux :

- **L4** (TCP) : rapide, aveugle au contenu.
- **L7** (HTTP) : route par chemin, en-tête ou hôte ; permet TLS terminé,
  réécriture, déploiement progressif. C'est le choix par défaut pour du web.

Algorithmes : round-robin (simple, suppose des requêtes homogènes),
**moins de connexions actives** (meilleur quand les durées varient),
hachage par IP ou par clé (affinité, utile pour un cache local mais crée des
points chauds).

Le load balancer devient lui-même un point unique de défaillance : il en faut
deux, avec une IP flottante.

## Passerelle d'API

Point d'entrée unique qui mutualise ce que chaque service refera sinon :
authentification, limitation de débit, journalisation, routage, agrégation.
**Nécessaire quand** il y a plusieurs services derrière et plusieurs types de
clients devant. Sur un service unique, c'est du poids inutile.

Coût : une dépendance de plus sur le chemin critique, et la tentation d'y
mettre de la logique métier — à éviter absolument, elle devient vite le
composant que personne n'ose toucher.

## Limiteur de débit

Protège de l'abus et de l'effondrement en cascade. Algorithmes, du plus
simple au plus juste :

| Algorithme | Comportement |
|---|---|
| Compteur par fenêtre fixe | simple, mais laisse passer 2× la limite à la jointure de deux fenêtres |
| Fenêtre glissante | corrige ce défaut, coûte plus de mémoire |
| Seau à jetons (*token bucket*) | autorise des rafales bornées — le plus utilisé |
| Seau percé (*leaky bucket*) | lisse la sortie à débit constant |

Renvoie `429` avec un en-tête `Retry-After`, et documente la limite dans les
en-têtes de réponse : un client qui ne sait pas qu'il est limité retente en
boucle et aggrave la situation.

## Services sans état

Un service **sans état** ne garde rien en mémoire entre deux requêtes : on
peut donc en ajouter, en retirer, en redémarrer librement. C'est la condition
de la mise à l'échelle horizontale.

Ce qui casse cette propriété : les sessions en mémoire (les mettre dans Redis
ou dans un jeton signé), les fichiers écrits localement (stockage d'objets),
les tâches planifiées locales (un ordonnanceur avec élection de leader).

Mise à l'échelle **verticale** (machine plus grosse) : simple, immédiate,
plafonnée et sans redondance. **Horizontale** : sans plafond pratique,
tolérante aux pannes, mais impose l'absence d'état et une couche de
coordination. Commence vertical, passe horizontal quand le chiffre l'impose.

## Cache

Où cacher, du plus proche au plus loin : navigateur → CDN → cache local du
service (mémoire) → cache distribué (Redis, Memcached) → cache de la base.
Chaque étage divise la charge de l'étage suivant.

**Stratégies d'écriture :**

| Stratégie | Fonctionnement | À utiliser quand |
|---|---|---|
| *Cache-aside* | l'application lit le cache, va en base si absent, remplit | cas général, le plus courant |
| *Read-through* | le cache va chercher lui-même en base | quand le client doit rester simple |
| *Write-through* | écriture en cache et en base ensemble | cohérence forte souhaitée, écritures rares |
| *Write-behind* | écriture en cache, base plus tard en asynchrone | écritures très fréquentes, perte tolérable |

**Éviction :** LRU (défaut raisonnable), LFU (quand quelques clés dominent
durablement), FIFO, TTL. Un TTL est presque toujours une bonne idée en plus
de la politique d'éviction — il borne la durée d'une donnée périmée.

**Les trois pathologies à connaître :**

- *Cache stampede* : une clé chaude expire, mille requêtes partent en base
  simultanément. Remède : verrou sur le recalcul, ou recalcul anticipé.
- *Cache penetration* : des requêtes sur des clés inexistantes traversent
  systématiquement. Remède : cacher aussi l'absence, ou un filtre de Bloom.
- *Cache avalanche* : beaucoup de clés expirent en même temps. Remède :
  ajouter un aléa au TTL.

## Files de messages

Découplent producteur et consommateur : le producteur n'attend pas, la file
absorbe les pics, le consommateur traite à son rythme. **Nécessaire quand**
un travail est long, faillible ou non indispensable à la réponse (envoi
d'e-mail, encodage, indexation, statistiques).

Deux familles :

- **File de tâches** (RabbitMQ, SQS, Celery) : un message, un consommateur ;
  le message disparaît une fois traité. Pour du travail à distribuer.
- **Journal de messages** (Kafka, Kinesis, Pulsar) : le message est conservé,
  plusieurs consommateurs le lisent à leur position, on peut rejouer
  l'historique. Pour de l'évènementiel et de l'analytique.

Garanties de livraison : *at-most-once* (perte possible), *at-least-once*
(doublons possibles — c'est le défaut de fait), *exactly-once* (coûteux,
souvent illusoire de bout en bout). **Conclusion pratique : conçois les
consommateurs idempotents** et le débat s'éteint.

Prévois toujours une file de rebut (*dead letter queue*) : sans elle, un
message empoisonné bloque le traitement indéfiniment.

## Workers et traitements planifiés

Pour le batch et le périodique. Deux pièges : l'ordonnanceur qui s'exécute
sur plusieurs instances à la fois (verrou distribué ou élection de leader), et
le traitement qui prend plus de temps que son intervalle (mesurer, et sauter
plutôt qu'empiler).

## Recherche

Une base relationnelle fait très bien du `LIKE 'préfixe%'` avec un index. Pour
de la recherche plein texte pertinente (tolérance aux fautes, scoring,
facettes), il faut un index inversé : Elasticsearch, OpenSearch, ou les
extensions plein texte de Postgres.

Coût : une deuxième source de vérité à synchroniser, donc un pipeline
d'indexation et une désynchronisation possible. Ne double pas ton stockage
tant que l'index de la base suffit.

## Stockage d'objets

S3 et équivalents : pour les fichiers, images, vidéos, sauvegardes,
archives. Durabilité très élevée, coût faible, latence de l'ordre de la
dizaine de millisecondes.

Deux motifs à connaître : les **URL pré-signées** (le client téléverse
directement vers le stockage sans passer par ton service — cela retire tout
le trafic de fichiers de ton chemin critique) et le **CDN devant le
stockage** pour la lecture.

Ne mets jamais de fichiers binaires dans une base relationnelle : tu paies le
coût du transactionnel sur des données qui n'en ont pas besoin, et tu gonfles
les sauvegardes.

## Coupe-circuit et reprises

Quand un service dépendant tombe, les appels s'accumulent, les fils
d'exécution se remplissent, et la panne remonte. Les trois protections :

- **Délai d'attente** sur tout appel réseau. Un appel sans timeout est un
  incident en attente.
- **Reprise avec recul exponentiel et aléa** (*jitter*). Sans aléa, tous les
  clients retentent en même temps et créent une vague.
- **Coupe-circuit** : après N échecs, on arrête d'appeler pendant un temps et
  on renvoie une réponse dégradée. Cela laisse au service en panne la chance
  de se relever.

Complète par le **cloisonnement** (*bulkhead*) : des pools de connexions
séparés par dépendance, pour qu'une dépendance lente n'épuise pas les
ressources des autres.

## Observabilité

Trois piliers, complémentaires :

- **Métriques** : agrégats numériques (QPS, latences en percentiles, taux
  d'erreur, saturation). Peu coûteuses, faites pour l'alerte.
- **Journaux** : évènements détaillés, avec un identifiant de corrélation
  pour suivre une requête à travers les services.
- **Traces** : le parcours d'une requête à travers les services, avec le
  temps passé dans chacun. C'est ce qui répond à « où sont passées les
  800 ms ».

Alerte sur les **symptômes** vus par l'utilisateur (latence, taux d'erreur,
saturation), pas sur les causes possibles — une alerte par cause produit du
bruit et finit ignorée.

## Monolithe ou microservices

| | Monolithe modulaire | Microservices |
|---|---|---|
| Déploiement | une unité | indépendant par service |
| Transactions | locales, simples | distribuées, saga, compensation |
| Débogage | une pile d'appels | traces distribuées obligatoires |
| Montée en charge | globale | ciblée par service |
| Équipes | se coordonnent | avancent en parallèle |
| Coût d'exploitation | faible | élevé |

**Le découpage se justifie par l'organisation et par des profils de charge
incompatibles, pas par la technologie.** Deux équipes qui se bloquent sur les
mêmes fichiers, ou un composant qui a besoin de 50 machines quand le reste en
demande 2 : ce sont des raisons. « C'est plus moderne » n'en est pas une.

Le bon chemin est presque toujours : monolithe **modulaire** — frontières
internes nettes, pas d'accès croisé aux tables — puis extraction des modules
qui le méritent, quand ils le méritent. Les frontières internes rendent
l'extraction possible plus tard ; leur absence la rend impossible.
