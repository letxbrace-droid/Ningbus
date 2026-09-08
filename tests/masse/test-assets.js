/* Les assets générés : servis, chargés, appliqués, et précachés. */
const { chromium } = require('playwright-core');
const fs = require('fs');
const CTX = require('./contexte');
const B = CTX.BASE;
const ok = [], bad = [];
const check = (n, c, d) => (c ? ok : bad).push(n + (d ? ' — ' + d : ''));
/* Les cinq icônes de séance ont disparu en v31 : toute séance est dessinée
   par le pictogramme de sa moitié de corps. Restent les icônes hors séance
   et de navigation. */
const ICONES = ['today','semaine','progres','moi',
                'swim','cardio','rest','fatigue','normal','forme','coach'];
/* Les sept planches mus-*.webp ont disparu en v30 : la carte est un
   <symbol> SVG inline. Il ne reste donc que les icônes et les fonds. */

(async () => {
  const br = await chromium.launch({ executablePath: CTX.CHROMIUM, args: ['--no-sandbox'] });
  const c = await br.newContext({ viewport: { width: 390, height: 844 } });
  const p = await c.newPage();
  const rates = [];
  p.on('requestfailed', r => rates.push(r.url()));
  p.on('response', r => { if (r.status() >= 400) rates.push(r.status() + ' ' + r.url()); });

  await p.goto(B + '/index.html', { waitUntil: 'domcontentloaded' });
  await p.waitForTimeout(1200);

  // ---------- fichiers servis ----------
  let poids = 0, manquants = [];
  for (const f of [...ICONES.map(n => 'ic-' + n), 'fond-texture', 'fond-salle']) {
    const r = await p.request.get(B + '/img/' + f + '.webp');
    if (!r.ok()) manquants.push(f); else poids += (await r.body()).length;
  }
  check('les 13 assets restants sont servis', manquants.length === 0, manquants.join(', '));
  check('poids total sous 200 Ko', poids < 200 * 1024, Math.round(poids / 1024) + ' Ko');

  // ---------- masques réellement appliqués ----------
  const masques = await p.evaluate(() => {
    const out = { total: 0, sans: [] };
    /* seules les icônes-masques sont concernées : une icône vectorielle
       porte ses tracés, pas un masque, et sa présence est vérifiée plus
       bas par le contrat « chaque séance dessine quelque chose ». */
    document.querySelectorAll('i.ic').forEach(e => {
      out.total++;
      const m = getComputedStyle(e).webkitMaskImage || getComputedStyle(e).maskImage;
      if (!m || m === 'none') out.sans.push(e.className);
    });
    return out;
  });
  /* Une classe .ic sans règle de masque ne dessine RIEN : le carré reste
     vide. C'est arrivé aux deux séances Haut/Bas, dont les icônes
     n'existaient pas. On vérifie donc que CHAQUE séance offerte a la
     sienne, pas seulement que le total est correct. */
  /* Deux mécaniques cohabitent depuis la v30 : masque WebP pour les icônes
     historiques, tracé SVG pour Haut et Bas. Le contrat ne change pas —
     chaque séance offerte doit DESSINER quelque chose — mais il ne peut
     plus se vérifier sur le seul masque. */
  const sansIcone = await p.evaluate(() => SESSIONS.filter(g => {
    const html = ico(g);
    const h = document.createElement('div');
    h.innerHTML = html; document.body.appendChild(h);
    const e = h.firstChild;
    let dessine;
    if (e.tagName.toLowerCase() === 'svg') {
      const u = e.querySelector('use');
      const id = u && (u.getAttribute('href') || '').slice(1);
      dessine = !!(id && document.getElementById(id) &&
                   document.getElementById(id).querySelector('path,circle,rect'));
    } else {
      const m = getComputedStyle(e).webkitMaskImage || getComputedStyle(e).maskImage;
      dessine = !!m && m !== 'none';
    }
    h.remove();
    return !dessine;
  }));
  check('chaque séance du programme a une icône dessinée',
    sansIcone.length === 0, sansIcone.join(', ') || 'toutes');

  /* « Aucune séance n'emprunte le dessin d'une autre » était la bonne
     règle tant qu'une icône valait pour une séance. Depuis la v31 le
     partage est VOULU : Poussée, Tirage et Bras travaillent tous le haut
     du corps et portent donc le même pictogramme. La règle devient plus
     forte — l'icône doit correspondre à la moitié du corps réellement
     travaillée, et c'est le programme Haut/Bas qui en juge. */
  const moities = await p.evaluate(() => {
    const carte = carteMuscles(), moitie = {}, faux = [];
    ['haut','bas'].forEach(m =>
      document.querySelectorAll('#' + m + ' .station').forEach(st =>
        (carte[st.getAttribute('data-ex')] || []).forEach(mus => {
          if (!moitie[mus]) moitie[mus] = m;
        })));
    SEANCES_CONNUES.forEach(g => {
      const n = { haut: 0, bas: 0 }, vus = {};
      document.querySelectorAll('#' + g + ' .station').forEach(st =>
        (carte[st.getAttribute('data-ex')] || []).forEach(mus => {
          if (vus[mus]) return; vus[mus] = 1;
          if (moitie[mus]) n[moitie[mus]]++;
        }));
      const attendu = n.haut >= n.bas ? 'haut' : 'bas';
      const h = document.createElement('div');
      h.innerHTML = ico(g); document.body.appendChild(h);
      const u = h.firstChild.querySelector && h.firstChild.querySelector('use');
      const obtenu = u ? (u.getAttribute('href') || '').replace('#i-', '') : '(pas de tracé)';
      h.remove();
      if (obtenu !== attendu)
        faux.push(g + ' : ' + obtenu + ' au lieu de ' + attendu +
                  ' (haut ' + n.haut + ' / bas ' + n.bas + ')');
    });
    return faux;
  });
  check('l\'icône d\'une séance est celle de la moitié qu\'elle travaille',
    moities.length === 0, moities.join(' · ') || 'les 7 séances concordent');

  /* Le sélecteur de jour codait EN DUR les cinq séances de la rotation :
     en Haut/Bas il proposait de consigner « Poussée », une séance absente
     du programme. */
  const grille = await p.evaluate(() => {
    const g = document.getElementById('pickGrid');
    const noms = [...g.querySelectorAll('.pick')].map(e => e.textContent.trim());
    const attendus = SESSIONS.map(k => SESS[k].t);
    const hors = SEANCES_CONNUES.filter(k => SESSIONS.indexOf(k) < 0).map(k => SESS[k].t);
    return { manquants: attendus.filter(t => !noms.includes(t)),
             intrus: hors.filter(t => noms.includes(t)),
             noms: noms.join(' · ') };
  });
  check('le sélecteur de jour ne propose que les séances du programme',
    grille.manquants.length === 0 && grille.intrus.length === 0,
    (grille.manquants.length ? 'manque ' + grille.manquants.join(',') : '') +
    (grille.intrus.length ? ' intrus ' + grille.intrus.join(',') : '') || grille.noms);

  /* Un <svg> qui porterait un background peindrait un carré plein
     par-dessus son propre tracé : colorer une icône passe par `color`. */
  const carres = await p.evaluate(() => [...document.querySelectorAll('svg.ic')]
    .map(e => getComputedStyle(e).backgroundColor)
    .filter(c => c && c !== 'rgba(0, 0, 0, 0)' && c !== 'transparent'));
  check('aucune icône vectorielle n\'est masquée par un fond plein',
    carres.length === 0, carres.join(', ') || 'toutes transparentes');
  check('toutes les icônes-masques ont bien leur masque', masques.sans.length === 0 && masques.total >= 10,
    masques.total + ' icônes, ' + masques.sans.length + ' sans masque');

  // ---------- les fichiers de masque se décodent ----------
  const decodees = await p.evaluate(async noms => {
    const bad = [];
    for (const n of noms) {
      const img = new Image(); img.src = 'img/' + n + '.webp';
      try { await img.decode(); if (!img.naturalWidth) bad.push(n); } catch (e) { bad.push(n); }
    }
    return bad;
  }, ICONES.map(n => 'ic-' + n));
  check('tous les WebP se décodent', decodees.length === 0, decodees.join(', '));

  // ---------- la carte musculaire, désormais pilotée ----------
  /* Elle n'illustre plus la séance, elle la lit. Ces vérifications portent
     donc sur le CÂBLAGE, pas sur des fichiers. */
  const carte = await p.evaluate(() => {
    const svg = document.querySelector('.sess.show .sh-mus');
    const sym = document.getElementById('mv-carte');
    const r = svg.getBoundingClientRect();
    const vb = (sym.getAttribute('viewBox') || '').split(/\s+/).map(Number);
    return {
      nb: document.querySelectorAll(selActif() + ' .sh-mus').length,
      nbSess: document.querySelectorAll(selActif()).length,
      symbole: !!sym,
      use: !!svg.querySelector('use'),
      href: (svg.querySelector('use') || {}).getAttribute
        ? svg.querySelector('use').getAttribute('href') : null,
      chemins: sym.querySelectorAll('path').length,
      w: Math.round(r.width), h: Math.round(r.height),
      ratio: vb.length === 4 ? vb[2] / vb[3] : 0
    };
  });
  check('une carte par séance du programme actif',
    carte.nb === carte.nbSess, carte.nb + ' cartes / ' + carte.nbSess + ' séances');
  check('les tracés ne sont stockés qu\'une fois, dans un <symbol>',
    carte.symbole && carte.use && carte.href === '#mv-carte',
    carte.chemins + ' chemins, href ' + carte.href);

  /* LE piège du remplacement : un <svg> sans hauteur explicite retombe sur
     les 150 px par défaut des éléments remplacés, là où un <img> déduisait
     la sienne du fichier. La carte sortait en 100×150 au lieu de 100×105. */
  check('la carte garde le rapport de son viewBox, pas le 150 px par défaut',
    Math.abs(carte.w / carte.h - carte.ratio) < 0.03,
    carte.w + '×' + carte.h + ' pour un rapport attendu de ' + carte.ratio.toFixed(3));

  /* Le contrat qui remplace « le fichier existe » : tout muscle que l'app
     sait compter doit être traçable, sinon il serait comptabilisé dans le
     volume sans jamais apparaître sur la carte. */
  const couverture = await p.evaluate(() => {
    const sym = document.getElementById('mv-carte');
    const traces = new Set();
    sym.querySelectorAll('path[style]').forEach(q => {
      const m = /--m-([A-Za-z]+)/.exec(q.getAttribute('style'));
      if (m) traces.add(m[1]);
    });
    return { manquants: Object.keys(MUSCLES_DET).filter(m => !traces.has(m)),
             inconnus: [...traces].filter(m => !MUSCLES_DET[m]),
             n: traces.size };
  });
  check('les 16 muscles de MUSCLES_DET ont tous un tracé',
    couverture.manquants.length === 0, couverture.manquants.join(', ') || couverture.n + ' tracés');
  check('… et aucun tracé ne désigne un muscle que l\'app ignore',
    couverture.inconnus.length === 0, couverture.inconnus.join(', ') || 'aucun');

  /* Chaque séance doit allumer exactement les muscles de ses machines :
     c'est ce que sept images ne pouvaient pas garantir. */
  const peint = await p.evaluate(() => {
    const carte = carteMuscles(), out = [];
    document.querySelectorAll(selActif()).forEach(sess => {
      const attendu = new Set();
      sess.querySelectorAll('.station').forEach(st =>
        (carte[st.getAttribute('data-ex')] || []).forEach(m => attendu.add(m)));
      const style = sess.querySelector('.sh-mus').getAttribute('style') || '';
      const pose = new Set([...style.matchAll(/--m-([A-Za-z]+)/g)].map(m => m[1]));
      const rate = [...attendu].filter(m => !pose.has(m));
      const trop = [...pose].filter(m => !attendu.has(m));
      if (rate.length || trop.length)
        out.push(sess.id + ' manque:' + rate.join('/') + ' trop:' + trop.join('/'));
    });
    return out;
  });
  check('chaque séance allume exactement les muscles de ses machines',
    peint.length === 0, peint.join(' · ') || 'toutes concordent');

  /* Et le moteur doit se distinguer de l'assistant, sinon la carte ment
     sur ce qui travaille vraiment. */
  const nuance = await p.evaluate(() => {
    const st = document.querySelector('#' + SESSIONS[0] + ' .sh-mus').getAttribute('style') || '';
    return { moteur: /--mus-moteur/.test(st), assist: /--mus-assist/.test(st) };
  });
  check('la carte distingue le moteur de l\'assistant',
    nuance.moteur && nuance.assist, JSON.stringify(nuance));

  // ---------- plus d'emoji dans la structure ----------
  const emoji = await p.evaluate(() => {
    const zones = ['.bottomnav', '.sessbar', '#pickSheet .pick-grid', '#today .fatigue'];
    const re = /[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}]/u;
    const out = [];
    zones.forEach(z => document.querySelectorAll(z + ' *').forEach(e => {
      const t = [...e.childNodes].filter(n => n.nodeType === 3).map(n => n.textContent).join('');
      if (re.test(t)) out.push(z + ' → ' + t.trim().slice(0, 12));
    }));
    return out;
  });
  check('la structure n\'utilise plus d\'emoji', emoji.length === 0, emoji.join(' | '));

  // ---------- l'icône du coach suit le même jeu ----------
  await p.evaluate(() => { localStorage.clear(); });
  await p.reload({ waitUntil: 'domcontentloaded' }); await p.waitForTimeout(800);
  const coachIc = await p.evaluate(() => document.getElementById('coachIc').innerHTML);
  check('le coach porte son casque', /class="ic ic-coach"/.test(coachIc), coachIc.slice(0, 40));
  const glyphe = await p.evaluate(() => {
    const src = document.documentElement.innerHTML;
    return { etat: /poserCoachIc\(icEl, ic\)/.test(src), emojiEtat: /ic==='[^a-z]/.test(src) };
  });
  check('l\'état du coach ne transite plus par un emoji', glyphe.emojiEtat === false);
  const tons = await p.evaluate(() => {
    const el = document.getElementById('coachIc');
    const vus = [];
    /* l'état est un NOM, plus un glyphe : c'est ce découplage qui permet
       à l'icône d'être un tracé du sprite sans casser la coloration. */
    ['alerte', 'ok', 'rest', 'legs'].forEach(e => { poserCoachIc(el, e); vus.push(el.className); });
    updateCoach();
    return vus;
  });
  check('l\'état colore le cadre au lieu de changer le glyphe',
    JSON.stringify(tons) === JSON.stringify(['coach-ic t-alerte','coach-ic t-ok','coach-ic t-calme','coach-ic']),
    tons.join(' | '));

  // ---------- précache ----------
  const sw = fs.readFileSync(CTX.SW, 'utf8');
  const absents = [...ICONES.map(n => 'ic-' + n), 'fond-texture', 'fond-salle']
    .filter(n => !sw.includes('img/' + n + '.webp'));
  check('tous les assets sont précachés pour l\'offline', absents.length === 0, absents.join(', '));

  // ---------- hors ligne ----------
  await p.evaluate(() => navigator.serviceWorker.ready);
  await p.waitForTimeout(1500);
  await c.setOffline(true);
  await p.reload({ waitUntil: 'domcontentloaded' }).catch(() => {});
  await p.waitForTimeout(900);
  const horsLigne = await p.evaluate(async () => {
    const img = new Image(); img.src = 'img/ic-today.webp?offline=1';
    try { await img.decode(); return img.naturalWidth > 0; } catch (e) { return false; }
  });
  /* La carte est inline : hors ligne, elle est là si l'app est là. C'est
     précisément ce qu'on a gagné en quittant les fichiers. */
  const musOff = await p.evaluate(() => {
    const e = document.querySelector('.sess.show .sh-mus');
    return !!e && !!document.getElementById('mv-carte') &&
           e.getBoundingClientRect().width > 20;
  });
  check('les icônes restent disponibles hors ligne', horsLigne);
  check('la carte musculaire aussi, sans rien télécharger', musOff);
  await c.setOffline(false);

  check('aucune requête en échec', rates.filter(r => !/fonts\.googleapis|fonts\.gstatic/.test(r)).length === 0,
    rates.filter(r => !/fonts\./.test(r)).join(' | '));

  await br.close();
  console.log('\n=== PASS (' + ok.length + ') ===');
  ok.forEach(s => console.log('  ✓ ' + s));
  if (bad.length) { console.log('\n=== FAIL (' + bad.length + ') ==='); bad.forEach(s => console.log('  ✗ ' + s)); }
  process.exit(bad.length ? 1 : 0);
})().catch(e => { console.error('CRASH', e); process.exit(2); });
