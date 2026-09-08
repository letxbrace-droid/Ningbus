/* Les chemins, en un seul endroit.
   Les tests vivaient dans un dossier temporaire et codaient tout en dur :
   le binaire du navigateur, le chemin de l'app, le port du serveur. Rien de
   tout ça n'existe sur une autre machine. Ce module les résout, pour que la
   suite tourne partout où le dépôt est cloné. */
const fs = require('fs');
const path = require('path');

/* la racine du dépôt : deux niveaux au-dessus de tests/masse/ */
const RACINE = path.resolve(__dirname, '..', '..');
const MASSE  = path.join(RACINE, 'docs', 'masse');

/* Chromium : la variable d'environnement gagne, sinon on cherche le binaire
   fourni par Playwright (le nom du dossier porte un numéro de build qui
   change à chaque mise à jour — on ne peut pas le figer), sinon on laisse
   Playwright se débrouiller avec son propre navigateur. */
function trouverChromium() {
  if (process.env.CHROMIUM) return process.env.CHROMIUM;
  const base = process.env.PLAYWRIGHT_BROWSERS_PATH || '/opt/pw-browsers';
  try {
    const dossier = fs.readdirSync(base).filter(d => d.startsWith('chromium')).sort().pop();
    if (dossier) {
      for (const suite of ['chrome-linux/chrome', 'chrome-linux/headless_shell', 'chrome']) {
        const p = path.join(base, dossier, suite);
        if (fs.existsSync(p)) return p;
      }
    }
  } catch (e) { /* pas de dossier Playwright : on laissera undefined */ }
  return undefined;   /* Playwright utilisera son navigateur par défaut */
}

const PORT = Number(process.env.PORT_TEST || 8765);

module.exports = {
  RACINE,
  MASSE,
  APP:   path.join(MASSE, 'index.html'),
  SW:    path.join(MASSE, 'sw.js'),
  BASE:  'http://127.0.0.1:' + PORT,
  PORT,
  CHROMIUM: trouverChromium(),
  SORTIE: process.env.SORTIE_TEST || path.join(__dirname, '.sortie'),
  FIXTURES: path.join(__dirname, 'fixtures'),
};
