# Planning Chauffeur

App PWA + native (iOS/Android via Capacitor) pour gérer le planning chauffeur bus / VTC : **OCR de screenshots**, **alarmes natives** pour les prises de service, **météo J+1**, **contrôles de cohérence** (repos quotidien, durée max, total semaine), **export iCalendar**.

---

## 🚀 Build APK sans rien installer (recommandé)

Tu pousses sur GitHub → la CI compile l'APK → tu télécharges depuis ton téléphone.

### 1. Crée un repo GitHub

Depuis ton phone (app GitHub) ou web :
- Nouveau repo, par ex. `planning-chauffeur`, **Private** ou **Public** au choix

### 2. Pousse le code

Sur Termux ou n'importe quel terminal :

```bash
cd planning-chauffeur
git init
git add .
git commit -m "init"
git branch -M main
git remote add origin git@github.com:TON_USER/planning-chauffeur.git
git push -u origin main
```

### 3. Attends ~5–10 min

Va sur **github.com/TON_USER/planning-chauffeur/actions** → tu vois le workflow `Build Android APK` tourner.

### 4. Télécharge l'APK

À la fin, dans la run, section **Artifacts** → `planning-chauffeur-apk` (ZIP). Télécharge depuis ton téléphone, dézippe, installe l'APK (autorise « Sources inconnues » dans les réglages).

### 5. Active les alarmes

Dans l'app : section **4 / Réveils** → **Activer alarmes natives**. Android te demande la permission notifications + permission alarmes exactes (à activer dans Réglages système).

---

## 🧪 Test PWA en attendant (sans build)

Tu peux tester immédiatement la version web sur GitHub Pages : il suffit de servir `www/index.html` à la racine. Ou ouvrir le fichier en local sur ton téléphone via Termux + un serveur HTTP simple :

```bash
cd planning-chauffeur/www
python3 -m http.server 8080
# puis ouvre http://localhost:8080 dans Chrome
```

⚠️ La version PWA n'a pas les **vraies alarmes natives** — utilise l'export `.ics` vers ton Calendrier en attendant le build APK.

---

## 📂 Structure

```
planning-chauffeur/
├── www/
│   └── index.html              ← l'app entière (1 fichier)
├── .github/workflows/
│   └── build-android.yml       ← CI qui build l'APK
├── package.json                ← deps Capacitor
├── capacitor.config.json       ← config app (id, nom, plugins)
├── .gitignore
└── README.md
```

`android/` et `ios/` ne sont **pas** dans le repo — la CI les régénère à chaque build via `npx cap add android`. Si tu builds en local et veux versionner ces dossiers (recommandé pour personnaliser l'icône, la signature release, etc.), retire-les du `.gitignore`.

---

## 🔧 Build local (si tu préfères)

Si tu as un poste de dev sous la main :

```bash
npm install
npx cap add android
npx cap sync
npx cap open android       # ouvre Android Studio
```

Build dans Android Studio → APK dans `android/app/build/outputs/apk/debug/`.

---

## 🍎 Build iOS

Le workflow CI ne build **pas iOS** par défaut (les runners macOS GitHub coûtent 10× plus cher en minutes). Pour iOS :
- Mac requis avec Xcode
- `npx cap add ios` puis `npx cap open ios`
- Voir `READMECAPACITOR.md` pour les détails (signing, Info.plist)

Pour 600 chauffeurs, viable seulement avec compte Apple Developer (99 €/an) + TestFlight.

---

## 🎛️ Customisation

### Changer l'app id / nom
Édite `capacitor.config.json` :
```json
{
  "appId": "fr.tondomaine.planning",
  "appName": "Mon App"
}
```

### Changer l'icône
Place `assets/icon.png` (1024×1024) à la racine, puis :
```bash
npm install --save-dev @capacitor/assets
npx capacitor-assets generate
```

### Changer la couleur du splash / theme
`capacitor.config.json` → `plugins.SplashScreen.backgroundColor`

---

## 🐛 Debug

| Problème | Solution |
|---|---|
| L'APK s'installe pas | Active « Sources inconnues » dans les réglages Android |
| Les alarmes sonnent pas | Réglages > Apps > Planning Chauffeur > Alarmes et rappels : activer |
| Permission notifications refusée | Réinstalle ou réglages > notifications > activer |
| OCR très lent | Premier lancement : télécharge ~10 Mo de modèle FR, c'est normal. Ensuite cache local. |
| Build CI échoue | Onglet Actions > clique la run en échec > regarde le log. Souvent : Java mémoire (relance), ou conflit Gradle (touche pas, c'est temporaire). |

---

## 📋 Roadmap

- [ ] Scan caméra direct (sans passer par la galerie)
- [ ] Synchronisation entre plusieurs appareils (backend FastAPI)
- [ ] Mode dépôt avec admin (pour rouler sur 600 chauffeurs)
- [ ] Intégration directe planning RATP / Transilien (parsing PDF officiel)
- [ ] Cross-référence avec courses VTC pour optimiser les doubles activités
