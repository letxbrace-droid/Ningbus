const { chromium } = require('playwright-core');
const CTX = require('./contexte');
const ok=[],ko=[];
const check=(n,c,d)=>((c?ok:ko).push(n+(d?' — '+d:'')));
(async () => {
  const br = await chromium.launch({ executablePath: CTX.CHROMIUM, args:['--no-sandbox'] });
  const p = await (await br.newContext()).newPage();
  const errs=[]; p.on('pageerror', e=>errs.push(e.message));
  await p.goto('http://127.0.0.1:8765/index.html', { waitUntil:'domcontentloaded' });
  await p.waitForTimeout(1200);

  const r = await p.evaluate(() => {
    const o = {};
    o.sessPdc = document.querySelectorAll('.sess[data-prog="pdc"]').length;
    o.stationsPdc = document.querySelectorAll('.sess[data-prog="pdc"] .station').length;
    o.maison = JSON.stringify(MAISON);
    // les séances maison sont hors de tous les comptages du programme actif
    o.stationsActives = document.querySelectorAll(selActif()+' .station').length;
    o.sessions = SESSIONS.join(',');
    // volume : aucune station maison ne doit être comptée
    o.volHaut = document.querySelectorAll('#haut .station').length;
    o.volMhaut = document.querySelectorAll('#mhaut .station').length;
    // prescriptions maison : fourchettes et types
    o.presc = [...document.querySelectorAll('.sess[data-prog="pdc"] .station')].map(st => {
      const pr = getPresc(st);
      return { ex: st.getAttribute('data-ex'), lo: pr.lo, hi: pr.hi, sets: pr.sets, t: typeExo(pr) };
    });
    // la carte musculaire connaît les nouveaux exercices
    const c = carteMuscles();
    o.sansMuscle = o.presc.map(x=>x.ex).filter(e => !(c[e]||[]).length);
    // navigation
    showSess('mhaut', true, true);
    o.montre = (document.querySelector('.sess.show')||{}).id;
    o.sessKey = localStorage.getItem('inrun_last_sess');
    showSess('haut', true, true);
    o.retour = (document.querySelector('.sess.show')||{}).id;
    return o;
  });

  check('deux séances maison dans le DOM', r.sessPdc===2, r.sessPdc+' séance(s)');
  check('dix stations maison', r.stationsPdc===10, r.stationsPdc+' station(s)');
  check('les 10 stations maison restent hors du programme actif', r.stationsActives===20,
        r.stationsActives+' stations comptées · SESSIONS='+r.sessions);
  check('le volume de Haut ne bouge pas', r.volHaut===11, r.volHaut+' stations en salle, '+r.volMhaut+' à la maison');
  check('chaque prescription maison a une fourchette',
        r.presc.every(x=>x.lo!==x.hi), r.presc.filter(x=>x.lo===x.hi).map(x=>x.ex).join(' ')||'toutes');
  check('les types poly/iso sont cohérents',
        r.presc.every(x=>(x.lo<=10)===(x.t==='poly')), r.presc.map(x=>x.ex+':'+x.lo+'-'+x.hi+' '+x.t).join(' · '));
  check('chaque exercice maison est sur la carte musculaire',
        r.sansMuscle.length===0, r.sansMuscle.join(' ')||'les dix');
  check('la navigation ouvre la séance maison', r.montre==='mhaut', r.montre);
  check('… sans mémoriser une séance hors programme', r.sessKey==='haut', 'mémorisé : '+r.sessKey);
  check('le retour en salle fonctionne', r.retour==='haut', r.retour);

  // calories : une séance maison coûte moins cher
  const kc = await p.evaluate(() => {
    localStorage.setItem('inrun_profil', JSON.stringify({h:175,w:90,a:35}));
    const k = todayKey();
    localStorage.removeItem('inrun_maison');
    localStorage.setItem('inrun_activite', JSON.stringify({[k]:{b:'assis',s:1}}));
    const salle = bilanKcal(k).plafond;
    marquerMaison(k);
    const maison = bilanKcal(k).plafond;
    return { salle, maison, ecart: salle-maison };
  });
  check('une séance en salle plafonne à 2 180', kc.salle===2180, kc.salle+' kcal');
  check('une séance maison plafonne plus bas', kc.maison===2050, kc.maison+' kcal · écart '+kc.ecart);
  // le journal ne doit connaître que 'haut' / 'bas' : sinon la récupération,
  // le calendrier et la bande de la semaine ne reconnaîtraient plus la séance
  const jr = await p.evaluate(() => {
    localStorage.removeItem('inrun_trainlog');
    localStorage.removeItem('inrun_maison');
    autoMark('mbas');
    const k = todayKey();
    return { log: loadTLog()[k], maison: estMaison(k), reconnu: SESSIONS.indexOf(loadTLog()[k]) >= 0 };
  });
  check('une séance maison s\'inscrit au journal comme sa moitié en salle',
    jr.log === 'bas', 'journal : ' + jr.log);
  check('… et reste une séance que le programme reconnaît', jr.reconnu);
  check('… tout en étant marquée « maison » pour les calories', jr.maison === true);

  const cta = await p.evaluate(() => ({
    haut: !!document.querySelector('#haut .maison-cta button'),
    bas:  !!document.querySelector('#bas .maison-cta button'),
    retourH: !!document.querySelector('#mhaut .maison-cta button'),
    retourB: !!document.querySelector('#mbas .maison-cta button')
  }));
  check('chaque séance en salle offre son repli maison', cta.haut && cta.bas, JSON.stringify(cta));
  check('… et chaque séance maison ramène en salle', cta.retourH && cta.retourB, JSON.stringify(cta));

  check('aucune erreur JS', errs.length===0, errs.join(' | ')||'aucune');

  console.log('=== PASS ('+ok.length+') ==='); ok.forEach(x=>console.log('  ✓ '+x));
  if(ko.length){ console.log('=== FAIL ('+ko.length+') ==='); ko.forEach(x=>console.log('  ✗ '+x)); }
  await br.close();
  process.exit(ko.length?1:0);
})();
