# Arbitre — le verdict d'une course avant la fin du compte à rebours

Application Android qui se pose **par-dessus** ton application chauffeur. Dès
qu'une offre de course apparaît — sur l'écran ou en notification — elle la lit,
calcule ce que la course laisse réellement par heure de travail, et affiche
un mot :

```
┌──────────────────────────────────────┐
│  PRENDS                      ⚡ 84 ms│
│                                      │
│  31 €/h                    1,45 €/km │
│  ──────────────────●─────            │
│            objectif 25 €/h           │
│                                      │
│  31 €/h pour 25 visés                │
│  12,40 € net · 24 min · 8,6 km       │
│  approche 4 min · 1,2 km             │
│  circulation dense · 18 km/h         │
│  ⚠ retour à vide compté à 35 %       │
└──────────────────────────────────────┘
```

Un appui la referme, un glissement vertical la déplace et retient sa position.
Elle se pose **en haut** de l'écran et sa fenêtre est déclarée non focalisable :
le bouton « Accepter » de l'application chauffeur, toujours en bas, reste
atteignable. Un widget qui coûte une course acceptée coûterait plus cher qu'il
ne rapporte.

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

Un écran de navigation affiche lui aussi un prix et des kilomètres. Pour ne
pas faire surgir une bulle en pleine conduite, seuls sont retenus les écrans
qui portent un bouton d'acceptation (« Mise en relation », « Accepter »…) **ou**
deux distances distinctes — une offre annonce toujours l'approche *et* la
course, une navigation une seule. Les écrans écartés qui portaient un montant
sont notés dans le journal : si une course passe à travers, on y voit ce qu'il
manquait.

## Les cinq secondes

Demandé : voir la course en moins de 5 s. Obtenu : **de l'ordre de la
centaine de millisecondes**, et le chiffre est affiché sur chaque bulle
plutôt que promis.

Le chemin complet, entre l'apparition de l'offre et celle de la bulle :

1. Android livre l'événement au service — d'écoute des notifications ou
   d'accessibilité — que le système maintient lié en permanence. Rien à
   réveiller, rien à démarrer.
2. Les textes sont rassemblés : titre, corps et texte déplié pour une
   notification ; parcours en profondeur de l'arbre des vues, borné à 600
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
estimée **au rythme de l'approche** — mesurée sur les mêmes routes, à la même
minute, dans le même trafic — et non à une constante de réglage. Le verdict est
dans ce cas plafonné à `LIMITE` : une durée estimée ne mérite pas de feu vert.

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
└── app/                    Module Android
    └── src/main/kotlin/…/
        ├── LectureEcran.kt         AccessibilityService — lit la carte d'offre
        ├── EcouteNotifications.kt  NotificationListenerService — l'autre chemin
        ├── Arbitrage.kt            Le chemin commun aux deux, et le dédoublonnage
        ├── Bulle.kt                La superposition d'écran
        ├── Repli.kt                Notification de secours
        ├── Haptique.kt             Le verdict vibré
        ├── Reglages.kt             Préférences
        ├── Journal.kt              Historique local et mode découverte
        ├── ActivitePrincipale.kt   Permissions et barème
        └── ActiviteJournal.kt      Relecture et export du journal
```

Le moteur ne dépend d'aucune classe Android : c'est ce qui permet de tester
les 32 cas d'analyse et d'arbitrage sur une simple machine de build — dont la
carte d'offre Uber reproduite plus haut — et de vérifier un calcul de
rentabilité à la main plutôt que sur un émulateur.
