/* Le plafond calorique.
   Le calcul est fait PAR JOUR : les facteurs d'activité classiques
   (1,2 · 1,55 · 1,725) sont des moyennes hebdomadaires, les appliquer à
   une journée précise est un abus. On part d'une base assise et on
   ajoute ce qui a été réellement fait — donc marche ET séance peuvent
   se cumuler, ce que trois boutons exclusifs auraient rendu impossible. */
const { chromium } = require('playwright-core');
const CTX = require('./contexte');
const B = CTX.BASE;
const ok = [], bad = [];
const check = (n, c, d) => (c ? ok : bad).push(n + (d ? ' — ' + d : ''));

(async () => {
  const br = await chromium.launch({ executablePath: CTX.CHROMIUM, args: ['--no-sandbox'] });
  const p = await (await br.newContext({ viewport: { width: 390, height: 844 } })).newPage();
  const errs = [];
  p.on('pageerror', e => errs.push(e.message));
  p.on('console', m => { if (m.type() === 'error' && !/Failed to load resource/.test(m.text())) errs.push(m.text()); });
  await p.goto(B + '/index.html', { waitUntil: 'domcontentloaded' });
  await p.waitForTimeout(800);

  // ============ 1 · la formule ============
  const mb = await p.evaluate(() => ({
    lui: metabolismeBase({ h: 175, w: 90, a: 35 }),
    jeune: metabolismeBase({ h: 175, w: 90, a: 25 }),
    leger: metabolismeBase({ h: 175, w: 80, a: 35 }),
    sansAge: metabolismeBase({ h: 175, w: 90 }),
    vide: metabolismeBase({})
  }));
  /* Mifflin-St Jeor homme : 10×90 + 6,25×175 − 5×35 + 5 = 1824 */
  check('la formule de Mifflin-St Jeor est exacte', mb.lui === 1824, String(mb.lui));
  check('dix ans de moins valent 50 kcal de plus', mb.jeune - mb.lui === 50, mb.jeune + ' vs ' + mb.lui);
  check('dix kilos de moins valent 100 kcal de moins', mb.lui - mb.leger === 100, String(mb.leger));
  check('sans âge, pas de calcul : on n\'invente pas une donnée',
    mb.sansAge === null && mb.vide === null, JSON.stringify(mb));

  // ============ 2 · les quatre scénarios ============
  const scenarios = await p.evaluate(() => {
    localStorage.clear();
    localStorage.setItem('inrun_profil', JSON.stringify({ h: 175, w: 90, a: 35 }));
    const k = todayKey(), out = {};
    [['assis', 0], ['assis', 1], ['marche', 0], ['marche', 1]].forEach(([b, s]) => {
      localStorage.setItem('inrun_activite', JSON.stringify({ [k]: { b: b, s: s } }));
      const x = bilanKcal();
      out[b + (s ? '+seance' : '')] = { dep: x.depense, plaf: x.plafond, sous: x.sousPlancher };
    });
    return out;
  });
  /* base = 1824 × 1,25 = 2280 · marche +250 · séance +300 · déficit −400 */
  check('une journée assise : 2 280 de dépense',
    scenarios.assis.dep === 2280, JSON.stringify(scenarios.assis));
  check('… et son plafond est retenu par le plancher, pas par le déficit',
    scenarios.assis.plaf === 2000 && scenarios.assis.sous === true, JSON.stringify(scenarios.assis));
  check('assise + séance : 2 580 → 2 180',
    scenarios['assis+seance'].dep === 2580 && scenarios['assis+seance'].plaf === 2180,
    JSON.stringify(scenarios['assis+seance']));
  check('marche seule : 2 530 → 2 130',
    scenarios.marche.dep === 2530 && scenarios.marche.plaf === 2130, JSON.stringify(scenarios.marche));
  check('marche ET séance se cumulent : 2 830 → 2 430',
    scenarios['marche+seance'].dep === 2830 && scenarios['marche+seance'].plaf === 2430,
    JSON.stringify(scenarios['marche+seance']));
  check('la séance vaut le même écart quelle que soit la journée',
    scenarios['assis+seance'].dep - scenarios.assis.dep === scenarios['marche+seance'].dep - scenarios.marche.dep);

  // ============ 3 · la séance vient du journal, et reste corrigeable ====
  const auto = await p.evaluate(() => {
    localStorage.clear();
    localStorage.setItem('inrun_profil', JSON.stringify({ h: 175, w: 90, a: 35 }));
    const k = todayKey();
    localStorage.setItem('inrun_trainlog', JSON.stringify({ [k]: 'push' }));
    const avecSeance = actJour(k);
    localStorage.setItem('inrun_trainlog', JSON.stringify({ [k]: 'rest' }));
    const repos = actJour(k);
    localStorage.setItem('inrun_trainlog', JSON.stringify({}));
    const rien = actJour(k);
    return { seance: avecSeance, repos: repos, rien: rien };
  });
  check('une séance déjà au journal est proposée cochée : on ne la redemande pas',
    auto.seance.s === 1 && auto.seance.defaut === true, JSON.stringify(auto.seance));
  check('un jour de repos n\'est pas une séance', auto.repos.s === 0, JSON.stringify(auto.repos));
  check('sans rien au journal, rien n\'est présumé', auto.rien.s === 0, JSON.stringify(auto.rien));

  // ============ 4 · le choix se garde, et par jour =====================
  await p.evaluate(() => {
    localStorage.clear();
    localStorage.setItem('inrun_profil', JSON.stringify({ h: 175, w: 90, a: 35 }));
  });
  await p.reload({ waitUntil: 'domcontentloaded' });
  await p.waitForTimeout(900);
  await p.evaluate(() => { try { closeRecap(); } catch (e) {} });
  const clic = await p.evaluate(() => {
    const box = document.querySelector('[data-kcal]');
    box.querySelector('[data-b="marche"]').click();
    const apres = bilanKcal();
    return { choix: actJour().b, plaf: apres.plafond, sel: !!box.querySelector('[data-b="marche"].sel') };
  });
  check('le choix de la journée est enregistré et se voit',
    clic.choix === 'marche' && clic.sel === true, JSON.stringify(clic));
  await p.reload({ waitUntil: 'domcontentloaded' });
  await p.waitForTimeout(900);
  const apresRechargement = await p.evaluate(() => actJour().b);
  check('… et il survit à un rechargement', apresRechargement === 'marche', apresRechargement);

  const hier = await p.evaluate(() => {
    const d = new Date(); d.setDate(d.getDate() - 1);
    const k = d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
    return { hier: actJour(k).b, aujourdhui: actJour().b };
  });
  check('le choix vaut pour UN jour, pas pour toujours',
    hier.hier === 'assis' && hier.aujourdhui === 'marche', JSON.stringify(hier));

  const bascule = await p.evaluate(() => {
    const box = document.querySelector('[data-kcal]');
    const avant = bilanKcal().plafond;
    box.querySelector('[data-s]').click();
    const apres = bilanKcal().plafond;
    return { avant: avant, apres: apres, ecart: Math.abs(apres - avant) };
  });
  check('la séance se coche et se décoche, et vaut bien 300 kcal',
    bascule.ecart === 300, JSON.stringify(bascule));

  // ============ 5 · sans profil, on ne devine pas =======================
  const vide = await p.evaluate(() => {
    localStorage.clear();
    renderKcal();
    const box = document.querySelector('[data-kcal]');
    return { bilan: bilanKcal(), txt: box.textContent };
  });
  check('sans taille, poids ou âge, aucun chiffre n\'est inventé', vide.bilan === null);
  check('… et l\'app dit quoi renseigner plutôt que d\'afficher un tiret',
    /âge/.test(vide.txt) && /Progrès/.test(vide.txt), vide.txt.slice(0, 90));

  // ============ 6 · la sauvegarde emporte le nouveau stockage ==========
  const sauve = await p.evaluate(() => ALL_KEYS.indexOf('inrun_activite') >= 0);
  check('l\'activité du jour part avec la sauvegarde', sauve === true);

  check('aucune erreur JS', errs.length === 0, errs.join(' | '));

  await br.close();
  console.log('\n=== PASS (' + ok.length + ') ===');
  ok.forEach(s => console.log('  ✓ ' + s));
  if (bad.length) { console.log('\n=== FAIL (' + bad.length + ') ==='); bad.forEach(s => console.log('  ✗ ' + s)); }
  process.exit(bad.length ? 1 : 0);
})().catch(e => { console.error('CRASH', e); process.exit(2); });
