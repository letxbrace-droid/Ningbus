/* Le coach doit dire autre chose selon la forme du jour et selon ce que
   les muscles ont réellement encaissé. */
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

  await p.goto(B + '/index.html', { waitUntil: 'domcontentloaded' });
  await p.waitForTimeout(700);

  // ---------- la carte anatomique est bien lue dans le DOM ----------
  const carte = await p.evaluate(() => carteMuscles());
  const stations = Object.keys(carte);
  const vides = stations.filter(id => !carte[id].length);
  /* le NOMBRE de stations n'est pas un invariant : il bouge dès qu'on ajoute
     une séance (les 10 stations maison de la v36). Ce qui doit tenir, c'est
     que chacune soit rattachée à au moins un muscle. */
  check('chaque station est rattachée à des muscles', stations.length >= 26 && vides.length === 0,
    vides.join(', ') || stations.length + ' stations');
  check('la chest press vise pecs, triceps et deltoïde antérieur',
    JSON.stringify(carte.push1) === JSON.stringify(['pecs', 'triceps', 'deltAnt']), JSON.stringify(carte.push1));
  check('les élévations latérales isolent le deltoïde latéral',
    JSON.stringify(carte.push4) === JSON.stringify(['deltLat']), JSON.stringify(carte.push4));
  check('l\'extension lombaire couvre lombaires, fessiers et ischios',
    JSON.stringify(carte.core4) === JSON.stringify(['lombaires', 'fessiers', 'ischios']), JSON.stringify(carte.core4));

  // ---------- volume pondéré : entier pour le moteur, moitié pour l'assistant ----------
  await p.evaluate(() => {
    localStorage.clear();
      localStorage.setItem('inrun_programme', 'rot5');
    const t = new Date(), k = n => { const d = new Date(t); d.setDate(t.getDate() + n);
      return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0'); };
    const serie = n => Array.from({ length: n }, () => ({ w: 40, r: 10, rir: 2 }));
    const s = {}; s.push1 = {}; s.push1[k(-1)] = serie(4);
    s.push2 = {}; s.push2[k(-1)] = serie(4);      /* développé épaules */
    s.push3 = {}; s.push3[k(-1)] = serie(3);      /* pec deck */
    s.push5 = {}; s.push5[k(-1)] = serie(3);      /* triceps poulie */
    localStorage.setItem('inrun_sets', JSON.stringify(s));
    const l = {}; l[k(-1)] = 'push'; localStorage.setItem('inrun_trainlog', JSON.stringify(l));
  });
  await p.reload({ waitUntil: 'domcontentloaded' }); await p.waitForTimeout(600);
  const vol = await p.evaluate(() => volumeParMuscle(7));
  /* Séance seedée : chest press 4, développé épaules 4, pec deck 3, triceps 3.
     Pectoraux   = 4 (chest press moteur) + 3 (pec deck moteur)            = 7
     Triceps     = 4×0,5 + 4×0,5 (assistant) + 3 (extensions moteur)       = 7
     Delt. ant.  = 4×0,5 (chest press) + 4 (développé épaules moteur)      = 6
     Delt. lat.  = rien : aucune élévation dans cette séance                */
  check('le muscle moteur compte une série entière', vol.pecs === 7, 'pecs ' + vol.pecs);
  check('le muscle assistant compte une demi-série', vol.triceps === 7, 'triceps ' + vol.triceps);
  check('… y compris quand il cumule les deux rôles', vol.deltAnt === 6, 'deltAnt ' + vol.deltAnt);
  check('un muscle non sollicité reste à zéro', vol.deltLat === 0, 'deltLat ' + vol.deltLat);

  // ---------- la prescription change avec la forme du jour ----------
  const dire = async etat => {
    await p.evaluate(e => {
      const f = {}; if (e) f[todayKey()] = e;
      localStorage.setItem('inrun_fatigue', JSON.stringify(f));
      renderFatigue(); updateCoach();
    }, etat);
    await p.waitForTimeout(150);
    return p.evaluate(() => document.getElementById('coachSig').textContent);
  };
  /* Le profil seedé n'a qu'une séance : niveau 1. À ce palier le coach
     prescrit la technique avant la charge — 3 reps en réserve un jour
     normal, une répétition de plus plutôt qu'un kilo un jour favorable. */
  const sNormal = await dire(null), sForme = await dire('forme'), sFatigue = await dire('fatigue');
  check('niveau 1, en forme : une répétition de plus, pas un kilo',
    /2 reps en réserve/.test(sForme) && /répétition plutôt qu'un kilo/.test(sForme), sForme.slice(0, 80));
  check('fatigué : il interdit la montée de charge',
    /3 reps en réserve/.test(sFatigue) && /aucune montée de charge/i.test(sFatigue), sFatigue.slice(0, 80));
  check('niveau 1, jour normal : 3 reps en réserve et amplitude complète',
    /3 reps en réserve/.test(sNormal) && /amplitude complète/.test(sNormal) && !/aucune montée/.test(sNormal),
    sNormal.slice(0, 80));
  check('les trois prescriptions sont distinctes',
    new Set([sNormal, sForme, sFatigue]).size === 3);
  check('un jour creux, le conseil bascule sur le sommeil',
    /sommeil/.test(sFatigue) && !/sommeil/.test(sNormal), sFatigue.slice(-60));

  // ---------- le message nomme le muscle en dette, pas la séance ----------
  await dire(null);
  const msg = await p.evaluate(() => document.getElementById('coachMsg').textContent);
  check('le message cite un muscle précis et sa cible',
    /(Deltoïde latéral|Mollets|Ischio-jambiers|Quadriceps|Grand dorsal|Biceps|Lombaires)/.test(msg) && /cible de \d+-\d+/.test(msg),
    msg.slice(-150));

  // ---------- saturation : le coach freine ----------
  await p.evaluate(() => {
    const t = new Date(), k = n => { const d = new Date(t); d.setDate(t.getDate() + n);
      return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0'); };
    const s = JSON.parse(localStorage.getItem('inrun_sets'));
    s.push4 = {};                       /* élévations latérales : 30 séries sur 3 jours */
    [-1, -2, -3].forEach(n => { s.push4[k(n)] = Array.from({ length: 10 }, () => ({ w: 10, r: 15, rir: 2 })); });
    localStorage.setItem('inrun_sets', JSON.stringify(s));
    updateCoach(); analyzeTraining();
  });
  await p.waitForTimeout(200);
  const msgSat = await p.evaluate(() => document.getElementById('coachMsg').textContent);
  check('30 séries d\'élévations → le coach signale le plafond',
    /Deltoïde latéral : 30 séries/.test(msgSat) && /plafond utile/.test(msgSat), msgSat.slice(-140));

  // ---------- l'analyse hebdo détaille les muscles ----------
  const analyse = await p.evaluate(() => document.getElementById('planAnalysis').textContent);
  check('l\'analyse liste les séries stimulantes par muscle',
    /Séries stimulantes par muscle/.test(analyse), analyse.slice(0, 120));
  check('… et nomme la dette avec sa cible', /En dette/.test(analyse) && /\d+\/\d+/.test(analyse),
    (analyse.match(/En dette[^.]{0,90}/) || [''])[0]);
  check('… en expliquant pourquoi ce muscle d\'abord',
    /(largeur d'épaules|fibres lentes|déséquilibre le genou|protègent ton dos|volume du buste|largeur en V|pic du bras|plus grosse masse)/.test(analyse),
    'motif présent');

  check('aucune erreur JS', errs.length === 0, errs.join(' | '));

  await br.close();
  console.log('\n=== PASS (' + ok.length + ') ===');
  ok.forEach(s => console.log('  ✓ ' + s));
  if (bad.length) { console.log('\n=== FAIL (' + bad.length + ') ==='); bad.forEach(s => console.log('  ✗ ' + s)); }
  process.exit(bad.length ? 1 : 0);
})().catch(e => { console.error('CRASH', e); process.exit(2); });
