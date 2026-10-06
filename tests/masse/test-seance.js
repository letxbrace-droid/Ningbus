/* Le MODE SÉANCE : une machine, plein écran.
   Ce qu'il doit garantir, et que rien d'autre ne garantit :
     - on n'y entre que depuis une station qui sait enregistrer une série ;
     - la station n'est JAMAIS déplacée dans le DOM — c'est ce qui permet
       à la saisie, à l'historique et au chrono de continuer à marcher ;
     - le chrono se pose au-dessus de la saisie sans la recouvrir ;
     - le conseil du coach est lisible pendant le repos, pas sous la
       ligne de flottaison ;
     - on en sort, et la page retrouve sa place. */
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
  await p.goto(B + '/index.html', { waitUntil: 'domcontentloaded' });
  await p.waitForTimeout(900);
  await p.evaluate(() => { try { closeRecap(); } catch (e) {} });

  // ===== 1 · le bouton n'existe que là où il y a de quoi saisir =====
  const boutons = await p.evaluate(() => {
    const st = document.querySelectorAll('.station');
    let avecLog = 0, avecBouton = 0, boutonSansLog = 0;
    st.forEach(s => {
      const l = !!s.querySelector('.log'), b = !!s.querySelector('.fx-go');
      if (l) avecLog++;
      if (b) avecBouton++;
      if (b && !l) boutonSansLog++;
    });
    return { avecLog, avecBouton, boutonSansLog, total: st.length };
  });
  check('chaque station qui enregistre une série a son bouton Démarrer',
    boutons.avecBouton === boutons.avecLog, JSON.stringify(boutons));
  check('aucun bouton sur une station qui ne sait rien enregistrer',
    boutons.boutonSansLog === 0, String(boutons.boutonSansLog));

  // ===== 2 · entrer : le panneau prend l'écran =====
  const entre = await p.evaluate(() => {
    const st = document.querySelector('#haut .station[data-ex="push1"]');
    const parentAvant = st.parentNode;
    const rangAvant = Array.prototype.indexOf.call(parentAvant.children, st);
    st.classList.add('open');
    st.querySelector('.fx-go').click();
    const cs = getComputedStyle(st);
    return {
      focusOn: document.body.classList.contains('focus-on'),
      position: cs.position,
      opaque: cs.backgroundColor,
      memeParent: st.parentNode === parentAvant,
      memeRang: Array.prototype.indexOf.call(st.parentNode.children, st) === rangAvant,
      nom: document.getElementById('fxName').textContent,
      muscles: document.getElementById('fxMus').textContent,
      pos: document.getElementById('fxPos').textContent,
      corpsFige: getComputedStyle(document.body).overflow
    };
  });
  check('le panneau occupe tout l\'écran', entre.position === 'fixed', entre.position);
  check('son fond est opaque — la liste ne transparaît pas',
    /^rgb\(\s*\d+,\s*\d+,\s*\d+\s*\)$/.test(entre.opaque), entre.opaque);
  check('LA STATION N\'EST PAS DÉPLACÉE dans le DOM',
    entre.memeParent && entre.memeRang, JSON.stringify({ p: entre.memeParent, r: entre.memeRang }));
  check('la barre porte le nom, les muscles et la position',
    entre.nom === 'Chest press' && /Pectoraux/.test(entre.muscles) && entre.pos === '1 / 11',
    JSON.stringify(entre));
  check('la page du dessous ne défile plus', entre.corpsFige === 'hidden', entre.corpsFige);

  // ===== 3 · saisir une série depuis le panneau =====
  /* On saisit sur la DEUXIÈME machine du binôme : depuis la v44, la
     première d'une paire ne démarre aucun repos — c'est le sujet des
     contrôles 9. Ici on veut vérifier la saisie et le chrono. */
  const saisie = await p.evaluate(() => {
    document.getElementById('fxNext').click();
    const st = document.querySelector('.station.focus');
    st.querySelector('[data-w]').value = '36';
    st.querySelector('[data-r]').value = '10';
    st.querySelector('[data-rir] .rb[data-v="1"]').click();
    st.querySelector('[data-save]').click();
    const k = todayKey();
    return { enregistrees: setsOn('pull2', k).length, rir: setsOn('pull2', k)[0].rir,
             resting: st.classList.contains('resting') };
  });
  check('la série s\'enregistre depuis le mode séance', saisie.enregistrees === 1);
  check('… avec son RIR', saisie.rir === 1, String(saisie.rir));
  check('… et le chrono démarre', saisie.resting === true);

  // ===== 4 · le chrono ne recouvre pas la saisie =====
  await p.waitForTimeout(400);
  const geo = await p.evaluate(() => {
    const ctl = document.getElementById('restCtl').getBoundingClientRect();
    const corps = document.querySelector('.station.focus .st-body');
    const premier = corps.firstElementChild.getBoundingClientRect();
    const av = document.querySelector('.station.focus [data-advice]').getBoundingClientRect();
    const anneau = document.getElementById('restRing').getBoundingClientRect();
    return { ctlBas: ctl.bottom, premierHaut: premier.top,
             anneauCentre: Math.abs((anneau.left + anneau.right) / 2 - 195) < 6,
             conseilHaut: av.top, conseilBas: av.bottom };
  });
  check('les boutons du chrono ne recouvrent pas le contenu',
    geo.premierHaut >= geo.ctlBas, Math.round(geo.premierHaut) + ' vs ' + Math.round(geo.ctlBas));
  check('l\'anneau est centré, pas dans un coin', geo.anneauCentre);
  check('LE CONSEIL DU COACH est visible pendant le repos, sans défiler',
    geo.conseilHaut > 0 && geo.conseilHaut < 844, Math.round(geo.conseilHaut));

  // ===== 5 · le RIR se choisit AVANT d'enregistrer =====
  const ordre = await p.evaluate(() => {
    const st = document.querySelector('.station.focus');
    const r = st.querySelector('.rirrow').getBoundingClientRect();
    const b = st.querySelector('[data-save]').getBoundingClientRect();
    return { rir: r.top, bouton: b.top };
  });
  check('le RIR est au-dessus du bouton : on le choisit avant d\'enregistrer',
    ordre.rir < ordre.bouton, Math.round(ordre.rir) + ' vs ' + Math.round(ordre.bouton));

  // ===== 6 · naviguer d'une machine à l'autre =====
  const nav = await p.evaluate(() => {
    document.getElementById('fxNext').click();
    const st = document.querySelector('.station.focus');
    return { ex: st.getAttribute('data-ex'), pos: document.getElementById('fxPos').textContent,
             unSeul: document.querySelectorAll('.station.focus').length,
             chronoCoupe: !document.getElementById('restRing').classList.contains('show'),
             precedentActif: !document.getElementById('fxPrev').disabled };
  });
  check('Suivant passe à la machine d\'après', nav.ex === 'pull1' && nav.pos === '3 / 11',
    JSON.stringify(nav));
  check('une seule station en mode séance à la fois', nav.unSeul === 1, String(nav.unSeul));
  check('le chrono de la machine quittée est coupé', nav.chronoCoupe === true);
  check('Précédent se réactive', nav.precedentActif === true);

  // ===== 7 · sortir =====
  const sortie = await p.evaluate(() => {
    document.getElementById('fxBack').click();
    return { focusOn: document.body.classList.contains('focus-on'),
             restantes: document.querySelectorAll('.station.focus').length,
             corps: getComputedStyle(document.body).overflow,
             barre: getComputedStyle(document.getElementById('fxBar')).display };
  });
  check('on sort du mode séance', sortie.focusOn === false && sortie.restantes === 0);
  check('la page redéfile', sortie.corps !== 'hidden', sortie.corps);
  check('les barres disparaissent', sortie.barre === 'none', sortie.barre);

  // ===== 8 · la série saisie en mode séance a bien survécu =====
  const apres = await p.evaluate(() => setsOn('pull2', todayKey()).length);
  check('la série reste enregistrée après la sortie', apres === 1, String(apres));

  // ===== 9 · LE CHRONO CONNAÎT LES SUPERSÉRIES =====
  /* La v42 prescrit « chest press puis DIRECTEMENT rowing, et seulement
     là 2 min ». La v43 lançait 2 minutes après la première machine —
     l'app se contredisait. Les paires se lisent dans le DOM : un bloc
     porte une .ssnote, les machines s'y apparient deux par deux. */
  const paires = await p.evaluate(() => {
    const q = id => document.querySelector('#haut .station[data-ex="' + id + '"]');
    const dit = id => { const a = paireDe(q(id));
      return a ? (a.premier ? '1:' : '2:') + a.partenaire.getAttribute('data-ex') : 'seule'; };
    return { push1: dit('push1'), pull2: dit('pull2'), pull1: dit('pull1'),
             push4: dit('push4'), up5: dit('up5'), pull5: dit('pull5'),
             repos1: reposApres(q('push1')), repos2: reposApres(q('pull2')),
             reposSeule: reposApres(q('pull1')) };
  });
  check('les machines du bloc 1 s\'apparient : chest press ⟷ rowing',
    paires.push1 === '1:pull2' && paires.pull2 === '2:push1', JSON.stringify(paires));
  check('le tirage vertical reste seul — le bloc est impair',
    paires.pull1 === 'seule', paires.pull1);
  check('les blocs d\'isolation s\'apparient aussi',
    paires.push4 === '1:up5' && paires.up5 === '2:push4' && paires.pull5 === '2:pull4',
    JSON.stringify(paires));
  check('AUCUN REPOS après la première machine d\'une paire',
    paires.repos1 === 0, String(paires.repos1));
  check('le repos prescrit après la seconde', paires.repos2 === 120, String(paires.repos2));
  check('… et après une machine seule', paires.reposSeule === 120, String(paires.reposSeule));

  const ss = await p.evaluate(() => {
    localStorage.removeItem('inrun_sets');
    const st = document.querySelector('#haut .station[data-ex="push1"]');
    refreshStation(st);
    st.classList.add('open'); st.querySelector('.fx-go').click();
    st.querySelector('[data-w]').value = '48.3';
    st.querySelector('[data-r]').value = '10';
    st.querySelector('[data-save]').click();
    const e = st.querySelector('[data-ench]');
    return { chrono: document.getElementById('restRing').classList.contains('show'),
             enchVu: e.style.display !== 'none', txt: e.textContent };
  });
  check('en supersérie, aucun chrono ne démarre', ss.chrono === false);
  check('… la consigne « enchaîne » le remplace',
    ss.enchVu && /Rowing machine assis/.test(ss.txt), ss.txt.slice(0, 50));

  const yaller = await p.evaluate(() => {
    document.querySelector('.station.focus .ench-go').click();
    const st = document.querySelector('.station.focus');
    st.querySelector('[data-w]').value = '36';
    st.querySelector('[data-r]').value = '10';
    st.querySelector('[data-save]').click();
    return { arrive: st.getAttribute('data-ex'),
             chrono: document.getElementById('restRing').classList.contains('show'),
             ench: st.querySelector('[data-ench]').style.display };
  });
  check('« Y aller » ouvre la machine partenaire', yaller.arrive === 'pull2', yaller.arrive);
  check('le chrono part après la SECONDE du binôme', yaller.chrono === true);
  check('et la machine partenaire n\'affiche pas la consigne',
    yaller.ench === 'none', yaller.ench);

  // ===== 10 · LA CHARGE ARRIVE DÉJÀ ÉCRITE =====
  /* Séance réelle du 16/09, chest press : 48,3 — 48,3 — 48,3 — 48,3.
     Quatre fois la même saisie pour une valeur déjà connue. */
  const pre = await p.evaluate(() => {
    const st = document.querySelector('#haut .station[data-ex="push1"]');
    document.getElementById('fxBack').click();
    st.querySelector('.fx-go').click();
    const champ = st.querySelector('[data-w]').value;
    st.querySelector('[data-r]').value = '9';
    st.querySelector('[data-save]').click();     /* sans retoucher le poids */
    const t = setsOn('push1', todayKey());
    return { propose: champ, enregistre: t[t.length - 1].w, nbSeries: t.length,
             kgReste: st.querySelector('[data-w]').value,
             repsVide: st.querySelector('[data-r]').value };
  });
  check('le champ kg arrive rempli avec la charge précédente',
    pre.propose === '48,3', pre.propose);
  check('la virgule est bien relue — 48,3 et non 48',
    pre.enregistre === 48.3, String(pre.enregistre));
  check('une série de plus sans avoir retapé le poids', pre.nbSeries === 2, String(pre.nbSeries));
  check('la charge reste après l\'enregistrement, les reps se vident',
    pre.kgReste === '48,3' && pre.repsVide === '', JSON.stringify(pre));

  // ===== 11 · l'écran reste allumé =====
  const verrou = await p.evaluate(() => ({
    fonction: typeof prendreVerrou === 'function' && typeof rendreVerrou === 'function',
    tolere: (() => { try { prendreVerrou(); rendreVerrou(); return true; } catch (e) { return false; } })()
  }));
  check('le verrou d\'écran existe et ne casse rien sans support',
    verrou.fonction && verrou.tolere, JSON.stringify(verrou));

  check('aucune erreur JS', errs.length === 0, errs.join(' | '));

  await br.close();
  console.log('\n=== PASS (' + ok.length + ') ===');
  ok.forEach(s => console.log('  ✓ ' + s));
  if (bad.length) {
    console.log('\n=== FAIL (' + bad.length + ') ===');
    bad.forEach(s => console.log('  ✗ ' + s));
  }
  process.exit(bad.length ? 1 : 0);
})();
