/* Nouveaux : page Abdos (groupe core) et journée Piscine (swim). */
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
  p.on('console', m => { if (m.type() === 'error' && !/Failed to load resource/.test(m.text())) errs.push(m.text()); });

  const seed = async d => {
    await p.evaluate(x => { localStorage.clear();
      localStorage.setItem('inrun_programme', 'rot5'); Object.keys(x).forEach(k => localStorage.setItem(k, JSON.stringify(x[k]))); }, d);
    await p.reload({ waitUntil: 'domcontentloaded' });
    await p.waitForTimeout(500);
  };
  const K = n => p.evaluate(n => shiftKey(todayKey(), n), n);

  await p.goto(B + '/index.html', { waitUntil: 'domcontentloaded' });
  await p.waitForTimeout(700);
  await seed({});

  // ---------- la page Abdos ----------
  const nStations = await p.evaluate(() => document.querySelectorAll('#core .station').length);
  check('la page Abdos existe avec ses machines (mollets inclus)', nStations === 5, nStations + ' stations');
  const noms = await p.evaluate(() => [...document.querySelectorAll('#core .st-name')].map(e => e.textContent));
  check('lombaires inclus dans la ceinture', noms.some(n => /lombaire/i.test(n)), noms.join(' · '));

  const navL = await p.evaluate(() => [...document.querySelectorAll('.bottomnav .bn .l')].map(e => e.textContent));
  check('barre de navigation à 4 onglets', navL.length === 4, navL.join(','));
  const chips = await p.evaluate(() => [...document.querySelectorAll('.sessbar .sc')].map(e => e.getAttribute('data-g')));
  check('Abdos dans le sélecteur de séance', chips.includes('core'), chips.join(','));
  await p.click('.sessbar .sc[data-g="core"]');
  await p.waitForTimeout(400);
  const visible = await p.evaluate(() => document.getElementById('core').classList.contains('show'));
  check('le sélecteur ouvre la séance Abdos', visible);
  check('ancre #core', p.url().endsWith('#core'), p.url());
  // aucun libellé de la barre ne déborde de son onglet
  const debord = await p.evaluate(() => [...document.querySelectorAll('.bottomnav .bn')]
    .filter(b => b.querySelector('.l').scrollWidth > b.clientWidth).map(b => b.querySelector('.l').textContent));
  check('les 7 onglets tiennent sans déborder', debord.length === 0, debord.join(','));

  // ---------- une série d'abdos alimente le coach ----------
  await p.fill('.station[data-ex="core1"] [data-w]', '30');
  await p.fill('.station[data-ex="core1"] [data-r]', '14');
  await p.click('.station[data-ex="core1"] [data-save]');
  await p.waitForTimeout(400);
  const j0 = await K(0);
  const tlog = await p.evaluate(() => loadTLog());
  check('la séance abdos se marque dans le calendrier', tlog[j0] === 'core', JSON.stringify(tlog));
  const vol = await p.evaluate(() => weeklyVolume(7));
  check('le volume abdos est compté', vol.byGroup.core === 1, JSON.stringify(vol.byGroup));
  const adv = await p.evaluate(() => document.querySelector('.station[data-ex="core1"] [data-advice]').textContent);
  check('le coach conseille sur les abdos', /Série 2/.test(adv), adv.slice(0, 90));

  // ---------- récupération plus courte pour la ceinture ----------
  await seed({ inrun_trainlog: { [await K(0)]: 'core' } });
  let v = await p.evaluate(k => verdictJour(k, 'core'), await K(1));
  check('abdos le lendemain : autorisé mais nuancé', v.niveau === 'warn', v.niveau + ' / ' + v.txt);
  v = await p.evaluate(k => verdictJour(k, 'core'), await K(2));
  check('abdos à 48 h : feu vert', v.niveau === 'ok', v.niveau + ' / ' + v.txt);
  // pendant que les gros groupes restent à 48 h
  await seed({ inrun_trainlog: { [await K(0)]: 'legs' } });
  v = await p.evaluate(k => verdictJour(k, 'legs'), await K(1));
  check('les jambes gardent leurs 48 h strictes', v.niveau === 'stop', v.niveau);

  // ---------- la piscine ----------
  await seed({});
  v = await p.evaluate(k => verdictJour(k, 'swim'), await K(1));
  check('la piscine ne se refuse jamais', v.niveau === 'ok', v.niveau + ' / ' + v.txt);
  await seed({ inrun_trainlog: { [await K(0)]: 'legs' } });
  v = await p.evaluate(k => verdictJour(k, 'swim'), await K(1));
  check('… et le lendemain d\'une séance, elle est encouragée',
    v.niveau === 'ok' && /articulations/.test(v.txt), v.txt);

  await seed({});
  await p.evaluate(k => { openPick(k); choisirJour('swim'); }, j0);
  await p.waitForTimeout(300);
  const log2 = await p.evaluate(() => loadTLog());
  check('la piscine s\'enregistre comme journée', log2[j0] === 'swim', JSON.stringify(log2));
  const total = await p.evaluate(() => document.getElementById('stTotal').textContent);
  check('la piscine ne compte pas comme séance de muscu', total === '0', 'total=' + total);

  // elle ne casse pas le raisonnement sur les jours d'affilée
  await seed({ inrun_trainlog: { [await K(-3)]: 'push', [await K(-2)]: 'pull', [await K(-1)]: 'swim' } });
  v = await p.evaluate(k => verdictJour(k, 'legs'), j0);
  check('un jour piscine coupe la série de jours d\'affilée', v.niveau === 'ok', v.niveau + ' / ' + v.txt);

  // ---------- abdos proposés par le planificateur ----------
  //  4 groupes déjà passés, espacés (sinon c'est un repos qu'il faut conseiller),
  //  la ceinture jamais travaillée : c'est elle qui doit sortir.
  await seed({ inrun_trainlog: { [await K(-1)]: 'legs', [await K(-3)]: 'push', [await K(-5)]: 'pull', [await K(-7)]: 'upper' } });
  const sug = await p.evaluate(k => suggestGroup(k), j0);
  check('le planificateur propose les abdos quand ils manquent', sug === 'core', 'suggéré: ' + sug);
  await seed({});
  await p.evaluate(() => proposerSemaine());
  await p.waitForTimeout(400);
  const plan = await p.evaluate(() => loadPlan());
  check('la semaine générée inclut les abdos',
    Object.keys(plan).some(k => plan[k] === 'core'), JSON.stringify(plan));

  // ---------- récap et analyse ----------
  await seed({ inrun_trainlog: { [await K(-1)]: 'swim', [await K(-2)]: 'core' } });
  const analyse = await p.evaluate(() => document.getElementById('planAnalysis').textContent);
  check('l\'analyse compte la piscine comme cardio', /cardio/i.test(analyse), analyse.slice(0, 140));
  const legend = await p.evaluate(() => document.querySelector('.cal-legend').textContent);
  check('la légende du calendrier liste Abdos et Piscine',
    /Abdos/.test(legend) && /Piscine/.test(legend), legend.replace(/\s+/g, ' '));

  check('aucune erreur JS', errs.length === 0, errs.join(' | '));

  await br.close();
  console.log('\n=== PASS (' + ok.length + ') ===');
  ok.forEach(s => console.log('  ✓ ' + s));
  if (bad.length) { console.log('\n=== FAIL (' + bad.length + ') ==='); bad.forEach(s => console.log('  ✗ ' + s)); }
  process.exit(bad.length ? 1 : 0);
})().catch(e => { console.error('CRASH', e); process.exit(2); });
