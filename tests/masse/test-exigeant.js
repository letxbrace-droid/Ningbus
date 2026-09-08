/* Le coach exigeant.
   Un coach cher ne commente pas : il classe les fuites par ce qu'elles
   coûtent et il en donne UNE. Et il refuse d'accélérer quand accélérer
   fait perdre du muscle — y compris quand c'est demandé. */
const { chromium } = require('playwright-core');
const CTX = require('./contexte');
const B = CTX.BASE;
const ok = [], bad = [];
const check = (n, c, d) => (c ? ok : bad).push(n + (d ? ' — ' + d : ''));
const K = n => { const d = new Date(); d.setDate(d.getDate() - n);
  return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0'); };

(async () => {
  const br = await chromium.launch({ executablePath: CTX.CHROMIUM, args: ['--no-sandbox'] });
  const p = await (await br.newContext({ viewport: { width: 390, height: 844 } })).newPage();
  const errs = [];
  p.on('pageerror', e => errs.push(e.message));
  p.on('console', m => { if (m.type() === 'error' && !/Failed to load resource/.test(m.text())) errs.push(m.text()); });
  await p.goto(B + '/index.html', { waitUntil: 'domcontentloaded' });
  /* MUSCLES est figé au chargement à partir du programme actif : écrire
     inrun_programme après coup ne le déplace pas. Cette suite raconte la
     rotation, donc on la charge vraiment en rotation. Le cas Haut/Bas est
     testé plus bas, sur le programme par défaut. */
  await p.evaluate(() => localStorage.setItem('inrun_programme', 'rot5'));
  await p.reload({ waitUntil: 'domcontentloaded' });
  await p.waitForTimeout(800);

  const jouer = (log, sets, poids) => p.evaluate(a => {
    localStorage.clear();
      localStorage.setItem('inrun_programme', 'rot5');
    localStorage.setItem('inrun_profil', JSON.stringify({ h: 175, w: 90, a: 35 }));
    localStorage.setItem('inrun_trainlog', JSON.stringify(a.log || {}));
    localStorage.setItem('inrun_sets', JSON.stringify(a.sets || {}));
    if (a.poids) localStorage.setItem('inrun_poids', JSON.stringify(a.poids));
    _cal = null;
    return fuites().map(f => ({ code: f.code, p: f.p, txt: f.t.replace(/<[^>]+>/g, '') }));
  }, { log: log, sets: sets, poids: poids });

  const series = (ids, jours, rir) => {
    const s = {};
    ids.forEach(id => { s[id] = {}; jours.forEach(j => { s[id][K(j)] = [0,1,2].map(() => ({ w: 40, r: 12, rir: rir })); }); });
    return s;
  };
  const PUSH = ['push1','push2','push3','push4','push5'];
  const ROT = { [K(1)]: 'push', [K(3)]: 'pull', [K(5)]: 'legs' };

  // ================= 1 · le rendement des séries =================
  const rend = await p.evaluate(() => {
    localStorage.clear();
      localStorage.setItem('inrun_programme', 'rot5');
    const k = todayKey();
    const mk = rir => ({ push1: { [k]: [0,1,2,3].map(() => ({ w: 40, r: 12, rir: rir })) } });
    const lire = rir => { localStorage.setItem('inrun_sets', JSON.stringify(mk(rir))); _cal = null;
      return rendementSeries(7); };
    localStorage.setItem('inrun_sets', JSON.stringify({ push1: { [k]: [
      { w: 40, r: 12, rir: 1 }, { w: 40, r: 12, rir: 1 }, { w: 40, r: 12, rir: null }] } }));
    _cal = null;
    const mixte = rendementSeries(7);
    return { dur: lire(1), mou: lire(6), mixte: mixte };
  });
  check('des séries menées près de l\'échec valent leur plein stimulus',
    rend.dur.rendement === 1, JSON.stringify(rend.dur));
  check('des séries loin de l\'échec ne valent qu\'une fraction',
    rend.mou.rendement === 0.3, JSON.stringify(rend.mou));
  check('les séries sans RIR sont comptées à part, pas devinées',
    rend.mixte.n === 3 && rend.mixte.sansRir === 1 && rend.mixte.rendement === 1,
    JSON.stringify(rend.mixte));

  // ================= 2 · « tu te ménages » =================
  const mou = await jouer({ [K(1)]: 'push', [K(3)]: 'push', [K(5)]: 'push' },
    series(PUSH, [1, 3, 5], 5));
  const fMou = mou.find(f => f.code === 'menage');
  check('des séries systématiquement loin de l\'échec sont nommées', !!fMou,
    mou.map(f => f.code).join(', '));
  check('… avec le pourcentage de stimulus réellement produit',
    fMou && /30 % du stimulus/.test(fMou.txt), fMou && fMou.txt.slice(0, 100));
  const dur = await jouer({ [K(1)]: 'push', [K(3)]: 'push', [K(5)]: 'push' },
    series(PUSH, [1, 3, 5], 1));
  check('… et rien n\'est reproché quand les séries sont menées dur',
    !dur.some(f => f.code === 'menage'), dur.map(f => f.code).join(', '));

  // ================= 3 · le refus d'accélérer =================
  const vite = await jouer(ROT, series(PUSH, [1, 3, 5], 1),
    [{ d: K(21), w: 95 }, { d: K(14), w: 93 }, { d: K(7), w: 91 }, { d: K(0), w: 89 }]);
  const fVite = vite.find(f => f.code === 'tropVite');
  check('perdre 2 kg par semaine est signalé comme une erreur, pas comme un succès',
    !!fVite, vite.map(f => f.code).join(', '));
  check('… c\'est la fuite la plus prioritaire de toutes',
    fVite && vite[0].code === 'tropVite', vite[0] && vite[0].code);
  check('… et la consigne est chiffrée, pas morale',
    fVite && /200 kcal/.test(fVite.txt), fVite && fVite.txt.slice(-90));
  const lent = await jouer(ROT, series(PUSH, [1, 3, 5], 1),
    [{ d: K(21), w: 91 }, { d: K(14), w: 90.6 }, { d: K(7), w: 90.3 }, { d: K(0), w: 90 }]);
  check('une perte de 0,3 kg par semaine ne déclenche rien : c\'est la bonne vitesse',
    !lent.some(f => f.code === 'tropVite'), lent.map(f => f.code).join(', '));

  // ================= 4 · la séance abandonnée =================
  const ab = await jouer({ [K(2)]: 'push' }, { push1: { [K(2)]: [{ w: 40, r: 12, rir: 1 }] } });
  const fAb = ab.find(f => f.code === 'abandon');
  check('une séance ouverte puis lâchée est vue', !!fAb, ab.map(f => f.code).join(', '));
  check('… et le pluriel est juste', fAb && /1 exercice sur 5/.test(fAb.txt), fAb && fAb.txt.slice(0, 80));
  const entiere = await jouer({ [K(2)]: 'push' }, series(PUSH, [2], 1));
  check('une séance complète n\'est pas un abandon',
    !entiere.some(f => f.code === 'abandon'), entiere.map(f => f.code).join(', '));

  /* Et le même reproche doit fonctionner sur le programme par défaut :
     une fuite qui ne connaît que les noms de l'ancienne rotation se tait
     en silence le jour où la structure change. */
  const abHB = await p.evaluate(k => {
    localStorage.setItem('inrun_programme', 'hb');
    location.reload();
  }, null).then(() => p.waitForTimeout(900)).then(() => p.evaluate(k => {
    const garde = localStorage.getItem('inrun_programme');
    localStorage.clear();
    localStorage.setItem('inrun_programme', garde);
    localStorage.setItem('inrun_profil', JSON.stringify({ h: 175, w: 90, a: 35 }));
    localStorage.setItem('inrun_trainlog', JSON.stringify({ [k]: 'haut' }));
    const id = document.querySelector('#haut .station').getAttribute('data-ex');
    localStorage.setItem('inrun_sets', JSON.stringify({ [id]: { [k]: [{ w: 40, r: 12, rir: 1 }] } }));
    _cal = null;
    const f = fuites().find(x => x.code === 'abandon');
    return { prog: progActif(), total: document.querySelectorAll('#haut .station').length,
      txt: f ? f.t.replace(/<[^>]+>/g, '') : null };
  }, K(2)));
  check('… et la même règle vaut sur Haut/Bas, pas seulement sur la rotation',
    abHB.prog === 'hb' && abHB.txt !== null, abHB.prog + ' · ' + abHB.txt);
  check('… en comptant les exercices du haut, pas ceux de la poussée',
    abHB.txt && abHB.txt.indexOf('sur ' + abHB.total + '.') >= 0,
    abHB.total + ' stations · ' + (abHB.txt || '').slice(0, 80));

  await p.evaluate(() => { localStorage.setItem('inrun_programme', 'rot5'); });
  await p.reload({ waitUntil: 'domcontentloaded' });
  await p.waitForTimeout(800);

  // ================= 5 · le coaching à l'aveugle =================
  const aveugle = await p.evaluate(k => {
    localStorage.clear();
      localStorage.setItem('inrun_programme', 'rot5');
    localStorage.setItem('inrun_profil', JSON.stringify({ h: 175, w: 90, a: 35 }));
    const s = { push1: {}, push2: {} };
    [k[0], k[1]].forEach(j => { s.push1[j] = [0,1,2].map(() => ({ w: 40, r: 12, rir: null }));
      s.push2[j] = [0,1,2].map(() => ({ w: 40, r: 12, rir: null })); });
    localStorage.setItem('inrun_sets', JSON.stringify(s));
    localStorage.setItem('inrun_trainlog', JSON.stringify({ [k[0]]: 'push', [k[1]]: 'push' }));
    _cal = null;
    return fuites().map(f => f.code);
  }, [K(1), K(3)]);
  check('sans RIR, le coach dit qu\'il ne peut pas travailler',
    aveugle.indexOf('aveugle') >= 0, aveugle.join(', '));

  // ================= 6 · une seule priorité affichée =================
  await p.evaluate(a => {
    localStorage.clear();
      localStorage.setItem('inrun_programme', 'rot5');
    localStorage.setItem('inrun_profil', JSON.stringify({ h: 175, w: 90, a: 35 }));
    localStorage.setItem('inrun_trainlog', JSON.stringify(a.log));
    localStorage.setItem('inrun_sets', JSON.stringify(a.sets));
    localStorage.setItem('inrun_poids', JSON.stringify(a.poids));
  }, { log: { [K(1)]: 'push', [K(3)]: 'push', [K(5)]: 'push' }, sets: series(PUSH, [1, 3, 5], 5),
       poids: [{ d: K(21), w: 95 }, { d: K(14), w: 93 }, { d: K(7), w: 91 }, { d: K(0), w: 89 }] });
  await p.reload({ waitUntil: 'domcontentloaded' });
  await p.waitForTimeout(1100);
  await p.evaluate(() => { try { closeRecap(); } catch (e) {} });
  const bloc = await p.evaluate(() => {
    const b = document.getElementById('coachPrio');
    return { visible: b.classList.contains('show'), titre: (b.querySelector('.ph') || {}).textContent,
      corps: (b.querySelector('.pt') || {}).textContent, reste: (b.querySelector('.pn') || {}).textContent || '',
      nbFuites: fuites().length };
  });
  check('le bloc de priorité s\'affiche', bloc.visible === true);
  check('il annonce ce que ça coûte, pas « conseil »',
    /coûte/i.test(bloc.titre || ''), bloc.titre);
  check('il n\'énonce QU\'UNE fuite, même quand il y en a plusieurs',
    bloc.nbFuites > 1 && (bloc.corps || '').indexOf('Tu te ménages') < 0 &&
    /trop vite/.test(bloc.corps || ''), bloc.nbFuites + ' fuites → ' + (bloc.corps || '').slice(0, 60));
  check('… et il annonce combien il en reste, sans les déballer',
    /autre/.test(bloc.reste), bloc.reste);

  // ================= 7 · quand tout est propre, il le dit =============
  /* Une semaine réellement bien menée : la rotation complète, ET le
     nombre de séries que le programme prescrit réellement pour chaque
     station. Trois séries partout laissait les mollets en dette — le
     coach avait raison, c'est le scénario qui était faux. */
  await p.evaluate(k => {
    localStorage.clear();
      localStorage.setItem('inrun_programme', 'rot5');
    localStorage.setItem('inrun_profil', JSON.stringify({ h: 175, w: 90, a: 35 }));
    const jour = { push: k[0], pull: k[1], legs: k[2], upper: k[3], core: k[4] };
    const log = {}, sets = {};
    Object.keys(jour).forEach(g => {
      log[jour[g]] = g;
      document.querySelectorAll('#' + g + ' .station').forEach(st => {
        const id = st.getAttribute('data-ex'), pr = getPresc(st);
        sets[id] = sets[id] || {};
        sets[id][jour[g]] = Array.from({ length: pr.sets }, () => ({ w: 40, r: pr.hi, rir: 1 }));
      });
    });
    localStorage.setItem('inrun_trainlog', JSON.stringify(log));
    localStorage.setItem('inrun_sets', JSON.stringify(sets));
  }, [K(1), K(2), K(4), K(5), K(6)]);
  await p.reload({ waitUntil: 'domcontentloaded' });
  await p.waitForTimeout(1100);
  await p.evaluate(() => { try { closeRecap(); } catch (e) {} });
  const propre = await p.evaluate(() => ({
    codes: fuites().map(f => f.code),
    titre: (document.querySelector('#coachPrio .ph') || {}).textContent || '',
    corps: (document.querySelector('#coachPrio .pt') || {}).textContent || ''
  }));
  check('une semaine réellement bien menée ne produit aucune fuite',
    propre.codes.length === 0, propre.codes.join(', '));
  check('… et le coach le dit au lieu de se taire',
    /reprocher/i.test(propre.titre) && /recommence/i.test(propre.corps),
    propre.titre + ' | ' + propre.corps.slice(0, 70));

  check('aucune erreur JS', errs.length === 0, errs.join(' | '));

  await br.close();
  console.log('\n=== PASS (' + ok.length + ') ===');
  ok.forEach(s => console.log('  ✓ ' + s));
  if (bad.length) { console.log('\n=== FAIL (' + bad.length + ') ==='); bad.forEach(s => console.log('  ✗ ' + s)); }
  process.exit(bad.length ? 1 : 0);
})().catch(e => { console.error('CRASH', e); process.exit(2); });
