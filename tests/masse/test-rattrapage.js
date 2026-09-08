/* Le coach ne se contente plus de constater : il désigne la séance à
   refaire, la date où elle tient, la pose d'un appui, et laisse une
   trace datée de ce qu'il a décidé à ta place. */
const { chromium } = require('playwright-core');
const CTX = require('./contexte');
const B = process.env.B || CTX.BASE;
const ok = [], bad = [];
const check = (n, c, d) => (c ? ok : bad).push(n + (d ? ' — ' + d : ''));

/* la rotation entière, chaque station à sa prescription, à un niveau donné */
const ROTATION = `(n) => {
  const t = new Date(), k = d => { const x = new Date(t); x.setDate(t.getDate() - d);
    return x.getFullYear()+'-'+String(x.getMonth()+1).padStart(2,'0')+'-'+String(x.getDate()).padStart(2,'0'); };
  localStorage.clear();
      localStorage.setItem('inrun_programme', 'rot5');
  localStorage.setItem('inrun_profil', JSON.stringify({ h: 175, w: 90, a: 35 }));
  saveNiv({ n: n });
  const log = {}, sets = {};
  ['push','pull','legs','upper','core'].forEach((g, i) => {
    log[k(i + 1)] = g;
    document.querySelectorAll('#' + g + ' .station').forEach(st => {
      const id = st.getAttribute('data-ex'), pr = getPresc(st);
      sets[id] = sets[id] || {};
      sets[id][k(i + 1)] = Array.from({ length: pr.sets }, () => ({ w: 40, r: pr.hi, rir: 1 }));
    });
  });
  localStorage.setItem('inrun_trainlog', JSON.stringify(log));
  localStorage.setItem('inrun_sets', JSON.stringify(sets));
}`;

(async () => {
  const br = await chromium.launch({ executablePath: CTX.CHROMIUM, args: ['--no-sandbox'] });
  const p = await (await br.newContext({ viewport: { width: 390, height: 844 } })).newPage();
  const errs = [];
  p.on('pageerror', e => errs.push(e.message));
  await p.goto(B + '/index.html', { waitUntil: 'domcontentloaded' });
  await p.waitForTimeout(900);

  const poser = async niv => {
    await p.evaluate(`(${ROTATION})(${niv})`);
    await p.reload({ waitUntil: 'domcontentloaded' });
    await p.waitForTimeout(1200);
    await p.evaluate(() => { try { closeRecap(); } catch (e) {} });
  };

  // ========== 1 · au niveau de départ, rien à rattraper ==========
  await poser(1);
  const neuf = await p.evaluate(() => ({
    rat: seanceARattraper(), codes: fuites().map(f => f.code),
    bouton: !!document.querySelector('#coachPrio .pact button')
  }));
  check('rotation complète au niveau de départ : aucun rattrapage à prescrire',
    neuf.rat === null, JSON.stringify(neuf.rat));
  check('… et aucun bouton d\'action inutile', neuf.bouton === false);

  // ===== 2 · un cran plus haut : quelques trous, il désigne UNE séance =====
  /* Au niveau 2 la barre monte juste assez pour que six muscles passent
     sous la cible, et un second passage en comble la majorité : c'est le
     cas où « refais cette séance » est la bonne réponse. */
  await poser(2);
  const haut = await p.evaluate(() => {
    const r = seanceARattraper();
    return { g: r && r.g, vises: r ? r.vises : [], reste: r && r.reste,
      jour: r ? jourPourRattrapage(r) : null,
      codes: fuites().map(f => f.code),
      texte: (document.querySelector('#coachPrio .pt') || {}).textContent || '',
      titre: (document.querySelector('#coachPrio .ph') || {}).textContent || '',
      txtRat: (fuites().find(f => f.code === 'structurel') || {}).t || '',
      bouton: (document.querySelector('#coachPrio .pact button') || {}).textContent || '' };
  });
  check('à un niveau supérieur, une séance précise est prescrite',
    !!haut.g, haut.g + ' → ' + haut.vises.join(', '));
  /* Depuis v28, quand une AUTRE structure comble tout pour moins de
     séances, c'est elle que le coach met en tête — rustiner passe
     derrière. Le rattrapage doit rester dans la liste pour qui décide
     de garder sa structure, mais il ne doit plus parler le premier. */
  check('… mais une meilleure structure passe devant la rustine',
    haut.codes[0] === 'structureMieux', haut.codes.join(', '));
  check('… et le rattrapage reste disponible juste derrière',
    haut.codes.indexOf('structurel') > 0, haut.codes.join(', '));

  /* LE contrat : citer des muscles puis ordonner une séance qui ne les
     touche pas est une contradiction dans le même paragraphe. */
  const nomsVises = await p.evaluate(v => v.map(m => MUSCLES_DET[m].n), haut.vises);
  check('les muscles nommés sont ceux que la séance prescrite répare vraiment',
    nomsVises.slice(0, 3).every(n => haut.txtRat.indexOf(n) >= 0),
    nomsVises.slice(0, 3).join(', ') + ' | ' + haut.txtRat.slice(0, 90));

  const servis = await p.evaluate(g => {
    const carte = carteMuscles(), out = {};
    document.querySelectorAll('#' + g + ' .station').forEach(st => {
      (carte[st.getAttribute('data-ex')] || []).forEach(m => { out[m] = true; });
    });
    return out;
  }, haut.g);
  check('… et la séance prescrite les travaille effectivement',
    haut.vises.every(m => servis[m]), haut.vises.join(', '));

  check('le jour proposé est un jour libre à venir',
    !!haut.jour && haut.jour > await p.evaluate(() => todayKey()), haut.jour);
  check('le bouton applique la structure décidée',
    /Applique/i.test(haut.bouton), haut.bouton.trim());
  check('le titre ne met pas ça sur le dos de l\'élève',
    !/coûte/i.test(haut.titre), haut.titre.trim());
  check('le texte du rattrapage annonce ce qu\'il restera à faire ensuite',
    haut.reste === 0 || /restera/.test(haut.txtRat), 'reste=' + haut.reste);

  // ========== 3 · le rattrapage pose vraiment la séance ==========
  /* le bouton du bloc porte maintenant la bascule de structure : on
     déclenche donc le rattrapage par son propre chemin */
  await p.evaluate(() => calerRattrapage());
  await p.waitForTimeout(800);
  const pose = await p.evaluate(() => ({ plan: loadPlan(), journal: loadJournal() }));
  check('l\'appui écrit la séance dans le planning',
    pose.plan[haut.jour] === haut.g, JSON.stringify(pose.plan));
  check('le coach laisse une trace datée de ce qu\'il a décidé',
    pose.journal.length === 1 && pose.journal[0].d === await p.evaluate(() => todayKey()),
    JSON.stringify(pose.journal.map(e => e.d)));
  check('… et la trace dit POURQUOI, pas seulement quoi',
    /sous la cible/.test(pose.journal[0].t), pose.journal[0].t.replace(/<[^>]+>/g, '').slice(0, 80));
  const vu = await p.evaluate(() => {
    show('moi', document.querySelectorAll('.bottomnav .bn')[3]);
    document.querySelectorAll('#moi .fold')[0].open = true;
    return document.querySelectorAll('#journalCoach .jrow').length;
  });
  check('la trace est lisible dans l\'onglet Moi', vu === 1, vu + ' ligne(s)');

  // ========== 4 · le planificateur sait caler ce second passage ==========
  await poser(2);
  const semaine = await p.evaluate(() => {
    effacerPlan(); proposerSemaine();
    const plan = loadPlan(), cnt = {};
    Object.keys(plan).forEach(k => { cnt[plan[k]] = (cnt[plan[k]] || 0) + 1; });
    return { cnt: cnt, rat: (seanceARattraper() || {}).g,
      absents: ['push','pull','legs','upper','core'].filter(g => !cnt[g]) };
  });
  check('la semaine proposée sert quand même les 5 groupes',
    semaine.absents.length === 0, JSON.stringify(semaine.cnt));
  check('… et elle contient bien un second passage sur la séance prescrite',
    semaine.cnt[semaine.rat] >= 2, semaine.rat + ' ×' + semaine.cnt[semaine.rat]);
  check('aucun groupe n\'est posé plus de deux fois',
    Object.keys(semaine.cnt).every(g => g === 'rest' || semaine.cnt[g] <= 2), JSON.stringify(semaine.cnt));

  // ===== 5 · quand la séance ajoutée ne règle qu'une minorité du trou =====
  /* Dire « refais Abdos » quand douze muscles sont courts et que deux
     seront comblés, c'est du déni par arithmétique. Le chiffre décide du
     registre : au-delà de la moitié restée courte, c'est le découpage
     qui est en cause, et le coach le nomme — sans cesser de proposer le
     meilleur pas immédiat, parce que les deux sont vrais en même temps. */
  await poser(4);   /* la rotation entière, mais à une barre bien plus haute */
  const depasse = await p.evaluate(() => {
    const r = seanceARattraper();
    return { rat: r && { vises: r.vises.length, reste: r.reste },
      codes: fuites().map(f => f.code) };
  });
  check('la séance ajoutée laisse plus de trous qu\'elle n\'en comble',
    !!depasse.rat && depasse.rat.reste > depasse.rat.vises,
    depasse.rat ? depasse.rat.vises + ' comblés / ' + depasse.rat.reste + ' restants' : 'aucune');
  check('… et le coach dit alors que c\'est le découpage qui est dépassé',
    depasse.codes.indexOf('depasse') >= 0 && depasse.codes.indexOf('structurel') < 0,
    depasse.codes.join(', '));
  const txtDep = await p.evaluate(() => {
    renderPrio();
    return { t: (document.querySelector('#coachPrio .pt') || {}).textContent || '',
      titre: (document.querySelector('#coachPrio .ph') || {}).textContent || '',
      bouton: !!document.querySelector('#coachPrio .pact button'),
      libelle: (document.querySelector('#coachPrio .pact button') || {}).textContent || '' };
  });
  check('… en nommant la structure qui règle le problème',
    /haut du corps/i.test(txtDep.t) && /deux passages/i.test(txtDep.t), txtDep.t.slice(0, 130));
  check('… en chiffrant ce que la séance ajoutée ne règle PAS',
    /n\'en règle que/.test(txtDep.t), txtDep.t.slice(0, 200));
  /* Le bouton porte désormais la décision structurelle (« Applique le
     nouveau programme »), et le texte garde le pas immédiat en attendant
     que la bascule soit faite : les deux restent vrais en même temps. */
  check('… tout en proposant quand même un pas immédiat',
    txtDep.bouton === true && /D'ici là/.test(txtDep.t), 'bouton=' + txtDep.bouton);
  check('… et le bouton applique la décision, il ne la soumet pas au vote',
    /Applique/.test(txtDep.libelle || ''), (txtDep.libelle || '').trim());
  check('… et le titre reste celui du programme, pas de l\'élève',
    !/coûte/i.test(txtDep.titre), txtDep.titre.trim());

  await p.evaluate(() => { localStorage.clear();
      localStorage.setItem('inrun_programme', 'rot5'); });
  check('aucune erreur JS', errs.length === 0, errs.join(' | '));

  await br.close();
  console.log('\n=== PASS (' + ok.length + ') ===');
  ok.forEach(s => console.log('  ✓ ' + s));
  if (bad.length) { console.log('\n=== FAIL (' + bad.length + ') ==='); bad.forEach(s => console.log('  ✗ ' + s)); }
  process.exit(bad.length ? 1 : 0);
})().catch(e => { console.error('CRASH', e); process.exit(2); });
