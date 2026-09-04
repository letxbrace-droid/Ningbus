---
name: system-design-101
description: "Méthode et fiches de référence pour concevoir, dimensionner, critiquer et expliquer un système — API, base de données, cache, file de messages, microservices, mise à l'échelle, disponibilité, latence, sécurité. Utilise cette skill dès que la conversation touche à l'architecture d'un service, au choix d'une base ou d'un protocole (REST / gRPC / GraphQL / WebSocket / webhook), à « ça tient combien d'utilisateurs », à un système qui rame ou qui tombe, à un entretien de type « system design », ou à un « comment marche X » sur une brique d'infra (CDN, load balancer, Kafka, Redis, OAuth). Utilise-la même quand l'utilisateur ne prononce jamais les mots « system design » — « on met quoi comme base », « comment on scale ça », « pourquoi c'est lent », « il faut découper en microservices ? » sont exactement ces questions-là."
---

# System Design 101

Concevoir un système, ce n'est pas empiler des briques à la mode. C'est
choisir, pour une contrainte chiffrée, la structure la plus simple qui la
tient — et savoir dire ce qu'on sacrifie en échange.

Cette skill sert deux usages qui demandent le même travail :
concevoir pour de vrai, et expliquer/réviser (entretien, revue d'archi,
« comment marche X »).

## Le principe qui commande tout le reste

**Chiffrer avant de choisir.** La plupart des mauvaises architectures ne
viennent pas d'une ignorance technique mais d'un ordre de grandeur jamais
posé. 500 requêtes/s tiennent sur une machine et un Postgres ; 500 000
requêtes/s ne tiennent pas, et c'est ce facteur 1000 qui décide du sharding,
pas une préférence.

Alors avant de proposer quoi que ce soit, pose l'ordre de grandeur — même
approximatif, même à voix haute : volume de données, requêtes par seconde,
ratio lecture/écriture, taille d'un objet, latence acceptée. Si l'utilisateur
ne les connaît pas, propose une hypothèse explicite (« je pars sur ~10 000
utilisateurs actifs par jour ») et continue. Une hypothèse écrite se corrige ;
une hypothèse implicite se paie en refonte.

Les chiffres pour le faire de tête sont dans `references/chiffres.md`.

## La méthode, en six temps

Le détail et un exemple déroulé de bout en bout sont dans
`references/methode.md`. En résumé :

1. **Cadrer.** Qui l'utilise, pour quoi, qu'est-ce qui est explicitement hors
   périmètre. Une seule phrase de fonctionnel, puis les contraintes non
   fonctionnelles : latence, disponibilité, cohérence, coût, durabilité.
2. **Chiffrer.** QPS pic (souvent 2 à 5× la moyenne), volume à 1 an, taille
   d'un enregistrement, bande passante. Écris les calculs, pas seulement les
   résultats.
3. **Interface.** Les 3 à 5 opérations qui portent le système, avec leurs
   entrées/sorties. C'est le contrat : il révèle la moitié des problèmes de
   modèle de données avant qu'on ait dessiné quoi que ce soit.
4. **Données.** Le modèle d'abord, le moteur ensuite. Les patrons d'accès
   décident du moteur — jamais l'inverse. Voir `references/donnees.md`.
5. **Schéma haut niveau.** Client → entrée (DNS, CDN, load balancer) →
   service → stockage, plus l'asynchrone (files, workers, batch). Le
   catalogue des briques et ce que chacune coûte : `references/briques.md`.
6. **Approfondir et casser.** Prends un composant et pousse : que se
   passe-t-il quand il tombe, quand le trafic ×10, quand deux écritures
   arrivent en même temps ? C'est là que se juge une conception.

Les six temps ne sont pas un rituel à réciter. Si la question est « pourquoi
mon endpoint met 3 s », saute directement au 6 : mesure, trouve le goulot,
explique. Le squelette sert quand le problème est ouvert.

## Comment restituer

Structure la réponse ainsi, en adaptant la longueur à la question :

```
Ce que je comprends du besoin  (1-3 lignes, + hypothèses assumées)
Les chiffres                   (les calculs, pas juste les résultats)
La conception                  (schéma texte ou diagramme mermaid)
Ce qui se passe quand ça casse (pannes, pics, concurrence)
Ce que ça coûte                (le compromis accepté, nommé)
```

Deux exigences de fond :

**Nomme le compromis.** Toute décision d'archi en est un. « On met un cache
Redis devant » n'est complet qu'avec « en échange on accepte des données
périmées jusqu'à N secondes, et un chemin de repli quand Redis tombe ». Une
proposition sans son prix est une proposition non instruite.

**Commence petit.** Propose d'abord la version la plus simple qui tient les
chiffres, puis montre la marche suivante et le seuil qui la déclenche
(« ce monolithe tient jusqu'à ~5 000 QPS ; au-delà, on sort la lecture »).
Une architecture livrable aujourd'hui plus un chemin de croissance vaut mieux
qu'un schéma final qui ne sera jamais construit.

Pour les diagrammes, mermaid rend nativement dans les artifacts et les
messages ; préfère `flowchart LR` pour une topologie et `sequenceDiagram`
pour un protocole.

## Les pièges qui reviennent

- **Microservices par défaut.** Le découpage coûte un réseau, des
  transactions distribuées et une chaîne d'observabilité. Il se justifie par
  des équipes qui se bloquent ou des profils de charge incompatibles, pas par
  la modernité. Un monolithe modulaire bien découpé est le bon départ dans la
  grande majorité des cas.
- **Le cache comme pansement.** Un cache posé sur une requête lente cache
  aussi le fait qu'il manque un index. Cherche la cause avant d'ajouter la
  couche.
- **Confondre disponibilité et cohérence.** Sous partition réseau, il faut
  choisir (CAP). Dis lequel et pourquoi : un solde bancaire et un compteur de
  vues n'ont pas le même arbitrage.
- **Oublier l'idempotence.** Tout ce qui peut être rejoué le sera — retry
  client, redélivraison de file, double-clic. Les opérations qui écrivent ont
  besoin d'une clé d'idempotence.
- **La moyenne au lieu du p99.** Une latence moyenne de 80 ms avec un p99 à
  4 s, c'est un utilisateur sur cent qui part. Raisonne en percentiles.
- **Ignorer l'exploitation.** Un système qu'on ne peut ni déployer sans
  coupure, ni observer, ni revenir en arrière n'est pas fini.

## Fiches de référence

Lis la fiche pertinente au moment où tu en as besoin, pas d'avance.

| Fiche | Quand l'ouvrir |
|---|---|
| `references/methode.md` | Problème ouvert à concevoir, ou préparation d'entretien. Contient un exemple complet déroulé. |
| `references/chiffres.md` | Dès qu'il faut dimensionner : latences de référence, calcul de QPS et de stockage, les « neufs » de disponibilité, Big-O. |
| `references/briques.md` | Choisir et placer un composant : load balancer, CDN, cache, file, workers, passerelle d'API, limiteur de débit. |
| `references/donnees.md` | Choix du moteur, modélisation, index, réplication, sharding, transactions, CAP/ACID/BASE. |
| `references/communication.md` | REST, GraphQL, gRPC, WebSocket, SSE, webhooks, versionnage d'API, formats. |
| `references/securite-ops.md` | Authentification, OAuth 2 / OIDC, JWT, sessions, HTTPS, secrets — et CI/CD, déploiements, observabilité. |

Si une question tombe entre deux fiches, réponds avec ce que tu sais et dis
ce qui reste à vérifier — mieux vaut une réponse honnêtement bornée qu'une
fiche étirée.
