# I&N RUN Masse — PWA

Application de suivi de prise de masse sur machines : séries, RIR, double
progression, calendrier réel, récap hebdomadaire. Tout est local au téléphone
(localStorage), aucun compte, aucun serveur.

## Les fichiers vont ensemble

Tous au même niveau, dans le même dossier :

```
index.html               ← l'application
sw.js                    ← le service worker (offline)
manifest.json            ← l'installation PWA
icon.svg                 ← l'icône vectorielle
icon-maskable.svg        ← variante « maskable » (Android découpe l'icône)
icon-180.png             ← icône iOS (iOS ignore les SVG)
icon-192.png             ← icône Android
icon-512.png             ← icône haute définition / splash screen
icon-maskable-512.png    ← icône adaptative Android
_headers                 ← en-têtes de cache (Netlify et Cloudflare Pages)
```

Un service worker ne peut pas mettre en cache ce qui se trouve au-dessus de son
propre dossier. Ici tout est relatif (`./`), donc le dossier marche aussi bien à
la racine d'un site que dans un sous-dossier — mais il doit rester entier.

## Hébergement

**HTTPS obligatoire.** Un service worker refuse de s'enregistrer en HTTP simple.

### GitHub Pages — ce dépôt

Le dossier est publié dans `docs/masse/`. Pages étant servi depuis `/docs` sur
`main`, l'app est en ligne à :

```
https://letxbrace-droid.github.io/Ningbus/masse/
```

Pages ne permet pas de régler les en-têtes HTTP, donc `_headers` y est ignoré.
Ce n'est pas bloquant : l'app enregistre le worker avec `updateViaCache:'none'`,
ce qui donne le même résultat sur les navigateurs récents.

### Netlify / Cloudflare Pages

Glisser-déposer le dossier sur **app.netlify.com/drop**. Le fichier `_headers`
y est lu : c'est lui qui empêche le navigateur de garder l'ancien `sw.js`
pendant 24 h et de bloquer les mises à jour.

À éviter : InfinityFree, 000webhost et compagnie (publicités injectées, HTTPS
capricieux, suspensions arbitraires).

## Mettre à jour l'app

Après chaque modification d'`index.html`, incrémente la version dans `sw.js` :

```js
var VERSION = 'v5';   →   'v6'
```

Sans ça, le téléphone continue de servir l'ancienne version depuis son cache.
Quand la nouvelle version est détectée, un bandeau « Recharger » apparaît en bas
de l'écran — tu recharges quand tu veux, jamais en pleine séance. Le bandeau
demande au nouveau worker de prendre la main puis recharge une seule fois.

## Tester en local

```bash
python3 -m http.server 8000
```

Puis `http://localhost:8000`. Le service worker ne fonctionne **pas** en
`file://` — c'est normal, l'app marche quand même, simplement sans cache
offline.

## Le coach planificateur

Deux notions distinctes, volontairement séparées dans le stockage :

- `inrun_trainlog` — les séances **faites**. Toutes les statistiques (volume,
  récap, compteurs de régularité, jours depuis chaque groupe) ne lisent que ça.
- `inrun_plan` — les séances **prévues**, uniquement dans le futur. Purgé
  automatiquement dès qu'un jour est passé, et effacé pour un jour donné dès
  qu'une série y est enregistrée : le prévu devient du fait.

Quand tu touches un jour et choisis un groupe, `verdictJour(date, groupe)`
tranche avant l'enregistrement :

| Verdict | Quand | Ce qui se passe |
| --- | --- | --- |
| `stop` | même groupe à moins de 48 h, avant ou après | le coach explique et propose un autre jour |
| `warn` | 4ᵉ jour d'affilée, groupe au-delà de 22 séries / 7 jours, 3ᵉ séance du même groupe dans la semaine | idem, avec l'option repos |
| `ok` | tout le reste | enregistré sans friction, confirmation en bas de l'écran |

Rien n'est jamais imposé : « Garder quand même » respecte toujours ton choix.

Le même moteur alimente les suggestions sur les jours libres de la bande
hebdo (`→ Tirage`) et le bouton **Propose-moi la semaine**, qui remplit les
7 jours suivants en respectant les 48 h par groupe et en glissant un repos
avant tout 4ᵉ jour consécutif. Il ne touche jamais un jour déjà décidé.

## Sauvegarde

Profil → Exporter. Le JSON contient les clés : carnet, historique 1RM, poids,
profil, réglages, calendrier, planning prévisionnel, séries détaillées avec
RIR, prescriptions ajustées, fatigue.

Fais-le une fois par mois. C'est la seule protection contre un cache vidé ou un
changement de téléphone.

## Ce qui a été corrigé pour rendre la PWA opérationnelle

- **Fichiers PWA réels.** L'ancienne version (`massemachines10.html`) générait le
  manifeste et le service worker depuis des URL `blob:` — refusé par tous les
  navigateurs, erreur avalée, zéro offline et aucune installation possible.
  `manifest.json` et `sw.js` sont maintenant de vrais fichiers.
- **Service worker complet** : précache de l'app, réseau d'abord pour la
  navigation (fraîche en ligne, disponible hors ligne), cache des Google Fonts,
  ménage des anciennes versions, activation contrôlée par l'utilisateur.
- **Bandeau « Recharger » réellement efficace.** Il se contentait de recharger
  la page pendant que l'ancien worker gardait la main : on revenait sur la même
  version. Il demande maintenant `SKIP_WAITING` puis recharge une fois.
- **Icônes PNG.** iOS ignore les SVG en `apple-touch-icon` et collait une capture
  d'écran de la page sur l'écran d'accueil.
- **Groupe « Bras » rattaché aux compteurs.** Les stations `up1…up6` ne
  correspondaient à aucun groupe (`upper`) : le travail des bras n'entrait ni
  dans le volume hebdomadaire, ni dans le récap, et la séance n'était jamais
  marquée automatiquement dans le calendrier.
- **« Effacer tout le carnet de bord » efface vraiment.** Seule l'ancienne clé
  était supprimée : les séries revenaient au premier rechargement. Les perfs
  (séries, dernières perfs, historique 1RM) sont maintenant effacées ensemble,
  le calendrier, le poids et les réglages sont conservés.
- **Polices non bloquantes.** Une feuille de style distante retardait
  l'affichage quand le réseau de la salle est mauvais.
- **Navigation par ancre** (`#push`, `#plan`…), ce qui fait marcher les
  raccourcis d'icône du manifeste.
