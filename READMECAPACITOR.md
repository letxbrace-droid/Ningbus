# Planning Chauffeur — Wrap PWA → Native (Capacitor)

App de planning bus / VTC avec OCR, météo J+1, et **alarmes natives** pour les prises de service.

---

## 1. Architecture

- **Source unique** : `www/index.html` (le HTML/CSS/JS que tu connais déjà)
- **Détection runtime** : le code détecte `window.Capacitor.isNativePlatform()` au chargement
  - Si **web** (PWA) → utilise les notifications navigateur + export `.ics`
  - Si **natif** (iOS/Android) → utilise le plugin `LocalNotifications` (alarmes système réelles)
- Le même HTML fonctionne dans les deux modes, **zéro réécriture**

---

## 2. Prérequis

| Pour Android | Pour iOS |
|---|---|
| Node.js 18+ | macOS uniquement |
| Java JDK 17 | Xcode 15+ |
| Android Studio | CocoaPods (`sudo gem install cocoapods`) |
| Android SDK 34+ | Compte Apple Developer (99€/an pour distribution) |

> 💡 Tu peux faire **Android depuis Linux/Windows/Mac**. Pour iOS il **faut un Mac** (ou un service CI comme Codemagic / GitHub Actions avec runner macOS).

---

## 3. Setup initial (une seule fois)

```bash
# 1. Clone le repo et entre dedans
git clone https://github.com/letxbrace-droid/inrunparis.git
cd inrunparis

# 2. Installe les dépendances
npm install

# 3. Ajoute les plateformes
npx cap add android
npx cap add ios       # (Mac uniquement)

# 4. Synchronise les assets web vers les projets natifs
npx cap sync
```

---

## 4. Build Android

```bash
# Ouvre Android Studio
npx cap open android

# Dans Android Studio :
# - Build > Build Bundle(s) / APK(s) > Build APK(s)
# - L'APK est généré dans android/app/build/outputs/apk/debug/
# - Pour un build release signé : Build > Generate Signed Bundle / APK
```

**Configuration alarmes Android** — à ajouter dans `android/app/src/main/AndroidManifest.xml` :

```xml
<!-- Permissions pour réveils fiables, même en doze mode -->
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM"/>
<uses-permission android:name="android.permission.USE_EXACT_ALARM"/>
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
<uses-permission android:name="android.permission.WAKE_LOCK"/>
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>
<uses-permission android:name="android.permission.VIBRATE"/>
```

> ⚠️ Sur Android 13+ tu dois aussi demander la permission notifications au runtime (déjà géré par le plugin `LocalNotifications`).
> Sur Android 12+ pour `SCHEDULE_EXACT_ALARM` : l'utilisateur doit l'activer manuellement dans Réglages → App → Alarmes et rappels.

---

## 5. Build iOS

```bash
# Ouvre Xcode
npx cap open ios

# Dans Xcode :
# - Sélectionne ton équipe (Signing & Capabilities)
# - Connecte un iPhone OU choisis un simulateur
# - Cmd+R pour build & run
```

**Configuration alarmes iOS** — à ajouter dans `ios/App/App/Info.plist` :

```xml
<key>NSUserNotificationsUsageDescription</key>
<string>Planning Chauffeur utilise les notifications pour les réveils de prise de service.</string>
<key>NSLocationWhenInUseUsageDescription</key>
<string>Localisation utilisée pour la météo J+1.</string>
<key>NSCameraUsageDescription</key>
<string>Accès caméra pour photographier le planning.</string>
```

> ⚠️ iOS limite à **64 notifications planifiées** simultanées par app. Le code en programme max 60 (les plus proches en priorité).

---

## 6. Workflow de mise à jour

Quand tu modifies `www/index.html` :

```bash
npx cap sync     # copie les nouveaux assets vers iOS + Android
npx cap run android   # ou run ios
```

Pas besoin de re-build complet, juste un `cap sync`.

---

## 7. Distribution (pour les 600 chauffeurs plus tard)

| Canal | Coût | Pour qui |
|---|---|---|
| **APK direct** (sideload) | gratuit | Tests internes, flotte privée |
| **Google Play** | 25€ une fois | Distribution publique Android |
| **Apple App Store** | 99€/an | Distribution publique iOS |
| **Apple Enterprise** | 299€/an | Distribution interne entreprise iOS sans App Store |
| **TestFlight** | inclus | Bêta iOS jusqu'à 10000 testeurs |

**Recommandation pour V1** : APK + TestFlight. Tu maîtrises la diffusion sans dépendre des stores.

---

## 8. Icônes & splash

Génère icônes/splash automatiquement avec `@capacitor/assets` :

```bash
npm install --save-dev @capacitor/assets
mkdir -p assets

# Place dans assets/ :
#   icon.png   (1024x1024, fond transparent ou plein)
#   splash.png (2732x2732, logo centré)

npx capacitor-assets generate
```

---

## 9. Plugins natifs disponibles (déjà installés)

| Plugin | Usage |
|---|---|
| `@capacitor/local-notifications` | Alarmes système (le critique) |
| `@capacitor/geolocation` | GPS natif (plus précis que web) |
| `@capacitor/camera` | Photo direct depuis l'app pour scan planning |
| `@capacitor/filesystem` | Stockage robuste si localStorage saturé |
| `@capacitor/splash-screen` | Écran de lancement |
| `@capacitor/status-bar` | Style barre statut (déjà sombre dans la config) |

Pour utiliser dans ton code (si tu veux étendre) :

```javascript
const { Camera } = window.Capacitor.Plugins;
const photo = await Camera.getPhoto({
  quality: 90,
  allowEditing: false,
  resultType: 'base64',
  source: 'CAMERA'
});
// photo.base64String → tu le passes à Tesseract directement
```

---

## 10. Prochaines étapes recommandées

1. ✅ Tester le build Android sur ton téléphone (debug APK suffit)
2. ✅ Vérifier que les alarmes sonnent en mode silencieux + écran éteint
3. 🔄 Ajouter le scan caméra direct (plus rapide qu'upload)
4. 🔄 Ajouter une intégration directe avec ton site I&N RUN (récupération automatique des réservations VTC pour cross-référencer avec planning bus)
5. 🔄 Backend FastAPI pour multi-utilisateurs si tu passes à 600 chauffeurs
