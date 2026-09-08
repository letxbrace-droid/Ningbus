/* Nouvelle architecture : 4 onglets, séances en sélecteur, blocs redistribués. */
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
    await p.waitForTimeout(600);
    await p.evaluate(() => { try { closeRecap(); } catch (e) {} });
  };
  const K = n => p.evaluate(n => shiftKey(todayKey(), n), n);

  await p.goto(B + '/index.html', { waitUntil: 'domcontentloaded' });
  await p.waitForTimeout(800);
  /* Le récap hebdo s'ouvre le dimanche soir et intercepte tous les clics :
     sans ça le test échouait un jour sur sept, sans rapport avec l'app. */
  await p.evaluate(() => { try { closeRecap(); } catch (e) {} });
  await seed({});

  // ---------- 4 onglets ----------
  const nav = await p.evaluate(() => [...document.querySelectorAll('.bottomnav .bn .l')].map(e => e.textContent));
  check('la barre compte 4 onglets', nav.length === 4, nav.join(' · '));
  check('les onglets sont des rôles, pas des séances',
    JSON.stringify(nav) === JSON.stringify(["Aujourd'hui", 'Semaine', 'Progrès', 'Moi']), nav.join(','));
  const largeur = await p.evaluate(() => Math.round(document.querySelector('.bottomnav .bn').getBoundingClientRect().width));
  check('onglets deux fois plus larges qu\'avant (55 px)', largeur >= 90, largeur + ' px');

  // ---------- les 5 séances vivent dans Aujourd'hui ----------
  const dedans = await p.evaluate(() => ['push','pull','legs','upper','core']
    .every(id => document.getElementById('today').contains(document.getElementById(id))));
  check('les 5 séances sont dans l\'onglet Aujourd\'hui', dedans);
  const visibles = await p.evaluate(() => document.querySelectorAll('.sess.show').length);
  check('une seule séance visible à la fois', visibles === 1, visibles + ' visibles');

  // ---------- l'app ouvre sur la séance du jour ----------
  await seed({ inrun_plan: {} });
  await p.evaluate(async k => { const pl = {}; pl[k] = 'legs'; savePlan(pl); }, await K(0));
  await p.reload({ waitUntil: 'domcontentloaded' }); await p.waitForTimeout(700);
  await p.evaluate(() => { try { closeRecap(); } catch (e) {} });
  let ouverte = await p.evaluate(() => (document.querySelector('.sess.show') || {}).id);
  check('ouvre sur la séance prévue aujourd\'hui', ouverte === 'legs', ouverte);
  await seed({ inrun_trainlog: { [await K(0)]: 'pull' } });
  ouverte = await p.evaluate(() => (document.querySelector('.sess.show') || {}).id);
  check('ouvre sur la séance déjà commencée', ouverte === 'pull', ouverte);
  /* sur un onglet neuf : le navigateur ne restaure aucune position, donc on
     mesure bien notre propre comportement au démarrage */
  const p3 = await c.newPage();
  await p3.goto(B + '/index.html', { waitUntil: 'domcontentloaded' });
  await p3.waitForTimeout(1400);
  await p3.evaluate(() => { try { closeRecap(); } catch (e) {} });
  const noScroll = await p3.evaluate(() => scrollY);
  const ancre = await p3.evaluate(() => location.hash);
  await p3.close();
  check('aucun défilement parasite au démarrage', noScroll === 0, 'scrollY=' + noScroll);
  check('pas d\'ancre écrite sans action de l\'utilisateur', ancre === '', 'hash=' + ancre);

  // ---------- le sélecteur porte l'état ----------
  /* La coche dit « déjà fait cette semaine » : il faut donc un jour PASSÉ
     dans la semaine en cours. L'ancienne version semait un jour futur, que
     l'app reclasse (à raison) en intention plutôt qu'en séance faite — le
     test échouait donc tous les lundis, seul jour sans passé dans sa
     semaine. On fixe l'horloge à un jeudi et on sème le mardi. */
  const p4 = await c.newPage();
  p4.on('pageerror', e => errs.push(e.message));
  await p4.clock.install({ time: new Date('2026-08-27T10:00:00') });   /* jeudi */
  await p4.goto(B + '/index.html', { waitUntil: 'domcontentloaded' });
  await p4.waitForTimeout(800);
  await p4.evaluate(() => {
    localStorage.clear();
      localStorage.setItem('inrun_programme', 'rot5');
    localStorage.setItem('inrun_trainlog', JSON.stringify({ '2026-08-25': 'push' }));  /* mardi */
    localStorage.setItem('inrun_plan', JSON.stringify({ '2026-08-27': 'legs' }));
  });
  await p4.reload({ waitUntil: 'domcontentloaded' });
  await p4.waitForTimeout(800);
  await p4.evaluate(() => { try { closeRecap(); } catch (e) {} });
  const etat = await p4.evaluate(() => [...document.querySelectorAll('.sessbar .sc')].map(e => ({
    g: e.getAttribute('data-g'), point: !!e.querySelector('.dot'), coche: !!e.querySelector('.ok') })));
  await p4.close();
  check('point orange sur la séance du jour',
    etat.find(x => x.g === 'legs').point === true, JSON.stringify(etat));
  check('coche verte sur ce qui est déjà fait cette semaine',
    etat.find(x => x.g === 'push').coche === true, JSON.stringify(etat));
  check('rien d\'autre ne porte de marque', etat.filter(x => x.point || x.coche).length === 2, JSON.stringify(etat));

  // ---------- changer de séance ----------
  await p.click('.sessbar .sc[data-g="core"]');
  await p.waitForTimeout(400);
  check('le sélecteur change de séance', await p.evaluate(() => document.getElementById('core').classList.contains('show')));
  check('… sans quitter l\'onglet Aujourd\'hui', await p.evaluate(() => document.getElementById('today').classList.contains('show')));

  // ---------- anciennes ancres ----------
  for (const [ancre, attendu] of [['plan','semaine'], ['profil','progres'], ['legs','today']]) {
    const p2 = await c.newPage();
    await p2.goto(B + '/index.html#' + ancre, { waitUntil: 'domcontentloaded' });
    await p2.waitForTimeout(700);
    const vu = await p2.evaluate(() => (document.querySelector('.day.show') || {}).id);
    check('l\'ancienne ancre #' + ancre + ' retombe sur ' + attendu, vu === attendu, vu);
    await p2.close();
  }

  // ---------- répartition des blocs ----------
  const ou = await p.evaluate(() => {
    const dans = (page, sel) => !!document.getElementById(page).querySelector(sel);
    return {
      calSemaine: dans('semaine', '#calGrid'), stripSemaine: dans('semaine', '#weekStrip'),
      analyseSemaine: dans('semaine', '#planAnalysis'),
      poidsProgres: dans('progres', '#wchart'), forceProgres: dans('progres', '#pchart'),
      recapProgres: dans('progres', '#wkList'),
      reglagesMoi: dans('moi', '.settings'), sauvegardeMoi: dans('moi', '#io-file'),
      methodeRepliee: dans('moi', '.fold'), resetMoi: dans('moi', '.resetbar'),
      coachToday: dans('today', '#coachCard'), fatigueToday: dans('today', '[data-fatigue]')
    };
  });
  Object.keys(ou).forEach(k => check('bloc au bon endroit : ' + k, ou[k]));
  const nbFatigue = await p.evaluate(() => document.querySelectorAll('[data-fatigue]').length);
  check('le widget fatigue n\'est plus répété 5 fois', nbFatigue === 1, nbFatigue + ' exemplaires');
  const methodeOuverte = await p.evaluate(() => document.querySelector('#moi .fold').open);
  check('la méthode est repliée par défaut', methodeOuverte === false);

  // ---------- replis ----------
  const descAvant = await p.evaluate(() => getComputedStyle(document.querySelector('.sess.show .st-desc')).display);
  await p.click('.sess.show .station .st-head');
  await p.waitForTimeout(300);
  const descApres = await p.evaluate(() => getComputedStyle(document.querySelector('.sess.show .st-desc')).display);
  check('la technique est repliée par défaut', descAvant === 'none', descAvant);
  check('… et se déplie sur l\'en-tête', descApres !== 'none', descApres);
  await p.click('#coachCard'); await p.waitForTimeout(200);
  check('le message du coach se déplie', await p.evaluate(() => document.getElementById('coachCard').classList.contains('open')));

  // ---------- ergonomie ----------
  const petits = await p.evaluate(() => {
    const out = [];
    document.querySelectorAll('.sess.show [data-save], .sess.show .rb, .sessbar .sc, .bottomnav .bn, #today .fatigue button')
      .forEach(e => { const r = e.getBoundingClientRect();
        if (r.height && r.height < 44) out.push((e.className||e.tagName) + ' ' + Math.round(r.height)); });
    return out;
  });
  check('les commandes de séance font au moins 44 px', petits.length === 0, petits.join(', '));
  const deborde = await p.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 1);
  check('aucun débordement horizontal', !deborde);

  // ---------- le sélecteur reste accessible en défilant ----------
  /* html{scroll-behavior:smooth} : un délai fixe mesure une image en
     cours d'animation, pas la position finale. On attend que le
     défilement se stabilise. */
  await p.evaluate(() => scrollTo(0, 1200));
  await p.waitForFunction(() => {
    const y = Math.round(scrollY);
    if (window.__y === y) return true;
    window.__y = y; return false;
  }, null, { polling: 120, timeout: 5000 });
  const colle = await p.evaluate(() => {
    const r = document.querySelector('.sessbar-wrap').getBoundingClientRect();
    return r.top >= -1 && r.top < 60 && r.bottom > 0;
  });
  check('le sélecteur reste collé en haut pendant la séance', colle);

  check('aucune erreur JS', errs.length === 0, errs.join(' | '));

  await br.close();
  console.log('\n=== PASS (' + ok.length + ') ===');
  ok.forEach(s => console.log('  ✓ ' + s));
  if (bad.length) { console.log('\n=== FAIL (' + bad.length + ') ==='); bad.forEach(s => console.log('  ✗ ' + s)); }
  process.exit(bad.length ? 1 : 0);
})().catch(e => { console.error('CRASH', e); process.exit(2); });
