/* Le système de niveaux : un état relu sur 28 jours, pas un compteur de points.
   On fabrique des profils complets (calendrier + séries + RIR + forme + 1RM)
   et on vérifie que le coach en tire le bon palier, la bonne prescription et
   les bonnes fourchettes de volume. */
const { chromium } = require('playwright-core');
const CTX = require('./contexte');
const B = CTX.BASE;
const ok = [], bad = [];
const check = (n, c, d) => (c ? ok : bad).push(n + (d ? ' — ' + d : ''));

/* Injecté dans la page : construit un profil sur `jours` jours en arrière.
   ratio = part des jours travaillés, rir/forme = qualité de la donnée,
   prog = les 1RM montent-elles sur la dernière fenêtre de 28 j. */
function seed(o) {
  localStorage.clear();
  const t = new Date();
  const k = n => { const d = new Date(t); d.setDate(t.getDate() - n);
    return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0'); };
  /* les cinq séances en rotation, chacune avec ses stations */
  const PLAN = {
    push: ['push1', 'push2', 'push3', 'push4', 'push5'],
    pull: ['pull1', 'pull2', 'pull3', 'pull4', 'pull5'],
    legs: ['legs1', 'legs2', 'legs3', 'legs4', 'legs5'],
    upper: ['up1', 'up2', 'up3', 'up4', 'up5'],
    core: ['core1', 'core2', 'core3', 'core4']
  };
  const groupes = Object.keys(PLAN);
  const log = {}, sets = {}, fat = {}, hist = {};
  let n = 0;
  for (let j = o.jours - 1; j >= 0; j--) {
    const jour = k(j);
    /* `ratio` jours sur 7 sont des séances, le reste du repos */
    if ((j % 7) >= o.parSemaine) { log[jour] = 'rest'; continue; }
    const g = groupes[n % groupes.length]; n++;
    log[jour] = g;
    if (o.forme) fat[jour] = 'normal';
    PLAN[g].forEach(id => {
      if (!sets[id]) sets[id] = {};
      /* la charge monte lentement : de quoi produire des records récents */
      const w = 40 + (o.prog ? (o.jours - j) * 0.25 : 0);
      sets[id][jour] = Array.from({ length: o.series }, () => ({ w: Math.round(w), r: 10, rir: o.rir ? 2 : null }));
      if (!hist[id]) hist[id] = [];
      hist[id].push({ d: jour, w: Math.round(w), r: 10, rm: Math.round(w * (1 + 10 / 30)) });
    });
  }
  localStorage.setItem('inrun_trainlog', JSON.stringify(log));
  localStorage.setItem('inrun_sets', JSON.stringify(sets));
  localStorage.setItem('inrun_fatigue', JSON.stringify(fat));
  localStorage.setItem('inrun_hist', JSON.stringify(hist));
  if (o.niveau) localStorage.setItem('inrun_niveau', JSON.stringify(o.niveau));
}

(async () => {
  const br = await chromium.launch({ executablePath: CTX.CHROMIUM, args: ['--no-sandbox'] });
  const c = await br.newContext({ viewport: { width: 390, height: 844 } });
  const p = await c.newPage();
  const errs = [];
  p.on('pageerror', e => errs.push(e.message));
  p.on('console', m => { if (m.type() === 'error' && !/Failed to load resource/.test(m.text())) errs.push(m.text()); });

  const charger = async o => {
    await p.evaluate(seed, o);
    await p.reload({ waitUntil: 'domcontentloaded' });
    await p.waitForTimeout(700);
  };

  await p.goto(B + '/index.html', { waitUntil: 'domcontentloaded' });
  await p.waitForTimeout(600);

  // ---------- installation vierge ----------
  await p.evaluate(() => localStorage.clear());
  await p.reload({ waitUntil: 'domcontentloaded' });
  await p.waitForTimeout(700);
  const vierge = await p.evaluate(() => ({ etat: loadNiv(), calc: niveauCalcule() }));
  check('installation vierge : niveau 1', vierge.etat.n === 1 && vierge.calc.n === 1, JSON.stringify(vierge.etat));
  check('… aucune jauge de force ni de rigueur sans données',
    !vierge.calc.jauges.force.dispo && !vierge.calc.jauges.rigueur.dispo && !vierge.calc.jauges.equilibre.dispo);
  check('… l\'assiduité, elle, est toujours mesurable et vaut 0',
    vierge.calc.jauges.assiduite.dispo && vierge.calc.jauges.assiduite.v === 0);
  check('… le score ne punit pas l\'absence de données', vierge.calc.score === 0, String(vierge.calc.score));
  const chip0 = await p.evaluate(() => document.getElementById('nivChip').textContent);
  check('la pastille du coach annonce le palier', /Niv\. 1 · Premier contact/.test(chip0), chip0);

  // ---------- un débutant assidu mais tout neuf reste au niveau 1 ----------
  await charger({ jours: 14, parSemaine: 4, series: 4, rir: true, forme: true, prog: true });
  const neuf = await p.evaluate(() => niveauCalcule());
  check('14 jours de pratique parfaite ne suffisent pas à passer 3',
    neuf.n <= 2, 'niveau ' + neuf.n + ' · score ' + neuf.score + ' · ' + neuf.total + ' séances');
  check('… parce que le second verrou est le nombre de séances',
    neuf.total < 25, neuf.total + ' séances');

  // ---------- un pratiquant installé : les deux verrous cèdent ----------
  await charger({ jours: 120, parSemaine: 4, series: 4, rir: true, forme: true, prog: true });
  const solide = await p.evaluate(() => ({ calc: niveauCalcule(), etat: loadNiv() }));
  check('120 jours à 4 séances/semaine, données complètes → au moins niveau 4',
    solide.calc.n >= 4, 'niveau ' + solide.calc.n + ' · score ' + solide.calc.score + ' · ' + solide.calc.total + ' séances');
  check('… les quatre jauges sont disponibles',
    ['assiduite', 'equilibre', 'force', 'rigueur'].every(k => solide.calc.jauges[k].dispo),
    JSON.stringify(Object.keys(solide.calc.jauges).map(k => k + ':' + solide.calc.jauges[k].dispo)));
  check('… et le niveau est enregistré, avec sa date', solide.etat.n === solide.calc.n && !!solide.etat.depuis,
    JSON.stringify(solide.etat));

  // ---------- la rigueur pèse : mêmes séances, aucune donnée de qualité ----------
  await charger({ jours: 120, parSemaine: 4, series: 4, rir: false, forme: false, prog: true });
  const brut = await p.evaluate(() => niveauCalcule());
  check('sans RIR ni forme déclarée, la rigueur s\'effondre',
    brut.jauges.rigueur.dispo && brut.jauges.rigueur.v === 0, 'rigueur ' + brut.jauges.rigueur.v);
  check('… et le score global descend', brut.score < solide.calc.score,
    brut.score + ' vs ' + solide.calc.score);

  // ---------- la force : des charges qui ne montent plus ----------
  await charger({ jours: 120, parSemaine: 4, series: 4, rir: true, forme: true, prog: false });
  const plat = await p.evaluate(() => niveauCalcule());
  check('charges figées → la jauge de force chute',
    plat.jauges.force.dispo && plat.jauges.force.v < solide.calc.jauges.force.v,
    plat.jauges.force.v + ' vs ' + solide.calc.jauges.force.v);

  // ---------- promotion : annoncée une seule fois ----------
  await charger({ jours: 120, parSemaine: 4, series: 4, rir: true, forme: true, prog: true,
    niveau: { n: 2, max: 2, depuis: '2026-01-01', sursis: null, vu: 2 } });
  const promo = await p.evaluate(() => {
    const av = JSON.parse(JSON.stringify(loadNiv()));
    annoncerNiveau();
    const t = document.getElementById('coachToast');
    return { av: av, toast: t.className.indexOf('show') >= 0, txt: t.textContent, ap: loadNiv() };
  });
  check('le niveau monte tout de suite quand les deux verrous cèdent',
    promo.av.n > 2, 'niveau ' + promo.av.n);
  check('… le coach l\'annonce', promo.toast && /Niveau \d/.test(promo.txt), promo.txt.slice(0, 70));
  check('… et le marque comme vu, pour ne pas le répéter', promo.ap.vu === promo.ap.n,
    'vu=' + promo.ap.vu + ' n=' + promo.ap.n);
  const encore = await p.evaluate(() => {
    document.getElementById('coachToast').className = 'ctoast';
    annoncerNiveau();
    return document.getElementById('coachToast').className.indexOf('show') >= 0;
  });
  check('… une seconde fois, plus rien', encore === false);

  // ---------- rétrogradation : sursis d'abord, jamais brutale ----------
  const hier = await p.evaluate(() => {
    const d = new Date(); d.setDate(d.getDate() - 1);
    return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
  });
  await charger({ jours: 200, parSemaine: 0, series: 0, rir: false, forme: false, prog: false,
    niveau: { n: 5, max: 5, depuis: '2026-01-01', sursis: null, vu: 5 } });
  const s1 = await p.evaluate(() => loadNiv());
  check('une pratique qui s\'effondre ne fait pas perdre le niveau tout de suite',
    s1.n === 5 && !!s1.sursis, JSON.stringify(s1));
  const carteSursis = await p.evaluate(() => document.getElementById('nivCard').textContent);
  check('… la carte explique le sursis et le compte à rebours',
    /Sursis/.test(carteSursis) && /14 jours/.test(carteSursis), (carteSursis.match(/Sursis[^.]{0,80}/) || [''])[0]);
  const chipSursis = await p.evaluate(() => document.getElementById('nivChip').className);
  check('… la pastille du coach passe en alerte', /sursis/.test(chipSursis), chipSursis);

  // sursis entamé il y a 20 jours : l'échéance est passée
  await p.evaluate(() => {
    const d = new Date(); d.setDate(d.getDate() - 20);
    const k = d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
    localStorage.setItem('inrun_niveau', JSON.stringify({ n: 5, max: 5, depuis: '2026-01-01', sursis: k, vu: 5 }));
  });
  await p.reload({ waitUntil: 'domcontentloaded' });
  await p.waitForTimeout(700);
  const s2 = await p.evaluate(() => loadNiv());
  check('après 14 jours de sursis, le niveau descend — d\'un seul palier',
    s2.n === 4 && !s2.sursis, JSON.stringify(s2));
  check('… mais le meilleur palier atteint reste acquis', s2.max === 5, 'max ' + s2.max);

  // le sursis se lève tout seul si la pratique repart
  await charger({ jours: 120, parSemaine: 4, series: 4, rir: true, forme: true, prog: true,
    niveau: { n: 3, max: 4, depuis: '2026-01-01', sursis: hier, vu: 3 } });
  const s3 = await p.evaluate(() => loadNiv());
  check('un sursis rattrapé avant l\'échéance disparaît sans conséquence', !s3.sursis, JSON.stringify(s3));

  // ---------- ce que le niveau change vraiment ----------
  const presc = await p.evaluate(() => {
    const out = {};
    [1, 3, 5].forEach(n => {
      out[n] = { normal: reglagePrescription(n, null), forme: reglagePrescription(n, 'forme') };
    });
    return out;
  });
  check('niveau 1 : la technique avant la charge',
    /3 reps en réserve/.test(presc[1].normal) && /amplitude complète/.test(presc[1].normal), presc[1].normal.slice(0, 60));
  check('niveau 3 : double progression à 2 reps en réserve',
    /2 reps en réserve/.test(presc[3].normal) && /haut de la fourchette/.test(presc[3].normal), presc[3].normal.slice(0, 60));
  check('niveau 5 : on va chercher 1 rep en réserve',
    /1 à 2 reps en réserve/.test(presc[5].normal), presc[5].normal.slice(0, 60));
  check('… et un jour favorable, une série menée à 0-1',
    /0-1 rep en réserve/.test(presc[5].forme), presc[5].forme.slice(0, 60));
  check('les trois paliers disent trois choses différentes',
    new Set([presc[1].normal, presc[3].normal, presc[5].normal]).size === 3);

  const cibles = await p.evaluate(() => ({
    n1: cibleMuscle('deltLat', 1), n3: cibleMuscle('deltLat', 3), n6: cibleMuscle('deltLat', 6)
  }));
  check('les fourchettes de volume s\'élargissent avec le niveau',
    cibles.n1.min < cibles.n3.min && cibles.n3.max < cibles.n6.max,
    [cibles.n1, cibles.n3, cibles.n6].map(c => c.min + '-' + c.max).join(' / '));

  // le planificateur se desserre à partir du niveau 5, jamais avant
  const tol = await p.evaluate(() => {
    const t = new Date(), k = n => { const d = new Date(t); d.setDate(t.getDate() + n);
      return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0'); };
    /* trois jours d'entraînement derrière soi : aujourd'hui serait le 4ᵉ */
    const ctx = { log: { [k(-1)]: 'push', [k(-2)]: 'pull', [k(-3)]: 'legs' }, plan: {} };
    const lire = n => {
      localStorage.setItem('inrun_niveau', JSON.stringify({ n: n, max: n, depuis: k(-30), sursis: null, vu: n }));
      _niv = null;
      return verdictJour(k(0), 'upper', ctx, true).niveau;
    };
    return { bas: lire(3), haut: lire(5) };
  });
  check('niveau 3 : le 4ᵉ jour d\'affilée déclenche l\'avertissement', tol.bas === 'warn', tol.bas);
  check('niveau 5 : le même enchaînement passe — un avancé encaisse un jour de plus',
    tol.haut === 'ok', tol.haut);

  // ---------- la carte de Progrès ----------
  await charger({ jours: 60, parSemaine: 4, series: 4, rir: true, forme: true, prog: true });
  await p.evaluate(() => show('progres', document.querySelector('[data-nav="progres"]')));
  await p.waitForTimeout(300);
  const carte = await p.evaluate(() => {
    const el = document.getElementById('nivCard');
    const arc = el.querySelector('.niv-badge .arc');
    return { txt: el.textContent, jauges: el.querySelectorAll('.jr').length,
      badge: (el.querySelector('.niv-badge .n') || {}).textContent,
      barres: [...el.querySelectorAll('.bar i')].map(i => i.style.width),
      score: +(el.querySelector('.niv-sh b') || {}).textContent,
      arcCible: arc ? parseFloat(arc.getAttribute('data-arc')) : null,
      arcTotal: arc ? parseFloat(arc.getAttribute('stroke-dasharray')) : null };
  });
  check('la carte affiche les quatre jauges', carte.jauges === 4, String(carte.jauges));
  check('… un badge avec le numéro du palier', /^[1-6]$/.test(carte.badge || ''), carte.badge);
  /* Quatre barres, plus cinq : la cinquième répétait le score global que
     l'anneau du badge porte désormais tout seul. */
  check('… une barre par jauge, et pas une de plus',
    carte.barres.length === 4 && carte.barres.every(w => /^\d+%$/.test(w)), carte.barres.join(' '));
  check('… le score global porté par l\'anneau, pas par une barre en double',
    carte.arcTotal > 0 && Math.abs(carte.arcCible - carte.arcTotal * (1 - carte.score / 100)) < 0.01,
    'score ' + carte.score + ' → arc ' + carte.arcCible + '/' + carte.arcTotal);
  check('… et ce qu\'il manque pour le palier suivant, chiffré',
    /Pour passer niveau/.test(carte.txt) && /séances/.test(carte.txt) && /\/ 100/.test(carte.txt),
    (carte.txt.match(/Pour passer niveau[^.]{0,120}/) || [''])[0]);
  check('… sans déborder de l\'écran', await p.evaluate(() =>
    document.getElementById('nivCard').scrollWidth <= document.getElementById('nivCard').clientWidth + 1));

  // ---------- sauvegarde et remise à zéro ----------
  const dansExport = await p.evaluate(() => ALL_KEYS.indexOf('inrun_niveau') >= 0);
  check('le niveau part dans la sauvegarde', dansExport);
  /* « Effacer les perfs » garde volontairement le calendrier : le niveau
     n'est donc pas remis à 1, il est RECALCULÉ sur ce qui subsiste — plus
     de séries, donc plus d'équilibre, de force ni de rigueur. Ce qui doit
     disparaître, c'est le palier stocké : garder un « meilleur palier 5 »
     sans une seule série derrière serait exactement le mensonge qu'on évite. */
  const avantReset = await p.evaluate(() => loadNiv().n);
  await p.evaluate(() => { window.confirm = () => true; resetLogs(); });
  await p.waitForTimeout(400);
  const apresReset = await p.evaluate(() => ({ etat: loadNiv(), calc: niveauCalcule() }));
  check('effacer les perfs ne laisse plus qu\'une jauge debout',
    apresReset.calc.jauges.assiduite.dispo && !apresReset.calc.jauges.equilibre.dispo &&
    !apresReset.calc.jauges.force.dispo && !apresReset.calc.jauges.rigueur.dispo);
  check('… le niveau est recalculé sur ce qui reste, pas conservé tel quel',
    apresReset.etat.n === apresReset.calc.n, 'avant ' + avantReset + ' → ' + apresReset.etat.n);
  check('… et le meilleur palier ne dépasse plus ce que les données justifient',
    apresReset.etat.max === apresReset.etat.n, JSON.stringify(apresReset.etat));

  check('aucune erreur JS', errs.length === 0, errs.join(' | '));

  await br.close();
  console.log('\n=== PASS (' + ok.length + ') ===');
  ok.forEach(s => console.log('  ✓ ' + s));
  if (bad.length) { console.log('\n=== FAIL (' + bad.length + ') ==='); bad.forEach(s => console.log('  ✗ ' + s)); }
  process.exit(bad.length ? 1 : 0);
})().catch(e => { console.error('CRASH', e); process.exit(2); });
