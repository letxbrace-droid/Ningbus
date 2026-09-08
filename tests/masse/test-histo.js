/* L'historique 1RM doit toujours dire ce que les séries disent.
   Le défaut : delSet() retirait la série de inrun_sets mais laissait sa 1RM
   dans inrun_hist. Le graphe montrait un sommet jamais tenu, et surtout ce
   sommet servait de référence à « record battu » — donc un vrai record
   pouvait ne plus être détecté.
   Le fixture est un extrait ANONYMISÉ d'une sauvegarde réelle, qui contient
   les trois points fantômes (les données personnelles en ont été retirées). */
const { chromium } = require('playwright-core');
const fs = require('fs');
const CTX = require('./contexte');
const B = CTX.BASE;
const SAUV = JSON.parse(fs.readFileSync(
  require('path').join(CTX.FIXTURES, 'historique.json'), 'utf8'));

const ok = [], bad = [];
const check = (n, c, d) => (c ? ok : bad).push(n + (d ? ' — ' + d : ''));

(async () => {
  const br = await chromium.launch({
    executablePath: CTX.CHROMIUM,
    args: ['--no-sandbox'] });
  const ctx = await br.newContext();
  const p = await ctx.newPage();
  const errs = []; p.on('pageerror', e => errs.push(e.message));

  /* on injecte la sauvegarde AVANT que l'app ne démarre */
  await p.addInitScript(d => {
    for (const k in d) localStorage.setItem(k, d[k]);
  }, SAUV.data);
  await p.goto(B + '/index.html', { waitUntil: 'networkidle' });
  await p.waitForTimeout(1200);

  const ep = (w, r) => Math.round(w * (1 + r / 30));

  /* ---------- 1. la réparation a eu lieu au démarrage ---------- */
  const apres = await p.evaluate(() => ({
    hist: JSON.parse(localStorage.getItem('inrun_hist') || '{}'),
    sets: JSON.parse(localStorage.getItem('inrun_sets') || '{}'),
  }));
  const faux = [];
  for (const id in apres.hist)
    for (const e of apres.hist[id]) {
      const ss = (apres.sets[id] || {})[e.d];
      if (!ss || !ss.length) continue;
      const b = ss.slice().sort((x, y) => ep(y.w, y.r) - ep(x.w, x.r))[0];
      if (e.rm !== ep(b.w, b.r)) faux.push(`${id} ${e.d}: hist ${e.rm} vs réel ${ep(b.w, b.r)}`);
    }
  check('aucun point d\'historique ne contredit les séries', faux.length === 0, faux.join(' | '));

  /* les trois fantômes connus sont bien corrigés */
  const attendu = { pull5: ['2026-08-23', 18], pull2: ['2026-08-26', 52], up5: ['2026-08-24', 6] };
  for (const id in attendu) {
    const [d, rm] = attendu[id];
    const e = (apres.hist[id] || []).find(x => x.d === d);
    check(`fantôme corrigé : ${id} ${d}`, e && e.rm === rm, e ? `1RM ${e.rm}, attendu ${rm}` : 'point absent');
  }

  /* ---------- 2. idempotence ---------- */
  const rejoue = await p.evaluate(() => {
    const avant = localStorage.getItem('inrun_hist');
    const n = reparerHistorique();
    return { n, identique: localStorage.getItem('inrun_hist') === avant };
  });
  check('relancer la réparation ne change rien', rejoue.n === 0 && rejoue.identique,
        `${rejoue.n} correction(s), identique=${rejoue.identique}`);

  /* ---------- 3. un point sans séries est préservé ---------- */
  const orphelin = await p.evaluate(() => {
    const h = JSON.parse(localStorage.getItem('inrun_hist'));
    h.push1.push({ d: '2020-01-01', w: 99, r: 9, rm: 129 });   /* version antérieure */
    localStorage.setItem('inrun_hist', JSON.stringify(h));
    reparerHistorique();
    const e = JSON.parse(localStorage.getItem('inrun_hist')).push1.find(x => x.d === '2020-01-01');
    return e ? e.rm : null;
  });
  check('un point sans séries n\'est pas écrasé', orphelin === 129, 'rm=' + orphelin);

  /* ---------- 4. supprimer une série redescend jusqu'à l'historique ---------- */
  const suppr = await p.evaluate(() => {
    const tk = todayKey();
    const all = loadExSets();
    all.push1 = all.push1 || {};
    all.push1[tk] = [{ w: 40, r: 10, rir: 2 }, { w: 80, r: 10, rir: 1 }];
    saveExSets(all);
    resyncPerf('push1', tk);                       /* état de départ : 80×10 */
    const avant = loadHist().push1.find(e => e.d === tk).rm;
    delSet('push1', 1);                            /* on efface la série lourde */
    const h = loadHist().push1.find(e => e.d === tk);
    const log = loadLogs().push1;
    return { avant, apres: h ? h.rm : null, logW: log.w, tk };
  });
  check('la 1RM du jour suit la suppression',
        suppr.avant === 107 && suppr.apres === 53,
        `avant ${suppr.avant} → après ${suppr.apres} (attendu 107 → 53)`);
  check('le carnet « dernière fois » suit aussi', suppr.logW === 40, 'w=' + suppr.logW);

  /* ---------- 5. effacer la dernière série retire le point ---------- */
  const vide = await p.evaluate(() => {
    const tk = todayKey();
    delSet('push1', 0);
    const h = loadHist().push1 || [];
    return { pointDuJour: h.some(e => e.d === tk), log: loadLogs().push1 };
  });
  check('plus aucune série → plus de point d\'historique ce jour-là', !vide.pointDuJour);
  check('le carnet retombe sur la séance précédente',
        vide.log && vide.log.date === '01/09', JSON.stringify(vide.log));

  /* ---------- 6. un vrai record n'est plus masqué ---------- */
  /* c'était la conséquence grave : bestBefore comparé à un sommet fantôme */
  /* On ne code pas la valeur en dur : le sommet doit être le meilleur des
     séries réellement enregistrées, quel que soit le jour. Sur pull2 c'est
     45×8 du 28/08 (1RM 57) — et non la dernière séance, qui vaut 54. */
  const record = await p.evaluate(() => {
    const ep = (w, r) => Math.round(w * (1 + r / 30));
    const h = loadHist(), s = loadExSets();
    const out = [];
    for (const id in h) {
      const jours = Object.keys(s[id] || {});
      if (!jours.length) continue;
      const reel = Math.max.apply(null, jours.map(d =>
        Math.max.apply(null, s[id][d].map(x => ep(x.w, x.r)))));
      /* les points sans séries (versions antérieures de l'app) sont
         invérifiables par construction : on ne les compare pas */
      const verifiables = h[id].filter(e => (s[id] || {})[e.d]);
      if (!verifiables.length) continue;
      const vu = Math.max.apply(null, verifiables.map(e => e.rm));
      if (vu !== reel) out.push(`${id}: historique ${vu}, réel ${reel}`);
    }
    return out;
  });
  check('aucun sommet d\'historique n\'est un fantôme', record.length === 0, record.join(' | '));

  check('aucune erreur JS', errs.length === 0, errs.join(' | '));

  console.log('=== PASS (' + ok.length + ') ===');
  ok.forEach(s => console.log('  ✓ ' + s));
  if (bad.length) { console.log('\n=== FAIL (' + bad.length + ') ==='); bad.forEach(s => console.log('  ✗ ' + s)); }
  await br.close();
  process.exit(bad.length ? 1 : 0);
})().catch(e => { console.error('CRASH', e); process.exit(2); });
