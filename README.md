# ningbus

Ce dépôt contient :

- **`radar-decode/`** — Radar Décodé : veille automatique de l'actu (RSS multi-médias + alerte Telegram). Voir `radar-decode/README.md`.
- **`docs/masse/`** — **I&N RUN Masse**, PWA de suivi d'entraînement (prise de masse sur machines), déployée via GitHub Pages : installable, fonctionne hors ligne. Voir `docs/masse/LISEZMOI.md`.

Le projet TrendTrack (scraper Meta Ads) qui occupait précédemment ce dépôt a été retiré.

La PWA de suivi de défauts bus (RATP Cap Saclay) a également été retirée. Seul
`docs/defauts-sw.js` subsiste : c'est une pierre tombale qui désinstalle le
service worker resté enregistré sur les téléphones où l'app avait été
installée. Il pourra être supprimé une fois qu'il n'y a plus d'installation en
circulation.
