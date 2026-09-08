/* Phase A du système design : ce sont des règles, pas des goûts.
   1. aucun emoji dans le chrome — un tracé monochrome, jamais un glyphe
      dont le rendu dépend de l'appareil ;
   2. la séparation vient de la surface, la bordure n'est plus un contour ;
   3. une valeur numérique est neutre, l'accent est réservé à l'action ;
   4. une jauge porte son état, elle ne décore pas ;
   5. un stockage du mauvais type ne peut plus vider un onglet. */
const { chromium } = require('playwright-core');
const CTX = require('./contexte');
const B = CTX.BASE;
const ok = [], bad = [];
const check = (n, c, d) => (c ? ok : bad).push(n + (d ? ' — ' + d : ''));

(async () => {
  const br = await chromium.launch({ executablePath: CTX.CHROMIUM, args: ['--no-sandbox'] });
  const c = await br.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2 });
  const p = await c.newPage();
  const errs = [];
  p.on('pageerror', e => errs.push(e.message));
  p.on('console', m => { if (m.type() === 'error' && !/Failed to load resource/.test(m.text())) errs.push(m.text()); });

  await p.goto(B + '/index.html', { waitUntil: 'domcontentloaded' });
  await p.waitForTimeout(900);
  await p.evaluate(() => { try { closeRecap(); } catch (e) {} });

  // ================= 1 · le sprite =================
  const sprite = await p.evaluate(() => {
    const defs = document.querySelector('svg[aria-hidden] defs');
    const ids = [...defs.querySelectorAll('symbol')].map(s => s.id);
    /* « pointer dans le vide » se juge sur TOUT le document, pas sur le seul
       sprite d'icônes : depuis la v30 la carte musculaire et les glyphes
       Haut/Bas vivent dans leurs propres blocs. La version précédente les
       déclarait orphelins alors qu'ils résolvaient parfaitement — elle
       vérifiait moins que ce que son nom promet. */
    const tous = [...document.querySelectorAll('symbol')].map(s => s.id);
    const uses = [...document.querySelectorAll('use')].map(u => u.getAttribute('href'));
    const creux = tous.filter(id =>
      !document.getElementById(id).querySelector('path,circle,rect,polygon,line'));
    return { ids: ids, tous: tous.length, creux: creux,
             orphelins: [...new Set(uses)].filter(h => !tous.includes(h.slice(1))) };
  });
  check('le jeu d\'icônes est défini une seule fois', sprite.ids.length >= 30, sprite.ids.length + ' symboles');
  check('aucune icône ne pointe dans le vide', sprite.orphelins.length === 0,
    sprite.orphelins.join(' ') || sprite.tous + ' symboles résolus');
  /* un symbole qui existe mais ne dessine rien passait le contrôle
     précédent : c'est pourtant le carré vide qu'on cherche à éviter */
  check('… et aucun symbole n\'est une coquille vide',
    sprite.creux.length === 0, sprite.creux.join(' ') || 'tous dessinent');

  const trace = await p.evaluate(() => {
    const g = document.querySelector('.gi');
    const st = getComputedStyle(g);
    return { stroke: st.stroke, fill: st.fill, couleurTexte: getComputedStyle(g.parentElement).color };
  });
  check('l\'icône prend la couleur de son texte, jamais la sienne',
    trace.stroke === trace.couleurTexte && trace.fill === 'none', JSON.stringify(trace));

  // ================= 2 · plus d'emoji dans le chrome =================
  const chrome = ['.block-h', '.phase .tag', '.set-ic', '.info-h', '.tipbox', '.arow', '.wk-line',
                  '.io-b', '.presc-edit', '.coach-ic', '.set-chip'];
  const restes = await p.evaluate(sel => {
    const out = [];
    sel.forEach(s => document.querySelectorAll(s).forEach(e => {
      const t = [...e.querySelectorAll('*')].concat([e])
        .flatMap(n => [...n.childNodes].filter(x => x.nodeType === 3))
        .map(n => n.textContent).join('');
      if (/[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}]/u.test(t)) out.push(s + ' → ' + t.trim().slice(0, 16));
    }));
    return out;
  }, chrome);
  check('aucun emoji dans le chrome rendu', restes.length === 0, restes.join(' | '));

  const entetes = await p.evaluate(() => [...document.querySelectorAll('.block-h .num, .phase .num')]
    .map(e => ({ svg: !!e.querySelector('.gi'), txt: e.textContent.trim() })));
  check('chaque en-tête de section porte un tracé ou un numéro, jamais un emoji',
    entetes.every(e => e.svg || /^[0-9]+$/.test(e.txt)),
    JSON.stringify(entetes.filter(e => !e.svg && !/^[0-9]+$/.test(e.txt))));

  // ================= 3 · les bordures ne sont plus des contours =========
  const bords = await p.evaluate(() => {
    const l = k => getComputedStyle(document.documentElement).getPropertyValue(k).trim();
    const lum = c => { const f = c.map(x => { x /= 255; return x <= .04045 ? x / 12.92 : Math.pow((x + .055) / 1.055, 2.4); });
      return .2126 * f[0] + .7152 * f[1] + .0722 * f[2]; };
    const hex = h => { h = h.replace('#', ''); return [0, 2, 4].map(i => parseInt(h.slice(i, i + 2), 16)); };
    const surf = hex(l('--n-800')), bord = hex(l('--n-700')), champ = hex(l('--n-450'));
    const fond = hex(l('--n-700'));
    const ratio = (a, b) => { const x = lum(a) + .05, y = lum(b) + .05; return +(Math.max(x, y) / Math.min(x, y)).toFixed(2); };
    return { carte: ratio(surf, bord), saisie: ratio(surf, champ), saisieFond: ratio(fond, champ) };
  });
  check('la bordure de carte est un liseré, pas un contour', bords.carte < 1.6, 'ratio ' + bords.carte);
  /* WCAG 1.4.11 : la limite d'un composant doit tenir 3:1 contre les
     couleurs ADJACENTES — ici la carte derrière et le fond du champ. */
  check('le cadre d\'un champ tient 3:1 contre la carte', bords.saisie >= 3, 'ratio ' + bords.saisie);
  check('… et 3:1 contre son propre fond', bords.saisieFond >= 3, 'ratio ' + bords.saisieFond);

  // ================= 4 · les compteurs ne sont plus tricolores ==========
  await p.evaluate(() => {
    localStorage.clear();
    const t = new Date(), k = n => { const d = new Date(t); d.setDate(t.getDate() - n);
      return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0'); };
    const log = {}; for (let j = 20; j >= 0; j--) if (j % 3) log[k(j)] = ['push', 'pull', 'legs'][j % 3];
    localStorage.setItem('inrun_trainlog', JSON.stringify(log));
  });
  await p.reload({ waitUntil: 'domcontentloaded' });
  await p.waitForTimeout(900);
  const cpt = await p.evaluate(() => [...document.querySelectorAll('.streak .v')]
    .map(e => getComputedStyle(e).color));
  check('les trois compteurs ne portent plus trois couleurs différentes',
    new Set(cpt).size <= 2, cpt.join(' | '));

  // ================= 5 · les jauges portent leur état ==================
  const etats = await p.evaluate(() => ({
    bas: etatJauge(12), limite1: etatJauge(39), moy: etatJauge(40),
    limite2: etatJauge(69), haut: etatJauge(70), plein: etatJauge(100)
  }));
  check('sous 40 une jauge est un problème', etats.bas === 'b-bas' && etats.limite1 === 'b-bas', JSON.stringify(etats));
  check('de 40 à 69 elle est en chemin', etats.moy === 'b-moy' && etats.limite2 === 'b-moy');
  check('au-delà elle est acquise', etats.haut === 'b-haut' && etats.plein === 'b-haut');

  const couleurs = await p.evaluate(() => {
    const d = document.createElement('div'); d.className = 'niv-card';
    d.innerHTML = '<div class="bar"><i class="b-bas"></i></div><div class="bar"><i class="b-moy"></i></div>' +
                  '<div class="bar"><i class="b-haut"></i></div>';
    document.body.appendChild(d);
    const c = [...d.querySelectorAll('i')].map(e => getComputedStyle(e).backgroundColor);
    d.remove(); return c;
  });
  check('les trois états se distinguent vraiment à l\'écran',
    new Set(couleurs).size === 3, couleurs.join(' | '));

  // ================= 6 · un stockage abîmé ne vide plus l'app ==========
  await p.evaluate(() => {
    localStorage.clear();
    /* exactement la forme qui faisait planter drawWeights : un objet là
       où le code attend un tableau. Une restauration de sauvegarde
       tronquée suffit à la produire. */
    localStorage.setItem('inrun_poids', JSON.stringify({ '2026-01-01': 90 }));
    localStorage.setItem('inrun_sets', JSON.stringify([1, 2, 3]));
    localStorage.setItem('inrun_trainlog', JSON.stringify('cassé'));
    return true;
  });
  const avant = errs.length;
  await p.reload({ waitUntil: 'domcontentloaded' });
  await p.waitForTimeout(1100);
  const vivant = await p.evaluate(() => ({
    sess: document.querySelectorAll('.sess.show').length,
    bar: (document.getElementById('sessBar') || {}).innerHTML.length,
    poids: loadWeights().length, sets: Object.keys(loadExSets()).length
  }));
  check('un historique de poids du mauvais type ne fait plus planter le rendu',
    vivant.sess === 1 && vivant.bar > 0, JSON.stringify(vivant));
  const types = await p.evaluate(() => ({
    poids: Array.isArray(loadWeights()),
    sets: !Array.isArray(loadExSets()) && typeof loadExSets() === 'object',
    log: !Array.isArray(loadTLog()) && typeof loadTLog() === 'object'
  }));
  check('les chargeurs rendent le type attendu, jamais la donnée abîmée',
    types.poids && types.sets && types.log, JSON.stringify(types));
  check('la donnée du mauvais type est écartée, pas propagée',
    vivant.poids === 0 && vivant.sets === 0, JSON.stringify(vivant));
  check('et aucune erreur JS n\'est levée au passage', errs.length === avant, errs.slice(avant).join(' | '));

  // ================= 7 · la semaine d'un coup d'œil =====================
  await p.evaluate(() => {
    localStorage.clear();
    const t = new Date(), k = n => { const d = new Date(t); d.setDate(t.getDate() - n);
      return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0'); };
    const lun = new Date(t); lun.setDate(t.getDate() - ((t.getDay() + 6) % 7));
    const j = n => { const d = new Date(lun); d.setDate(lun.getDate() + n);
      return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0'); };
    /* Le repos doit être posé sur un jour PASSÉ OU AUJOURD'HUI : un « rest »
       inscrit demain n'est pas un fait, et l'app a raison de ne pas le peindre.
       Le lundi, j(1) est dans le futur — la version d'avant échouait ce
       jour-là et passait les six autres. On ancre donc sur aujourd'hui. */
    const iAuj = (t.getDay() + 6) % 7;
    const log = {};
    log[j(iAuj)] = 'rest';
    if (iAuj > 0) log[j(0)] = 'push';
    localStorage.setItem('inrun_trainlog', JSON.stringify(log));
    localStorage.setItem('inrun_plan', JSON.stringify({ [j(6)]: 'legs' }));
  });
  await p.reload({ waitUntil: 'domcontentloaded' });
  await p.waitForTimeout(1000);
  await p.evaluate(() => { try { closeRecap(); } catch (e) {} });
  const coup = await p.evaluate(() => {
    const g = document.getElementById('glance');
    const d = [...g.querySelectorAll('.gd')];
    const auj = (new Date().getDay() + 6) % 7;
    return {
      jours: d.length,
      fait: auj === 0 || (d[0].className.includes('fait') && !!d[0].querySelector('.gi')),
      repos: d[auj].className.includes('repos') && !d[auj].querySelector('.gi'),
      lundi: auj === 0,
      prevu: d[6].className.includes('prevu') && !d[6].querySelector('.gi'),
      auj: d[auj].className.includes('auj'),
      unSeulAuj: d.filter(e => e.className.includes('auj')).length,
      couleur: d[0].getAttribute('style') || '',
      deborde: g.scrollWidth > g.clientWidth + 1
    };
  });
  check('la bande porte sept jours et ne déborde pas',
    coup.jours === 7 && coup.deborde === false, JSON.stringify(coup));
  check('un jour fait porte l\'aplat de sa séance et sa coche',
    coup.fait && (coup.lundi || /--gc:var\(--s-push\)/.test(coup.couleur)),
    coup.lundi ? 'lundi : pas de jour passé dans la semaine' : coup.couleur);
  check('un repos n\'est pas une séance faite', coup.repos, JSON.stringify(coup));
  check('une intention n\'est pas un fait : le prévu reste creux et sans coche', coup.prevu);
  check('aujourd\'hui est marqué une fois et une seule',
    coup.auj && coup.unSeulAuj === 1, 'marqués : ' + coup.unSeulAuj);

  // ================= 8 · la bande détaillée tient dans l'écran =========
  await p.evaluate(() => {
    const t = new Date(), lun = new Date(t); lun.setDate(t.getDate() - ((t.getDay() + 6) % 7));
    const log = {};
    for (let i = 0; i < 7; i++) { const d = new Date(lun); d.setDate(lun.getDate() + i);
      log[d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0')] = 'push'; }
    localStorage.setItem('inrun_trainlog', JSON.stringify(log));
  });
  await p.reload({ waitUntil: 'domcontentloaded' });
  await p.waitForTimeout(1000);
  await p.evaluate(() => { show('semaine', document.querySelectorAll('.bottomnav .bn')[1]); try { closeRecap(); } catch (e) {} });
  await p.waitForTimeout(400);
  const bande = await p.evaluate(() => {
    const b = document.getElementById('weekStrip');
    return { deborde: b.scrollWidth > b.clientWidth + 1,
      rognes: [...b.querySelectorAll('.wsn')].filter(e => e.scrollWidth > e.clientWidth + 1).map(e => e.textContent) };
  });
  /* pire cas : sept jours portant le plus long des huit noms */
  check('les sept jours détaillés tiennent dans la largeur', bande.deborde === false);
  check('aucun nom de séance n\'est rogné', bande.rognes.length === 0, bande.rognes.join(' | '));

  // ================= 9 · l'aide se replie ==============================
  const aide = await p.evaluate(() => {
    const b = document.querySelector('.aide');
    const p2 = document.getElementById(b.getAttribute('aria-controls'));
    const avant = { cache: p2.hidden, dit: b.getAttribute('aria-expanded') };
    b.click();
    const apres = { cache: p2.hidden, dit: b.getAttribute('aria-expanded') };
    b.click();
    return { avant: avant, apres: apres, refermee: p2.hidden };
  });
  check('avec de l\'historique, l\'explication est repliée : la donnée passe avant',
    aide.avant.cache === true && aide.avant.dit === 'false', JSON.stringify(aide.avant));
  check('le « ? » l\'ouvre et annonce son état aux lecteurs d\'écran',
    aide.apres.cache === false && aide.apres.dit === 'true', JSON.stringify(aide.apres));
  check('et la referme', aide.refermee === true);

  await p.evaluate(() => localStorage.clear());
  await p.reload({ waitUntil: 'domcontentloaded' });
  await p.waitForTimeout(1000);
  const neuf = await p.evaluate(() => {
    const b = document.querySelector('.aide');
    return { cache: document.getElementById(b.getAttribute('aria-controls')).hidden,
      dit: b.getAttribute('aria-expanded') };
  });
  check('au tout premier lancement elle est ouverte : il n\'y a rien d\'autre à lire',
    neuf.cache === false && neuf.dit === 'true', JSON.stringify(neuf));

  // ================= 10 · l'anneau du niveau ==========================
  /* Un historique qui donne un score INTERMÉDIAIRE : à 0 ou à 100, l'arc
     vaut trivialement C ou 0 et les assertions passeraient sans rien
     vérifier. */
  const semer = () => {
    localStorage.clear();
    const t = new Date(), k = n => { const d = new Date(t); d.setDate(t.getDate() - n);
      return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0'); };
    const cycle = ['push', 'pull', 'legs', 'upper', 'core'];
    const EX = { push: ['push1','push2','push3'], pull: ['pull1','pull2','pull3'],
                 legs: ['legs1','legs2','legs3'], upper: ['up1','up2','up3'], core: ['core1','core2'] };
    const log = {}, sets = {};
    let c = 0;
    for (let j = 42; j >= 0; j--) {
      if (j % 7 === 6 || j % 7 === 2) continue;
      const g = cycle[c++ % 5];
      log[k(j)] = g;
      EX[g].forEach((id, i) => { sets[id] = sets[id] || {};
        const base = [40, 30, 25][i] + Math.floor((42 - j) / 14) * 2.5;
        sets[id][k(j)] = [0, 1, 2].map(() => ({ w: base, r: 10 + ((42 - j) % 3), rir: 2 })); });
    }
    localStorage.setItem('inrun_trainlog', JSON.stringify(log));
    localStorage.setItem('inrun_sets', JSON.stringify(sets));
  };
  await p.evaluate(semer);
  await p.reload({ waitUntil: 'domcontentloaded' });
  await p.waitForTimeout(1200);
  await p.evaluate(() => { try { closeRecap(); } catch (e) {} show('progres', document.querySelectorAll('.bottomnav .bn')[2]); });
  await p.waitForTimeout(400);

  const geo = await p.evaluate(() => {
    const arc = document.querySelector('.niv-badge .arc');
    const r = +arc.getAttribute('r');
    const C = 2 * Math.PI * r;
    const dash = parseFloat(arc.getAttribute('stroke-dasharray'));
    const cible = parseFloat(arc.getAttribute('data-arc'));
    const score = +document.querySelector('.niv-sh b').textContent;
    return { C: +C.toFixed(2), dash: dash, cible: cible, score: score,
      attendu: +(C * (1 - score / 100)).toFixed(2),
      classe: arc.getAttribute('class'),
      classeScore: document.querySelector('.niv-sh b').getAttribute('class') };
  });
  check('le scénario donne bien un score intermédiaire, sinon rien n\'est vérifié',
    geo.score > 0 && geo.score < 100, 'score ' + geo.score);
  check('la circonférence de l\'anneau est exacte', Math.abs(geo.dash - geo.C) < 0.01, geo.dash + ' vs ' + geo.C);
  check('l\'arc couvre exactement la fraction du score',
    Math.abs(geo.cible - geo.attendu) < 0.01, geo.cible + ' vs ' + geo.attendu + ' (score ' + geo.score + ')');
  check('l\'arc et le score portent le même état',
    geo.classe.includes(geo.classeScore) && /b-(bas|moy|haut)/.test(geo.classeScore),
    geo.classe + ' / ' + geo.classeScore);

  const bornes = await p.evaluate(() => {
    const C = 2 * Math.PI * 34;
    const lire = html => { const d = document.createElement('div'); d.innerHTML = html;
      const a = d.querySelector('.arc'); return +parseFloat(a.getAttribute('data-arc')).toFixed(2); };
    return { zero: lire(anneauNiveau(1, 0)), plein: lire(anneauNiveau(6, 100)),
      moitie: lire(anneauNiveau(3, 50)), C: +C.toFixed(2), deborde: lire(anneauNiveau(3, 150)) };
  });
  check('un score de 0 laisse l\'anneau vide', Math.abs(bornes.zero - bornes.C) < 0.01, JSON.stringify(bornes));
  check('un score de 100 le remplit entièrement', bornes.plein === 0, String(bornes.plein));
  check('un score hors bornes est ramené dans l\'anneau, pas au-delà',
    bornes.deborde === 0, String(bornes.deborde));

  // l'arc part de zéro : sans ça, l'animation n'a rien à parcourir
  const depart = await p.evaluate(() => {
    const d = document.createElement('div'); d.innerHTML = anneauNiveau(3, 68);
    const a = d.querySelector('.arc');
    return { attribut: parseFloat(a.getAttribute('stroke-dashoffset')),
      dash: parseFloat(a.getAttribute('stroke-dasharray')) };
  });
  check('l\'arc est rendu vide puis rempli, jamais rempli d\'emblée',
    Math.abs(depart.attribut - depart.dash) < 0.01, JSON.stringify(depart));

  const arrivee = await p.evaluate(() => {
    const a = document.querySelector('.niv-badge .arc');
    return { pose: parseFloat(a.style.strokeDashoffset), cible: parseFloat(a.getAttribute('data-arc')) };
  });
  check('et il arrive bien à sa valeur, strictement entre le vide et le plein',
    Math.abs(arrivee.pose - arrivee.cible) < 0.01 && arrivee.pose > 0 && arrivee.pose < geo.C,
    JSON.stringify(arrivee));

  const barres = await p.evaluate(() => [...document.querySelectorAll('.niv-card .bar i[data-w]')]
    .map(b => ({ w: b.style.width, d: b.getAttribute('data-w') })));
  check('les jauges reçoivent leur largeur en JS, pour que la transition existe',
    barres.length > 0 && barres.every(b => b.w === b.d + '%'), JSON.stringify(barres.slice(0, 3)));

  // ================= 11 · qui refuse le mouvement est écouté ==========
  const ctxCalme = await br.newContext({ viewport: { width: 390, height: 844 }, reducedMotion: 'reduce' });
  const pc = await ctxCalme.newPage();
  const errCalme = [];
  pc.on('pageerror', e => errCalme.push(e.message));
  await pc.goto(B + '/index.html', { waitUntil: 'domcontentloaded' });
  await pc.waitForTimeout(700);
  await pc.evaluate(semer);
  await pc.reload({ waitUntil: 'domcontentloaded' });
  await pc.waitForTimeout(1200);
  await pc.evaluate(() => { try { closeRecap(); } catch (e) {} show('progres', document.querySelectorAll('.bottomnav .bn')[2]); });
  await pc.waitForTimeout(300);
  const calme = await pc.evaluate(() => {
    const a = document.querySelector('.niv-badge .arc');
    const b = document.querySelector('.niv-card .bar i[data-w]');
    const dur = getComputedStyle(a).transitionDuration;
    confetti();
    return { detecte: mouvementRefuse(), arc: parseFloat(a.style.strokeDashoffset),
      cible: parseFloat(a.getAttribute('data-arc')), barre: b.style.width, dureeTransition: dur,
      confettis: !!document.getElementById('confCv') };
  });
  await pc.close(); await ctxCalme.close();
  check('le refus du mouvement est détecté', calme.detecte === true);
  check('les transitions sont neutralisées', parseFloat(calme.dureeTransition) < 0.01, calme.dureeTransition);
  check('l\'anneau montre quand même sa valeur, sans la parcourir',
    Math.abs(calme.arc - calme.cible) < 0.01, JSON.stringify(calme));
  check('les jauges aussi', calme.barre !== '' && calme.barre !== '0%', calme.barre);
  check('et les confettis, que le CSS ne peut pas arrêter, ne se lancent pas',
    calme.confettis === false);
  check('aucune erreur JS en mode calme', errCalme.length === 0, errCalme.join(' | '));

  // ================= 12 · la palette FIT GREEN ========================
  const pal = await p.evaluate(() => {
    const v = k => getComputedStyle(document.documentElement).getPropertyValue(k).trim();
    /* getComputedStyle résout var() : on compare les valeurs finales,
       pas les noms de jetons. */
    return { noir: v('--n-900'), carte: v('--n-800'), lime: v('--lime'),
      accent: v('--accent'), succes: v('--success'), on: v('--accent-on'),
      info: v('--info'), cardio: v('--s-cardio') };
  });
  check('les deux ancres de la palette sont posées telles quelles',
    pal.noir === '#000000' && pal.carte === '#1c1c1c', JSON.stringify(pal));
  check('le citron de la palette est bien l\'accent',
    pal.lime === '#affa01' && pal.accent === pal.lime, pal.accent + ' vs ' + pal.lime);
  check('et il porte aussi le positif : un seul vert, jamais deux',
    pal.succes === pal.lime, pal.succes);
  check('l\'encre posée SUR un aplat citron est le noir de la palette',
    pal.on === pal.noir, pal.on);
  check('le cyan garde son rôle d\'état et ne devient pas la marque',
    pal.info !== pal.lime && /^#/.test(pal.info), pal.info);

  /* Le piège de cette palette : le citron est CLAIR. Tout aplat citron
     doit porter une encre SOMBRE — l'inverse de l'orange qu'il remplace.
     Un bouton principal en blanc sur citron donne APCA 0. */
  const aplats = await p.evaluate(() => {
    const lin = c => Math.pow(c / 255, 2.4);
    const Ys = c => .2126729*lin(c[0]) + .7151522*lin(c[1]) + .0721750*lin(c[2]);
    const apca = (t, f) => { let Yt = Ys(t), Yb = Ys(f);
      Yt = Yt > .022 ? Yt : Yt + Math.pow(.022 - Yt, 1.414);
      Yb = Yb > .022 ? Yb : Yb + Math.pow(.022 - Yb, 1.414);
      if (Math.abs(Yb - Yt) < .0005) return 0;
      let L; if (Yb > Yt) { L = (Math.pow(Yb,.56) - Math.pow(Yt,.57)) * 1.14; L = L < .001 ? 0 : (L - .027)*100; }
      else { L = (Math.pow(Yb,.65) - Math.pow(Yt,.62)) * 1.14; L = L > -.001 ? 0 : (L + .027)*100; }
      return Math.abs(L); };
    const rgb = s => (s.match(/\d+/g) || []).slice(0,3).map(Number);
    const out = [];
    document.querySelectorAll('button, .io-b, .log-in button').forEach(e => {
      const st = getComputedStyle(e);
      const bg = rgb(st.backgroundColor), fg = rgb(st.color);
      if (bg.length < 3 || fg.length < 3) return;
      /* uniquement les aplats OPAQUES : un rgba(255,255,255,.05) est un
         voile sur du noir, pas une surface claire — le compter comme tel
         faisait échouer le test sur des boutons parfaitement lisibles. */
      const a = st.backgroundColor.startsWith('rgba') ? parseFloat(st.backgroundColor.split(',')[3]) : 1;
      if (a < 0.99) return;
      if (Ys(bg) < 0.25) return;
      out.push({ t: (e.textContent || '').trim().slice(0, 18), lc: Math.round(apca(fg, bg)),
        bg: st.backgroundColor, fg: st.color });
    });
    return out;
  });
  check('il existe bien des aplats clairs à vérifier', aplats.length > 0, aplats.length + ' trouvés');
  check('aucune encre claire posée sur un aplat citron',
    aplats.every(a => a.lc >= 60), JSON.stringify(aplats.filter(a => a.lc < 60)));

  /* Cardio était à 10° du citron devenu couleur de marque. */
  const cat = await p.evaluate(() => {
    const g = x => x <= .04045 ? x/12.92 : Math.pow((x+.055)/1.055, 2.4);
    const oklab = c => { const [r,gg,bb] = c.map(v => g(v/255));
      const l = Math.cbrt(.4122214708*r + .5363325363*gg + .0514459929*bb);
      const m = Math.cbrt(.2119034982*r + .6806995451*gg + .1073969566*bb);
      const s2 = Math.cbrt(.0883024619*r + .2817188376*gg + .6299787005*bb);
      return [.2104542553*l + .7936177850*m - .0040720468*s2,
              1.9779984951*l - 2.4285922050*m + .4505937099*s2,
              .0259040371*l + .7827717662*m - .8086757660*s2]; };
    const dehex = h => { h = h.trim().replace('#',''); return [0,2,4].map(i => parseInt(h.slice(i,i+2),16)); };
    const v = k => getComputedStyle(document.documentElement).getPropertyValue(k).trim();
    const noms = ['push','pull','legs','upper','core','swim','cardio','rest'];
    const P = noms.map(n => oklab(dehex(v('--s-' + n))));
    const L = oklab(dehex(v('--lime')));
    const dE = (A,B) => Math.round(Math.hypot(A[0]-B[0], A[1]-B[1], A[2]-B[2]) * 100);
    let min = 1e9, pire = '';
    for (let i = 0; i < P.length; i++) for (let j = i+1; j < P.length; j++) {
      const d = dE(P[i], P[j]); if (d < min) { min = d; pire = noms[i] + '/' + noms[j]; } }
    const auLime = noms.map((n,i) => [n, dE(P[i], L)]).sort((a,b) => a[1]-b[1])[0];
    return { min: min, pire: pire, auLime: auLime };
  });
  /* Huit catégories saturent le cercle des teintes : ΔE 8 entre jambes
     et piscine est ce que ce jeu permet, et le calendrier porte une
     légende nommée. On verrouille la valeur atteinte contre une
     dégradation, on ne prétend pas à mieux. */
  check('les huit séances restent distinctes entre elles',
    cat.min >= 8, 'la plus proche : ' + cat.pire + ' à ΔE ' + cat.min);
  check('et aucune ne se confond avec la couleur de marque',
    cat.auLime[1] >= 20, cat.auLime[0] + ' à ΔE ' + cat.auLime[1] + ' du citron');

  check('aucune erreur JS sur l\'ensemble du parcours', errs.length === 0, errs.join(' | '));

  await br.close();
  console.log('\n=== PASS (' + ok.length + ') ===');
  ok.forEach(s => console.log('  ✓ ' + s));
  if (bad.length) { console.log('\n=== FAIL (' + bad.length + ') ==='); bad.forEach(s => console.log('  ✗ ' + s)); }
  process.exit(bad.length ? 1 : 0);
})().catch(e => { console.error('CRASH', e); process.exit(2); });
