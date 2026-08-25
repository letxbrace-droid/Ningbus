# I&N RUN Masse — PWA

Application de suivi de prise de masse sur machines : cinq séances (poussée,
tirage, jambes, bras, ceinture abdominale), séries, RIR, double
progression, calendrier réel, récap hebdomadaire. Tout est local au téléphone
(localStorage), aucun compte, aucun serveur.

## Les fichiers vont ensemble

Tous au même niveau, dans le même dossier :

```
index.html               ← l'application
sw.js                    ← le service worker (offline)
manifest.json            ← l'installation PWA
icon.svg                 ← l'icône vectorielle
icon-maskable.svg        ← variante « maskable » (Android découpe l'icône)
icon-180.png             ← icône iOS (iOS ignore les SVG)
icon-192.png             ← icône Android
icon-512.png             ← icône haute définition / splash screen
icon-maskable-512.png    ← icône adaptative Android
_headers                 ← en-têtes de cache (Netlify et Cloudflare Pages)
img/                     ← icônes, cartes musculaires et fonds (147 Ko)
```

Le dossier `img/` contient 22 fichiers WebP :

- `ic-*.webp` — 16 icônes monochromes, utilisées comme **masques CSS**. Un seul
  fichier sert l'état actif (dégradé bleu-cyan), inactif (gris) et toutes les
  tailles : c'est `background` qui les colore, pas l'image.
- `mus-*.webp` — 5 cartes musculaires (face et dos) en tête de chaque séance.
  Deux niveaux de lecture : **cyan** pour les muscles moteurs, **bleu** pour
  ceux sollicités en second. Les planches ont été générées sur fond magenta —
  demander un fond « transparent » fait peindre un damier dans les pixels, et
  le détourage par saturation qui en découle laisse des bords crénelés.
- `fond-texture.webp` — matière sur le fond global, à 50 % d'opacité.
- `fond-salle.webp` — photo de salle dans l'en-tête d'Aujourd'hui, sous un
  dégradé qui garantit la lisibilité du texte.

`ic-coach.webp` est le casque du coach : il ne change jamais de forme, c'est la
couleur de son cadre qui porte l'état — orange par défaut, rouge sur un signal
de décharge, vert quand la séance est faite, acier un jour de repos. Le
personnage reste constant, l'information passe par la teinte, et la séance
conseillée reste nommée en toutes lettres dans le message.

Tout est précaché par le service worker : l'app garde ses icônes hors ligne.
Les emoji restent employés dans les textes du coach — les icônes servent la
structure, les emoji servent la prose.

Un service worker ne peut pas mettre en cache ce qui se trouve au-dessus de son
propre dossier. Ici tout est relatif (`./`), donc le dossier marche aussi bien à
la racine d'un site que dans un sous-dossier — mais il doit rester entier.

## Hébergement

**HTTPS obligatoire.** Un service worker refuse de s'enregistrer en HTTP simple.

### GitHub Pages — ce dépôt

Le dossier est publié dans `docs/masse/`. Pages étant servi depuis `/docs` sur
`main`, l'app est en ligne à :

```
https://letxbrace-droid.github.io/Ningbus/masse/
```

Pages ne permet pas de régler les en-têtes HTTP, donc `_headers` y est ignoré.
Ce n'est pas bloquant : l'app enregistre le worker avec `updateViaCache:'none'`,
ce qui donne le même résultat sur les navigateurs récents.

### Netlify / Cloudflare Pages

Glisser-déposer le dossier sur **app.netlify.com/drop**. Le fichier `_headers`
y est lu : c'est lui qui empêche le navigateur de garder l'ancien `sw.js`
pendant 24 h et de bloquer les mises à jour.

À éviter : InfinityFree, 000webhost et compagnie (publicités injectées, HTTPS
capricieux, suspensions arbitraires).

## Mettre à jour l'app

Après chaque modification d'`index.html`, incrémente la version dans `sw.js` :

```js
var VERSION = 'v16';   →   'v17'
```

Sans ça, le téléphone continue de servir l'ancienne version depuis son cache.
Quand la nouvelle version est détectée, un bandeau « Recharger » apparaît en bas
de l'écran — tu recharges quand tu veux, jamais en pleine séance. Le bandeau
demande au nouveau worker de prendre la main puis recharge une seule fois.

## Tester en local

```bash
python3 -m http.server 8000
```

Puis `http://localhost:8000`. Le service worker ne fonctionne **pas** en
`file://` — c'est normal, l'app marche quand même, simplement sans cache
offline.

## Structure de l'app

Quatre onglets, un rôle chacun :

| Onglet | Rôle | Contenu |
| --- | --- | --- |
| **Aujourd'hui** | faire | message du coach · fatigue du jour · sélecteur de séance · la séance |
| **Semaine** | piloter | bande hebdo · propose-moi la semaine · régularité · calendrier · analyse |
| **Progrès** | regarder | niveau et jauges · mensurations et IMC · suivi du poids · courbes de force · récap hebdo |
| **Moi** | régler | réglages · sauvegarde · la méthode (repliée) · effacer les perfs |

Les cinq séances ne sont pas des destinations de navigation : elles se
choisissent dans le sélecteur horizontal en haut d'« Aujourd'hui », qui reste
collé en haut de l'écran pendant la séance. Les puces portent l'état — point
orange sur la séance du jour, coche verte sur ce qui est déjà fait cette
semaine.

À l'ouverture, l'app affiche la séance déjà commencée aujourd'hui, sinon celle
qui est prévue, sinon celle que le coach conseille, sinon la dernière consultée.
Aucune ancre n'est écrite tant que l'utilisateur n'a rien touché : le navigateur
ferait défiler la page jusqu'à l'élément correspondant.

Les anciennes ancres restent valides — `#plan` mène à Semaine, `#profil` à
Progrès, `#push`/`#legs`… ouvrent Aujourd'hui sur la bonne séance. C'est ce qui
fait marcher les raccourcis d'icône du manifeste et les liens déjà partagés.

## Le coach raisonne par muscle

Chaque station déclare les muscles qu'elle sollicite dans son libellé
(« Pectoraux · triceps · épaules »). `carteMuscles()` lit ces libellés dans le
DOM : le programme reste la source de vérité, il n'y a pas de table à tenir à
jour en double.

`volumeParMuscle(7)` en tire les séries hebdomadaires par muscle — **entière
pour le muscle moteur, une demie pour les assistants**, la convention du volume
indirect. Les fourchettes de `MUSCLES_DET` ne sont pas uniformes : un deltoïde
latéral encaisse 12 à 25 séries là où un brachial sature vers 12.

## L'équilibre du programme

Le volume par muscle a été mesuré sur les 25 stations, à partir de la même
source que le coach : les libellés du DOM et les prescriptions par défaut.
Le premier relevé était déséquilibré.

Les gros muscles étaient servis par trois ou quatre exercices — les biceps
ramassaient 18 séries, moteurs sur deux curls et assistants sur tous les
tirages. Les petits n'en avaient qu'un : le **deltoïde latéral, à 3 séries
pour une cible de 12-25**, soit 25 % — alors que c'est lui qui donne la
largeur d'épaules, le meilleur rendement visuel qui existe.

Le rééquilibrage ne rajoute pas de volume, il le **redistribue** :

| Station | Avant | Après |
| --- | --- | --- |
| Élévations latérales machine | 3 | 5 |
| Rear delt (oiseau machine) | 3 | 5 |
| Leg curl | 3 | 5 |
| Mollets machine | 4 | 7 |
| Rotation buste · Extension lombaire | 3 | 4 |
| Curl poulie basse *(doublon)* | 3 séries biceps | **Élévations latérales poulie**, 4 séries |
| Curl poulie basse (corde) | 3 | **Curl marteau**, 4 — brachial en moteur |
| Tirage horizontal (2ᵉ passage) | « Dos · biceps » | « Dos moyen · **trapèzes** · biceps » |

Deux corrections méritent d'être expliquées. Le **curl marteau** en prise
neutre remplace un troisième curl en supination : le brachial passe de
simple assistant à muscle moteur. Et le tirage horizontal du 2ᵉ passage ne
déclarait pas les trapèzes alors que le rowing identique de la séance
Tirage les déclare — le libellé était faux, pas le geste.

Résultat mesuré, à une séance par groupe et par semaine :

| Muscle | Avant | Après | Cible |
| --- | --- | --- | --- |
| Deltoïde latéral | 3 | **9** | 12-25 |
| Mollets | 4 | **7** | 12-22 |
| Trapèzes | 3,5 | **6,5** | 8-18 |
| Ischio-jambiers | 4,5 | **7** | 10-16 |
| Deltoïde postérieur | 3 | **5** | 8-18 |
| Biceps | 18 | **14** | 10-20 |

Aucun muscle ne descend plus sous 50 % de sa cible, et aucun ne dépasse son
plafond. Le reste de l'écart se comble par la **fréquence** : à deux séances
par groupe — ce que le planificateur encourage déjà — 14 muscles sur 16
atteignent leur fourchette.

La **coiffe des rotateurs** est le seul muscle du programme entraîné pour ne
pas se blesser plutôt que pour grossir : deux séries de rotations externes
dans l'échauffement de la poussée, sans compter dans le volume.

`test-equilibre.js` verrouille tout ça — un exercice renommé ou une série
retirée fait échouer la suite.

## La série stimulante

Toutes les séries ne se valent pas. L'hypertrophie augmente à mesure qu'une
série se termine près de l'échec et s'effondre au-delà d'environ cinq
répétitions en réserve : compter une série menée à RIR 6 comme une série menée
à RIR 1 était une erreur de mesure.

`poidsRir()` pondère donc chaque série — **RIR ≤ 2 → 1 · RIR 3-4 → 0,7 ·
RIR ≥ 5 → 0,3** — et `volumeParMuscle()` additionne des *séries stimulantes*.

Le volume **brut** (`weeklyVolume()`) ne bouge pas : c'est le nombre de séries
réellement faites, et c'est lui qu'affiche le compteur « 7 j : N séries ». Les
deux chiffres sont différents exprès — l'un mesure ce que tu as fait, l'autre ce
que ça a stimulé.

Une série sans RIR ne vaut ni zéro ni un : elle prend la valeur moyenne des
tiennes. Sans aucun repère, elle compte plein — on ne punit pas l'absence de
donnée, ici comme dans les jauges de niveau.

## La charge de référence

La double progression compare des séries faites **à la même charge** : on grimpe
en répétitions à charge fixe, puis on ajoute du poids et on redescend en bas de
fourchette. Le verdict s'ancrait pourtant sur la charge la **plus lourde** de la
séance, `Math.max` des séries. Tant que la charge ne bougeait pas, ça revenait au
même ; dès qu'elle bougeait, le coach conseillait une charge jamais travaillée.

Signalé depuis la salle sur deux exercices réels :

| Séance | Ce que disait le coach | Ce qu'il aurait dû dire |
|---|---|---|
| `36×10` `36×12` `41×12` | « Reste à **41 kg** » | la séance s'est faite à 36 |
| `18×6` `14×12` `14×11` | « Reste à **18 kg** » | 18 avait justement été abandonné |

`chargeRef()` remplace ce maximum : la charge de référence est celle qui porte le
plus de séries — à égalité la plus récente, parce que c'est la dernière décision
prise. Tout le verdict se juge sur elle et sur les **seules séries faites à cette
charge** : une série d'essai plus lourde ou un repli plus léger ne contaminent
plus le jugement. Quand la référence ne porte pas la séance entière, le coach le
dit (`jugé sur tes 3 séries à 40 kg`).

Et quand aucune charge n'a porté la prescription complète, il n'y a rien à
juger : il y a une charge à fixer. Le coach nomme alors le vrai problème —
`36 → 41 kg` — et distingue les deux cas, parce qu'ils ne se valent pas :

- **repli** (`18 → 14`) : bon réflexe, la première charge était trop lourde ;
  la prochaine fois, partir directement à 14 ;
- **montée** (`36 → 41`) : les séries ne sont plus comparables ; refaire les
  trois séries à 36, et 41 devient la marche d'après.

### Corollaire : plus de montée de charge en cours de séance

Le conseil affiché *pendant* la séance était la cause première. Au haut de
fourchette dès la première série, il disait « Série 2 : monte à 41 kg » — puis le
verdict de fin de séance reprochait ce changement. Il annonce désormais la montée
pour la **séance suivante** et fait terminer les séries à la charge en cours.

Seule exception conservée : une charge franchement trop lourde (plus de deux
répétitions sous le bas de fourchette), où continuer ne produirait plus rien
d'exploitable. Deux répétitions de trop peu ne justifient plus de toucher à la
charge.

Le rappel « dernière fois » suivait le même biais : il affichait `3 × 41 kg` pour
une séance faite à 36. Il montre maintenant la référence et l'amplitude réelle,
`3 × 36 (36-41) kg`.

`test-charge.js` verrouille les 26 vérifications correspondantes, dont les deux
séances réelles ci-dessus.

## La calibration du RIR

Le RIR déclaré est une estimation, pas une mesure : l'erreur type dépasse deux
répétitions même chez les pratiquants expérimentés, et tout le monde se juge
mieux près de l'échec que loin.

Un signal objectif existe pourtant dans les données déjà stockées : **à charge
constante, la chute de reps d'une série à l'autre dit à quel point la première
était proche de l'échec.** `calibration()` ne retient que les séances d'au moins
trois séries, à charge strictement identique, avec le RIR renseigné partout —
sinon la chute viendrait de la charge et non de la fatigue.

| Observation | Verdict | Correction |
| --- | --- | --- |
| 3 reps perdues ou plus en annonçant ≥ 2,5 de réserve | optimiste | −1 rep en réserve |
| aucune rep perdue en annonçant ≤ 1 | pessimiste | +1 rep en réserve |
| le reste | calibré | aucune |

Il faut **au moins 4 séances comparables** pour qu'un verdict soit rendu : en
dessous, le biais vaut zéro et rien ne change.

Le biais n'est pas décoratif — `rirCorrige()` s'applique partout où le RIR
décide : la pondération des séries stimulantes, et `avgRIR()` donc le verdict de
double progression. Un pratiquant optimiste au plafond de reps ne se verra plus
proposer d'ajouter une série : la marge que le chiffre déclarait n'existe pas.

Le coach l'annonce en clair dans l'analyse de la semaine, chiffres à l'appui.

Le coach s'en sert pour nommer le muscle en dette plutôt que la séance, et
l'analyse hebdomadaire affiche le détail avec la cible de chacun.

La ligne de signature, elle, est devenue la **prescription du jour** : elle
change avec la forme déclarée — reps en réserve, temps de repos, autorisation
ou non de monter la charge — et bascule sur le sommeil un jour creux. En
stagnation avérée, elle prescrit une semaine de décharge à 60 %.

## Les niveaux

Un compteur d'expérience qui ne fait que monter serait un mensonge : quelqu'un
qui arrête six mois resterait « athlète ». Ici le niveau est un **état**, relu
sur une fenêtre glissante de 28 jours à partir de quatre jauges :

| Jauge | Ce qu'elle mesure | Disponible à partir de |
| --- | --- | --- |
| **Assiduité** | séances de muscu sur 28 jours, cible 14 | toujours |
| **Équilibre** | part des 16 muscles dans leur fourchette hebdo | 10 séries sur 4 semaines |
| **Force** | 1RM au-dessus du record précédent, par exercice | 3 exercices suivis depuis plus de 4 semaines |
| **Rigueur** | RIR renseigné (60 %) et forme du jour déclarée (40 %) | 10 séries sur 4 semaines |

Une jauge sans données **sort du calcul** au lieu de compter zéro : un débutant
n'a pas à être puni de ne pas encore avoir d'historique. Le score global est la
moyenne des jauges disponibles.

Monter demande **deux verrous** simultanés — un score et un nombre de séances au
compteur. L'un sans l'autre ne suffit pas : quatre séances parfaites ne font pas
un pratiquant avancé.

| Niveau | Séances | Score |
| --- | --- | --- |
| 1 · Premier contact | 0 | 0 |
| 2 · Assidu | 8 | 40 |
| 3 · Régulier | 25 | 55 |
| 4 · Structuré | 60 | 65 |
| 5 · Avancé | 120 | 75 |
| 6 · Athlète | 200 | 85 |

La promotion est immédiate. La rétrogradation ne l'est jamais : le coach met
d'abord en **sursis** pendant 14 jours, en nommant le verrou qui a cédé, puis
descend d'**un seul** palier. Le meilleur palier atteint reste acquis.

La jauge d'équilibre se juge sur les fourchettes **brutes** de `MUSCLES_DET`,
jamais sur celles du niveau courant : si la barre montait avec le niveau,
franchir un palier ferait aussitôt chuter la jauge et l'app oscillerait entre
deux niveaux.

Le niveau n'est pas décoratif, il change trois choses :

- **les fourchettes de volume** visées par le coach (`cibleMuscle()`), de 0,8×
  au premier palier à 1,3× au dernier — un débutant n'a pas besoin de 12 séries
  d'élévations latérales, un avancé ne progresse plus avec 10 ;
- **la prescription du jour** (`reglagePrescription()`) : 3 reps en réserve et
  l'amplitude avant la charge au niveau 1, double progression stricte au
  niveau 3, séries menées à 0-1 rep en réserve à partir du niveau 5 ;
- **les tolérances du planificateur** : à partir du niveau 5, un jour
  d'enchaînement de plus, 26 séries par groupe au lieu de 22, une 3ᵉ séance du
  même groupe dans la semaine. Elles ne se resserrent jamais en dessous.

## Phase A : l'hygiène visuelle

Cinq règles, cinq corrections. Elles sont verrouillées par `test-design.js`
(18 vérifications) parce que ce sont des règles, pas des goûts.

### Les emoji n'étaient pas des icônes

`🏅 TON NIVEAU`, `📅 CETTE SEMAINE`, `📉 SUIVI DU POIDS` : des glyphes système,
rendus différemment sur chaque appareil, multicolores, hors palette — et posés
juste à côté des icônes maison de la barre du bas. C'était le défaut le plus
visible de l'app.

Un sprite SVG de 36 symboles les remplace, en-têtes, réglages, encadrés de
conseil, lignes du récap et lignes du coach compris. Un seul tracé, une seule
graisse, et `stroke:currentColor` : **l'icône ne peut plus sortir de la
palette**, elle prend la couleur de son contexte. Une icône dans une ligne
`warn` est ambre, dans une ligne `good` elle est verte, sans une ligne de code
de plus.

Effet de bord utile : l'état du coach transitait par l'emoji lui-même
(`etat==='🛑'`). Il passe désormais par un **nom** (`'alerte'`, `'ok'`,
`'rest'`) — le découplage qu'il aurait fallu faire dès le début.

### La bordure n'est pas une séparation

Chaque carte, chaque tuile, chaque bouton portait un trait de 1 px à
`--n-500`. Le résultat n'était pas une hiérarchie, c'était une grille de
boîtes. Or les marches de surface (ΔL ≈ 0,045) existaient déjà pour séparer :
la bordure faisait double emploi.

`--bord` descend donc à `--n-700` — un liseré qui pose l'arête (ratio 1,14
contre la carte), plus un contour. Avec une exception **mesurée** : un champ de
saisie vide n'a que son cadre pour dire qu'on peut y écrire. WCAG 1.4.11 demande
3:1 contre les couleurs adjacentes, des deux côtés. `--n-500` n'en donnait que
1,55 et `--n-400` que 2,35 ; d'où `--n-450 #62728a`, qui tient **3,43 contre la
carte et 3,00 contre le fond du champ**.

### Trois comptes ne sont pas trois natures

Ce mois en cyan, la série en orange, le total en vert : trois couleurs pour
trois chiffres qui sont tous des comptes. La couleur promettait une différence
de sens qui n'existait pas.

Les valeurs deviennent neutres et tabulaires, la taille porte la hiérarchie.
Une seule garde l'accent — la **série en cours**, la seule des trois sur
laquelle on puisse encore agir aujourd'hui. Même grammaire pour les unités :
`90` en 26 px, `kg` en 12 px sur `--text-3`, au lieu de deux tailles voisines
qui se disputaient le regard.

### Une jauge porte son état

Assiduité 100, Équilibre 43, Rigueur 60 s'affichaient dans le même cyan :
impossible de voir laquelle allait mal. `etatJauge()` applique le seuil que le
coach utilise déjà pour désigner le levier le plus court — **sous 40 c'est un
problème (rouge), sous 70 c'est en chemin (ambre), au-delà c'est acquis
(vert)** — à la barre et à sa valeur.

### Un stockage abîmé ne vide plus un onglet

Trouvé par accident en préparant les captures : en écrivant l'historique de
poids dans le mauvais format, l'onglet Aujourd'hui devenait **entièrement
blanc**. Les chargeurs se protégeaient d'un JSON illisible, pas d'un JSON
valide du mauvais **type** — un objet là où le code attend un tableau, et le
premier `.map` fait tomber tout le rendu.

Ce n'est pas théorique : `importData()` écrit le contenu d'une sauvegarde sans
vérifier sa forme, donc un fichier tronqué suffisait. `litObjet()` et
`litTableau()` vérifient le type ; les neuf chargeurs passent par elles.

## Phase B : la lecture d'un coup d'œil

### La semaine en sept pastilles

`renderGlance()` pose en haut d'**Aujourd'hui** une bande de sept pastilles qui
ne répond qu'à une question : où j'en suis cette semaine. Aucun nom, aucun
bouton — la planification garde son onglet. Ce qui est **fait** porte l'aplat
catégoriel de sa séance et une coche ; ce qui est **prévu** porte la même teinte
en creux, parce qu'une intention n'est pas un fait ; **aujourd'hui** porte un
anneau, jamais une couleur de plus. Toucher la bande mène à Semaine.

### La bande détaillée débordait

Sur 390 px, le septième jour sortait de l'écran : `.ws` était en `flex:1` sans
`min-width:0`, donc le contenu en `nowrap` imposait sa largeur. Corrigé.

Restait à faire tenir les noms. Les rapetisser était le mauvais réflexe — **plus
un texte est petit, plus APCA exige de contraste** : à 9,5 px, « Poussée »
tombait à Lc 75 pour un seuil de 98. Les noms sont donc **abrégés** (`SESS[].c`,
six caractères au plus) et rendus à 11 px en `--text`. Même raisonnement pour
l'ancienne flèche `→` des jours proposés : elle mangeait la place du mot. Prévu
et proposé se lisent maintenant à leur cadre en pointillés et à leur icône en
retrait — le contraste d'un mot ne se négocie pas contre une nuance de sens.

Un test mesure le pire cas : sept jours portant le nom le plus long, aucun
débordement, aucun `text-overflow` déclenché.

### L'explication passe après la donnée

Trois lignes de prose ouvraient « Cette semaine » et « Ton niveau » : utiles la
première fois, du bruit la quarantième. Elles se replient derrière un « ? » qui
porte `aria-expanded`, **sauf au tout premier lancement** — quand il n'y a aucun
historique, l'explication est ce qu'il y a de plus utile à l'écran.

`initAides()` doit tourner une fois l'app en place : appelée trop tôt, elle lit
un stockage encore inaccessible, croit l'app neuve et ouvre tout.

### Les tuiles de statistique

Icône et intitulé d'abord, en petit et en retrait, puis la valeur seule en gros
et tabulaire — l'inverse de l'ancien, où la valeur colorée et l'intitulé se
disputaient le regard. Les deux copies du bloc sont réunies dans `statsRecap()`.

### Ce que la phase A avait assombri de trop

En passant les intitulés en capitales de 12 px/`--text-2` à 11,5 px/`--text-3`,
la phase A les avait fait tomber sous leur seuil (Lc 75 pour 88 exigé). Retour
en arrière sur les cinq concernés. Le bilan de l'audit s'améliore nettement :

| | avant | après |
|---|---|---|
| textes sous seuil | 6 | **4** |
| APCA moyen | 85 | **89,8** |
| perdus en plein soleil | 3 | **0** |

Les budgets de `test-contraste.js` sont resserrés d'autant.

## Phase C : l'anneau et le mouvement

### Un élément au lieu de deux

Le badge du niveau était un carré qui ne disait que le palier, et une barre
linéaire répétait le score global quinze lignes plus bas : deux éléments pour une
seule information. L'**anneau** les réunit — l'arc porte le score, le centre
porte le palier. La barre a disparu.

L'arc suit les seuils de `etatJauge()`, les mêmes que les jauges, et la valeur
« Score global » sous la carte porte la **même couleur d'état** : c'est ce qui
relie l'arc à son chiffre sans avoir à l'écrire.

Le mot « niveau » a quitté le centre de l'anneau : mesuré, le bloc de texte
débordait du cercle utile de 3,7 px. L'élargir aurait mangé la colonne de texte,
et le mot répétait déjà l'en-tête de section (« Ton niveau ») et le nom du palier
juste à droite.

### Le mouvement dit quelque chose, ou il ne sert à rien

L'anneau et les jauges sont rendus **vides** puis remplis à la frame suivante :
écrire directement la valeur finale dans le HTML fait naître l'élément déjà
rempli et la transition ne se déclenche jamais. Le mouvement dit « voilà où tu en
es », il ne décore pas.

### Qui refuse le mouvement est écouté partout

L'ancienne règle `prefers-reduced-motion` ne coupait qu'une animation nommée et
les transitions. Les feuilles qui montent, les voiles qui apparaissent et les
**confettis** continuaient de bouger — ces derniers sont du canvas, aucune règle
CSS ne peut les arrêter. `mouvementRefuse()` les court-circuite, et la règle CSS
couvre désormais toutes les animations et transitions.

En mode calme, l'anneau et les jauges affichent quand même leur valeur : on
supprime le parcours, jamais l'information. Six vérifications le contrôlent dans
un contexte navigateur réellement configuré en `reducedMotion: reduce`.

## La palette FIT GREEN

### Les codes imprimés sur l'image de référence sont faux

Première chose faite : échantillonner les pastilles au pixel. Quatre étiquettes
sur cinq annonçaient une palette de beiges et de bruns pour des pastilles citron
vert et noires — le gabarit portait les codes d'une autre palette.

| Bande | Étiquette imprimée | Pastille réelle | Écart |
|---|---|---|---|
| WHITE | `#FEF9F5` | `#ffffff` | 12 |
| FIT GREEN | `#F1DFCF` beige | **`#affa01`** | 218 |
| FIT GREEN 40% | `#7B7457` kaki | **`#cfed89`** | 156 |
| BLACK 88% | `#CF8E54` ocre | **`#1c1c1c`** | 219 |
| BLACK | `#854627` brun | **`#000000`** | 155 |

C'est sur les valeurs mesurées que tout est construit.

### Ce que la palette impose

Monochrome plus **un** accent. Les deux ancres — `#000000` pour le fond,
`#1c1c1c` pour la carte — sont posées telles quelles ; les marches au-dessus
gardent l'écart de clarté ΔL ≈ 0,048 qui rend une carte lisible sans bordure.
Les gris bleutés d'avant deviennent des gris neutres.

Les encres sont résolues pour atteindre 95 / 88 / 75 APCA sur la carte, avec une
pointe de chaleur : du blanc pur sur du noir crée du halo, et l'étiquette
« white » de l'image allait déjà dans ce sens — c'est la seule exploitable.

### Le piège du citron : il est CLAIR

`#affa01` est à L 0,90. C'est l'inverse exact de l'orange qu'il remplace. Il
écrit très bien sur le noir (APCA 89) et **porte une encre noire** quand il sert
d'aplat (APCA 90). Il n'a donc pas besoin d'un aplat de bouton assombri — il
*est* le bouton, avec `--accent-on` par-dessus.

Le piège s'est refermé une fois pendant le travail : le bouton « Propose-moi la
semaine » gardait son encre claire, ce qui donnait **APCA 0**, strictement
illisible. C'est l'audit qui l'a vu. Un test le verrouille désormais : aucun
aplat opaque clair ne peut porter une encre sous Lc 60.

### Une fusion assumée : le citron porte tout le positif

La règle « une couleur, une fonction » posée en v15 est relâchée sur un point,
délibérément. Cette palette est monochrome plus un accent : poser un **second**
vert à côté du citron pour dire « réussi » serait l'erreur classique des deux
verts. Le citron porte donc l'action à faire *et* l'état atteint. Le rouge et
l'ambre gardent le reste, et le cyan ne garde que son rôle d'état — « ceci est
une information » dans les conseils du coach.

Tout le cyan **décoratif** disparaît : il teintait la moitié des intitulés et
faisait concurrence à l'accent. Les titres redeviennent neutres, les cartes du
coach et de la fatigue redeviennent des surfaces au lieu de dégradés teintés.

### Une seule teinte catégorielle déplacée

Cardio (`#68a553`) était à 10° du citron devenu couleur de marque. Le replacer en
rose (`#d66892`) fait passer la séparation minimale de tout le jeu de ΔE 7 à
**ΔE 12**, et l'écarte du citron de ΔE 42.

J'ai tenté une re-répartition complète des huit teintes : elle ne bat pas le jeu
existant. Huit catégories saturent le cercle des teintes. Jambes et Piscine
restent à ΔE 8 — c'est ce que ce jeu permet, le calendrier porte une légende
nommée, et le test verrouille cette valeur contre une dégradation plutôt que de
prétendre à mieux.

### La palette mesure mieux que la précédente

| | avant (v20) | après |
|---|---|---|
| textes sous seuil | 4 | **2** |
| APCA moyen | 89,8 | **91,3** |
| perdus en plein soleil | 0 | **0** |

## Le système de couleurs

Trois étages, dans `:root` :

| Étage | Rôle | Exemple |
| --- | --- | --- |
| **primitives** | valeurs brutes, aucun sens | `--n-800`, `--orange-i` |
| **sémantique** | le sens — le seul étage que le CSS devrait lire | `--surface-1`, `--accent-ink` |
| **hérité** | anciens noms redirigés, le temps de la migration | `--panel`, `--cyan` |

Toutes les valeurs sont construites en **OKLCH** puis ajustées par un
solveur jusqu'à atteindre leur cible **APCA**. Aucune n'a été choisie à
l'œil. OKLCH est perceptuellement uniforme : deux couleurs de même L ont
le même poids visuel, ce que HSL ne garantit pas.

### Pourquoi APCA et pas WCAG 2

Mesuré sur les pixels réellement rendus, l'ancienne palette échouait sur
**67 %** de ses textes en APCA tout en passant WCAG 2 AA à 100 %. WCAG 2
surestime le contraste quand les deux couleurs sont sombres, et donne le
même score à une étiquette de 11 px et à un titre de 32 px alors que la
fréquence spatiale pilote la perception. Il est inutilisable pour régler
un thème sombre.

### Trois jetons par rôle

Un aplat de milieu d'échelle ne porte **ni** l'encre claire **ni**
l'encre foncée : mesuré, du texte foncé sur `--accent` ne donne que
Lc 43. D'où :

- `--x` — l'aplat : pastilles, jauges, bordures ;
- `--x-btn` — l'aplat de bouton, assez sombre pour porter l'encre claire ;
- `--x-ink` — l'encre, visée à Lc 84, ce qu'il faut pour tenir à 13 px/700.

### Deux palettes qui ne se croisent jamais

La palette **sémantique** porte l'état — action, information, réussite,
prudence, danger. La palette **catégorielle** (`--s-push`, `--s-pull`…)
porte l'identité d'une séance et ne dit **jamais** un état. Avant, l'orange
signifiait « Poussée » *et* « le coach » *et* « compteur du mois » ; l'ambre
« Abdos » *et* « attention ». Une couleur ne peut pas porter deux sens.

Bras est passé au violet et Piscine au sarcelle : ils étaient
indiscernables de Tirage et de Jambes.

### La hiérarchie ne vient pas de la couleur

La table APCA demande Lc 90 à 14 px et Lc 100 à 12 px, quelle que soit la
graisse. Les trois niveaux de texte sont donc **proches** en contraste —
c'est la **taille** et la **graisse** qui portent la hiérarchie. Assombrir
davantage rendrait le texte illisible sans le rendre plus « secondaire ».

Conséquences : plancher à 12 px, graisse 500 minimum sous 15 px, `--text-3`
interdit sous 15 px, et du texte **coloré** seulement à partir de 14 px.

### La netteté

Trois réglages coûtaient du piqué : `-webkit-font-smoothing:antialiased`
(qui amincit les traits sur fond sombre), la texture de points à 3 px (du
bruit à haute densité) et le flou de verre à 22 px. Retiré, agrandi,
ramené à 12 px.

### Le verre

Les panneaux étaient des `rgba(255,255,255,.06)` posés sur un dégradé :
ils ne se détachaient pas du fond. Ils reposent maintenant sur une échelle
de surfaces **opaques**, séparées de ΔL ≈ 0,045 en OKLCH. La profondeur se
fait par la clarté, pas par l'ombre — on ne projette pas d'ombre sur du
noir. Et on ne descend pas au noir pur : le blanc pur sur noir pur provoque
du halo.

### Le test qui verrouille tout

`test-contraste.js` capture l'app, redécode les pixels et mesure chaque
texte contre son fond **réel**. Six budgets de non-régression : aucune
couleur ne peut redescendre sans faire échouer la suite.

| Mesure | Avant | Après |
| --- | --- | --- |
| APCA moyen | 71,4 | 89,5 |
| textes sous leur seuil | 37 / 56 | 3 / 53 |
| APCA moyen au soleil | 43,5 | 56,9 |
| textes perdus au soleil | 38 | 0 |

## Le passage de minuit

Une PWA reste ouverte ou en veille pendant des jours. Tout ce qui dépend
d'« aujourd'hui » était calculé une fois au chargement : passé minuit, la date
affichée, la bande de la semaine, le calendrier et la fatigue du jour restaient
figés sur la veille.

`verifierJour()` compare la date courante à celle affichée et, au moindre écart,
recalcule tout : date, fatigue, séries du jour, conseils, purge du prévu,
calendrier, coach, récap. Elle est déclenchée au retour au premier plan
(`visibilitychange`, `focus`, `pageshow`) et par une minuterie calée sur le
prochain minuit, réarmée à chaque passage.

La séance affichée, elle, ne bascule jamais toute seule : quelqu'un qui
s'entraîne à minuit passé n'a pas à voir son écran changer sous ses doigts.

## Le coach planificateur

Deux notions distinctes, volontairement séparées dans le stockage :

- `inrun_trainlog` — les séances **faites**. Toutes les statistiques (volume,
  récap, compteurs de régularité, jours depuis chaque groupe) ne lisent que ça.
- `inrun_plan` — les séances **prévues**, uniquement dans le futur. Purgé
  automatiquement dès qu'un jour est passé, et effacé pour un jour donné dès
  qu'une série y est enregistrée : le prévu devient du fait.

Quand tu touches un jour et choisis un groupe, `verdictJour(date, groupe)`
tranche avant l'enregistrement :

| Verdict | Quand | Ce qui se passe |
| --- | --- | --- |
| `stop` | même groupe à moins de 48 h, avant ou après (24 h pour les abdos) | le coach explique et propose un autre jour |
| `warn` | 4ᵉ jour d'affilée, groupe au-delà de 22 séries / 7 jours, 3ᵉ séance du même groupe dans la semaine, abdos deux jours de suite | idem, avec l'option repos |
| `ok` | tout le reste | enregistré sans friction, confirmation en bas de l'écran |

Les cinq groupes musculaires (`push`, `pull`, `legs`, `upper`, `core`) passent
par ce contrôle. **Piscine**, **Cardio** et **Repos** n'y sont pas soumis : ils
ne consomment aucune récupération musculaire, ne comptent pas comme séances
dans les statistiques, et coupent les séries de jours d'affilée.

Deux réglages par groupe, dans `recupMin()` et `volMin()` : la ceinture
abdominale récupère en 24 h et sature vers 6 séries par semaine, là où les gros
groupes demandent 48 h et 10 séries.

Rien n'est jamais imposé : « Garder quand même » respecte toujours ton choix.

Le même moteur alimente les suggestions sur les jours libres de la bande
hebdo (`→ Tirage`) et le bouton **Propose-moi la semaine**, qui remplit les
7 jours suivants en respectant les 48 h par groupe et en glissant un repos
avant tout 4ᵉ jour consécutif. Il ne touche jamais un jour déjà décidé.

## Sauvegarde

Moi → Exporter. Le JSON contient les clés : carnet, historique 1RM, poids,
profil, réglages, calendrier, planning prévisionnel, séries détaillées avec
RIR, prescriptions ajustées, fatigue, niveau.

Fais-le une fois par mois. C'est la seule protection contre un cache vidé ou un
changement de téléphone.

## Ce qui a été corrigé pour rendre la PWA opérationnelle

- **Fichiers PWA réels.** L'ancienne version (`massemachines10.html`) générait le
  manifeste et le service worker depuis des URL `blob:` — refusé par tous les
  navigateurs, erreur avalée, zéro offline et aucune installation possible.
  `manifest.json` et `sw.js` sont maintenant de vrais fichiers.
- **Service worker complet** : précache de l'app, réseau d'abord pour la
  navigation (fraîche en ligne, disponible hors ligne), cache des Google Fonts,
  ménage des anciennes versions, activation contrôlée par l'utilisateur.
- **Bandeau « Recharger » réellement efficace.** Il se contentait de recharger
  la page pendant que l'ancien worker gardait la main : on revenait sur la même
  version. Il demande maintenant `SKIP_WAITING` puis recharge une fois.
- **Icônes PNG.** iOS ignore les SVG en `apple-touch-icon` et collait une capture
  d'écran de la page sur l'écran d'accueil.
- **Groupe « Bras » rattaché aux compteurs.** Les stations `up1…up6` ne
  correspondaient à aucun groupe (`upper`) : le travail des bras n'entrait ni
  dans le volume hebdomadaire, ni dans le récap, et la séance n'était jamais
  marquée automatiquement dans le calendrier.
- **« Effacer tout le carnet de bord » efface vraiment.** Seule l'ancienne clé
  était supprimée : les séries revenaient au premier rechargement. Les perfs
  (séries, dernières perfs, historique 1RM) sont maintenant effacées ensemble,
  le calendrier, le poids et les réglages sont conservés.
- **Polices non bloquantes.** Une feuille de style distante retardait
  l'affichage quand le réseau de la salle est mauvais.
- **Navigation par ancre** (`#push`, `#plan`…), ce qui fait marcher les
  raccourcis d'icône du manifeste.
