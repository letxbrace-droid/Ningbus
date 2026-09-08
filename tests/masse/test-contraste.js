/* Audit de contraste sur les PIXELS RÉELLEMENT RENDUS.
   On ne calcule pas le contraste depuis les valeurs CSS : le glassmorphisme
   compose une translucidité par-dessus un dégradé radial, plus une texture
   de points. Seul le rendu dit la vérité. On capture donc l'écran, on le
   redécode dans un canvas, et pour chaque élément de texte on prend :
     - la couleur de texte exacte (computed style, elle est opaque)
     - le fond réel = couleur modale des pixels de sa boîte
   Puis WCAG 2 (le standard actuel) et APCA (le candidat WCAG 3, seul
   valable en mode sombre : WCAG 2 surestime le contraste quand les deux
   couleurs sont sombres).
   On modélise en plus le voile de lumière du jour : un reflet ajoute une
   luminance constante aux deux couleurs, ce qui écrase le contraste. */
const { chromium } = require('playwright-core');
const fs = require('fs');
const CTX = require('./contexte');
const B = CTX.BASE;
const OUT = CTX.SORTIE + '/';

/* ---------- métriques ---------- */
const srgb2lin = v => { v /= 255; return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); };
const relLum = c => 0.2126 * srgb2lin(c[0]) + 0.7152 * srgb2lin(c[1]) + 0.0722 * srgb2lin(c[2]);
const wcag = (a, b) => { const L1 = relLum(a), L2 = relLum(b); const hi = Math.max(L1, L2), lo = Math.min(L1, L2);
  return (hi + 0.05) / (lo + 0.05); };

/* APCA 0.1.9 (W3C beta) */
function apcaY(c) {
  const s = v => Math.pow(v / 255, 2.4);
  return 0.2126729 * s(c[0]) + 0.7151522 * s(c[1]) + 0.0721750 * s(c[2]);
}
function apca(txt, bg) {
  let Yt = apcaY(txt), Yb = apcaY(bg);
  Yt = Yt > 0.022 ? Yt : Yt + Math.pow(0.022 - Yt, 1.414);
  Yb = Yb > 0.022 ? Yb : Yb + Math.pow(0.022 - Yb, 1.414);
  if (Math.abs(Yb - Yt) < 0.0005) return 0;
  let C;
  if (Yb > Yt) { C = (Math.pow(Yb, 0.56) - Math.pow(Yt, 0.57)) * 1.14; C = C < 0.1 ? 0 : C - 0.027; }
  else { C = (Math.pow(Yb, 0.65) - Math.pow(Yt, 0.62)) * 1.14; C = C > -0.1 ? 0 : C + 0.027; }
  return C * 100;
}
/* voile de lumière : un reflet ajoute la même luminance partout, en linéaire */
function voile(c, k) {
  /* Modèle physique du reflet : l'écran émet lin ∈ [0,1], le reflet ajoute
     une luminance constante k identique sur tout le panneau, et l'œil
     renormalise sur le nouveau blanc (1 + k). Un lerp vers le blanc, lui,
     écraserait artificiellement le haut de l'échelle. */
  return c.map(v => {
    const lin = (srgb2lin(v) + k) / (1 + k);
    const s = lin <= 0.0031308 ? lin * 12.92 : 1.055 * Math.pow(lin, 1 / 2.4) - 0.055;
    return Math.max(0, Math.min(255, Math.round(s * 255)));
  });
}

/* seuil APCA attendu selon la taille et la graisse du texte
   (table « font-to-lightness » APCA, simplifiée) */
/* Approximation continue de la table « font-to-lightness » d'APCA.
   La fréquence spatiale — à quel point les traits sont fins et serrés —
   pilote la perception du contraste : un même couple de couleurs demande
   bien plus de contraste à 11 px qu'à 24 px. C'est précisément ce que
   WCAG 2 ignore, en donnant le même score à une étiquette de 11 px et à
   un titre de 32 px.
   Modèle : une base décroissante avec la taille, moins un bonus de
   graisse. C'est une interpolation, pas la table officielle — mais elle
   est appliquée à l'identique avant et après, donc la comparaison tient. */
function seuilAPCA(px, poids) {
  const pts = [[12, 100], [14, 90], [16, 82], [18, 76], [21, 70],
               [24, 64], [28, 58], [32, 52], [48, 45]];
  let base;
  if (px <= pts[0][0]) base = pts[0][1] + (pts[0][0] - px) * 3;
  else if (px >= pts[pts.length - 1][0]) base = pts[pts.length - 1][1];
  else {
    for (let i = 0; i < pts.length - 1; i++) {
      if (px >= pts[i][0] && px <= pts[i + 1][0]) {
        const t = (px - pts[i][0]) / (pts[i + 1][0] - pts[i][0]);
        base = pts[i][1] + t * (pts[i + 1][1] - pts[i][1]);
        break;
      }
    }
  }
  const bonus = poids >= 800 ? 16 : poids >= 700 ? 14 : poids >= 600 ? 10 : poids >= 500 ? 5 : 0;
  return Math.round(Math.max(45, base - bonus));
}

(async () => {
  const br = await chromium.launch({ executablePath: CTX.CHROMIUM, args: ['--no-sandbox'] });
  const ctx = await br.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2 });
  const p = await ctx.newPage();
  await p.goto(B + '/index.html', { waitUntil: 'domcontentloaded' });
  await p.waitForTimeout(900);

  /* un profil réaliste pour que toutes les zones aient du contenu */
  await p.evaluate(() => {
    localStorage.clear();
    const t = new Date(), k = n => { const d = new Date(t); d.setDate(t.getDate() - n);
      return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0'); };
    const sets = {}, log = {}, hist = {};
    const rot = [['push1', 'push3', 'push5'], ['pull1', 'pull3'], ['legs1', 'legs3'], ['up1', 'up3'], ['core1', 'core2']];
    let n = 0;
    for (let j = 40; j >= 1; j--) {
      if (j % 7 === 0) { log[k(j)] = 'rest'; continue; }
      const g = rot[n % rot.length]; n++;
      log[k(j)] = g[0].replace(/[0-9]+$/, '').replace(/^up$/, 'upper');
      g.forEach(id => {
        if (!sets[id]) sets[id] = {}; if (!hist[id]) hist[id] = [];
        const w = 40 + (40 - j) * 0.3;
        sets[id][k(j)] = Array.from({ length: 4 }, () => ({ w: Math.round(w), r: 10, rir: 2 }));
        hist[id].push({ d: k(j), w: Math.round(w), r: 10, rm: Math.round(w * 1.333) });
      });
    }
    localStorage.setItem('inrun_sets', JSON.stringify(sets));
    localStorage.setItem('inrun_trainlog', JSON.stringify(log));
    localStorage.setItem('inrun_hist', JSON.stringify(hist));
    localStorage.setItem('inrun_poids', JSON.stringify([{ d: k(20), v: 89 }, { d: k(5), v: 90 }]));
  });
  await p.reload({ waitUntil: 'domcontentloaded' });
  await p.waitForTimeout(1000);
  await p.evaluate(() => { try { closeRecap(); } catch (e) {} });

  /* page de décodage */
  const dec = await ctx.newPage();
  await dec.goto('about:blank');

  const lignes = [];
  const onglets = [['today', 'Aujourd\'hui'], ['semaine', 'Semaine'], ['progres', 'Progrès'], ['moi', 'Moi']];

  for (const [id, nom] of onglets) {
    await p.evaluate(o => { show(o, document.querySelector('[data-nav="' + o + '"]')); window.scrollTo(0, 0); }, id);
    await p.waitForTimeout(500);

    const cibles = await p.evaluate(() => {
      const out = [];
      const vus = new Set();
      document.querySelectorAll('.day.show *').forEach(el => {
        /* uniquement les éléments dont le texte propre est visible */
        const propre = Array.from(el.childNodes)
          .filter(n => n.nodeType === 3).map(n => n.textContent.trim()).join('');
        if (propre.length < 2) return;
        /* Les emoji sont des bitmaps multicolores : leur « couleur de
           texte » n'existe pas et aucune métrique de contraste ne s'y
           applique. On les écarte plutôt que de produire un chiffre faux. */
        if (!/[a-zA-Z0-9\u00C0-\u00FF]/.test(
              propre.replace(/[\u{1F000}-\u{1FAFF}\u2190-\u27BF\uFE0F\u200D]/gu, ''))) return;
        const r = el.getBoundingClientRect();
        if (r.width < 8 || r.height < 6) return;
        if (r.top < 0 || r.bottom > window.innerHeight) return;
        const cs = getComputedStyle(el);
        if (cs.visibility === 'hidden' || cs.opacity === '0') return;
        const cle = el.className + '|' + cs.color + '|' + cs.fontSize;
        if (vus.has(cle)) return;
        vus.add(cle);
        const m = cs.color.match(/[\d.]+/g).map(Number);
        out.push({
          cle: (el.className || el.tagName.toLowerCase()).toString().split(' ').slice(0, 2).join('.'),
          txt: propre.slice(0, 26),
          col: [m[0], m[1], m[2]],
          alpha: m[3] === undefined ? 1 : m[3],
          px: parseFloat(cs.fontSize),
          poids: parseInt(cs.fontWeight, 10) || 400,
          r: { x: r.x, y: r.y, w: r.width, h: r.height }
        });
      });
      return out;
    });

    const png = await p.screenshot();
    const data = 'data:image/png;base64,' + png.toString('base64');
    const fonds = await dec.evaluate(async ({ data, cibles }) => {
      const img = new Image(); img.src = data; await img.decode();
      const cv = document.createElement('canvas');
      cv.width = img.width; cv.height = img.height;
      const x = cv.getContext('2d'); x.drawImage(img, 0, 0);
      const D = 2;   /* deviceScaleFactor */
      return cibles.map(c => {
        const X = Math.round(c.r.x * D), Y = Math.round(c.r.y * D);
        const W = Math.max(1, Math.round(c.r.w * D)), H = Math.max(1, Math.round(c.r.h * D));
        const d = x.getImageData(X, Y, W, H).data;
        /* couleur modale = le fond : le texte n'occupe jamais la majorité
           d'une boîte de ligne */
        const h = new Map();
        for (let i = 0; i < d.length; i += 4) {
          const q = ((d[i] >> 2) << 12) | ((d[i + 1] >> 2) << 6) | (d[i + 2] >> 2);
          h.set(q, (h.get(q) || 0) + 1);
        }
        /* Le fond est la couleur modale — sauf sur du très gros texte, où
           les glyphes occupent la majorité de la boîte et où la modale
           SERAIT le texte. On exclut donc les couleurs proches de la
           couleur de texte connue avant de choisir. */
        const tq = ((c.col[0] >> 2) << 12) | ((c.col[1] >> 2) << 6) | (c.col[2] >> 2);
        const loin = k => {
          const dr = (((k >> 12) & 63) - ((tq >> 12) & 63));
          const dg = (((k >> 6) & 63) - ((tq >> 6) & 63));
          const db = ((k & 63) - (tq & 63));
          return dr * dr + dg * dg + db * db > 12;
        };
        let best = -1, bn = -1, bestTout = 0, bnTout = -1;
        h.forEach((v, k) => {
          if (v > bnTout) { bnTout = v; bestTout = k; }
          if (loin(k) && v > bn) { bn = v; best = k; }
        });
        if (best < 0) best = bestTout;
        return [((best >> 12) & 63) << 2, ((best >> 6) & 63) << 2, (best & 63) << 2];
      });
    }, { data, cibles });

    cibles.forEach((c, i) => {
      const fond = fonds[i];
      /* une couleur de texte semi-transparente se compose sur son fond */
      const txt = c.alpha >= 1 ? c.col
        : c.col.map((v, k) => Math.round(v * c.alpha + fond[k] * (1 - c.alpha)));
      const lc = Math.abs(apca(txt, fond));
      lignes.push({
        onglet: nom, cle: c.cle, txt: c.txt, px: c.px, poids: c.poids,
        txtCol: txt, fond: fond,
        wcag: Math.round(wcag(txt, fond) * 100) / 100,
        lc: Math.round(lc * 10) / 10,
        seuil: seuilAPCA(c.px, c.poids),
        lc15: Math.round(Math.abs(apca(voile(txt, 0.15), voile(fond, 0.15))) * 10) / 10,
        lc30: Math.round(Math.abs(apca(voile(txt, 0.30), voile(fond, 0.30))) * 10) / 10
      });
    });
  }

  await br.close();

  const hex = c => '#' + c.map(v => v.toString(16).padStart(2, '0')).join('');
  lignes.sort((a, b) => (a.lc - a.seuil) - (b.lc - b.seuil));

  const echecs = lignes.filter(l => l.lc < l.seuil);
  const wcagKo = lignes.filter(l => l.wcag < 4.5);
  const jourKo = lignes.filter(l => l.lc30 < 45);
  const moy = Math.round(lignes.reduce((s, l) => s + l.lc, 0) / lignes.length * 10) / 10;
  const moyJour = Math.round(lignes.reduce((s, l) => s + l.lc30, 0) / lignes.length * 10) / 10;

  /* Seuils de non-régression. Ce ne sont pas des idéaux : ce sont les
     valeurs atteintes, verrouillées, pour qu'aucune couleur ne puisse
     redescendre sans faire échouer la suite. */
  /* Resserré après le passage à la palette FIT GREEN, qui mesure mieux
     que l'ancienne : ces valeurs sont celles atteintes, verrouillées. */
  const BUDGET = { echecs: 1, wcag: 2, jour: 0, moyenne: 91.5, moyenneJour: 58 };
  const ok = [], bad = [];
  const check = (n, c, d) => (c ? ok : bad).push(n + (d ? ' — ' + d : ''));

  check('au plus ' + BUDGET.echecs + ' textes sous leur seuil APCA',
    echecs.length <= BUDGET.echecs, echecs.length + ' sur ' + lignes.length);
  check('au plus ' + BUDGET.wcag + ' textes sous WCAG 2 AA',
    wcagKo.length <= BUDGET.wcag, wcagKo.length + '');
  check('au plus ' + BUDGET.jour + ' textes perdus en plein soleil',
    jourKo.length <= BUDGET.jour, jourKo.length + '');
  check('APCA moyen >= ' + BUDGET.moyenne, moy >= BUDGET.moyenne, String(moy));
  check('APCA moyen au soleil >= ' + BUDGET.moyenneJour, moyJour >= BUDGET.moyenneJour, String(moyJour));
  check('aucun texte sous Lc 60 (illisible au repos)',
    !lignes.some(l => l.lc < 60), (lignes.filter(l => l.lc < 60).map(l => l.cle).join(', ')) || '');

  if (process.argv.indexOf('--detail') >= 0) {
    console.log('\nonglet     | élément            | px/gr  | texte    fond     | WCAG | APCA  seuil | jour15 jour30');
    console.log('-'.repeat(112));
    lignes.forEach(l => {
      console.log(
        l.onglet.padEnd(10) + ' | ' + (l.cle + ' « ' + l.txt + ' »').slice(0, 18).padEnd(18) + ' | ' +
        (l.px + '/' + l.poids).padEnd(6) + ' | ' + hex(l.txtCol) + '  ' + hex(l.fond) + ' | ' +
        String(l.wcag).padStart(4) + ' | ' + String(l.lc).padStart(5) + ' ' + String(l.seuil).padStart(3) +
        (l.lc < l.seuil ? '  ✗' : '  ✓') + ' | ' + String(l.lc15).padStart(5) + ' ' + String(l.lc30).padStart(5));
    });
  }
  console.log('\n' + lignes.length + ' textes mesurés · APCA moyen ' + moy +
    ' · au soleil ' + moyJour + ' · ' + echecs.length + ' sous seuil · ' + jourKo.length + ' perdus au soleil');
  fs.writeFileSync(OUT + 'audit-contraste.json', JSON.stringify(lignes, null, 1));

  console.log('\n=== PASS (' + ok.length + ') ===');
  ok.forEach(s => console.log('  ✓ ' + s));
  if (bad.length) { console.log('\n=== FAIL (' + bad.length + ') ==='); bad.forEach(s => console.log('  ✗ ' + s)); }
  process.exit(bad.length ? 1 : 0);
})().catch(e => { console.error('CRASH', e); process.exit(2); });
