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
var VERSION = 'v15';   →   'v16'
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
