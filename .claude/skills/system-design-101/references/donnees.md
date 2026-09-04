# Données et stockage

Le choix du stockage est la décision la plus difficile à défaire d'une
architecture : on change de langage, on change de cloud, on ne change presque
jamais de modèle de données sans douleur. D'où la règle : **les patrons
d'accès choisissent le moteur.**

## Sommaire

- [Choisir un moteur](#choisir-un-moteur)
- [Modéliser](#modéliser)
- [Index](#index)
- [Transactions et ACID](#transactions-et-acid)
- [Concurrence](#concurrence)
- [Réplication](#réplication)
- [Partitionnement](#partitionnement)
- [CAP, PACELC, BASE](#cap-pacelc-base)
- [Modèles de cohérence](#modèles-de-cohérence)
- [Transactionnel et analytique](#transactionnel-et-analytique)
- [Migrations sans coupure](#migrations-sans-coupure)

## Choisir un moteur

| Famille | Exemples | Bon pour | Mauvais pour |
|---|---|---|---|
| Relationnel | Postgres, MySQL | relations, transactions, requêtes ad hoc | volumes d'écriture extrêmes sur un nœud |
| Document | MongoDB, DynamoDB | agrégats lus en bloc, schéma mouvant | requêtes croisant plusieurs entités |
| Clé-valeur | Redis, DynamoDB | accès par clé, cache, sessions, compteurs | toute requête qui n'est pas par clé |
| Colonnes larges | Cassandra, ScyllaDB | écritures massives, séries par clé | agrégats globaux, jointures |
| Séries temporelles | TimescaleDB, InfluxDB | métriques, capteurs, fenêtres glissantes | données relationnelles |
| Graphe | Neo4j | parcours de relations profondes | agrégats de masse |
| Recherche | Elasticsearch | plein texte, facettes, scoring | source de vérité transactionnelle |
| Analytique | ClickHouse, BigQuery, Snowflake | agrégats sur milliards de lignes | écriture unitaire, lecture par ligne |

**Le conseil par défaut est Postgres**, et ce n'est pas de la paresse : il
fait du relationnel, du JSON indexé, du plein texte, du géospatial, des
séries temporelles avec extension, et il tient les chiffres de la grande
majorité des projets (voir `chiffres.md`). Commencer là et sortir un moteur
spécialisé quand un patron d'accès précis le réclame coûte beaucoup moins
cher que l'inverse.

## Modéliser

Écris les **patrons d'accès** avant le schéma : « lire un profil par
identifiant », « lister les commandes d'un client par date décroissante »,
« compter les évènements par heure ». Chacun se traduit en un index ou en une
structure ; s'il n'existe ni l'un ni l'autre, la requête sera lente et aucun
cache ne le rattrapera durablement.

**Normaliser** (chaque fait à un seul endroit) évite les incohérences et
allège l'écriture ; **dénormaliser** (dupliquer pour éviter les jointures)
accélère la lecture au prix d'une synchronisation à maintenir. Le bon réflexe
est de normaliser d'abord, puis de dénormaliser précisément là où un patron
de lecture chaud le justifie — pas partout par principe.

En base document ou colonnes larges, c'est inversé : on modélise directement
autour de la requête, quitte à stocker la même donnée dans plusieurs
collections. Ce n'est pas un défaut, c'est le contrat de ces moteurs.

## Index

Un index est une structure triée qui évite de parcourir toute la table. Il
accélère la lecture, ralentit l'écriture et occupe de l'espace — donc il
s'ajoute pour un besoin, pas par prudence.

- **B-tree** : le défaut. Sert l'égalité, les intervalles, le tri, et les
  préfixes (`LIKE 'abc%'`).
- **Hash** : égalité seulement, plus compact.
- **Index composite** : l'ordre des colonnes compte. Un index `(a, b)` sert
  les requêtes sur `a` et sur `(a, b)`, jamais sur `b` seul.
- **Index couvrant** : contient toutes les colonnes lues, la table n'est même
  pas touchée.
- **Index partiel** : sur un sous-ensemble (`WHERE actif = true`) — souvent
  bien plus petit et plus efficace.
- **GIN / inversé** : JSON, tableaux, plein texte.

**Le réflexe de diagnostic est `EXPLAIN ANALYZE`.** Une requête lente
s'explique presque toujours par un parcours séquentiel là où un index
manquait, ou par un index ignoré parce qu'une fonction est appliquée à la
colonne (`WHERE lower(email) = …` n'utilise pas l'index sur `email`).

## Transactions et ACID

- **Atomicité** : tout ou rien.
- **Cohérence** : les contraintes restent vraies avant et après.
- **Isolation** : les transactions concurrentes ne se marchent pas dessus.
- **Durabilité** : une fois confirmée, l'écriture survit à une panne.

Niveaux d'isolation et anomalies qu'ils laissent passer :

| Niveau | Lecture sale | Lecture non répétable | Lecture fantôme |
|---|---|---|---|
| Read uncommitted | possible | possible | possible |
| Read committed *(défaut Postgres)* | non | possible | possible |
| Repeatable read | non | non | possible* |
| Serializable | non | non | non |

\* Postgres en *repeatable read* utilise un instantané qui élimine aussi les
fantômes classiques, mais pas toutes les anomalies d'écriture — d'où
l'existence de *serializable*.

Monter le niveau d'isolation coûte en concurrence. Le bon usage : rester en
*read committed*, et passer en *serializable* uniquement sur les transactions
où une anomalie serait grave (mouvements d'argent, réservation de stock).

## Concurrence

Le problème canonique : deux clients lisent le même stock à 1, décident tous
deux qu'il en reste, écrivent 0 — et deux commandes passent.

- **Verrou pessimiste** (`SELECT … FOR UPDATE`) : on verrouille avant de
  lire. Sûr, mais sérialise et peut créer des interblocages.
- **Verrou optimiste** : on lit une version, on écrit avec
  `WHERE version = lue` ; zéro ligne modifiée signifie conflit, on relit et on
  recommence. Bon défaut quand les conflits sont rares.
- **Opération atomique** : `UPDATE stock SET n = n - 1 WHERE id = ? AND n > 0`
  règle le cas en une instruction, sans verrou explicite. Quand c'est
  possible, c'est la meilleure réponse.

Pour les interblocages : verrouiller toujours les ressources dans le même
ordre, et garder les transactions courtes.

## Réplication

- **Primaire / réplicas.** Une écriture, plusieurs lectures. En réplication
  **asynchrone**, un réplica peut servir une donnée périmée — d'où le
  classique « je poste et je ne vois pas mon message ». Remède : lire depuis
  le primaire pendant quelques secondes après une écriture (*read your own
  writes*). En **synchrone**, pas de retard mais la latence d'écriture
  augmente et la panne d'un réplica peut bloquer.
- **Multi-primaires.** Écriture partout, donc conflits à résoudre (dernier
  écrivain gagne, CRDT, résolution applicative). À n'envisager que pour du
  multi-région avec un vrai besoin.

Le basculement doit être pensé : détection, promotion, redirection des
clients, et surtout protection contre le *split-brain* (deux primaires qui se
croient seuls) — c'est le scénario qui perd des données.

## Partitionnement

Découper les données quand un nœud ne suffit plus.

- **Vertical** : séparer les tables par domaine. Simple, limité.
- **Horizontal (sharding)** : découper les lignes d'une même table.

Stratégies de clé de partition :

| Stratégie | Avantage | Défaut |
|---|---|---|
| Intervalle (par date, par plage d'id) | requêtes par plage efficaces | points chauds sur la partition courante |
| Hachage | répartition uniforme | requêtes par plage impossibles |
| Hachage cohérent | rééquilibrage limité à l'ajout/retrait | plus complexe |
| Par entité (client, tenant) | isolation naturelle | un gros client déséquilibre tout |

Ce que le sharding coûte, et qu'il faut annoncer : plus de jointures entre
partitions, plus de transactions globales simples, agrégats à recomposer côté
application, et un rééquilibrage à opérer un jour. **C'est pour cela qu'il
arrive en dernier**, après l'indexation, le cache, les réplicas de lecture et
la montée en gamme du matériel.

## CAP, PACELC, BASE

**CAP** : en cas de **partition réseau** (P — qui n'est pas une option mais
un fait de la vie), il faut choisir entre rester **disponible** (répondre au
risque de servir du périmé) et rester **cohérent** (refuser de répondre).
Hors partition, on n'a pas à choisir : le théorème ne dit rien du régime
normal, contrairement à l'usage qu'on en fait souvent.

**PACELC** complète justement ce vide : *if Partition then A or C, Else
Latency or Consistency*. En marche normale, la vraie tension est entre
latence et cohérence.

**BASE** décrit l'autre pôle qu'ACID : *Basically Available, Soft state,
Eventually consistent* — disponible, état transitoire, cohérent à terme.

Ce qu'il faut savoir en faire : pour **chaque type de donnée**, dire de quel
côté on se range. Un solde de compte est ACID ; un compteur de vues, un fil
d'actualité ou une liste de recommandations sont parfaitement heureux en
cohérence à terme. Un système sérieux mélange les deux, il n'en choisit pas
un globalement.

## Modèles de cohérence

Du plus fort au plus faible :

- **Linéarisable / forte** : toute lecture voit la dernière écriture
  confirmée. Coûteuse, nécessaire pour l'argent et les verrous.
- **Séquentielle** : tout le monde voit les opérations dans le même ordre,
  pas forcément immédiatement.
- **Causale** : ce qui dépend d'une écriture arrive après elle (une réponse
  ne s'affiche jamais avant son message).
- **Read your own writes** : l'auteur voit son écriture, les autres peut-être
  pas encore. Souvent le compromis suffisant en pratique.
- **À terme** : les réplicas convergent, sans garantie de délai.

## Transactionnel et analytique

**OLTP** : beaucoup de petites transactions, lecture par ligne, index B-tree,
stockage par ligne. **OLAP** : peu de requêtes, chacune balayant des millions
de lignes, stockage en colonnes, compression forte.

Ne fais pas tourner l'analytique sur la base de production : une requête
d'agrégat qui balaie une année verrouille des ressources et dégrade le
service. Sépare, via un réplica dédié pour commencer, puis un entrepôt
alimenté par un pipeline (ETL, ou capture de changements — *CDC*) quand le
volume le justifie.

## Migrations sans coupure

Une migration de schéma ne doit jamais imposer d'arrêt. Le motif
**expand / contract** :

1. **Étendre** : ajouter la nouvelle colonne, nullable, sans contrainte.
2. **Écrire double** : le code écrit dans l'ancienne et la nouvelle.
3. **Remplir** : remplir l'historique par lots, en surveillant la charge.
4. **Basculer la lecture** vers la nouvelle colonne, vérifier.
5. **Contracter** : arrêter d'écrire l'ancienne, puis la supprimer — dans un
   déploiement ultérieur, jamais le même.

Chaque étape est réversible seule ; c'est tout l'intérêt. Sur les grosses
tables, méfie-toi des opérations qui prennent un verrou exclusif : ajouter un
index se fait en `CONCURRENTLY`, ajouter une contrainte se fait `NOT VALID`
puis valider.
