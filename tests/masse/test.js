const { chromium } = require('playwright-core');

const CTX = require('./contexte');
const BASE = CTX.BASE;
const ok = [], bad = [];
function check(name, cond, detail) { (cond ? ok : bad).push(name + (detail ? ' — ' + detail : '')); }

(async () => {
  const browser = await chromium.launch({
    executablePath: CTX.CHROMIUM,
    args: ['--no-sandbox']
  });
  const ctx = await browser.newContext({ viewport: { width: 390, height: 844 } });
  const page = await ctx.newPage();
  const errors = [], failed = [];
  page.on('pageerror', e => errors.push('pageerror: ' + e.message));
  page.on('console', m => { if (m.type() === 'error') errors.push('console: ' + m.text()); });
  page.on('requestfailed', r => failed.push(r.url() + ' :: ' + (r.failure() || {}).errorText));

  await page.goto(BASE + '/index.html', { waitUntil: 'load' });
  /* Deux programmes cohabitent dans la page : une suite qui exerce la
     rotation doit l'épingler, sinon elle mesure « le programme par
     défaut du jour » et vise des séances masquées. */
  await page.evaluate(() => localStorage.setItem('inrun_programme', 'rot5'));
  await page.reload({ waitUntil: 'load' });
  await page.waitForTimeout(1500);

  const appErrors = errors.filter(e => !/Failed to load resource/.test(e));
  check('page loads without JS error', appErrors.length === 0, appErrors.join(' | '));
  check('only Google Fonts fails (bac a sable hors ligne)', failed.every(f => /fonts\.googleapis\.com|fonts\.gstatic\.com/.test(f)), failed.join(' | '));

  // --- manifest & icons reachable ---
  for (const f of ['manifest.json', 'sw.js', 'icon.svg', 'icon-192.png', 'icon-512.png', 'icon-180.png', 'icon-maskable-512.png', '_headers']) {
    const r = await page.request.get(BASE + '/' + f);
    check('GET ' + f, r.ok(), 'status ' + r.status());
  }
  const man = await (await page.request.get(BASE + '/manifest.json')).json();
  check('manifest parses & has icons', Array.isArray(man.icons) && man.icons.length >= 3);

  // --- service worker ---
  const swState = await page.evaluate(async () => {
    const reg = await navigator.serviceWorker.getRegistration();
    if (!reg) return { registered: false };
    await navigator.serviceWorker.ready;
    return { registered: true, scope: reg.scope, controller: !!navigator.serviceWorker.controller };
  });
  check('service worker registered', swState.registered, JSON.stringify(swState));

  // --- station interaction: log a set ---
  await page.fill('.sess[data-prog="rot5"] .station[data-ex="push1"] [data-w]', '40');
  await page.fill('.sess[data-prog="rot5"] .station[data-ex="push1"] [data-r]', '10');
  await page.click('.sess[data-prog="rot5"] .station[data-ex="push1"] [data-rir] .rb[data-v="2"]');
  await page.click('.sess[data-prog="rot5"] .station[data-ex="push1"] [data-save]');
  await page.waitForTimeout(300);
  const chip = await page.textContent('.sess[data-prog="rot5"] .station[data-ex="push1"] [data-sets]');
  check('set chip rendered', /40\s*×\s*10/.test(chip.replace(/ /g, ' ')), JSON.stringify(chip));
  const advice = await page.textContent('.sess[data-prog="rot5"] .station[data-ex="push1"] [data-advice]');
  check('coach advice rendered', advice && advice.length > 20, advice);
  const rm = await page.textContent('.sess[data-prog="rot5"] .station[data-ex="push1"] [data-rm]');
  check('1RM shown', /kg/.test(rm || ''), rm);

  // --- rest timer started (timer setting default on) ---
  const ringOn = await page.evaluate(() => document.getElementById('restRing').classList.contains('show'));
  check('rest timer auto-started', ringOn);
  await page.evaluate(() => stopRest());

  // --- calendar auto-mark for the "upper/bras" group (the up* bug) ---
  await page.click('.sessbar .sc[data-g="upper"]');       // Bras
  await page.waitForTimeout(300);
  await page.fill('.sess[data-prog="rot5"] .station[data-ex="up1"] [data-w]', '20');
  await page.fill('.sess[data-prog="rot5"] .station[data-ex="up1"] [data-r]', '12');
  await page.click('.sess[data-prog="rot5"] .station[data-ex="up1"] [data-save]');
  await page.waitForTimeout(300);
  const tlog = await page.evaluate(() => JSON.parse(localStorage.getItem('inrun_trainlog') || '{}'));
  const today = new Date();
  const k = today.getFullYear() + '-' + String(today.getMonth() + 1).padStart(2, '0') + '-' + String(today.getDate()).padStart(2, '0');
  check('bras session auto-marked in calendar', tlog[k] === 'upper', JSON.stringify(tlog));
  const vol = await page.evaluate(() => weeklyVolume(7));
  check('bras volume counted', vol.byGroup.upper === 1, JSON.stringify(vol.byGroup));

  // --- weight tracking + IMC ---
  await page.evaluate(() => { document.getElementById('wlog-in').value = '88'; addWeight(); });
  const imc = await page.textContent('#imc-val');
  check('IMC computed', /^\d+\.\d$/.test(imc.trim()), imc);

  // --- export payload ---
  const dump = await page.evaluate(() => {
    const KEYS = ['inrun_masse_log', 'inrun_hist', 'inrun_poids', 'inrun_profil', 'inrun_masse_set',
      'inrun_trainlog', 'inrun_sets', 'inrun_weeks', 'inrun_prescriptions', 'inrun_fatigue'];
    const o = {}; KEYS.forEach(k => { const v = localStorage.getItem(k); if (v !== null) o[k] = v; });
    return o;
  });
  check('export covers stored keys', Object.keys(dump).length >= 5, Object.keys(dump).join(','));

  // --- prescription tools ---
  await page.click('.sessbar .sc[data-g="push"]');       // retour Poussée
  await page.waitForTimeout(300);
  await page.click('.sess[data-prog="rot5"] .station[data-ex="push1"] .presc-edit');
  await page.click('.sess[data-prog="rot5"] .station[data-ex="push1"] .presc-tools [data-p="sets"][data-d="1"]');
  const sets = await page.textContent('.sess[data-prog="rot5"] .station[data-ex="push1"] .presc .cv');
  check('prescription adjustable', sets.trim() === '5', sets);

  // --- fatigue + navigation ---
  await page.click('#today [data-fatigue] [data-f="forme"]');
  const fat = await page.evaluate(() => todayFat());
  check('fatigue stored', fat === 'forme', String(fat));

  await page.click('.bottomnav .bn:nth-child(2)');       // Semaine
  await page.waitForTimeout(400);
  const planVisible = await page.evaluate(() => document.getElementById('semaine').classList.contains('show'));
  check('nav to Semaine works', planVisible);
  check('hash updated', page.url().endsWith('#semaine'), page.url());
  const cal = await page.evaluate(() => document.getElementById('calGrid').children.length);
  check('calendar rendered', cal > 27, 'cells ' + cal);
  const analysis = await page.evaluate(() => document.getElementById('planAnalysis').textContent.trim().length);
  check('coach analysis rendered', analysis > 20, 'len ' + analysis);

  // --- deep link via hash (manifest shortcut) ---
  const p2 = await ctx.newPage();
  await p2.goto(BASE + '/index.html#legs', { waitUntil: 'load' });
  await p2.waitForTimeout(800);
  const legsVisible = await p2.evaluate(() => document.getElementById('legs').classList.contains('show')
    && document.getElementById('today').classList.contains('show'));
  check('manifest shortcut #legs opens Jambes dans Aujourd\'hui', legsVisible);
  await p2.close();

  // --- reset only wipes perfs, keeps calendar ---
  page.on('dialog', d => d.accept());
  await page.evaluate(() => resetLogs());
  await page.waitForTimeout(300);
  const after = await page.evaluate(() => ({
    sets: localStorage.getItem('inrun_sets'),
    hist: localStorage.getItem('inrun_hist'),
    cal: localStorage.getItem('inrun_trainlog'),
    poids: localStorage.getItem('inrun_poids'),
    chip: document.querySelector('.sess[data-prog="rot5"] .station[data-ex="push1"] [data-sets]').innerHTML
  }));
  check('reset wipes perfs', !after.sets && !after.hist, JSON.stringify(after.sets));
  check('reset keeps calendar & poids', !!after.cal && !!after.poids);
  check('reset clears displayed sets', after.chip === '', after.chip);

  // --- offline ---
  await ctx.setOffline(true);
  const r2 = await page.goto(BASE + '/index.html', { waitUntil: 'load' }).catch(e => ({ err: e.message }));
  const title = await page.title().catch(() => '');
  check('offline reload serves the app', /Masse/.test(title), 'title=' + title + ' ' + (r2 && r2.err ? r2.err : ''));
  /* deux programmes cohabitent dans la page : ce qui compte est que le
     programme ACTIF soit rendu entièrement hors ligne, pas le total du
     fichier — sinon l'assertion casse à chaque évolution de l'autre. */
  const stationsOffline = await page.evaluate(() =>
    document.querySelectorAll(selActif() + ' .station').length).catch(() => 0);
  check('offline page fully rendered', stationsOffline === 26, 'stations ' + stationsOffline);
  await ctx.setOffline(false);

  await browser.close();

  console.log('\n=== PASS (' + ok.length + ') ===');
  ok.forEach(s => console.log('  ✓ ' + s));
  if (bad.length) { console.log('\n=== FAIL (' + bad.length + ') ==='); bad.forEach(s => console.log('  ✗ ' + s)); }
  if (failed.length) { console.log('\n--- requêtes échouées ---'); [...new Set(failed)].forEach(s => console.log('  · ' + s)); }
  process.exit(bad.length ? 1 : 0);
})().catch(e => { console.error('TEST CRASH', e); process.exit(2); });
