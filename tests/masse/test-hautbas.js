/* Le programme Haut/Bas, et la bascule qui y mène.
   Le contrat central : changer de structure ne doit RIEN coûter à
   l'historique. Les identifiants d'exercice sont partagés entre les deux
   programmes exprès — si un jour quelqu'un les renomme, cette suite doit
   hurler avant que les charges de six mois deviennent orphelines. */
const { chromium } = require('playwright-core');
const CTX = require('./contexte');
const B = process.env.B || CTX.BASE;
const ok = [], bad = [];
const check = (n, c, d) => (c ? ok : bad).push(n + (d ? ' — ' + d : ''));

(async () => {
  const br = await chromium.launch({ executablePath: CTX.CHROMIUM, args: ['--no-sandbox'] });
  const p = await (await br.newContext({ viewport: { width: 390, height: 844 } })).newPage();
  const errs = [];
  p.on('pageerror', e => errs.push(e.message));
  await p.goto(B + '/index.html', { waitUntil: 'domcontentloaded' });
  await p.waitForTimeout(900);

  // ============ 1 · les deux structures cohabitent proprement ============
  const dom = await p.evaluate(() => {
    const out = [];
    document.querySelectorAll('.sess').forEach(s => out.push({
      id: s.id, prog: s.getAttribute('data-prog'),
      imbrique: !!s.parentElement.closest('.sess'),
      stations: s.querySelectorAll('.station').length }));
    return { sess: out, actives: document.querySelectorAll(selActif() + ' .station').length };
  });
  check('chaque séance déclare son programme',
    dom.sess.every(x => ['rot5','hb','pdc'].indexOf(x.prog) >= 0),
    dom.sess.map(x => x.id + ':' + x.prog).join(' '));
  /* une séance imbriquée dans une autre fait mentir closest('.sess') et
     tous les comptages qui en dépendent — c'est arrivé à la génération */
  check('aucune séance n\'est imbriquée dans une autre',
    dom.sess.every(x => !x.imbrique), dom.sess.filter(x => x.imbrique).map(x => x.id).join(', '));
  const attendu = await p.evaluate(() => {
    let n = 0;
    PROGRAMMES[progActif()].seances.forEach(g => { n += document.querySelectorAll('#' + g + ' .station').length; });
    return { n: n, prog: progActif(), total: document.querySelectorAll('.station').length };
  });
  /* l'invariant est « seul le programme actif compte », pas un nombre
     figé : celui-ci change dès qu'on change de programme par défaut */
  check('seul le programme actif est compté',
    dom.actives === attendu.n && dom.actives < attendu.total,
    dom.actives + ' actives sur ' + attendu.total + ' (programme ' + attendu.prog + ')');

  // ============ 2 · les identifiants sont PARTAGÉS ============
  const partages = await p.evaluate(() => {
    const ids = g => [...document.querySelectorAll('#' + g + ' .station')].map(s => s.getAttribute('data-ex'));
    const hb = [...ids('haut'), ...ids('bas')];
    const rot = ['push', 'pull', 'legs', 'upper', 'core'].reduce((a, g) => a.concat(ids(g)), []);
    return { hb: hb, inconnus: hb.filter(x => rot.indexOf(x) < 0) };
  });
  check('toutes les machines de Haut/Bas existent déjà dans la rotation',
    partages.inconnus.length === 0,
    partages.inconnus.join(', ') || partages.hb.length + ' machines reprises');

  // ============ 3 · Haut/Bas tient les cibles ============
  const cibles = await p.evaluate(() => {
    localStorage.clear(); localStorage.setItem('inrun_programme', 'hb');
    const res = {};
    [1, 2, 3].forEach(niv => {
      saveNiv({ n: niv }); _nominal = null;
      const nom = volumeNominal();
      res[niv] = { sous: Object.keys(MUSCLES_DET).filter(m => nom[m] < cibleMuscle(m, niv).min)
                     .map(m => MUSCLES_DET[m].n + ' ' + nom[m] + '/' + cibleMuscle(m, niv).min),
                   sur: Object.keys(MUSCLES_DET).filter(m => nom[m] > cibleMuscle(m, niv).max)
                     .map(m => MUSCLES_DET[m].n) };
    });
    saveNiv({}); _nominal = null; localStorage.clear();
    return res;
  });
  [1, 2, 3].forEach(niv => {
    check('Haut/Bas atteint toutes les cibles au niveau ' + niv,
      cibles[niv].sous.length === 0, cibles[niv].sous.join(' · ') || 'toutes ✓');
  });
  check('… sans dépasser aucun plafond',
    [1, 2, 3].every(n => cibles[n].sur.length === 0),
    [1, 2, 3].map(n => cibles[n].sur.join(',')).filter(Boolean).join(' | ') || 'aucun');
  /* le volume nominal doit compter les DEUX passages prévus, pas un */
  const passages = await p.evaluate(() => {
    localStorage.setItem('inrun_programme', 'hb');
    _nominal = null;
    const nom = volumeNominal();
    let series = 0;
    document.querySelectorAll('#haut .station').forEach(st => { series += getPresc(st).sets; });
    localStorage.clear(); _nominal = null;
    return { pecs: nom.pecs, seriesHaut: series };
  });
  check('le nominal compte les deux passages hebdomadaires, pas un seul',
    passages.pecs >= 10, 'pectoraux ' + passages.pecs + ' séries/semaine');

  // ============ 4 · la bascule conserve l'historique ============
  await p.evaluate(() => {
    const t = new Date(), k = d => { const x = new Date(t); x.setDate(t.getDate() - d);
      return x.getFullYear()+'-'+String(x.getMonth()+1).padStart(2,'0')+'-'+String(x.getDate()).padStart(2,'0'); };
    localStorage.clear();
    localStorage.setItem('inrun_programme', 'rot5');   /* on part de la rotation */
    localStorage.setItem('inrun_profil', JSON.stringify({ h: 175, w: 90, a: 35 }));
    saveNiv({ n: 4 });
    const log = {}, sets = {};
    ['push','pull','legs','upper','core'].forEach((g, i) => {
      log[k(i + 1)] = g;
      document.querySelectorAll('#' + g + ' .station').forEach(st => {
        const id = st.getAttribute('data-ex'), pr = getPresc(st);
        sets[id] = sets[id] || {};
        sets[id][k(i + 1)] = Array.from({ length: pr.sets }, () => ({ w: 42.5, r: pr.hi, rir: 1 }));
      });
    });
    localStorage.setItem('inrun_trainlog', JSON.stringify(log));
    localStorage.setItem('inrun_sets', JSON.stringify(sets));
  });
  await p.reload({ waitUntil: 'domcontentloaded' });
  await p.waitForTimeout(1300);
  await p.evaluate(() => { try { closeRecap(); } catch (e) {} });

  const avant = await p.evaluate(() => ({
    prog: progActif(), codes: fuites().map(f => f.code),
    bouton: (document.querySelector('#coachPrio .pact button') || {}).textContent || '',
    texte: (document.querySelector('#coachPrio .pt') || {}).textContent || '',
    setsPush1: Object.keys((loadExSets().push1 || {})).length }));
  check('sur la rotation devenue trop courte, le coach tranche',
    avant.codes.indexOf('depasse') >= 0, avant.codes.join(', '));
  check('… et il l\'énonce comme une décision, pas une option',
    /C'est ma décision/.test(avant.texte), avant.texte.slice(-120));
  check('… avec le bouton qui l\'applique',
    /Applique/.test(avant.bouton), avant.bouton.trim());

  await p.click('#coachPrio .pact button');
  await p.waitForTimeout(2000);
  await p.evaluate(() => { try { closeRecap(); } catch (e) {} });
  const apres = await p.evaluate(() => {
    const sets = loadExSets();
    const st = document.querySelector('#haut [data-ex="push1"]');
    return { prog: progActif(), seances: SESSIONS.slice(),
      barre: [...document.querySelectorAll('.sessbar .sc')].map(e => e.getAttribute('data-g')),
      ouverte: (document.querySelector('.sess.show') || {}).id,
      setsPush1: Object.keys(sets.push1 || {}).length,
      charge: ((Object.values(sets.push1 || {})[0] || [])[0] || {}).w,
      prev: (st.querySelector('[data-prev]') || {}).textContent || '',
      journal: loadJournal().map(e => e.t) };
  });
  check('le programme actif a changé', apres.prog === 'hb', apres.prog);
  check('le sélecteur ne propose plus que les deux séances',
    JSON.stringify(apres.barre) === '["haut","bas"]', apres.barre.join(','));
  /* le repli sur une séance écrite en dur laissait l'onglet vide */
  check('une séance s\'ouvre bien après la bascule',
    apres.seances.indexOf(apres.ouverte) >= 0, 'ouverte=' + apres.ouverte);

  check('AUCUNE série enregistrée n\'est perdue',
    apres.setsPush1 === avant.setsPush1 && apres.charge === 42.5,
    apres.setsPush1 + ' séance(s), ' + apres.charge + ' kg');
  check('… et la dernière perf s\'affiche sur la machine reprise',
    /42,5/.test(apres.prev), apres.prev.trim());
  check('le changement de structure est tracé et motivé',
    apres.journal.length === 1 && /Rotation 5/.test(apres.journal[0]) && /Haut \/ Bas/.test(apres.journal[0]),
    apres.journal.join(' | ').replace(/<[^>]+>/g, '').slice(0, 90));

  // ============ 5 · sur Haut/Bas, le levier change ============
  await p.evaluate(() => {
    const t = new Date(), k = d => { const x = new Date(t); x.setDate(t.getDate() - d);
      return x.getFullYear()+'-'+String(x.getMonth()+1).padStart(2,'0')+'-'+String(x.getDate()).padStart(2,'0'); };
    const log = {}, sets = {};
    [['haut',1],['bas',2],['haut',4],['bas',5]].forEach(([g, j]) => {
      log[k(j)] = g;
      document.querySelectorAll('#' + g + ' .station').forEach(st => {
        const id = st.getAttribute('data-ex'), pr = getPresc(st);
        sets[id] = sets[id] || {};
        sets[id][k(j)] = Array.from({ length: pr.sets }, () => ({ w: 42.5, r: pr.hi, rir: 1 }));
      });
    });
    localStorage.setItem('inrun_trainlog', JSON.stringify(log));
    localStorage.setItem('inrun_sets', JSON.stringify(sets));
  });
  await p.reload({ waitUntil: 'domcontentloaded' });
  await p.waitForTimeout(1300);
  await p.evaluate(() => { try { closeRecap(); } catch (e) {} });
  const plaf = await p.evaluate(() => ({
    codes: fuites().map(f => f.code), rat: seanceARattraper(),
    texte: (document.querySelector('#coachPrio .pt') || {}).textContent || '',
    bouton: (document.querySelector('#coachPrio .pact button') || {}).textContent || '' }));
  check('déjà en Haut/Bas, aucun troisième passage n\'est proposé',
    plaf.rat === null, JSON.stringify(plaf.rat && plaf.rat.g));
  check('… le coach ne propose pas non plus la structure où il est déjà',
    plaf.codes.indexOf('depasse') < 0 && plaf.codes.indexOf('plafondProg') >= 0, plaf.codes.join(', '));
  check('… il dit que la fréquence est épuisée',
    /fréquence ne peut plus rien/.test(plaf.texte), plaf.texte.slice(0, 60));
  check('… et il nomme la série qu\'il ajoute',
    /J'ajoute une série ici/.test(plaf.texte) && /Ajoute la série/.test(plaf.bouton),
    plaf.bouton.trim());

  const serie = await p.evaluate(() => {
    const lire = () => getPresc(document.querySelector('#haut [data-ex="push5"]')).sets;
    const avant = lire();
    ajouterSeries();
    return { avant: avant, apres: lire(),
      affiche: document.querySelector('#haut [data-ex="push5"] .presc .cv').textContent,
      journal: loadJournal().length, codes: fuites().map(f => f.code) };
  });
  check('l\'appui ajoute vraiment la série', serie.apres === serie.avant + 1,
    serie.avant + ' → ' + serie.apres);
  check('… et l\'affichage suit', serie.affiche === String(serie.apres), 'affiché ' + serie.affiche);
  check('… la modification est tracée', serie.journal === 2, serie.journal + ' lignes');
  /* la contradiction la plus bête : ajouter une série puis reprocher de
     ne pas l'avoir faite dans la semaine écoulée */
  check('… et le coach ne reproche PAS aussitôt de ne pas l\'avoir faite',
    serie.codes.indexOf('dette') < 0, serie.codes.join(', '));

  // ============ 6 · le retour est toujours possible ============
  /* basculerProgramme recharge la page : on ne peut pas lire son retour
     dans le même contexte d'exécution, il faut attendre la navigation. */
  await Promise.all([
    p.waitForNavigation({ waitUntil: 'domcontentloaded' }).catch(() => {}),
    p.evaluate(() => { basculerProgramme('rot5', 'retour de test'); })
  ]);
  await p.waitForTimeout(1200);
  const retour = await p.evaluate(() => ({
    prog: progActif(), seances: SESSIONS.slice(),
    journal: loadJournal().length,
    sets: Object.keys(loadExSets().push1 || {}).length }));
  check('on peut revenir à la rotation',
    retour.prog === 'rot5' && retour.seances.length === 5, retour.prog + ' · ' + retour.seances.join(','));
  check('… et le retour ne perd rien non plus',
    retour.sets >= 1, retour.sets + ' séance(s) conservée(s) sur le chest press');
  check('… le retour est tracé comme le reste', retour.journal === 3, retour.journal + ' lignes');

  // ===== 7 · le chemin vers l'autre programme est ATTEIGNABLE =====
  /* Le défaut le plus coûteux de la v26 : Haut/Bas existait, était
     testé, et personne ne pouvait y arriver. Le coach ne le proposait
     que sous une condition jamais atteinte, et le seul autre chemin
     était un bouton étiqueté « Revenir à » — un verbe absurde pour un
     endroit où l'on n'a jamais mis les pieds. Un programme qu'on ne
     peut pas atteindre n'existe pas. */
  const chemins = await p.evaluate(() => {
    const t = new Date(), k = d => { const x = new Date(t); x.setDate(t.getDate() - d);
      return x.getFullYear()+'-'+String(x.getMonth()+1).padStart(2,'0')+'-'+String(x.getDate()).padStart(2,'0'); };
    /* installation neuve : le réglage doit déjà offrir le passage */
    localStorage.clear();
    localStorage.setItem('inrun_programme', 'rot5');
    refreshPlan();
    show('moi', document.querySelectorAll('.bottomnav .bn')[3]);
    const neuf = (document.querySelector('#progActuel .pg-alt') || {}).textContent || '';
    /* quelques semaines de rotation : le coach doit le proposer LUI-MÊME */
    localStorage.setItem('inrun_programme', 'rot5');
    localStorage.setItem('inrun_profil', JSON.stringify({ h: 175, w: 90, a: 35 }));
    const cycle = ['push','pull','legs','upper','core'];
    const log = {}, sets = {}; let c = 0;
    for (let j = 20; j >= 0; j--) {
      if (j % 7 === 6 || j % 7 === 2) continue;
      const g = cycle[c++ % 5];
      log[k(j)] = g;
      document.querySelectorAll('#' + g + ' .station').forEach(st => {
        const id = st.getAttribute('data-ex'), pr = getPresc(st);
        sets[id] = sets[id] || {};
        sets[id][k(j)] = Array.from({ length: pr.sets }, () => ({ w: 40, r: pr.hi, rir: 1 }));
      });
    }
    localStorage.setItem('inrun_trainlog', JSON.stringify(log));
    localStorage.setItem('inrun_sets', JSON.stringify(sets));
    saveTLog(log); saveExSets(sets);
    /* le palier est fixé explicitement : _niv est un cache mémoire que
       localStorage.clear() ne remet pas à zéro, et un palier hérité
       d'une section précédente change ce que progMieux() peut combler */
    saveNiv({ n: 2 });
    _nominal = null; _ratFait = false;
    refreshPlan();
    const codes = fuites().map(f => f.code);
    const m = progMieux();
    localStorage.clear();
    return { neuf: neuf, codes: codes, mieux: m && { code: m.code, cout: m.cout, coutA: m.coutA, comble: m.comble } };
  });
  check('dès l\'installation, le réglage propose de PASSER à l\'autre programme',
    /Passer en/.test(chemins.neuf), chemins.neuf.trim().slice(0, 60));
  check('… le verbe n\'est pas « revenir » pour un programme jamais utilisé',
    !/Revenir/.test(chemins.neuf), chemins.neuf.trim().slice(0, 40));
  check('quand une structure fait mieux, le coach la propose LUI-MÊME',
    chemins.codes[0] === 'structureMieux', chemins.codes.join(', '));
  /* et la comparaison doit être un gain mesuré, pas une préférence */
  check('… et seulement si elle comble tout pour un coût égal ou moindre',
    !!chemins.mieux && chemins.mieux.cout <= chemins.mieux.coutA && chemins.mieux.comble > 0,
    chemins.mieux ? chemins.mieux.comble + ' muscles comblés, ' +
      chemins.mieux.cout + ' séances au lieu de ' + chemins.mieux.coutA : 'aucune');

  /* ===== La bascule de défaut de la v29 =====
     Un utilisateur qui a déjà travaillé sous la rotation et n'a jamais
     choisi de structure change de programme au premier chargement. Le
     coach doit le dire, et ne pas proposer comme neuf le programme que
     cet utilisateur vient de quitter. */
  const K = n => { const d = new Date(); d.setDate(d.getDate() - n);
    return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0'); };
  const migr = async histo => {
    await p.evaluate(h => {
      localStorage.clear();
      if (h) localStorage.setItem('inrun_trainlog', JSON.stringify(h));
    }, histo);
    await p.reload({ waitUntil: 'domcontentloaded' });
    await p.waitForTimeout(1000);
    return p.evaluate(() => ({
      prog: progActif(),
      cle: localStorage.getItem('inrun_programme'),
      journal: loadJournal().map(e => e.t.replace(/<[^>]+>/g, '')).join(' | '),
      bouton: (document.getElementById('progActuel') || {}).textContent || ''
    }));
  };
  const ancien = await migr({ [K(2)]: 'push', [K(4)]: 'pull', [K(6)]: 'legs' });
  check('un habitué de la rotation se retrouve bien en Haut/Bas',
    ancien.prog === 'hb' && ancien.cle === 'hb', ancien.prog + ' · clé ' + ancien.cle);
  check('… et le coach écrit pourquoi, daté, au lieu de changer en silence',
    /Rotation 5 → Haut \/ Bas/.test(ancien.journal), ancien.journal.slice(0, 90) || 'journal vide');
  check('… en promettant que les charges survivent au changement',
    /charges et tes records sont conservés/.test(ancien.journal), ancien.journal.slice(-60));
  check('… et le réglage lui propose de REVENIR à la rotation, pas de la découvrir',
    /Revenir à Rotation 5/.test(ancien.bouton), ancien.bouton.trim().slice(0, 70));

  const debutant = await migr(null);
  check('une installation neuve démarre en Haut/Bas sans rien annoncer',
    debutant.prog === 'hb' && debutant.journal === '',
    debutant.prog + ' · journal « ' + debutant.journal.slice(0, 50) + ' »');

  /* Un choix déjà exprimé prime : la bascule ne doit jamais l'écraser. */
  await p.evaluate(() => { localStorage.clear(); localStorage.setItem('inrun_programme', 'rot5'); });
  await p.reload({ waitUntil: 'domcontentloaded' });
  await p.waitForTimeout(1000);
  const choisi = await p.evaluate(() => ({ prog: progActif(), j: loadJournal().length }));
  check('un choix déjà enregistré n\'est pas écrasé par le nouveau défaut',
    choisi.prog === 'rot5' && choisi.j === 0, choisi.prog + ' · ' + choisi.j + ' ligne(s)');

  /* ===== Ce que la suite ne voyait pas =====
     Cinq surfaces du calendrier supposaient la rotation, et 510
     vérifications ne l'ont jamais dit. Le planning annonçait « Poussée »
     à quelqu'un passé en Haut/Bas pendant que le coach, dans le même
     écran, lui reprochait de n'avoir jamais fait Haut du corps.

     La règle qui manquait est simple : AUCUNE surface ne doit nommer,
     colorer ou proposer une séance absente du programme actif. */

  const surfaces = async code => {
    await p.evaluate(cd => {
      const t = new Date(), k = d => { const x = new Date(t); x.setDate(t.getDate() + d);
        return x.getFullYear()+'-'+String(x.getMonth()+1).padStart(2,'0')+'-'+String(x.getDate()).padStart(2,'0'); };
      localStorage.clear();
      localStorage.setItem('inrun_programme', cd);
      /* un planning posé sous l'AUTRE programme, comme après une bascule */
      const autre = cd === 'hb' ? ['push','pull','legs','core','upper'] : ['haut','bas','haut','bas','haut'];
      const plan = {};
      autre.forEach((g, i) => { plan[k(i + 1)] = g; });
      localStorage.setItem('inrun_plan', JSON.stringify(plan));
    }, code);
    await p.reload({ waitUntil: 'domcontentloaded' });
    await p.waitForTimeout(1300);
    await p.evaluate(() => { try { closeRecap(); } catch (e) {} show('semaine'); });
    await p.waitForTimeout(400);
    return p.evaluate(() => {
      const cs = getComputedStyle(document.documentElement);
      const hors = SEANCES_CONNUES.filter(g => SESSIONS.indexOf(g) < 0);
      const plan = loadPlan();
      const txt = (document.getElementById('planAnalysis') || {}).textContent || '';
      return {
        prog: progActif(),
        sansCouleur: SESSIONS.filter(g => !cs.getPropertyValue('--s-' + g).trim()),
        legende: [...document.querySelectorAll('#calLegend span')].map(e => e.textContent.trim()),
        planRestant: Object.keys(plan).filter(k => hors.indexOf(plan[k]) >= 0).map(k => plan[k]),
        coachIntrus: hors.filter(g => txt.indexOf(SESS[g].t) >= 0),
        bande: [...document.querySelectorAll('.wk .wd, .weekstrip .d')].length
      };
    });
  };

  for (const code of ['hb', 'rot5']) {
    const v = await surfaces(code);
    const nom = code === 'hb' ? 'Haut/Bas' : 'la rotation';
    check('en ' + nom + ', chaque séance du programme a une couleur',
      v.sansCouleur.length === 0, v.sansCouleur.join(', ') || 'toutes');
    check('… la légende ne nomme que des séances du programme',
      v.legende.length > 0 && !v.legende.some(t =>
        ['Poussée','Tirage','Jambes','Bras','Abdos','Haut du corps','Bas du corps']
          .filter(x => !v.legende.includes(x)).includes(t)),
      v.legende.join(' · '));
    check('… le planning posé sous l\'autre programme est converti',
      v.planRestant.length === 0, v.planRestant.join(', ') || 'aucun jour étranger');
    check('… et le coach ne nomme aucune séance absente du programme',
      v.coachIntrus.length === 0, v.coachIntrus.join(', ') || 'aucune');
  }

  /* La conversion préserve la moitié du corps, elle n'efface pas.
     SESSIONS est figé au chargement : écrire inrun_programme puis appeler
     la fonction sans recharger la mesurerait sous l'ANCIEN programme. */
  await p.evaluate(() => { localStorage.clear(); localStorage.setItem('inrun_programme','hb'); });
  await p.reload({ waitUntil: 'domcontentloaded' });
  await p.waitForTimeout(1100);
  const conv = await p.evaluate(() => {
    if (typeof migrerPlanProgramme !== 'function') return 'FONCTION ABSENTE';
    const t = new Date(), k = d => { const x = new Date(t); x.setDate(t.getDate() + d);
      return x.getFullYear()+'-'+String(x.getMonth()+1).padStart(2,'0')+'-'+String(x.getDate()).padStart(2,'0'); };
    localStorage.setItem('inrun_plan', JSON.stringify({
      [k(1)]:'push', [k(2)]:'legs', [k(3)]:'upper', [k(4)]:'core', [k(5)]:'rest' }));
    migrerPlanProgramme();
    const pl = loadPlan();
    return [1,2,3,4,5].map(i => pl[k(i)]).join(' ');
  });
  check('la conversion garde la moitié du corps travaillée',
    conv === 'haut bas haut bas rest', conv);

  /* LA règle qui manquait à la conversion elle-même.
     Préserver la moitié du corps ne suffit pas : Poussée mercredi et
     Tirage jeudi étaient deux séances différentes, devenues Haut et Haut
     à 24 h d'écart quand le programme en exige 48. L'app rejetait son
     propre planning. Un planning converti ne doit laisser AUCUN jour que
     verdictJour refuse. */
  const espace = await p.evaluate(() => {
    if (typeof migrerPlanProgramme !== 'function') return { erreur: 'FONCTION ABSENTE' };
    const t = new Date(), k = d => { const x = new Date(t); x.setDate(t.getDate() + d);
      return x.getFullYear()+'-'+String(x.getMonth()+1).padStart(2,'0')+'-'+String(x.getDate()).padStart(2,'0'); };
    /* deux séances de la rotation qui tombent toutes deux dans le haut */
    localStorage.setItem('inrun_trainlog', '{}');
    localStorage.setItem('inrun_plan', JSON.stringify({
      [k(1)]:'push', [k(2)]:'pull', [k(3)]:'upper', [k(4)]:'legs', [k(5)]:'core' }));
    migrerPlanProgramme();
    const pl = loadPlan(), ctx = { log: loadTLog(), plan: pl };
    const refuses = Object.keys(pl).filter(x => x >= k(0) && SESSIONS.indexOf(pl[x]) >= 0)
      .filter(x => verdictJour(x, pl[x], ctx, true).niveau === 'stop');
    return { suite: [1,2,3,4,5].map(i => pl[k(i)] || '—').join(' '), refuses: refuses.length,
             premier: pl[k(1)] };
  });
  check('un planning converti ne laisse aucun jour que le coach refuse',
    espace.refuses === 0, espace.erreur || espace.refuses + ' jour(s) en stop · ' + espace.suite);
  check('… et c\'est le jour SUIVANT qui cède, pas celui déjà prévu',
    espace.premier === 'haut', espace.erreur || 'premier jour : ' + espace.premier);

  /* et le passé garde son nom : une séance faite s'appelle comme ce jour-là */
  const passe = await p.evaluate(() => {
    if (typeof migrerPlanProgramme !== 'function') return 'FONCTION ABSENTE';
    const t = new Date(), k = d => { const x = new Date(t); x.setDate(t.getDate() - d);
      return x.getFullYear()+'-'+String(x.getMonth()+1).padStart(2,'0')+'-'+String(x.getDate()).padStart(2,'0'); };
    localStorage.setItem('inrun_plan', '{}');
    localStorage.setItem('inrun_trainlog', JSON.stringify({ [k(3)]:'push', [k(1)]:'legs' }));
    migrerPlanProgramme();
    const lg = loadTLog();
    return [lg[k(3)], lg[k(1)]].join(' ');
  });
  check('… mais ce qui a été FAIT garde le nom qu\'il portait',
    passe === 'push legs', passe);

  await p.evaluate(() => { localStorage.clear(); });
  check('aucune erreur JS', errs.length === 0, errs.join(' | '));

  await br.close();
  console.log('\n=== PASS (' + ok.length + ') ===');
  ok.forEach(s => console.log('  ✓ ' + s));
  if (bad.length) { console.log('\n=== FAIL (' + bad.length + ') ==='); bad.forEach(s => console.log('  ✗ ' + s)); }
  process.exit(bad.length ? 1 : 0);
})().catch(e => { console.error('CRASH', e); process.exit(2); });
