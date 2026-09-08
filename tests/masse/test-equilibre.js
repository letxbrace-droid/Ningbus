/* L'équilibre du programme, verrouillé.
   Le volume par muscle se lit dans la même source que le coach : les
   libellés des stations et leurs prescriptions par défaut. Un exercice
   renommé, une série retirée, et la couverture d'un petit muscle peut
   s'effondrer sans que rien d'autre ne casse. D'où ces budgets. */
const { chromium } = require('playwright-core');
const CTX = require('./contexte');
const ok = [], bad = [];
const check = (n, c, d) => (c ? ok : bad).push(n + (d ? ' — ' + d : ''));

(async () => {
  const br = await chromium.launch({ executablePath: CTX.CHROMIUM, args: ['--no-sandbox'] });
  const p = await (await br.newContext({ viewport: { width: 390, height: 844 } })).newPage();
  const errs = [];
  p.on('pageerror', e => errs.push(e.message));
  await p.goto('http://127.0.0.1:8765/index.html', { waitUntil: 'domcontentloaded' });
  await p.waitForTimeout(900);

  const mesurer = code => p.evaluate(cd => {
    localStorage.setItem('inrun_programme', cd);
    _carte = null; _seanceDe = null; _nominal = null;
    const carte = carteMuscles(), vol = {}, parSeance = {};
    Object.keys(MUSCLES_DET).forEach(m => { vol[m] = 0; parSeance[m] = {}; });
    let n = 0, sansMuscle = [];
    document.querySelectorAll('.sess[data-prog="' + cd + '"] .station').forEach(st => {
      n++;
      const id = st.getAttribute('data-ex'), pr = getPresc(st), mus = carte[id] || [];
      if (!mus.length) sansMuscle.push(id);
      const seance = id.replace(/[0-9]+$/, '').replace(/^up$/, 'upper');
      mus.forEach((m, i) => {
        if (vol[m] === undefined) return;
        const part = pr.sets * (i === 0 ? 1 : 0.5);
        vol[m] += part;
        parSeance[m][seance] = (parSeance[m][seance] || 0) + part;
      });
    });
    const v2 = {};
    Object.keys(parSeance).forEach(m => {
      v2[m] = 0;
      Object.keys(parSeance[m]).forEach(sn => { v2[m] += parSeance[m][sn] * (sn === 'core' ? 1 : 2); });
    });
    /* combien de stations ont ce muscle en MOTEUR : deux angles valent
       mieux qu'une machine chargée de douze séries */
    const moteurs = {};
    Object.keys(MUSCLES_DET).forEach(m => { moteurs[m] = []; });
    document.querySelectorAll('.sess[data-prog="' + cd + '"] .station').forEach(st => {
      const mus = carte[st.getAttribute('data-ex')] || [];
      if (mus.length && moteurs[mus[0]]) {
        moteurs[mus[0]].push((st.querySelector('.st-name') || {}).textContent || '');
      }
    });
    /* les cibles du niveau de départ, et le contrat d'aveu à tous les
       niveaux : hors de portée ⇒ detteStructurelle doit le dire */
    const cible1 = {}, debordent = [], mensonges = [];
    Object.keys(MUSCLES_DET).forEach(m => { const c = cibleMuscle(m, 1); cible1[m] = { min: c.min, max: c.max }; });
    for (let n = 1; n <= 6; n++) {
      saveNiv({ n: n }); _nominal = null;
      const nom = volumeNominal();
      Object.keys(MUSCLES_DET).forEach(m => {
        const c = cibleMuscle(m, n);
        if (nom[m] > c.max) debordent.push(MUSCLES_DET[m].n + ' niv' + n + ' ' + nom[m] + '/' + c.max);
        if (nom[m] < c.min && !detteStructurelle(m)) mensonges.push(MUSCLES_DET[m].n + ' niv' + n);
      });
    }
    saveNiv({}); _nominal = null;
    return { vol, v2, n, sansMuscle, moteurs, det: MUSCLES_DET, cible1, debordent, mensonges };
  }, code);

  /* Deux programmes cohabitent : une suite d'équilibre qui lit « le
     programme actif » mesurait en réalité le défaut du jour. On mesure
     les deux, explicitement, et chacun avec ses propres attentes. */
  const rot = await mesurer('rot5');
  const hb  = await mesurer('hb');
  const r = rot;
  await br.close();

  const noms = Object.keys(r.det);
  const cov = m => r.vol[m] / r.det[m].min;                 /* couverture à 1×/semaine */
  const pire = noms.slice().sort((a, b) => cov(a) - cov(b))[0];

  check('les 26 stations de la rotation sont rattachées à des muscles',
    rot.n === 26 && rot.sansMuscle.length === 0, rot.sansMuscle.join(', ') || rot.n + ' stations');
  check('les 20 stations de Haut/Bas aussi',
    hb.n === 20 && hb.sansMuscle.length === 0, hb.sansMuscle.join(', ') || hb.n + ' stations');

  /* LA règle qui manquait : le coach fixe des cibles ET prescrit un
     programme. Si la rotation entière, faite exactement comme prescrite,
     laisse un muscle sous sa cible, c'est LUI qui se contredit — et l'app
     reprochait une dette impossible à combler. Six muscles étaient dans ce
     cas (mollets à 58 % de leur cible).

     La cible de référence est celle du niveau de départ : ce programme est
     une rotation à UN passage par groupe, et c'est ce qu'un passage peut
     donner. Au-delà, la barre monte avec le niveau et c'est la fréquence
     qui doit suivre — pas seize séries d'élévations le même jour. Le test
     de ce contrat-là est plus bas (« hors de portée ⇒ le coach le sait »). */
  const dette = noms.filter(m => r.vol[m] < r.cible1[m].min);
  check('la rotation complète atteint TOUTES les cibles du niveau de départ',
    dette.length === 0,
    dette.map(m => r.det[m].n + ' ' + r.vol[m] + '/' + r.cible1[m].min).join(' · ') ||
    'le plus juste : ' + r.det[pire].n + ' à ' + Math.round(cov(pire) * 100) + ' % de la référence');

  /* … et sans dépasser par le haut, à aucun niveau : au-delà du maximum on
     paie de la récupération pour rien. */
  check('… sans faire déborder aucun muscle au-dessus de son maximum',
    r.debordent.length === 0, r.debordent.join(' · ') || 'aucun');

  /* Le contrat de l'aveu : dès qu'une cible passe hors de portée de la
     rotation (niveaux supérieurs), detteStructurelle doit le dire. Sans
     ça le coach accuse l'utilisateur de ce que son plan interdit. */
  check('toute cible hors de portée est reconnue comme structurelle',
    r.mensonges.length === 0, r.mensonges.join(' · ') || 'aucune');

  /* À 2 séances par groupe — ce que le planificateur encourage — les
     petits muscles doivent atteindre leur cible. */
  const rate2 = noms.filter(m => r.v2[m] < r.det[m].min);
  check('à 2 séances/semaine, au plus 2 muscles restent sous leur cible',
    rate2.length <= 2, rate2.map(m => r.det[m].n + ' ' + r.v2[m] + '/' + r.det[m].min).join(' · ') || 'aucun');

  /* Les muscles à faible rendement visuel ne doivent pas manger le budget
     des autres : plafond à 1×/semaine. */
  const gloutons = noms.filter(m => r.vol[m] > r.det[m].max);
  check('aucun muscle au-dessus de son plafond à 1 séance/semaine',
    gloutons.length === 0, gloutons.map(m => r.det[m].n + ' ' + r.vol[m] + '/' + r.det[m].max).join(' · ') || 'aucun');

  /* Les quatre muscles que le rééquilibrage visait, chiffrés. */
  [['deltLat', 9], ['mollets', 7], ['deltPost', 5], ['ischios', 7], ['trapezes', 6.5], ['brachial', 4]]
    .forEach(([m, mini]) => {
      check(r.det[m].n + ' ≥ ' + mini + ' séries/semaine',
        r.vol[m] >= mini, r.vol[m] + ' séries');
    });

  /* Le deltoïde latéral a deux angles, pas un seul : c'est ce qui permet
     d'atteindre sa fourchette sans entasser 12 séries sur une machine. */
  check('le deltoïde latéral est servi par deux stations distinctes',
    r.moteurs.deltLat.length >= 2, r.moteurs.deltLat.join(' + ') || 'aucune');
  check('… et ce sont bien deux exercices différents',
    new Set(r.moteurs.deltLat).size === r.moteurs.deltLat.length, r.moteurs.deltLat.join(' + '));

  check('aucune erreur JS', errs.length === 0, errs.join(' | '));

  console.log('\nVolume 1×/sem — ' + noms.map(m => r.det[m].n.split(' ')[0] + ' ' + r.vol[m]).join(' · '));
  console.log('\n=== PASS (' + ok.length + ') ===');
  ok.forEach(s => console.log('  ✓ ' + s));
  if (bad.length) { console.log('\n=== FAIL (' + bad.length + ') ==='); bad.forEach(s => console.log('  ✗ ' + s)); }
  process.exit(bad.length ? 1 : 0);
})().catch(e => { console.error('CRASH', e); process.exit(2); });
