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

- `radar_decode.py` — le script principal (détection + génération du post).
- `requirements.txt` — dépendances Python de ce sous-projet.
- `../.github/workflows/radar_decode.yml` — le workflow GitHub Actions (cron 30 min).
- `vus.json` — mémoire des alertes déjà envoyées (généré automatiquement).

---

## Réglages (dans `radar_decode.py`)

| Réglage | Rôle | Défaut |
|---|---|---|
| `SEUIL_ALERTE` | Nb de sources mini pour alerter | 2 |
| `SEUIL_SIMILARITE` | Nb de mots communs = même sujet | 2 |
| `LONGUEUR_POST_MAX` | Longueur cible du post Décodé | 280 |
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
