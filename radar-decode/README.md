# 🧩 RADAR DÉCODÉ

Veille automatique de l'actu. Surveille les grands médias français, repère
les sujets qui montent (une même info chez plusieurs sources) et t'envoie
une alerte Telegram avec un **post "Décodé" prêt à copier-coller sur X**,
généré par l'API Gemini.

Tourne tout seul via GitHub Actions. Zéro serveur.

Ce sous-projet est indépendant du reste du dépôt `ningbus` :
il possède son propre workflow, ses propres dépendances et sa propre mémoire.

---

## Ce qu'il fait

1. Toutes les 30 min, il lit les flux RSS de 8 médias.
2. Il regroupe les articles qui parlent du même sujet.
3. Si un sujet apparaît chez **au moins 2 sources** → c'est une tendance.
4. Il génère un **post "Décodé"** via Gemini (voir plus bas) et te
   l'envoie dans une alerte Telegram.
5. Il retient ce qu'il a déjà envoyé pour ne pas te spammer.

Tu peux aussi taper **`/scan`** dans Telegram pour forcer un passage
immédiat sans attendre le cron (voir plus bas).

---

## Le post "Décodé"

Chaque sujet détecté est envoyé à **Gemini** (`gemini-2.0-flash`, tier
gratuit) avec le titre, les sources qui couvrent déjà le sujet, et le texte
de l'article (extrait des balises `<p>` de la page). Un prompt système
strict impose le gabarit et les règles éditoriales :

```
{emoji} ZONE/THÈME — Accroche factuelle

Où on en est :
→ fait 1 (avec chiffre si disponible)
→ fait 2
→ fait 3

Pourquoi ça compte : enjeu, neutre

On suit. 🧩
```

- **Emoji** selon le sujet : 🔴 urgence/drame, 🌾 politique, 🚴 sport,
  💶 économie, 🌍 international, 🧩 par défaut.
- **Neutralité imposée** : aucun jugement, aucun adjectif d'opinion.
- **Aucun chiffre inventé** : Gemini n'utilise que ce qui est dans
  l'article. S'il manque un fait, il met 2 flèches au lieu de 3 —
  jamais de `[à compléter]`.
- **Aucun hashtag.**
- Post visé sous **280 caractères**.
- Dans l'alerte Telegram, le post est encadré par `━━━ PRÊT À PUBLIER ━━━`
  et suivi d'un rappel : `⚠️ Vérifie les chiffres avant de publier.`

**Repli automatique** (jamais de plantage du radar) :
- Article inaccessible (timeout, 403...) → Gemini génère à partir du titre seul.
- Appel Gemini en échec (quota dépassé, réseau, clé absente) → le post
  retombe sur le titre brut + le lien de l'article.

### Secret requis : `GEMINI_API_KEY`

1. Crée une clé gratuite sur [Google AI Studio](https://aistudio.google.com/apikey).
2. Dans le repo → **Settings → Secrets and variables → Actions → New
   repository secret** : nom `GEMINI_API_KEY`, valeur = la clé.

Sans ce secret, le post retombe systématiquement sur le titre + lien (pas
d'erreur, juste un post moins riche).

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
→ **New repository secret**. Crée ces secrets :
- `TELEGRAM_TOKEN` = ton token BotFather
- `TELEGRAM_CHAT_ID` = ton chat id
- `GEMINI_API_KEY` = ta clé Google AI Studio (voir section post plus haut) —
  optionnel, sinon repli sur titre + lien.
- `GH_DISPATCH_TOKEN` = ton token GitHub (voir section `/scan` ci-dessus) —
  optionnel si tu ne veux que le cron automatique.

### 4. Activer
- Onglet **Actions** du repo → active les workflows.
- Clique sur "Radar Décodé" → **Run workflow** pour un premier test.
- Tu devrais recevoir une alerte Telegram dans la minute.

C'est tout. Il tourne désormais seul, jour et nuit, en parallèle du reste du repo.

---

## Fichiers

- `radar_decode.py` — le script principal (détection + génération du post via Gemini).
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
| `GEMINI_MODELE` | Modèle Gemini utilisé | `gemini-2.0-flash` |
| `GEMINI_DELAI_MIN` | Délai mini (s) entre deux appels Gemini | 4 |
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
