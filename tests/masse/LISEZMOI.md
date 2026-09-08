# Tests — I&N RUN Masse

**586 contrôles sur 23 fichiers.** Ils tournent dans un vrai Chromium contre
`docs/masse/index.html`, servi en local. Rien n'est simulé : ce qui est
vérifié est ce que le navigateur rend.

## Lancer

```bash
cd tests/masse
npm install          # une seule fois — playwright-core
bash batterie.sh     # toute la suite (~6 min)
bash batterie.sh test-histo.js   # un seul fichier
```

`batterie.sh` démarre son propre serveur si le port est libre et le coupe en
sortant. Le détail des échecs va dans `resultat.txt`.

Deux variables si besoin : `PORT_TEST` (8765 par défaut) et `CHROMIUM` (le
binaire du navigateur ; sinon `contexte.js` le cherche dans
`PLAYWRIGHT_BROWSERS_PATH`, puis laisse Playwright choisir le sien).

## Ce que chaque fichier tient

| fichier | ✓ | ce qu'il empêche |
|---|---|---|
| `test.js` | 32 | le socle : structure, navigation, saisie d'une série |
| `test-structure.js` | 38 | l'ossature du document et des séances |
| `test-plan.js` | 40 | le plan de la semaine et son calcul |
| `test-abdos.js` | 23 | la ceinture abdominale n'est pas oubliée |
| `test-assets.js` | 23 | chaque séance dessine quelque chose, et l'offline tient |
| `test-minuit.js` | 11 | le passage de minuit ne décale pas un jour |
| `test-coach.js` | 19 | le coach dit quelque chose, et dit vrai |
| `test-niveau.js` | 42 | les paliers montent et redescendent comme annoncé |
| `test-stimulus.js` | 32 | chaque muscle reçoit un stimulus réel |
| `test-contraste.js` | 6 | **APCA sur les pixels rendus** — aucun texte sous son seuil |
| `test-equilibre.js` | 16 | l'équilibre du volume entre groupes |
| `test-charge.js` | 26 | la double progression et le choix de charge |
| `test-design.js` | 55 | la palette, les états, la cohérence visuelle |
| `test-calories.js` | 32 | le modèle calorique, ses plafonds, **et la cohérence déficit ↔ fourchette** |
| `test-exigeant.js` | 23 | le coach reste exigeant quand il faut |
| `test-coherence.js` | 23 | l'app ne se contredit pas d'un écran à l'autre |
| `test-rattrapage.js` | 26 | le rattrapage après une semaine manquée |
| `test-hautbas.js` | 52 | Haut/Bas tient ses cibles aux niveaux 1 à 3 |
| `test-bilan.js` | 19 | le bilan de fin de séance |
| `test-maison.js` | 18 | les séances sans matériel restent hors des comptages |
| `test-jetons.js` | 9 | **aucune couleur en dur, aucune primitive lue par le CSS** |
| `test-echelles.js` | 9 | **rayons et tailles sur leur échelle, espacements pairs** |
| `test-histo.js` | 12 | **supprimer une série retire bien sa 1RM de l'historique** |

`audit.js` n'est pas un test : il parcourt l'app et cherche des
contradictions entre ce qu'elle affiche et ce qu'elle calcule. Il se lance
seul (`node audit.js`) et n'a pas de compteur.

## Les outils

Dans `outils/`, ce qui sert à instruire une décision plutôt qu'à la garder :

- `couleurs.js` — relève la couleur résolue de tous les éléments rendus, plus
  des états fabriqués à la main ; `diff_couleurs.py` compare deux relevés en
  normalisant les notations (`color(srgb …)` et `rgba()` sont le même pixel).
  C'est ce qui a permis de prouver qu'une migration de 191 jetons ne
  déplaçait que 7 pixels voulus.
- `appshot.js` — photographie les quatre onglets et signale tout débordement
  horizontal ou texte tronqué.
- `analyse-prog.js` — extrait le volume par muscle en utilisant la doctrine
  de l'app elle-même (`volumeNominalDe`, `cibleMuscle`) plutôt qu'une
  réimplémentation qui pourrait diverger. Elle a divergé une fois : le
  moteur compte 1 série, l'assistance 0,5.

## La fixture

`fixtures/historique.json` est un extrait **anonymisé** d'une sauvegarde
réelle. Il contient les trois points d'historique fantômes que
`test-histo.js` vérifie — un vrai bug ne se teste bien que sur les vraies
données qui l'ont révélé. Les champs personnels (poids du corps, taille,
âge, plan, activité, ressenti) en ont été retirés : aucun contrôle ne s'en
servait.

## Une chose à savoir sur le lanceur

`batterie.sh` traite **0 assertion comme un échec**. Sans ça, un fichier qui
plante avant d'exécuter son premier contrôle passe pour un succès — c'est
arrivé la première fois que la suite a tourné depuis le dépôt, et deux tests
cassés sont passés inaperçus dans un total qui annonçait « 0 KO ».
