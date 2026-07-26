# 🧩 RADAR DÉCODÉ

Veille automatique de l'actu. Surveille les grands médias français, repère
les sujets qui montent (une même info chez plusieurs sources) et t'envoie
une alerte Telegram avec un **post "Décodé" prêt à copier-coller sur X**.

Tourne tout seul via GitHub Actions. Zéro serveur, zéro coût.

Ce sous-projet est indépendant du reste du dépôt `ningbus` :
il possède son propre workflow, ses propres dépendances et sa propre mémoire.

---

## Ce qu'il fait

1. Toutes les 30 min, il lit les flux RSS de 8 médias.
2. Il regroupe les articles qui parlent du même sujet.
3. Si un sujet apparaît chez **au moins 2 sources** → c'est une tendance.
4. Il génère un **post "Décodé"** prêt à publier sur X (voir plus bas) et
   te l'envoie dans une alerte Telegram.
5. Il retient ce qu'il a déjà envoyé pour ne pas te spammer.

Tu peux aussi taper **`/scan`** dans Telegram pour forcer un passage
immédiat sans attendre le cron (voir plus bas).

---

## Le post "Décodé"

Chaque sujet détecté est transformé en post prêt à copier-coller, sans IA
et sans API payante — uniquement du texte déjà public (flux RSS + page de
l'article) passé dans un gabarit fixe :

```
{emoji} ZONE — Accroche du sujet

Où on en est :
→ fait 1
→ fait 2
→ fait 3

Pourquoi ça compte : enjeu

On suit. 🧩
```

- **Emoji** choisi selon le thème détecté dans les mots-clés du sujet :
  🔴 urgence/drame, 🌍 monde, 🌾 politique, 💶 éco, 🚴 sport, 🧩 par défaut.
- **ZONE** = la commune/le département repéré en tête du titre (ex. "NICE.",
  "Loire-Atlantique :"), sinon le mot-clé le plus saillant du sujet.
- **Faits/enjeu** = extraits du chapô RSS puis, si besoin, de la page de
  l'article (meta description, sinon premier paragraphe). Les phrases avec
  un chiffre clé (%, €, km, ha, habitants...) passent toujours en premier.
- **Aucun hashtag.** Aucun chiffre inventé : ce qui n'est pas trouvé dans le
  texte source reste `[à compléter]`.
- Le post vise **moins de 280 caractères** ; au-delà, les lignes sont
  raccourcies proprement (jamais coupées en plein milieu d'un mot).
- Dans l'alerte Telegram, le post est encadré par `━━━ PRÊT À PUBLIER ━━━`
  et suivi d'un rappel : `⚠️ Vérifie les chiffres avant de publier.`

---

## La commande `/scan`

Le radar n'a pas de serveur, donc pas de vrai webhook Telegram. La commande
`/scan` marche par **sondage** : une deuxième Action GitHub
(`radar_listener.yml`) tourne toutes les ~5 minutes, regarde si tu as tapé
`/scan` depuis le dernier passage, et si oui déclenche immédiatement un run
de `radar_decode.yml` via l'API GitHub. Compte **jusqu'à ~5 minutes** de
délai avant le déclenchement (le temps que le sondage passe), puis le scan
lui-même prend une minute ou deux.

Ça veut dire : **tu n'as jamais besoin de cliquer sur "Run workflow"** — ni
pour un scan automatique (cron 30 min), ni pour un scan à la demande (`/scan`
depuis Telegram).

### Secret supplémentaire requis : `GH_DISPATCH_TOKEN`

L'API `workflow_dispatch` refuse le jeton automatique `GITHUB_TOKEN` (pour
éviter les déclenchements en boucle), il faut donc un jeton personnel :

1. GitHub → **Settings** (ton compte, pas le repo) → **Developer settings**
   → **Personal access tokens** → **Fine-grained tokens** → **Generate new token**.
2. Restreins-le à ce repo (`ningbus`), avec la permission **Actions : Read and write**.
3. Copie le token, puis dans le repo → **Settings → Secrets and variables →
   Actions → New repository secret** : nom `GH_DISPATCH_TOKEN`, valeur = le token.
4. Active le workflow **"Radar Décodé — écouteur Telegram"** dans l'onglet Actions.

Sans ce secret, `/scan` reçoit une réponse d'avertissement au lieu de lancer le scan.

---

## Mise en route (5 min, une seule fois)

### 1. Créer ton bot Telegram
- Sur Telegram, écris à **@BotFather**.
- Tape `/newbot`, choisis un nom (ex. "Radar Décodé").
- Il te donne un **TOKEN** (garde-le).

### 2. Trouver ton CHAT_ID
- Écris un message à ton nouveau bot (n'importe quoi).
- Ouvre dans un navigateur :
  `https://api.telegram.org/bot<TON_TOKEN>/getUpdates`
- Cherche `"chat":{"id":123456789` → c'est ton **CHAT_ID**.

### 3. Ajouter tes secrets
Dans ce repo GitHub → **Settings → Secrets and variables → Actions**
→ **New repository secret**. Crée trois secrets :
- `TELEGRAM_TOKEN` = ton token BotFather
- `TELEGRAM_CHAT_ID` = ton chat id
- `GH_DISPATCH_TOKEN` = ton token GitHub (voir section `/scan` ci-dessus) —
  optionnel si tu ne veux que le cron automatique.

### 4. Activer
- Onglet **Actions** du repo → active les workflows.
- Clique sur "Radar Décodé" → **Run workflow** pour un premier test.
- Tu devrais recevoir une alerte Telegram dans la minute.

C'est tout. Il tourne désormais seul, jour et nuit, en parallèle du reste du repo.

---

## Fichiers

- `radar_decode.py` — le script principal (détection + génération du post).
- `telegram_listener.py` — l'écouteur de la commande `/scan`.
- `requirements.txt` — dépendances Python de ce sous-projet.
- `../.github/workflows/radar_decode.yml` — le workflow principal (cron 30 min).
- `../.github/workflows/radar_listener.yml` — le workflow d'écoute `/scan` (cron 5 min).
- `vus.json` — mémoire des alertes déjà envoyées (généré automatiquement).
- `telegram_offset.json` — curseur des messages Telegram déjà lus (généré automatiquement).

---

## Réglages (dans `radar_decode.py`)

| Réglage | Rôle | Défaut |
|---|---|---|
| `SEUIL_ALERTE` | Nb de sources mini pour alerter | 2 |
| `SEUIL_SIMILARITE` | Nb de mots communs = même sujet | 2 |
| `LONGUEUR_POST_MAX` | Longueur cible du post Décodé | 280 |
| `FLUX` | Liste des médias surveillés | 8 sources |
| cron dans `radar_decode.yml` | Fréquence de scan | 30 min |
| cron dans `radar_listener.yml` | Fréquence de sondage `/scan` | 5 min |

**Trop d'alertes ?** monte `SEUIL_ALERTE` à 3.
**Pas assez ?** descends `SEUIL_SIMILARITE` à 1 (plus sensible).

---

## Ajouter une source
Dans `radar_decode.py`, ajoute une ligne dans `FLUX` :
```python
"Nom du média": "https://.../rss",
```
Presque tous les médias ont un flux RSS (cherche "nom du média + RSS").
