/* AUDIT DE COHÉRENCE — ce que la batterie ne demande pas.

   Les tests vérifient chacun leur coin. Les défauts d'aujourd'hui étaient
   tous du même genre : deux surfaces, alimentées par la MÊME donnée, qui
   se contredisaient à l'écran. Le planning disait « Poussée » pendant que
   le coach reprochait « jamais fait Haut du corps ».

   Cet audit ne teste donc pas des fonctions : il fait tourner l'app comme
   un utilisateur, avec un historique plausible, et compare ce que les
   différents blocs RACONTENT du même fait.

   Aucun check n'est écrit pour passer : chacun cherche une contradiction. */
const { chromium } = require('playwright-core');
const CTX = require('./contexte');
const B = process.env.B || CTX.BASE;

const OK = [], KO = [], NOTE = [];
const dit = (n, cond, det) => (cond ? OK : KO).push(n + (det ? '  → ' + det : ''));
const note = t => NOTE.push(t);

/* Le semis doit SUIVRE LE PROGRAMME, sinon l'audit ne mesure que mon
   erreur : la première version ne posait que deux séances par semaine là
   où Haut/Bas en demande quatre, et « 16 muscles en dette » ne disait rien
   d'autre que ça.

   Chaque séance est donc posée le nombre de passages que le programme
   déclare, espacée d'au moins 48 h, sur un profil réel : 35 ans, 90 kg,
   1 m 75. C'est la seule façon de poser LA question qui compte — si on
   fait exactement ce qui est prescrit, le coach a-t-il encore à redire ? */
const SEED = (prog, semaines) => `(() => {
  const t = new Date();
  const k = d => { const x = new Date(t); x.setDate(t.getDate() - d);
    return x.getFullYear()+'-'+String(x.getMonth()+1).padStart(2,'0')+'-'+String(x.getDate()).padStart(2,'0'); };
  localStorage.clear();
  localStorage.setItem('inrun_programme', ${JSON.stringify(prog)});
  localStorage.setItem('inrun_profil', JSON.stringify({ h:175, w:90, a:35 }));

  const P = PROGRAMMES[${JSON.stringify(prog)}];
  /* la semaine type : chaque séance autant de fois que le programme le dit */
  const semaine = [];
  const passes = P.seances.map(g => P.passages[g] || 1);
  const total = passes.reduce((a, b) => a + b, 0);
  for (let tour = 0; tour < Math.max(...passes); tour++)
    P.seances.forEach((g, i) => { if (tour < passes[i]) semaine.push(g); });
  /* Étalées DANS la fenêtre de comptage. volumeParMuscle(7) écarte tout
     ce qui a plus de 6 jours : poser une séance à J-7 la laisse dehors et
     fait apparaître une dette qui n'existe pas. Les passages tiennent donc
     entre J-6 et J-1, et jamais deux fois la même séance à 24 h. */
  const jours = [];
  for (let i = 0; i < semaine.length; i++)
    jours.push(1 + Math.round(i * 5 / Math.max(1, semaine.length - 1)));

  const log = {}, sets = {}, poids = [];
  for (let s = 0; s < ${semaines}; s++) {
    semaine.forEach((g, i) => {
      const j = s * 7 + (7 - jours[i]);
      if (j > ${semaines} * 7) return;
      log[k(j)] = g;
      document.querySelectorAll('#' + g + ' .station').forEach(st => {
        const id = st.getAttribute('data-ex'), pr = getPresc(st);
        sets[id] = sets[id] || {};
        sets[id][k(j)] = Array.from({ length: pr.sets },
          () => ({ w: 40 + (${semaines} - s) * 2.5, r: pr.lo + 1, rir: 1 }));
      });
    });
  }
  for (let s = 0; s <= ${semaines}; s++) poids.push({ d: k(s * 7), w: 90 - (${semaines} - s) * 0.3 });
  localStorage.setItem('inrun_trainlog', JSON.stringify(log));
  localStorage.setItem('inrun_sets', JSON.stringify(sets));
  localStorage.setItem('inrun_poids', JSON.stringify(poids));
  return { seances: Object.keys(log).length, semaine: semaine.join(' ') };
})()`;

(async () => {
  const br = await chromium.launch({ executablePath: CTX.CHROMIUM, args:['--no-sandbox'] });
  const ctx = await br.newContext({ viewport:{ width:390, height:844 } });
  const p = await ctx.newPage();
  const errs = [], res404 = [];
  p.on('pageerror', e => errs.push(e.message));
  p.on('console', m => { if (m.type() === 'error' && !/Failed to load resource/.test(m.text())) errs.push('console: ' + m.text()); });
  p.on('response', r => { if (r.status() >= 400) res404.push(r.status() + ' ' + r.url().split('/').pop()); });

  await p.goto(B + '/index.html', { waitUntil:'domcontentloaded' });
  await p.waitForTimeout(1200);

  /* ---------- 1 · ÇA COMPILE ---------- */
  const global = await p.evaluate(() => {
    const manquantes = ['carteMuscles','volumeParMuscle','cibleMuscle','musclesEnDette',
      'volumeNominal','detteStructurelle','fuites','verdictJour','suggestGroup',
      'proposerSemaine','moitieDe','peindreCartes','renderLegende','renderPickGrid',
      'migrerPlanProgramme','bilanSeance','cibleRIR','typeExo','niveauActuel',
      'objectifNutrition','rendementSeries','seanceARattraper','progMieux']
      .filter(f => typeof window[f] !== 'function');
    return { manquantes, nSess: document.querySelectorAll('.sess').length,
             nStations: document.querySelectorAll('.station').length };
  });
  dit('toutes les fonctions clés existent', global.manquantes.length === 0, global.manquantes.join(', '));

  for (const prog of ['hb', 'rot5']) {
    const nom = prog === 'hb' ? 'HAUT/BAS' : 'ROTATION';

    const pose = await p.evaluate(`${SEED(prog, 4)}`);
    await p.reload({ waitUntil:'domcontentloaded' });
    await p.waitForTimeout(1500);
    await p.evaluate(() => { try { closeRecap(); } catch (e) {} });

    /* toutes les vues, pour que chaque bloc s'exécute vraiment */
    for (const v of ['today','semaine','progres','profil']) {
      await p.evaluate(x => { try { show(x); } catch (e) {} }, v);
      await p.waitForTimeout(320);
    }
    await p.evaluate(() => { try { show('today'); } catch (e) {} });
    await p.waitForTimeout(300);

    /* ---------- 2 · LE SUIVI : les nombres s'accordent-ils ? ---------- */
    const m = await p.evaluate(() => {
      const vol = volumeParMuscle(7);
      const carte = carteMuscles();
      /* recompte à la main depuis le carnet, sans passer par la fonction */
      const sets = loadExSets(), log = loadTLog(), t = todayKey();
      const brut = {};
      Object.keys(MUSCLES_DET).forEach(x => { brut[x] = 0; });
      Object.keys(sets).forEach(id => Object.keys(sets[id]).forEach(k => {
        if (joursEntre(k, t) > 6 || k > t) return;
        const mus = carte[id] || [];
        mus.forEach((x, i) => { if (brut[x] !== undefined) brut[x] += sets[id][k].length * (i === 0 ? 1 : 0.5); });
      }));
      const niv = niveauActuel();
      const dette = musclesEnDette(vol).map(x => x.m);
      const sousCible = Object.keys(MUSCLES_DET).filter(x =>
        (carte && Object.values(carte).some(l => l.indexOf(x) >= 0)) &&
        vol[x] < cibleMuscle(x, niv).min);
      const nominal = volumeNominal();
      return {
        vol, brut, niv, dette, sousCible, nominal,
        seances: SESSIONS.slice(),
        travailles: Object.keys(MUSCLES_DET).filter(x =>
          SESSIONS.some(g => [...document.querySelectorAll('#' + g + ' .station')]
            .some(st => (carte[st.getAttribute('data-ex')] || []).indexOf(x) >= 0)))
      };
    });

    /* le volume pondéré ne peut pas dépasser le volume brut : la
       pondération par le RIR ne fait que retrancher */
    const gonfles = Object.keys(m.vol).filter(x => m.vol[x] > m.brut[x] + 0.01);
    dit(nom + ' · le volume compté ne dépasse jamais les séries réellement notées',
      gonfles.length === 0,
      gonfles.map(x => MUSCLES_DET[x] && x + ' ' + m.vol[x] + ' > ' + m.brut[x]).join(', '));

    /* tout muscle sous sa cible doit être signalé en dette, et
       réciproquement — sinon deux blocs disent l'inverse l'un de l'autre */
    const oublies = m.sousCible.filter(x => m.dette.indexOf(x) < 0);
    const inventes = m.dette.filter(x => m.sousCible.indexOf(x) < 0);
    dit(nom + ' · « en dette » et « sous la cible » désignent les mêmes muscles',
      oublies.length === 0 && inventes.length === 0,
      (oublies.length ? 'non signalés : ' + oublies.join(',') : '') +
      (inventes.length ? '  signalés à tort : ' + inventes.join(',') : ''));

    /* un muscle que le programme ne travaille pas ne doit jamais être
       reproché : c'est le coach qui aurait tort, pas l'utilisateur */
    const horsProg = m.dette.filter(x => m.travailles.indexOf(x) < 0);
    dit(nom + ' · aucun muscle absent du programme n\'est reproché',
      horsProg.length === 0, horsProg.join(', '));

    /* ---------- 3 · LE DISCOURS : le coach ne nomme que ce qui existe ---------- */
    const parle = await p.evaluate(() => {
      const zones = ['#coachPrio','#planAnalysis','#todayCoach','.coach','#recapBody'];
      let txt = '';
      zones.forEach(z => document.querySelectorAll(z).forEach(e => { txt += ' ' + e.textContent; }));
      const hors = SEANCES_CONNUES.filter(g => SESSIONS.indexOf(g) < 0);
      return {
        intrus: hors.filter(g => txt.indexOf(SESS[g].t) >= 0),
        vide: txt.replace(/\s+/g, '').length < 40,
        extrait: txt.replace(/\s+/g, ' ').trim().slice(0, 150)
      };
    });
    dit(nom + ' · le coach ne nomme aucune séance absente du programme',
      parle.intrus.length === 0, parle.intrus.join(', '));
    dit(nom + ' · le coach a quelque chose à dire', !parle.vide, parle.extrait.slice(0, 80));

    /* ---------- 3 bis · À QUI LA FAUTE ----------
       Le contrat le plus important de l'app : quand le PROGRAMME lui-même
       ne peut pas atteindre une cible, le reproche doit changer de camp.
       Reprocher à quelqu'un une dette que son plan interdit de combler,
       c'est le défaut que la v24 a corrigé — il ne doit pas revenir. */
    const faute = await p.evaluate(() => {
      const niv = niveauActuel(), nom = volumeNominal();
      const impossibles = Object.keys(MUSCLES_DET)
        .filter(x => nom[x] !== undefined && nom[x] < cibleMuscle(x, niv).min);
      const txt = (document.getElementById('coachPrio') || {}).textContent || '';
      return { n: impossibles.length,
               mentis: impossibles.filter(x => !detteStructurelle(x)).map(x => MUSCLES_DET[x].n),
               codes: fuites().map(f => f.code),
               structurel: /limite de ton programme/i.test(txt),
               titre: txt.replace(/\s+/g, ' ').trim().slice(0, 60) };
    });
    dit(nom + ' · toute cible hors de portée du programme est avouée comme structurelle',
      faute.mentis.length === 0, faute.mentis.join(', ') || faute.n + ' cible(s) hors de portée');
    dit(nom + ' · … et le reproche change de camp au lieu d\'accuser l\'utilisateur',
      faute.n === 0 || faute.structurel ||
      faute.codes.some(c => c === 'structurel' || c === 'structureMieux'),
      faute.n + ' hors de portée · « ' + faute.titre + ' » · ' + faute.codes.slice(0, 3).join(','));

    /* ---------- 4 · LA SEMAINE PROPOSÉE tient ses propres règles ---------- */
    const plan = await p.evaluate(() => {
      localStorage.setItem('inrun_plan', '{}');
      proposerSemaine();
      const pl = loadPlan(), t = todayKey(), c = { log: loadTLog(), plan: pl };
      const jours = Object.keys(pl).filter(k => k > t).sort();
      const refuses = jours.filter(k => SESSIONS.indexOf(pl[k]) >= 0 &&
        verdictJour(k, pl[k], c, true).niveau === 'stop');
      const servis = {};
      jours.forEach(k => { if (SESSIONS.indexOf(pl[k]) >= 0) servis[pl[k]] = (servis[pl[k]] || 0) + 1; });
      return { suite: jours.map(k => pl[k]).join(' '), refuses: refuses.length,
               absentes: SESSIONS.filter(g => !servis[g]), servis };
    });
    dit(nom + ' · la semaine proposée ne contient aucun jour que le coach refuse',
      plan.refuses === 0, plan.refuses + ' refusé(s) · ' + plan.suite);
    dit(nom + ' · … et elle sert chaque séance du programme',
      plan.absentes.length === 0, plan.absentes.join(', ') || plan.suite);

    /* ---------- 5 · LA CARTE dit ce que la séance travaille ---------- */
    const carte = await p.evaluate(() => {
      const c = carteMuscles(), faux = [];
      document.querySelectorAll(selActif()).forEach(sess => {
        const attendu = new Set();
        sess.querySelectorAll('.station').forEach(st =>
          (c[st.getAttribute('data-ex')] || []).forEach(x => attendu.add(x)));
        const st = sess.querySelector('.sh-mus').getAttribute('style') || '';
        const pose = new Set([...st.matchAll(/--m-([A-Za-z]+)/g)].map(x => x[1]));
        if ([...attendu].some(x => !pose.has(x)) || [...pose].some(x => !attendu.has(x)))
          faux.push(sess.id);
      });
      return faux;
    });
    dit(nom + ' · la carte musculaire allume exactement ce que la séance travaille',
      carte.length === 0, carte.join(', '));

    /* ---------- 6 · LA NUTRITION est cohérente avec le poids ---------- */
    /* le plafond du jour, pas l'objectif : objectifNutrition() décrit
       l'intention (sèche, déficit 400), bilanKcal() en tire le nombre */
    const nut = await p.evaluate(() => {
      const b = bilanKcal();
      return b && { plafond: b.plafond, depense: b.depense, deficit: b.obj.deficit,
                    sousPlancher: b.sousPlancher, but: b.obj.t };
    });
    dit(nom + ' · le plafond calorique reste dans une plage vivable',
      nut && nut.plafond >= 1800 && nut.plafond <= 3500,
      nut ? nut.plafond + ' kcal (dépense ' + nut.depense + ', déficit ' + nut.deficit + ')' : 'aucun bilan');
    dit(nom + ' · … et il découle bien de la dépense moins le déficit',
      nut && (nut.sousPlancher || nut.plafond === nut.depense - nut.deficit),
      nut ? nut.depense + ' − ' + nut.deficit + ' = ' + (nut.depense - nut.deficit) +
            ' vs ' + nut.plafond + (nut.sousPlancher ? ' (plancher appliqué)' : '') : '');

    note(nom + ' — semaine type posée : ' + pose.semaine + '  (' + pose.seances + ' séances sur 4 semaines)');
    note(nom + ' — niveau ' + m.niv + ', ' + m.dette.length + ' muscle(s) en dette, semaine proposée : ' + plan.suite);
  }

  /* ---------- 7 · rien n'a cassé en route ---------- */
  dit('aucune erreur JS sur l\'ensemble du parcours', errs.length === 0, errs.slice(0, 3).join(' | '));
  dit('aucune ressource manquante', res404.length === 0, [...new Set(res404)].join(', '));

  await br.close();
  console.log('\n════ AUDIT DE COHÉRENCE ════\n');
  NOTE.forEach(t => console.log('  · ' + t));
  console.log('\n─ conforme (' + OK.length + ') ─');
  OK.forEach(t => console.log('  ✓ ' + t));
  if (KO.length) { console.log('\n─ CONTRADICTIONS (' + KO.length + ') ─'); KO.forEach(t => console.log('  ✗ ' + t)); }
  else console.log('\n  aucune contradiction trouvée');
  process.exit(KO.length ? 1 : 0);
})().catch(e => { console.error('CRASH', e); process.exit(2); });
