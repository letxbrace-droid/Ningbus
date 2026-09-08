/* Le coach planificateur : verdicts, alternatives, semaine proposée. */
const { chromium } = require('playwright-core');
const CTX = require('./contexte');
const B = process.env.B || CTX.BASE;
const ok = [], bad = [];
const check = (n, c, d) => (c ? ok : bad).push(n + (d ? ' — ' + d : ''));

(async () => {
  const br = await chromium.launch({ executablePath: CTX.CHROMIUM, args: ['--no-sandbox'] });
  const c = await br.newContext({ viewport: { width: 390, height: 844 } });
  const p = await c.newPage();
  const errs = [];
  p.on('pageerror', e => errs.push(e.message));
  p.on('console', m => { if (m.type() === 'error' && !/Failed to load resource/.test(m.text())) errs.push(m.text()); });

  const seed = async data => {
    await p.evaluate(d => {
      localStorage.clear();
      localStorage.setItem('inrun_programme', 'rot5');
      Object.keys(d).forEach(k => localStorage.setItem(k, JSON.stringify(d[k])));
    }, data);
    await p.reload({ waitUntil: 'domcontentloaded' });
    await p.waitForTimeout(500);
  };
  const K = (n) => p.evaluate(n => shiftKey(todayKey(), n), n);

  await p.goto(B + '/index.html', { waitUntil: 'domcontentloaded' });
  await p.waitForTimeout(800);

  // ---------- 1. prévu ≠ fait ----------
  await seed({});
  const j2 = await K(2);
  await p.evaluate(k => { openPick(k); choisirJour('legs'); }, j2);
  await p.waitForTimeout(300);
  let st = await p.evaluate(() => ({ log: loadTLog(), plan: loadPlan() }));
  check('un jour futur va dans le plan, pas dans le journal',
    st.plan[j2] === 'legs' && !st.log[j2], JSON.stringify(st));
  const streak = await p.evaluate(() => document.getElementById('stTotal').textContent);
  check('un jour prévu ne compte pas dans les séances faites', streak === '0', 'total=' + streak);

  // ---------- 2. aujourd'hui = fait ----------
  const j0 = await K(0);
  await p.evaluate(k => { openPick(k); choisirJour('push'); }, j0);
  await p.waitForTimeout(300);
  st = await p.evaluate(() => ({ log: loadTLog(), plan: loadPlan() }));
  check("aujourd'hui va dans le journal", st.log[j0] === 'push' && !st.plan[j0], JSON.stringify(st.log));

  // ---------- 3. même groupe trop tôt → stop + alternative ----------
  //  jambes faites AUJOURD'HUI, on veut les refaire DEMAIN : 24 h, refusé.
  //  (48 h — jambes hier, jambes demain — est en revanche autorisé.)
  await seed({ inrun_trainlog: { [await K(0)]: 'legs' } });
  const j1 = await K(1);
  let v = await p.evaluate(k => verdictJour(k, 'legs'), j1);
  check('jambes le lendemain de jambes → stop', v.niveau === 'stop', v.niveau + ' / ' + v.txt);
  check('une alternative est proposée', !!v.alt, 'alt=' + v.alt);
  check("l'alternative respecte les 48 h", v.alt >= (await K(2)), 'alt=' + v.alt);

  // le panneau s'affiche et rien n'est enregistré
  await p.evaluate(k => { openPick(k); choisirJour('legs'); }, j1);
  await p.waitForTimeout(250);
  const shown = await p.evaluate(() => document.getElementById('pickVerdict').classList.contains('show'));
  st = await p.evaluate(() => loadPlan());
  check('le verdict bloquant s\'affiche', shown);
  check('rien n\'est enregistré tant que tu n\'as pas tranché', !st[j1], JSON.stringify(st));

  // ---------- 4. le bouton « Caler <jour> » ----------
  const alt = v.alt;
  await p.evaluate(() => accepterAlt());
  await p.waitForTimeout(300);
  st = await p.evaluate(() => loadPlan());
  check('« Caler » enregistre sur le jour proposé', st[alt] === 'legs' && !st[j1], JSON.stringify(st));

  // ---------- 5. « Garder quand même » ----------
  await seed({ inrun_trainlog: { [await K(0)]: 'legs' } });
  await p.evaluate(k => { openPick(k); choisirJour('legs'); forcerJour(); }, j1);
  await p.waitForTimeout(300);
  st = await p.evaluate(() => loadPlan());
  check('« Garder quand même » respecte ton choix', st[j1] === 'legs', JSON.stringify(st));

  // ---------- 6. 4 jours d'affilée → repos conseillé ----------
  await seed({ inrun_trainlog: { [await K(-3)]: 'push', [await K(-2)]: 'pull', [await K(-1)]: 'legs' } });
  v = await p.evaluate(k => verdictJour(k, 'upper'), j0);
  check('4ᵉ jour d\'affilée → avertissement', v.niveau === 'warn', v.niveau + ' / ' + v.txt);
  check('… avec la proposition de repos', v.rest === true, JSON.stringify(v.rest));

  // ---------- 7. bon créneau → aucune friction ----------
  await seed({ inrun_trainlog: { [await K(-3)]: 'legs' } });
  v = await p.evaluate(k => verdictJour(k, 'legs'), j0);
  check('3 jours de récup → feu vert', v.niveau === 'ok', v.niveau + ' / ' + v.txt);

  await p.evaluate(k => { openPick(k); choisirJour('legs'); }, j0);
  await p.waitForTimeout(300);
  const sheetClosed = await p.evaluate(() => !document.getElementById('pickSheet').classList.contains('show'));
  const toast = await p.evaluate(() => document.getElementById('coachToast').classList.contains('show'));
  check('feu vert : la feuille se ferme sans friction', sheetClosed);
  check('… et le coach confirme dans un message', toast);

  // ---------- 7 bis. 48 h pile : autorisé, mais nuancé ----------
  await seed({ inrun_trainlog: { [await K(-1)]: 'legs' } });
  const v48 = await p.evaluate(k => verdictJour(k, 'legs'), await K(1));
  check('48 h pile → autorisé, avec la nuance', v48.niveau === 'ok' && /minimum viable/.test(v48.txt), v48.txt);

  // ---------- 8. suggestion sur les jours libres ----------
  await seed({ inrun_trainlog: { [await K(-1)]: 'legs', [await K(-2)]: 'push' } });
  const sug = await p.evaluate(k => suggestGroup(k), j0);
  check('le jour libre évite le groupe fait hier', sug && sug !== 'legs', 'suggéré: ' + sug);
  const strip = await p.evaluate(() => {
    const els = [...document.querySelectorAll('#weekStrip .ws')];
    return els.map(e => (e.className.match(/free|plan|done/) || [''])[0] + ':' + e.querySelector('.wsn').textContent);
  });
  check('la bande de la semaine affiche des créneaux libres',
    strip.some(s => s.startsWith('free:') && s.length > 5), JSON.stringify(strip));

  // ---------- 9. semaine proposée ----------
  await seed({});
  await p.evaluate(() => proposerSemaine());
  await p.waitForTimeout(400);
  const plan = await p.evaluate(() => loadPlan());
  const keys = Object.keys(plan).sort();
  check('7 jours proposés', keys.length === 7, JSON.stringify(plan));
  check('rien n\'est posé sur aujourd\'hui ni le passé', keys.every(k => k > j0), keys[0]);
  // règle des 48 h respectée
  let viol48 = [];
  keys.forEach((k, i) => {
    for (let j = i + 1; j < keys.length; j++) {
      if (plan[keys[j]] === plan[k] && plan[k] !== 'rest') {
        const d = (new Date(keys[j]) - new Date(k)) / 86400000;
        if (d < 2) viol48.push(plan[k] + ' ' + k + '→' + keys[j]);
      }
    }
  });
  check('aucun groupe répété à moins de 48 h', viol48.length === 0, viol48.join(', '));
  // pas plus de 3 jours d'entraînement d'affilée
  let run = 0, maxRun = 0;
  keys.forEach(k => { const t = plan[k]; if (t && t !== 'rest' && t !== 'cardio') { run++; maxRun = Math.max(maxRun, run); } else run = 0; });
  check('jamais plus de 3 jours d\'entraînement d\'affilée', maxRun <= 3, 'max=' + maxRun);
  const groupes = [...new Set(keys.map(k => plan[k]).filter(t => t !== 'rest'))];
  check('la semaine couvre plusieurs groupes', groupes.length >= 3, groupes.join(','));

  // ---------- 10. effacer le prévu ----------
  await p.evaluate(k => { const l = loadTLog(); l[k] = 'push'; saveTLog(l); }, j0);
  await p.evaluate(() => effacerPlan());
  await p.waitForTimeout(300);
  st = await p.evaluate(() => ({ log: loadTLog(), plan: loadPlan() }));
  check('« Effacer le prévu » vide le plan', Object.keys(st.plan).length === 0, JSON.stringify(st.plan));
  check('… et laisse les séances faites', st.log[j0] === 'push', JSON.stringify(st.log));

  // ---------- 11. migration des jours futurs déjà marqués ----------
  await seed({ inrun_trainlog: { [await K(3)]: 'legs', [await K(-1)]: 'push' } });
  st = await p.evaluate(() => ({ log: loadTLog(), plan: loadPlan() }));
  const j3 = await K(3);
  check('un jour futur déjà marqué bascule en prévu',
    st.plan[j3] === 'legs' && !st.log[j3] && st.log[await K(-1)] === 'push', JSON.stringify(st));

  // ---------- 12. une série enregistrée efface le prévu du jour ----------
  await seed({ inrun_plan: { [j0]: 'push' } });   // sera nettoyé (pas futur) → on force
  await p.evaluate(k => { const pl = loadPlan(); pl[k] = 'push'; savePlan(pl); }, j0);
  await p.fill('.station[data-ex="push1"] [data-w]', '40');
  await p.fill('.station[data-ex="push1"] [data-r]', '10');
  await p.click('.station[data-ex="push1"] [data-save]');
  await p.waitForTimeout(400);
  st = await p.evaluate(() => ({ log: loadTLog(), plan: loadPlan() }));
  check('une série faite fait passer le jour de prévu à fait',
    st.log[j0] === 'push' && !st.plan[j0], JSON.stringify(st));

  // ---------- 13. le coach parle du prochain jour prévu ----------
  await seed({ inrun_trainlog: { [await K(-1)]: 'push' }, inrun_plan: { [j1]: 'legs' } });
  const coach = await p.evaluate(() => document.getElementById('coachMsg').textContent);
  check('le coach annonce la séance prévue', /Prévu demain\s*:\s*Jambes/i.test(coach), coach.slice(-160));

  // ---------- 14. export ----------
  await p.evaluate(() => { const pl = loadPlan(); pl['2099-01-01'] = 'legs'; savePlan(pl); });
  const exported = await p.evaluate(() => ALL_KEYS.indexOf('inrun_plan') >= 0);
  check('le plan est inclus dans la sauvegarde', exported);

  // ---------- 15. aucun groupe ne peut disparaître de la semaine ----------
  /* Le test qui manquait. En v24, une série ajoutée au leg curl a fait
     passer la séance Jambes au-dessus d'un plafond écrit en dur ; le
     planificateur écartait tout groupe en « warn », et les jambes ont
     disparu de TOUTES les semaines proposées — sans qu'aucune suite ne
     bronche. On vérifie donc l'invariant lui-même, à tous les niveaux :
     une semaine proposée sert les cinq groupes. */
  for (const niv of [1, 3, 4, 5, 6]) {
    const r = await p.evaluate(async n => {
      const t = new Date(), k = d => { const x = new Date(t); x.setDate(t.getDate() - d);
        return x.getFullYear()+'-'+String(x.getMonth()+1).padStart(2,'0')+'-'+String(x.getDate()).padStart(2,'0'); };
      localStorage.clear();
      localStorage.setItem('inrun_programme', 'rot5');
      localStorage.setItem('inrun_profil', JSON.stringify({ h: 175, w: 90, a: 35 }));
      saveNiv({ n: n });
      const log = {}, sets = {};
      ['push', 'pull', 'legs', 'upper', 'core'].forEach((g, i) => {
        log[k(i + 1)] = g;
        document.querySelectorAll('#' + g + ' .station').forEach(st => {
          const id = st.getAttribute('data-ex'), pr = getPresc(st);
          sets[id] = sets[id] || {};
          sets[id][k(i + 1)] = Array.from({ length: pr.sets }, () => ({ w: 40, r: pr.hi, rir: 1 }));
        });
      });
      localStorage.setItem('inrun_trainlog', JSON.stringify(log));
      localStorage.setItem('inrun_sets', JSON.stringify(sets));
      saveTLog(log); saveExSets(sets);
      refreshPlan(); effacerPlan(); proposerSemaine();
      const plan = loadPlan(), vus = {};
      Object.keys(plan).forEach(d => { vus[plan[d]] = true; });
      return { absents: ['push', 'pull', 'legs', 'upper', 'core'].filter(g => !vus[g]),
        plan: Object.keys(plan).sort().map(d => plan[d]).join(' ') };
    }, niv);
    check('niveau ' + niv + ' : la semaine proposée sert les 5 groupes',
      r.absents.length === 0, r.absents.length ? 'absent(s) : ' + r.absents.join(', ') + ' → ' + r.plan : r.plan);
  }

  /* le plafond de saturation ne peut pas condamner la séance elle-même */
  const plaf = await p.evaluate(() => {
    /* absente des versions antérieures : on le dit au lieu de planter */
    if (typeof volumeSeance !== 'function') return null;
    return ['push', 'pull', 'legs', 'upper', 'core'].map(g => {
      const niv = niveauActuel();
      const dur = Math.max(niv >= 5 ? 26 : 22, Math.round(volumeSeance(g) * 1.5));
      return { g: g, seance: volumeSeance(g), plafond: dur, ok: dur >= volumeSeance(g) };
    });
  });
  check('aucun plafond de saturation n\'est sous le volume de sa propre séance',
    !!plaf && plaf.every(x => x.ok),
    plaf ? plaf.map(x => x.g + ' ' + x.seance + '/' + x.plafond).join(' · ') : 'volumeSeance() absente');

  await p.evaluate(() => { localStorage.clear();
      localStorage.setItem('inrun_programme', 'rot5'); saveNiv({}); });
  await p.reload({ waitUntil: 'domcontentloaded' });
  await p.waitForTimeout(600);

  /* ===== La bande de la semaine conseille-t-elle une semaine tenable ? =====
     Elle calculait chaque jour libre contre le MÊME contexte : aucun ne
     savait ce que son voisin affichait, et les cinq jours libres portaient
     la même séance. Le coach montrait donc, comme son propre conseil, une
     semaine qu'il refuse sur les cinq jours.

     proposerSemaine() chaînait déjà ses décisions ; l'affichage non. Deux
     surfaces, la même donnée, deux réponses contraires. */
  for (const prog of ['hb', 'rot5']) {
    const nomP = prog === 'hb' ? 'Haut/Bas' : 'la rotation';
    await p.evaluate(cd => {
      const t = new Date(), k = d => { const x = new Date(t); x.setDate(t.getDate() - d);
        return x.getFullYear()+'-'+String(x.getMonth()+1).padStart(2,'0')+'-'+String(x.getDate()).padStart(2,'0'); };
      localStorage.clear();
      localStorage.setItem('inrun_programme', cd);
      localStorage.setItem('inrun_profil', JSON.stringify({ h:175, w:90, a:35 }));
      localStorage.setItem('inrun_trainlog', JSON.stringify({ [k(1)]: PROGRAMMES[cd].seances[0] }));
      localStorage.setItem('inrun_plan', '{}');
    }, prog);
    await p.reload({ waitUntil: 'domcontentloaded' });
    await p.waitForTimeout(1300);
    await p.evaluate(() => { try { closeRecap(); } catch (e) {} show('semaine'); });
    await p.waitForTimeout(400);

    const bande = await p.evaluate(() => {
      const libres = [...document.querySelectorAll('#weekStrip .ws.free')]
        .map(e => (e.querySelector('.wsn') || {}).textContent || '');
      const parCourt = {};
      Object.keys(SESS).forEach(g => { parCourt[SESS[g].c] = g; });
      /* on rejoue ce que la bande annonce, jour après jour, et on demande
         son avis au coach sur SA propre proposition */
      const c = { log: loadTLog(), plan: {} };
      let k = todayKey(), refuses = 0, n = 0;
      libres.forEach(nom => {
        const g = parCourt[nom];
        if (g) { c.plan[k] = g; n++; }
        k = shiftKey(k, 1);
      });
      Object.keys(c.plan).forEach(x => {
        if (SESSIONS.indexOf(c.plan[x]) < 0) return;
        if (verdictJour(x, c.plan[x], c, true).niveau === 'stop') refuses++;
      });
      return { libres, refuses, n, distinctes: new Set(libres.filter(Boolean)).size };
    });

    check('en ' + nomP + ', la bande ne conseille aucun jour que le coach refuse',
      bande.refuses === 0, bande.refuses + ' refusé(s) sur ' + bande.n + ' · ' + bande.libres.join(' '));
    /* le symptôme visible : cinq fois la même séance d'affilée */
    check('… et elle ne répète pas la même séance sur tous les jours libres',
      bande.libres.length < 3 || bande.distinctes >= 2,
      bande.distinctes + ' séance(s) distincte(s) · ' + bande.libres.join(' '));
  }

  await p.evaluate(() => { localStorage.clear();
      localStorage.setItem('inrun_programme', 'rot5'); saveNiv({}); });
  await p.reload({ waitUntil: 'domcontentloaded' });
  await p.waitForTimeout(600);

  check('aucune erreur JS pendant tout le parcours', errs.length === 0, errs.join(' | '));

  await br.close();
  console.log('\n=== PASS (' + ok.length + ') ===');
  ok.forEach(s => console.log('  ✓ ' + s));
  if (bad.length) { console.log('\n=== FAIL (' + bad.length + ') ==='); bad.forEach(s => console.log('  ✗ ' + s)); }
  process.exit(bad.length ? 1 : 0);
})().catch(e => { console.error('CRASH', e); process.exit(2); });
