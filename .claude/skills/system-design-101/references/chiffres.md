# Les chiffres

À quoi ça sert : pouvoir dire « ça tient » ou « ça ne tient pas » sans
benchmark, en trente secondes, avec une marge d'un facteur 2 — ce qui suffit
presque toujours à trancher une décision d'architecture.

## Sommaire

- [Latences de référence](#latences-de-référence)
- [Calculer un QPS](#calculer-un-qps)
- [Calculer un volume de stockage](#calculer-un-volume-de-stockage)
- [Calculer une bande passante](#calculer-une-bande-passante)
- [Ce que tient une machine](#ce-que-tient-une-machine)
- [Les neufs de disponibilité](#les-neufs-de-disponibilité)
- [Percentiles](#percentiles)
- [Big-O en pratique](#big-o-en-pratique)
- [Unités](#unités)

## Latences de référence

Ordres de grandeur usuels sur du matériel serveur moderne. Ils bougent avec
le temps ; ce qui ne bouge pas, ce sont les **rapports entre eux**, et c'est
le rapport qui décide.

| Opération | Ordre de grandeur |
|---|---|
| Référence cache L1 | 1 ns |
| Référence cache L2 | 4 ns |
| Verrou / déverrouillage mutex | 20 ns |
| Accès mémoire principale | 100 ns |
| Compression 1 Ko | 2 µs |
| Envoi 1 Ko sur réseau 10 Gbps | 1 µs |
| Lecture 1 Mo séquentiel en mémoire | 50 µs |
| Aller-retour dans le même datacenter | 500 µs |
| Lecture 1 Mo séquentiel sur SSD NVMe | 100-200 µs |
| Lecture aléatoire sur SSD | 50-150 µs |
| Recherche sur disque rotatif | 5-10 ms |
| Aller-retour Californie ↔ Pays-Bas | ~150 ms |

Trois conclusions qui servent tout le temps :

1. **La mémoire est ~1000× plus rapide que le réseau local, le réseau local
   ~300× plus rapide que le transatlantique.** D'où : cache mémoire avant
   cache distribué, cache distribué avant appel externe, et réplication
   géographique pour les utilisateurs lointains.
2. **Le séquentiel écrase l'aléatoire**, même sur SSD. C'est pourquoi les
   moteurs modernes (LSM-tree, journaux d'écriture) transforment l'aléatoire
   en séquentiel.
3. **Un aller-retour réseau, c'est ~0,5 ms en interne.** Une requête qui en
   enchaîne 200 (le fameux N+1) coûte 100 ms de pur réseau, avant tout
   traitement.

## Calculer un QPS

```
QPS moyen = (utilisateurs actifs par jour × actions par utilisateur) / 86 400
QPS pic   ≈ QPS moyen × 2 à 5      (l'heure de pointe concentre le trafic)
```

`86 400` s/jour est le seul nombre à retenir. Astuce : 1 million d'actions
par jour ≈ **12 QPS**. 100 millions/jour ≈ 1200 QPS.

Exemple : 10 M d'utilisateurs actifs/jour, 20 actions chacun
→ 200 M actions/jour → 200/1 ≈ **2 300 QPS moyen**, ~9 000 QPS au pic.

Sépare toujours **lecture** et **écriture** : un ratio 100:1 (typique d'un
réseau social) et un ratio 1:1 (typique d'un système de télémétrie) ne
donnent pas la même architecture du tout. Un fort ratio de lecture appelle
cache et réplicas de lecture ; un fort ratio d'écriture appelle
partitionnement et écriture asynchrone.

## Calculer un volume de stockage

```
Volume/jour = écritures/jour × taille moyenne d'un enregistrement
Volume à N ans = Volume/jour × 365 × N × facteur de réplication
```

Le facteur de réplication (souvent 3) se paie en vrai — ne l'oublie pas.
Ajoute les index : sur une base relationnelle, ils pèsent couramment 20 à
50 % des données.

Tailles utiles : un UUID 16 octets, un horodatage 8, un entier 4 à 8, un
tweet ~300 octets, un enregistrement de log ~1 Ko, une photo compressée
~200 Ko à 2 Mo, une minute de vidéo 1080p ~10 à 50 Mo.

## Calculer une bande passante

```
Bande passante = QPS × taille de la réponse
```

Exemple : 5 000 QPS × 200 Ko (une image) = 1 Go/s = **8 Gbps**. C'est le
chiffre qui, en une ligne, justifie un CDN : le servir depuis l'origine
demanderait plusieurs liens 10 Gbps et coûterait cher en transit.

## Ce que tient une machine

Encore une fois, des ordres de grandeur, pas des garanties :

| Composant | Capacité usuelle sur une machine |
|---|---|
| Serveur applicatif (I/O, requêtes simples) | 1 000 - 10 000 QPS |
| Postgres / MySQL, lectures indexées | 5 000 - 20 000 QPS |
| Postgres / MySQL, écritures | 500 - 5 000 QPS |
| Redis | 50 000 - 200 000 ops/s |
| Nginx en proxy | 20 000 - 100 000 QPS |
| Broker Kafka (un nœud) | 100 000+ messages/s |

La leçon la plus utile de ce tableau : **une seule base relationnelle
correctement indexée absorbe l'écrasante majorité des projets**. Ne propose
un partitionnement qu'une fois le chiffre posé et le plafond dépassé.

## Les neufs de disponibilité

| Disponibilité | Indisponibilité par an | Par mois | Par semaine |
|---|---|---|---|
| 99 % | 3,65 jours | 7,3 h | 1,7 h |
| 99,9 % | 8,8 h | 44 min | 10 min |
| 99,99 % | 53 min | 4,4 min | 1 min |
| 99,999 % | 5,3 min | 26 s | 6 s |

Deux points souvent manqués :

- **Les composants en série multiplient leurs disponibilités.** Trois
  services à 99,9 % chaînés donnent 99,7 %. Chaque dépendance synchrone
  ajoutée dégrade le total.
- **Chaque neuf supplémentaire coûte environ un ordre de grandeur.** Passer
  de 99,9 % à 99,99 % impose du multi-zone, du basculement automatique et de
  l'astreinte. Demande toujours si le besoin réel le justifie.

## Percentiles

La moyenne ment. Utilise p50 (le cas courant), p95 et p99 (l'expérience de la
queue). Un p99 à 4 s sur un service appelé 10 fois par page, c'est ~10 % des
pages dégradées, pas 1 %.

Objectifs raisonnables pour une API interactive : p50 < 100 ms,
p99 < 1 s. Au-delà de 100 ms l'utilisateur perçoit la latence, au-delà de 1 s
il perd le fil de son action.

## Big-O en pratique

Ce qui compte en conception, c'est le comportement quand `n` grandit :

| Complexité | n = 1 000 | n = 1 000 000 | Verdict |
|---|---|---|---|
| O(1), O(log n) | instantané | instantané | toujours bon |
| O(n) | ok | ~1 M opérations, ok | acceptable si un seul passage |
| O(n log n) | ok | ~20 M | bon plafond pour un tri |
| O(n²) | 1 M | 10¹² | inutilisable au-delà de quelques milliers |

En pratique, les O(n²) qui font tomber les systèmes sont rarement des
algorithmes : ce sont des boucles qui appellent la base à chaque itération.

## Unités

Puissances de 10 (facturation, débits) contre puissances de 2 (mémoire,
tailles de blocs) : garde-les distinctes, l'écart atteint 10 % au téraoctet.

| | 10^n | 2^n |
|---|---|---|
| kilo | 10³ = 1 000 | Ki = 1 024 |
| méga | 10⁶ | Mi ≈ 1,049 M |
| giga | 10⁹ | Gi ≈ 1,074 G |
| téra | 10¹² | Ti ≈ 1,100 T |
