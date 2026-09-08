/* Le système de couleurs est appliqué — ce test vérifie qu'il le reste.
   Sans lui, la migration est un nettoyage ponctuel : la prochaine règle
   CSS écrite à la main remettra un rgba(175,250,1,.2) et personne ne le
   verra. Avec lui, « appliqué » devient une propriété contrôlée.
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

/* ---------- 1. l'étage 3 n'est pas revenu ---------- */
const HERITE = ['bg','bg2','panel','panel2','line','line2','ink','mut','dim','steel',
                'blue','blue-l','cyan','cyan-glow','gauge','acc','acc-d','mint','mint-d','amber'];
const revenus = HERITE.filter(n => new RegExp('--' + n + '\\s*:').test(root));
check('aucun nom hérité n\'est redéfini', revenus.length === 0, revenus.join(', '));

const lus = HERITE.filter(n => new RegExp('var\\(--' + n + '[,)]').test(src));
check('aucun nom hérité n\'est lu', lus.length === 0, lus.join(', '));

/* ---------- 2. aucune couleur de la palette recopiée ---------- */
/* Les primitives, relevées dans :root — c'est la palette qui définit ce
   qu'on n'a pas le droit de réécrire à la main, pas une liste figée. */
const palette = {};
for (const m of root.matchAll(/--([a-z0-9-]+):(#[0-9a-fA-F]{6})\b/g)) {
  const h = m[2].slice(1);
  palette[[0, 2, 4].map(i => parseInt(h.slice(i, i + 2), 16)).join(',')] = m[1];
}
const recopiees = [];
for (const m of corps.matchAll(/rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)/g)) {
  const c = [m[1], m[2], m[3]].join(',');
  /* le noir et le blanc purs restent des voiles neutres (ombres, reflets) :
     ils ne disent pas une couleur de marque, ils disent une profondeur */
  if (c === '0,0,0' || c === '255,255,255') continue;
  if (palette[c]) recopiees.push(`rgb(${c}) = --${palette[c]}`);
  else recopiees.push(`rgb(${c}) n'est AUCUN jeton (couleur orpheline)`);
}
check('aucune couleur n\'est écrite en dur dans le CSS',
      recopiees.length === 0, [...new Set(recopiees)].join(' | '));

/* ---------- 3. aucun hex hors de la palette ---------- */
const hex = [...corps.matchAll(/#[0-9a-fA-F]{3,6}\b/g)].map(m => m[0])
  /* #000 dans un mask-image veut dire « opaque », pas une couleur */
  .filter(h => h.toLowerCase() !== '#000');
check('aucun hex en dur dans le corps du CSS', hex.length === 0,
      [...new Set(hex)].join(', '));

/* ---------- 4. tout var(--x) mène quelque part ---------- */
/* Deux façons légitimes : le jeton est défini dans :root, ou bien la
   lecture porte une valeur de repli — c'est le cas des --m-* de la carte
   musculaire et du --gc du calendrier, posés en style inline par le JS et
   toujours lus en var(--m-pecs,var(--mus-repos)). Une lecture SANS repli
   d'un jeton jamais défini, elle, ne rend rien du tout.
   On retire les commentaires d'abord : ils citent des noms en exemple. */
const sansComm = src.replace(/\/\*[\s\S]*?\*\//g, '');
const definis = new Set([...root.matchAll(/(--[a-z0-9-]+)\s*:/g)].map(m => m[1]));
const pendants = [...new Set(
  [...sansComm.matchAll(/var\(\s*(--[a-z0-9-]+)\s*([,)])/g)]
    .filter(m => m[2] === ')')          /* sans repli */
    .map(m => m[1]))].filter(n => !definis.has(n));
check('toute lecture sans repli vise un jeton défini',
      pendants.length === 0, pendants.join(', '));

/* ---------- 5. les voiles passent par color-mix ---------- */
const voiles = (corps.match(/color-mix\(in srgb,\s*var\(--/g) || []).length;
check('les voiles sont construits sur un jeton', voiles >= 60, voiles + ' color-mix');

/* ---------- 5 bis. le corps du CSS ne lit pas l'étage 1 ---------- */
/* C'est la règle que le système énonce lui-même : « étage 2, le sens —
   c'est le seul étage que le CSS lit ». Un voile posé sur var(--lime) est
   un jeton, mais c'est encore une primitive : il dit une couleur, pas un
   rôle. Seuls les jetons catégoriels (--s-*, --mus-*) échappent à ça :
   ils sont une famille sémantique à eux seuls, l'identité d'une séance. */
const PRIM = ['n-000','n-900','n-800','n-700','n-600','n-500','n-450','n-400',
              'n-100','n-200','n-300','lime','lime-40','rouge','rouge-i',
              'ambre','ambre-i','cyan-f','cyan-i'];
const fuites = PRIM.filter(p => new RegExp('var\\(--' + p + '[,)]').test(corps));
check('le corps du CSS ne lit aucune primitive', fuites.length === 0,
      fuites.map(p => '--' + p).join(', '));

/* ---------- 6. le corps du CSS lit bien l'étage 2 ---------- */
const SEM = ['surface-0','surface-1','surface-2','surface-3','bord','bord-fort','bord-champ',
             'text','text-2','text-3','accent','accent-ink','accent-on','accent-btn',
             'success','success-ink','info','info-ink','info-glow','warn','warn-ink',
             'danger','danger-ink'];
const sem = SEM.reduce((n, x) =>
  n + (corps.match(new RegExp('var\\(--' + x + '[,)]', 'g')) || []).length, 0);
check('le CSS lit l\'étage sémantique', sem >= 400, sem + ' lectures');

/* ---------- 7. le commentaire d'en-tête dit la vérité ---------- */
check('l\'en-tête ne promet plus trois étages',
      /SYSTÈME DE COULEURS — deux étages/.test(style) && !/^\s*étage 3\s+hérité/m.test(style));

console.log('=== PASS (' + ok.length + ') ===');
ok.forEach(s => console.log('  ✓ ' + s));
if (bad.length) {
  console.log('\n=== FAIL (' + bad.length + ') ===');
  bad.forEach(s => console.log('  ✗ ' + s));
}
process.exit(bad.length ? 1 : 0);
