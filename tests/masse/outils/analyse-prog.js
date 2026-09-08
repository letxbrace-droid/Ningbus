/* Analyse du programme avec les VRAIES données, en utilisant la doctrine de
   l'app elle-même (MUSCLES_DET, volumeParMuscle, cibleMuscle) plutôt qu'une
   réimplémentation qui pourrait diverger. */
const { chromium } = require('playwright-core');
const fs = require('fs');
const CTX = require('../contexte');
const SAUV = JSON.parse(fs.readFileSync(
  require('path').join(CTX.FIXTURES, 'historique.json'),'utf8'));

(async () => {
  const br = await chromium.launch({
    executablePath: CTX.CHROMIUM, args:['--no-sandbox'] });
  const p = await (await br.newContext()).newPage();
  await p.addInitScript(d => { for (const k in d) localStorage.setItem(k, d[k]); }, SAUV.data);
  await p.goto('http://127.0.0.1:8765/index.html', { waitUntil:'networkidle' });
  await p.waitForTimeout(1400);

  const R = await p.evaluate(() => {
    const out = {};
    out.programme = progActif();
    out.niveau = niveauActuel();

    /* --- ce que le PROGRAMME prescrit, par séance et par muscle --- */
    const carte = carteMuscles();
    const P = PROGRAMMES[progActif()];
    const seances = P.seances, passages = P.passages;
    const parSeance = {}, presParMuscle = {};
    for (const g of seances) {
      const sess = document.querySelector('.sess[id="' + g + '"]');
      if (!sess) continue;
      parSeance[g] = [];
      sess.querySelectorAll('.station').forEach(st => {
        const id = st.getAttribute('data-ex');
        const cv = st.querySelectorAll('.presc .cv');
        const nb = cv[0] ? parseInt(cv[0].textContent, 10) : 0;
        const reps = cv[1] ? cv[1].textContent.trim() : '';
        const mus = carte[id] || [];
        parSeance[g].push({ id, nom: (st.querySelector('.st-name')||{}).textContent,
                            series: nb, reps, muscles: mus });
        /* l'app pondère : le PREMIER muscle d'une machine compte 1 série,
           les suivants (assistance) comptent 0,5 — cf. volumeNominalDe() */
        mus.forEach((m,i) => { presParMuscle[m] = (presParMuscle[m]||0) + nb*(passages[g]||1)*(i===0?1:0.5); });
      });
    }
    out.parSeance = parSeance;
    out.prescritSemaine = presParMuscle;   /* séries/semaine si le programme est suivi */
    out.passages = passages;

    /* --- ce qu'il FAIT réellement, sur 28 jours --- */
    out.reel28 = volumeParMuscle(28);
    out.cibles = {};
    Object.keys(MUSCLES_DET).forEach(m => { out.cibles[m] = cibleMuscle(m); });
    /* le test contrôle les niveaux 1 à 3 : la contrainte qui lie est
       min du niveau 3 (coef 1,0) et max du niveau 1 (coef 0,8) */
    out.bornes = {};
    Object.keys(MUSCLES_DET).forEach(m => {
      out.bornes[m] = { min: cibleMuscle(m,3).min, max: cibleMuscle(m,1).max, nom: MUSCLES_DET[m].n };
    });
    out.nominal = volumeNominal();

    /* --- les jauges du coach --- */
    out.jauges = { assiduite: jaugeAssiduite(), equilibre: jaugeEquilibre(), force: jaugeForce() };
    return out;
  });

  fs.writeFileSync(require('path').join(CTX.SORTIE, 'prog.json'),
                   JSON.stringify(R, null, 1));
  console.log('programme actif :', R.programme, '· niveau', R.niveau);
  console.log('séances :', Object.keys(R.parSeance).join(', '));
  await br.close();
})().catch(e => { console.error('CRASH', e.message); process.exit(2); });
