/* Passage de minuit sur une PWA restée ouverte : horloge simulée. */
const { chromium } = require('playwright-core');
const CTX = require('./contexte');
const B = CTX.BASE;
const ok = [], bad = [];
const check = (n, c, d) => (c ? ok : bad).push(n + (d ? ' — ' + d : ''));

(async () => {
  const br = await chromium.launch({ executablePath: CTX.CHROMIUM, args: ['--no-sandbox'] });
  const c = await br.newContext({ viewport: { width: 390, height: 844 } });
  const p = await c.newPage();
  const errs = [];
  p.on('pageerror', e => errs.push(e.message));

  // samedi 22 août 2026, 23 h 58 — heure locale du navigateur
  await p.clock.install({ time: new Date('2026-08-22T23:58:00') });
  await p.goto(B + '/index.html', { waitUntil: 'domcontentloaded' });
  await p.waitForTimeout(400);
  await p.evaluate(() => {
    localStorage.clear();
    const l = {}; l['2026-08-22'] = 'push'; saveTLog(l);
    const f = {}; f['2026-08-22'] = 'forme';
    localStorage.setItem('inrun_fatigue', JSON.stringify(f));
    const pl = {}; pl['2026-08-23'] = 'legs'; savePlan(pl);
  });
  await p.reload({ waitUntil: 'domcontentloaded' });
  await p.waitForTimeout(600);

  const avant = await p.evaluate(() => ({
    date: document.getElementById('todayDate').textContent,
    jour: todayKey(),
    fatigue: !!document.querySelector('[data-fatigue] .sel'),
    coach: document.getElementById('coachMsg').textContent.slice(0, 60),
    strip: [...document.querySelectorAll('#weekStrip .ws')].findIndex(e => e.classList.contains('today'))
  }));
  check('avant minuit : samedi 22', /samedi 22/.test(avant.date) && avant.jour === '2026-08-22',
    avant.date + ' / ' + avant.jour);
  check('… la forme du jour est déclarée', avant.fatigue);
  check('… samedi est le jour marqué dans la bande', avant.strip === 5, 'index ' + avant.strip);

  // on franchit minuit, la page reste ouverte
  await p.clock.fastForward('00:04:00');
  await p.waitForTimeout(800);

  const apres = await p.evaluate(() => ({
    date: document.getElementById('todayDate').textContent,
    jour: todayKey(),
    fatigue: !!document.querySelector('[data-fatigue] .sel'),
    strip: [...document.querySelectorAll('#weekStrip .ws')].findIndex(e => e.classList.contains('today')),
    cal: !!document.querySelector('#calGrid .cd.today'),
    calJour: (document.querySelector('#calGrid .cd.today .cdn') || {}).textContent
  }));
  check('après minuit : la date bascule sur dimanche 23',
    /dimanche 23/.test(apres.date), apres.date);
  check('… la bande de la semaine suit', apres.strip === 6, 'index ' + apres.strip);
  check('… le calendrier marque le 23', apres.calJour === '23', apres.calJour);
  check('… la forme déclarée hier ne compte plus', apres.fatigue === false);

  // la séance affichée n'a pas bougé sous les doigts
  const sess = await p.evaluate(() => (document.querySelector('.sess.show') || {}).id);
  check('la séance ouverte ne bascule pas toute seule', !!sess, sess);

  // et une série enregistrée après minuit part sur le bon jour
  await p.evaluate(() => showSess('legs', true));
  await p.waitForTimeout(200);
  await p.fill('.sess.show [data-w]', '80');
  await p.fill('.sess.show [data-r]', '10');
  await p.click('.sess.show [data-save]');
  await p.waitForTimeout(400);
  const sets = await p.evaluate(() => loadExSets());
  const ids = Object.keys(sets);
  check('une série après minuit est datée du 23',
    ids.length === 1 && !!sets[ids[0]]['2026-08-23'], JSON.stringify(sets));

  // retour au premier plan après une longue veille
  await p.clock.fastForward('30:00:00');            // deux jours plus tard
  await p.evaluate(() => document.dispatchEvent(new Event('visibilitychange')));
  await p.waitForTimeout(600);
  const tard = await p.evaluate(() => document.getElementById('todayDate').textContent);
  check('au retour après deux jours de veille, la date est à jour',
    /24|lundi/.test(tard), tard);

  check('aucune erreur JS', errs.length === 0, errs.join(' | '));

  await br.close();
  console.log('\n=== PASS (' + ok.length + ') ===');
  ok.forEach(s => console.log('  ✓ ' + s));
  if (bad.length) { console.log('\n=== FAIL (' + bad.length + ') ==='); bad.forEach(s => console.log('  ✗ ' + s)); }
  process.exit(bad.length ? 1 : 0);
})().catch(e => { console.error('CRASH', e); process.exit(2); });
