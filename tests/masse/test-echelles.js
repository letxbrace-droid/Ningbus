/* Les échelles n'existaient pas ; maintenant qu'elles existent, ce test
   les tient. Sans lui, la prochaine règle écrite à la main remettra un
   font-size:13.5px et on repartira vers les 27 valeurs.
   Tout se lit dans la source : aucun navigateur nécessaire. */
const fs = require('fs');
const CTX = require('./contexte');
const SRC = CTX.APP;
const src = fs.readFileSync(SRC, 'utf8');

const ok = [], bad = [];
const check = (nom, cond, detail) =>
  (cond ? ok : bad).push(nom + (detail ? ' — ' + detail : ''));

const style = src.slice(src.indexOf('<style>'), src.indexOf('</style>'));
const finRoot = style.indexOf('\n}', style.indexOf(':root{'));
const root = style.slice(0, finRoot);
const corps = style.slice(finRoot);

const RAYONS  = [4, 8, 12, 16, 20];
const TAILLES = [11, 12, 13, 14, 15, 16, 18, 20, 23, 26, 30, 34];

/* ---------- 1. les échelles sont déclarées, et telles quelles ---------- */
const lit = (n) => {
  const m = root.match(new RegExp('--' + n + ':\\s*([\\d.]+)px'));
  return m ? parseFloat(m[1]) : null;
};
const r = RAYONS.map((_, i) => lit('r-' + (i + 1)));
const t = TAILLES.map((_, i) => lit('t-' + (i + 1)));
check('l\'échelle des rayons est celle attendue',
      JSON.stringify(r) === JSON.stringify(RAYONS), r.join(' · '));
check('l\'échelle des tailles est celle attendue',
      JSON.stringify(t) === JSON.stringify(TAILLES), t.join(' · '));
check('aucune marche en trop',
      !root.match(/--r-6\s*:/) && !root.match(/--t-13\s*:/));

/* ---------- 2. plus une seule valeur nue ---------- */
/* clamp() est la seule exception admise : les deux bornes du titre
   décrivent une plage fluide, pas une taille sur l'échelle. */
const nues = [];
for (const m of corps.matchAll(/\b(border-radius|font-size)\s*:([^;{}]+)/g)) {
  if (m[2].includes('clamp(')) continue;
  for (const x of m[2].matchAll(/([\d.]+)px/g)) nues.push(`${m[1]} ${x[1]}px`);
}
check('aucun rayon ni aucune taille écrits en pixels',
      nues.length === 0, [...new Set(nues)].join(', '));

/* ---------- 3. les jetons sont réellement lus ---------- */
const usesR = (corps.match(/var\(--r-\d+\)/g) || []).length;
const usesT = (corps.match(/var\(--t-\d+\)/g) || []).length;
check('les rayons passent par l\'échelle', usesR >= 75, usesR + ' lectures');
check('les tailles passent par l\'échelle', usesT >= 180, usesT + ' lectures');

/* ---------- 4. les espacements tiennent la grille de 2 ---------- */
/* Sous 2 px on ne parle plus d'espacement mais de calage optique — un
   margin-left:1px décale un glyphe d'un cheveu, l'arrondir le supprime. */
const impairs = [];
for (const m of corps.matchAll(
     /\b(gap|row-gap|column-gap|padding|margin)(?:-top|-right|-bottom|-left)?\s*:([^;{}]+)/g)) {
  if (m[2].includes('var(')) continue;
  for (const x of m[2].matchAll(/(?<![\w.])(\d+(?:\.\d+)?)px/g)) {
    const v = parseFloat(x[1]);
    if (v >= 2 && v % 2 !== 0) impairs.push(`${m[1]}:${v}px`);
  }
}
check('les espacements sont sur la grille de 2 px',
      impairs.length === 0, [...new Set(impairs)].join(', '));

/* ---------- 5. le nombre de valeurs a bien fondu ---------- */
const distinctes = (motif) => {
  const s = new Set();
  for (const m of corps.matchAll(motif))
    for (const x of m[2].matchAll(/([\d.]+)px/g)) s.add(x[1]);
  return s.size;
};
const espDist = (() => {
  const s = new Set();
  for (const m of corps.matchAll(
       /\b(gap|row-gap|column-gap|padding|margin)(?:-top|-right|-bottom|-left)?\s*:([^;{}]+)/g))
    for (const x of m[2].matchAll(/(?<![\w.])(\d+(?:\.\d+)?)px/g)) s.add(x[1]);
  return s.size;
})();
/* Garde-fou contre la dérive lente. La grille de 2 a fait tomber les
   espacements de 38 valeurs distinctes à 18 (17 paires de 2 à 80 px, plus
   le 1 px des calages optiques). Le seuil est à 20 : deux marches de
   marge pour un vrai besoin, pas assez pour recommencer un continuum.
   C'est le contrôle 4 qui tient la règle ; celui-ci tient la quantité. */
check('les espacements ne sont plus un continuum', espDist <= 20, espDist + ' valeurs distinctes');

/* ---------- 6. le bloc dit ce qu'il fait ---------- */
check('l\'échelle est documentée dans :root',
      /ÉCHELLES — rayons et tailles de texte/.test(root));

console.log('=== PASS (' + ok.length + ') ===');
ok.forEach(s => console.log('  ✓ ' + s));
if (bad.length) {
  console.log('\n=== FAIL (' + bad.length + ') ===');
  bad.forEach(s => console.log('  ✗ ' + s));
}
process.exit(bad.length ? 1 : 0);
