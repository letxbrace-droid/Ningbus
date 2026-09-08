/* La charge de référence.
   Bug remonté en séance réelle : le coach ancrait tous ses conseils sur la
   charge la PLUS LOURDE de la séance. Sur 36/36/41 il disait « reste à
   41 kg » alors que le travail s'était fait à 36 ; sur 18/14/14 il disait
   « reste à 18 kg » alors que 18 avait justement été abandonné.
   Pire : c'est le conseil en cours de séance qui avait ordonné la montée
   à 41 entre deux séries — puis le verdict la lui reprochait. */
const { chromium } = require('playwright-core');
const CTX = require('./contexte');
const B = CTX.BASE;
const ok = [], bad = [];
const check = (n, c, d) => (c ? ok : bad).push(n + (d ? ' — ' + d : ''));

function poser(seances) {
  localStorage.clear();
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

(async () => {
  const br = await chromium.launch({ executablePath: CTX.CHROMIUM, args: ['--no-sandbox'] });
  const c = await br.newContext({ viewport: { width: 390, height: 844 } });
  const p = await c.newPage();
  const errs = [];
  p.on('pageerror', e => errs.push(e.message));
  p.on('console', m => { if (m.type() === 'error' && !/Failed to load resource/.test(m.text())) errs.push(m.text()); });

  await p.goto(B + '/index.html', { waitUntil: 'domcontentloaded' });
  await p.waitForTimeout(600);

  // ================= 1 · la charge de référence =================
  const ref = await p.evaluate(() => ({
    fixe:   chargeRef([{ w: 36, r: 12 }, { w: 36, r: 12 }, { w: 36, r: 12 }]),
    montee: chargeRef([{ w: 36, r: 10 }, { w: 36, r: 12 }, { w: 41, r: 12 }]),
    repli:  chargeRef([{ w: 18, r: 6 }, { w: 14, r: 12 }, { w: 14, r: 11 }]),
    egalite: chargeRef([{ w: 40, r: 12 }, { w: 40, r: 12 }, { w: 45, r: 10 }, { w: 45, r: 10 }]),
    seule:  chargeRef([{ w: 22.5, r: 9 }])
  }));
  check('à charge constante, la référence est cette charge', ref.fixe === 36, String(ref.fixe));
  check('après une montée en fin de séance, la référence reste la charge travaillée',
    ref.montee === 36, String(ref.montee));
  check('après un repli, la référence est la charge d\'arrivée, pas celle abandonnée',
    ref.repli === 14, String(ref.repli));
  check('à égalité de séries, la référence est la plus récente — la dernière décision',
    ref.egalite === 45, String(ref.egalite));
  check('une série unique se juge sur elle-même', ref.seule === 22.5, String(ref.seule));

  // ================= 2 · les deux séances réelles =================
  const reel = await p.evaluate(() => {
    const pr = { sets: 3, lo: 12, hi: 12 };
    return {
      dips: verdict([{ w: 36, r: 10, rir: 2 }, { w: 36, r: 12, rir: null }, { w: 41, r: 12, rir: 1 }], pr, 'push1', 5),
      curl: verdict([{ w: 18, r: 6, rir: 0 }, { w: 14, r: 12, rir: 3 }, { w: 14, r: 11, rir: 1 }], pr, 'pull4', 2)
    };
  });
  check('dips 36/36/41 : le coach ne réclame plus la charge la plus lourde',
    !/41 kg<\/b>/.test(reel.dips.txt) && /36 kg<\/b>/.test(reel.dips.txt), reel.dips.txt.slice(0, 120));
  check('… il nomme le vrai problème : la charge a bougé en cours de séance',
    /chang. de charge/.test(reel.dips.txt) && /36 → 41/.test(reel.dips.txt), reel.dips.txt.slice(0, 90));
  check('… et 41 kg est présenté comme la marche d\'après, pas comme la consigne',
    /marche suivante sera 41/.test(reel.dips.txt), reel.dips.txt.slice(-90));
  check('curl 18/14/14 : le coach ne renvoie plus à 18 kg',
    !/18 kg<\/b>/.test(reel.curl.txt) && /14 kg<\/b>/.test(reel.curl.txt), reel.curl.txt.slice(0, 120));
  check('… il reconnaît l\'allègement au lieu de le reprocher',
    /bon réflexe/.test(reel.curl.txt), reel.curl.txt.slice(0, 90));
  check('les deux verdicts restent des « hold », jamais une montée',
    reel.dips.cls === 'hold' && reel.curl.cls === 'hold', reel.dips.cls + ' / ' + reel.curl.cls);

  // ================= 3 · non-régression à charge fixe =================
  const fixe = await p.evaluate(() => {
    const pr = { sets: 3, lo: 10, hi: 12 };
    return {
      plafond: verdict([{ w: 50, r: 12, rir: 2 }, { w: 50, r: 12, rir: 2 }, { w: 50, r: 12, rir: 2 }], pr, 'push1', 2.5),
      dedans:  verdict([{ w: 50, r: 11, rir: 2 }, { w: 50, r: 10, rir: 2 }, { w: 50, r: 10, rir: 2 }], pr, 'push1', 2.5),
      dessous: verdict([{ w: 50, r: 8, rir: 2 }, { w: 50, r: 9, rir: 2 }, { w: 50, r: 8, rir: 2 }], pr, 'push1', 2.5),
      courte:  verdict([{ w: 50, r: 12, rir: 2 }, { w: 50, r: 12, rir: 2 }], pr, 'push1', 2.5)
    };
  });
  check('charge fixe au plafond : la montée de charge fonctionne toujours',
    fixe.plafond.cls === 'up' && /monte à 52,5 kg/.test(fixe.plafond.txt), fixe.plafond.txt.slice(0, 90));
  check('charge fixe dans la fourchette : on gratte des reps', /gratte/.test(fixe.dedans.txt));
  check('charge fixe sous la fourchette : on tient la charge', /Reste à <b>50 kg/.test(fixe.dessous.txt));
  check('séries manquantes : le compte prime encore', /manquait 1 série/.test(fixe.courte.txt));
  check('aucun de ces verdicts ne porte l\'incise « jugé sur »',
    !/jugé sur/.test(fixe.plafond.txt + fixe.dedans.txt + fixe.dessous.txt + fixe.courte.txt));

  // ============ 4 · prescription bouclée + série d'essai plus lourde ============
  const essai = await p.evaluate(() => verdict(
    [{ w: 40, r: 12, rir: 2 }, { w: 40, r: 12, rir: 2 }, { w: 40, r: 12, rir: 2 }, { w: 45, r: 7, rir: 0 }],
    { sets: 3, lo: 10, hi: 12 }, 'push1', 2.5));
  check('une série d\'essai plus lourde ne casse pas le verdict de la charge travaillée',
    essai.cls === 'up' && /monte à 42,5 kg/.test(essai.txt), essai.txt.slice(0, 100));
  check('… et le coach dit sur quoi il a jugé', /jugé sur tes 3 séries à 40 kg/.test(essai.txt), essai.txt.slice(-80));

  // ================= 5 · le plafond d'une séance mixte =================
  const plaf = await p.evaluate(() => {
    const pr = { sets: 3, lo: 10, hi: 12 };
    return {
      complet: auPlafond([{ w: 40, r: 12 }, { w: 40, r: 12 }, { w: 40, r: 12 }, { w: 45, r: 6 }], pr),
      partiel: auPlafond([{ w: 40, r: 12 }, { w: 40, r: 12 }, { w: 45, r: 12 }], pr),
      court:   auPlafond([{ w: 40, r: 12 }, { w: 40, r: 12 }], pr),
      vide:    auPlafond([], pr)
    };
  });
  check('le plafond exige la prescription entière à une même charge',
    plaf.complet === true && plaf.partiel === false, JSON.stringify(plaf));
  check('trop peu de séries : jamais un plafond', plaf.court === false && plaf.vide === false);

  // ================= 6 · en cours de séance : on ne charge plus entre deux séries =================
  const pendant = await p.evaluate(() => {
    const st = document.querySelector('.station[data-ex="push1"]');
    const pr = getPresc(st);
    const lire = sets => {
      const o = {}; o.push1 = {}; o.push1[todayKey()] = sets;
      saveExSets(o); refreshStation(st);
      return st.querySelector('[data-advice]').textContent;
    };
    return {
      pr: pr,
      plafond: lire([{ w: 36, r: pr.hi, rir: 1 }]),
      lourd:   lire([{ w: 18, r: Math.max(1, pr.lo - 4), rir: 0 }]),
      court:   lire([{ w: 36, r: Math.max(1, pr.lo - 1), rir: 1 }])
    };
  });
  check('haut de fourchette dès la 1re série : le coach ne fait plus monter la charge en séance',
    !/monte à/.test(pendant.plafond) && /reste à 36 kg/.test(pendant.plafond), pendant.plafond.slice(0, 110));
  check('… il annonce la montée pour la séance suivante',
    /prochaine séance/.test(pendant.plafond), pendant.plafond.slice(0, 110));
  check('une charge franchement trop lourde reste la seule exception : on allège',
    /redescends à/.test(pendant.lourd), pendant.lourd.slice(0, 110));
  check('quelques reps de trop peu ne justifient pas de changer de charge',
    !/redescends|monte à/.test(pendant.court) && /garde 36 kg/.test(pendant.court), pendant.court.slice(0, 110));

  // ================= 7 · le rappel de la séance précédente =================
  const rappel = await p.evaluate(poser, [{ id: 'push1', jour: 2, sets: [{ w: 36, r: 10, rir: 2 }, { w: 36, r: 12, rir: 1 }, { w: 41, r: 12, rir: 1 }] }]);
  await p.reload({ waitUntil: 'domcontentloaded' });
  await p.waitForTimeout(700);
  const lbl = await p.evaluate(() => document.querySelector('.station[data-ex="push1"] [data-prev]').textContent);
  check('le rappel n\'affiche plus « 3 × 41 kg » pour une séance faite à 36',
    /3 × 36 \(36-41\) kg/.test(lbl), lbl);

  check('aucune erreur JS', errs.length === 0, errs.join(' | '));

  await br.close();
  console.log('\n=== PASS (' + ok.length + ') ===');
  ok.forEach(s => console.log('  ✓ ' + s));
  if (bad.length) { console.log('\n=== FAIL (' + bad.length + ') ==='); bad.forEach(s => console.log('  ✗ ' + s)); }
  process.exit(bad.length ? 1 : 0);
})().catch(e => { console.error('CRASH', e); process.exit(2); });
