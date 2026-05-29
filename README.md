# TrendTrack · Angle Intelligence

Un tracker d'annonces Meta + analyseur d'angles marketing, entièrement gratuit, déployé sur GitHub Pages & GitHub Actions.

Il scrape la Meta Ad Library, classe chaque pub par **angle marketing** via Claude AI, détecte les **niches sous-exploitées** (haut potentiel, faible concurrence), et propose des **produits Shopify** à tester.

---

## Architecture

```
Meta Ad Library (Playwright)
        │
        ▼
  angle_analyzer.py  ←  Claude API (Sonnet)
        │
        ▼
  angle_aggregator.py  →  KPIs + Gap detection
        │
        ▼
  product_recommender.py  →  DuckDuckGo + /products.json
        │
        ▼
  data/latest_analysis.json  (commit auto)
        │
        ▼
  docs/index.html  ←  Dashboard PWA (GitHub Pages)
```

GitHub Actions orchestre tout en cron quotidien (06h UTC).

---

## Setup (15 minutes)

### 1. Fork le repo

```bash
# Depuis GitHub : bouton "Fork" en haut à droite
# Puis clone local :
git clone https://github.com/TON_USERNAME/ningbus
cd ningbus
```

### 2. Ajouter les GitHub Secrets

Dans ton repo → **Settings → Secrets and variables → Actions → New repository secret** :

| Secret | Obligatoire | Description |
|--------|-------------|-------------|
| `CLAUDE_API_KEY` | ✅ | Clé Anthropic ([console.anthropic.com](https://console.anthropic.com/)) |
| `BRIGHT_DATA_TOKEN` | ⚡ Recommandé | Token Bright Data pour proxy SOCKS5 (évite les bans Meta) |
| `SERPAPI_KEY` | ❌ Optionnel | Fallback pour la recherche produits |

### 3. Activer GitHub Pages

**Settings → Pages → Source : Deploy from a branch → Branch: `main` → Folder: `/docs`**

Ton dashboard sera disponible sur : `https://TON_USERNAME.github.io/ningbus/`

### 4. Mettre à jour la config du dashboard

Dans `docs/index.html`, ligne 4–5, remplace :

```js
const GITHUB_USER = 'letxbrace-droid';
const GITHUB_REPO = 'ningbus';
```

par ton nom d'utilisateur et le nom de ton repo forké.

### 5. Activer le workflow

**Actions → (onglet) → Enable workflows**

---

## Usage

### Lancement automatique

Le workflow tourne chaque jour à **06h00 UTC** et scrape toutes les niches listées dans `scraper/config.yaml`.

### Lancement manuel

**Actions → Scrape & Analyze Meta Ads → Run workflow** 

Paramètres disponibles :
- `niche` : mot-clé à scraper (ex: `"collagen skin"`)
- `country` : code pays (défaut: `FR`)
- `max_ads` : limite d'annonces (défaut: `100`)

### Ajouter des niches

Édit `scraper/config.yaml` :

```yaml
niches:
  - "foot wellness"
  - "ma nouvelle niche"
```

### Dashboard

Ouvre `https://TON_USERNAME.github.io/ningbus/`

- Onglets par niche
- **Angles inexploités** (carte jaune = opportunité)
- **Performances** de tous les angles détectés
- Sort par viabilité / jours / volume
- Recherche par nom d'angle
- Mode sombre/clair
- Installable comme app (PWA)

---

## Développement local

```bash
# Installer les dépendances
pip install -r requirements.txt
playwright install chromium

# Copier les secrets
cp .env.example .env
# Éditer .env avec tes clés

# Lancer le scraper
python -m scraper.main --niche "foot wellness" --country FR --max-ads 50

# Générer les icônes PWA
python generate_icons.py

# Tester le dashboard en local
cd docs && python -m http.server 8080
# Ouvrir http://localhost:8080
```

---

## Coûts

| Service | Coût |
|---------|------|
| GitHub Actions | **Gratuit** (2 000 min/mois) |
| GitHub Pages | **Gratuit** |
| Claude API (Sonnet) | ~$0.003/ad · 100 ads/niche/jour = **~$0.30/jour** |
| Bright Data proxy | ~10€/mois (optionnel) |
| SerpAPI | Gratuit jusqu'à 100 requêtes/mois |

**Total minimum : ~$9/mois** (Claude API uniquement)

---

## Limitations

- **~50–100 ads/niche** par scrape (vs. 2–3M pour TrendTrack Pro)
- Meta peut changer son GraphQL → le scraper peut nécessiter des mises à jour
- Sans proxy : risque de ban IP temporaire
- Les angles sont classifiés par LLM → quelques erreurs possibles
- Google Trends : signal approximatif (pas l'API officielle)

---

## Contributing

Les PR sont les bienvenues ! Idées d'améliorations :
- Support d'autres plateformes (TikTok Ads, YouTube Ads)
- Meilleure détection de tendances
- Alertes email/Slack sur nouveaux gaps
- Export CSV/Notion

---

## Licence

MIT
