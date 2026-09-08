/* La cohérence du discours.
   Un coach à 100 €/h se fait virer pour une seule chose : dire deux
   choses différentes selon l'écran. Chaque vérification ici correspond
   à une contradiction réellement trouvée dans l'app. */
const { chromium } = require('playwright-core');
const fs = require('fs');
const CTX = require('./contexte');
const B = CTX.BASE;
const F = CTX.APP;
const ok = [], bad = [];
const check = (n, c, d) => (c ? ok : bad).push(n + (d ? ' — ' + d : ''));

(async () => {
  const br = await chromium.launch({ executablePath: CTX.CHROMIUM, args: ['--no-sandbox'] });
  const p = await (await br.newContext({ viewport: { width: 390, height: 844 } })).newPage();
  const errs = [];
  p.on('pageerror', e => errs.push(e.message));
  p.on('console', m => { if (m.type() === 'error' && !/Failed to load resource/.test(m.text())) errs.push(m.text()); });
  await p.goto(B + '/index.html', { waitUntil: 'domcontentloaded' });
  await p.waitForTimeout(900);

  // ============ 1 · l'objectif se déduit du corps ============
  const obj = await p.evaluate(() => {
    const lire = (h, w) => { localStorage.setItem('inrun_profil', JSON.stringify({ h: h, w: w, a: 35 }));
      return objectifNutrition(); };
    return { lui: lire(175, 90), pivot27: lire(175, 82.7), moyen: lire(175, 76),
      pivot23: lire(175, 70.4), sec: lire(175, 65), sansRien: (() => {
        localStorage.setItem('inrun_profil', JSON.stringify({})); return objectifNutrition(); })() };
  });
  check('à IMC 29, le plan est une recomposition en déficit — pas un surplus',
    obj.lui.code === 'seche' && obj.lui.deficit === 400 && obj.lui.bas < 0, JSON.stringify(obj.lui));
  check('l\'objectif change avec le corps, il n\'est pas écrit en dur',
    obj.moyen.code === 'recomp' && obj.moyen.deficit === 200, JSON.stringify(obj.moyen));
  check('… et un corps sec repasse bien en surplus',
    obj.sec.code === 'masse' && obj.sec.deficit < 0 && obj.sec.bas > 0, JSON.stringify(obj.sec));
  check('sans taille ni poids, aucun objectif n\'est inventé', obj.sansRien === null);

  // ============ 2 · le plafond suit l'objectif ============
  const suite = await p.evaluate(() => {
    const k = todayKey();
    localStorage.setItem('inrun_activite', JSON.stringify({ [k]: { b: 'assis', s: 1 } }));
    const lire = (h, w) => { localStorage.setItem('inrun_profil', JSON.stringify({ h: h, w: w, a: 35 }));
      const b = bilanKcal(); return { plaf: b.plafond, dep: b.depense, code: b.obj.code }; };
    return { gros: lire(175, 90), sec: lire(175, 65) };
  });
  check('le plafond calorique applique le déficit de l\'objectif, pas un chiffre figé',
    suite.gros.plaf === suite.gros.dep - 400, JSON.stringify(suite.gros));
  check('… et devient un surplus quand l\'objectif est la masse',
    suite.sec.plaf > suite.sec.dep, JSON.stringify(suite.sec));

  // ============ 3 · plus un mot de « surplus » quand on est en déficit ====
  const src = fs.readFileSync(F, 'utf8');
  const methode = await p.evaluate(() =>
    [...document.querySelectorAll('#moi .info-row')].map(e => e.textContent).join(' '));
  check('la méthode ne prescrit plus un surplus en toutes lettres',
    !/[Ll]éger surplus/.test(methode), (methode.match(/.{0,40}surplus.{0,40}/) || [''])[0]);
  check('… et elle renvoie au plafond calculé, au lieu d\'un conseil général',
    /recomposition/i.test(methode) && /Progrès/.test(methode), methode.slice(0, 80));

  // ============ 4 · la ligne de poids juge selon l'objectif ============
  const poids = await p.evaluate(() => {
    localStorage.setItem('inrun_profil', JSON.stringify({ h: 175, w: 90, a: 35 }));
    /* la forme exacte que recapLines attend */
    const faire = d => recapLines({ setsByGroup: { push: 12 }, byMuscle: {}, sets: 12,
      sessions: 4, reps: 100, tonnage: 5000, prs: [], wStart: 90, wEnd: 90 + d,
      rir: 2, fatDays: 0, from: '2026-08-17', to: '2026-08-23' })
      .map(l => l.t.replace(/<[^>]+>/g, '')).filter(t => /Poids/.test(t))[0] || '';
    return { bonne: faire(-0.5), tropVite: faire(-1.5), prise: faire(+0.3) };
  });
  check('perdre 0,5 kg est validé comme la bonne vitesse',
    /bonne vitesse/.test(poids.bonne), poids.bonne);
  check('perdre 1,5 kg est signalé comme trop rapide, pas comme un succès',
    /[Tt]rop vite/.test(poids.tropVite) && /200 kcal/.test(poids.tropVite), poids.tropVite);
  check('PRENDRE du poids n\'est plus félicité quand l\'objectif est de perdre',
    !/bonne vitesse/.test(poids.prise) && /[Tt]rop/.test(poids.prise), poids.prise);

  // ============ 5 · une seule définition des jours d'affilée ============
  const jours = await p.evaluate(() => {
    const t = new Date(), k = n => { const d = new Date(t); d.setDate(t.getDate() - n);
      return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0'); };
    /* deux jours faits, un trou non renseigné, puis trois jours faits */
    localStorage.setItem('inrun_trainlog', JSON.stringify({
      [k(0)]: 'push', [k(1)]: 'pull', [k(3)]: 'legs', [k(4)]: 'upper', [k(5)]: 'core' }));
    const seq = []; for (let i = 6; i >= 0; i--) seq.push(loadTLog()[k(i)]);
    let cur = 0, mx = 0;
    seq.forEach(v => { if (isTraining(v)) { cur++; mx = Math.max(mx, cur); } else { cur = 0; } });
    return { serieJours: serieJours(), maxAnalyse: mx };
  });
  check('un jour sans séance enregistrée coupe la série : on n\'accuse pas sans donnée',
    jours.serieJours === 2, 'série en cours : ' + jours.serieJours);
  check('l\'analyse et le coach comptent la même chose',
    jours.maxAnalyse === 3 && jours.serieJours <= jours.maxAnalyse,
    'analyse ' + jours.maxAnalyse + ' / coach ' + jours.serieJours);

  // ============ 6 · la priorité ne contredit jamais l'analyse ============
  await p.evaluate(() => {
    localStorage.clear();
      localStorage.setItem('inrun_programme', 'rot5');
    localStorage.setItem('inrun_profil', JSON.stringify({ h: 175, w: 90, a: 35 }));
    const cycle = ['push', 'pull', 'legs', 'upper', 'core'];
    const log = {}, sets = {};
    let c = 0;
    for (let j = 42; j >= 0; j--) {
      if (j % 7 === 6 || j % 7 === 2) continue;
      const d = new Date(); d.setDate(d.getDate() - j);
      const key = d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
      const g = cycle[c++ % 5];
      log[key] = g;
      document.querySelectorAll('#' + g + ' .station').forEach(st => {
        const id = st.getAttribute('data-ex'), pr = getPresc(st);
        sets[id] = sets[id] || {};
        sets[id][key] = Array.from({ length: pr.sets }, () => ({ w: 40, r: pr.hi, rir: 2 }));
      });
    }
    localStorage.setItem('inrun_trainlog', JSON.stringify(log));
    localStorage.setItem('inrun_sets', JSON.stringify(sets));
  });
  await p.reload({ waitUntil: 'domcontentloaded' });
  await p.waitForTimeout(1400);
  await p.evaluate(() => { try { closeRecap(); } catch (e) {} });
  const croise = await p.evaluate(() => {
    show('semaine', document.querySelectorAll('.bottomnav .bn')[1]);
    const analyse = [...document.querySelectorAll('#planAnalysis .arow')].map(e => e.textContent);
    return { prio: (document.getElementById('coachPrio') || {}).textContent || '',
      analyse: analyse, dette: analyse.some(t => /En dette/.test(t)),
      equilibre: analyse.some(t => /bien équilibrée/.test(t)),
      codes: fuites().map(f => f.code) };
  });
  check('quand des muscles sont en dette, la priorité ne dit pas « rien à te reprocher »',
    !(croise.dette && /reprocher/.test(croise.prio)), croise.prio.slice(0, 70));
  check('… et l\'analyse ne conclut pas « bien équilibrée » au-dessus d\'une dette',
    !(croise.dette && croise.equilibre),
    'dette ' + croise.dette + ' / équilibrée ' + croise.equilibre);
  check('le seuil de dette du coach est celui du reste de l\'app',
    croise.dette === (croise.codes.indexOf('dette') >= 0),
    'analyse ' + croise.dette + ' / fuites ' + croise.codes.join(','));

  // ============ 7 · l'enchaînement sans repos est une fuite ============
  const repos = await p.evaluate(() => {
    const t = new Date(), k = n => { const d = new Date(t); d.setDate(t.getDate() - n);
      return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0'); };
    const log = {}; for (let j = 0; j < 6; j++) log[k(j)] = ['push', 'pull', 'legs', 'upper', 'core'][j % 5];
    localStorage.setItem('inrun_trainlog', JSON.stringify(log));
    _cal = null;
    const f = fuites();
    return { codes: f.map(x => x.code), txt: (f.find(x => x.code === 'sansRepos') || {}).t || '' };
  });
  check('six jours d\'affilée sont signalés comme une fuite',
    repos.codes.indexOf('sansRepos') >= 0, repos.codes.join(', '));
  check('… et la consigne est datée, pas une suggestion',
    /Demain, c\'est repos/.test(repos.txt), repos.txt.replace(/<[^>]+>/g, '').slice(0, 90));

  // ====== 8 · la faute du plan n'est pas mise sur le dos de l'élève ======
  /* Rotation ENTIÈRE, exactement comme prescrite, mais à un niveau où la
     barre est montée : plusieurs muscles restent alors sous leur cible
     sans que l'utilisateur ait quoi que ce soit à se reprocher. Deux
     surfaces doivent le refléter — le titre de la priorité et l'analyse. */
  const plan = await p.evaluate(() => {
    const t = new Date(), k = n => { const d = new Date(t); d.setDate(t.getDate() - n);
      return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0'); };
    localStorage.clear();
      localStorage.setItem('inrun_programme', 'rot5');
    localStorage.setItem('inrun_profil', JSON.stringify({ h: 175, w: 90, a: 35 }));
    saveNiv({ n: 4 });                       /* la barre a monté avec le niveau */
    const log = {}, sets = {};
    ['push', 'pull', 'legs', 'upper', 'core'].forEach((g, i) => {
      log[k(i + 1)] = g;
      document.querySelectorAll('#' + g + ' .station').forEach(st => {
        const id = st.getAttribute('data-ex'), pr = getPresc(st);
        sets[id] = sets[id] || {};
        sets[id][k(i + 1)] = Array.from({ length: pr.sets }, () => ({ w: 40, r: pr.hi, rir: 1 }));
      });
    });
    localStorage.setItem('inrun_trainlog', JSON.stringify(log));
    localStorage.setItem('inrun_sets', JSON.stringify(sets));
    return null;
  });
  await p.reload({ waitUntil: 'domcontentloaded' });
  await p.waitForTimeout(1400);
  await p.evaluate(() => { try { closeRecap(); } catch (e) {} });
  const aveu = await p.evaluate(() => {
    show('semaine', document.querySelectorAll('.bottomnav .bn')[1]);
    const analyse = [...document.querySelectorAll('#planAnalysis .arow')].map(e => e.textContent);
    return { codes: fuites().map(f => f.code),
      titre: (document.querySelector('#coachPrio .ph') || {}).textContent || '',
      corps: (document.querySelector('#coachPrio .pt') || {}).textContent || '',
      accuse: analyse.some(t => /En dette/.test(t)),
      avoue: analyse.some(t => /Hors de portée de la rotation/.test(t)) };
  });
  /* Le code exact dépend de ce qu'une séance ajoutée peut encore
     réparer : « structurel » quand elle comble la majorité du trou,
     « depasse » quand le découpage lui-même est en cause. Ce qui doit
     tenir dans les deux cas, c'est que la faute n'est pas mise sur
     l'élève — c'est ça qu'on verrouille, pas le nom de la branche. */
  check('à un niveau supérieur, la rotation parfaite laisse une dette de programme',
    aveu.codes.length === 1 && ['structurel', 'depasse'].indexOf(aveu.codes[0]) >= 0,
    aveu.codes.join(', '));
  check('… la priorité ne titre PAS « ce qui te coûte le plus »',
    !/coûte/i.test(aveu.titre) && /programme/i.test(aveu.titre), aveu.titre);
  check('… et le texte impute la limite au plan, pas à l\'élève',
    /pas toi/i.test(aveu.corps) || /dépassé ce découpage/i.test(aveu.corps),
    aveu.corps.slice(0, 60));
  check('… et l\'analyse ne l\'inscrit pas non plus comme une dette de sa part',
    aveu.accuse === false && aveu.avoue === true,
    'En dette ' + aveu.accuse + ' / Hors de portée ' + aveu.avoue);

  check('aucune erreur JS', errs.length === 0, errs.join(' | '));

  await br.close();
  console.log('\n=== PASS (' + ok.length + ') ===');
  ok.forEach(s => console.log('  ✓ ' + s));
  if (bad.length) { console.log('\n=== FAIL (' + bad.length + ') ==='); bad.forEach(s => console.log('  ✗ ' + s)); }
  process.exit(bad.length ? 1 : 0);
})().catch(e => { console.error('CRASH', e); process.exit(2); });
