# Arbitre — le verdict d'une course avant la fin du compte à rebours

Application Android qui se pose **par-dessus** ton application chauffeur. Dès
qu'une offre de course arrive, elle lit la notification, calcule ce que la
course laisse réellement par heure de travail, et affiche un mot :

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

## Les cinq secondes

Demandé : voir la course en moins de 5 s. Obtenu : **de l'ordre de la
centaine de millisecondes**, et le chiffre est affiché sur chaque bulle
plutôt que promis.

Le chemin complet, entre l'affichage de la notification et celui de la bulle :

1. Android livre la notification à un `NotificationListenerService`, que le
   système maintient lié en permanence. Rien à réveiller, rien à démarrer.
2. Les textes de la notification sont concaténés (titre, corps, texte déplié).
3. Quelques expressions régulières en extraient prix, durées et distances.
4. Une trentaine d'opérations flottantes produisent le verdict.
5. La vue est ajoutée au `WindowManager`.

Aucun appel réseau, aucune base de données, aucun service à lancer sur ce
chemin — c'est ce qui rend le délai structurellement court, et pas seulement
court en moyenne. La latence mesurée est l'écart entre `postTime` (l'horodatage
système d'affichage de la notification) et la pose de la bulle : le délai tel
que le chauffeur le vit, pas le temps de calcul.

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
| **INCOMPLET** | la notification n'était pas lisible |

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
- « durée estimée » quand la notification n'en donnait pas

**Une donnée manquante n'obtient jamais de feu vert.** Si l'approche n'a pas pu
être lue, l'euro/heure est mécaniquement gonflé : le verdict est alors plafonné
à `LIMITE`. Un vert sur données trouées est l'erreur qui coûte cher.

---

## Lire la notification

Le texte varie d'une plateforme à l'autre et d'une version d'app à l'autre.
L'analyseur procède en quatre temps :

1. repérer tous les montants, durées et distances, **avec leur position** ;
2. classer chaque nombre en « approche » ou « trajet » selon les mots qui
   l'entourent (`de vous`, `de trajet`, `away`, `prise en charge`…) ;
3. attribuer le reste dans l'ordre de lecture — toutes les plateformes
   annoncent l'approche avant le trajet ;
4. vérifier la cohérence : « 12,50 € · 3 min · 8 km » donnerait 160 km/h, ce
   qui trahit une mauvaise attribution — les 3 min sont l'approche.

Sont gérés : virgule et point décimaux, espaces insécables avant le symbole
euro, mètres comme kilomètres, `1 h 15` comme `75 min`, et les heures de la
journée (`18h30`) qui ne sont pas des durées.

Les offres de course se réaffichent chaque seconde tant que le compte à rebours
tourne. Le dédoublonnage porte donc sur **les chiffres extraits**, jamais sur le
libellé : la bulle ne clignote pas.

### Quand une notification est mal lue

Le journal conserve le **texte brut** de chaque notification, et le bouton
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

Une nouvelle version se publie en poussant une étiquette :

```bash
git tag arbitre-v1.1 && git push origin arbitre-v1.1
```

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

### Les deux permissions

Au premier lancement, l'écran d'accueil mène aux deux réglages système :

- **Accès aux notifications** — pour voir l'offre dès son affichage. C'est la
  permission sensible : elle donne accès à *toutes* les notifications du
  téléphone. L'application n'en lit que le texte, ne retient que celles des
  applications cochées, et **rien ne sort du téléphone** : ni compte, ni
  serveur, ni analytique, ni permission réseau dans le manifeste.
- **Superposition d'écran** — pour dessiner par-dessus l'application chauffeur.
  Si elle est refusée, le verdict arrive par notification classique plutôt que
  d'être perdu.

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
        ├── EcouteNotifications.kt  NotificationListenerService — le point d'entrée
        ├── Bulle.kt                La superposition d'écran
        ├── Repli.kt                Notification de secours
        ├── Haptique.kt             Le verdict vibré
        ├── Reglages.kt             Préférences
        ├── Journal.kt              Historique local et mode découverte
        ├── ActivitePrincipale.kt   Permissions et barème
        └── ActiviteJournal.kt      Relecture et export du journal
```

Le moteur ne dépend d'aucune classe Android : c'est ce qui permet de tester
les 26 cas d'analyse et d'arbitrage sur une simple machine de build, et de
vérifier un calcul de rentabilité à la main plutôt que sur un émulateur.
