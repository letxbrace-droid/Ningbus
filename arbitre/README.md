# Arbitre — le verdict d'une course avant la fin du compte à rebours

Application Android qui se pose **par-dessus** ton application chauffeur. Dès
qu'une offre de course apparaît — sur l'écran ou en notification — elle la lit,
calcule ce que la course laisse réellement par heure de travail, et affiche
un mot :

```
┌──────────────────────────────────────────────┐
│  LAISSE                          ⚡ 594 ms   │  1. la décision
├──────────────────────────────────────────────┤
│  20 €/h                          0,36 €/km   │  2. l'argent
│  ━━━━━━━━━━━━●━━━━━━━━━━━━━━━━━━━━━━━━━      │
│            objectif 25 €/h                   │
│                                              │
│  ⚠ Approche de 6,0 km avant la prise en      │  le motif
│    charge                                    │
│                                              │
│  28,1 km · 30 min mobilisées · 10,24 € net   │  3. les contraintes
│                                              │
│  course 16,4 km · durée estimée              │  4. l'explication
│  approche 6,0 km · 5 min                     │
│  ↩ retour à vide 5,7 km (supposé)            │
│  circulation voie rapide · 57 km/h           │
│                                              │
│  lecture : image · offre 90/100 ·            │  5. le diagnostic
│  confiance 93 %                              │
└──────────────────────────────────────────────┘
```

**Cinq étages, et le chauffeur doit pouvoir s'arrêter au premier.** Tout ce qui
suit le deuxième est là pour justifier après coup, jamais pour décider.

Trois gestes, aucun qui demande de viser au volant : un appui la réduit à ses
deux premiers étages — `🔴 20 €/h — LAISSE` — et le choix est retenu d'une
offre à l'autre ; un glissement vertical la déplace et retient sa place ; un
appui long ouvre le journal.

Elle se pose **en haut** de l'écran et sa fenêtre est déclarée non focalisable :
le bouton « Accepter » de l'application chauffeur, toujours en bas, reste
atteignable. Un widget qui coûte une course acceptée coûterait plus cher qu'il
ne rapporte.

**Les trois kilométrages ne sont jamais additionnés.** « 28,1 km » ne dit rien ;
« 16,4 de course, 6,0 d'approche, 5,7 de retour à vide » dit tout — et rend
criante une approche lue quatre fois trop grande, ce qui est exactement
arrivé sur la course reproduite ci-dessus.

---

## Deux façons de voir l'offre, parce qu'une seule ne suffit pas

C'est le point le plus important, et celui sur lequel la première version se
trompait : **quand l'application chauffeur est au premier plan — c'est-à-dire
pendant tout le temps où l'on travaille — Uber dessine sa carte d'offre sans
poster la moindre notification.** Un service d'écoute des notifications ne voit
alors strictement rien, et la course passe.

L'application écoute donc par deux chemins :

| Chemin | Quand il sert | Permission |
|---|---|---|
| **Lecture de l'écran** | l'app chauffeur est au premier plan — le cas courant | accessibilité |
| **Notification** | l'app est en arrière-plan ou l'écran verrouillé | accès aux notifications |

Les deux passent par le même arbitrage et le même dédoublonnage : une course
vue par les deux chemins ne produit qu'une bulle.

La lecture d'écran n'est branchée **que sur les applications cochées**. Le
filtre est posé dans `setServiceInfo`, donc appliqué par le système lui-même :
les événements des autres applications n'atteignent jamais ce code.

### L'offre flotte par-dessus n'importe quelle application

Deuxième point sur lequel les premières versions se trompaient. La carte
d'offre n'est pas dessinée *dans* l'application chauffeur : c'est une **fenêtre
flottante** posée par-dessus ce que le chauffeur regardait — l'écran d'accueil,
un GPS, TikTok — et **le focus reste à la fenêtre du dessous**.

Or `rootInActiveWindow` ne rend que la fenêtre qui a le focus. On lisait donc
consciencieusement le mauvais écran : un prix attrapé au hasard, aucune
distance, et un verdict « INCOMPLET » sur une carte parfaitement lisible. La
lecture parcourt désormais **toutes les fenêtres affichées**, de la plus en
avant vers la plus en arrière.

Deux conséquences qui en découlent :

- **le filtre par application passe avant l'étranglement.** L'application du
  dessous émet des dizaines d'événements par seconde ; étrangler d'abord
  dépensait tout le budget sur elle, et l'événement d'Uber arrivé cent
  millisecondes plus tard était jeté ;
- **quand une application est nommée, on ne lit que ses fenêtres.** Le repli
  « à défaut, toutes » attribuait le texte d'une application voisine à celle
  qui avait émis l'événement. Il ne subsiste que pour l'analyse demandée à la
  main, qui ne sait légitimement pas d'où vient l'écran.

### La pastille « €/h »

Une pastille déplaçable reste à l'écran. **Un appui analyse l'écran tel qu'il
est** : c'est le chemin qui ne dépend d'aucune détection, d'aucun nom de paquet
et d'aucune forme d'écran. Si une offre passe inaperçue, il répond quand même.

**Un appui long** envoie le texte brut de l'écran au journal, sans
interprétation, toutes fenêtres confondues. C'est la seule donnée qui permette
de comprendre pourquoi une offre n'a pas été lue plutôt que de le supposer —
trois versions ont été passées à deviner ce que le service voyait.

### Reconnaissance de texte, en second rideau

L'arbre d'accessibilité a un angle mort, et il s'élargit : une application qui
**peint** sa carte d'offre sur un `Canvas`, ou qui compose une vue sans lui
donner de sémantique, n'expose aucun nœud de texte. Le service lit une fenêtre
parfaitement vide pendant que le chauffeur, lui, voit une offre.

Une capture d'écran est alors analysée par un moteur de reconnaissance
embarqué dans l'APK — pas de réseau, pas de Play Services, rien qui sorte du
téléphone. Elle ne part jamais en premier, et quatre garde-fous la bornent :

- **l'arbre d'abord.** L'OCR ne sert que si l'arbre n'a rien donné
  d'arbitrable ;
- **une fenêtre qui s'ouvre**, et non un contenu qui se rafraîchit ;
- **trois captures par écran distinct**, comptées sur l'empreinte du couple
  application + texte lu. Un écran figé ne coûte donc que trois captures, quel
  que soit le nombre d'événements qu'il émet ;
- **nos propres fenêtres s'effacent** le temps de la prise. Une image ne
  connaît pas les paquets : sans cela, la bulle du verdict précédent serait
  relue comme une offre, et la pastille verrait son « 31 €/h » retenu comme
  prix de la course.

Ce qu'on ne peut pas faire : deviner depuis l'arbre qu'une carte peinte
existe. C'est le sens même de l'angle mort. Le déclenchement ne dépend donc
d'aucune propriété de l'arbre — un garde-fou qui s'y fiait refusait la capture
parce qu'une *autre* fenêtre était bavarde.

### Distinguer une offre du reste de l'écran

Un écran de navigation affiche lui aussi un prix et des kilomètres. Une règle
binaire — un bouton d'acceptation **ou** deux distances — tenait, mais ne
savait rien dire : un écran écarté l'était sans motif.

Un score additionne donc des indices qui se défendent seuls, et retranche ce
qui trahit un autre écran :

| Indice | Poids |
|---|---|
| bouton d'acceptation (« Mise en relation », « Accepter »…) | +30 |
| un montant | +20 |
| distance d'approche | +15 |
| distance de course | +15 |
| une durée | +10 |
| compte à rebours | +10 |
| vocabulaire de navigation (« en route », « restants »…) | −30 |
| vocabulaire d'historique (« gains », « courses terminées »…) | −30 |
| écran de réglages (« préférences », « tarifs »…) | −40 |

Au-dessus de 55, l'écran est arbitré. En dessous, il est écarté — et le calcul
part au journal, donc relisible :

```
pas une offre (5) · +20 montant +15 distance de course -30 vocabulaire de navigation
```

### Ce que vaut la lecture, séparément de ce qu'elle dit

Deux questions se confondaient en une seule : « cette course est-elle
rentable ? » et « ai-je assez lu pour le dire ? ». Un LAISSE sur données
complètes et un LAISSE calculé sur une approche inventée se ressemblent à
l'écran et ne se valent pas.

Chaque champ est donc pesé par ce que son absence coûte au calcul — prix 40 %,
distance de course 20 %, distance d'approche 15 %, durée de course 15 %, durée
d'approche 10 % — un champ reconstitué comptant pour la moitié d'un champ lu.
La bulle affiche le pourcentage et ce qui manque ; le journal porte le détail :

```
confiance 93 %
✓ prix : 12,51 €
✓ distance de la course : 12,6 km
✓ distance d'approche : 2,5 km
≈ durée de la course : estimée
✓ durée d'approche : 9 min
```

**La confiance ne peut que resserrer les règles, jamais les desserrer.** Elle
s'ajoute aux conditions d'origine du feu vert au lieu de les remplacer, et un
test l'exige explicitement : une mesure de fiabilité qui autoriserait un vert
refusé jusque-là ferait l'inverse de ce qu'on attend d'elle.

### Quand Android détache le service

Un service d'accessibilité n'est pas mis en veille par le Doze : c'est le
système qui le maintient lié. La croyance inverse, répandue, est fausse sur un
Android de référence.

Elle ne l'est pas sur les surcouches. MIUI, EMUI, ColorOS et quelques autres
arrêtent des **processus** entiers selon leurs propres règles, et le service
part avec le sien. Le système le relie parfois ensuite, parfois pas — c'est la
panne « autorisé mais non lié », où tout paraît en ordre et où rien ne se
produit.

Deux remèdes, et une limite qu'il faut dire :

- un **service au premier plan** qui ne calcule rien, et dont le seul effet est
  d'élever l'importance du processus ;
- une **exemption d'optimisation batterie**, proposée depuis le tableau de
  santé ;
- **l'application ne peut pas se relier elle-même.** Seul le système lie un
  service d'accessibilité, et l'y forcer demanderait `WRITE_SECURE_SETTINGS`,
  que nul n'accorde à une application installée de côté. Elle peut en revanche
  cesser d'être muette : un battement vérifie la liaison chaque minute et
  prévient, ce qui transforme une panne inexplicable en notification qui mène
  droit au réglage à recocher.

## Les cinq secondes

Demandé : voir la course en moins de 5 s. Obtenu : **de l'ordre de la
centaine de millisecondes**, et le chiffre est affiché sur chaque bulle
plutôt que promis.

Le chemin complet, entre l'apparition de l'offre et celle de la bulle :

1. Android livre l'événement au service — d'écoute des notifications ou
   d'accessibilité — que le système maintient lié en permanence. Rien à
   réveiller, rien à démarrer.
2. Les textes sont rassemblés : titre, corps et texte déplié pour une
   notification ; parcours en profondeur de l'arbre des vues, borné à 3000
   nœuds, pour une carte d'offre.
3. Quelques expressions régulières en extraient prix, durées et distances.
4. Une trentaine d'opérations flottantes produisent le verdict.
5. La vue est ajoutée au `WindowManager`.

Aucun appel réseau, aucune base de données, aucun service à lancer sur ce
chemin — c'est ce qui rend le délai structurellement court, et pas seulement
court en moyenne. La latence mesurée est l'écart entre l'horodatage système de
l'événement — `postTime` pour une notification, `eventTime` pour un
changement d'écran — et la pose de la bulle : le délai tel que le chauffeur
le vit, pas le temps de calcul. Le journal note aussi par quel chemin chaque
course est arrivée.

Le **journal** affiche la médiane et le pire cas relevés sur les dernières
courses : la promesse est vérifiable sur ton propre téléphone.

---

## Comment la rentabilité est calculée

Un prix ne dit rien. « 18 € » est excellent pour 12 minutes à côté, désastreux
pour 14 minutes d'approche suivies de 40 minutes de bouchons et d'une dépose
en grande banlieue. L'arbitre ramène donc tout à **ce qu'il te reste par heure
réellement mobilisée**.

### Le temps mobilisé, pas le temps payé

```
temps total = approche + attente au ramassage + trajet + repositionnement
```

L'approche est du temps non payé. L'attente au point de prise en charge aussi.
Le **retour à vide** également : une dépose à 30 km de ta zone te coûte le
trajet du retour, que personne ne règle. Ce dernier poste est réglable de 0 %
(tu enchaînes toujours sur place) à 100 % (tu reviens systématiquement à ton
point de départ) ; 35 % par défaut.

### Les kilomètres coûtent, tous

```
km total = approche + trajet + repositionnement
coût     = km total × coût de roulage
revenu net = prix − commission − coût
```

Le coût de roulage n'est pas que le carburant : pneus, entretien,
amortissement. Environ 0,22 €/km en thermique, 0,10 €/km en électrique
rechargé à la maison.

### Le verdict

```
€/h = revenu net ÷ (temps total / 60)
```

Comparé à ton objectif horaire, avec une bande de tolérance de ±15 % :

| Verdict | Condition |
|---|---|
| **PRENDS** | au-dessus de l'objectif + 15 % |
| **LIMITE** | dans la bande autour de l'objectif |
| **LAISSE** | en dessous de l'objectif − 15 % |
| **INCOMPLET** | l'offre n'était pas lisible |

Trois **vetos** écrasent le calcul, quel que soit l'euro/heure : prix sous ton
plancher, approche au-dessus de ta limite, ou roulage plus coûteux que la
course ne rapporte.

### Les bouchons, sans appel réseau

La durée annoncée par la plateforme est une ETA calculée sur le trafic réel du
moment. Le rapport distance/durée contient donc déjà les bouchons :

```
vitesse implicite = km du trajet ÷ (durée annoncée / 60)
```

| Vitesse | Lecture | Durée majorée de |
|---|---|---|
| < 13 km/h | bouchons | 25 % |
| 13–22 km/h | dense | 12 % |
| 22–45 km/h | fluide | — |
| > 45 km/h | voie rapide | — |

La majoration n'est pas une défiance gratuite : les ETA sont optimistes, et
elles le sont d'autant plus que la circulation est déjà chargée — un bouchon
se dégrade plus souvent qu'il ne se résorbe pendant qu'on y roule. Mieux vaut
le savoir avant d'accepter qu'en route. Réglage désactivable.

À noter : rouler lentement n'est pas mauvais en soi, puisque les tarifs
comportent une part au temps. C'est bien l'euro/heure final qui tranche, pas
la vitesse.

### Ce qui est signalé sans être fatal

- « approche inconnue — verdict optimiste »
- « plus de vide que de charge »
- « X % du temps n'est pas payé » (au-delà de 45 %)
- « durée estimée » quand l'offre n'en donnait pas

**Une donnée manquante n'obtient jamais de feu vert.** Si l'approche n'a pas pu
être lue, l'euro/heure est mécaniquement gonflé : le verdict est alors plafonné
à `LIMITE`. Un vert sur données trouées est l'erreur qui coûte cher.

---

## Lire l'offre

Le texte varie d'une plateforme à l'autre et d'une version d'app à l'autre.
L'analyseur procède en cinq temps :

1. repérer tous les montants, durées et distances, **avec leur position** ;
2. classer chaque nombre en « approche » ou « trajet » selon les mots qui
   l'entourent (`de vous`, `de trajet`, `away`, `prise en charge`…) ;
3. transmettre le rôle entre une durée et une distance **collées** — « 16 min
   (à 10,9 km) » décrit une seule étape. La transmission ne saute que de la
   ponctuation : dans « (1,3 km) de vous 22 min », les mots qui séparent les
   deux nombres disent précisément qu'il s'agit d'étapes différentes ;
4. attribuer le reste dans l'ordre de lecture — toutes les plateformes
   annoncent l'approche avant le trajet ;
5. vérifier la cohérence : « 12,50 € · 3 min · 8 km » donnerait 160 km/h, ce
   qui trahit une mauvaise attribution — les 3 min sont l'approche.

C'est ce qui fait tenir le format réel d'Uber, où rien ne nomme l'approche :

```
UberX Priority          17,08 €          Montant net de frais
+2,34 € inclus pour la prise en charge
16 min (à 10.9 km)      125 Rue Lieutenant André Lemoal, 91640 Briis-sous-Forges
Course de 12.1 km       121 Chem. du Vieux Pavé, 91310 Saint-Germain-lès-Arpajon
```

« Course de 12,1 km » nomme le trajet ; 10,9 km est donc l'approche par
élimination ; et « 16 min », collé à elle, en hérite. Le prix retenu est 17,08 €
et non le bonus de 2,34 € qui y est déjà inclus, ni la note 4,80 du chauffeur —
qui n'a pas de symbole euro. Les codes postaux 91640 et 91310 ne sont pas lus
comme des distances.

Cette course-là, au barème par défaut, rapporte **moins de 16 €/h** et se fait
refuser sur la seule approche : 16 minutes et 10,9 km à vide pour 12,1 km
payés.

Sont gérés : virgule et point décimaux, espaces insécables avant le symbole
euro, mètres comme kilomètres, `1 h 15` comme `75 min`, et les heures de la
journée (`18h30`) qui ne sont pas des durées.

Uber n'annonce pas la durée du trajet, seulement sa distance. Elle est alors
estimée à partir de la **vitesse typique d'un trajet de cette longueur**,
corrigée par ce que l'approche apprend du trafic du moment :

| Longueur du trajet | Vitesse typique |
|---|---|
| < 3 km | 18 km/h |
| 3–10 km | 26 km/h |
| 10–25 km | 38 km/h |
| > 25 km | 55 km/h |

Extrapoler bêtement le rythme de l'approche était une faute de méthode, et
elle coûtait cher : les 2,5 km d'approche de la course des Ulis sont des rues
de ville à 17 km/h, et appliquer ce rythme aux 12,6 km du trajet donnait
45 minutes — le double du vrai. Une course longue emprunte des axes, une course
courte reste aux carrefours. Le facteur de trafic tiré de l'approche reste
borné à [0,6 ; 1,5], pour qu'une approche de 200 m dans un parking ne condamne
pas une course de 40 km.

Le verdict est dans ce cas plafonné à `LIMITE` : une durée estimée ne mérite
pas de feu vert.

Les offres de course se réaffichent chaque seconde tant que le compte à rebours
tourne. Le dédoublonnage porte donc sur **les chiffres extraits**, jamais sur le
libellé : la bulle ne clignote pas.

### Quand une offre est mal lue

Le journal conserve le **texte brut** de chaque offre, et le bouton
« Copier le journal » le met dans le presse-papiers. C'est la seule matière qui
permette de corriger l'analyseur quand une plateforme change ses libellés — les
motifs sont dans `moteur/src/main/kotlin/…/Analyseur.kt`, les cas de test dans
`moteur/src/test/kotlin/…/AnalyseurTest.kt`.

---

## Installation

L'application n'est pas sur le Play Store : elle lit les notifications d'autres
applications, ce qui relève d'une permission que Google n'accorde qu'à un
usage justifié. Elle s'installe donc de côté (« sideload »).

### Depuis le téléphone — la page Releases

[**Dernière version**](https://github.com/letxbrace-droid/Ningbus/releases/latest) :
le fichier `.apk` s'y télécharge directement, sans compte GitHub et sans
dézippage. L'ouvrir suffit ; Android demandera d'autoriser « installer des
applications inconnues » pour le navigateur.

Une nouvelle version se publie en changeant un chiffre : modifier le fichier
`arbitre/VERSION` et pousser. Le workflow **Arbitre — publication** construit
l'APK, le nomme d'après ce numéro, crée l'étiquette `arbitre-vX.Y` et la
Release. Republier le même numéro remplace le fichier au lieu d'échouer.

Le dépôt étant public, le fichier d'une Release se télécharge **sans compte
GitHub**. Ce n'est pas le cas des artefacts de la CI ci-dessous, que GitHub
réserve aux utilisateurs connectés même sur un dépôt public.

L'APK est signé avec la clé de débogage versionnée dans `app/debug.keystore`,
et non avec celle que chaque machine engendre dans son coin. C'est ce qui
permet d'installer une mise à jour par-dessus la précédente sans désinstaller
— donc sans perdre ses réglages ni son journal.

### Depuis un ordinateur — l'artefact de la CI

Chaque passage du workflow **Arbitre (Android)** publie un APK, y compris sur
les branches de travail : onglet **Actions** → dernier passage vert → section
**Artifacts** → `arbitre-debug-apk`. Il faut être connecté à GitHub, et
l'artefact arrive sous forme de `.zip` à décompresser.

### Compiler soi-même

```bash
cd arbitre
./gradlew :app:assembleDebug      # APK dans app/build/outputs/apk/debug/
./gradlew :moteur:test            # les tests du moteur, sans SDK Android
```

Le module `:app` n'est inclus dans la construction que si un SDK Android est
présent (`ANDROID_HOME`, `ANDROID_SDK_ROOT` ou un `local.properties`). Le
moteur, lui, se teste sur n'importe quelle machine dotée d'un JDK.

### Les trois permissions

Au premier lancement, l'écran d'accueil mène aux trois réglages système :

- **Lecture de l'écran** (accessibilité) — la plus importante : sans elle,
  toutes les offres reçues pendant que l'app chauffeur est au premier plan
  passent inaperçues. Elle n'est branchée que sur les applications cochées.
- **Accès aux notifications** — pour les offres reçues app en arrière-plan ou
  écran verrouillé. Elle donne accès à *toutes* les notifications du
  téléphone ; l'application n'en lit que le texte et ne retient que celles des
  applications cochées.
- **Superposition d'écran** — pour dessiner par-dessus l'application chauffeur.
  Si elle est refusée, le verdict arrive par notification classique plutôt que
  d'être perdu.

Ces deux premières permissions sont larges par nature. Ce qui les borne ici :
**rien ne sort du téléphone** — ni compte, ni serveur, ni analytique, et
aucune permission réseau dans le manifeste. Le code qui le vérifie tient en une
ligne : `grep INTERNET app/src/main/AndroidManifest.xml` ne renvoie rien.

Le bouton **« Essayer avec une course fictive »** affiche une bulle immédiatement :
de quoi la placer où l'on veut et régler son barème sans attendre une vraie offre.

### Trouver son application chauffeur

La liste d'origine (Uber, Bolt, Heetch, FreeNow, Marcel, Caocao) part de noms de
paquets qui changent selon les versions et les pays. Le **mode découverte**
retient les applications non cochées qui envoient une notification contenant un
montant et une distance : elles apparaissent sous la liste, prêtes à cocher.
Aucune permission supplémentaire n'est nécessaire — en particulier pas
`QUERY_ALL_PACKAGES`.

---

## Le banc d'essai

Quatre versions ont été livrées sans qu'une seule ligne du chemin réel n'ait
jamais été exécutée. Les tests du moteur vérifient le calcul ; **toutes** les
pannes rencontrées étaient ailleurs — la mauvaise fenêtre lue, l'arbre des vues
tronqué, la carte pas encore dessinée. Aucun test unitaire ne pouvait les voir,
et supposer à leur place coûtait un aller-retour à chaque fois.

Le module `:simulateur` est une **fausse application chauffeur**, paquet
distinct — la lecture d'écran ignore les fenêtres d'Arbitre lui-même, un
simulateur logé dans la même application validerait donc un chemin que personne
n'emprunte. Elle rejoue de vraies cartes d'offre, ligne par ligne et dans
l'ordre, puisque l'analyseur se sert de la position des nombres. Elle les
affiche de trois façons — plein écran, en fenêtre flottante par-dessus
l'accueil (focus laissé au-dessous), et **peinte sur un `Canvas`**, sans le
moindre nœud de texte.

Six essais instrumentés tournent sur émulateur à chaque modification :

| Essai | Ce qu'il interdit de casser |
|---|---|
| `uneOffreDessineeEstLueParReconnaissanceDeTexte` | une carte peinte est lue, bulle précédente à l'écran, et ne produit qu'un verdict |
| `uneOffreEnFenetreFlottanteEstLue` | l'offre flottante est lue avec son approche **et** sa course |
| `uneOffreEnPleinEcranEstLue` | l'offre de Briis est refusée pour la bonne raison |
| `le_montant_retenu_est_le_prix_et_non_le_bonus` | 12,51 € et non le bonus de 2,43 € |
| `un_ecran_de_navigation_ne_declenche_aucune_bulle` | aucune bulle en pleine conduite, mais l'écran est journalisé |
| `uneMemeOffreNEstArbitreeQuUneFois` | une carte qui se redessine ne fait pas vingt bulles |

Trois précautions gouvernent leur écriture, chacune payée d'un tour perdu :

1. **L'automate d'interface n'éteint pas ce qu'il mesure.** Une instrumentation
   qui ouvre une `UiAutomation` devient par défaut le seul client
   d'accessibilité du système, qui délie alors tous les autres services. Le
   `dumpsys` le disait ligne à ligne — le service *activé*, jamais *lié*. D'où
   `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`, obtenu par un accesseur :
   redemander l'automate sans le drapeau le reconstruit, et la suppression
   revient.
2. **Arbitre est installé, le simulateur est instrumenté.** Les essais vivent
   donc dans `:simulateur` : aucun processus d'essai dans l'espace d'Arbitre,
   aucun réglage écrit dans son dos. Le banc ne lui parle que par où le système
   lui parle — une fenêtre qui s'affiche — et ne le relit que par le disque,
   via `run-as`.
3. **La configuration n'est pas truquée.** Le simulateur figure dans la liste
   d'origine des applications écoutées, mais sur émulateur seulement
   (`Reglages.paquetsDOrigine`). Le banc éprouve les réglages livrés, et non
   des réglages posés pour la circonstance — c'est précisément l'écart entre
   les deux qui produisait la panne suivante.

```bash
./gradlew :moteur:test                      # 50 cas, sans SDK Android
bash banc-essai.sh                          # tout le parcours, sur un appareil branché
```

### Ce que le gardien a trouvé, et que rien d'autre n'aurait trouvé

L'essai de la carte peinte a coûté cinq tours, et à chaque fois pour une
cause **différente** qui produisait exactement le même symptôme : un silence
complet, indiscernable de tous les autres silences.

1. **L'application se lisait elle-même.** Une capture d'écran ne connaît pas
   les paquets : elle prend nos propres fenêtres. La bulle du verdict
   précédent, relue, contenait un montant, une approche et une distance — les
   siens. Le verdict engendrait un verdict.
2. **Le premier remède était trop large.** Interdire toute capture tant
   qu'une bulle est affichée marchait, mais une offre qui arrive pendant
   qu'un verdict traîne encore est précisément celle qu'il ne faut pas
   manquer. Ce n'est pas la capture qu'il faut empêcher, c'est notre verdict
   qu'il faut absenter de l'image.
3. **Le déclenchement dépendait de l'arbre.** La capture était refusée parce
   qu'une *autre* fenêtre exposait 324 caractères — alors qu'une carte peinte
   ne laisse par définition aucune trace dans l'arbre. Décider d'une carte
   invisible d'après le bavardage de sa voisine n'a aucun sens.
4. **La carte du simulateur débordait.** Sur la dalle de 320 × 640 pixels de
   l'émulateur, une carte de douze lignes ancrée en bas se fait rogner par le
   haut — donc le montant, en deuxième ligne.
5. **Le quota de reprises était une porte derrière une porte fermée.** Trois
   captures étaient permises par écran, mais chaque tentative attendait un
   nouvel événement de fenêtre — or une carte qui finit de se dessiner n'en
   produit aucun.

Aucune de ces cinq causes n'était visible en lisant le code, et chacune
imitait les quatre autres.

Le script existe parce que l'action d'émulateur exécute son champ `script`
**ligne par ligne**, chacune dans son propre `sh -c` : une continuation de
ligne y fait attendre l'interpréteur sur son entrée jusqu'au garde-fou de
quarante-cinq minutes, et aucune variable ne survit d'une ligne à la suivante.

---

## Réglages

| Réglage | Défaut | Ce qu'il change |
|---|---|---|
| Objectif, € nets/h | 25 | L'étalon de tout le reste |
| Coût de roulage, €/km | 0,22 | Carburant + pneus + entretien + amortissement |
| Commission prélevée, % | 0 | 0 si la notification annonce déjà ta part |
| Retour à vide, % du trajet | 35 | Le repositionnement après la dépose |
| Attente au ramassage, min | 2 | Le temps mort sur place |
| Approche maximale, min | 12 | Veto |
| Prix plancher, € | 6 | Veto |
| Zone « limite », ± % | 15 | Largeur de la bande orange |
| Vitesse supposée, km/h | 22 | Ne sert qu'à compléter une donnée absente |
| Affichage de la bulle, s | 12 | Avant escamotage |

Le **verdict vibré** donne trois rythmes distincts — deux impulsions brèves pour
prendre, une longue pour laisser — de quoi décider sans quitter la route des yeux.

---

## Ce que l'application ne fait pas, volontairement

- **Elle n'accepte jamais une course à ta place.** Automatiser l'acceptation
  suppose de piloter l'interface d'une autre application : c'est contraire aux
  conditions d'utilisation des plateformes, et une erreur d'automatisation
  engage un vrai véhicule sur une vraie route. L'arbitre conseille, le
  chauffeur décide.
- **Elle n'appelle aucune API de trafic.** Un appel réseau au moment où la
  course tombe, c'est de la latence et une dépendance à la couverture mobile
  au pire endroit. La vitesse implicite donne l'essentiel gratuitement.
- **Elle ne connaît pas la destination.** Les notifications la mentionnent
  rarement, et l'adresse texte ne suffit pas à juger d'une zone. Le retour à
  vide est traité par un pourcentage réglable plutôt que par une géolocalisation
  approximative.
- **Elle n'envoie rien nulle part.** Tout est local : préférences, journal,
  calculs.

---

## Structure

```
arbitre/
├── moteur/                 Kotlin pur — analyse et arbitrage, testable sans Android
│   └── src/main/kotlin/…/
│       ├── Course.kt       Modèle d'une course, trafic, vitesse implicite
│       ├── Bareme.kt       Les paramètres économiques du chauffeur
│       ├── Analyseur.kt    Extraction des chiffres d'une notification
│       ├── Arbitre.kt      Le calcul de rentabilité et le verdict
│       └── Plateformes.kt  Noms de paquets des applications chauffeur
├── app/                    Module Android
│   └── src/main/kotlin/…/
│       ├── LectureEcran.kt         AccessibilityService — lit la carte d'offre
│       ├── EcouteNotifications.kt  NotificationListenerService — l'autre chemin
│       ├── Arbitrage.kt            Le chemin commun aux deux, et le dédoublonnage
│       ├── Bulle.kt                La superposition d'écran
│       ├── BoutonFlottant.kt       La pastille « €/h » — analyse et capture à la main
│       ├── Repli.kt                Notification de secours
│       ├── Haptique.kt             Le verdict vibré
│       ├── Reglages.kt             Préférences
│       ├── Journal.kt              Historique local, écrans écartés, captures brutes
│       ├── ActivitePrincipale.kt   Permissions et barème
│       └── ActiviteJournal.kt      Relecture et export du journal
├── simulateur/             Fausse application chauffeur — le banc d'essai
│   ├── src/main/kotlin/…/Offres.kt            Vraies cartes, reproduites ligne par ligne
│   ├── src/main/kotlin/…/ActivitePrincipale.kt Plein écran ou fenêtre flottante
│   └── src/androidTest/kotlin/…/BancEssaiTest.kt Les cinq essais de bout en bout
└── banc-essai.sh           Pose, essais et relevés, dans un vrai interpréteur
```

Le moteur ne dépend d'aucune classe Android : c'est ce qui permet de tester
les 50 cas d'analyse, d'arbitrage, de confiance et de détection sur une simple
machine de build — dont la carte d'offre Uber reproduite plus haut — et de
vérifier un calcul de rentabilité à la main plutôt que sur un émulateur. Ce
que ces 50 cas ne peuvent pas voir, le banc d'essai le voit.
