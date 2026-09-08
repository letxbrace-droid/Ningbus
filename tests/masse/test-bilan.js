/* Deux ajouts : le RIR cible dépend du geste, et la séance a un bilan.
   Le contrat central du bilan : il juge contre la MOYENNE des séances
   précédentes, jamais contre la dernière — une seule séance faible
   suffirait sinon à annoncer un recul qui n'existe pas. */
const { chromium } = require('playwright-core');
const CTX = require('./contexte');
const B = process.env.B || CTX.BASE;
const ok = [], bad = [];
const check = (n, c, d) => (c ? ok : bad).push(n + (d ? ' — ' + d : ''));

/* quatre séances de push : les trois premières servent de référence,
   la quatrième est celle qu'on juge */
const SEED = `(sc) => {
  const t = new Date(), k = d => { const x = new Date(t); x.setDate(t.getDate() - d);
    return x.getFullYear()+'-'+String(x.getMonth()+1).padStart(2,'0')+'-'+String(x.getDate()).padStart(2,'0'); };
  localStorage.clear();
      localStorage.setItem('inrun_programme', 'rot5');
  localStorage.setItem('inrun_profil', JSON.stringify({ h: 175, w: 90, a: 35 }));
  const sets = {}, log = {};
  [9, 6, 3, 0].forEach((j, idx) => {
    log[k(j)] = 'push';
    document.querySelectorAll('#push .station').forEach(st => {
      const id = st.getAttribute('data-ex'), pr = getPresc(st), c = cibleRIR(pr);
      sets[id] = sets[id] || {};
      let w = 40, r = pr.lo + 1, rir = c.bas;
      if (idx === 3) {
        if (sc === 'progression') { r = pr.hi; }
        else if (sc === 'recul')  { r = Math.max(1, pr.lo - 3); rir = 0; }
        else if (sc === 'mou')    { rir = c.haut + 2; }
        else if (sc === 'partiel'){ r = pr.lo + 1; }
      }
      const n = (idx === 3 && sc === 'partiel' && id !== 'push1') ? 0 : pr.sets;
      if (n) sets[id][k(j)] = Array.from({ length: n }, () => ({ w: w, r: r, rir: rir }));
    });
  });
  localStorage.setItem('inrun_sets', JSON.stringify(sets));
  localStorage.setItem('inrun_trainlog', JSON.stringify(log));
}`;

(async () => {
  const br = await chromium.launch({ executablePath: CTX.CHROMIUM, args: ['--no-sandbox'] });
  const p = await (await br.newContext({ viewport: { width: 390, height: 844 } })).newPage();
  const errs = [];
  p.on('pageerror', e => errs.push(e.message));
  await p.goto(B + '/index.html', { waitUntil: 'domcontentloaded' });
  await p.waitForTimeout(900);

  // ============ 1 · le RIR cible dépend du geste ============
  const types = await p.evaluate(() => {
    const out = {};
    document.querySelectorAll('.station').forEach(st => {
      const pr = getPresc(st);
      out[st.getAttribute('data-ex')] = { t: typeExo(pr), rir: cibleRIR(pr).t, lo: pr.lo,
        nom: (st.querySelector('.st-name') || {}).textContent || '' };
    });
    return out;
  });
  /* la classification attendue, exercice par exercice : si une
     prescription change au point de basculer un geste d'une catégorie à
     l'autre, on veut que ça casse ici et pas en silence */
  const ATTENDU = {
    push1:'poly', push2:'poly', pull1:'poly', pull2:'poly',
    legs1:'poly', legs2:'poly', up1:'poly', up2:'poly',
    push3:'iso', push4:'iso', push5:'iso', pull3:'iso', pull4:'iso', pull5:'iso',
    legs3:'iso', legs4:'iso', legs5:'iso', up3:'iso', up4:'iso', up5:'iso', up6:'iso',
    core1:'iso', core2:'iso', core3:'iso', core4:'iso', core5:'iso'
  };
  const faux = Object.keys(ATTENDU).filter(k => !types[k] || types[k].t !== ATTENDU[k]);
  check('chaque machine est classée du bon côté',
    faux.length === 0,
    faux.map(k => k + ' ' + (types[k] ? types[k].t + ' (' + types[k].lo + ' reps)' : 'absente')).join(' · ') ||
    Object.keys(ATTENDU).length + ' machines');
  check('les gros mouvements visent 2-3 reps en réserve',
    types.push1.rir === '2-3' && types.legs1.rir === '2-3', types.push1.rir);
  check('l\'isolation vise 0-1',
    types.push4.rir === '0-1' && types.legs5.rir === '0-1', types.push4.rir);
  /* les deux exemplaires d'une même machine doivent prescrire les mêmes
     reps : sinon un exercice change de catégorie selon le programme */
  const reps = await p.evaluate(() => {
    const par = {};
    document.querySelectorAll('.station').forEach(st => {
      const id = st.getAttribute('data-ex'), pr = getPresc(st);
      (par[id] = par[id] || []).push(pr.lo + '-' + pr.hi);
    });
    return Object.keys(par).filter(id => new Set(par[id]).size > 1)
      .map(id => id + ' : ' + par[id].join(' vs '));
  });
  check('une machine présente dans les deux programmes prescrit les mêmes reps',
    reps.length === 0, reps.join(' · ') || 'toutes concordent');

  /* La progression de l'app est une DOUBLE progression : on monte les reps
     de lo à hi à charge constante, puis la charge monte. Une prescription
     où lo == hi n'a donc pas de marche intermédiaire — c'est réussi ou
     raté sur un seul chiffre, et la charge grimpe dès le premier succès.
     Six stations sur vingt étaient dans ce cas. */
  const figees = await p.evaluate(() => {
    const out = [];
    document.querySelectorAll('.station').forEach(st => {
      const pr = getPresc(st);
      if (pr.lo === pr.hi)
        out.push((st.querySelector('.st-name') || {}).textContent + ' ' + pr.lo + '-' + pr.hi);
    });
    return [...new Set(out)];
  });
  check('chaque prescription laisse une marche de progression en reps',
    figees.length === 0, figees.join(' · ') || 'toutes ont une fourchette');

  const poser = async sc => {
    await p.evaluate(`(${SEED})(${JSON.stringify(sc)})`);
    await p.reload({ waitUntil: 'domcontentloaded' });
    await p.waitForTimeout(1300);
    await p.evaluate(() => { try { closeRecap(); } catch (e) {} });
    return p.evaluate(() => {
      showSess('push');
      const b = bilanSeance('push'), el = document.getElementById('bilan-push');
      return { cnt: b.cnt, faites: b.faites, total: b.total, complete: b.complete,
        pire: b.pire && { nom: b.pire.nom, ecart: b.pire.ecart, nRef: b.pire.nRef },
        mous: b.mous.length, visible: el.classList.contains('show'),
        dit: (el.querySelector('.bt') || {}).textContent || '',
        meta: (el.querySelector('.bil-meta') || {}).textContent || '' };
    });
  };

  // ============ 2 · une séance en progression ============
  const prog = await poser('progression');
  check('le bilan s\'affiche quand la séance a été notée', prog.visible === true);
  check('une séance qui bat ses références est annoncée comme bonne',
    /bonne séance/.test(prog.dit) && prog.cnt.haut > 0, prog.dit.slice(0, 70));
  /* la contradiction à ne jamais produire : reprocher de laisser du
     stimulus sur la table pendant que tout passe à la charge suivante */
  check('… sans reprocher en même temps un manque d\'intensité',
    !/stimulus sur la table/.test(prog.dit), prog.dit.slice(0, 70));
  check('le pied de carte compte ce qui a été fait',
    /\d+\/\d+ exercices/.test(prog.meta) && /séries/.test(prog.meta), prog.meta);

  // ============ 3 · un recul net ============
  const recul = await poser('recul');
  check('une séance nettement en dessous est vue comme un recul',
    recul.cnt.bas > 0 && !!recul.pire, JSON.stringify(recul.cnt));
  check('… chiffré en écart à la moyenne des séances précédentes',
    recul.pire.ecart < -8 && recul.pire.nRef === 3,
    recul.pire.ecart + ' % sur ' + recul.pire.nRef + ' séances');
  check('… et le coach renvoie à la récupération, pas à la charge',
    /récupération/.test(recul.dit) && !/monte à/.test(recul.dit), recul.dit.slice(0, 80));

  // ============ 4 · des séries trop molles ============
  const mou = await poser('mou');
  check('un RIR trop loin de la cible est repéré', mou.mous > 0, mou.mous + ' exercice(s)');
  check('… et nommé avec sa cible',
    /reps en réserve pour une cible de/.test(mou.dit), mou.dit.slice(0, 80));

  // ============ 5 · une séance abandonnée ============
  const part = await poser('partiel');
  check('une séance incomplète est comptée telle quelle',
    part.faites < part.total && part.complete === false,
    part.faites + '/' + part.total);
  check('… et le coach le dit',
    /sur \d+\./.test(part.dit) || /exercices? sur/.test(part.dit), part.dit.slice(0, 70));

  // ============ 6 · sans historique, on ne juge pas ============
  const neuf = await p.evaluate(() => {
    const t = new Date(), k = d => { const x = new Date(t); x.setDate(t.getDate() - d);
      return x.getFullYear()+'-'+String(x.getMonth()+1).padStart(2,'0')+'-'+String(x.getDate()).padStart(2,'0'); };
    localStorage.clear();
      localStorage.setItem('inrun_programme', 'rot5');
    const sets = {};
    document.querySelectorAll('#push .station').forEach(st => {
      const id = st.getAttribute('data-ex'), pr = getPresc(st);
      sets[id] = {}; sets[id][k(0)] = Array.from({ length: pr.sets }, () => ({ w: 40, r: pr.lo, rir: 2 }));
    });
    localStorage.setItem('inrun_sets', JSON.stringify(sets));
    const b = bilanSeance('push');
    localStorage.clear();
      localStorage.setItem('inrun_programme', 'rot5');
    return { cnt: b.cnt, faites: b.faites };
  });
  check('une première séance ne produit ni progression ni recul',
    neuf.cnt.bas === 0 && neuf.cnt.neuf === neuf.faites,
    JSON.stringify(neuf.cnt));

  // ============ 7 · pas de collision de classes ============
  /* .bn est la classe des onglets de navigation : un élément portant ce
     nom ailleurs se faisait ramasser par show() et recevait l'état
     actif — le tiret vert est apparu au milieu d'une carte. */
  const collision = await p.evaluate(() => [...document.querySelectorAll('.bn')]
    .filter(e => !e.closest('.bottomnav')).length);
  check('aucun élément hors navigation ne porte la classe .bn',
    collision === 0, collision + ' intrus');

  await p.evaluate(() => { localStorage.clear();
      localStorage.setItem('inrun_programme', 'rot5'); });
  check('aucune erreur JS', errs.length === 0, errs.join(' | '));

  await br.close();
  console.log('\n=== PASS (' + ok.length + ') ===');
  ok.forEach(s => console.log('  ✓ ' + s));
  if (bad.length) { console.log('\n=== FAIL (' + bad.length + ') ==='); bad.forEach(s => console.log('  ✗ ' + s)); }
  process.exit(bad.length ? 1 : 0);
})().catch(e => { console.error('CRASH', e); process.exit(2); });
