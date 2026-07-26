# 🧩 RADAR DÉCODÉ

Veille automatique de l'actu. Surveille les grands médias français, repère
les sujets qui montent (une même info chez plusieurs sources) et t'envoie
une alerte Telegram prête à passer en mode Décodé.

Tourne tout seul via GitHub Actions. Zéro serveur, zéro coût.

Ce sous-projet est indépendant du reste du dépôt `ningbus` (TrendTrack) :
il possède son propre workflow, ses propres dépendances et sa propre mémoire.

---

## Ce qu'il fait

1. Toutes les 30 min, il lit les flux RSS de 8 médias.
2. Il regroupe les articles qui parlent du même sujet.
3. Si un sujet apparaît chez **au moins 2 sources** → c'est une tendance.
4. Il t'envoie une alerte Telegram (titre + thèmes + sources + lien).
5. Il retient ce qu'il a déjà envoyé pour ne pas te spammer.

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
→ **New repository secret**. Crée deux secrets :
- `TELEGRAM_TOKEN` = ton token BotFather
- `TELEGRAM_CHAT_ID` = ton chat id

### 4. Activer
- Onglet **Actions** du repo → active les workflows.
- Clique sur "Radar Décodé" → **Run workflow** pour un premier test.
- Tu devrais recevoir une alerte Telegram dans la minute.

C'est tout. Il tourne désormais seul, jour et nuit, en parallèle du reste du repo.

---

## Fichiers

- `radar_decode.py` — le script principal.
- `requirements.txt` — dépendances Python de ce sous-projet.
- `../.github/workflows/radar_decode.yml` — le workflow GitHub Actions (cron 30 min).
- `vus.json` — mémoire des alertes déjà envoyées (généré automatiquement).

---

## Réglages (dans `radar_decode.py`)

| Réglage | Rôle | Défaut |
|---|---|---|
| `SEUIL_ALERTE` | Nb de sources mini pour alerter | 2 |
| `SEUIL_SIMILARITE` | Nb de mots communs = même sujet | 2 |
| `FLUX` | Liste des médias surveillés | 8 sources |
| cron dans `radar_decode.yml` | Fréquence de scan | 30 min |

**Trop d'alertes ?** monte `SEUIL_ALERTE` à 3.
**Pas assez ?** descends `SEUIL_SIMILARITE` à 1 (plus sensible).

---

## Ajouter une source
Dans `radar_decode.py`, ajoute une ligne dans `FLUX` :
```python
"Nom du média": "https://.../rss",
```
Presque tous les médias ont un flux RSS (cherche "nom du média + RSS").
