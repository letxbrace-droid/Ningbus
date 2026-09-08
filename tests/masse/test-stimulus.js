/* Deux mécanismes qui n'existaient pas :
   1. la série stimulante — le volume par muscle est pondéré par la
      proximité de l'échec, le volume brut ne bouge pas ;
   2. la calibration du RIR — le coach compare ce que tu annonces à ce
      que tes reps montrent, et corrige. */
const { chromium } = require('playwright-core');
const CTX = require('./contexte');
const B = CTX.BASE;
const ok = [], bad = [];
const check = (n, c, d) => (c ? ok : bad).push(n + (d ? ' — ' + d : ''));

/* Injecté : `seances` = [{id, jour, sets:[{w,r,rir}]}] posé tel quel. */
function poser(seances) {
  localStorage.clear();
      localStorage.setItem('inrun_programme', 'rot5');
  const t = new Date();
  const k = n => { const d = new Date(t); d.setDate(t.getDate() - n);
    return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0'); };
  const sets = {}, log = {};
  seances.forEach(s => {
    const jour = k(s.jour);
    if (!sets[s.id]) sets[s.id] = {};
    sets[s.id][jour] = s.sets;
    log[jour] = s.id.replace(/[0-9]+$/, '').replace(/^up$/, 'upper');
  });
  localStorage.setItem('inrun_sets', JSON.stringify(sets));
  localStorage.setItem('inrun_trainlog', JSON.stringify(log));
}
/* n séries identiques */
const S = (n, w, r, rir) => Array.from({ length: n }, () => ({ w, r, rir }));

(async () => {
  const br = await chromium.launch({ executablePath: CTX.CHROMIUM, args: ['--no-sandbox'] });
  const c = await br.newContext({ viewport: { width: 390, height: 844 } });
  const p = await c.newPage();
  const errs = [];
  p.on('pageerror', e => errs.push(e.message));
  p.on('console', m => { if (m.type() === 'error' && !/Failed to load resource/.test(m.text())) errs.push(m.text()); });

  const charger = async s => {
    await p.evaluate(poser, s);
    await p.reload({ waitUntil: 'domcontentloaded' });
    await p.waitForTimeout(700);
  };

  await p.goto(B + '/index.html', { waitUntil: 'domcontentloaded' });
  await p.waitForTimeout(600);

  // ================= 1 · la pondération =================
  const poids = await p.evaluate(() => ({
    p0: poidsRir(0), p2: poidsRir(2), p3: poidsRir(3), p4: poidsRir(4), p5: poidsRir(5), p8: poidsRir(8),
    nul: poidsRir(null), indef: poidsRir(undefined)
  }));
  check('une série près de l\'échec compte plein', poids.p0 === 1 && poids.p2 === 1, JSON.stringify(poids));
  check('3 à 4 reps en réserve : comptée aux deux tiers', poids.p3 === 0.7 && poids.p4 === 0.7);
  check('au-delà de 5, le stimulus s\'effondre', poids.p5 === 0.3 && poids.p8 === 0.3);
  check('un RIR absent n\'est pas un RIR de zéro', poids.nul === null && poids.indef === null);

  /* chest press : pecs (moteur) · triceps · deltAnt (assistants) */
  await charger([{ id: 'push1', jour: 1, sets: S(10, 40, 10, 1) }]);
  const dur = await p.evaluate(() => ({ mus: volumeParMuscle(7), brut: weeklyVolume(7) }));
  check('10 séries menées près de l\'échec valent 10 séries stimulantes',
    dur.mus.pecs === 10, 'pecs ' + dur.mus.pecs);
  check('… et les assistants en gardent la moitié', dur.mus.triceps === 5, 'triceps ' + dur.mus.triceps);

  await charger([{ id: 'push1', jour: 1, sets: S(10, 40, 10, 6) }]);
  const mou = await p.evaluate(() => ({ mus: volumeParMuscle(7), brut: weeklyVolume(7) }));
  check('les mêmes 10 séries arrêtées à 6 reps en réserve n\'en valent que 3',
    mou.mus.pecs === 3, 'pecs ' + mou.mus.pecs);
  check('le volume BRUT, lui, ne bouge pas : 10 séries restent 10 séries',
    mou.brut.byGroup.push === 10 && dur.brut.byGroup.push === 10,
    'dur ' + dur.brut.byGroup.push + ' / mou ' + mou.brut.byGroup.push);
  check('… c\'est bien le stimulus qui distingue les deux séances',
    dur.mus.pecs > mou.mus.pecs, dur.mus.pecs + ' vs ' + mou.mus.pecs);

  // une séance molle laisse le muscle en dette là où la dure ne l'y laisse pas
  const dette = await p.evaluate(() => {
    const d = musclesEnDette(volumeParMuscle(7));
    const pecs = d.filter(x => x.m === 'pecs')[0];
    return { dedans: !!pecs, manque: pecs ? pecs.manque : 0 };
  });
  check('un muscle noyé sous des séries molles reste en dette',
    dette.dedans, 'pecs en dette de ' + dette.manque + ' séries');
  await charger([{ id: 'push1', jour: 1, sets: S(10, 40, 10, 1) }]);
  const dette2 = await p.evaluate(() =>
    musclesEnDette(volumeParMuscle(7)).filter(x => x.m === 'pecs').length);
  check('… alors que les mêmes séries menées dur l\'en sortent',
    dette2 === 0, dette2 ? 'toujours en dette' : 'pecs sorti de la dette');

  // une série sans RIR vaut ce que valent les tiennes, pas zéro
  await charger([
    { id: 'push1', jour: 1, sets: S(4, 40, 10, 5) },          /* poids 0,3 */
    { id: 'push3', jour: 2, sets: S(4, 30, 12, null) }        /* inconnu → défaut */
  ]);
  const def = await p.evaluate(() => ({ cal: calibration(), mus: volumeParMuscle(7) }));
  check('une série sans RIR prend la valeur moyenne des tiennes',
    Math.abs(def.cal.defaut - 0.3) < 0.01, 'défaut ' + def.cal.defaut);
  check('… donc elle compte, sans compter plein',
    def.mus.pecs > 0 && def.mus.pecs < 8, 'pecs ' + def.mus.pecs);

  // ================= 2 · la calibration =================
  const vierge = await p.evaluate(() => calibration());
  check('sans séances comparables, aucun biais n\'est appliqué',
    vierge.biais === 0, JSON.stringify(vierge));

  /* Optimiste : charge constante, 4 séries, les reps s'effondrent
     (12 → 8) alors qu'il annonce 3 reps en réserve. */
  const optim = [0, 2, 4, 6, 8].map(j => ({
    id: 'push1', jour: j + 1,
    sets: [{ w: 50, r: 12, rir: 3 }, { w: 50, r: 10, rir: 3 }, { w: 50, r: 9, rir: 3 }, { w: 50, r: 8, rir: 3 }]
  }));
  await charger(optim);
  const co = await p.evaluate(() => calibration());
  check('4 reps perdues à charge constante en annonçant 3 de réserve → optimiste',
    co.biais === -1 && co.opt === 5 && co.n === 5, JSON.stringify(co));
  const rc = await p.evaluate(() => ({ trois: rirCorrige(3), zero: rirCorrige(0), nul: rirCorrige(null) }));
  check('le coach retire une rep en réserve à tout ce qui est déclaré',
    rc.trois === 2 && rc.nul === null, JSON.stringify(rc));
  check('… sans jamais descendre sous zéro', rc.zero === 0, String(rc.zero));
  const msgO = await p.evaluate(() => document.getElementById('planAnalysis').textContent);
  check('… et il le dit, chiffres à l\'appui',
    /RIR est optimiste/.test(msgO) && /plus près de l'échec/.test(msgO),
    (msgO.match(/Ton RIR est optimiste[^.]{0,110}/) || [''])[0]);
  check('… sans écrire « 5 séances sur 5 » quand tout concorde',
    /les 5 séances comparables/.test(msgO) && !/sur 5 à charge/.test(msgO),
    (msgO.match(/Sur [^,]{0,40}/) || [''])[0]);

  /* Pessimiste : il annonce l'échec (RIR 0) mais ne perd pas une rep. */
  const pess = [0, 2, 4, 6, 8].map(j => ({
    id: 'pull1', jour: j + 1,
    sets: [{ w: 50, r: 10, rir: 0 }, { w: 50, r: 10, rir: 0 }, { w: 50, r: 10, rir: 1 }]
  }));
  await charger(pess);
  const cp = await p.evaluate(() => calibration());
  check('l\'échec annoncé sans aucune chute de reps → pessimiste',
    cp.biais === 1 && cp.pess === 5, JSON.stringify(cp));
  const msgP = await p.evaluate(() => document.getElementById('planAnalysis').textContent);
  check('… le coach annonce qu\'il chargera plus vite',
    /sous-estimes/.test(msgP) && /marge/.test(msgP),
    (msgP.match(/Tu te sous-estimes[^.]{0,110}/) || [''])[0]);

  /* Calibré : chute modérée, RIR cohérent. */
  const cal = [0, 2, 4, 6, 8].map(j => ({
    id: 'legs1', jour: j + 1,
    sets: [{ w: 80, r: 10, rir: 2 }, { w: 80, r: 9, rir: 2 }, { w: 80, r: 9, rir: 1 }]
  }));
  await charger(cal);
  const cc = await p.evaluate(() => calibration());
  check('une chute modérée avec un RIR cohérent → calibré',
    cc.biais === 0 && cc.n === 5 && cc.opt === 0 && cc.pess === 0, JSON.stringify(cc));
  const msgC = await p.evaluate(() => document.getElementById('planAnalysis').textContent);
  check('… le coach confirme que la donnée est fiable',
    /RIR est fiable/.test(msgC), (msgC.match(/Ton RIR est fiable[^.]{0,90}/) || [''])[0]);

  // ---------- les garde-fous du détecteur ----------
  const garde = await p.evaluate(() => {
    const t = new Date(), k = n => { const d = new Date(t); d.setDate(t.getDate() - n);
      return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0'); };
    const lire = sets => {
      const o = {}; o.push1 = {};
      [1, 2, 3, 4, 5].forEach(j => { o.push1[k(j)] = JSON.parse(JSON.stringify(sets)); });
      localStorage.setItem('inrun_sets', JSON.stringify(o));
      _cal = null;
      return calibration();
    };
    return {
      charge: lire([{ w: 50, r: 12, rir: 3 }, { w: 40, r: 10, rir: 3 }, { w: 30, r: 8, rir: 3 }]),
      courte: lire([{ w: 50, r: 12, rir: 3 }, { w: 50, r: 8, rir: 3 }]),
      sansRir: lire([{ w: 50, r: 12, rir: null }, { w: 50, r: 10, rir: 3 }, { w: 50, r: 8, rir: 3 }])
    };
  });
  check('une charge qui baisse ne compte pas : la chute vient de la charge',
    garde.charge.n === 0 && garde.charge.biais === 0, JSON.stringify(garde.charge));
  check('deux séries ne suffisent pas à juger', garde.courte.n === 0, JSON.stringify(garde.courte));
  check('une séance au RIR incomplet est écartée', garde.sansRir.n === 0, JSON.stringify(garde.sansRir));

  // ---------- le biais change vraiment la décision de charge ----------
  /* Deux séances de suite au plafond de reps. La branche « ajoute une
     série plutôt qu'un kilo » exige une vraie marge : depuis que le RIR
     cible dépend du geste, le seuil est le HAUT de la cible de
     l'exercice — 3 sur un gros mouvement comme le chest press. Déclaré
     à 3, corrigé à 2, cette marge n'existe plus : le verdict doit
     changer alors que les séries, elles, sont identiques.
     Le contrat vérifié n'est pas le chiffre, c'est le fait que la
     calibration pèse réellement sur la décision. */
  const decision = await p.evaluate(() => {
    const t = new Date(), k = n => { const d = new Date(t); d.setDate(t.getDate() - n);
      return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0'); };
    const st = document.querySelector('.station[data-ex="push1"]');
    const pr = getPresc(st);
    const plafond = Array.from({ length: pr.sets }, () => ({ w: 50, r: pr.hi, rir: cibleRIR(pr).haut }));
    /* deux séances antérieures au plafond, pour armer le compteur de série */
    const o = { push1: {} };
    [k(2), k(4)].forEach(j => { o.push1[j] = JSON.parse(JSON.stringify(plafond)); });
    o.push1[k(0)] = JSON.parse(JSON.stringify(plafond));
    localStorage.setItem('inrun_sets', JSON.stringify(o));
    const lire = b => {
      _cal = { biais: b, n: 9, opt: 0, pess: 0, defaut: 1 };
      return { rir: avgRIR(plafond), txt: verdict(plafond, pr, 'push1', 2.5).txt };
    };
    const neutre = lire(0), corrige = lire(-1);
    _cal = null;
    return { pr: pr, cible: cibleRIR(pr).haut, neutre: neutre, corrige: corrige };
  });
  check('sans biais, un RIR au haut de la cible ouvre l\'option « ajoute une série »',
    decision.neutre.rir === decision.cible && /ᵉ série/.test(decision.neutre.txt),
    'RIR ' + decision.neutre.rir + '/' + decision.cible + ' — ' + decision.neutre.txt.slice(0, 70));
  check('le RIR utilisé par le verdict est la version corrigée',
    decision.corrige.rir === decision.cible - 1,
    'déclaré ' + decision.cible + ' → utilisé ' + decision.corrige.rir);
  check('… et la marge disparaissant, le coach bascule sur la montée de charge',
    !/ᵉ série/.test(decision.corrige.txt) && /monte à/.test(decision.corrige.txt),
    decision.corrige.txt.slice(0, 95));
  check('… sur des séries pourtant strictement identiques',
    decision.neutre.txt !== decision.corrige.txt);

  // ---------- la jauge d'équilibre suit le stimulus ----------
  await charger([{ id: 'push1', jour: 1, sets: S(12, 40, 10, 1) }, { id: 'push3', jour: 1, sets: S(12, 30, 12, 1) }]);
  const jDur = await p.evaluate(() => niveauCalcule().jauges.equilibre.v);
  await charger([{ id: 'push1', jour: 1, sets: S(12, 40, 10, 7) }, { id: 'push3', jour: 1, sets: S(12, 30, 12, 7) }]);
  const jMou = await p.evaluate(() => niveauCalcule().jauges.equilibre.v);
  check('à séries égales, des séances molles font baisser la jauge d\'équilibre',
    jDur > jMou, 'dur ' + jDur + ' vs mou ' + jMou);

  check('aucune erreur JS', errs.length === 0, errs.join(' | '));

  await br.close();
  console.log('\n=== PASS (' + ok.length + ') ===');
  ok.forEach(s => console.log('  ✓ ' + s));
  if (bad.length) { console.log('\n=== FAIL (' + bad.length + ') ==='); bad.forEach(s => console.log('  ✗ ' + s)); }
  process.exit(bad.length ? 1 : 0);
})().catch(e => { console.error('CRASH', e); process.exit(2); });
