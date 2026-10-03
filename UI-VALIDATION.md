# Arbitre 2.0 UI — sources candidates, non compilées

## Base vérifiée

Archive utilisateur : Ningbus-claude-widget-course-arbitrage-y8uo30.zip.
Commit inscrit dans le ZIP : a2a197d27c0aef456fd6519638a231305a84a755.
Le préfixe a2a197d est celui de la capture du workflow #35 fournie par l'utilisateur.
Pas d'accès authentifié à GitHub : aucune branche créée, aucun push, aucune fusion.
Ne pas appliquer ces modifications à main, qui ne contient pas le projet Android
sur la capture fournie. Créer arbitre-2.0-ui depuis le commit ci-dessus.

## Modifications

- ActivitePrincipale.kt : intégration des quatre onglets, restauration de l'onglet,
  switches Material, curseurs Material avec libellés, haptique, chips d'état,
  €/km mis en avant dans le simulateur. Gestion existante des réglages conservée.
- InterfacePremium.kt : présentation réutilisable, navigation Material 3,
  transitions 220 ms respectant la désactivation des animations, insets système/clavier,
  cockpit et cartes d'offres. Rafraîchissement périodique arrêté en pause.
- Journal.kt : ajout du seul champ économique optionnel euroKm, enregistré depuis
  le Verdict existant. Les fonctions de dédoublonnage du journal restent identiques.
  Anciennes données : aucune valeur inventée ou recalculée avec un nouveau barème.
- Bulle.kt et BoutonFlottant.kt : afficheurs €/km ; fenêtres et gestes inchangés.
- principale.xml : pages Accueil / Courses / Simulateur / Réglages.
- bulle.xml : métrique €/km au-dessus du €/h, disposition verticale.
- bouton_flottant.xml et strings.xml : libellé de repos €/km.
- fond_carte.xml : coins 24 dp.
- menu/navigation.xml et quatre icônes vectorielles : navigation basse.
- tests_ui_sandbox.py : contrôles statiques additionnels, distincts des tests moteur.

## Limites importantes

Le verdict reste celui du moteur existant, basé notamment sur l'objectif horaire.
La priorité visuelle au €/km ne transforme pas le moteur en modèle décisionnel au km.
Le net est celui du modèle (barème et éventuelles estimations), pas un revenu réellement
encaissé. Les courses affichées sont des offres analysées, pas des courses effectuées.
Le simulateur existant utilise une offre fictive fixe ; ses curseurs modifient les
réglages du barème, comme dans l'application d'origine.
Le service de veille ne fournit pas d'indicateur d'exécution public : l'UI dit
« Demandée · état non exposé », pas « actif ». Les services de lecture et de
notifications affichent leur liaison réelle. L'OCR indique le secours autorisé,
pas une reconnaissance en cours.

## Résultats sandbox

8 contrôles statiques réussis :

1. Sources moteur, simulateur, services protégés et tests identiques au ZIP original.
2. Aucun changement des fichiers existants en dehors d'arbitre/.
3. Tous les XML bien formés.
4. Tous les identifiants de l'écran original conservés sans doublon ; dix switches.
5. Quatre destinations, contrôle des animations et cycle de vie du rafraîchissement.
6. Corps des fonctions de gestes strictement identiques ; FLAG_NOT_FOCUSABLE présent.
7. Déduplication du journal identique ; aucun recalcul des anciennes offres dans l'UI.
8. Références Kotlin aux ressources et principales références XML résolues.

Ces contrôles analysent les sources. Ils ne compilent pas Kotlin, ne lancent pas
l'application et ne valident pas le rendu, TalkBack, les interactions ou les services.

Commande reproductible depuis arbitre/ :

```sh
python3 tests_ui_sandbox.py /chemin/Ningbus-claude-widget-course-arbitrage-y8uo30.zip
```

Tentative de compilation/tests :

```sh
bash gradlew :moteur:test :app:assembleDebug --offline --no-daemon
```

Échec avant exécution des tâches : Gradle 8.9 absent ; le wrapper tente de télécharger
sa distribution même avec --offline. Erreur : java.net.SocketException: Network is unreachable.
Java 17 est présent. adb et gradle ne sont pas disponibles dans PATH.
Le banc Android n'a pas été exécuté faute de chaîne Android utilisable.
Aucun APK ni workflow vert produit. Aucun test moteur annoncé comme passé.
Rendu visuel sur Android et compatibilité Kotlin/Material non vérifiés.

## Validation requise sur GitHub

Sur une branche arbitre-2.0-ui créée depuis le commit de base, importer UNIQUEMENT
les fichiers modifiés sous arbitre/. Ne pas fusionner dans main et ne pas écrire sur claude/*.
Le workflow .github/workflows/arbitre.yml est conservé intact et inclut déjà :

- :moteur:test ;
- :app:assembleDebug ;
- contrôle des permissions réseau de l'APK ;
- banc-essai.sh sur émulateur Android ;
- publication de arbitre-debug-apk et des rapports de tests.

Attendre les deux jobs verts avant de présenter l'APK comme validé. Puis vérifier
manuellement : quatre onglets, rotation, grande police, retour depuis permissions,
réglages persistés, historique ancien/nouveau, curseurs, clavier, arrêt/reprise,
mini-widget €/km, tap/analyse, appui long/diagnostic et capture OCR sans autolecture.
