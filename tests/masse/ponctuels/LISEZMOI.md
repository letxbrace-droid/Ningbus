# Tests ponctuels — hors batterie

Ces trois fichiers ont été écrits pour une migration précise et ne font
**pas** partie de `batterie.sh`. Ils ne tournent pas en l'état : chacun a
besoin d'un montage que la suite courante ne fournit pas.

| fichier | ce qu'il vérifie | ce qui lui manque |
|---|---|---|
| `test-cohab.js` | ouvrir l'ancienne PWA RATP ne détruit plus le cache hors ligne de Masse | un second serveur sur le port 8766, servant tout `docs/` |
| `test-tombstone.js` | un téléphone qui avait installé la PWA RATP se nettoie seul | idem, plus un instantané de l'ancienne version dans `.sortie/old/` |
| `test-update.js` | le cycle `sw.js` modifié → bandeau → « Recharger » → un seul rechargement | un serveur qui sert successivement l'ancien puis le nouveau `sw.js` |

Ils sont conservés parce qu'ils documentent des contrats réels et qu'ils
resserviront le jour où l'un de ces sujets revient. Les remettre en marche
demande d'écrire le montage manquant, pas de réparer le test.
